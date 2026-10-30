package pt.smartaccess.app.data

import okhttp3.OkHttpClient
import pt.smartaccess.app.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface SmartAccessApi {
    @GET("api/sessions")
    suspend fun aulas(
        @Query("sala") sala: String? = null,
        @Query("limite") limite: Int = 50,
    ): List<Aula>

    companion object {
        val instancia: SmartAccessApi by lazy {
            val cliente = OkHttpClient.Builder()
                .addInterceptor { cadeia ->
                    cadeia.proceed(
                        cadeia.request().newBuilder()
                            .header("x-api-key", BuildConfig.API_KEY)
                            .build()
                    )
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
