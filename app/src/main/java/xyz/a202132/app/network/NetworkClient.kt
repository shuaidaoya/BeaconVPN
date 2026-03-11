package xyz.a202132.app.network

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import xyz.a202132.app.AppConfig
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

object NetworkClient {

    fun withUserAgent(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder.addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", AppConfig.HTTP_USER_AGENT)
                .build()
            chain.proceed(request)
        }
    }
    
    private val okHttpClient = withUserAgent(OkHttpClient.Builder())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = GsonBuilder()
        .setLenient()
        .create()
    
    // 订阅响应的字符串转换器
    private val stringConverterFactory = object : Converter.Factory() {
        override fun responseBodyConverter(
            type: Type,
            annotations: Array<out Annotation>,
            retrofit: Retrofit
        ): Converter<ResponseBody, *>? {
            return if (type == String::class.java) {
                Converter<ResponseBody, String> { it.string() }
            } else {
                null
            }
        }
    }
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://your-server.com/")
        .client(okHttpClient)
        .addConverterFactory(stringConverterFactory)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    val apiService: ApiService = retrofit.create(ApiService::class.java)
    
    // OkHttpClient 用于延迟测试（更短的超时时间）
    val latencyTestClient = withUserAgent(OkHttpClient.Builder())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
}
