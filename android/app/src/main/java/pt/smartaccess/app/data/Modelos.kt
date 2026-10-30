package pt.smartaccess.app.data

import com.google.gson.annotations.SerializedName

// Respostas e pedidos da API (ver api/index.py)

data class Utilizador(
    val id: Long,
    val nome: String,
    val email: String,
    val perfil: String,
) {
    val admin: Boolean get() = perfil == "admin"
}

data class PedidoLogin(val email: String, val password: String)

data class RespostaLogin(val token: String, val utilizador: Utilizador)

// GET /api/status
data class Dispositivo(
    val id: Long,
    val nome: String,
    @SerializedName("porta_aberta") val portaAberta: Boolean,
    @SerializedName("pessoas_dentro") val pessoasDentro: Int,
    @SerializedName("ultimo_contacto") val ultimoContacto: String?,
    val online: Boolean,
    @SerializedName("ultimo_acesso") val ultimoAcesso: UltimoAcesso?,
)

data class UltimoAcesso(
    @SerializedName("data_hora") val dataHora: String,
    val resultado: String,
    val origem: String,
    val utilizador: String?,
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
