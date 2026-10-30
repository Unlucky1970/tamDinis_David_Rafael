package pt.smartaccess.app.data

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import pt.smartaccess.app.BuildConfig
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException

interface SmartAccessApi {
    @POST("api/auth/login")
    suspend fun login(@Body pedido: PedidoLogin): RespostaLogin

    @GET("api/status")
    suspend fun estado(): List<Dispositivo>

    @POST("api/door/unlock")
    suspend fun abrirPorta(@Body pedido: PedidoAbertura): RespostaAbertura

    @GET("api/door/commands/{id}")
    suspend fun comando(@Path("id") id: Long): Comando

    // desde/ate no formato "yyyy-MM-dd"; parametros a null nao sao enviados
    @GET("api/logs")
    suspend fun registos(
        @Query("desde") desde: String? = null,
        @Query("ate") ate: String? = null,
        @Query("utilizador_id") utilizadorId: Long? = null,
        @Query("resultado") resultado: String? = null,
        @Query("limite") limite: Int = 100,
    ): List<Registo>

    @GET("api/cards")
    suspend fun cartoes(): List<Cartao>

    @PATCH("api/cards/{id}")
    suspend fun alterarCartao(@Path("id") id: Long, @Body alteracao: AlteracaoCartao): Cartao

    @PUT("api/cards/{id}/permissions")
    suspend fun definirPermissoes(@Path("id") id: Long, @Body permissoes: List<Permissao>): List<Permissao>

    @GET("api/alerts")
    suspend fun alertas(): List<Alerta>

    @PATCH("api/alerts/{id}")
    suspend fun alterarAlerta(@Path("id") id: Long, @Body alteracao: AlteracaoAlerta): Alerta

    @GET("api/users")
    suspend fun utilizadores(): List<Utilizador>

    @POST("api/users")
    suspend fun criarUtilizador(@Body novo: NovoUtilizador): Utilizador

    @PATCH("api/users/{id}")
    suspend fun alterarUtilizador(@Path("id") id: Long, @Body alteracao: AlteracaoUtilizador): Utilizador

    @GET("api/devices")
    suspend fun dispositivos(): List<DispositivoGestao>

    @PATCH("api/devices/{id}")
    suspend fun alterarDispositivo(@Path("id") id: Long, @Body alteracao: AlteracaoDispositivo): DispositivoGestao

    @GET("api/classes")
    suspend fun aulas(@Query("limite") limite: Int = 50): List<Aula>

    @GET("api/schedules")
    suspend fun horarios(): HorariosPerfis

    // perfil: docente | funcionario
    @PUT("api/schedules/{perfil}")
    suspend fun definirHorario(@Path("perfil") perfil: String, @Body horario: List<Permissao>): List<Permissao>

    companion object {
        // Token JWT da sessao atual (definido pelo SessaoViewModel)
        @Volatile
        var token: String? = null

        // Chamado quando a API responde 401 a um pedido autenticado (token expirado)
        @Volatile
        var aoExpirarSessao: (() -> Unit)? = null

        val instancia: SmartAccessApi by lazy {
            val cliente = OkHttpClient.Builder()
                .addInterceptor { cadeia ->
                    val atual = token
                    val pedido = cadeia.request().newBuilder().apply {
                        if (atual != null) header("Authorization", "Bearer $atual")
                    }.build()
                    val resposta = cadeia.proceed(pedido)
                    if (resposta.code == 401 && atual != null) aoExpirarSessao?.invoke()
                    resposta
                }
                .build()

            Retrofit.Builder()
                .baseUrl(BuildConfig.API_URL)
                .client(cliente)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SmartAccessApi::class.java)
        }
    }
}

// Mensagem legivel a partir de um erro da API ({"detail": "..."}) ou de rede
fun Throwable.mensagemErro(): String = when (this) {
    is HttpException -> runCatching {
        val detalhe = JsonParser.parseString(response()?.errorBody()?.string()).asJsonObject.get("detail")
        if (detalhe.isJsonPrimitive) detalhe.asString else "Dados invalidos"
    }.getOrDefault("Erro ${code()}")
    is IOException -> "Sem ligacao ao servidor"
    else -> message ?: "Erro desconhecido"
}
