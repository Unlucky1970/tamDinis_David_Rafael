package pt.smartaccess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import pt.smartaccess.app.data.mensagemErro
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface Carregamento<out T> {
    data object ACarregar : Carregamento<Nothing>
    data class Erro(val mensagem: String) : Carregamento<Nothing>
    data class Sucesso<T>(val dados: T) : Carregamento<T>
}

// Se ja havia dados, um erro numa atualizacao automatica mantem-nos no ecra
suspend fun <T> MutableStateFlow<Carregamento<T>>.carregarDe(pedido: suspend () -> T) {
    try {
        value = Carregamento.Sucesso(pedido())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (value !is Carregamento.Sucesso) value = Carregamento.Erro(e.mensagemErro())
    }
}

// ViewModel com atualizacao dos dados e acoes que mostram uma mensagem no fim
abstract class BaseViewModel : ViewModel() {
    private val _mensagens = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val mensagens: SharedFlow<String> = _mensagens

    abstract suspend fun atualizar()

    fun recarregar() {
        viewModelScope.launch { atualizar() }
    }

    protected fun mostrarMensagem(texto: String) {
        _mensagens.tryEmit(texto)
    }

    protected fun executar(sucesso: String, acao: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                acao()
                mostrarMensagem(sucesso)
                atualizar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mostrarMensagem(e.mensagemErro())
            }
        }
    }
}

@Composable
fun MostrarMensagens(viewModel: BaseViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(viewModel) {
        viewModel.mensagens.collect { snackbar.showSnackbar(it) }
    }
}

// Repete a acao enquanto o ecra esta visivel (para quando a app vai para segundo plano)
@Composable
fun AtualizarPeriodicamente(intervaloMs: Long, acao: suspend () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                acao()
                delay(intervaloMs)
            }
        }
    }
}

@Composable
fun <T> ConteudoCarregado(
    estado: Carregamento<T>,
    aoTentarDeNovo: () -> Unit,
    conteudo: @Composable (T) -> Unit,
) {
    when (estado) {
        Carregamento.ACarregar -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Carregamento.Erro -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Nao foi possivel carregar", style = MaterialTheme.typography.titleMedium)
            Text(estado.mensagem, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = aoTentarDeNovo) { Text("Tentar de novo") }
        }
        is Carregamento.Sucesso -> conteudo(estado.dados)
    }
}

@Composable
fun MensagemVazia(texto: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(texto, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

val DIAS_SEMANA = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sab", "Dom")

private val FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

fun formatarData(iso: String): String =
    runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(FORMATO_DATA)
    }.getOrDefault(iso)

fun textoMotivo(motivo: String?): String = when (motivo) {
    "cartao_desconhecido" -> "Cartao desconhecido"
    "cartao_pendente" -> "Cartao por aprovar"
    "cartao_revogado" -> "Cartao revogado"
    "fora_do_horario" -> "Fora do horario"
    "abertura_remota" -> "Abertura remota"
    null -> ""
    else -> motivo
}
