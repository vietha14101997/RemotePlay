package com.reka.remoteplay.app.di

import com.reka.remoteplay.feature.connection.data.repository.ConnectionStateRepositoryImpl
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindConnectionStateRepository(
        impl: ConnectionStateRepositoryImpl
    ): ConnectionStateRepository
}
