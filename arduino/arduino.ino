// SmartAccess - leitor RFID RC522 + 2 sensores HC-SR04 (entrada/saida) + WiFi + API (Python no Vercel)
// O Arduino so le os sensores e executa: a decisao de abrir a porta e tomada na API.
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
void abrirPorta(String linha1, String linha2);
void fecharPorta();
void acessoNegado(String motivo);
long medirDistancia(int trig, int echo);
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
const int LIMIAR_CM = 100;                          // abaixo disto = alguem a frente do sensor (calibrar)
const unsigned long PORTA_ABERTA_MS = 5000;         // tempo que a tranca fica aberta
const unsigned long INTERVALO_COMANDOS_MS = 5000;   // consulta de comandos remotos (e heartbeat)
const unsigned long TIMEOUT_PASSAGEM_MS = 3000;     // passagem que nao chega ao 2.o sensor e ignorada
const unsigned long INTERVALO_WIFI_MS = 30000;      // espera entre tentativas de religar o WiFi
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
bool portaAberta = false;
unsigned long abertaEm = 0;
unsigned long ultimaConsulta = 0;
unsigned long ultimaTentativaWiFi = 0;
int pessoasDentro = 0;

// Detecao do sentido: o primeiro sensor a ver alguem indica de onde vem a pessoa.
// A -> B = entrada, B -> A = saida. A passagem conta quando os dois sensores ficam livres.
char primeiroSensor = 0;     // 0 (livre), 'A' ou 'B'
bool viuSegundo = false;
unsigned long inicioPassagem = 0;

// Passagens que falharam por falta de rede ('E' = entrada, 'S' = saida)
char pendentes[MAX_PENDENTES];
int numPendentes = 0;

// ======================================================
void setup() {
  Serial.begin(9600);

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

  Serial.println("=== SmartAccess ===");
  ligarWiFi();
  consultarComandos();                // primeiro contacto com a API

  mostrarRepouso();
  Serial.println("Aproxime um cartao do leitor RFID.");
  Serial.println("Testes pelo Monitor Serie: escreva um UID, 'E' (entrada) ou 'S' (saida).");
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
    else if (texto.length() > 0) tratarCartao(texto);
  }

  // 2) Contagem de entradas e saidas com os 2 sensores
  verificarPassagem();

  // 3) Voltar a trancar ao fim de 5 s
  if (portaAberta && millis() - abertaEm >= PORTA_ABERTA_MS) {
    fecharPorta();
  }

  // 4) Comandos remotos + heartbeat
  if (millis() - ultimaConsulta >= INTERVALO_COMANDOS_MS) {
    consultarComandos();
  }

  delay(20);
}

// ======================================================
//  CARTOES E PORTA
// ======================================================
void tratarCartao(String uid) {
  Serial.print("Cartao lido: ");
  Serial.println(uid);
  mostrar("A verificar...", uid);

  String resposta;
  int estado = pedidoApi(true, "/api/access", "{\"uid\":\"" + uid + "\"}", resposta);

  if (estado == 200) {
    Serial.println(">> ACESSO CONCEDIDO");
    abrirPorta("Acesso concedido", lerCampoTexto(resposta, "nome"));
  } else if (estado == 403) {
    String motivo = lerCampoTexto(resposta, "motivo");
    Serial.print(">> ACESSO NEGADO: ");
    Serial.println(motivo);
    acessoNegado(textoMotivo(motivo));
  } else if (estado < 0) {
    // Sem rede: so o cartao mestre abre (a porta fica fechada por seguranca)
    online = false;
    Serial.println(">> SEM LIGACAO A API");
    if (uid == UID_OFFLINE) abrirPorta("Sem ligacao", "Cartao mestre");
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

void abrirPorta(String linha1, String linha2) {
  bool jaAberta = portaAberta;
  portaAberta = true;
  abertaEm = millis();          // se ja estava aberta, prolonga

  tranca.write(ANGULO_DESTRANCADO);
  digitalWrite(LED_VERMELHO, LOW);
  digitalWrite(LED_VERDE, HIGH);
  tone(BUZZER, 1000, 200);
  mostrar(linha1, linha2);

  if (!jaAberta) enviarEvento("{\"tipo\":\"porta\",\"aberta\":true}");
}

void fecharPorta() {
  portaAberta = false;
  tranca.write(ANGULO_TRANCADO);
  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_VERMELHO, HIGH);
  Serial.println("Porta trancada");
  mostrarRepouso();
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

void verificarPassagem() {
  bool a = medirDistancia(TRIG_A, ECHO_A) < LIMIAR_CM;
  delay(10);                                    // evita que o eco de um sensor chegue ao outro
  bool b = medirDistancia(TRIG_B, ECHO_B) < LIMIAR_CM;

  if (primeiroSensor == 0) {
    if (a && !b) primeiroSensor = 'A';
    else if (b && !a) primeiroSensor = 'B';
    if (primeiroSensor != 0) {
      viuSegundo = false;
      inicioPassagem = millis();
    }
    return;
  }

  if ((primeiroSensor == 'A' && b) || (primeiroSensor == 'B' && a)) viuSegundo = true;

  if (!a && !b) {
    // Zona livre: so conta se a pessoa passou pelos dois sensores (senao voltou para tras)
    if (viuSegundo) registarPassagem(primeiroSensor == 'A' ? "entrada" : "saida");
    primeiroSensor = 0;
  } else if (!viuSegundo && millis() - inicioPassagem > TIMEOUT_PASSAGEM_MS) {
    primeiroSensor = 0;       // alguem parado a frente de um so sensor
  }
}

void registarPassagem(const char* sentido) {
  bool entrada = strcmp(sentido, "entrada") == 0;
  if (entrada) pessoasDentro++;
  else if (pessoasDentro > 0) pessoasDentro--;

  Serial.print("PASSAGEM: ");
  Serial.print(sentido);
  Serial.print(" -> dentro: ");
  Serial.println(pessoasDentro);
  tone(BUZZER, 1500, 80);

  if (!enviarPassagem(sentido) && numPendentes < MAX_PENDENTES) {
    pendentes[numPendentes++] = entrada ? 'E' : 'S';
  }
  if (!portaAberta) mostrarRepouso();
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
  if (online != estavaOnline && !portaAberta) mostrarRepouso();
  if (!online) return;

  if (numPendentes > 0) enviarPassagensPendentes();

  long comandoId = lerCampoNumero(resposta, "id");
  if (comandoId > 0) {
    Serial.print(">> ABERTURA REMOTA (comando ");
    Serial.print(comandoId);
    Serial.println(")");
    abrirPorta("Abertura remota", "");
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
  if (!portaAberta) mostrarRepouso();
  return ligado;
}

// Faz um GET ou POST (JSON) a API; devolve o codigo HTTP (negativo = erro de rede)
int pedidoApi(bool post, const char* caminho, const String& corpo, String& resposta) {
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

  int estado = http.responseStatusCode();
  if (estado > 0) resposta = http.responseBody();
  http.stop();
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
  mostrar("Passe o cartao", linha2);
}
