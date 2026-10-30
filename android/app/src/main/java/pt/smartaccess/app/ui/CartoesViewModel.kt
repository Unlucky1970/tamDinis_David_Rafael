package pt.smartaccess.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pt.smartaccess.app.data.AlteracaoCartao
import pt.smartaccess.app.data.Cartao
import pt.smartaccess.app.data.Permissao
import pt.smartaccess.app.data.SmartAccessApi
import pt.smartaccess.app.data.Utilizador

class CartoesViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<List<Cartao>>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<List<Cartao>>> = _estado

    private val _utilizadores = MutableStateFlow<List<Utilizador>>(emptyList())
    val utilizadores: StateFlow<List<Utilizador>> = _utilizadores

    override suspend fun atualizar() {
        _estado.carregarDe { api.cartoes() }
        try {
            _utilizadores.value = api.utilizadores()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // mantem a lista anterior
        }
    }

    // pendente != null: aprova um cartao lido numa porta; null: adiciona um cartao novo pelo UID
    fun associar(uid: String, escolha: EscolhaPessoa, pendente: Cartao?) =
        executar("Cartao $uid associado a ${escolha.nome}") {
            associarCartao(api, uid, escolha, pendente?.id)
        }

    fun revogar(cartao: Cartao) =
        executar("Cartao ${cartao.uid} revogado") {
            api.alterarCartao(cartao.id, AlteracaoCartao(estado = "revogado"))
        }

    fun reativar(cartao: Cartao) =
        executar("Cartao ${cartao.uid} reativado") {
            api.alterarCartao(cartao.id, AlteracaoCartao(estado = "ativo"))
        }

    fun guardarHorarios(cartao: Cartao, permissoes: List<Permissao>) =
        executar("Horario guardado") {
            api.definirPermissoes(cartao.id, permissoes)
        }
}
