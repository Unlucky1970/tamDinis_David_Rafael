package pt.smartaccess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.smartaccess.app.data.Permissao

@Composable
fun HorariosScreen(snackbar: SnackbarHostState, viewModel: HorariosViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    var aEditar by remember { mutableStateOf<Pair<String, List<Permissao>>?>(null) }

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(60_000) { viewModel.atualizar() }

    ConteudoCarregado(estado, viewModel::recarregar) { horarios ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Horario usado pelos cartoes que nao tem horario proprio. " +
                    "O docente administrador entra a qualquer hora. " +
                    "O dono de uma aula pode sempre fecha-la, mesmo fora do horario.",
                style = MaterialTheme.typography.bodySmall,
            )
            CartaoHorario("docente", horarios.docente) { aEditar = "docente" to horarios.docente }
            CartaoHorario("funcionario", horarios.funcionario) { aEditar = "funcionario" to horarios.funcionario }
        }
    }

    aEditar?.let { (perfil, horario) ->
        DialogoHorarios(
            titulo = "Horario: ${textoPerfil(perfil)}",
            permissoes = horario,
            aviso = if (perfil == "funcionario") "Normalmente mais alargado do que o do docente." else null,
            aoFechar = { aEditar = null },
            aoGuardar = {
                viewModel.guardar(perfil, it)
                aEditar = null
            },
        )
    }
}

@Composable
private fun CartaoHorario(perfil: String, horario: List<Permissao>, aoEditar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(textoPerfil(perfil), style = MaterialTheme.typography.titleMedium)
            Text(descricaoPerfil(perfil), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (horario.isEmpty()) {
                Text("Sem horario: estes cartoes nunca abrem a sala", color = MaterialTheme.colorScheme.error)
            } else {
                horario.sortedBy { it.diaSemana }.forEach {
                    Text("${DIAS_SEMANA[it.diaSemana - 1]}  ${it.horaInicio} - ${it.horaFim}")
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = aoEditar) { Text("Editar") }
        }
    }
}
