package pt.smartaccess.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.smartaccess.app.data.Utilizador

private val SEPARADORES_GESTAO = listOf("Portas", "Cartoes", "Pessoas", "Horarios")

// So para administradores: portas (Arduinos), cartoes, utilizadores e horarios por perfil
@Composable
fun GestaoScreen(eu: Utilizador, snackbar: SnackbarHostState, chave: String) {
    var atual by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = atual) {
            SEPARADORES_GESTAO.forEachIndexed { i, titulo ->
                Tab(selected = atual == i, onClick = { atual = i }, text = { Text(titulo) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (atual) {
                0 -> PortasScreen(snackbar, viewModel(key = "portas-$chave"))
                1 -> CartoesScreen(snackbar, viewModel(key = "cartoes-$chave"))
                2 -> UtilizadoresScreen(eu, snackbar, viewModel(key = "utilizadores-$chave"))
                else -> HorariosScreen(snackbar, viewModel(key = "horarios-$chave"))
            }
        }
    }
}
