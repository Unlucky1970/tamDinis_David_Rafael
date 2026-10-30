"""SmartAccess - API REST (Flask + PostgreSQL).

No Vercel todas as rotas sao encaminhadas para este ficheiro (ver vercel.json).
Localmente: flask --app api/index run --port 8000 --debug   (le o .env automaticamente)

Autenticacao:
  - Arduino: cabecalho "x-api-key". A chave e gerada pela API no registo do dispositivo
             (POST /api/device/register, pelo MAC) e guardada na tabela dispositivos como SHA-256.
  - App:     cabecalho "Authorization: Bearer <token>" obtido em /api/auth/login
Erros: {"detail": "mensagem"} com o codigo HTTP correspondente.

Perfis: admin (docente administrador), funcionario, docente.
"""

import hashlib
import os
import re
import secrets
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
VALIDADE_COMANDO = timedelta(minutes=1)   # um pedido de abertura antigo ja nao abre a porta
TENTATIVAS_PARA_ALERTA = 3

PERFIS = ("admin", "funcionario", "docente")
PERFIS_QUE_FECHAM_QUALQUER_AULA = ("admin", "funcionario")


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
# tipo: dict para objetos {"...": ...}, list para listas (horarios)
def corpo_json(tipo=dict):
    dados = request.get_json(silent=True)
    if dados is None:
        raise ErroApi(400, "O corpo tem de ser JSON")
    if not isinstance(dados, tipo):
        raise ErroApi(422, "O corpo tem de ser " + ("um objeto JSON" if tipo is dict else "uma lista JSON"))
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


# [{"dia_semana": 1, "hora_inicio": "08:00", "hora_fim": "20:00"}, ...] -> [(1, time, time), ...]
def ler_janelas(lista) -> list:
    if not isinstance(lista, list):
        raise ErroApi(422, "O corpo tem de ser uma lista de horarios")
    janelas = []
    for p in lista:
        if not isinstance(p, dict):
            raise ErroApi(422, "Cada horario tem de ser um objeto")
        dia = ler_inteiro(p.get("dia_semana"), "dia_semana", 1, 7)
        inicio = ler_hora(p.get("hora_inicio"), "hora_inicio")
        fim = ler_hora(p.get("hora_fim"), "hora_fim")
        if fim <= inicio:
            raise ErroApi(422, "hora_fim tem de ser depois de hora_inicio")
        janelas.append((dia, inicio, fim))
    return janelas


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


def hash_chave(chave: str) -> str:
    return hashlib.sha256(chave.encode()).hexdigest()


# Identifica o Arduino pela chave e regista o contacto (serve de heartbeat).
# Um dispositivo pendente ou revogado tambem fica com o contacto registado (o admin ve que esta ligado),
# mas so um dispositivo ativo pode usar as rotas.
def dispositivo_atual():
    chave = request.headers.get("x-api-key", "")
    if not chave:
        raise ErroApi(401, "Chave em falta")
    dispositivo = bd().execute(
        """UPDATE dispositivos SET ultimo_contacto = now()
           WHERE api_key_hash = %s
           RETURNING id, nome, estado""",
        (hash_chave(chave),),
    ).fetchone()
    if not dispositivo:
        raise ErroApi(401, "Chave invalida")
    if dispositivo["estado"] == "pendente":
        g.con.commit()   # guarda o contacto antes do erro (o erro faz rollback)
        raise ErroApi(403, "Dispositivo a aguardar aprovacao")
    if dispositivo["estado"] == "revogado":
        raise ErroApi(403, "Dispositivo revogado")
    return dispositivo


def ler_configuracao(con, chave: str, omissao: str) -> str:
    linha = con.execute("SELECT valor FROM configuracao WHERE chave = %s", (chave,)).fetchone()
    return linha["valor"] if linha else omissao


# ======================================================
#  REGRAS DE NEGOCIO
# ======================================================
# Usa as permissoes proprias do cartao; se nao tiver nenhuma, usa o horario do perfil
def dentro_do_horario(con, cartao_id: int, perfil: str) -> bool:
    agora = datetime.now(FUSO)
    return con.execute(
        """SELECT 1 FROM (
             SELECT dia_semana, hora_inicio, hora_fim FROM permissoes WHERE cartao_id = %(c)s
             UNION ALL
             SELECT dia_semana, hora_inicio, hora_fim FROM horarios_perfil
             WHERE perfil = %(p)s AND NOT EXISTS (SELECT 1 FROM permissoes WHERE cartao_id = %(c)s)
           ) h
           WHERE dia_semana = %(d)s AND hora_inicio <= %(t)s AND %(t)s < hora_fim""",
        {"c": cartao_id, "p": perfil, "d": agora.isoweekday(), "t": agora.time()},
    ).fetchone() is not None


def aula_aberta(con, dispositivo_id: int):
    return con.execute(
        """SELECT a.*, u.nome AS dono, u.perfil AS dono_perfil
           FROM aulas a LEFT JOIN utilizadores u ON u.id = a.aberta_por
           WHERE a.dispositivo_id = %s AND a.fim IS NULL""",
        (dispositivo_id,),
    ).fetchone()


def resumo_aula(aula) -> dict:
    fim = aula["fim"] or datetime.now(timezone.utc)
    return {
        "id": aula["id"],
        "dono": aula.get("dono"),
        "dono_perfil": aula.get("dono_perfil"),
        "cartao_uid": aula["cartao_uid"],
        "inicio": aula["inicio"],
        "duracao_min": int((fim - aula["inicio"]).total_seconds() // 60),
        "entradas": aula["entradas"],
        "saidas": aula["saidas"],
        "entradas_nao_detetadas": aula["entradas_nao_detetadas"],
    }


def abrir_aula(con, dispositivo_id: int, utilizador_id, cartao_uid, registo_id):
    con.execute(
        """INSERT INTO aulas (dispositivo_id, aberta_por, cartao_uid, registo_abertura_id)
           VALUES (%s, %s, %s, %s)""",
        (dispositivo_id, utilizador_id, cartao_uid, registo_id),
    )
    con.execute("UPDATE dispositivos SET porta_aberta = TRUE WHERE id = %s", (dispositivo_id,))
    return aula_aberta(con, dispositivo_id)


def fechar_aula(con, aula, fechada_por, motivo: str) -> dict:
    con.execute(
        "UPDATE aulas SET fim = now(), fechada_por = %s, motivo_fim = %s WHERE id = %s",
        (fechada_por, motivo, aula["id"]),
    )
    con.execute("UPDATE dispositivos SET porta_aberta = FALSE WHERE id = %s", (aula["dispositivo_id"],))
    return resumo_aula({**aula, "fim": datetime.now(timezone.utc)})


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
             AND motivo <> 'aula_a_decorrer'   -- cartao valido, so nao pode fechar a aula de outro
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
        "SELECT id, nome FROM dispositivos WHERE estado = 'ativo' AND ultimo_contacto < now() - %s",
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
@app.get("/")
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
# Registo automatico: o Arduino envia o seu MAC e recebe uma chave unica (so e mostrada aqui).
# O dispositivo fica pendente ate um administrador o aprovar na app (PATCH /api/devices/{id}).
# Se o mesmo MAC voltar a registar-se (ex.: a memoria do Arduino foi apagada), recebe uma
# chave nova e volta a ficar pendente: a chave antiga deixa de funcionar.
# Um dispositivo revogado continua revogado (registar-se de novo nao desfaz a revogacao).
@app.post("/api/device/register")
def registar_dispositivo():
    mac = str(corpo_json().get("mac", "")).strip().upper()
    if not re.fullmatch(r"([0-9A-F]{2}:){5}[0-9A-F]{2}", mac):
        raise ErroApi(422, "'mac' tem de estar no formato AA:BB:CC:DD:EE:FF")

    chave = secrets.token_hex(32)
    con = bd()
    dispositivo = con.execute(
        """INSERT INTO dispositivos (nome, mac, api_key_hash, estado)
           VALUES (%(nome)s, %(mac)s, %(hash)s, 'pendente')
           ON CONFLICT (mac) DO UPDATE
             SET api_key_hash = EXCLUDED.api_key_hash, porta_aberta = FALSE,
                 estado = CASE WHEN dispositivos.estado = 'revogado' THEN 'revogado' ELSE 'pendente' END
           RETURNING id, nome, estado""",
        {"nome": "PORTA-" + mac.replace(":", "")[-6:], "mac": mac, "hash": hash_chave(chave)},
    ).fetchone()
    con.execute(
        "UPDATE aulas SET fim = now(), motivo_fim = 'sistema' WHERE dispositivo_id = %s AND fim IS NULL",
        (dispositivo["id"],),
    )
    criar_alerta(
        con, "dispositivo_novo", dispositivo["id"],
        f"{dispositivo['nome']} ({mac}) registou-se e aguarda aprovacao",
    )
    return {"api_key": chave, "nome": dispositivo["nome"], "estado": "pendente"}, 201


# Configuracao do Arduino, pedida no arranque: nome da porta, cartoes que funcionam sem rede,
# intervalo de contacto e a aula que estiver a decorrer (para retomar depois de um reinicio)
@app.get("/api/device/config")
def configuracao_dispositivo():
    dispositivo = dispositivo_atual()
    con = bd()
    perfis_offline = [p.strip() for p in ler_configuracao(con, "perfis_offline", "admin").split(",") if p.strip()]
    cartoes = con.execute(
        """SELECT c.uid FROM cartoes c JOIN utilizadores u ON u.id = c.utilizador_id
           WHERE c.estado = 'ativo' AND u.perfil = ANY(%s)
           ORDER BY c.id LIMIT 15""",
        (perfis_offline,),
    ).fetchall()
    aula = aula_aberta(con, dispositivo["id"])
    pessoas = con.execute(
        "SELECT pessoas_dentro FROM dispositivos WHERE id = %s", (dispositivo["id"],)
    ).fetchone()["pessoas_dentro"]
    return {
        "nome": dispositivo["nome"],
        "intervalo_comandos_s": int(ler_configuracao(con, "intervalo_comandos_s", "5")),
        "cartoes_offline": ",".join(c["uid"] for c in cartoes),
        "pessoas_dentro": pessoas,
        "aula": resumo_aula(aula) if aula else None,
    }


# Decide o que acontece quando um cartao e passado:
#   sala fechada  -> "abrir" (inicia a aula) se o cartao for valido e estiver dentro do horario
#   aula aberta   -> "fechar" se for o dono da aula, um admin ou um funcionario (a qualquer hora)
#                 -> negado "aula_a_decorrer" para os outros
# 200 -> {"acesso": "concedido", "acao": "abrir"|"fechar", "nome", "perfil", "aula": {...}}
# 403 -> {"acesso": "negado", "motivo", "nome", "perfil", "aula"}
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
    aula = aula_aberta(con, dispositivo["id"])
    dono_da_aula = bool(aula and cartao and cartao["utilizador_id"] is not None
                        and cartao["utilizador_id"] == aula["aberta_por"])

    acao, motivo = None, None
    if cartao is None:
        # Cartao novo: fica pendente para o administrador aprovar na app
        con.execute("INSERT INTO cartoes (uid) VALUES (%s) ON CONFLICT (uid) DO NOTHING", (uid,))
        motivo = "cartao_desconhecido"
    elif dono_da_aula:
        acao = "fechar"   # o dono fecha sempre a sua aula, mesmo fora do horario
    elif cartao["estado"] == "pendente":
        motivo = "cartao_pendente"
    elif cartao["estado"] == "revogado":
        motivo = "cartao_revogado"
    elif aula and cartao["perfil"] in PERFIS_QUE_FECHAM_QUALQUER_AULA:
        acao = "fechar"
    elif aula:
        motivo = "aula_a_decorrer"
    elif cartao["perfil"] != "admin" and not dentro_do_horario(con, cartao["id"], cartao["perfil"]):
        motivo = "fora_do_horario"
    else:
        acao = "abrir"

    registo = con.execute(
        """INSERT INTO registos_acesso (dispositivo_id, cartao_uid, utilizador_id, origem, resultado, motivo)
           VALUES (%s, %s, %s, 'cartao', %s, %s) RETURNING id""",
        (
            dispositivo["id"], uid, cartao["utilizador_id"] if cartao else None,
            "concedido" if acao else "negado",
            {"abrir": "inicio_aula", "fechar": "fim_aula"}.get(acao, motivo),
        ),
    ).fetchone()

    pessoa = {"nome": cartao["nome"] if cartao else None, "perfil": cartao["perfil"] if cartao else None}
    if acao == "abrir":
        aula = abrir_aula(con, dispositivo["id"], cartao["utilizador_id"], uid, registo["id"])
        return {"acesso": "concedido", "acao": "abrir", **pessoa, "aula": resumo_aula(aula)}
    if acao == "fechar":
        motivo_fim = "dono" if dono_da_aula else cartao["perfil"]
        resumo = fechar_aula(con, aula, cartao["utilizador_id"], motivo_fim)
        return {"acesso": "concedido", "acao": "fechar", **pessoa, "motivo_fim": motivo_fim, "aula": resumo}

    verificar_tentativas_negadas(con, dispositivo)
    return {"acesso": "negado", "motivo": motivo, **pessoa, "aula": resumo_aula(aula) if aula else None}, 403


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

    # Abertura remota executada: a aula fica em nome de quem a pediu na app
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
        aula = aula_aberta(con, dispositivo["id"]) or abrir_aula(
            con, dispositivo["id"], comando["criado_por"], None, registo["id"]
        )
        return {"registo_id": registo["id"], "aula": resumo_aula(aula)}

    if tipo == "passagem":
        sentido = ler_opcao(evento.get("sentido"), "sentido", ("entrada", "saida"))
        aula = aula_aberta(con, dispositivo["id"])
        antes = con.execute(
            "SELECT pessoas_dentro FROM dispositivos WHERE id = %s", (dispositivo["id"],)
        ).fetchone()["pessoas_dentro"]
        # Saida com a sala vazia: a pessoa saiu, mas a entrada dela escapou aos sensores
        nao_detetada = sentido == "saida" and antes == 0

        con.execute(
            """INSERT INTO passagens (dispositivo_id, sentido, registo_acesso_id, aula_id)
               VALUES (%s, %s, %s, %s)""",
            (dispositivo["id"], sentido, aula["registo_abertura_id"] if aula else None, aula["id"] if aula else None),
        )
        pessoas = con.execute(
            """UPDATE dispositivos SET pessoas_dentro = greatest(pessoas_dentro + %s, 0)
               WHERE id = %s RETURNING pessoas_dentro""",
            (1 if sentido == "entrada" else -1, dispositivo["id"]),
        ).fetchone()["pessoas_dentro"]

        if aula:
            aula = con.execute(
                f"""UPDATE aulas SET {'entradas = entradas + 1' if sentido == 'entrada' else 'saidas = saidas + 1'},
                       entradas_nao_detetadas = entradas_nao_detetadas + %s
                    WHERE id = %s RETURNING *""",
                (1 if nao_detetada else 0, aula["id"]),
            ).fetchone()
        elif sentido == "entrada":
            # Entrada com a sala fechada: a porta foi forcada ou ficou mal trancada
            criar_alerta(
                con, "entrada_sem_acesso", dispositivo["id"],
                f"Entrada em {dispositivo['nome']} sem aula a decorrer",
                unico=False,
            )
        return {
            "pessoas_dentro": pessoas,
            "autorizada": aula is not None or sentido == "saida",
            "entrada_nao_detetada": nao_detetada,
            "aula": {"entradas": aula["entradas"], "saidas": aula["saidas"]} if aula else None,
        }

    # Estado da porta mudado no proprio Arduino (sem rede): abrir com um cartao offline ou fechar a sala
    if tipo == "porta":
        aberta = evento.get("aberta")
        if not isinstance(aberta, bool):
            raise ErroApi(422, "'aberta' tem de ser true ou false")
        aula = aula_aberta(con, dispositivo["id"])
        if aberta and not aula:
            uid = ler_uid(evento["uid"]) if evento.get("uid") else None
            dono = con.execute(
                "SELECT utilizador_id FROM cartoes WHERE uid = %s", (uid,)
            ).fetchone() if uid else None
            abrir_aula(con, dispositivo["id"], dono["utilizador_id"] if dono else None, uid, None)
        elif not aberta and aula:
            fechar_aula(con, aula, None, "offline")

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
                     ORDER BY r.data_hora DESC LIMIT 1) x) AS ultimo_acesso,
                  (SELECT row_to_json(x) FROM (
                     SELECT a.id, a.inicio, u.nome AS dono, u.perfil AS dono_perfil, a.entradas, a.saidas
                     FROM aulas a LEFT JOIN utilizadores u ON u.id = a.aberta_por
                     WHERE a.dispositivo_id = d.id AND a.fim IS NULL) x) AS aula
           FROM dispositivos d WHERE d.estado = 'ativo' ORDER BY d.id""",
        (OFFLINE_APOS,),
    ).fetchall()


@app.post("/api/door/unlock")
def abrir_porta():
    admin = exigir_admin()
    dados = request.get_json(silent=True) or {}
    con = bd()

    if dados.get("dispositivo_id") is not None:
        dispositivo_id = ler_inteiro(dados["dispositivo_id"], "dispositivo_id")
        dispositivo = con.execute(
            "SELECT id, nome FROM dispositivos WHERE id = %s AND estado = 'ativo'", (dispositivo_id,)
        ).fetchone()
    else:
        dispositivo = con.execute(
            "SELECT id, nome FROM dispositivos WHERE estado = 'ativo' ORDER BY id LIMIT 1"
        ).fetchone()
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
    permissoes = ler_janelas(corpo_json(list))

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
    perfil = ler_opcao(dados.get("perfil", "docente"), "perfil", PERFIS)
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


# Mudar o perfil ou o nome de um utilizador
@app.patch("/api/users/<int:utilizador_id>")
def alterar_utilizador(utilizador_id: int):
    exigir_admin()
    dados = corpo_json()
    con = bd()
    atual = con.execute("SELECT nome, perfil FROM utilizadores WHERE id = %s", (utilizador_id,)).fetchone()
    if not atual:
        raise ErroApi(404, "Utilizador nao encontrado")
    perfil = ler_opcao(dados["perfil"], "perfil", PERFIS) if dados.get("perfil") is not None else atual["perfil"]
    nome = str(dados.get("nome") or atual["nome"]).strip()
    return con.execute(
        "UPDATE utilizadores SET nome = %s, perfil = %s WHERE id = %s RETURNING id, nome, email, perfil",
        (nome, perfil, utilizador_id),
    ).fetchone()


# ======================================================
#  ROTAS - APP: DISPOSITIVOS, AULAS E HORARIOS
# ======================================================
@app.get("/api/devices")
def listar_dispositivos():
    exigir_admin()
    return bd().execute(
        """SELECT id, nome, mac, estado, porta_aberta, pessoas_dentro, ultimo_contacto, criado_em,
                  coalesce(ultimo_contacto > now() - %s, false) AS online
           FROM dispositivos
           ORDER BY (estado = 'pendente') DESC, id""",
        (OFFLINE_APOS,),
    ).fetchall()


# Aprovar ({"estado": "ativo", "nome": "Sala 1.04"}), revogar ({"estado": "revogado"}) ou mudar o nome
@app.patch("/api/devices/<int:dispositivo_id>")
def alterar_dispositivo(dispositivo_id: int):
    exigir_admin()
    dados = corpo_json()
    con = bd()
    atual = con.execute("SELECT nome, estado FROM dispositivos WHERE id = %s", (dispositivo_id,)).fetchone()
    if not atual:
        raise ErroApi(404, "Dispositivo nao encontrado")
    estado = ler_opcao(dados["estado"], "estado", ("ativo", "revogado")) if dados.get("estado") else atual["estado"]
    nome = str(dados.get("nome") or atual["nome"]).strip()[:16]   # o LCD so tem 16 colunas
    try:
        return con.execute(
            """UPDATE dispositivos SET nome = %s, estado = %s WHERE id = %s
               RETURNING id, nome, mac, estado, ultimo_contacto""",
            (nome, estado, dispositivo_id),
        ).fetchone()
    except psycopg.errors.UniqueViolation:
        raise ErroApi(409, "Ja existe um dispositivo com esse nome")


# Historico das aulas (um docente ou funcionario so ve as suas)
@app.get("/api/classes")
def listar_aulas():
    utilizador = utilizador_atual()
    limite = ler_inteiro(request.args.get("limite", 50), "limite", 1, 500)
    so_minhas = utilizador["perfil"] != "admin"
    return bd().execute(
        """SELECT a.id, d.nome AS dispositivo, u.nome AS dono, u.perfil AS dono_perfil, a.cartao_uid,
                  a.inicio, a.fim, f.nome AS fechada_por, a.motivo_fim,
                  a.entradas, a.saidas, a.entradas_nao_detetadas
           FROM aulas a
           JOIN dispositivos d ON d.id = a.dispositivo_id
           LEFT JOIN utilizadores u ON u.id = a.aberta_por
           LEFT JOIN utilizadores f ON f.id = a.fechada_por
           WHERE NOT %(so_minhas)s OR a.aberta_por = %(eu)s
           ORDER BY a.inicio DESC
           LIMIT %(limite)s""",
        {"so_minhas": so_minhas, "eu": utilizador["id"], "limite": limite},
    ).fetchall()


@app.get("/api/schedules")
def listar_horarios():
    exigir_admin()
    linhas = bd().execute(
        """SELECT perfil, dia_semana, to_char(hora_inicio, 'HH24:MI') AS hora_inicio,
                  to_char(hora_fim, 'HH24:MI') AS hora_fim
           FROM horarios_perfil ORDER BY perfil, dia_semana, hora_inicio"""
    ).fetchall()
    horarios = {"docente": [], "funcionario": []}
    for linha in linhas:
        horarios[linha.pop("perfil")].append(linha)
    return horarios


# Substitui o horario de um perfil (docente ou funcionario)
@app.put("/api/schedules/<perfil>")
def definir_horario_perfil(perfil: str):
    exigir_admin()
    ler_opcao(perfil, "perfil", ("docente", "funcionario"))
    janelas = ler_janelas(corpo_json(list))
    con = bd()
    con.execute("DELETE FROM horarios_perfil WHERE perfil = %s", (perfil,))
    for dia, inicio, fim in janelas:
        con.execute(
            "INSERT INTO horarios_perfil (perfil, dia_semana, hora_inicio, hora_fim) VALUES (%s, %s, %s, %s)",
            (perfil, dia, inicio, fim),
        )
    return listar_horarios()[perfil]


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, debug=True)
