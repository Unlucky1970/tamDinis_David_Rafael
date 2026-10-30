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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
            EstadoVazio(
                Icons.Default.DateRange, "Ainda nao houve aulas",
                if (utilizador.admin) "Uma aula comeca quando alguem passa o cartao numa porta fechada." else null,
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
    val c = MaterialTheme.colorScheme
    CartaoLista(destaque = if (aula.aDecorrer) c.secondaryContainer.copy(alpha = 0.55f) else null) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(aula.dono, aula.donoPerfil)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(aula.dono ?: "Abertura sem dono", style = MaterialTheme.typography.titleMedium)
                    Text(aula.dispositivo, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
                if (aula.aDecorrer) Etiqueta("A decorrer", c.secondary, c.onSecondary)
                else Etiqueta(duracao(aula.inicio, aula.fim), c.surfaceContainerHighest, c.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            // "30/10/2026 09:00 - 10:30"; se acabou noutro dia mostra a data completa do fim
            val inicio = formatarData(aula.inicio)
            val fim = aula.fim?.let(::formatarData)
            Text(
                inicio + when {
                    fim == null -> ""
                    fim.take(10) == inicio.take(10) -> " - " + fim.takeLast(5)
                    else -> " - $fim"
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Contador("Entradas", aula.entradas, Modifier.weight(1f))
                Contador("Saidas", aula.saidas, Modifier.weight(1f))
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
private fun Contador(rotulo: String, valor: Int, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(valor.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(rotulo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
