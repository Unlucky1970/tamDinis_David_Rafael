package pt.smartaccess.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pt.smartaccess.app.data.HorariosPerfis
import pt.smartaccess.app.data.Permissao
import pt.smartaccess.app.data.SmartAccessApi

// Horario por omissao de cada perfil (usado pelos cartoes sem horario proprio)
class HorariosViewModel(
    private val api: SmartAccessApi = SmartAccessApi.instancia,
) : BaseViewModel() {

    private val _estado = MutableStateFlow<Carregamento<HorariosPerfis>>(Carregamento.ACarregar)
    val estado: StateFlow<Carregamento<HorariosPerfis>> = _estado

    override suspend fun atualizar() {
        _estado.carregarDe { api.horarios() }
    }

    fun guardar(perfil: String, horario: List<Permissao>) =
        executar("Horario do ${textoPerfil(perfil).lowercase()} guardado") {
            api.definirHorario(perfil, horario)
        }
}
