package pt.smartaccess.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pt.smartaccess.app.data.Aula
import pt.smartaccess.app.data.SmartAccessApi

sealed interface EstadoAulas {
    data object ACarregar : EstadoAulas
    data class Erro(val mensagem: String) : EstadoAulas
    data class Sucesso(val aulas: List<Aula>) : EstadoAulas
}

class AulasViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : ViewModel() {

    private val _estado = MutableStateFlow<EstadoAulas>(EstadoAulas.ACarregar)
    val estado: StateFlow<EstadoAulas> = _estado

    init {
        carregar()
    }

    fun carregar() {
        _estado.value = EstadoAulas.ACarregar
        viewModelScope.launch {
            _estado.value = try {
                EstadoAulas.Sucesso(api.aulas())
            } catch (e: Exception) {
                EstadoAulas.Erro(e.message ?: "Erro desconhecido")
            }
        }
    }
}
