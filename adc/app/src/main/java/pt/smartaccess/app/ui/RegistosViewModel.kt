package pt.smartaccess.app.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pt.smartaccess.app.data.Registo
import pt.smartaccess.app.data.SmartAccessApi
import pt.smartaccess.app.data.Utilizador
import java.time.LocalDate

// dias = quantos dias para tras alem de hoje (null = sem limite)
enum class Periodo(val titulo: String, val dias: Long?) {
    HOJE("Hoje", 0),
    SEMANA("7 dias", 6),
    MES("30 dias", 29),
    TUDO("Tudo", null),
}

data class FiltrosRegistos(
    val periodo: Periodo = Periodo.SEMANA,
    val resultado: String? = null,          // null | "concedido" | "negado"
    val utilizador: Utilizador? = null,     // so para administradores
)

class RegistosViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<List<Registo>>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<List<Registo>>> = _estado

    private val _filtros = MutableStateFlow(FiltrosRegistos())
    val filtros: StateFlow<FiltrosRegistos> = _filtros

    private val _utilizadores = MutableStateFlow<List<Utilizador>>(emptyList())
    val utilizadores: StateFlow<List<Utilizador>> = _utilizadores

    override suspend fun atualizar() {
        val f = _filtros.value
        _estado.carregarDe {
            api.registos(
                desde = f.periodo.dias?.let { LocalDate.now().minusDays(it).toString() },
                utilizadorId = f.utilizador?.id,
                resultado = f.resultado,
            )
        }
    }

    fun alterarFiltros(novos: FiltrosRegistos) {
        _filtros.value = novos
        _estado.value = Carregamento.ACarregar
        recarregar()
    }

    fun carregarUtilizadores() {
        if (_utilizadores.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                _utilizadores.value = api.utilizadores()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // o filtro por utilizador fica so com "Todos"
            }
        }
    }
}
