// SmartAccess - versao com leitor RFID RC522 real (1 sensor) + WiFi + API (Vercel)
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
#include "arduino_secrets.h"   // rede WiFi, endereco da API, chave e sala

// Prototipos das funcoes
void tratarCartao(String uid);
String lerUID();
bool pedirAcesso(String uid);
void iniciarSessao(String uid);
void terminarSessao(String motivo);
void acessoNegado();
void fecharSala();
long medirDistancia();
void verificarPassagem();
void registarPassagem();
void enviarPassagem();
void mostrar(String linha1, String linha2);
void atualizarLCD();
void ligarWiFi();
int enviarPost(const char* caminho, const String& corpo, String& resposta);
long lerCampoNumero(const String& json, const char* campo);

// ---------------- Pinos ----------------
const int TRIG = 2;     // Sensor HC-SR04 na porta
const int ECHO = 3;
const int LED_VERDE = 6;
const int LED_VERMELHO = 7;
const int BUZZER = 8;
const int PINO_SERVO = 9;
const int RFID_SS = 10;   // SDA/SS do RC522
const int RFID_RST = 0;  // RST do RC522

// ---------------- Configuracao ----------------
const int LIMIAR_CM = 100;                    // abaixo disto = alguem a frente do sensor
const unsigned long DURACAO_AULA_MS = 120000;// 2 min para testar o fecho automatico
const int ANGULO_TRANCADO = 0;
const int ANGULO_DESTRANCADO = 90;

// ---------------- Estado ----------------
Servo tranca;
MFRC522 rfid(RFID_SS, RFID_RST);
LiquidCrystal_I2C lcd(0x27, 16, 2);   // 0x27 = endereco por omissao no Wokwi e na maioria dos LCD reais

WiFiSSLClient wifi;                       // o Vercel so aceita HTTPS
HttpClient http(wifi, API_HOST, 443);

bool sessaoAtiva = false;
String donoSessao = "";
long sessaoId = -1;                       // id da aula na base de dados
unsigned long inicioSessao = 0;
int totalPassagens = 0;

// Detecao de passagem: alguem a frente do sensor -> depois sai da frente = 1 passagem
bool alguemNaPorta = false;

// ======================================================
void setup() {
  Serial.begin(9600);

  pinMode(TRIG, OUTPUT);
  pinMode(ECHO, INPUT);
  pinMode(LED_VERDE, OUTPUT);
  pinMode(LED_VERMELHO, OUTPUT);
  pinMode(BUZZER, OUTPUT);

  tranca.attach(PINO_SERVO);

  SPI.begin();
  rfid.PCD_Init();

  lcd.init();
  lcd.backlight();

  fecharSala();   // estado inicial: trancada, LED vermelho

  Serial.println("=== SmartAccess ===");
  ligarWiFi();

  mostrar("SmartAccess", "Passe o cartao");
  Serial.println("Aproxime um cartao do leitor RFID (ou escreva um UID no Monitor Serie).");
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

  // (para testes) aceitar UIDs escritos no Monitor Serie
  if (Serial.available()) {
    delay(50);
    String uid = Serial.readString();
    uid.trim();
    uid.toUpperCase();
    if (uid.length() > 0) tratarCartao(uid);
  }

  // 2) Contagem de passagens (so durante a aula)
  if (sessaoAtiva) {
    verificarPassagem();

    // 3) Fecho automatico no fim do horario
    if (millis() - inicioSessao >= DURACAO_AULA_MS) {
      Serial.println("Fim do horario da aula -> fecho automatico");
      terminarSessao("automatico");
    }
  }

  delay(60);   // o HC-SR04 precisa de ~60 ms entre medicoes
}

// ======================================================
//  CARTOES E SESSAO
// ======================================================
void tratarCartao(String uid) {
  Serial.print("Cartao lido: ");
  Serial.println(uid);

  if (!sessaoAtiva) {
    if (pedirAcesso(uid)) {
      iniciarSessao(uid);
    } else {
      acessoNegado();
    }
  } else if (uid == donoSessao) {
    terminarSessao("cartao");
  } else {
    Serial.println("Ja existe uma aula a decorrer.");
    mostrar("Aula a decorrer", "");
    delay(1500);
    atualizarLCD();
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

// POST /api/access -> 200 = concedido (devolve sessao_id), 403 = negado
bool pedirAcesso(String uid) {
  mostrar("A verificar...", "");
  String corpo = "{\"uid\":\"" + uid + "\",\"sala\":\"" + SALA + "\"}";
  String resposta;
  int estado = enviarPost("/api/access", corpo, resposta);

  if (estado == 200) {
    sessaoId = lerCampoNumero(resposta, "sessao_id");
    return sessaoId > 0;
  }
  if (estado != 403) {
    Serial.print("Erro na API: ");
    Serial.print(estado);
    Serial.print(" ");
    Serial.println(resposta);
    mostrar("Erro de ligacao", String("Codigo ") + estado);
    delay(1500);
  }
  return false;
}

void iniciarSessao(String uid) {
  sessaoAtiva = true;
  donoSessao = uid;
  inicioSessao = millis();
  totalPassagens = 0;
  alguemNaPorta = false;

  tranca.write(ANGULO_DESTRANCADO);
  digitalWrite(LED_VERMELHO, LOW);
  digitalWrite(LED_VERDE, HIGH);
  tone(BUZZER, 1000, 200);

  Serial.print(">> ACESSO CONCEDIDO - Aula iniciada (sessao ");
  Serial.print(sessaoId);
  Serial.println("), porta destrancada");
  mostrar("Aula iniciada", "");
  delay(1500);
  atualizarLCD();
}

void terminarSessao(String motivo) {
  sessaoAtiva = false;
  fecharSala();               // tranca primeiro, so depois fala com a API
  tone(BUZZER, 800, 300);

  Serial.print(">> AULA TERMINADA (");
  Serial.print(motivo);
  Serial.println(")");
  Serial.print("   Total de passagens na aula: ");
  Serial.println(totalPassagens);
  mostrar("Sala fechada", "Passe o cartao");

  // POST /api/sessions/end
  String corpo = "{\"sessao_id\":" + String(sessaoId) + ",\"motivo\":\"" + motivo + "\"}";
  String resposta;
  int estado = enviarPost("/api/sessions/end", corpo, resposta);
  if (estado != 200) {
    Serial.print("Erro ao terminar sessao na API: ");
    Serial.println(estado);
  }
  sessaoId = -1;
}

void acessoNegado() {
  Serial.println(">> ACESSO NEGADO");
  mostrar("Acesso negado", "");
  for (int i = 0; i < 3; i++) {          // LED vermelho a piscar + bip grave
    digitalWrite(LED_VERMELHO, LOW);
    tone(BUZZER, 300, 150);
    delay(200);
    digitalWrite(LED_VERMELHO, HIGH);
    delay(200);
  }
  mostrar("SmartAccess", "Passe o cartao");
}

void fecharSala() {
  tranca.write(ANGULO_TRANCADO);
  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_VERMELHO, HIGH);
}

// ======================================================
//  CONTAGEM COM 1 SENSOR
// ======================================================
long medirDistancia() {
  digitalWrite(TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG, LOW);
  long duracao = pulseIn(ECHO, HIGH, 30000);   // timeout ~5 m
  if (duracao == 0) return 999;                 // sem eco = nada a frente
  return duracao / 58;                          // conversao para cm
}

void verificarPassagem() {
  bool presente = medirDistancia() < LIMIAR_CM;

  if (presente && !alguemNaPorta) {
    alguemNaPorta = true;            // alguem chegou a frente do sensor
  } else if (!presente && alguemNaPorta) {
    alguemNaPorta = false;           // saiu da frente -> conta 1 passagem
    registarPassagem();
  }
}

void registarPassagem() {
  totalPassagens++;
  tone(BUZZER, 1500, 80);
  atualizarLCD();
  enviarPassagem();
}

// POST /api/passages
void enviarPassagem() {
  unsigned long segundos = (millis() - inicioSessao) / 1000;
  Serial.print("[");
  Serial.print(segundos);
  Serial.print(" s] PASSAGEM -> total: ");
  Serial.println(totalPassagens);

  String corpo = "{\"sessao_id\":" + String(sessaoId) + "}";
  String resposta;
  int estado = enviarPost("/api/passages", corpo, resposta);
  if (estado != 201) {
    Serial.print("Erro ao enviar passagem: ");
    Serial.println(estado);
  }
}

// ======================================================
//  WIFI E API
// ======================================================
void ligarWiFi() {
  mostrar("A ligar WiFi...", SECRET_SSID);
  Serial.print("A ligar a ");
  Serial.println(SECRET_SSID);

  while (WiFi.status() != WL_CONNECTED) {
    WiFi.begin(SECRET_SSID, SECRET_PASS);
    delay(3000);
  }

  Serial.print("WiFi ligado, IP: ");
  Serial.println(WiFi.localIP());
}

// Envia um POST com JSON para a API; devolve o codigo HTTP (negativo = erro de rede)
int enviarPost(const char* caminho, const String& corpo, String& resposta) {
  if (WiFi.status() != WL_CONNECTED) ligarWiFi();

  http.beginRequest();
  http.post(caminho);
  http.sendHeader("Content-Type", "application/json");
  http.sendHeader("Content-Length", corpo.length());
  http.sendHeader("x-api-key", API_KEY);
  http.beginBody();
  http.print(corpo);
  http.endRequest();

  int estado = http.responseStatusCode();
  resposta = http.responseBody();
  http.stop();
  return estado;
}

// Le um numero de um JSON simples, ex.: {"sessao_id":12} -> 12 (ou -1 se nao existir)
long lerCampoNumero(const String& json, const char* campo) {
  String chave = String("\"") + campo + "\":";
  int pos = json.indexOf(chave);
  if (pos < 0) return -1;
  return json.substring(pos + chave.length()).toInt();
}

// ======================================================
//  LCD
// ======================================================
void mostrar(String linha1, String linha2) {
  lcd.clear();
  lcd.setCursor(0, 0); lcd.print(linha1);
  lcd.setCursor(0, 1); lcd.print(linha2);
}

void atualizarLCD() {
  String linha2 = "Passagens: ";
  linha2 += totalPassagens;
  mostrar("Aula a decorrer", linha2);
}
