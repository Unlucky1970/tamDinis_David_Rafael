package pt.smartaccess.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pt.smartaccess.app.data.AlteracaoUtilizador
import pt.smartaccess.app.data.NovoUtilizador
import pt.smartaccess.app.data.SmartAccessApi
import pt.smartaccess.app.data.Utilizador

class UtilizadoresViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<List<Utilizador>>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<List<Utilizador>>> = _estado

    override suspend fun atualizar() {
        _estado.carregarDe { api.utilizadores() }
    }

    fun criar(novo: NovoUtilizador) =
        executar("${novo.nome} criado como ${textoPerfil(novo.perfil).lowercase()}") {
            api.criarUtilizador(novo)
        }

    fun mudarPerfil(utilizador: Utilizador, perfil: String) =
        executar("${utilizador.nome} passou a ${textoPerfil(perfil).lowercase()}") {
            api.alterarUtilizador(utilizador.id, AlteracaoUtilizador(perfil = perfil))
        }
}
