package pt.smartaccess.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
            EstadoVazio(Icons.Default.CheckCircle, "Sem alertas", "Esta tudo em ordem.")
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
    "dispositivo_offline" -> "Porta sem contacto"
    "entrada_sem_acesso" -> "Entrada com a sala fechada"
    "dispositivo_novo" -> "Porta nova por aprovar"
    else -> tipo
}

@Composable
private fun CartaoAlerta(alerta: Alerta, aoResolver: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // Porta nova e um aviso (ambar); o resto sao problemas (vermelho)
    val (fundo, cor) = when {
        alerta.resolvido -> c.surfaceContainerHighest to c.onSurfaceVariant
        alerta.tipo == "dispositivo_novo" -> c.tertiaryContainer to c.onTertiaryContainer
        else -> c.errorContainer to c.onErrorContainer
    }
    CartaoLista {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).background(fundo, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (alerta.resolvido) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null, tint = cor, modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tituloAlerta(alerta.tipo), style = MaterialTheme.typography.titleMedium)
                Text(alerta.descricao, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatarData(alerta.dataHora), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    if (alerta.resolvido) {
                        Etiqueta("Resolvido", c.surfaceContainerHighest, c.onSurfaceVariant)
                    } else {
                        OutlinedButton(onClick = aoResolver) { Text("Resolver") }
                    }
                }
            }
        }
    }
}
