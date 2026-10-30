package pt.smartaccess.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pt.smartaccess.app.data.Alerta
import pt.smartaccess.app.data.AlteracaoAlerta
import pt.smartaccess.app.data.SmartAccessApi

class AlertasViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<List<Alerta>>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<List<Alerta>>> = _estado

    override suspend fun atualizar() {
        _estado.carregarDe { api.alertas() }
    }

    fun resolver(alerta: Alerta) =
        executar("Alerta marcado como resolvido") {
            api.alterarAlerta(alerta.id, AlteracaoAlerta(resolvido = true))
        }
}
