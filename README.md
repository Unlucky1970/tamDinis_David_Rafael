# SmartAccess

Controlo de acesso a uma sala técnica com cartões RFID: Arduino + API em Python (Flask, no Vercel) + PostgreSQL + app Android (Kotlin/Compose).

```
arduino/arduino.ino      sketch do Arduino (RFID, 2 sensores HC-SR04, servo, LEDs, LCD, buzzer)
api/index.py             API REST (um único ficheiro Python)
requirements.txt         dependências da API
vercel.json              encaminha todas as rotas para api/index.py
db/schema.sql            tabelas, índices e trigger de registos imutáveis
db/seed.sql              utilizadores, dispositivo e cartões de teste
adc/                     app Android (abrir esta pasta no Android Studio)
```

## Como funciona

1. O utilizador passa o cartão; o Arduino envia o UID para `POST /api/access`.
2. A API verifica se o cartão está **ativo** e se o dia/hora está dentro das suas **permissões** (os administradores entram a qualquer hora). Regista sempre a tentativa e responde concedido/negado.
3. Um cartão desconhecido fica **pendente**: o administrador aprova-o na app e associa-o a um utilizador.
4. **Aula:** o cartão do docente abre a sala e a porta fica aberta. Só volta a trancar quando o **mesmo cartão** passa outra vez, ou quando passa o cartão de um docente administrador (perfil admin). Durante a aula, os outros cartões só veem "Aula a decorrer".
5. Os **2 sensores** (A fora, B dentro) contam as entradas e saídas de todos (docente e alunos); o Serial Monitor mostra as pessoas dentro e um resumo no fim da aula. Quem entra e volta logo a sair conta 1 entrada + 1 saída. Só se conta com a aula a decorrer: com a porta trancada ninguém consegue passar, por isso os sensores são ignorados.
6. A app pode pedir a **abertura remota**: a API cria um comando, que o Arduino recolhe a cada 5 s. A sala fica aberta até passar o cartão de um docente administrador.
7. Alertas: 3 tentativas negadas seguidas, dispositivo sem contacto há mais de 60 s, entrada sem acesso.

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
| Admin | `admin@smartaccess.pt` / `admin123`, cartão `8DF36E69` |
| Utilizador | `user@smartaccess.pt` / `user123`, cartão `337A93AA` (seg–sex 08:00–20:00) |
| Dispositivo | `PORTA-1`, chave em `db/seed.sql` (a mesma do `API_KEY` no Arduino) |

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

Pela linha de comandos, sempre a partir da **raiz do repositório** (não da pasta `api/`): `vercel --prod`.

Para confirmar que está a funcionar: `https://<projeto>.vercel.app/api` deve devolver `{"servico":"SmartAccess"}`.

### Localmente

```bash
python -m venv .venv
.venv\Scripts\activate          # Windows (Linux/Mac: source .venv/bin/activate)
pip install -r requirements.txt python-dotenv
copy .env.example .env          # e preencher
flask --app api/index run --port 8000 --debug
```

A API fica em http://127.0.0.1:8000 (testar com Postman ou com os exemplos abaixo). Os erros vêm sempre como `{"detail": "mensagem"}`.

## 3. Endpoints

**Arduino**: cabeçalho `x-api-key: <chave do dispositivo>`

| Método | Rota | Corpo | Resposta |
|---|---|---|---|
| POST | `/api/access` | `{"uid":"8DF36E69"}` | 200 `{"acesso":"concedido","nome":...}` / 403 `{"acesso":"negado","motivo":...}` |
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
$dev = @{ "x-api-key" = "<chave do dispositivo>" }
Invoke-RestMethod -Method Post "$api/api/access" -Headers $dev -ContentType "application/json" -Body '{"uid":"8DF36E69"}'

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

Testes no Serial Monitor: escrever um UID (ex.: `8DF36E69`), `E` (simula uma entrada), `S` (simula uma saída) ou `D` (liga/desliga a impressão das distâncias dos dois sensores, útil para afinar a montagem).

### Entradas e saídas

Os dois HC-SR04 ficam lado a lado na ombreira, a apontar para a ombreira em frente: o **A do lado de fora** e o **B do lado de dentro**, afastados uns 10–20 cm.

```
   FORA                         DENTRO
          [A]  ──────►    (pessoa a entrar: tapa o A, depois o B)
          [B]  ──────►
```

- No arranque o Arduino **calibra** cada sensor (a porta tem de estar livre): mede a distância até à ombreira e considera "ocupado" tudo o que esteja 15 cm mais perto (`MARGEM_CM`).
- Cada sensor só muda de estado com 2 leituras seguidas iguais, por isso um eco falso isolado não conta.
- **Entrada** = o primeiro sensor tapado foi o A e o último a ficar livre foi o B. **Saída** = o contrário.
- Se o primeiro e o último forem o mesmo sensor, a pessoa voltou para trás e não conta.
- A passagem só fecha quando os dois sensores ficam livres durante 400 ms, o que tolera o espaço entre eles.
- Alguém parado à porta mais de 8 s é ignorado.
- O LCD mostra "Entrou 1 pessoa" / "Saiu 1 pessoa" durante 2 s, e o buzzer dá um bip agudo na entrada e grave na saída.

Sem rede: só o cartão `UID_OFFLINE` abre a porta. As passagens ficam guardadas (até 20) e são enviadas quando a ligação volta.

Se o HTTPS falhar com código negativo na Rev2, atualizar o firmware do módulo WiFi (*Tools → WiFi101 / WiFiNINA Firmware Updater*).

## 5. App Android

1. Abrir a pasta `adc/` no Android Studio (usa Gradle 9.7 / AGP 9.4, compatíveis com o JDK do Android Studio).
2. Em `adc/local.properties` acrescentar o endereço da API:
   ```
   api.url=https://<projeto>.vercel.app/
   ```
   Para testar com a API local no emulador: `api.url=http://10.0.2.2:8000/` (a versão debug permite HTTP).
   Se o emulador não chegar ao PC, usar `adb reverse tcp:8000 tcp:8000` e `api.url=http://127.0.0.1:8000/`.
3. *Run*.

Ecrãs: **Login**, **Painel** (estado da porta, online/offline, pessoas dentro, último acesso, botão "Abrir porta" com confirmação), **Registos** (filtros por período, resultado e utilizador, atualiza sozinho), **Cartões** (aprovar pendentes, revogar/reativar, horários por dia) e **Alertas** (marcar como resolvido). Um utilizador normal só vê o Painel e os seus Registos.

## Por fazer

- Notificações push (Firebase Cloud Messaging): é preciso criar um projeto Firebase e adicionar o `google-services.json`. Por agora a app consulta os alertas a cada 10 s enquanto está aberta.
