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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import pt.smartaccess.app.data.DispositivoGestao

private const val MAX_NOME = 16   // o LCD do Arduino so tem 16 colunas

@Composable
fun PortasScreen(snackbar: SnackbarHostState, viewModel: PortasViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    var aAprovar by remember { mutableStateOf<DispositivoGestao?>(null) }
    var aRenomear by remember { mutableStateOf<DispositivoGestao?>(null) }
    var aRevogar by remember { mutableStateOf<DispositivoGestao?>(null) }

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(10_000) { viewModel.atualizar() }

    ConteudoCarregado(estado, viewModel::recarregar) { portas ->
        if (portas.isEmpty()) {
            MensagemVazia("Ainda nao ha portas.\nLigue um Arduino: ele regista-se sozinho e aparece aqui para aprovar.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(portas, key = { it.id }) { porta ->
                    CartaoPorta(
                        porta = porta,
                        aoAprovar = { aAprovar = porta },
                        aoRenomear = { aRenomear = porta },
                        aoRevogar = { aRevogar = porta },
                        aoReativar = { viewModel.reativar(porta) },
                    )
                }
            }
        }
    }

    aAprovar?.let { porta ->
        DialogoNome(
            titulo = "Aprovar porta",
            texto = "O Arduino ${porta.mac ?: ""} passa a funcionar. De-lhe o nome da sala (aparece no LCD).",
            inicial = porta.nome,
            confirmar = "Aprovar",
            aoFechar = { aAprovar = null },
            aoConfirmar = {
                viewModel.aprovar(porta, it)
                aAprovar = null
            },
        )
    }

    aRenomear?.let { porta ->
        DialogoNome(
            titulo = "Mudar nome",
            texto = "O novo nome aparece no LCD da porta no proximo contacto com a API.",
            inicial = porta.nome,
            confirmar = "Guardar",
            aoFechar = { aRenomear = null },
            aoConfirmar = {
                viewModel.mudarNome(porta, it)
                aRenomear = null
            },
        )
    }

    aRevogar?.let { porta ->
        AlertDialog(
            onDismissRequest = { aRevogar = null },
            title = { Text("Revogar ${porta.nome}?") },
            text = {
                Text(
                    "A chave deste Arduino deixa de funcionar: a porta deixa de validar cartoes ate ser reativada. " +
                        "Use isto se o Arduino for roubado ou substituido."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revogar(porta)
                    aRevogar = null
                }) { Text("Revogar") }
            },
            dismissButton = { TextButton(onClick = { aRevogar = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CartaoPorta(
    porta: DispositivoGestao,
    aoAprovar: () -> Unit,
    aoRenomear: () -> Unit,
    aoRevogar: () -> Unit,
    aoReativar: () -> Unit,
) {
    val verde = Color(0xFF2E7D32)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (porta.estado == "pendente") {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
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
                Text(porta.nome, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (porta.estado) {
                        "pendente" -> "Por aprovar"
                        "revogado" -> "Revogada"
                        else -> if (porta.online) "Online" else "Offline"
                    },
                    color = when {
                        porta.estado == "revogado" -> MaterialTheme.colorScheme.error
                        porta.estado == "pendente" -> MaterialTheme.colorScheme.tertiary
                        porta.online -> verde
                        else -> MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            porta.mac?.let { Text("MAC $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                porta.ultimoContacto?.let { "Ultimo contacto: ${formatarData(it)}" } ?: "Ainda sem contacto",
                style = MaterialTheme.typography.bodySmall,
            )
            if (porta.estado == "ativo") {
                Text(
                    "${if (porta.portaAberta) "Aula a decorrer" else "Fechada"} - ${porta.pessoasDentro} pessoa(s) dentro",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (porta.estado) {
                    "pendente" -> OutlinedButton(onClick = aoAprovar) { Text("Aprovar") }
                    "ativo" -> {
                        OutlinedButton(onClick = aoRenomear) { Text("Mudar nome") }
                        OutlinedButton(onClick = aoRevogar) { Text("Revogar") }
                    }
                    "revogado" -> OutlinedButton(onClick = aoReativar) { Text("Reativar") }
                }
            }
        }
    }
}

@Composable
private fun DialogoNome(
    titulo: String,
    texto: String,
    inicial: String,
    confirmar: String,
    aoFechar: () -> Unit,
    aoConfirmar: (String) -> Unit,
) {
    var nome by remember { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(titulo) },
        text = {
            Column {
                Text(texto, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nome,
                    onValueChange = { if (it.length <= MAX_NOME) nome = it },
                    label = { Text("Nome (ex.: Sala 1.04)") },
                    supportingText = { Text("${nome.length}/$MAX_NOME") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { aoConfirmar(nome.trim()) }, enabled = nome.isNotBlank()) { Text(confirmar) }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}
