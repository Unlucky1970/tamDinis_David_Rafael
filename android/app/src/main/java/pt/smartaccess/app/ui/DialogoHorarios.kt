package pt.smartaccess.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pt.smartaccess.app.data.Permissao

private data class LinhaHorario(val ativo: Boolean, val inicio: String, val fim: String)

private val FORMATO_HORA = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

// Uma janela por dia da semana (se houver varias no mesmo dia, so a primeira e editada).
// Usado nos horarios proprios dos cartoes e nos horarios de cada perfil.
@Composable
fun DialogoHorarios(
    titulo: String,
    permissoes: List<Permissao>,
    aviso: String?,
    aoFechar: () -> Unit,
    aoGuardar: (List<Permissao>) -> Unit,
) {
    val linhas = remember {
        mutableStateListOf<LinhaHorario>().apply {
            for (dia in 1..7) {
                val p = permissoes.firstOrNull { it.diaSemana == dia }
                add(LinhaHorario(p != null, p?.horaInicio ?: "08:00", p?.horaFim ?: "20:00"))
            }
        }
    }
    var erro by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(titulo) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                aviso?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                linhas.forEachIndexed { i, linha ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = linha.ativo, onCheckedChange = { linhas[i] = linha.copy(ativo = it) })
                        Text(DIAS_SEMANA[i], modifier = Modifier.width(36.dp))
                        CampoHora(linha.inicio, linha.ativo) { linhas[i] = linha.copy(inicio = it) }
                        Text(" - ")
                        CampoHora(linha.fim, linha.ativo) { linhas[i] = linha.copy(fim = it) }
                    }
                }
                erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ativas = linhas.withIndex().filter { it.value.ativo }
                val invalida = ativas.firstOrNull { (_, l) ->
                    !FORMATO_HORA.matches(l.inicio) || !FORMATO_HORA.matches(l.fim) || l.fim <= l.inicio
                }
                if (invalida != null) {
                    erro = "${DIAS_SEMANA[invalida.index]}: use HH:MM e um fim depois do inicio"
                } else {
                    aoGuardar(ativas.map { (i, l) -> Permissao(i + 1, l.inicio, l.fim) })
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

@Composable
private fun CampoHora(valor: String, ativo: Boolean, aoMudar: (String) -> Unit) {
    OutlinedTextField(
        value = valor,
        onValueChange = { if (it.length <= 5) aoMudar(it) },
        enabled = ativo,
        singleLine = true,
        placeholder = { Text("HH:MM") },
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.width(76.dp),
    )
}
