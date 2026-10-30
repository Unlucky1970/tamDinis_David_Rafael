package pt.smartaccess.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.smartaccess.app.data.Utilizador

@Composable
fun AppSmartAccess(sessaoViewModel: SessaoViewModel = viewModel()) {
    val sessao by sessaoViewModel.sessao.collectAsStateWithLifecycle()

    when (val s = sessao) {
        EstadoSessao.ACarregar -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        EstadoSessao.SemSessao -> LoginScreen(sessaoViewModel)
        is EstadoSessao.Ativa -> EcraPrincipal(s.dados.utilizador, aoSair = sessaoViewModel::sair)
    }
}

private enum class Separador(val titulo: String, val icone: ImageVector, val soAdmin: Boolean) {
    PAINEL("Painel", Icons.Default.Home, false),
    AULAS("Aulas", Icons.Default.DateRange, false),
    REGISTOS("Registos", Icons.AutoMirrored.Filled.List, false),
    GESTAO("Gestao", Icons.Default.Settings, true),
    ALERTAS("Alertas", Icons.Default.Warning, true),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EcraPrincipal(utilizador: Utilizador, aoSair: () -> Unit) {
    val separadores = Separador.entries.filter { utilizador.admin || !it.soAdmin }
    var atual by rememberSaveable { mutableStateOf(Separador.PAINEL) }
    val snackbar = remember { SnackbarHostState() }
    // Os ViewModels ficam associados ao utilizador, para nao mostrar dados de outra sessao
    val chave = "u${utilizador.id}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(atual.titulo)
                        Text(
                            "${utilizador.nome} (${textoPerfil(utilizador.perfil)})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = aoSair) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sair")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                separadores.forEach { separador ->
                    NavigationBarItem(
                        selected = separador == atual,
                        onClick = { atual = separador },
                        icon = { Icon(separador.icone, contentDescription = null) },
                        label = { Text(separador.titulo) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { margens ->
        Box(Modifier.fillMaxSize().padding(margens)) {
            when (atual) {
                Separador.PAINEL -> PainelScreen(utilizador, snackbar, viewModel(key = "painel-$chave"))
                Separador.AULAS -> AulasScreen(utilizador, snackbar, viewModel(key = "aulas-$chave"))
                Separador.REGISTOS -> RegistosScreen(utilizador, snackbar, viewModel(key = "registos-$chave"))
                Separador.GESTAO -> GestaoScreen(utilizador, snackbar, chave)
                Separador.ALERTAS -> AlertasScreen(snackbar, viewModel(key = "alertas-$chave"))
            }
        }
    }
}
