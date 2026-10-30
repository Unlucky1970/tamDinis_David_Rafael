package pt.smartaccess.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sessao")

data class DadosSessao(val token: String, val utilizador: Utilizador)

// Guarda o token e o utilizador autenticado entre arranques da app
class SessaoStore(private val context: Context) {

    val dados: Flow<DadosSessao?> = context.dataStore.data.map { p ->
        val token = p[TOKEN] ?: return@map null
        DadosSessao(
            token = token,
            utilizador = Utilizador(
                id = p[ID] ?: 0,
                nome = p[NOME].orEmpty(),
                email = p[EMAIL].orEmpty(),
                perfil = p[PERFIL].orEmpty(),
            ),
        )
    }

    suspend fun guardar(sessao: DadosSessao) {
        context.dataStore.edit { p ->
            p[TOKEN] = sessao.token
            p[ID] = sessao.utilizador.id
            p[NOME] = sessao.utilizador.nome
            p[EMAIL] = sessao.utilizador.email.orEmpty()
            p[PERFIL] = sessao.utilizador.perfil
        }
    }

    suspend fun limpar() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("token")
        val ID = longPreferencesKey("id")
        val NOME = stringPreferencesKey("nome")
        val EMAIL = stringPreferencesKey("email")
        val PERFIL = stringPreferencesKey("perfil")
    }
}
