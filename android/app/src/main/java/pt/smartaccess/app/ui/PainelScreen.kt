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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
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
            EstadoVazio(
                Icons.Default.Home, "Nenhuma porta ativa",
                if (utilizador.admin) "Ligue um Arduino e aprove-o em Gestao > Portas." else null,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Resumo(dispositivos) }
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
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("Abrir ${dispositivo.nome}?") },
            text = {
                Text(
                    "A porta e destrancada e comeca uma aula em seu nome. " +
                        "Fica aberta ate ser fechada com o cartao de um administrador ou de um funcionario."
                )
            },
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

// Tres numeros no topo: portas online, aulas a decorrer, pessoas dentro
@Composable
private fun Resumo(dispositivos: List<Dispositivo>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Numero(
            "${dispositivos.count { it.online }}/${dispositivos.size}", "Portas online",
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.weight(1f),
        )
        Numero(
            "${dispositivos.count { it.aula != null }}", "Aulas agora",
            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Modifier.weight(1f),
        )
        Numero(
            "${dispositivos.sumOf { it.pessoasDentro }}", "Pessoas dentro",
            MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Modifier.weight(1f),
        )
    }
}

@Composable
private fun Numero(valor: String, rotulo: String, fundo: Color, cor: Color, modifier: Modifier) {
    Surface(color = fundo, contentColor = cor, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(valor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(rotulo, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CartaoDispositivo(
    dispositivo: Dispositivo,
    podeAbrir: Boolean,
    aAbrir: Boolean,
    aoAbrir: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    CartaoLista {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dispositivo.nome, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    dispositivo.ultimoContacto?.let { "Ultimo contacto ${formatarData(it).takeLast(5)}" } ?: "Sem contacto",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
            Indicador(if (dispositivo.online) "Online" else "Offline", if (dispositivo.online) Verde else c.error)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dispositivo.portaAberta) Etiqueta("Aberta", c.secondaryContainer, c.onSecondaryContainer)
            else Etiqueta("Trancada", c.surfaceContainerHighest, c.onSurfaceVariant)
            Etiqueta("${dispositivo.pessoasDentro} dentro", c.surfaceContainerHighest, c.onSurfaceVariant)
        }

        // Aula a decorrer: quem a abriu, ha quanto tempo e quantos entraram/sairam
        dispositivo.aula?.let { aula ->
            Spacer(Modifier.height(12.dp))
            Surface(color = c.secondaryContainer.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(aula.dono, aula.donoPerfil, tamanho = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Aula a decorrer", style = MaterialTheme.typography.labelMedium, color = c.secondary)
                        Text(aula.dono ?: "Abertura sem dono", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Desde ${formatarData(aula.inicio).takeLast(5)} (${duracao(aula.inicio, null)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${aula.entradas} / ${aula.saidas}", style = MaterialTheme.typography.titleMedium)
                        Text("entr. / saidas", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = c.outlineVariant)
        val ultimo = dispositivo.ultimoAcesso
        if (ultimo == null) {
            Text("Ainda sem acessos", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        } else {
            val concedido = ultimo.resultado == "concedido"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ultimo acesso", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    Text(
                        "${ultimo.utilizador ?: "Cartao desconhecido"} - ${formatarData(ultimo.dataHora).takeLast(5)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ultimo.motivo?.let {
                        Text(
                            textoMotivo(it) + if (ultimo.origem == "remoto") " (remoto)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                        )
                    }
                }
                if (concedido) Etiqueta("Concedido", c.secondaryContainer, c.onSecondaryContainer)
                else Etiqueta("Negado", c.errorContainer, c.onErrorContainer)
            }
        }

        if (podeAbrir) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = aoAbrir,
                enabled = !aAbrir && dispositivo.online && !dispositivo.portaAberta,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    aAbrir -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    dispositivo.portaAberta -> Text("Aula a decorrer")
                    !dispositivo.online -> Text("Porta sem contacto")
                    else -> {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Abrir porta a distancia")
                    }
                }
            }
        }
    }
}
