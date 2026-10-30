package pt.smartaccess.app.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pt.smartaccess.app.data.Dispositivo
import pt.smartaccess.app.data.PedidoAbertura
import pt.smartaccess.app.data.SmartAccessApi
import pt.smartaccess.app.data.mensagemErro

class PainelViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<List<Dispositivo>>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<List<Dispositivo>>> = _estado

    private val _aAbrir = MutableStateFlow(false)
    val aAbrir: StateFlow<Boolean> = _aAbrir

    override suspend fun atualizar() {
        _estado.carregarDe { api.estado() }
    }

    // Cria o comando e espera que o Arduino o execute (consulta a cada 2 s, ate 1 minuto)
    fun abrirPorta(dispositivo: Dispositivo) {
        viewModelScope.launch {
            _aAbrir.value = true
            try {
                val pedido = api.abrirPorta(PedidoAbertura(dispositivo.id))
                mostrarMensagem("Pedido enviado para ${pedido.dispositivo}")

                var estadoComando = "pendente"
                var tentativas = 0
                while (estadoComando == "pendente" && tentativas < 30) {
                    delay(2000)
                    estadoComando = api.comando(pedido.comandoId).estado
                    tentativas++
                }
                mostrarMensagem(
                    when (estadoComando) {
                        "executado" -> "Porta aberta"
                        "expirado" -> "O dispositivo nao respondeu a tempo"
                        else -> "Sem confirmacao do dispositivo"
                    }
                )
                atualizar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mostrarMensagem(e.mensagemErro())
            } finally {
                _aAbrir.value = false
            }
        }
    }
}
