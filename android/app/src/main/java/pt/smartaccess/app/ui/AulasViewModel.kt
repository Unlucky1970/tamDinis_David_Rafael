package pt.smartaccess.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pt.smartaccess.app.data.Aula
import pt.smartaccess.app.data.SmartAccessApi

// Docentes e funcionarios so recebem as suas aulas (a API filtra)
class AulasViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<List<Aula>>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<List<Aula>>> = _estado

    override suspend fun atualizar() {
        _estado.carregarDe { api.aulas(limite = 100) }
    }
}
