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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.smartaccess.app.data.Aula
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AulasScreen(viewModel: AulasViewModel = viewModel()) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SmartAccess") },
                actions = {
                    IconButton(onClick = viewModel::carregar) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar")
                    }
                },
            )
        },
    ) { margens ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(margens),
            contentAlignment = Alignment.Center,
        ) {
            when (val e = estado) {
                EstadoAulas.ACarregar -> CircularProgressIndicator()
                is EstadoAulas.Erro -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nao foi possivel carregar as aulas", style = MaterialTheme.typography.titleMedium)
                    Text(e.mensagem, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::carregar) { Text("Tentar de novo") }
                }
                is EstadoAulas.Sucesso ->
                    if (e.aulas.isEmpty()) {
                        Text("Ainda nao ha aulas registadas")
                    } else {
                        ListaAulas(e.aulas)
                    }
            }
        }
    }
}

@Composable
private fun ListaAulas(aulas: List<Aula>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(aulas, key = { it.id }) { aula -> CartaoAula(aula) }
    }
}

@Composable
private fun CartaoAula(aula: Aula) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(aula.sala, style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = {},
                    label = { Text(if (aula.aDecorrer) "A decorrer" else "Terminada") },
                )
            }
            Text(aula.professor, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("Inicio: ${formatarData(aula.inicio)}", style = MaterialTheme.typography.bodySmall)
            aula.fim?.let {
                Text(
                    "Fim: ${formatarData(it)} (${aula.motivoFim})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("Passagens: ${aula.totalPassagens}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun formatarData(iso: String): String =
    runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(FORMATO_DATA)
    }.getOrDefault(iso)
