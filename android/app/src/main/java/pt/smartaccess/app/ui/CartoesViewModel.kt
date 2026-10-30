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

    fun aprovar(cartao: Cartao, utilizador: Utilizador) =
        executar("Cartao ${cartao.uid} aprovado para ${utilizador.nome}") {
            api.alterarCartao(cartao.id, AlteracaoCartao(estado = "ativo", utilizadorId = utilizador.id))
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
        executar("Horarios guardados") {
            api.definirPermissoes(cartao.id, permissoes)
        }
}
