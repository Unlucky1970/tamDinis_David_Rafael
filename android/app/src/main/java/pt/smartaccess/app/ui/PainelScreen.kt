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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.smartaccess.app.data.Dispositivo
import pt.smartaccess.app.data.Utilizador

@Composable
fun PainelScreen(utilizador: Utilizador, snackbar: SnackbarHostState, viewModel: PainelViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val aAbrir by viewModel.aAbrir.collectAsStateWithLifecycle()
    var aConfirmar by remember { mutableStateOf<Dispositivo?>(null) }

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(5_000) { viewModel.atualizar() }

    ConteudoCarregado(estado, viewModel::recarregar) { dispositivos ->
        if (dispositivos.isEmpty()) {
            MensagemVazia("Nenhum dispositivo registado")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(dispositivos, key = { it.id }) { dispositivo ->
                    CartaoDispositivo(
                        dispositivo = dispositivo,
                        podeAbrir = utilizador.admin,
                        aAbrir = aAbrir,
                        aoAbrir = { aConfirmar = dispositivo },
                    )
                }
            }
        }
    }

    aConfirmar?.let { dispositivo ->
        AlertDialog(
            onDismissRequest = { aConfirmar = null },
            title = { Text("Abrir porta?") },
            text = { Text("A porta de ${dispositivo.nome} vai ser destrancada durante 5 segundos.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.abrirPorta(dispositivo)
                    aConfirmar = null
                }) { Text("Abrir") }
            },
            dismissButton = {
                TextButton(onClick = { aConfirmar = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun CartaoDispositivo(
    dispositivo: Dispositivo,
    podeAbrir: Boolean,
    aAbrir: Boolean,
    aoAbrir: () -> Unit,
) {
    val verde = Color(0xFF2E7D32)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dispositivo.nome, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (dispositivo.online) "Online" else "Offline",
                    color = if (dispositivo.online) verde else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(12.dp))

            Linha("Porta", if (dispositivo.portaAberta) "Aberta" else "Fechada")
            Linha("Pessoas dentro", dispositivo.pessoasDentro.toString())
            dispositivo.ultimoContacto?.let { Linha("Ultimo contacto", formatarData(it)) }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Ultimo acesso", style = MaterialTheme.typography.labelLarge)
            val ultimo = dispositivo.ultimoAcesso
            if (ultimo == null) {
                Text("Sem registos", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "${formatarData(ultimo.dataHora)} - ${ultimo.utilizador ?: "Cartao desconhecido"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    ultimo.resultado.replaceFirstChar { it.uppercase() } +
                        if (ultimo.origem == "remoto") " (remoto)" else "",
                    color = if (ultimo.resultado == "concedido") verde else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (podeAbrir) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = aoAbrir,
                    enabled = !aAbrir && dispositivo.online,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (aAbrir) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Abrir porta")
                    }
                }
            }
        }
    }
}

@Composable
private fun Linha(rotulo: String, valor: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rotulo, style = MaterialTheme.typography.bodyMedium)
        Text(valor, style = MaterialTheme.typography.bodyLarge)
    }
}
