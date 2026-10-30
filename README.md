# SmartAccess

Controlo de acesso a salas de aula com cartões RFID: Arduino + API em Python (Flask, no Vercel) + PostgreSQL + app Android (Kotlin/Compose).

```
arduino/arduino.ino      sketch do Arduino (RFID, 2 sensores HC-SR04, servo, LEDs, LCD, buzzer)
api/index.py             API REST (um único ficheiro Python)
requirements.txt         dependências da API
vercel.json              encaminha todas as rotas para api/index.py
db/schema.sql            tabelas, índices e trigger de registos imutáveis (cria ou atualiza a base)
db/seed.sql              utilizadores, horários e cartões de teste
postman/                 coleção do Postman com todos os pedidos
adc/                     app Android (abrir esta pasta no Android Studio)
```

## Como funciona

**Perfis**

| Perfil | Quando entra | O que pode fazer na porta |
|---|---|---|
| `admin` (docente administrador) | a qualquer hora | abrir a sala e fechar **qualquer** aula; gere tudo na app |
| `funcionario` | horário do perfil (por omissão seg–sáb 07:00–23:00) | abrir a sala e fechar **qualquer** aula |
| `docente` | horário do perfil (por omissão seg–sex 08:00–20:00) | abrir a sala e fechar **só a sua** aula |

Os horários de cada perfil estão na tabela `horarios_perfil` e mudam-se pela API (`PUT /api/schedules/{perfil}`). Um cartão pode ter um horário próprio (`PUT /api/cards/{id}/permissions`), que substitui o do perfil.

**Aulas**

1. Sala fechada: o cartão passa, o Arduino envia o UID para `POST /api/access` e a **API decide**. Se o cartão for válido e estiver dentro do horário, responde `"acao":"abrir"`: começa uma aula e a porta fica aberta.
2. Aula a decorrer: o dono da aula, um admin ou um funcionário fecham a sala (`"acao":"fechar"`, a qualquer hora). Os outros recebem `aula_a_decorrer`.
3. Os **2 sensores** (A fora, B dentro) contam as entradas e saídas de todos, docente e alunos, mas só com a aula a decorrer: com a porta trancada ninguém consegue passar. Quem entra e volta logo a sair conta 1 entrada + 1 saída. Uma saída com 0 pessoas dentro é registada como "entrada não detetada".
4. Ao fechar, a API devolve o resumo da aula (duração, entradas, saídas, entradas não detetadas), que fica na tabela `aulas`.
5. Um cartão desconhecido fica **pendente** até o admin o aprovar e associar a um utilizador.
6. A app pode pedir a **abertura remota**: a aula fica em nome de quem a pediu.
7. Alertas: 3 tentativas negadas seguidas, dispositivo sem contacto há mais de 60 s, entrada com a sala fechada, dispositivo novo à espera de aprovação.

**Dispositivos (sem nada fixo no código)**

O Arduino não tem chaves nem ids no código: o mesmo sketch serve para qualquer porta.

1. No primeiro arranque, regista-se em `POST /api/device/register` com o seu **MAC** e recebe uma **chave única**, que guarda na EEPROM. A API só guarda o SHA-256 da chave.
2. A porta fica **pendente** (o LCD mostra "Aguarda aprovac.") até um admin a aprovar e lhe dar um nome (`PATCH /api/devices/{id}`).
3. A partir daí, o Arduino pede a configuração em `GET /api/device/config`: nome da porta (aparece no LCD), cartões que funcionam sem rede (perfis da tabela `configuracao`), intervalo de contacto e a aula que estiver a decorrer. Se o Arduino reiniciar a meio de uma aula, retoma-a.
4. Sem rede: só os cartões offline (admin/funcionário, guardados na EEPROM) abrem e fecham a sala, e o dono de uma aula aberta pode fechá-la. Os eventos ficam guardados e são enviados quando a rede voltar.

## 1. Base de dados

Cria uma base PostgreSQL (no Vercel: *Storage → Create → Neon Postgres*) e executa, por esta ordem:

```bash
psql "$DATABASE_URL" -f db/schema.sql
psql "$DATABASE_URL" -f db/seed.sql
```

(Ou cola o conteúdo dos dois ficheiros no SQL Editor da Neon.)

O `schema.sql` serve tanto para uma base nova como para **atualizar** uma base da versão anterior, e pode ser executado mais do que uma vez. Na atualização, o perfil antigo `utilizador` passa a `docente`.

Se a base tiver um dispositivo criado à mão nas versões anteriores (com a chave que estava no repositório), revoguem-no:

```sql
UPDATE dispositivos SET estado = 'revogado' WHERE mac IS NULL;
```

Dados de teste do `seed.sql` (**mudar antes da entrega**):

| Perfil | Login | Cartão |
|---|---|---|
| admin | `admin@smartaccess.pt` / `admin123` | `8DF36E69` |
| funcionario | `funcionario@smartaccess.pt` / `func123` | – (aprovar um cartão novo e associá-lo) |
| docente | `docente@smartaccess.pt` / `doc123` | `337A93AA` |

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

A API fica em http://127.0.0.1:8000. Os erros vêm sempre como `{"detail": "mensagem"}`.

## 3. Endpoints

A coleção `postman/SmartAccess.postman_collection.json` tem todos os pedidos prontos a usar (*Import* no Postman).

**Arduino**: cabeçalho `x-api-key: <chave do dispositivo>` (exceto o registo)

| Método | Rota | Corpo | Resposta |
|---|---|---|---|
| POST | `/api/device/register` | `{"mac":"AA:BB:CC:DD:EE:FF"}` | 201 `{"api_key","nome","estado":"pendente"}` |
| GET | `/api/device/config` | – | `{"nome","intervalo_comandos_s","cartoes_offline","pessoas_dentro","aula"}` |
| POST | `/api/access` | `{"uid":"8DF36E69"}` | 200 `{"acesso":"concedido","acao":"abrir"/"fechar","nome","perfil","aula":{...}}` / 403 `{"acesso":"negado","motivo"}` |
| GET | `/api/device/commands` | – | `{"comandos":[{"id":5,"tipo":"abrir"}]}` (serve também de heartbeat) |
| POST | `/api/device/events` | `{"tipo":"passagem","sentido":"entrada"}` | `{"pessoas_dentro","autorizada","entrada_nao_detetada","aula":{"entradas","saidas"}}` |
| POST | `/api/device/events` | `{"tipo":"comando_executado","comando_id":5}` | `{"registo_id","aula"}` |
| POST | `/api/device/events` | `{"tipo":"porta","aberta":true,"uid":"..."}` / `{"tipo":"heartbeat"}` | `{"ok":true}` |

Motivos de recusa: `cartao_desconhecido`, `cartao_pendente`, `cartao_revogado`, `fora_do_horario`, `aula_a_decorrer`. Um dispositivo pendente ou revogado recebe 403 `{"detail":...}` em todas as rotas.

**App**: cabeçalho `Authorization: Bearer <token>` (A = só administradores)

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/auth/login` | `{"email","password"}` → `{"token","utilizador"}` |
| GET | `/api/me` | utilizador autenticado |
| GET | `/api/status` | estado das portas ativas (online, porta, pessoas dentro, último acesso, aula a decorrer) |
| GET | `/api/devices` (A) | dispositivos (pendentes primeiro) |
| PATCH | `/api/devices/{id}` (A) | `{"estado":"ativo","nome":"Sala 1.04"}` aprova; `{"estado":"revogado"}` revoga |
| POST | `/api/door/unlock` (A) | `{"dispositivo_id"}` pede abertura remota → `{"comando_id"}` |
| GET | `/api/door/commands/{id}` (A) | estado do comando (pendente/executado/expirado) |
| GET | `/api/classes?limite=` | aulas com entradas/saídas (docente e funcionário só veem as suas) |
| GET | `/api/logs?desde=&ate=&utilizador_id=&resultado=&limite=` | histórico de acessos (docente e funcionário só veem os seus) |
| GET | `/api/cards?estado=` (A) | cartões com permissões próprias |
| PATCH | `/api/cards/{id}` (A) | `{"estado":"ativo","utilizador_id":2}` aprova; `{"estado":"revogado"}` revoga |
| GET / PUT | `/api/cards/{id}/permissions` (A) | horário próprio do cartão (`[]` volta ao horário do perfil) |
| GET | `/api/schedules` (A) | horários do docente e do funcionário |
| PUT | `/api/schedules/{perfil}` (A) | `[{"dia_semana":1,"hora_inicio":"08:00","hora_fim":"20:00"}]` (1 = segunda) |
| GET | `/api/alerts?resolvido=` (A) | alertas |
| PATCH | `/api/alerts/{id}` (A) | `{"resolvido":true}` |
| GET / POST | `/api/users` (A) | listar / criar utilizadores (`perfil`: admin, funcionario ou docente) |
| PATCH | `/api/users/{id}` (A) | `{"perfil":"funcionario"}` / `{"nome":"..."}` |

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
4. Copiar `arduino_secrets.h.example` para `arduino_secrets.h` e preencher **só** a rede WiFi e o `API_HOST`.
5. Upload e *Serial Monitor* a 9600 baud. No primeiro arranque aparece `Registado como PORTA-XXXXXX`: aprovar a porta (Postman → *App → Dispositivos*).

Comandos no Serial Monitor:

| Comando | O que faz |
|---|---|
| `<UID>` | simula um cartão (ex.: `8DF36E69`) |
| `E` / `S` | simula uma entrada / saída (só com a aula a decorrer) |
| `D` | liga/desliga as distâncias dos sensores (para afinar a montagem) |
| `I` | estado da porta, da aula e da ligação |
| `R` | apaga a chave guardada e regista a porta de novo |
| `?` | ajuda |

### Entradas e saídas

Os dois HC-SR04 ficam lado a lado na ombreira, a apontar para a ombreira em frente: o **A do lado de fora** e o **B do lado de dentro**, afastados uns 10–20 cm.

```
   FORA                         DENTRO
          [A]  ──────►    (pessoa a entrar: tapa o A, depois o B)
          [B]  ──────►
```

- No arranque o Arduino **calibra** cada sensor (a porta tem de estar livre): mede a distância até à ombreira e considera "ocupado" tudo o que esteja `MARGEM_CM` mais perto.
- Cada sensor só muda de estado com 2 leituras seguidas iguais, por isso um eco falso isolado não conta.
- Segue-se o lado onde a pessoa está: de **só A** para **só B** = entrada; de **só B** para **só A** = saída. A → B → A = 1 entrada + 1 saída.
- A passagem fecha quando os dois sensores ficam livres durante 400 ms, ou quando ninguém se mexe durante 5 s.

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

Ecrãs: **Login**, **Painel** (estado da porta, online/offline, pessoas dentro, último acesso, botão "Abrir porta" com confirmação), **Registos** (filtros por período, resultado e utilizador, atualiza sozinho), **Cartões** (aprovar pendentes, revogar/reativar, horários por dia) e **Alertas** (marcar como resolvido). Docentes e funcionários só veem o Painel e os seus Registos.

## Por fazer

- App: ecrãs para **Dispositivos** (aprovar portas novas), **Aulas** e **Horários por perfil**. Por agora faz-se pelo Postman.
- Notificações push (Firebase Cloud Messaging): é preciso criar um projeto Firebase e adicionar o `google-services.json`. Por agora a app consulta os alertas a cada 10 s enquanto está aberta.
