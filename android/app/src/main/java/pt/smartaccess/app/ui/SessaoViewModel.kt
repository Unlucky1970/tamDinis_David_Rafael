package pt.smartaccess.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pt.smartaccess.app.data.DadosSessao
import pt.smartaccess.app.data.PedidoLogin
import pt.smartaccess.app.data.SessaoStore
import pt.smartaccess.app.data.SmartAccessApi
import pt.smartaccess.app.data.mensagemErro

sealed interface EstadoSessao {
    data object ACarregar : EstadoSessao
    data object SemSessao : EstadoSessao
    data class Ativa(val dados: DadosSessao) : EstadoSessao
}

class SessaoViewModel(aplicacao: Application) : AndroidViewModel(aplicacao) {

    private val store = SessaoStore(aplicacao)
    private val api = SmartAccessApi.instancia

    val sessao: StateFlow<EstadoSessao> = store.dados
        .map { dados ->
            SmartAccessApi.token = dados?.token
            if (dados == null) EstadoSessao.SemSessao else EstadoSessao.Ativa(dados)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EstadoSessao.ACarregar)

    private val _aEntrar = MutableStateFlow(false)
    val aEntrar: StateFlow<Boolean> = _aEntrar

    private val _erroLogin = MutableStateFlow<String?>(null)
    val erroLogin: StateFlow<String?> = _erroLogin

    init {
        SmartAccessApi.aoExpirarSessao = { sair() }
        atualizarPerfil()
    }

    // O perfil fica guardado desde o login: se um admin o mudar entretanto (ou a base for atualizada),
    // a app mostraria os separadores errados. Pede os dados atuais a API sempre que a app arranca.
    fun atualizarPerfil() {
        viewModelScope.launch {
            val atual = (sessao.first { it != EstadoSessao.ACarregar } as? EstadoSessao.Ativa)?.dados ?: return@launch
            try {
                val utilizador = api.eu()
                if (utilizador != atual.utilizador) store.guardar(atual.copy(utilizador = utilizador))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // sem rede: fica o perfil guardado (um 401 ja termina a sessao pelo interceptor)
            }
        }
    }

    fun entrar(email: String, password: String) {
        if (email.isBlank() || password.isEmpty()) {
            _erroLogin.value = "Preencha o email e a palavra-passe"
            return
        }
        viewModelScope.launch {
            _aEntrar.value = true
            _erroLogin.value = null
            try {
                val resposta = api.login(PedidoLogin(email.trim(), password))
                store.guardar(DadosSessao(resposta.token, resposta.utilizador))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _erroLogin.value = e.mensagemErro()
            } finally {
                _aEntrar.value = false
            }
        }
    }

    fun sair() {
        viewModelScope.launch { store.limpar() }
    }

    override fun onCleared() {
        SmartAccessApi.aoExpirarSessao = null
    }
}
