package pt.smartaccess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.smartaccess.app.data.Aula
import pt.smartaccess.app.data.Utilizador

@Composable
fun AulasScreen(utilizador: Utilizador, snackbar: SnackbarHostState, viewModel: AulasViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(10_000) { viewModel.atualizar() }

    ConteudoCarregado(estado, viewModel::recarregar) { aulas ->
        if (aulas.isEmpty()) {
            MensagemVazia(
                if (utilizador.admin) "Ainda nao houve aulas" else "Ainda nao abriu nenhuma aula com o seu cartao"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(aulas, key = { it.id }) { aula -> CartaoAula(aula) }
            }
        }
    }
}

@Composable
private fun CartaoAula(aula: Aula) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (aula.aDecorrer) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(aula.dispositivo, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (aula.aDecorrer) "A decorrer" else duracao(aula.inicio, aula.fim),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (aula.aDecorrer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                aula.dono?.let { "$it (${textoPerfil(aula.donoPerfil)})" } ?: "Abertura sem dono",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                formatarData(aula.inicio) + (aula.fim?.let { " - " + formatarData(it).takeLast(5) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Contador("Entradas", aula.entradas)
                Contador("Saidas", aula.saidas)
            }

            if (aula.entradasNaoDetetadas > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${aula.entradasNaoDetetadas} entrada(s) nao detetada(s) pelos sensores",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!aula.aDecorrer) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Fechada " + (aula.fechadaPor?.let { "por $it" } ?: textoMotivoFim(aula.motivoFim)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Contador(rotulo: String, valor: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(rotulo, style = MaterialTheme.typography.bodySmall)
    }
}
