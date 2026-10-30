package pt.smartaccess.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.smartaccess.app.data.Registo
import pt.smartaccess.app.data.Utilizador

@Composable
fun RegistosScreen(utilizador: Utilizador, snackbar: SnackbarHostState, viewModel: RegistosViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val filtros by viewModel.filtros.collectAsStateWithLifecycle()
    val utilizadores by viewModel.utilizadores.collectAsStateWithLifecycle()

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(10_000) { viewModel.atualizar() }
    if (utilizador.admin) {
        LaunchedEffect(Unit) { viewModel.carregarUtilizadores() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Periodo.entries.forEach { periodo ->
                FilterChip(
                    selected = filtros.periodo == periodo,
                    onClick = { viewModel.alterarFiltros(filtros.copy(periodo = periodo)) },
                    label = { Text(periodo.titulo) },
                )
            }
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(null to "Todos", "concedido" to "Concedidos", "negado" to "Negados").forEach { (valor, titulo) ->
                FilterChip(
                    selected = filtros.resultado == valor,
                    onClick = { viewModel.alterarFiltros(filtros.copy(resultado = valor)) },
                    label = { Text(titulo) },
                )
            }
            if (utilizador.admin) {
                FiltroUtilizador(filtros.utilizador, utilizadores) {
                    viewModel.alterarFiltros(filtros.copy(utilizador = it))
                }
            }
        }

        ConteudoCarregado(estado, viewModel::recarregar) { registos ->
            if (registos.isEmpty()) {
                MensagemVazia("Sem registos para estes filtros")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(registos, key = { it.id }) { registo ->
                        LinhaRegisto(registo)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FiltroUtilizador(
    selecionado: Utilizador?,
    utilizadores: List<Utilizador>,
    aoEscolher: (Utilizador?) -> Unit,
) {
    var aberto by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selecionado != null,
            onClick = { aberto = true },
            label = { Text(selecionado?.nome ?: "Utilizador") },
        )
        DropdownMenu(expanded = aberto, onDismissRequest = { aberto = false }) {
            DropdownMenuItem(text = { Text("Todos") }, onClick = {
                aoEscolher(null)
                aberto = false
            })
            utilizadores.forEach { u ->
                DropdownMenuItem(text = { Text(u.nome) }, onClick = {
                    aoEscolher(u)
                    aberto = false
                })
            }
        }
    }
}

@Composable
private fun LinhaRegisto(registo: Registo) {
    val detalhes = buildList {
        add(formatarData(registo.dataHora))
        add(registo.dispositivo)
        if (registo.origem == "remoto") add("remoto")
        if (!registo.concedido) add(textoMotivo(registo.motivo))
        if (registo.concedido && registo.origem == "cartao") {
            add(if (registo.entradas > 0) "entrou" else "sem entrada detetada")
        }
    }
    ListItem(
        headlineContent = { Text(registo.utilizador ?: "Cartao ${registo.cartaoUid}") },
        supportingContent = { Text(detalhes.joinToString(" - ")) },
        trailingContent = {
            Text(
                if (registo.concedido) "Concedido" else "Negado",
                color = if (registo.concedido) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        },
    )
}
