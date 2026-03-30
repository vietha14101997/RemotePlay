package com.reka.remoteplay.core.network.relay

import com.reka.remoteplay.feature.auth.data.AuthRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RelayModule {

    @Provides
    @Singleton
    fun provideRelayApi(
        tokenManager: TokenManager,
        authRepositoryProvider: Provider<AuthRepository>
    ): RelayApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val baseUrl = (tokenManager.relayUrl ?: "http://34.87.150.141:8443").trimEnd('/')
                
                val requestBuilder = original.newBuilder()
                
                // Replace placeholder URL
                val newUrl = original.url.toString()
                    .replace("http://relay-placeholder", baseUrl)
                requestBuilder.url(newUrl)

                // Add Auth header if token exists
                tokenManager.accessToken?.let {
                    requestBuilder.header("Authorization", "Bearer $it")
                }

                chain.proceed(requestBuilder.build())
            }
            .authenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (response.code == 401) {
                        val authRepository = authRepositoryProvider.get()
                        val result = runBlocking { authRepository.refreshToken() }
                        if (result.isSuccess) {
                            return response.request.newBuilder()
                                .header("Authorization", "Bearer ${tokenManager.accessToken}")
                                .build()
                        }
                    }
                    return null
                }
            })
            .build()

        return Retrofit.Builder()
            .baseUrl("http://relay-placeholder/")
            .addConverterFactory(MoshiConverterFactory.create())
            .client(client)
            .build()
            .create(RelayApi::class.java)
    }
}
