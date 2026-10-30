package pt.smartaccess.app.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.smartaccess.app.data.Cartao

private enum class FiltroCartoes(val titulo: String, val estado: String?) {
    TODOS("Todos", null),
    PENDENTES("Por aprovar", "pendente"),
    ATIVOS("Ativos", "ativo"),
    REVOGADOS("Revogados", "revogado"),
}

// Cartao a associar: um pendente (UID conhecido) ou um novo escrito a mao
private data class AAssociar(val pendente: Cartao?)

@Composable
fun CartoesScreen(snackbar: SnackbarHostState, viewModel: CartoesViewModel) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val utilizadores by viewModel.utilizadores.collectAsStateWithLifecycle()
    var aAssociar by remember { mutableStateOf<AAssociar?>(null) }
    var aRevogar by remember { mutableStateOf<Cartao?>(null) }
    var horarios by remember { mutableStateOf<Cartao?>(null) }
    var filtro by rememberSaveable { mutableStateOf(FiltroCartoes.TODOS) }
    var pesquisa by rememberSaveable { mutableStateOf("") }

    MostrarMensagens(viewModel, snackbar)
    AtualizarPeriodicamente(15_000) { viewModel.atualizar() }

    Box(Modifier.fillMaxSize()) {
        ConteudoCarregado(estado, viewModel::recarregar) { cartoes ->
            val pendentes = cartoes.count { it.estado == "pendente" }
            val visiveis = cartoes.filter {
                (filtro.estado == null || it.estado == filtro.estado) &&
                    (pesquisa.isBlank() || contem(it.uid, pesquisa) || contem(it.utilizador, pesquisa))
            }
            Column(Modifier.fillMaxSize()) {
                CampoPesquisa(
                    pesquisa, { pesquisa = it }, "Pesquisar por UID ou nome",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FiltroCartoes.entries.forEach { f ->
                        FilterChip(
                            selected = filtro == f,
                            onClick = { filtro = f },
                            label = {
                                Text(if (f == FiltroCartoes.PENDENTES && pendentes > 0) "${f.titulo} ($pendentes)" else f.titulo)
                            },
                        )
                    }
                }
                when {
                    cartoes.isEmpty() -> EstadoVazio(
                        Icons.Default.Lock, "Ainda nao ha cartoes",
                        "Adicione um pelo UID, ou passe um cartao novo numa porta para ele aparecer aqui.",
                    )
                    visiveis.isEmpty() -> EstadoVazio(Icons.Default.Lock, "Nenhum cartao encontrado")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visiveis, key = { it.id }) { cartao ->
                            CartaoItem(
                                cartao = cartao,
                                donoPerfil = cartao.utilizadorPerfil,
                                aoAprovar = { aAssociar = AAssociar(cartao) },
                                aoRevogar = { aRevogar = cartao },
                                aoReativar = { viewModel.reativar(cartao) },
                                aoEditarHorarios = { horarios = cartao },
                            )
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { aAssociar = AAssociar(null) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Adicionar cartao") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    aAssociar?.let { pedido ->
        DialogoAssociarCartao(
            titulo = if (pedido.pendente != null) "Aprovar cartao" else "Adicionar cartao",
            uidFixo = pedido.pendente?.uid,
            pessoas = utilizadores,
            pessoaInicial = null,
            aoFechar = { aAssociar = null },
            aoConfirmar = { uid, escolha ->
                viewModel.associar(uid, escolha, pedido.pendente)
                aAssociar = null
            },
        )
    }

    aRevogar?.let { cartao ->
        AlertDialog(
            onDismissRequest = { aRevogar = null },
            title = { Text("Revogar cartao?") },
            text = { Text("O cartao ${cartao.uid} (${cartao.utilizador}) deixa de abrir as portas imediatamente.") },
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
fun EtiquetaEstadoCartao(estado: String) {
    val c = MaterialTheme.colorScheme
    when (estado) {
        "ativo" -> Etiqueta("Ativo", c.secondaryContainer, c.onSecondaryContainer)
        "revogado" -> Etiqueta("Revogado", c.errorContainer, c.onErrorContainer)
        else -> Etiqueta("Por aprovar", c.tertiaryContainer, c.onTertiaryContainer)
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
    CartaoLista(destaque = if (cartao.estado == "pendente") MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(cartao.utilizador ?: "?", donoPerfil)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cartao.uid, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                Text(
                    when {
                        cartao.utilizador == null -> "Sem pessoa associada"
                        donoPerfil == null -> cartao.utilizador
                        else -> "${cartao.utilizador} - ${if (donoPerfil == "admin") "Admin" else textoPerfil(donoPerfil)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EtiquetaEstadoCartao(cartao.estado)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            when {
                cartao.estado == "pendente" -> "Lido numa porta em ${formatarData(cartao.criadoEm)}"
                donoPerfil == "admin" -> "Administrador: entra a qualquer hora"
                cartao.permissoes.isNotEmpty() -> "Horario proprio: ${textoHorario(cartao.permissoes)}"
                donoPerfil == null -> "Horario do perfil"
                else -> "Horario do perfil (${textoPerfil(donoPerfil).lowercase()})"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (cartao.estado) {
                "pendente" -> Button(onClick = aoAprovar) { Text("Associar a alguem") }
                "ativo" -> {
                    // O administrador entra sempre: um horario nao teria efeito
                    if (donoPerfil != "admin") OutlinedButton(onClick = aoEditarHorarios) { Text("Horario") }
                    OutlinedButton(onClick = aoRevogar) { Text("Revogar") }
                }
                "revogado" -> OutlinedButton(onClick = aoReativar) { Text("Reativar") }
            }
        }
    }
}
