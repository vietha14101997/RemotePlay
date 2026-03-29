package com.reka.remoteplay.core.network.relay

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RelayModule {

    @Provides
    @Singleton
    fun provideRelayApi(tokenManager: TokenManager): RelayApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val baseUrl = (tokenManager.relayUrl ?: "http://34.87.150.141:8443").trimEnd('/')
                val newUrl = original.url.toString()
                    .replace("http://relay-placeholder", baseUrl)
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("http://relay-placeholder/")
            .addConverterFactory(MoshiConverterFactory.create())
            .client(client)
            .build()
            .create(RelayApi::class.java)
    }
}
