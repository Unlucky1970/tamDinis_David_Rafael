"""SmartAccess - API REST (Flask + PostgreSQL).

No Vercel todas as rotas sao encaminhadas para este ficheiro (ver vercel.json).
Localmente: flask --app api/index run --port 8000 --debug   (le o .env automaticamente)

Autenticacao:
  - Arduino: cabecalho "x-api-key" (guardada na tabela dispositivos como SHA-256)
  - App:     cabecalho "Authorization: Bearer <token>" obtido em /api/auth/login
Erros: {"detail": "mensagem"} com o codigo HTTP correspondente.
"""

import hashlib
import os
import re
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

import bcrypt
import jwt
import psycopg
from flask import Flask, g, jsonify, request
from flask.json.provider import DefaultJSONProvider
from psycopg.rows import dict_row

DATABASE_URL = os.environ.get("DATABASE_URL") or os.environ.get("POSTGRES_URL")
JWT_SECRET = os.environ.get("JWT_SECRET", "")
FUSO = ZoneInfo(os.environ.get("FUSO_HORARIO", "Europe/Lisbon"))

DURACAO_TOKEN = timedelta(hours=12)
OFFLINE_APOS = timedelta(seconds=60)      # o Arduino contacta a API a cada ~5 s
JANELA_ENTRADA = timedelta(seconds=30)    # tempo para entrar depois de um acesso concedido
VALIDADE_COMANDO = timedelta(minutes=1)   # um pedido de abertura antigo ja nao abre a porta
TENTATIVAS_PARA_ALERTA = 3


# Datas em ISO 8601 (o Flask usa por omissao o formato HTTP "Thu, 30 Oct 2026 ...")
class JsonIso(DefaultJSONProvider):
    @staticmethod
    def default(valor):
        if isinstance(valor, (datetime, date, time)):
            return valor.isoformat()
        return DefaultJSONProvider.default(valor)


app = Flask(__name__)
app.json = JsonIso(app)
app.json.sort_keys = False


# ======================================================
#  ERROS
# ======================================================
class ErroApi(Exception):
    def __init__(self, estado: int, mensagem: str):
        super().__init__(mensagem)
        self.estado = estado
        self.mensagem = mensagem


@app.errorhandler(ErroApi)
def tratar_erro_api(erro: ErroApi):
    con = g.pop("con", None)
    if con is not None:
        con.rollback()
        con.close()
    return jsonify(detail=erro.mensagem), erro.estado


@app.errorhandler(404)
def nao_encontrado(_):
    return jsonify(detail="Rota inexistente"), 404


@app.errorhandler(405)
def metodo_invalido(_):
    return jsonify(detail="Metodo nao permitido"), 405


@app.errorhandler(500)
def erro_interno(_):
    return jsonify(detail="Erro interno"), 500


# ======================================================
#  BASE DE DADOS
# ======================================================
# Uma ligacao por pedido; no fim faz commit (ou rollback se houve erro) e fecha
def bd():
    if "con" not in g:
        if not DATABASE_URL:
            raise ErroApi(500, "DATABASE_URL nao configurada no servidor")
        g.con = psycopg.connect(DATABASE_URL, row_factory=dict_row)
    return g.con


@app.teardown_request
def fechar_bd(excecao):
    con = g.pop("con", None)
    if con is None:
        return
    try:
        if excecao is None:
            con.commit()
        else:
            con.rollback()
    finally:
        con.close()


# ======================================================
#  LEITURA E VALIDACAO DOS DADOS
# ======================================================
def corpo_json():
    dados = request.get_json(silent=True)
    if dados is None:
        raise ErroApi(400, "O corpo tem de ser JSON")
    return dados


def ler_inteiro(valor, campo: str, minimo: int | None = None, maximo: int | None = None) -> int:
    if isinstance(valor, bool) or not isinstance(valor, int):
        try:
            valor = int(valor)
        except (TypeError, ValueError):
            raise ErroApi(422, f"'{campo}' tem de ser um numero inteiro")
    if (minimo is not None and valor < minimo) or (maximo is not None and valor > maximo):
        raise ErroApi(422, f"'{campo}' fora do intervalo permitido")
    return valor


def ler_opcao(valor, campo: str, opcoes: tuple):
    if valor not in opcoes:
        raise ErroApi(422, f"'{campo}' tem de ser um de: {', '.join(map(str, opcoes))}")
    return valor


def ler_hora(valor, campo: str) -> time:
    try:
        return time.fromisoformat(str(valor))
    except ValueError:
        raise ErroApi(422, f"'{campo}' tem de estar no formato HH:MM")


def ler_data(valor, campo: str) -> date | None:
    if not valor:
        return None
    try:
        return date.fromisoformat(valor)
    except ValueError:
        raise ErroApi(422, f"'{campo}' tem de estar no formato AAAA-MM-DD")


def ler_booleano(valor, campo: str) -> bool | None:
    if valor is None:
        return None
    if valor.lower() in ("true", "1"):
        return True
    if valor.lower() in ("false", "0"):
        return False
    raise ErroApi(422, f"'{campo}' tem de ser true ou false")


# "d5 ff df b5" -> "D5FFDFB5"
def ler_uid(valor) -> str:
    uid = re.sub(r"\s+", "", str(valor or "")).upper()
    if not re.fullmatch(r"[0-9A-F]{8,20}", uid):
        raise ErroApi(422, "'uid' tem de ser hexadecimal")
    return uid


# ======================================================
#  AUTENTICACAO
# ======================================================
def segredo_jwt() -> str:
    if not JWT_SECRET:
        raise ErroApi(500, "JWT_SECRET nao configurado no servidor")
    return JWT_SECRET


def utilizador_atual():
    cabecalho = request.headers.get("Authorization", "")
    if not cabecalho.startswith("Bearer "):
        raise ErroApi(401, "Token em falta")
    try:
        dados = jwt.decode(cabecalho[7:], segredo_jwt(), algorithms=["HS256"])
    except jwt.PyJWTError:
        raise ErroApi(401, "Token invalido ou expirado")

    utilizador = bd().execute(
        "SELECT id, nome, email, perfil FROM utilizadores WHERE id = %s",
        (int(dados["sub"]),),
    ).fetchone()
    if not utilizador:
        raise ErroApi(401, "Utilizador ja nao existe")
    return utilizador


def exigir_admin():
    utilizador = utilizador_atual()
    if utilizador["perfil"] != "admin":
        raise ErroApi(403, "Apenas para administradores")
    return utilizador


# Identifica o Arduino pela chave e regista o contacto (serve de heartbeat)
def dispositivo_atual():
    chave = request.headers.get("x-api-key", "")
    if not chave:
        raise ErroApi(401, "Chave em falta")
    dispositivo = bd().execute(
        """UPDATE dispositivos SET ultimo_contacto = now()
           WHERE api_key_hash = %s
           RETURNING id, nome""",
        (hashlib.sha256(chave.encode()).hexdigest(),),
    ).fetchone()
    if not dispositivo:
        raise ErroApi(401, "Chave invalida")
    return dispositivo


# ======================================================
#  REGRAS DE NEGOCIO
# ======================================================
def dentro_do_horario(con, cartao_id: int) -> bool:
    agora = datetime.now(FUSO)
    return con.execute(
        """SELECT 1 FROM permissoes
           WHERE cartao_id = %s AND dia_semana = %s
             AND hora_inicio <= %s AND %s < hora_fim""",
        (cartao_id, agora.isoweekday(), agora.time(), agora.time()),
    ).fetchone() is not None


def criar_alerta(con, tipo: str, dispositivo_id: int, descricao: str, unico: bool = True):
    # unico: nao repete o alerta enquanto houver um igual por resolver
    if unico and con.execute(
        "SELECT 1 FROM alertas WHERE tipo = %s AND dispositivo_id = %s AND NOT resolvido",
        (tipo, dispositivo_id),
    ).fetchone():
        return
    con.execute(
        "INSERT INTO alertas (tipo, dispositivo_id, descricao) VALUES (%s, %s, %s)",
        (tipo, dispositivo_id, descricao),
    )


def verificar_tentativas_negadas(con, dispositivo):
    # Negados nos ultimos 5 minutos desde o ultimo acesso concedido
    negados = con.execute(
        """SELECT count(*) AS n FROM registos_acesso
           WHERE dispositivo_id = %(d)s AND resultado = 'negado'
             AND data_hora > now() - interval '5 minutes'
             AND data_hora > coalesce(
               (SELECT max(data_hora) FROM registos_acesso
                WHERE dispositivo_id = %(d)s AND resultado = 'concedido'),
               '-infinity')""",
        {"d": dispositivo["id"]},
    ).fetchone()["n"]
    if negados >= TENTATIVAS_PARA_ALERTA:
        criar_alerta(
            con, "tentativas_negadas", dispositivo["id"],
            f"{negados} tentativas de acesso negadas seguidas em {dispositivo['nome']}",
        )


# Nao ha tarefas agendadas no Vercel gratuito: verifica-se quando a app pede dados
def verificar_offline(con):
    for d in con.execute(
        "SELECT id, nome FROM dispositivos WHERE ultimo_contacto < now() - %s",
        (OFFLINE_APOS,),
    ).fetchall():
        criar_alerta(con, "dispositivo_offline", d["id"], f"{d['nome']} sem comunicacao com o servidor")


def obter_cartao(con, cartao_id: int):
    cartao = con.execute(
        """SELECT c.id, c.uid, c.estado, c.utilizador_id, u.nome AS utilizador, c.criado_em,
                  coalesce((SELECT json_agg(json_build_object(
                                'dia_semana', p.dia_semana,
                                'hora_inicio', to_char(p.hora_inicio, 'HH24:MI'),
                                'hora_fim', to_char(p.hora_fim, 'HH24:MI'))
                              ORDER BY p.dia_semana, p.hora_inicio)
                            FROM permissoes p WHERE p.cartao_id = c.id), '[]') AS permissoes
           FROM cartoes c LEFT JOIN utilizadores u ON u.id = c.utilizador_id
           WHERE c.id = %s""",
        (cartao_id,),
    ).fetchone()
    if not cartao:
        raise ErroApi(404, "Cartao nao encontrado")
    return cartao


# ======================================================
#  ROTAS - GERAL E AUTENTICACAO
# ======================================================
@app.get("/api")
def raiz():
    return {"servico": "SmartAccess"}


@app.post("/api/auth/login")
def login():
    dados = corpo_json()
    email = str(dados.get("email", "")).strip()
    password = str(dados.get("password", ""))

    utilizador = bd().execute(
        "SELECT id, nome, email, perfil, password_hash FROM utilizadores WHERE lower(email) = lower(%s)",
        (email,),
    ).fetchone()
    if not utilizador or not bcrypt.checkpw(password.encode(), utilizador.pop("password_hash").encode()):
        raise ErroApi(401, "Email ou palavra-passe incorretos")

    token = jwt.encode(
        {
            "sub": str(utilizador["id"]),
            "perfil": utilizador["perfil"],
            "exp": datetime.now(timezone.utc) + DURACAO_TOKEN,
        },
        segredo_jwt(),
        algorithm="HS256",
    )
    return {"token": token, "utilizador": utilizador}


@app.get("/api/me")
def eu():
    return utilizador_atual()


# ======================================================
#  ROTAS - DISPOSITIVO (ARDUINO)
# ======================================================
# 200 -> {"acesso": "concedido", "registo_id", "nome"} | 403 -> {"acesso": "negado", "motivo"}
@app.post("/api/access")
def pedir_acesso():
    dispositivo = dispositivo_atual()
    uid = ler_uid(corpo_json().get("uid"))
    con = bd()

    cartao = con.execute(
        """SELECT c.id, c.estado, c.utilizador_id, u.nome, u.perfil
           FROM cartoes c LEFT JOIN utilizadores u ON u.id = c.utilizador_id
           WHERE c.uid = %s""",
        (uid,),
    ).fetchone()

    if cartao is None:
        # Cartao novo: fica pendente para o administrador aprovar na app
        con.execute("INSERT INTO cartoes (uid) VALUES (%s) ON CONFLICT (uid) DO NOTHING", (uid,))
        motivo = "cartao_desconhecido"
    elif cartao["estado"] == "pendente":
        motivo = "cartao_pendente"
    elif cartao["estado"] == "revogado":
        motivo = "cartao_revogado"
    elif cartao["perfil"] != "admin" and not dentro_do_horario(con, cartao["id"]):
        motivo = "fora_do_horario"
    else:
        motivo = None

    concedido = motivo is None
    registo = con.execute(
        """INSERT INTO registos_acesso (dispositivo_id, cartao_uid, utilizador_id, origem, resultado, motivo)
           VALUES (%s, %s, %s, 'cartao', %s, %s) RETURNING id""",
        (
            dispositivo["id"], uid, cartao["utilizador_id"] if cartao else None,
            "concedido" if concedido else "negado", motivo,
        ),
    ).fetchone()

    if not concedido:
        verificar_tentativas_negadas(con, dispositivo)
        return {"acesso": "negado", "motivo": motivo}, 403
    return {"acesso": "concedido", "registo_id": registo["id"], "nome": cartao["nome"]}


# Comandos pendentes (abertura remota). Chamado a cada ~5 s, serve tambem de heartbeat.
@app.get("/api/device/commands")
def comandos_pendentes():
    dispositivo = dispositivo_atual()
    con = bd()
    con.execute(
        """UPDATE comandos SET estado = 'expirado'
           WHERE dispositivo_id = %s AND estado = 'pendente' AND criado_em < now() - %s""",
        (dispositivo["id"], VALIDADE_COMANDO),
    )
    comandos = con.execute(
        "SELECT id, tipo FROM comandos WHERE dispositivo_id = %s AND estado = 'pendente' ORDER BY id",
        (dispositivo["id"],),
    ).fetchall()
    return {"comandos": comandos}


@app.post("/api/device/events")
def evento_dispositivo():
    dispositivo = dispositivo_atual()
    evento = corpo_json()
    tipo = ler_opcao(evento.get("tipo"), "tipo", ("heartbeat", "comando_executado", "passagem", "porta"))
    con = bd()

    if tipo == "comando_executado":
        comando_id = ler_inteiro(evento.get("comando_id"), "comando_id")
        comando = con.execute(
            """UPDATE comandos SET estado = 'executado', executado_em = now()
               WHERE id = %s AND dispositivo_id = %s AND estado = 'pendente'
               RETURNING criado_por""",
            (comando_id, dispositivo["id"]),
        ).fetchone()
        if not comando:
            raise ErroApi(404, "Comando inexistente ou ja tratado")
        registo = con.execute(
            """INSERT INTO registos_acesso (dispositivo_id, utilizador_id, origem, resultado, motivo)
               VALUES (%s, %s, 'remoto', 'concedido', 'abertura_remota') RETURNING id""",
            (dispositivo["id"], comando["criado_por"]),
        ).fetchone()
        return {"registo_id": registo["id"]}

    if tipo == "passagem":
        sentido = ler_opcao(evento.get("sentido"), "sentido", ("entrada", "saida"))

        # Cada acesso concedido autoriza uma entrada: liga-a ao acesso recente que ainda nao foi usado
        registo_id = None
        if sentido == "entrada":
            registo = con.execute(
                """SELECT r.id FROM registos_acesso r
                   WHERE r.dispositivo_id = %s AND r.resultado = 'concedido'
                     AND r.data_hora > now() - %s
                     AND NOT EXISTS (SELECT 1 FROM passagens p
                                     WHERE p.registo_acesso_id = r.id AND p.sentido = 'entrada')
                   ORDER BY r.data_hora
                   LIMIT 1""",
                (dispositivo["id"], JANELA_ENTRADA),
            ).fetchone()
            registo_id = registo["id"] if registo else None

        con.execute(
            "INSERT INTO passagens (dispositivo_id, sentido, registo_acesso_id) VALUES (%s, %s, %s)",
            (dispositivo["id"], sentido, registo_id),
        )
        pessoas = con.execute(
            """UPDATE dispositivos SET pessoas_dentro = greatest(pessoas_dentro + %s, 0)
               WHERE id = %s RETURNING pessoas_dentro""",
            (1 if sentido == "entrada" else -1, dispositivo["id"]),
        ).fetchone()["pessoas_dentro"]

        if sentido == "entrada" and registo_id is None:
            criar_alerta(
                con, "entrada_sem_acesso", dispositivo["id"],
                f"Entrada em {dispositivo['nome']} sem acesso correspondente (mais pessoas do que cartoes)",
                unico=False,
            )
        return {"pessoas_dentro": pessoas, "autorizada": registo_id is not None or sentido == "saida"}

    if tipo == "porta":
        aberta = evento.get("aberta")
        if not isinstance(aberta, bool):
            raise ErroApi(422, "'aberta' tem de ser true ou false")
        con.execute("UPDATE dispositivos SET porta_aberta = %s WHERE id = %s", (aberta, dispositivo["id"]))

    return {"ok": True}


# ======================================================
#  ROTAS - APP
# ======================================================
@app.get("/api/status")
def estado_sistema():
    utilizador_atual()
    con = bd()
    verificar_offline(con)
    return con.execute(
        """SELECT d.id, d.nome, d.porta_aberta, d.pessoas_dentro, d.ultimo_contacto,
                  coalesce(d.ultimo_contacto > now() - %s, false) AS online,
                  (SELECT row_to_json(x) FROM (
                     SELECT r.data_hora, r.resultado, r.origem, u.nome AS utilizador
                     FROM registos_acesso r LEFT JOIN utilizadores u ON u.id = r.utilizador_id
                     WHERE r.dispositivo_id = d.id
                     ORDER BY r.data_hora DESC LIMIT 1) x) AS ultimo_acesso
           FROM dispositivos d ORDER BY d.id""",
        (OFFLINE_APOS,),
    ).fetchall()


@app.post("/api/door/unlock")
def abrir_porta():
    admin = exigir_admin()
    dados = request.get_json(silent=True) or {}
    con = bd()

    if dados.get("dispositivo_id") is not None:
        dispositivo_id = ler_inteiro(dados["dispositivo_id"], "dispositivo_id")
        dispositivo = con.execute("SELECT id, nome FROM dispositivos WHERE id = %s", (dispositivo_id,)).fetchone()
    else:
        dispositivo = con.execute("SELECT id, nome FROM dispositivos ORDER BY id LIMIT 1").fetchone()
    if not dispositivo:
        raise ErroApi(404, "Dispositivo nao encontrado")

    comando = con.execute(
        "INSERT INTO comandos (dispositivo_id, criado_por) VALUES (%s, %s) RETURNING id",
        (dispositivo["id"], admin["id"]),
    ).fetchone()
    return {"comando_id": comando["id"], "dispositivo": dispositivo["nome"]}, 202


# Para a app saber se o Arduino ja executou a abertura
@app.get("/api/door/commands/<int:comando_id>")
def estado_comando(comando_id: int):
    exigir_admin()
    comando = bd().execute(
        "SELECT id, estado, criado_em, executado_em FROM comandos WHERE id = %s", (comando_id,)
    ).fetchone()
    if not comando:
        raise ErroApi(404, "Comando nao encontrado")
    return comando


@app.get("/api/logs")
def registos():
    utilizador = utilizador_atual()
    args = request.args
    desde = ler_data(args.get("desde"), "desde")
    ate = ler_data(args.get("ate"), "ate")
    resultado = args.get("resultado") or None
    if resultado is not None:
        ler_opcao(resultado, "resultado", ("concedido", "negado"))
    limite = ler_inteiro(args.get("limite", 100), "limite", 1, 500)

    # Um utilizador normal so ve os seus proprios acessos
    if utilizador["perfil"] == "admin":
        utilizador_id = ler_inteiro(args["utilizador_id"], "utilizador_id") if args.get("utilizador_id") else None
    else:
        utilizador_id = utilizador["id"]

    return bd().execute(
        """SELECT r.id, r.data_hora, r.cartao_uid, r.utilizador_id, u.nome AS utilizador,
                  r.origem, r.resultado, r.motivo, d.nome AS dispositivo,
                  (SELECT count(*) FROM passagens p
                   WHERE p.registo_acesso_id = r.id AND p.sentido = 'entrada')::int AS entradas
           FROM registos_acesso r
           JOIN dispositivos d ON d.id = r.dispositivo_id
           LEFT JOIN utilizadores u ON u.id = r.utilizador_id
           WHERE (%(desde)s::date IS NULL OR r.data_hora >= %(desde)s::date::timestamp AT TIME ZONE %(fuso)s)
             AND (%(ate)s::date IS NULL OR r.data_hora < (%(ate)s::date + 1)::timestamp AT TIME ZONE %(fuso)s)
             AND (%(utilizador)s::bigint IS NULL OR r.utilizador_id = %(utilizador)s)
             AND (%(resultado)s::text IS NULL OR r.resultado = %(resultado)s)
           ORDER BY r.data_hora DESC
           LIMIT %(limite)s""",
        {
            "desde": desde, "ate": ate, "utilizador": utilizador_id, "resultado": resultado,
            "limite": limite, "fuso": str(FUSO),
        },
    ).fetchall()


@app.get("/api/cards")
def listar_cartoes():
    exigir_admin()
    estado = request.args.get("estado") or None
    if estado is not None:
        ler_opcao(estado, "estado", ("pendente", "ativo", "revogado"))
    con = bd()
    ids = con.execute(
        """SELECT id FROM cartoes
           WHERE %(estado)s::text IS NULL OR estado = %(estado)s
           ORDER BY (estado = 'pendente') DESC, criado_em DESC""",
        {"estado": estado},
    ).fetchall()
    return [obter_cartao(con, linha["id"]) for linha in ids]


# Aprovar ({"estado": "ativo", "utilizador_id": 2}), revogar ({"estado": "revogado"}) ou reassociar
@app.patch("/api/cards/<int:cartao_id>")
def alterar_cartao(cartao_id: int):
    exigir_admin()
    dados = corpo_json()
    con = bd()
    atual = obter_cartao(con, cartao_id)

    estado = atual["estado"]
    if dados.get("estado") is not None:
        estado = ler_opcao(dados["estado"], "estado", ("ativo", "revogado"))

    utilizador_id = atual["utilizador_id"]
    if dados.get("utilizador_id") is not None:
        utilizador_id = ler_inteiro(dados["utilizador_id"], "utilizador_id")
        if not con.execute("SELECT 1 FROM utilizadores WHERE id = %s", (utilizador_id,)).fetchone():
            raise ErroApi(404, "Utilizador nao encontrado")

    if estado == "ativo" and utilizador_id is None:
        raise ErroApi(400, "Associe o cartao a um utilizador antes de o aprovar")

    con.execute(
        "UPDATE cartoes SET estado = %s, utilizador_id = %s WHERE id = %s",
        (estado, utilizador_id, cartao_id),
    )
    return obter_cartao(con, cartao_id)


@app.get("/api/cards/<int:cartao_id>/permissions")
def ver_permissoes(cartao_id: int):
    exigir_admin()
    return obter_cartao(bd(), cartao_id)["permissoes"]


# Substitui todas as janelas de acesso do cartao
@app.put("/api/cards/<int:cartao_id>/permissions")
def definir_permissoes(cartao_id: int):
    exigir_admin()
    lista = corpo_json()
    if not isinstance(lista, list):
        raise ErroApi(422, "O corpo tem de ser uma lista de permissoes")

    permissoes = []
    for p in lista:
        if not isinstance(p, dict):
            raise ErroApi(422, "Cada permissao tem de ser um objeto")
        dia = ler_inteiro(p.get("dia_semana"), "dia_semana", 1, 7)
        inicio = ler_hora(p.get("hora_inicio"), "hora_inicio")
        fim = ler_hora(p.get("hora_fim"), "hora_fim")
        if fim <= inicio:
            raise ErroApi(422, "hora_fim tem de ser depois de hora_inicio")
        permissoes.append((dia, inicio, fim))

    con = bd()
    obter_cartao(con, cartao_id)
    con.execute("DELETE FROM permissoes WHERE cartao_id = %s", (cartao_id,))
    for dia, inicio, fim in permissoes:
        con.execute(
            "INSERT INTO permissoes (cartao_id, dia_semana, hora_inicio, hora_fim) VALUES (%s, %s, %s, %s)",
            (cartao_id, dia, inicio, fim),
        )
    return obter_cartao(con, cartao_id)["permissoes"]


@app.get("/api/alerts")
def listar_alertas():
    exigir_admin()
    resolvido = ler_booleano(request.args.get("resolvido"), "resolvido")
    con = bd()
    verificar_offline(con)
    return con.execute(
        """SELECT a.id, a.tipo, a.descricao, a.data_hora, a.resolvido, d.nome AS dispositivo
           FROM alertas a LEFT JOIN dispositivos d ON d.id = a.dispositivo_id
           WHERE %(resolvido)s::boolean IS NULL OR a.resolvido = %(resolvido)s
           ORDER BY a.resolvido, a.data_hora DESC
           LIMIT 200""",
        {"resolvido": resolvido},
    ).fetchall()


@app.patch("/api/alerts/<int:alerta_id>")
def alterar_alerta(alerta_id: int):
    exigir_admin()
    resolvido = corpo_json().get("resolvido")
    if not isinstance(resolvido, bool):
        raise ErroApi(422, "'resolvido' tem de ser true ou false")
    alerta = bd().execute(
        "UPDATE alertas SET resolvido = %s WHERE id = %s RETURNING id, tipo, descricao, data_hora, resolvido",
        (resolvido, alerta_id),
    ).fetchone()
    if not alerta:
        raise ErroApi(404, "Alerta nao encontrado")
    return alerta


@app.get("/api/users")
def listar_utilizadores():
    exigir_admin()
    return bd().execute("SELECT id, nome, email, perfil FROM utilizadores ORDER BY nome").fetchall()


@app.post("/api/users")
def criar_utilizador():
    exigir_admin()
    dados = corpo_json()
    nome = str(dados.get("nome", "")).strip()
    email = str(dados.get("email", "")).strip()
    password = str(dados.get("password", ""))
    perfil = ler_opcao(dados.get("perfil", "utilizador"), "perfil", ("admin", "utilizador"))
    if not nome or "@" not in email:
        raise ErroApi(422, "'nome' e 'email' sao obrigatorios")
    if len(password) < 6:
        raise ErroApi(422, "A palavra-passe tem de ter pelo menos 6 caracteres")

    password_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
    try:
        novo = bd().execute(
            """INSERT INTO utilizadores (nome, email, password_hash, perfil)
               VALUES (%s, %s, %s, %s) RETURNING id, nome, email, perfil""",
            (nome, email, password_hash, perfil),
        ).fetchone()
    except psycopg.errors.UniqueViolation:
        raise ErroApi(409, "Ja existe um utilizador com esse email")
    return novo, 201


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, debug=True)
