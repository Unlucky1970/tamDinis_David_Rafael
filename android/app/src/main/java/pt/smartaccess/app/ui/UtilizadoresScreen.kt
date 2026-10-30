package pt.smartaccess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.smartaccess.app.data.NovoUtilizador
import pt.smartaccess.app.data.Utilizador

@Composable
fun UtilizadoresScreen(eu: Utilizador, snackbar: SnackbarHostState, viewModel: UtilizadoresViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    var aCriar by remember { mutableStateOf(false) }
    var aMudarPerfil by remember { mutableStateOf<Utilizador?>(null) }

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(30_000) { viewModel.atualizar() }

    Box(Modifier.fillMaxSize()) {
        ConteudoCarregado(estado, viewModel::recarregar) { utilizadores ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(utilizadores, key = { it.id }) { u ->
                    CartaoUtilizador(
                        utilizador = u,
                        souEu = u.id == eu.id,
                        aoMudarPerfil = { aMudarPerfil = u },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { aCriar = true },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Novo utilizador") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (aCriar) {
        DialogoNovoUtilizador(
            aoFechar = { aCriar = false },
            aoCriar = {
                viewModel.criar(it)
                aCriar = false
            },
        )
    }

    aMudarPerfil?.let { u ->
        DialogoPerfil(
            titulo = "Perfil de ${u.nome}",
            inicial = u.perfil,
            aoFechar = { aMudarPerfil = null },
            aoEscolher = {
                viewModel.mudarPerfil(u, it)
                aMudarPerfil = null
            },
        )
    }
}

@Composable
private fun CartaoUtilizador(utilizador: Utilizador, souEu: Boolean, aoMudarPerfil: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(utilizador.nome + if (souEu) " (eu)" else "", style = MaterialTheme.typography.titleMedium)
                Text(utilizador.email, style = MaterialTheme.typography.bodySmall)
                Text(
                    textoPerfil(utilizador.perfil),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Um admin nao muda o proprio perfil (ficaria sem acesso a gestao)
            if (!souEu) OutlinedButton(onClick = aoMudarPerfil) { Text("Perfil") }
        }
    }
}

@Composable
private fun EscolhaPerfil(escolhido: String, aoEscolher: (String) -> Unit) {
    PERFIS.forEach { perfil ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = escolhido == perfil, onClick = { aoEscolher(perfil) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = escolhido == perfil, onClick = null)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(textoPerfil(perfil))
                Text(descricaoPerfil(perfil), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DialogoPerfil(titulo: String, inicial: String, aoFechar: () -> Unit, aoEscolher: (String) -> Unit) {
    var perfil by remember { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(titulo) },
        text = { Column { EscolhaPerfil(perfil) { perfil = it } } },
        confirmButton = {
            TextButton(onClick = { aoEscolher(perfil) }, enabled = perfil != inicial) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

@Composable
private fun DialogoNovoUtilizador(aoFechar: () -> Unit, aoCriar: (NovoUtilizador) -> Unit) {
    var nome by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var perfil by remember { mutableStateOf("docente") }
    val valido = nome.isNotBlank() && "@" in email && password.length >= 6

    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Novo utilizador") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(nome, { nome = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(
                    email, { email = it.trim() },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Palavra-passe") },
                    supportingText = { Text("Pelo menos 6 caracteres") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(8.dp))
                Text("Perfil", style = MaterialTheme.typography.labelLarge)
                EscolhaPerfil(perfil) { perfil = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { aoCriar(NovoUtilizador(nome.trim(), email, password, perfil)) },
                enabled = valido,
            ) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}
