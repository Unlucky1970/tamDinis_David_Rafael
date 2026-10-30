// SmartAccess - leitor RFID RC522 + 2 sensores HC-SR04 (entrada/saida) + WiFi + API (Python no Vercel)
// O Arduino so le os sensores e executa: a decisao de abrir a porta e tomada na API.
//
// Funcionamento por aula:
//   - sala fechada: um cartao com acesso abre a porta e inicia a aula (a porta fica aberta)
//   - aula a decorrer: o mesmo cartao (docente) ou um funcionario (perfil admin) fecha a sala;
//     qualquer outro cartao so ve "Aula a decorrer"
#include <SPI.h>
#include <MFRC522.h>
#include <Servo.h>
#include <LiquidCrystal_I2C.h>
#if defined(ARDUINO_UNOR4_WIFI)
  #include <WiFiS3.h>       // UNO R4 WiFi
#else
  #include <WiFiNINA.h>     // UNO WiFi Rev2
#endif
#include <ArduinoHttpClient.h>
#include "arduino_secrets.h"   // rede WiFi, endereco da API, chave e cartao mestre

// Prototipos das funcoes
void tratarCartao(String uid);
String lerUID();
String textoMotivo(const String& motivo);
void abrirPorta(String dono, String linha1, String linha2);
void fecharPorta(String linha2);
void acessoNegado(String motivo);
long medirDistancia(int trig, int echo);
void calibrarSensores();
void verificarPassagem();
void registarPassagem(const char* sentido);
bool enviarPassagem(const char* sentido);
void enviarPassagensPendentes();
void consultarComandos();
void enviarEvento(const String& corpo);
void mostrar(String linha1, String linha2);
void mostrarRepouso();
bool ligarWiFi();
int pedidoApi(bool post, const char* caminho, const String& corpo, String& resposta);
long lerCampoNumero(const String& json, const char* campo);
String lerCampoTexto(const String& json, const char* campo);

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

// ---------------- Configuracao ----------------
const int LIMIAR_CM = 100;                          // limiar por omissao se a calibracao nao tiver eco
const int MARGEM_CM = 45;                           // ocupado = mais perto do que (referencia - margem)
const int LEITURAS_CONFIRMAR = 2;                   // leituras seguidas para mudar de estado (filtra ruido)
const unsigned long TEMPO_FIM_MS = 400;             // ambos livres durante isto = a pessoa ja passou
const unsigned long TIMEOUT_PASSAGEM_MS = 5000;     // atravessar os 2 sensores demora 1-2 s; mais do que 5 s = alguem parado
const unsigned long INTERVALO_COMANDOS_MS = 5000;   // consulta de comandos remotos (e heartbeat)
const unsigned long MENSAGEM_MS = 2000;             // tempo das mensagens "Entrou"/"Saiu" no LCD
const unsigned long INTERVALO_WIFI_MS = 30000;      // espera entre tentativas de religar o WiFi
const unsigned long INTERVALO_RFID_MS = 10000;      // verificacao periodica do leitor RC522
const int ANGULO_TRANCADO = 0;
const int ANGULO_DESTRANCADO = 90;
const int MAX_PENDENTES = 20;                       // passagens guardadas enquanto nao ha rede

// ---------------- Estado ----------------
Servo tranca;
MFRC522 rfid(RFID_SS, RFID_RST);
LiquidCrystal_I2C lcd(0x27, 16, 2);   // 0x27 = endereco por omissao no Wokwi e na maioria dos LCD reais

WiFiSSLClient wifi;                       // o Vercel so aceita HTTPS
HttpClient http(wifi, API_HOST, 443);

bool online = false;
bool portaAberta = false;    // true = aula a decorrer
String donoAula = "";        // UID do cartao que abriu a sala ("" = abertura remota)
int entradasAula = 0;        // contagem da aula atual (docente + alunos)
int saidasAula = 0;
unsigned long ultimaConsulta = 0;
unsigned long ultimaTentativaWiFi = 0;
unsigned long ultimaVerificacaoRfid = 0;
int pessoasDentro = 0;

// Cada HC-SR04 com o seu limiar (calibrado no arranque) e filtro de leituras
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

// Sentido da passagem:
//   ENTRADA = o primeiro sensor tapado e o A (fora) e o ultimo a ficar livre e o B (dentro)
//   SAIDA   = o primeiro e o B e o ultimo e o A
//   se o primeiro e o ultimo sao o mesmo sensor, a pessoa voltou para tras e nao conta
char primeiroSensor = 0;     // 0 (sem passagem), 'A' ou 'B'
char ultimoSensor = 0;       // ultimo sensor que deixou de ver a pessoa
unsigned long inicioPassagem = 0;
unsigned long livreDesde = 0;

bool mostrarDistancias = false;   // 'D' no Monitor Serie liga/desliga
unsigned long ultimaImpressao = 0;
unsigned long mensagemEm = 0;     // quando o LCD mostrou "Entrou"/"Saiu" (0 = nenhuma)

bool atualizarSensor(SensorPorta& s);

// Passagens que falharam por falta de rede ('E' = entrada, 'S' = saida)
char pendentes[MAX_PENDENTES];
int numPendentes = 0;

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

  tranca.attach(PINO_SERVO);
  tranca.write(ANGULO_TRANCADO);      // estado inicial: trancada, LED vermelho
  digitalWrite(LED_VERMELHO, HIGH);

  SPI.begin();
  rfid.PCD_Init();

  lcd.init();
  lcd.backlight();
  http.setHttpResponseTimeout(8000);
  http.connectionKeepAlive();         // reaproveita a ligacao HTTPS: so o 1.o pedido paga o handshake TLS (~1-2 s)

  Serial.println("=== SmartAccess ===");
  calibrarSensores();                 // a porta tem de estar livre durante o arranque
  ligarWiFi();
  // O Arduino arranca sempre com a sala fechada: corrige o estado na API (ex.: reiniciou a meio de uma aula)
  enviarEvento("{\"tipo\":\"porta\",\"aberta\":false}");
  consultarComandos();

  mostrarRepouso();
  Serial.println("Aproxime um cartao do leitor RFID.");
  Serial.println("Testes pelo Monitor Serie: escreva um UID, 'E' (entrada), 'S' (saida)");
  Serial.println("ou 'D' (mostrar as distancias dos sensores, para afinar a montagem).");
}

// ======================================================
void loop() {
  // 1) Leitor RFID RC522
  if (rfid.PICC_IsNewCardPresent() && rfid.PICC_ReadCardSerial()) {
    String uid = lerUID();
    rfid.PICC_HaltA();        // para de ler este cartao
    rfid.PCD_StopCrypto1();
    tratarCartao(uid);
  }

  // (para testes) aceitar UIDs ou passagens escritas no Monitor Serie
  if (Serial.available()) {
    delay(50);
    String texto = Serial.readString();
    texto.trim();
    texto.toUpperCase();
    if (texto == "E") registarPassagem("entrada");
    else if (texto == "S") registarPassagem("saida");
    else if (texto == "D") mostrarDistancias = !mostrarDistancias;
    else if (texto.length() > 0) tratarCartao(texto);
  }

  // 2) Contagem de entradas e saidas com os 2 sensores
  verificarPassagem();

  if (mostrarDistancias && millis() - ultimaImpressao >= 300) {
    ultimaImpressao = millis();
    Serial.print("A: ");
    Serial.print(sensorA.distancia);
    Serial.print(sensorA.ocupado ? " cm OCUPADO   B: " : " cm livre     B: ");
    Serial.print(sensorB.distancia);
    Serial.println(sensorB.ocupado ? " cm OCUPADO" : " cm livre");
  }

  // Apaga a mensagem "Entrou"/"Saiu" do LCD passados 2 s
  if (mensagemEm != 0 && millis() - mensagemEm >= MENSAGEM_MS) {
    mensagemEm = 0;
    mostrarRepouso();
  }

  // 3) Comandos remotos + heartbeat. Nao consulta a meio de uma passagem:
  //    o pedido bloqueia o loop e os sensores deixariam de ser lidos
  if (millis() - ultimaConsulta >= INTERVALO_COMANDOS_MS && primeiroSensor == 0) {
    consultarComandos();
  }

  // 4) O RC522 as vezes deixa de responder (ruido, mau contacto): se isso acontecer, reinicia-o
  if (millis() - ultimaVerificacaoRfid >= INTERVALO_RFID_MS) {
    ultimaVerificacaoRfid = millis();
    byte versao = rfid.PCD_ReadRegister(MFRC522::VersionReg);
    if (versao == 0x00 || versao == 0xFF) {
      Serial.println("Leitor RFID sem resposta - a reiniciar");
      rfid.PCD_Init();
    }
  }

  delay(20);
}

// ======================================================
//  CARTOES E PORTA
// ======================================================
void tratarCartao(String uid) {
  Serial.print("Cartao lido: ");
  Serial.println(uid);
  tone(BUZZER, 2000, 40);             // bip curto: o cartao foi lido, pode tirar-se do leitor

  // O docente que abriu a sala volta a passar o cartao: fim da aula (nao precisa da API)
  if (portaAberta && uid == donoAula) {
    Serial.println(">> FIM DA AULA (cartao do docente)");
    fecharPorta("Fim da aula");
    return;
  }

  mostrar("A verificar...", uid);
  String resposta;
  int estado = pedidoApi(true, "/api/access", "{\"uid\":\"" + uid + "\"}", resposta);

  if (estado == 200) {
    String nome = lerCampoTexto(resposta, "nome");
    if (!portaAberta) {
      Serial.println(">> ACESSO CONCEDIDO - aula iniciada");
      abrirPorta(uid, "Aula iniciada", nome);
    } else if (lerCampoTexto(resposta, "perfil") == "admin") {
      Serial.println(">> SALA FECHADA por um funcionario");
      fecharPorta(nome);
    } else {
      Serial.println("Ja existe uma aula a decorrer");
      mostrar("Aula a decorrer", "So o docente fecha");
      delay(1500);
      mostrarRepouso();
    }
  } else if (estado == 403) {
    String motivo = lerCampoTexto(resposta, "motivo");
    Serial.print(">> ACESSO NEGADO: ");
    Serial.println(motivo);
    acessoNegado(textoMotivo(motivo));
  } else if (estado < 0 || estado >= 500) {
    // Sem rede ou servidor em baixo: so o cartao mestre abre (a porta fica fechada por seguranca)
    online = false;
    Serial.print(">> SEM LIGACAO A API (codigo ");
    Serial.print(estado);
    Serial.println(")");
    if (uid == UID_OFFLINE && !portaAberta) abrirPorta(uid, "Sem ligacao", "Cartao mestre");
    else if (uid == UID_OFFLINE) fecharPorta("Cartao mestre");
    else acessoNegado("Sem ligacao");
  } else {
    Serial.print("Erro na API: ");
    Serial.print(estado);
    Serial.print(" ");
    Serial.println(resposta);
    acessoNegado(String("Erro API ") + estado);
  }
}

// Converte o UID do cartao (bytes) num texto em hexadecimal, ex.: "A1B2C3D4"
String lerUID() {
  String uid = "";
  for (byte i = 0; i < rfid.uid.size; i++) {
    if (rfid.uid.uidByte[i] < 0x10) uid += "0";
    uid += String(rfid.uid.uidByte[i], HEX);
  }
  uid.toUpperCase();
  return uid;
}

// Mensagens curtas (16 caracteres) para o LCD
String textoMotivo(const String& motivo) {
  if (motivo == "cartao_desconhecido") return "Cartao registado";
  if (motivo == "cartao_pendente") return "Por aprovar";
  if (motivo == "cartao_revogado") return "Cartao revogado";
  if (motivo == "fora_do_horario") return "Fora do horario";
  return motivo;
}

// Inicio da aula: destranca e a porta fica aberta ate o docente (ou um funcionario) a fechar
void abrirPorta(String dono, String linha1, String linha2) {
  portaAberta = true;
  donoAula = dono;
  entradasAula = 0;
  saidasAula = 0;

  tranca.write(ANGULO_DESTRANCADO);
  digitalWrite(LED_VERMELHO, LOW);
  digitalWrite(LED_VERDE, HIGH);
  tone(BUZZER, 1000, 200);
  mostrar(linha1, linha2);
  mensagemEm = millis();        // passados 2 s o LCD mostra "Aula a decorrer"

  enviarEvento("{\"tipo\":\"porta\",\"aberta\":true}");
}

void fecharPorta(String linha2) {
  portaAberta = false;
  donoAula = "";
  tranca.write(ANGULO_TRANCADO);
  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_VERMELHO, HIGH);
  tone(BUZZER, 800, 300);
  Serial.println("Porta trancada");
  Serial.print("   Resumo da aula: ");
  Serial.print(entradasAula);
  Serial.print(" entradas, ");
  Serial.print(saidasAula);
  Serial.print(" saidas, ainda dentro: ");
  Serial.println(pessoasDentro);
  mostrar("Sala fechada", linha2);
  mensagemEm = millis();

  enviarEvento("{\"tipo\":\"porta\",\"aberta\":false}");
}

void acessoNegado(String motivo) {
  mostrar("Acesso negado", motivo);
  for (int i = 0; i < 3; i++) {          // LED vermelho a piscar + bip grave
    digitalWrite(LED_VERMELHO, LOW);
    tone(BUZZER, 300, 150);
    delay(200);
    digitalWrite(LED_VERMELHO, !portaAberta);
    delay(200);
  }
  delay(800);
  mostrarRepouso();
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
// (ex.: sensores a apontar para a ombreira em frente, a 80 cm -> ocupado abaixo de 65 cm)
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

    Serial.print("Sensor ");
    Serial.print(s->nome);
    Serial.print(validas >= 5 ? ": referencia " : ": sem eco, ");
    if (validas >= 5) {
      Serial.print(soma / validas);
      Serial.print(" cm, ");
    }
    Serial.print("ocupado abaixo de ");
    Serial.print(s->limiar);
    Serial.println(" cm");
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

  // 1) Inicio: so comeca quando um dos lados e tapado antes do outro
  if (primeiroSensor == 0) {
    if (a != b) {
      primeiroSensor = a ? 'A' : 'B';
      ultimoSensor = primeiroSensor;
      inicioPassagem = agora;
      livreDesde = 0;
    }
    return;
  }

  // 2) Durante: guarda qual foi o ultimo sensor a deixar de ver a pessoa
  if (mudouA && !a) ultimoSensor = 'A';
  if (mudouB && !b) ultimoSensor = 'B';

  // 3) Fim: os dois livres durante TEMPO_FIM_MS (tolera o espaco entre os sensores)
  if (a || b) {
    livreDesde = 0;
  } else if (livreDesde == 0) {
    livreDesde = agora;
  } else if (agora - livreDesde >= TEMPO_FIM_MS) {
    if (primeiroSensor == 'A' && ultimoSensor == 'B') {
      registarPassagem("entrada");
    } else if (primeiroSensor == 'B' && ultimoSensor == 'A') {
      registarPassagem("saida");
    } else {
      Serial.println("Passagem ignorada: a pessoa voltou para tras");
    }
    primeiroSensor = 0;
    return;
  }

  // Alguem parado na porta ou objeto a tapar um sensor: descarta e recomeca
  if (agora - inicioPassagem > TIMEOUT_PASSAGEM_MS) {
    Serial.println("Passagem ignorada: demorou demasiado");
    primeiroSensor = 0;
  }
}

void registarPassagem(const char* sentido) {
  bool entrada = strcmp(sentido, "entrada") == 0;
  if (entrada) {
    pessoasDentro++;
    entradasAula++;
  } else {
    if (pessoasDentro > 0) pessoasDentro--;
    saidasAula++;
  }
  tone(BUZZER, entrada ? 1500 : 900, 80);     // agudo = entrada, grave = saida

  // Mostra ja a contagem local; o LCD volta ao normal passados 2 s (ver loop)
  mostrar(entrada ? "Entrou 1 pessoa" : "Saiu 1 pessoa", String("Dentro: ") + pessoasDentro);
  mensagemEm = millis();

  if (!enviarPassagem(sentido) && numPendentes < MAX_PENDENTES) {
    pendentes[numPendentes++] = entrada ? 'E' : 'S';
  }

  // Ex.: ">> ENTRADA | Pessoas dentro: 5 | Aula: 6 entradas, 1 saidas"
  Serial.print(entrada ? ">> ENTRADA" : ">> SAIDA  ");
  Serial.print(" | Pessoas dentro: ");
  Serial.print(pessoasDentro);
  Serial.print(" | Aula: ");
  Serial.print(entradasAula);
  Serial.print(" entradas, ");
  Serial.print(saidasAula);
  Serial.println(" saidas");
}

// POST /api/device/events -> {"pessoas_dentro": N, "autorizada": true|false}
bool enviarPassagem(const char* sentido) {
  String corpo = String("{\"tipo\":\"passagem\",\"sentido\":\"") + sentido + "\"}";
  String resposta;
  int estado = pedidoApi(true, "/api/device/events", corpo, resposta);
  if (estado != 200) {
    Serial.print("Erro ao enviar passagem: ");
    Serial.println(estado);
    return false;
  }

  long contagem = lerCampoNumero(resposta, "pessoas_dentro");
  if (contagem >= 0) pessoasDentro = contagem;   // o servidor tem a contagem oficial

  // Entrou mais gente do que os cartoes passados: alerta local (a API cria o alerta na app)
  if (resposta.indexOf("\"autorizada\":false") >= 0) {
    Serial.println(">> ALERTA: entrada sem acesso correspondente");
    mostrar("ALERTA", "Entrada s/ acesso");
    for (int i = 0; i < 3; i++) {
      tone(BUZZER, 2000, 300);
      delay(400);
    }
  }
  return true;
}

// Envia as passagens guardadas enquanto nao havia rede (ficam com a hora do envio)
void enviarPassagensPendentes() {
  int enviadas = 0;
  while (enviadas < numPendentes && enviarPassagem(pendentes[enviadas] == 'E' ? "entrada" : "saida")) {
    enviadas++;
  }
  for (int i = enviadas; i < numPendentes; i++) pendentes[i - enviadas] = pendentes[i];
  numPendentes -= enviadas;
}

// ======================================================
//  COMANDOS REMOTOS E EVENTOS
// ======================================================
// GET /api/device/commands -> {"comandos":[{"id":5,"tipo":"abrir"}]}
void consultarComandos() {
  ultimaConsulta = millis();
  bool estavaOnline = online;

  String resposta;
  int estado = pedidoApi(false, "/api/device/commands", "", resposta);
  online = estado == 200;
  if (online != estavaOnline && mensagemEm == 0) mostrarRepouso();
  if (!online) return;

  if (numPendentes > 0) enviarPassagensPendentes();

  long comandoId = lerCampoNumero(resposta, "id");
  if (comandoId > 0) {
    Serial.print(">> ABERTURA REMOTA (comando ");
    Serial.print(comandoId);
    Serial.println(")");
    if (!portaAberta) abrirPorta("", "Abertura remota", "");   // sem dono: fecha com cartao de funcionario
    enviarEvento("{\"tipo\":\"comando_executado\",\"comando_id\":" + String(comandoId) + "}");
  }
}

void enviarEvento(const String& corpo) {
  String resposta;
  int estado = pedidoApi(true, "/api/device/events", corpo, resposta);
  if (estado != 200) {
    Serial.print("Erro ao enviar evento: ");
    Serial.println(estado);
  }
}

// ======================================================
//  WIFI E API
// ======================================================
// Tenta ligar durante ~10 s; se falhar, so volta a tentar passado INTERVALO_WIFI_MS
bool ligarWiFi() {
  if (ultimaTentativaWiFi != 0 && millis() - ultimaTentativaWiFi < INTERVALO_WIFI_MS) return false;
  ultimaTentativaWiFi = millis();

  mostrar("A ligar WiFi...", SECRET_SSID);
  Serial.print("A ligar a ");
  Serial.println(SECRET_SSID);

  WiFi.begin(SECRET_SSID, SECRET_PASS);
  for (int i = 0; i < 20 && WiFi.status() != WL_CONNECTED; i++) delay(500);

  bool ligado = WiFi.status() == WL_CONNECTED;
  if (ligado) {
    Serial.print("WiFi ligado, IP: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("WiFi indisponivel - a funcionar sem rede");
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
    http.sendHeader("x-api-key", API_KEY);
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

// Le um texto de um JSON simples, ex.: {"nome":"Ana"} -> "Ana" (ou "" se nao existir)
String lerCampoTexto(const String& json, const char* campo) {
  String chave = String("\"") + campo + "\":\"";
  int inicio = json.indexOf(chave);
  if (inicio < 0) return "";
  inicio += chave.length();
  int fim = json.indexOf('"', inicio);
  return fim < 0 ? "" : json.substring(inicio, fim);
}

// ======================================================
//  LCD
// ======================================================
void mostrar(String linha1, String linha2) {
  lcd.clear();
  lcd.setCursor(0, 0); lcd.print(linha1.substring(0, 16));
  lcd.setCursor(0, 1); lcd.print(linha2.substring(0, 16));
}

void mostrarRepouso() {
  String linha2 = "Dentro: ";
  linha2 += pessoasDentro;
  if (!online) linha2 += " OFF";
  mostrar(portaAberta ? "Aula a decorrer" : "Passe o cartao", linha2);
}
