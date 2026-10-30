package pt.smartaccess.app.data

import com.google.gson.annotations.SerializedName

// Respostas e pedidos da API (ver api/index.py)

// So o admin tem email (e conta na app); docentes e funcionarios sao so o nome do cartao
data class Utilizador(
    val id: Long,
    val nome: String,
    val email: String?,
    val perfil: String,
) {
    val admin: Boolean get() = perfil == "admin"
}

data class PedidoLogin(val email: String, val password: String)

data class RespostaLogin(val token: String, val utilizador: Utilizador)

// perfil: admin (docente administrador) | funcionario | docente; email e password so para o admin
data class NovoUtilizador(
    val nome: String,
    val perfil: String,
    val email: String? = null,
    val password: String? = null,
)

// Campos a null nao sao enviados (o Gson omite-os). Passar a admin exige email e password.
data class AlteracaoUtilizador(
    val nome: String? = null,
    val perfil: String? = null,
    val email: String? = null,
    val password: String? = null,
)

// POST /api/cards: cartao adicionado a mao, ja associado a uma pessoa
data class NovoCartao(
    val uid: String,
    @SerializedName("utilizador_id") val utilizadorId: Long,
)

// GET /api/status (so as portas ativas)
data class Dispositivo(
    val id: Long,
    val nome: String,
    @SerializedName("porta_aberta") val portaAberta: Boolean,
    @SerializedName("pessoas_dentro") val pessoasDentro: Int,
    @SerializedName("ultimo_contacto") val ultimoContacto: String?,
    val online: Boolean,
    @SerializedName("ultimo_acesso") val ultimoAcesso: UltimoAcesso?,
    val aula: AulaAtual?,
)

// Aula a decorrer numa porta (dentro de /api/status)
data class AulaAtual(
    val id: Long,
    val inicio: String,
    val dono: String?,
    @SerializedName("dono_perfil") val donoPerfil: String?,
    val entradas: Int,
    val saidas: Int,
)

// GET /api/devices (todas as portas, incluindo as pendentes); estado: pendente | ativo | revogado
data class DispositivoGestao(
    val id: Long,
    val nome: String,
    val mac: String?,
    val estado: String,
    @SerializedName("porta_aberta") val portaAberta: Boolean,
    @SerializedName("pessoas_dentro") val pessoasDentro: Int,
    @SerializedName("ultimo_contacto") val ultimoContacto: String?,
    @SerializedName("criado_em") val criadoEm: String,
    val online: Boolean,
)

data class AlteracaoDispositivo(
    val estado: String? = null,
    val nome: String? = null,
)

// GET /api/classes; motivo_fim: dono | admin | funcionario | offline | sistema
data class Aula(
    val id: Long,
    val dispositivo: String,
    val dono: String?,
    @SerializedName("dono_perfil") val donoPerfil: String?,
    @SerializedName("cartao_uid") val cartaoUid: String?,
    val inicio: String,
    val fim: String?,
    @SerializedName("fechada_por") val fechadaPor: String?,
    @SerializedName("motivo_fim") val motivoFim: String?,
    val entradas: Int,
    val saidas: Int,
    @SerializedName("entradas_nao_detetadas") val entradasNaoDetetadas: Int,
) {
    val aDecorrer: Boolean get() = fim == null
}

// GET /api/schedules (o admin entra sempre, nao tem horario)
data class HorariosPerfis(
    val docente: List<Permissao>,
    val funcionario: List<Permissao>,
)

data class UltimoAcesso(
    @SerializedName("data_hora") val dataHora: String,
    val resultado: String,
    val origem: String,
    val utilizador: String?,
    val motivo: String?,
)

data class PedidoAbertura(@SerializedName("dispositivo_id") val dispositivoId: Long?)

data class RespostaAbertura(
    @SerializedName("comando_id") val comandoId: Long,
    val dispositivo: String,
)

// estado: pendente | executado | expirado
data class Comando(val id: Long, val estado: String)

// GET /api/logs
data class Registo(
    val id: Long,
    @SerializedName("data_hora") val dataHora: String,
    @SerializedName("cartao_uid") val cartaoUid: String?,
    @SerializedName("utilizador_id") val utilizadorId: Long?,
    val utilizador: String?,
    val origem: String,
    val resultado: String,
    val motivo: String?,
    val dispositivo: String,
    val entradas: Int,
) {
    val concedido: Boolean get() = resultado == "concedido"
}

// dia_semana: 1 = segunda ... 7 = domingo; horas no formato "HH:MM"
data class Permissao(
    @SerializedName("dia_semana") val diaSemana: Int,
    @SerializedName("hora_inicio") val horaInicio: String,
    @SerializedName("hora_fim") val horaFim: String,
)

// estado: pendente | ativo | revogado
data class Cartao(
    val id: Long,
    val uid: String,
    val estado: String,
    @SerializedName("utilizador_id") val utilizadorId: Long?,
    val utilizador: String?,
    @SerializedName("utilizador_perfil") val utilizadorPerfil: String?,
    @SerializedName("criado_em") val criadoEm: String,
    val permissoes: List<Permissao>,
)

// Campos a null nao sao enviados (o Gson omite-os)
data class AlteracaoCartao(
    val estado: String? = null,
    @SerializedName("utilizador_id") val utilizadorId: Long? = null,
)

data class Alerta(
    val id: Long,
    val tipo: String,
    val descricao: String,
    @SerializedName("data_hora") val dataHora: String,
    val resolvido: Boolean,
    val dispositivo: String?,
)

data class AlteracaoAlerta(val resolvido: Boolean)
