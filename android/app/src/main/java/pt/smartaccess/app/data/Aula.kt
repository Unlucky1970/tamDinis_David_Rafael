package pt.smartaccess.app.data

import com.google.gson.annotations.SerializedName

// Uma linha da vista vw_sessoes (GET /api/sessions)
data class Aula(
    val id: Long,
    val sala: String,
    val professor: String,
    @SerializedName("uid_cartao") val uidCartao: String,
    val inicio: String,
    val fim: String?,
    @SerializedName("motivo_fim") val motivoFim: String?,
    @SerializedName("total_passagens") val totalPassagens: Int,
) {
    val aDecorrer: Boolean get() = fim == null
}
