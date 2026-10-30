// SmartAccess - porta de sala de aula
// Leitor RFID RC522 + 2 sensores HC-SR04 (entrada/saida) + servo (tranca) + LCD + WiFi + API (Python no Vercel)
//
// O Arduino so le os sensores e executa: quem pode abrir/fechar a sala e decidido na API.
//   - sala fechada: um cartao valido (dentro do horario do seu perfil) abre a porta e inicia a aula
//   - aula a decorrer: o dono da aula, um administrador ou um funcionario fecham a sala
//   - os 2 sensores contam as entradas e saidas (so com a aula a decorrer: trancada ninguem passa)
//
// Nada de identificacao fica no codigo: no 1.o arranque o Arduino regista-se na API pelo seu MAC,
// recebe uma chave unica e guarda-a na EEPROM. Um administrador aprova a porta na app e a partir
// dai a API envia a configuracao (nome da porta, cartoes que funcionam sem rede, intervalos).
// O mesmo codigo serve para qualquer porta: basta montar o hardware e preencher a rede WiFi.
#include <SPI.h>
#include <EEPROM.h>
#include <MFRC522.h>
#include <Servo.h>
#include <LiquidCrystal_I2C.h>
#if defined(ARDUINO_UNOR4_WIFI)
  #include <WiFiS3.h>       // UNO R4 WiFi
#else
  #include <WiFiNINA.h>     // UNO WiFi Rev2
#endif
#include <ArduinoHttpClient.h>
#include "arduino_secrets.h"   // so a rede WiFi e o endereco da API

// ---------------- Pinos ----------------
const int TRIG_A = 2;     // Sensor A - lado de FORA da porta
const int ECHO_A = 3;
const int TRIG_B = 4;     // Sensor B - lado de DENTRO da porta
const int ECHO_B = 5;
const int LED_VERDE = 6;
const int LED_VERMELHO = 7;
const int BUZZER = 8;
const int PINO_SERVO = 9;
const int RFID_SS = 10;   // SDA/SS do RC522
const int RFID_RST = 0;   // RST do RC522

// ---------------- Montagem (ajustar a cada porta) ----------------
const int LIMIAR_CM = 100;                          // limiar por omissao se a calibracao nao tiver eco
const int MARGEM_CM = 45;                           // ocupado = mais perto do que (referencia - margem)
const int LEITURAS_CONFIRMAR = 2;                   // leituras seguidas para mudar de estado (filtra ruido)
const unsigned long TEMPO_FIM_MS = 400;             // ambos livres durante isto = a pessoa ja passou
const unsigned long TIMEOUT_PASSAGEM_MS = 5000;     // 5 s sem nenhum sensor mudar = alguem parado na porta
const int ANGULO_TRANCADO = 0;
const int ANGULO_DESTRANCADO = 90;

// ---------------- Tempos internos ----------------
const unsigned long MENSAGEM_MS = 2500;             // tempo das mensagens temporarias no LCD
const unsigned long INTERVALO_WIFI_MS = 30000;      // espera entre tentativas de religar o WiFi
const unsigned long INTERVALO_RFID_MS = 10000;      // verificacao periodica do leitor RC522
const unsigned long INTERVALO_REGISTO_MS = 10000;   // enquanto espera aprovacao, pergunta a API de 10 em 10 s

// ---------------- EEPROM (256 bytes no Rev2) ----------------
const int EE_ASSINATURA = 0;     // 'S','A' = ha dados validos
const int EE_CHAVE = 2;          // 64 caracteres hexadecimais
const int EE_OFFLINE = 66;       // 1 byte com o tamanho + lista "UID1,UID2,..."
const int MAX_OFFLINE_TXT = 170;

// ---------------- Objetos ----------------
Servo tranca;
MFRC522 rfid(RFID_SS, RFID_RST);
LiquidCrystal_I2C lcd(0x27, 16, 2);   // 0x27 = endereco por omissao na maioria dos LCD I2C

WiFiSSLClient wifi;                     // o Vercel so aceita HTTPS
HttpClient http(wifi, API_HOST, 443);

// ---------------- Estado do dispositivo ----------------
enum EstadoDispositivo { SEM_REGISTO, PENDENTE, ATIVO, REVOGADO };
EstadoDispositivo estadoDispositivo = SEM_REGISTO;
char apiKey[65] = "";             // vem da API no registo; fica na EEPROM
String mac = "";
String nomePorta = "SmartAccess"; // vem da API (ex.: "Sala 1.04")
String cartoesOffline = "";       // vem da API: cartoes que abrem/fecham sem rede
unsigned long intervaloComandosMs = 5000;   // vem da API
bool configuracaoRecebida = false;
bool online = false;

unsigned long ultimaConsulta = 0;
unsigned long ultimaTentativaWiFi = 0;
unsigned long ultimaTentativaRegisto = 0;
unsigned long ultimaVerificacaoRfid = 0;

// ---------------- Estado da aula ----------------
bool aulaAberta = false;
String donoUid = "";              // cartao que abriu ("" = abertura remota)
String donoNome = "";
String donoPerfil = "";
int pessoasDentro = 0;
int entradasAula = 0;
int saidasAula = 0;
int naoDetetadasAula = 0;

// ---------------- Sensores ----------------
struct SensorPorta {
  char nome;
  int trig;
  int echo;
  int limiar;
  long distancia;
  bool ocupado;
  int contagem;      // leituras seguidas a contrariar o estado atual
};
SensorPorta sensorA = {'A', TRIG_A, ECHO_A, LIMIAR_CM, 999, false, 0};
SensorPorta sensorB = {'B', TRIG_B, ECHO_B, LIMIAR_CM, 999, false, 0};

// Sentido da passagem: segue-se o lado onde a pessoa esta (so o A tapado = fora, so o B = dentro)
//   de A para B = 1 ENTRADA, de B para A = 1 SAIDA
//   entrar e voltar logo a sair (A -> B -> A) = 1 entrada + 1 saida
//   chegar ao A e voltar para tras sem nunca ficar so no B = nao conta
char primeiroSensor = 0;     // 0 (sem passagem em curso), 'A' ou 'B'
char ladoAtual = 0;
const int MAX_TRAVESSIAS = 8;
char travessias[MAX_TRAVESSIAS];   // 'E' / 'S' por ordem; so se enviam no fim (o pedido a API bloqueia os sensores)
int numTravessias = 0;
unsigned long ultimaMudanca = 0;
unsigned long livreDesde = 0;

bool mostrarDistancias = false;   // 'D' no Monitor Serie liga/desliga
unsigned long ultimaImpressao = 0;
unsigned long mensagemEm = 0;     // quando o LCD mostrou uma mensagem temporaria (0 = nenhuma)

// ---------------- Eventos por enviar (sem rede) ----------------
// 'E' entrada, 'S' saida, 'A' sala aberta com o cartao uid, 'F' sala fechada
struct EventoPendente {
  char tipo;
  char uid[21];
};
const int MAX_PENDENTES = 16;
EventoPendente pendentes[MAX_PENDENTES];
int numPendentes = 0;

// ---------------- Prototipos ----------------
void registarLog(const char* tag, const String& mensagem);
void bip(unsigned int frequencia, unsigned long duracao);
void lerMemoria();
void guardarChave(const String& chave);
void guardarCartoesOffline(const String& lista);
void apagarChave();
bool prepararDispositivo();
bool registarDispositivo();
bool pedirConfiguracao();
bool problemaDispositivo(int estado, const String& resposta);
void tratarCartao(String uid);
void tratarSemRede(const String& uid);
void abrirSala(const String& uid, const String& nome, const String& perfil);
void fecharSala(const String& linha2);
void mostrarResumo(const String& resposta);
void acessoNegado(const String& linha2);
String textoPerfil(const String& perfil);
String textoMotivo(const String& motivo);
long medirDistancia(int trig, int echo);
void calibrarSensores();
bool atualizarSensor(SensorPorta& s);
void verificarPassagem();
void registarPassagem(const char* sentido);
bool enviarPassagem(const char* sentido);
void guardarPendente(char tipo, const String& uid);
void enviarPendentes();
void consultarComandos();
bool enviarEvento(const String& corpo);
bool ligarWiFi();
int pedidoApi(bool post, const char* caminho, const String& corpo, String& resposta);
long lerCampoNumero(const String& json, const char* campo);
String lerCampoTexto(const String& json, const char* campo);
void mostrar(const String& linha1, const String& linha2);
void mostrarTemporario(const String& linha1, const String& linha2);
void mostrarRepouso();
void mostrarAjuda();
void mostrarInfo();

// ======================================================
void setup() {
  Serial.begin(9600);
  Serial.setTimeout(50);              // o readString() esperava 1 s por cada comando escrito

  pinMode(TRIG_A, OUTPUT);
  pinMode(ECHO_A, INPUT);
  pinMode(TRIG_B, OUTPUT);
  pinMode(ECHO_B, INPUT);
  pinMode(LED_VERDE, OUTPUT);
  pinMode(LED_VERMELHO, OUTPUT);
  pinMode(BUZZER, OUTPUT);
  digitalWrite(BUZZER, LOW);

  tranca.attach(PINO_SERVO);
  tranca.write(ANGULO_TRANCADO);      // arranca sempre trancada; a API diz se havia uma aula a decorrer
  digitalWrite(LED_VERMELHO, HIGH);

  SPI.begin();
  rfid.PCD_Init();

  lcd.init();
  lcd.backlight();
  http.setHttpResponseTimeout(8000);
  http.connectionKeepAlive();         // reaproveita a ligacao HTTPS: so o 1.o pedido paga o handshake TLS (~1-2 s)

  Serial.println();
  Serial.println(F("=============================================="));
  Serial.println(F("  SmartAccess - controlo de acesso a salas"));
  Serial.println(F("=============================================="));
  mostrar("SmartAccess", "A iniciar...");

  lerMemoria();
  calibrarSensores();                 // a porta tem de estar livre durante o arranque
  ligarWiFi();
  prepararDispositivo();

  mostrarRepouso();
  mostrarAjuda();
}

// ======================================================
void loop() {
  // 1) Leitor RFID RC522
  if (rfid.PICC_IsNewCardPresent() && rfid.PICC_ReadCardSerial()) {
    String uid = "";
    for (byte i = 0; i < rfid.uid.size; i++) {
      if (rfid.uid.uidByte[i] < 0x10) uid += "0";
      uid += String(rfid.uid.uidByte[i], HEX);
    }
    uid.toUpperCase();
    rfid.PICC_HaltA();        // para de ler este cartao
    rfid.PCD_StopCrypto1();
    tratarCartao(uid);
  }

  // 2) Comandos escritos no Monitor Serie (testes)
  if (Serial.available()) {
    String texto = Serial.readString();
    texto.trim();
    texto.toUpperCase();
    if (texto == "E" || texto == "S") {
      if (!aulaAberta) registarLog("PASSAGEM", "Ignorada: a porta esta trancada, ninguem pode entrar nem sair");
      else registarPassagem(texto == "E" ? "entrada" : "saida");
    }
    else if (texto == "D") mostrarDistancias = !mostrarDistancias;
    else if (texto == "I") mostrarInfo();
    else if (texto == "?" || texto == "H") mostrarAjuda();
    else if (texto == "R") {
      registarLog("SISTEMA", "Registo apagado: o dispositivo vai registar-se de novo na API");
      apagarChave();
      prepararDispositivo();
    }
    else if (texto.length() > 0) tratarCartao(texto);
  }

  // 3) Contagem de entradas e saidas com os 2 sensores
  verificarPassagem();

  if (mostrarDistancias && millis() - ultimaImpressao >= 300) {
    ultimaImpressao = millis();
    Serial.print(F("  Sensor A: "));
    Serial.print(sensorA.distancia);
    Serial.print(sensorA.ocupado ? F(" cm OCUPADO  | Sensor B: ") : F(" cm livre    | Sensor B: "));
    Serial.print(sensorB.distancia);
    Serial.println(sensorB.ocupado ? F(" cm OCUPADO") : F(" cm livre"));
  }

  // Apaga as mensagens temporarias do LCD
  if (mensagemEm != 0 && millis() - mensagemEm >= MENSAGEM_MS) {
    mensagemEm = 0;
    mostrarRepouso();
  }

  // 4) Contacto com a API. Nao a meio de uma passagem: o pedido bloqueia o loop e os sensores deixariam de ser lidos
  if (primeiroSensor == 0) {
    if (estadoDispositivo == ATIVO && millis() - ultimaConsulta >= intervaloComandosMs) {
      consultarComandos();
    } else if (estadoDispositivo != ATIVO && millis() - ultimaTentativaRegisto >= INTERVALO_REGISTO_MS) {
      prepararDispositivo();
    }
  }

  // 5) O RC522 as vezes deixa de responder (ruido, mau contacto): se isso acontecer, reinicia-o
  if (millis() - ultimaVerificacaoRfid >= INTERVALO_RFID_MS) {
    ultimaVerificacaoRfid = millis();
    byte versao = rfid.PCD_ReadRegister(MFRC522::VersionReg);
    if (versao == 0x00 || versao == 0xFF) {
      registarLog("RFID", "Leitor sem resposta - a reiniciar");
      rfid.PCD_Init();
    }
  }

  delay(20);
}

// ======================================================
//  REGISTO NO MONITOR SERIE E BUZZER
// ======================================================
// "[00:12:34] CARTAO   texto"
void registarLog(const char* tag, const String& mensagem) {
  unsigned long s = millis() / 1000;
  char hora[12];
  snprintf(hora, sizeof(hora), "[%02lu:%02lu:%02lu]", s / 3600, (s / 60) % 60, s % 60);
  Serial.print(hora);
  Serial.print(' ');
  Serial.print(tag);
  for (int i = strlen(tag); i < 9; i++) Serial.print(' ');
  Serial.println(mensagem);
}

// O tone() do megaAVR para ao fim de N oscilacoes e deixa o pino como estiver.
// Se um tone comecar enquanto outro ainda toca, o pino pode ficar em HIGH e um buzzer ativo
// apita sem parar (acontecia ao fechar a sala com o cartao do dono: bip de leitura + bip de fecho seguidos).
// noTone() antes de cada tom garante que comeca e acaba em LOW.
void bip(unsigned int frequencia, unsigned long duracao) {
  noTone(BUZZER);
  tone(BUZZER, frequencia, duracao);
}

// ======================================================
//  REGISTO DO DISPOSITIVO NA API (chave unica por MAC)
// ======================================================
void lerMemoria() {
  apiKey[0] = '\0';
  cartoesOffline = "";
  if (EEPROM.read(EE_ASSINATURA) != 'S' || EEPROM.read(EE_ASSINATURA + 1) != 'A') {
    registarLog("SISTEMA", "Memoria vazia: primeiro arranque deste Arduino");
    return;
  }
  for (int i = 0; i < 64; i++) {
    char c = EEPROM.read(EE_CHAVE + i);
    if (!isxdigit(c)) {
      apiKey[0] = '\0';
      return;
    }
    apiKey[i] = c;
  }
  apiKey[64] = '\0';

  int tamanho = EEPROM.read(EE_OFFLINE);
  if (tamanho > MAX_OFFLINE_TXT) tamanho = 0;
  for (int i = 0; i < tamanho; i++) cartoesOffline += (char)EEPROM.read(EE_OFFLINE + 1 + i);
  estadoDispositivo = ATIVO;          // ate a API dizer o contrario (permite o modo sem rede logo no arranque)
  registarLog("SISTEMA", "Chave do dispositivo lida da memoria");
}

void guardarChave(const String& chave) {
  for (int i = 0; i < 64; i++) EEPROM.update(EE_CHAVE + i, chave[i]);
  EEPROM.update(EE_ASSINATURA, 'S');
  EEPROM.update(EE_ASSINATURA + 1, 'A');
  chave.toCharArray(apiKey, sizeof(apiKey));
}

// So escreve se mudou (a EEPROM aguenta ~100 000 escritas por byte)
void guardarCartoesOffline(const String& lista) {
  if (lista == cartoesOffline || (int)lista.length() > MAX_OFFLINE_TXT) return;
  cartoesOffline = lista;
  EEPROM.update(EE_OFFLINE, lista.length());
  for (unsigned int i = 0; i < lista.length(); i++) EEPROM.update(EE_OFFLINE + 1 + i, lista[i]);
}

void apagarChave() {
  EEPROM.update(EE_ASSINATURA, 0xFF);
  apiKey[0] = '\0';
  estadoDispositivo = SEM_REGISTO;
}

// Garante que o dispositivo tem chave e esta aprovado; devolve true se ficou ATIVO
bool prepararDispositivo() {
  ultimaTentativaRegisto = millis();
  if (WiFi.status() != WL_CONNECTED && !ligarWiFi()) return false;
  if (apiKey[0] == '\0' && !registarDispositivo()) return false;
  return pedirConfiguracao();
}

// POST /api/device/register {"mac"} -> {"api_key", "nome", "estado": "pendente"}
bool registarDispositivo() {
  registarLog("API", "A registar este Arduino (MAC " + mac + ")...");
  mostrar("A registar...", mac);
  String resposta;
  int estado = pedidoApi(true, "/api/device/register", "{\"mac\":\"" + mac + "\"}", resposta);
  String chave = lerCampoTexto(resposta, "api_key");
  if (estado != 201 || chave.length() != 64) {
    registarLog("API", String("Falha no registo (codigo ") + estado + "): " + resposta);
    mostrarTemporario("Falha no registo", String("Codigo ") + estado);
    return false;
  }
  guardarChave(chave);
  nomePorta = lerCampoTexto(resposta, "nome");
  estadoDispositivo = PENDENTE;
  registarLog("API", "Registado como " + nomePorta + ". Chave guardada na memoria.");
  registarLog("API", "Um administrador tem de aprovar esta porta na app (Dispositivos)");
  mostrarRepouso();
  return true;
}

// GET /api/device/config -> {"nome", "intervalo_comandos_s", "cartoes_offline", "pessoas_dentro", "aula"}
bool pedirConfiguracao() {
  String resposta;
  int estado = pedidoApi(false, "/api/device/config", "", resposta);
  online = estado > 0 && estado < 500;
  if (problemaDispositivo(estado, resposta)) return false;
  if (estado != 200) {
    registarLog("API", String("Sem configuracao (codigo ") + estado + ") - a usar a guardada na memoria");
    mostrarRepouso();
    return false;
  }

  bool primeiraVez = !configuracaoRecebida;
  configuracaoRecebida = true;
  estadoDispositivo = ATIVO;
  nomePorta = lerCampoTexto(resposta, "nome");
  long intervalo = lerCampoNumero(resposta, "intervalo_comandos_s");
  if (intervalo > 0) intervaloComandosMs = intervalo * 1000UL;
  guardarCartoesOffline(lerCampoTexto(resposta, "cartoes_offline"));
  long dentro = lerCampoNumero(resposta, "pessoas_dentro");
  if (dentro >= 0) pessoasDentro = dentro;

  if (primeiraVez) {
    registarLog("API", "Porta ativa: " + nomePorta);
    int n = cartoesOffline.length() == 0 ? 0 : 1;
    for (unsigned int i = 0; i < cartoesOffline.length(); i++) if (cartoesOffline[i] == ',') n++;
    registarLog("API", String("Cartoes que funcionam sem rede: ") + n + " | contacto a cada " + (intervaloComandosMs / 1000) + " s");
  }

  // Havia uma aula a decorrer (ex.: o Arduino reiniciou a meio): retoma-a
  if (resposta.indexOf("\"aula\":null") < 0 && !aulaAberta) {
    registarLog("AULA", "A retomar a aula de " + lerCampoTexto(resposta, "dono") + " que estava a decorrer");
    abrirSala(lerCampoTexto(resposta, "cartao_uid"), lerCampoTexto(resposta, "dono"),
              lerCampoTexto(resposta, "dono_perfil"));
    entradasAula = lerCampoNumero(resposta, "entradas");
    saidasAula = lerCampoNumero(resposta, "saidas");
  }
  ultimaConsulta = millis();
  if (mensagemEm == 0) mostrarRepouso();
  return true;
}

// Trata as respostas que dizem respeito ao proprio dispositivo (chave invalida, pendente, revogado).
// Devolve true se a resposta era um desses casos.
bool problemaDispositivo(int estado, const String& resposta) {
  if (estado == 401) {
    registarLog("API", "Chave recusada pela API - o dispositivo vai registar-se de novo");
    apagarChave();
    return true;
  }
  if (estado == 403 && resposta.indexOf("\"detail\"") >= 0) {
    EstadoDispositivo novo = resposta.indexOf("revogado") >= 0 ? REVOGADO : PENDENTE;
    if (novo != estadoDispositivo) {
      registarLog("API", novo == REVOGADO ? "Esta porta foi REVOGADA na app" :
                                           "A aguardar que um administrador aprove esta porta (" + nomePorta + ")");
    }
    estadoDispositivo = novo;
    if (mensagemEm == 0) mostrarRepouso();
    return true;
  }
  return false;
}

// ======================================================
//  CARTOES E AULAS
// ======================================================
void tratarCartao(String uid) {
  bip(2000, 40);                      // bip curto: o cartao foi lido, pode tirar-se do leitor
  registarLog("CARTAO", "Lido " + uid);

  if (estadoDispositivo == PENDENTE || estadoDispositivo == REVOGADO) {
    registarLog("CARTAO", estadoDispositivo == PENDENTE ? "Ignorado: porta ainda nao aprovada na app"
                                                         : "Ignorado: porta revogada na app");
    acessoNegado(estadoDispositivo == PENDENTE ? "Porta por aprovar" : "Porta revogada");
    return;
  }

  mostrar("A verificar...", uid);
  String resposta;
  int estado = pedidoApi(true, "/api/access", "{\"uid\":\"" + uid + "\"}", resposta);
  if (problemaDispositivo(estado, resposta)) {
    acessoNegado("Erro na porta");
    return;
  }
  if (estado < 0 || estado >= 500) {
    online = false;
    registarLog("API", String("Sem ligacao (codigo ") + estado + ") - modo sem rede");
    tratarSemRede(uid);
    return;
  }
  online = true;

  String nome = lerCampoTexto(resposta, "nome");
  String perfil = lerCampoTexto(resposta, "perfil");

  if (estado == 200 && lerCampoTexto(resposta, "acao") == "abrir") {
    abrirSala(uid, nome, perfil);   // a API ja registou a aula
    return;
  }
  if (estado == 200 && lerCampoTexto(resposta, "acao") == "fechar") {
    String motivo = lerCampoTexto(resposta, "motivo_fim");
    if (motivo == "dono") {
      registarLog("AULA", "Terminada pelo " + textoPerfil(perfil) + " " + nome + " (dono da aula)");
    } else {
      registarLog("AULA", "Fechada pelo " + textoPerfil(perfil) + " " + nome + " (aula de " + donoNome + ")");
    }
    fecharSala(motivo == "dono" ? "Ate a proxima!" : (perfil == "admin" ? "Fechada p/ admin" : "Fechada p/ func."));
    mostrarResumo(resposta);
    return;
  }
  if (estado == 403) {
    String motivo = lerCampoTexto(resposta, "motivo");
    if (motivo == "aula_a_decorrer") {
      String dono = lerCampoTexto(resposta, "dono");
      registarLog("CARTAO", nome + " (" + textoPerfil(perfil) + ") nao pode fechar a aula de " + dono +
                            ": so o dono, um administrador ou um funcionario");
      bip(300, 150);
      mostrarTemporario("Aula a decorrer", "So o dono fecha");
    } else {
      registarLog("CARTAO", "NEGADO: " + textoMotivo(motivo) + (nome.length() ? " (" + nome + ")" : ""));
      acessoNegado(textoMotivo(motivo));
    }
    return;
  }
  registarLog("API", String("Resposta inesperada (codigo ") + estado + "): " + resposta);
  acessoNegado(String("Erro API ") + estado);
}

// Sem rede: so os cartoes que a API marcou como "offline" (admin/funcionario) abrem e fecham.
// O dono de uma aula aberta tambem a pode fechar. Tudo fica guardado e e enviado quando a rede voltar.
void tratarSemRede(const String& uid) {
  bool cartaoOffline = cartoesOffline.length() > 0 && ("," + cartoesOffline + ",").indexOf("," + uid + ",") >= 0;

  if (aulaAberta && (uid == donoUid || cartaoOffline)) {
    registarLog("AULA", "Fechada sem rede (fica registado quando a ligacao voltar)");
    fecharSala("Sem rede");
    guardarPendente('F', "");
  } else if (!aulaAberta && cartaoOffline) {
    abrirSala(uid, "Cartao offline", "");
    registarLog("AULA", "Aberta sem rede com um cartao autorizado offline");
    guardarPendente('A', uid);
  } else {
    registarLog("CARTAO", "NEGADO: sem ligacao a API e o cartao nao esta autorizado offline");
    acessoNegado("Sem rede");
  }
}

// Inicio da aula: destranca e a porta fica aberta ate o dono (ou um admin/funcionario) a fechar
void abrirSala(const String& uid, const String& nome, const String& perfil) {
  aulaAberta = true;
  donoUid = uid;
  donoNome = nome.length() ? nome : "remoto";
  donoPerfil = perfil;
  entradasAula = 0;
  saidasAula = 0;
  naoDetetadasAula = 0;

  tranca.write(ANGULO_DESTRANCADO);
  digitalWrite(LED_VERMELHO, LOW);
  digitalWrite(LED_VERDE, HIGH);
  bip(1000, 200);

  registarLog("AULA", "INICIADA por " + (perfil.length() ? textoPerfil(perfil) + " " : String("")) + donoNome +
                      " | porta destrancada | dentro: " + pessoasDentro);
  mostrarTemporario(perfil == "admin" ? "Aula (admin)" : (perfil == "funcionario" ? "Sala aberta" : "Aula iniciada"),
                    donoNome);
}

void fecharSala(const String& linha2) {
  aulaAberta = false;
  tranca.write(ANGULO_TRANCADO);
  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_VERMELHO, HIGH);
  bip(800, 300);
  registarLog("AULA", "Porta TRANCADA");
  mostrarTemporario("Sala fechada", linha2);
  donoUid = "";
  donoNome = "";
  donoPerfil = "";
}

// Resumo enviado pela API ao fechar: {"aula": {"duracao_min", "entradas", "saidas", "entradas_nao_detetadas"}}
void mostrarResumo(const String& resposta) {
  long entradas = lerCampoNumero(resposta, "entradas");
  long saidas = lerCampoNumero(resposta, "saidas");
  long naoDetetadas = lerCampoNumero(resposta, "entradas_nao_detetadas");
  long duracao = lerCampoNumero(resposta, "duracao_min");
  Serial.println(F("  +------------- Resumo da aula -------------+"));
  Serial.print(F("  | Duracao: "));
  Serial.print(duracao);
  Serial.println(F(" min"));
  Serial.print(F("  | Entradas: "));
  Serial.print(entradas);
  Serial.print(F("   Saidas: "));
  Serial.println(saidas);
  Serial.print(F("  | Ainda dentro: "));
  Serial.println(pessoasDentro);
  if (naoDetetadas > 0) {
    Serial.print(F("  | Entradas nao detetadas pelos sensores: "));
    Serial.println(naoDetetadas);
  }
  Serial.println(F("  +------------------------------------------+"));
}

void acessoNegado(const String& linha2) {
  mostrar("Acesso negado", linha2);
  for (int i = 0; i < 3; i++) {          // LED vermelho a piscar + bip grave
    digitalWrite(LED_VERMELHO, LOW);
    bip(300, 150);
    delay(200);
    digitalWrite(LED_VERMELHO, !aulaAberta);
    delay(200);
  }
  mensagemEm = millis();
}

String textoPerfil(const String& perfil) {
  if (perfil == "admin") return "administrador";
  if (perfil == "funcionario") return "funcionario";
  if (perfil == "docente") return "docente";
  return "utilizador";
}

// Mensagens curtas (16 caracteres) para o LCD
String textoMotivo(const String& motivo) {
  if (motivo == "cartao_desconhecido") return "Cartao novo";
  if (motivo == "cartao_pendente") return "Por aprovar";
  if (motivo == "cartao_revogado") return "Cartao revogado";
  if (motivo == "fora_do_horario") return "Fora do horario";
  return motivo;
}

// ======================================================
//  CONTAGEM COM 2 SENSORES (A = fora, B = dentro)
// ======================================================
long medirDistancia(int trig, int echo) {
  digitalWrite(trig, LOW);
  delayMicroseconds(2);
  digitalWrite(trig, HIGH);
  delayMicroseconds(10);
  digitalWrite(trig, LOW);
  long duracao = pulseIn(echo, HIGH, 12000);   // timeout ~2 m (so interessa < LIMIAR_CM)
  if (duracao == 0) return 999;                 // sem eco = nada a frente
  return duracao / 58;                          // conversao para cm
}

// Mede a distancia de cada sensor com a porta livre e define o limiar a partir dela
// (ex.: sensores a apontar para a ombreira em frente, a 80 cm -> ocupado abaixo de 80 - MARGEM_CM)
void calibrarSensores() {
  mostrar("A calibrar...", "Porta livre!");
  SensorPorta* sensores[] = {&sensorA, &sensorB};
  for (SensorPorta* s : sensores) {
    long soma = 0;
    int validas = 0;
    for (int i = 0; i < 10; i++) {
      long d = medirDistancia(s->trig, s->echo);
      if (d < 999) {
        soma += d;
        validas++;
      }
      delay(60);
    }
    // Sem eco (nada em frente ate ~2 m) fica o limiar por omissao
    if (validas >= 5) s->limiar = max(10L, soma / validas - MARGEM_CM);

    String texto = String("Sensor ") + s->nome + (s->nome == 'A' ? " (fora): " : " (dentro): ");
    if (validas >= 5) texto += String("referencia ") + (soma / validas) + " cm, ";
    else texto += "sem eco, ";
    texto += String("ocupado abaixo de ") + s->limiar + " cm";
    registarLog("SENSOR", texto);
  }
}

// Le o sensor e so muda de estado depois de LEITURAS_CONFIRMAR leituras seguidas
// (um eco falso isolado nao conta). Devolve true se o estado mudou.
bool atualizarSensor(SensorPorta& s) {
  s.distancia = medirDistancia(s.trig, s.echo);
  bool perto = s.distancia < s.limiar;
  if (perto == s.ocupado) {
    s.contagem = 0;
    return false;
  }
  if (++s.contagem < LEITURAS_CONFIRMAR) return false;
  s.ocupado = perto;
  s.contagem = 0;
  return true;
}

void verificarPassagem() {
  bool mudouA = atualizarSensor(sensorA);
  delay(10);                                    // evita que o eco de um sensor chegue ao outro
  bool mudouB = atualizarSensor(sensorB);
  bool a = sensorA.ocupado;
  bool b = sensorB.ocupado;
  unsigned long agora = millis();

  // Porta trancada: ninguem consegue entrar nem sair, o que os sensores virem e ruido
  // (ex.: alguem a passar no corredor em frente ao sensor A)
  if (!aulaAberta) {
    primeiroSensor = 0;
    return;
  }

  // 1) Inicio: so comeca quando um dos lados e tapado antes do outro
  if (primeiroSensor == 0) {
    if (a != b) {
      primeiroSensor = a ? 'A' : 'B';
      ladoAtual = primeiroSensor;
      numTravessias = 0;
      ultimaMudanca = agora;
      livreDesde = 0;
    }
    return;
  }
  if (mudouA || mudouB) ultimaMudanca = agora;

  // 2) Durante: sempre que a pessoa fica so no sensor do outro lado, atravessou a porta
  if (a != b) {
    char lado = a ? 'A' : 'B';
    if (lado != ladoAtual) {
      if (numTravessias < MAX_TRAVESSIAS) travessias[numTravessias++] = (lado == 'B') ? 'E' : 'S';
      ladoAtual = lado;
    }
  }

  // 3) Fim: os dois livres durante TEMPO_FIM_MS (tolera o espaco entre os sensores).
  //    Se ninguem se mexer durante TIMEOUT_PASSAGEM_MS (alguem parado na porta), fecha a passagem na mesma.
  bool fim = false;
  if (a || b) {
    livreDesde = 0;
  } else if (livreDesde == 0) {
    livreDesde = agora;
  } else if (agora - livreDesde >= TEMPO_FIM_MS) {
    fim = true;
  }
  if (!fim && agora - ultimaMudanca > TIMEOUT_PASSAGEM_MS) {
    registarLog("PASSAGEM", "Alguem parado na porta - a passagem foi fechada");
    fim = true;
  }
  if (!fim) return;

  primeiroSensor = 0;
  if (numTravessias == 0) {
    registarLog("PASSAGEM", "Ignorada: a pessoa voltou para tras");
    return;
  }
  for (int i = 0; i < numTravessias; i++) {
    registarPassagem(travessias[i] == 'E' ? "entrada" : "saida");
  }
}

void registarPassagem(const char* sentido) {
  bool entrada = strcmp(sentido, "entrada") == 0;
  // Saida com a sala vazia: a pessoa sai na mesma, mas a entrada dela escapou aos sensores
  bool naoDetetada = !entrada && pessoasDentro == 0;
  if (entrada) {
    pessoasDentro++;
    entradasAula++;
  } else {
    if (pessoasDentro > 0) pessoasDentro--;
    saidasAula++;
    if (naoDetetada) naoDetetadasAula++;
  }
  bip(entrada ? 1500 : 900, 80);              // agudo = entrada, grave = saida
  mostrarTemporario(entrada ? "Entrou 1 pessoa" : "Saiu 1 pessoa", String("Dentro: ") + pessoasDentro);

  // A API tem a contagem oficial (atualiza pessoasDentro, entradasAula e saidasAula)
  if (!enviarPassagem(sentido)) guardarPendente(entrada ? 'E' : 'S', "");

  if (naoDetetada) {
    registarLog("ALERTA", "Saida com 0 pessoas dentro: houve 1 entrada que os sensores nao detetaram");
  }
  registarLog("PASSAGEM", String(entrada ? "ENTRADA" : "SAIDA  ") + " | dentro: " + pessoasDentro +
                          " | aula: " + entradasAula + " entradas, " + saidasAula + " saidas" +
                          (online ? "" : " (por enviar)"));
}

// POST /api/device/events -> {"pessoas_dentro", "autorizada", "entrada_nao_detetada", "aula": {"entradas","saidas"}}
bool enviarPassagem(const char* sentido) {
  String resposta;
  int estado = pedidoApi(true, "/api/device/events",
                         String("{\"tipo\":\"passagem\",\"sentido\":\"") + sentido + "\"}", resposta);
  if (estado != 200) {
    online = estado > 0 && estado < 500;
    problemaDispositivo(estado, resposta);
    return false;
  }
  online = true;
  long valor = lerCampoNumero(resposta, "pessoas_dentro");
  if (valor >= 0) pessoasDentro = valor;
  valor = lerCampoNumero(resposta, "entradas");
  if (valor >= 0) entradasAula = valor;
  valor = lerCampoNumero(resposta, "saidas");
  if (valor >= 0) saidasAula = valor;

  if (resposta.indexOf("\"autorizada\":false") >= 0) {
    registarLog("ALERTA", "Entrada sem aula a decorrer (a API criou um alerta na app)");
    mostrarTemporario("ALERTA", "Entrada s/ acesso");
    for (int i = 0; i < 3; i++) {
      bip(2000, 300);
      delay(400);
    }
  }
  return true;
}

void guardarPendente(char tipo, const String& uid) {
  if (numPendentes >= MAX_PENDENTES) {
    registarLog("ALERTA", "Memoria de eventos sem rede cheia - evento perdido");
    return;
  }
  pendentes[numPendentes].tipo = tipo;
  uid.toCharArray(pendentes[numPendentes].uid, sizeof(pendentes[numPendentes].uid));
  numPendentes++;
}

// Envia por ordem o que ficou guardado enquanto nao havia rede (fica com a hora do envio)
void enviarPendentes() {
  int enviados = 0;
  while (enviados < numPendentes) {
    EventoPendente& e = pendentes[enviados];
    bool ok;
    if (e.tipo == 'E' || e.tipo == 'S') ok = enviarPassagem(e.tipo == 'E' ? "entrada" : "saida");
    else if (e.tipo == 'A') ok = enviarEvento(String("{\"tipo\":\"porta\",\"aberta\":true,\"uid\":\"") + e.uid + "\"}");
    else ok = enviarEvento("{\"tipo\":\"porta\",\"aberta\":false}");
    if (!ok) break;
    enviados++;
  }
  for (int i = enviados; i < numPendentes; i++) pendentes[i - enviados] = pendentes[i];
  numPendentes -= enviados;
  if (enviados > 0) registarLog("API", String(enviados) + " eventos guardados sem rede foram enviados");
}

// ======================================================
//  COMANDOS REMOTOS E EVENTOS
// ======================================================
// GET /api/device/commands -> {"comandos":[{"id":5,"tipo":"abrir"}]}  (serve tambem de heartbeat)
void consultarComandos() {
  ultimaConsulta = millis();
  bool estavaOnline = online;

  String resposta;
  int estado = pedidoApi(false, "/api/device/commands", "", resposta);
  if (problemaDispositivo(estado, resposta)) return;
  online = estado == 200;
  if (online != estavaOnline) {
    registarLog("API", online ? "Ligacao a API restabelecida" : String("Sem ligacao a API (codigo ") + estado + ")");
    if (mensagemEm == 0) mostrarRepouso();
    if (online) pedirConfiguracao();   // pode ter mudado alguma coisa enquanto estava sem rede
  }
  if (!online) return;

  if (numPendentes > 0) enviarPendentes();

  long comandoId = lerCampoNumero(resposta, "id");
  if (comandoId > 0) {
    registarLog("API", String("Abertura remota pedida na app (comando ") + comandoId + ")");
    String confirmacao;
    pedidoApi(true, "/api/device/events",
              "{\"tipo\":\"comando_executado\",\"comando_id\":" + String(comandoId) + "}", confirmacao);
    if (!aulaAberta) {
      abrirSala("", lerCampoTexto(confirmacao, "dono"), lerCampoTexto(confirmacao, "dono_perfil"));
    }
  }
}

bool enviarEvento(const String& corpo) {
  String resposta;
  int estado = pedidoApi(true, "/api/device/events", corpo, resposta);
  if (estado != 200) {
    problemaDispositivo(estado, resposta);
    return false;
  }
  return true;
}

// ======================================================
//  WIFI E API
// ======================================================
// Tenta ligar durante ~10 s; se falhar, so volta a tentar passado INTERVALO_WIFI_MS
bool ligarWiFi() {
  if (ultimaTentativaWiFi != 0 && millis() - ultimaTentativaWiFi < INTERVALO_WIFI_MS) return false;
  ultimaTentativaWiFi = millis();

  mostrar("A ligar WiFi...", SECRET_SSID);
  registarLog("WIFI", String("A ligar a ") + SECRET_SSID + "...");
  WiFi.begin(SECRET_SSID, SECRET_PASS);
  for (int i = 0; i < 20 && WiFi.status() != WL_CONNECTED; i++) delay(500);

  // O MAC identifica este Arduino na API (os bytes vem do fim para o inicio)
  byte b[6];
  WiFi.macAddress(b);
  char texto[18];
  snprintf(texto, sizeof(texto), "%02X:%02X:%02X:%02X:%02X:%02X", b[5], b[4], b[3], b[2], b[1], b[0]);
  mac = texto;

  bool ligado = WiFi.status() == WL_CONNECTED;
  if (ligado) {
    IPAddress ip = WiFi.localIP();
    registarLog("WIFI", String("Ligado | IP ") + ip[0] + "." + ip[1] + "." + ip[2] + "." + ip[3] +
                        " | sinal " + WiFi.RSSI() + " dBm | MAC " + mac);
  } else {
    registarLog("WIFI", "Rede indisponivel - a funcionar sem rede");
  }
  mostrarRepouso();
  return ligado;
}

// Faz um GET ou POST (JSON) a API; devolve o codigo HTTP (negativo = erro de rede)
// A ligacao fica aberta entre pedidos. Se o servidor a tiver fechado entretanto,
// o pedido falha: fecha-se do nosso lado e tenta-se uma segunda vez com uma ligacao nova.
int pedidoApi(bool post, const char* caminho, const String& corpo, String& resposta) {
  int estado = -1;
  for (int tentativa = 0; tentativa < 2; tentativa++) {
    resposta = "";
    if (WiFi.status() != WL_CONNECTED && !ligarWiFi()) return -1;

    http.beginRequest();
    if (post) http.post(caminho);
    else http.get(caminho);
    if (apiKey[0] != '\0') http.sendHeader("x-api-key", apiKey);
    if (post) {
      http.sendHeader("Content-Type", "application/json");
      http.sendHeader("Content-Length", corpo.length());
      http.beginBody();
      http.print(corpo);
    }
    http.endRequest();

    estado = http.responseStatusCode();
    if (estado > 0) {
      resposta = http.responseBody();   // ler a resposta toda deixa a ligacao pronta para o proximo pedido
      return estado;
    }
    http.stop();
  }
  return estado;
}

// Le um numero de um JSON simples, ex.: {"comando_id":12} -> 12 (ou -1 se nao existir)
long lerCampoNumero(const String& json, const char* campo) {
  String chave = String("\"") + campo + "\":";
  int pos = json.indexOf(chave);
  if (pos < 0) return -1;
  return json.substring(pos + chave.length()).toInt();
}

// Le um texto de um JSON simples, ex.: {"nome":"Ana"} -> "Ana" (ou "" se nao existir ou for null)
String lerCampoTexto(const String& json, const char* campo) {
  String chave = String("\"") + campo + "\":\"";
  int inicio = json.indexOf(chave);
  if (inicio < 0) return "";
  inicio += chave.length();
  int fim = json.indexOf('"', inicio);
  return fim < 0 ? "" : json.substring(inicio, fim);
}

// ======================================================
//  LCD E MONITOR SERIE
// ======================================================
void mostrar(const String& linha1, const String& linha2) {
  lcd.clear();
  lcd.setCursor(0, 0); lcd.print(linha1.substring(0, 16));
  lcd.setCursor(0, 1); lcd.print(linha2.substring(0, 16));
}

// Mensagem que desaparece sozinha passados MENSAGEM_MS (ver loop)
void mostrarTemporario(const String& linha1, const String& linha2) {
  mostrar(linha1, linha2);
  mensagemEm = millis();
}

void mostrarRepouso() {
  if (estadoDispositivo == SEM_REGISTO) {
    mostrar("Porta sem registo", WiFi.status() == WL_CONNECTED ? "A registar..." : "Sem rede");
  } else if (estadoDispositivo == PENDENTE) {
    mostrar("Aguarda aprovac.", nomePorta);
  } else if (estadoDispositivo == REVOGADO) {
    mostrar("Porta revogada", "Contacte o admin");
  } else if (aulaAberta) {
    String linha2 = String("Dentro: ") + pessoasDentro;
    if (!online) linha2 += " OFF";
    mostrar("Aula: " + donoNome, linha2);
  } else {
    mostrar(nomePorta, online ? "Passe o cartao" : "Sem rede");
  }
}

void mostrarAjuda() {
  Serial.println(F("----------------------------------------------"));
  Serial.println(F(" Comandos no Monitor Serie:"));
  Serial.println(F("   <UID>  simula um cartao (ex.: 8DF36E69)"));
  Serial.println(F("   E / S  simula uma entrada / saida"));
  Serial.println(F("   D      mostra as distancias dos sensores"));
  Serial.println(F("   I      estado da porta e da aula"));
  Serial.println(F("   R      apaga o registo e regista de novo"));
  Serial.println(F("   ?      esta ajuda"));
  Serial.println(F("----------------------------------------------"));
}

void mostrarInfo() {
  const char* estados[] = {"sem registo", "a aguardar aprovacao", "ativa", "revogada"};
  Serial.println(F("------------------ Estado --------------------"));
  Serial.println("  Porta:        " + nomePorta + " (" + estados[estadoDispositivo] + ")");
  Serial.println("  MAC:          " + mac);
  Serial.println(String("  API:          ") + API_HOST + (online ? " (ligada)" : " (sem ligacao)"));
  Serial.println(String("  Aula:         ") + (aulaAberta ? "a decorrer - " + donoNome : "nenhuma (porta trancada)"));
  Serial.println(String("  Dentro:       ") + pessoasDentro + " pessoas");
  if (aulaAberta) Serial.println(String("  Nesta aula:   ") + entradasAula + " entradas, " + saidasAula + " saidas");
  Serial.println(String("  Sem rede:     ") + (cartoesOffline.length() ? cartoesOffline : "nenhum cartao autorizado"));
  Serial.println(String("  Por enviar:   ") + numPendentes + " eventos");
  Serial.println(F("----------------------------------------------"));
}
