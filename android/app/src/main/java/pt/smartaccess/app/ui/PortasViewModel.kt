package pt.smartaccess.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pt.smartaccess.app.data.AlteracaoDispositivo
import pt.smartaccess.app.data.DispositivoGestao
import pt.smartaccess.app.data.SmartAccessApi

// Arduinos registados: os novos chegam pendentes e so funcionam depois de aprovados
class PortasViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<List<DispositivoGestao>>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<List<DispositivoGestao>>> = _estado

    override suspend fun atualizar() {
        _estado.carregarDe { api.dispositivos() }
    }

    fun aprovar(dispositivo: DispositivoGestao, nome: String) =
        executar("$nome aprovada") {
            api.alterarDispositivo(dispositivo.id, AlteracaoDispositivo(estado = "ativo", nome = nome))
        }

    fun mudarNome(dispositivo: DispositivoGestao, nome: String) =
        executar("Nome alterado para $nome") {
            api.alterarDispositivo(dispositivo.id, AlteracaoDispositivo(nome = nome))
        }

    fun revogar(dispositivo: DispositivoGestao) =
        executar("${dispositivo.nome} revogada") {
            api.alterarDispositivo(dispositivo.id, AlteracaoDispositivo(estado = "revogado"))
        }

    fun reativar(dispositivo: DispositivoGestao) =
        executar("${dispositivo.nome} reativada") {
            api.alterarDispositivo(dispositivo.id, AlteracaoDispositivo(estado = "ativo"))
        }
}
