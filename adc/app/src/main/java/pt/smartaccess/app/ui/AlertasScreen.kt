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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.smartaccess.app.data.Alerta

@Composable
fun AlertasScreen(snackbar: SnackbarHostState, viewModel: AlertasViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(10_000) { viewModel.atualizar() }

    ConteudoCarregado(estado, viewModel::recarregar) { alertas ->
        if (alertas.isEmpty()) {
            MensagemVazia("Sem alertas")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(alertas, key = { it.id }) { alerta ->
                    CartaoAlerta(alerta, aoResolver = { viewModel.resolver(alerta) })
                }
            }
        }
    }
}

private fun tituloAlerta(tipo: String): String = when (tipo) {
    "tentativas_negadas" -> "Tentativas de acesso negadas"
    "dispositivo_offline" -> "Dispositivo offline"
    "entrada_sem_acesso" -> "Entrada sem acesso"
    else -> tipo
}

@Composable
private fun CartaoAlerta(alerta: Alerta, aoResolver: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (alerta.resolvido) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(tituloAlerta(alerta.tipo), style = MaterialTheme.typography.titleMedium)
            Text(alerta.descricao, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatarData(alerta.dataHora), style = MaterialTheme.typography.bodySmall)
                if (alerta.resolvido) {
                    Text("Resolvido", style = MaterialTheme.typography.labelLarge)
                } else {
                    OutlinedButton(onClick = aoResolver) { Text("Resolver") }
                }
            }
        }
    }
}
