# SmartAccess

Controlo de acesso a uma sala técnica com cartões RFID: Arduino + API em Python (FastAPI, no Vercel) + PostgreSQL + app Android (Kotlin/Compose).

```
arduino/arduino.ino      sketch do Arduino (RFID, 2 sensores HC-SR04, servo, LEDs, LCD, buzzer)
api/index.py             API REST (um único ficheiro Python)
requirements.txt         dependências da API
vercel.json              encaminha todas as rotas para api/index.py
db/schema.sql            tabelas, índices e trigger de registos imutáveis
db/seed.sql              utilizadores, dispositivo e cartões de teste
android/                 app Android (abrir esta pasta no Android Studio)
```

## Como funciona

1. O utilizador passa o cartão; o Arduino envia o UID para `POST /api/access`.
2. A API verifica se o cartão está **ativo** e se o dia/hora está dentro das suas **permissões** (os administradores entram a qualquer hora). Regista sempre a tentativa e responde concedido/negado.
3. Um cartão desconhecido fica **pendente**: o administrador aprova-o na app e associa-o a um utilizador.
4. Os **2 sensores** (A fora, B dentro) detetam o sentido de cada passagem. Cada acesso concedido autoriza **uma** entrada; se entrar mais gente do que cartões passados é criado um alerta `entrada_sem_acesso`.
5. A app pode pedir a **abertura remota**: a API cria um comando, que o Arduino recolhe a cada 5 s.
6. Alertas: 3 tentativas negadas seguidas, dispositivo sem contacto há mais de 60 s, entrada sem acesso.

## 1. Base de dados

Cria uma base PostgreSQL (no Vercel: *Storage → Create → Neon Postgres*) e executa:

```bash
psql "$DATABASE_URL" -f db/schema.sql
psql "$DATABASE_URL" -f db/seed.sql
```

(Ou cola o conteúdo dos dois ficheiros no editor SQL da Neon.)

Se já tinham criado as tabelas da versão anterior (aulas), apaguem-nas primeiro:

```sql
DROP VIEW IF EXISTS vw_sessoes;
DROP TABLE IF EXISTS passagens, sessoes, tentativas_acesso, salas, professores CASCADE;
```

Dados de teste do `seed.sql` (**mudar antes da entrega**):

| | |
|---|---|
| Admin | `admin@smartaccess.pt` / `admin123`, cartão `D5FFDFB5` |
| Utilizador | `user@smartaccess.pt` / `user123`, cartão `11223344` (seg–sex 08:00–20:00) |
| Dispositivo | `PORTA-1`, chave `troca-isto-por-uma-chave-longa-e-aleatoria` |

Para trocar a chave do Arduino:

```sql
UPDATE dispositivos SET api_key_hash = encode(sha256(convert_to('NOVA-CHAVE', 'UTF8')), 'hex') WHERE nome = 'PORTA-1';
```

## 2. API

### No Vercel

1. Importar o repositório no Vercel (deteta o Python pelo `requirements.txt`).
2. Em *Settings → Environment Variables* definir:
   - `DATABASE_URL` (se usaram o Neon pelo Vercel, `POSTGRES_URL` já existe e também funciona)
   - `JWT_SECRET`: texto longo e aleatório
   - `FUSO_HORARIO` (opcional, por omissão `Europe/Lisbon`)

A documentação automática fica em `https://<projeto>.vercel.app/docs`.

### Localmente

```bash
python -m venv .venv
.venv\Scripts\activate          # Windows (Linux/Mac: source .venv/bin/activate)
pip install -r requirements.txt uvicorn python-dotenv
copy .env.example .env          # e preencher
uvicorn api.index:app --reload --env-file .env
```

Abrir http://127.0.0.1:8000/docs para testar todos os endpoints.

## 3. Endpoints

**Arduino**: cabeçalho `x-api-key: <chave do dispositivo>`

| Método | Rota | Corpo | Resposta |
|---|---|---|---|
| POST | `/api/access` | `{"uid":"D5FFDFB5"}` | 200 `{"acesso":"concedido","nome":...}` / 403 `{"acesso":"negado","motivo":...}` |
| GET | `/api/device/commands` | – | `{"comandos":[{"id":5,"tipo":"abrir"}]}` (serve também de heartbeat) |
| POST | `/api/device/events` | `{"tipo":"passagem","sentido":"entrada"}` | `{"pessoas_dentro":2,"autorizada":true}` |
| POST | `/api/device/events` | `{"tipo":"comando_executado","comando_id":5}` | `{"registo_id":...}` |
| POST | `/api/device/events` | `{"tipo":"porta","aberta":true}` / `{"tipo":"heartbeat"}` | `{"ok":true}` |

Motivos de recusa: `cartao_desconhecido`, `cartao_pendente`, `cartao_revogado`, `fora_do_horario`.

**App**: cabeçalho `Authorization: Bearer <token>` (A = só administradores)

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/login` | `{"email","password"}` → `{"token","utilizador"}` |
| GET | `/api/me` | utilizador autenticado |
| GET | `/api/status` | estado dos dispositivos (online, porta, pessoas dentro, último acesso) |
| POST | `/api/door/unlock` (A) | pede abertura remota → `{"comando_id"}` |
| GET | `/api/door/commands/{id}` (A) | estado do comando (pendente/executado/expirado) |
| GET | `/api/logs?desde=&ate=&utilizador_id=&resultado=&limite=` | histórico (um utilizador só vê os seus) |
| GET | `/api/cards?estado=` (A) | cartões com permissões |
| PATCH | `/api/cards/{id}` (A) | `{"estado":"ativo","utilizador_id":2}` aprova; `{"estado":"revogado"}` revoga |
| GET / PUT | `/api/cards/{id}/permissions` (A) | `[{"dia_semana":1,"hora_inicio":"08:00","hora_fim":"20:00"}]` (1 = segunda) |
| GET | `/api/alerts?resolvido=` (A) | alertas |
| PATCH | `/api/alerts/{id}` (A) | `{"resolvido":true}` |
| GET / POST | `/api/users` (A) | listar / criar utilizadores |

Exemplo (PowerShell):

```powershell
$api = "https://<projeto>.vercel.app"
$dev = @{ "x-api-key" = "troca-isto-por-uma-chave-longa-e-aleatoria" }
Invoke-RestMethod -Method Post "$api/api/access" -Headers $dev -ContentType "application/json" -Body '{"uid":"D5FFDFB5"}'

$login = Invoke-RestMethod -Method Post "$api/api/auth/login" -ContentType "application/json" -Body '{"email":"admin@smartaccess.pt","password":"admin123"}'
Invoke-RestMethod "$api/api/logs" -Headers @{ Authorization = "Bearer $($login.token)" }
```

## 4. Arduino

Ligações:

| Componente | Pinos |
|---|---|
| HC-SR04 **A** (lado de fora) | TRIG 2, ECHO 3 |
| HC-SR04 **B** (lado de dentro) | TRIG 4, ECHO 5 |
| LED verde / vermelho | 6 / 7 |
| Buzzer | 8 |
| Servo | 9 |
| RC522 | SS 10, RST 0, SPI |
| LCD I2C | SDA/SCL |

1. Arduino IDE → abrir `arduino/arduino.ino`.
2. *Tools → Board*: **Arduino Uno WiFi Rev2** (pacote "Arduino megaAVR Boards") ou **UNO R4 WiFi**.
3. *Library Manager*: `MFRC522`, `Servo`, `LiquidCrystal I2C` (Frank de Brabander), `ArduinoHttpClient` e, só na Rev2, `WiFiNINA`.
4. Copiar `arduino_secrets.h.example` para `arduino_secrets.h` e preencher (WiFi, `API_HOST`, `API_KEY`, `UID_OFFLINE`).
5. Upload e *Serial Monitor* a 9600 baud.

Testes sem hardware no Serial Monitor: escrever um UID (ex.: `D5FFDFB5`), `E` (simula uma entrada) ou `S` (simula uma saída).

Sem rede: só o cartão `UID_OFFLINE` abre a porta. As passagens ficam guardadas (até 20) e são enviadas quando a ligação volta. `LIMIAR_CM` tem de ser calibrado para a largura da porta.

Se o HTTPS falhar com código negativo na Rev2, atualizar o firmware do módulo WiFi (*Tools → WiFi101 / WiFiNINA Firmware Updater*).

## 5. App Android

1. Abrir a pasta `android/` no Android Studio (usa Gradle 9.6 / AGP 9.4, compatíveis com o JDK do Android Studio).
2. Em `android/local.properties` acrescentar o endereço da API:
   ```
   api.url=https://<projeto>.vercel.app/
   ```
   Para testar com a API local no emulador: `api.url=http://10.0.2.2:8000/` (a versão debug permite HTTP).
   Se o emulador não chegar ao PC, usar `adb reverse tcp:8000 tcp:8000` e `api.url=http://127.0.0.1:8000/`.
3. *Run*.

Ecrãs: **Login**, **Painel** (estado da porta, online/offline, pessoas dentro, último acesso, botão "Abrir porta" com confirmação), **Registos** (filtros por período, resultado e utilizador, atualiza sozinho), **Cartões** (aprovar pendentes, revogar/reativar, horários por dia) e **Alertas** (marcar como resolvido). Um utilizador normal só vê o Painel e os seus Registos.

## Por fazer

- Notificações push (Firebase Cloud Messaging): é preciso criar um projeto Firebase e adicionar o `google-services.json`. Por agora a app consulta os alertas a cada 10 s enquanto está aberta.
