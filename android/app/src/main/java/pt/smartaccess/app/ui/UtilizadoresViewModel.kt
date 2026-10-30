package pt.smartaccess.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pt.smartaccess.app.data.AlteracaoUtilizador
import pt.smartaccess.app.data.Cartao
import pt.smartaccess.app.data.NovoUtilizador
import pt.smartaccess.app.data.SmartAccessApi
import pt.smartaccess.app.data.Utilizador

// Pessoas (docentes, funcionarios e administradores) e os cartoes de cada uma
class UtilizadoresViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<List<Utilizador>>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<List<Utilizador>>> = _estado

    private val _cartoes = MutableStateFlow<List<Cartao>>(emptyList())
    val cartoes: StateFlow<List<Cartao>> = _cartoes

    override suspend fun atualizar() {
        _estado.carregarDe { api.utilizadores() }
        try {
            _cartoes.value = api.cartoes()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // mantem a lista anterior
        }
    }

    fun criar(novo: NovoUtilizador) =
        executar("${novo.nome} criado como ${textoPerfil(novo.perfil).lowercase()}") {
            api.criarUtilizador(novo)
        }

    fun mudarPerfil(utilizador: Utilizador, alteracao: AlteracaoUtilizador) =
        executar("${utilizador.nome} passou a ${textoPerfil(alteracao.perfil).lowercase()}") {
            api.alterarUtilizador(utilizador.id, alteracao)
        }

    // Se ja existir um cartao pendente com este UID (lido numa porta), fica aprovado; senao e criado
    fun associar(uid: String, escolha: EscolhaPessoa) =
        executar("Cartao $uid associado a ${escolha.nome}") {
            val pendente = _cartoes.value.firstOrNull { it.uid == uid && it.estado == "pendente" }
            associarCartao(api, uid, escolha, pendente?.id)
        }
}
