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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.smartaccess.app.data.Cartao
import pt.smartaccess.app.data.Utilizador

@Composable
fun CartoesScreen(snackbar: SnackbarHostState, viewModel: CartoesViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val utilizadores by viewModel.utilizadores.collectAsStateWithLifecycle()
    var aAprovar by remember { mutableStateOf<Cartao?>(null) }
    var aRevogar by remember { mutableStateOf<Cartao?>(null) }
    var horarios by remember { mutableStateOf<Cartao?>(null) }

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(15_000) { viewModel.atualizar() }

    ConteudoCarregado(estado, viewModel::recarregar) { cartoes ->
        if (cartoes.isEmpty()) {
            MensagemVazia("Ainda nao ha cartoes.\nPasse um cartao novo no leitor para ele aparecer aqui.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cartoes, key = { it.id }) { cartao ->
                    val dono = utilizadores.firstOrNull { it.id == cartao.utilizadorId }
                    CartaoItem(
                        cartao = cartao,
                        donoPerfil = dono?.perfil,
                        aoAprovar = { aAprovar = cartao },
                        aoRevogar = { aRevogar = cartao },
                        aoReativar = { viewModel.reativar(cartao) },
                        aoEditarHorarios = { horarios = cartao },
                    )
                }
            }
        }
    }

    aAprovar?.let { cartao ->
        DialogoAprovar(
            cartao = cartao,
            utilizadores = utilizadores,
            aoFechar = { aAprovar = null },
            aoAprovar = {
                viewModel.aprovar(cartao, it)
                aAprovar = null
            },
        )
    }

    aRevogar?.let { cartao ->
        AlertDialog(
            onDismissRequest = { aRevogar = null },
            title = { Text("Revogar cartao?") },
            text = { Text("O cartao ${cartao.uid} (${cartao.utilizador}) deixa de abrir a porta imediatamente.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revogar(cartao)
                    aRevogar = null
                }) { Text("Revogar") }
            },
            dismissButton = { TextButton(onClick = { aRevogar = null }) { Text("Cancelar") } },
        )
    }

    horarios?.let { cartao ->
        DialogoHorarios(
            titulo = "Horario de ${cartao.uid}",
            permissoes = cartao.permissoes,
            aviso = "Horario proprio deste cartao. Sem nenhum dia marcado, o cartao usa o horario do perfil do dono.",
            aoFechar = { horarios = null },
            aoGuardar = {
                viewModel.guardarHorarios(cartao, it)
                horarios = null
            },
        )
    }
}

@Composable
private fun CartaoItem(
    cartao: Cartao,
    donoPerfil: String?,
    aoAprovar: () -> Unit,
    aoRevogar: () -> Unit,
    aoReativar: () -> Unit,
    aoEditarHorarios: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(cartao.uid, style = MaterialTheme.typography.titleMedium)
                Text(
                    cartao.estado.replaceFirstChar { it.uppercase() },
                    color = when (cartao.estado) {
                        "ativo" -> MaterialTheme.colorScheme.primary
                        "revogado" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                cartao.utilizador?.let { "$it (${textoPerfil(donoPerfil)})" } ?: "Sem utilizador associado",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Lido em ${formatarData(cartao.criadoEm)}", style = MaterialTheme.typography.bodySmall)

            if (cartao.estado == "ativo") {
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        donoPerfil == "admin" -> "Administrador: acesso a qualquer hora"
                        cartao.permissoes.isEmpty() -> "Horario do perfil (${textoPerfil(donoPerfil)})"
                        else -> "Horario proprio: ${textoHorario(cartao.permissoes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (cartao.estado) {
                    "pendente" -> OutlinedButton(onClick = aoAprovar) { Text("Aprovar") }
                    "ativo" -> {
                        OutlinedButton(onClick = aoEditarHorarios) { Text("Horarios") }
                        OutlinedButton(onClick = aoRevogar) { Text("Revogar") }
                    }
                    "revogado" -> OutlinedButton(onClick = aoReativar) { Text("Reativar") }
                }
            }
        }
    }
}

@Composable
private fun DialogoAprovar(
    cartao: Cartao,
    utilizadores: List<Utilizador>,
    aoFechar: () -> Unit,
    aoAprovar: (Utilizador) -> Unit,
) {
    var escolhido by remember { mutableStateOf(utilizadores.firstOrNull { it.id == cartao.utilizadorId }) }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Aprovar ${cartao.uid}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Associar o cartao a:", style = MaterialTheme.typography.bodyMedium)
                utilizadores.forEach { u ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = escolhido == u, onClick = { escolhido = u })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = escolhido == u, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(u.nome)
                            Text("${textoPerfil(u.perfil)} - ${u.email}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { escolhido?.let(aoAprovar) }, enabled = escolhido != null) { Text("Aprovar") }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

