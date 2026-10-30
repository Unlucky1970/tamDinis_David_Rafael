# SmartAccess

Controlo de acesso a salas com RFID (Arduino) + API em Vercel + PostgreSQL.

```
arduino/arduino.ino      sketch do Arduino
api/                     funcoes serverless (Vercel)
lib/                     ligacao a BD e utilitarios HTTP
db/schema.sql            tabelas, indices e vista
db/seed.sql              sala e cartoes de teste
```

## 1. Base de dados

Cria uma base PostgreSQL (no Vercel: *Storage → Create → Neon Postgres*) e executa:

```bash
psql "$DATABASE_URL" -f db/schema.sql
psql "$DATABASE_URL" -f db/seed.sql
```

(Ou cola o conteudo dos dois ficheiros no editor SQL da Neon.)

## 2. Deploy no Vercel

1. Envia esta pasta para um repositorio GitHub e importa-o no Vercel (ou `vercel` na linha de comandos).
2. Em *Settings → Environment Variables* define:
   - `DATABASE_URL` (se usaste o Neon pelo Vercel, `POSTGRES_URL` ja existe e tambem funciona)
   - `DEVICE_API_KEY` – uma chave longa e aleatoria, a mesma que vais por no Arduino

## 3. Endpoints

Todos exigem o cabecalho `x-api-key: <DEVICE_API_KEY>`.

| Metodo | Rota | Corpo | Resposta |
|---|---|---|---|
| POST | `/api/access` | `{"uid":"D5FFDFB5","sala":"SALA-1"}` | 200 `{"acesso":"concedido","sessao_id":1,"professor":"..."}` / 403 `{"acesso":"negado"}` |
| POST | `/api/passages` | `{"sessao_id":1}` | 201 `{"total_passagens":3}` |
| POST | `/api/sessions/end` | `{"sessao_id":1,"motivo":"cartao"}` (ou `"automatico"`) | 200 `{"sessao_id":1,"fim":"...","total_passagens":3}` |
| GET | `/api/sessions?sala=SALA-1&limite=50` | – | lista de aulas com total de passagens |

Exemplo:

```bash
curl -X POST https://<projeto>.vercel.app/api/access \
  -H "Content-Type: application/json" -H "x-api-key: <chave>" \
  -d '{"uid":"D5FFDFB5","sala":"SALA-1"}'
```

## 4. Testar (por esta ordem)

### a) API sozinha (sem Arduino)

No PowerShell:

```powershell
$api = "https://<projeto>.vercel.app"
$h = @{ "x-api-key" = "<chave>" }

# abrir aula -> deve devolver acesso=concedido e um sessao_id
$r = Invoke-RestMethod -Method Post "$api/api/access" -Headers $h -ContentType "application/json" -Body '{"uid":"D5FFDFB5","sala":"SALA-1"}'
$r

# registar uma passagem e fechar a aula
Invoke-RestMethod -Method Post "$api/api/passages" -Headers $h -ContentType "application/json" -Body "{`"sessao_id`":$($r.sessao_id)}"
Invoke-RestMethod -Method Post "$api/api/sessions/end" -Headers $h -ContentType "application/json" -Body "{`"sessao_id`":$($r.sessao_id),`"motivo`":`"cartao`"}"

# ver as aulas
Invoke-RestMethod "$api/api/sessions" -Headers $h
```

Um UID que nao esta na BD (ex.: `AAAAAAAA`) deve dar erro 403 (negado).

### b) Arduino

1. Arduino IDE → *File → Open* → `arduino/arduino.ino`.
2. *Tools → Board*: **Arduino Uno WiFi Rev2** (pacote "Arduino megaAVR Boards") ou **UNO R4 WiFi** (pacote "Arduino UNO R4 Boards"), conforme a placa.
3. *Library Manager*: instalar `MFRC522`, `Servo`, `LiquidCrystal I2C` (Frank de Brabander), `ArduinoHttpClient` e, so na Rev2, `WiFiNINA`.
4. Preencher `arduino/arduino_secrets.h` (rede WiFi, `API_HOST`, `API_KEY`, `SALA`).
5. Fazer upload e abrir o *Serial Monitor* a 9600 baud. Deve aparecer `WiFi ligado, IP: ...`.
6. Sem hardware ligado da para testar escrevendo `D5FFDFB5` no Serial Monitor: deve aparecer `ACESSO CONCEDIDO (sessao N)` e a aula surge em `/api/sessions`.

Se o pedido HTTPS falhar com codigo negativo na Rev2, atualiza o firmware do modulo WiFi (*Tools → WiFi101 / WiFiNINA Firmware Updater*), que traz os certificados raiz atualizados.

### c) App Android

1. Abrir a pasta `android/` no Android Studio (ele cria o `local.properties` e descarrega o Gradle).
2. Acrescentar ao `android/local.properties`:
   ```
   api.url=https://<projeto>.vercel.app/
   api.key=<chave>
   ```
3. *Run* num emulador ou telemovel. Mostra a lista de aulas (com as que estao a decorrer).

## Correspondencia com o Arduino

| Funcao no sketch | Endpoint |
|---|---|
| `pedirAcesso()` + `iniciarSessao()` | `POST /api/access` (guardar o `sessao_id`) |
| `enviarPassagem()` | `POST /api/passages` |
| `terminarSessao(motivo)` | `POST /api/sessions/end` |

Para adicionar professores:

```sql
INSERT INTO professores (nome, email, uid_cartao) VALUES ('Nome', 'email@escola.pt', 'A1B2C3D4');
```
