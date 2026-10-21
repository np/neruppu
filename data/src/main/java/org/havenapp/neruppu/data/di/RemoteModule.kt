/*
 * Copyright (C) 2026 thamizh-root
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.havenapp.neruppu.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import org.havenapp.neruppu.data.remote.AgeKeyring
import org.havenapp.neruppu.data.remote.EncryptedRemoteConfigStore
import org.havenapp.neruppu.data.remote.GoAgeKeyring
import org.havenapp.neruppu.data.remote.NtfyClient
import org.havenapp.neruppu.data.remote.NtfyRemoteGuard
import org.havenapp.neruppu.data.remote.RemoteConfigStore
import org.havenapp.neruppu.data.remote.RemoteControlRepositoryImpl
import org.havenapp.neruppu.data.remote.RemoteGuard
import org.havenapp.neruppu.domain.remote.RemoteControlRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteModule {

    @Binds @Singleton
    abstract fun bindRemoteConfig(impl: EncryptedRemoteConfigStore): RemoteConfigStore

    @Binds @Singleton
    abstract fun bindRemoteGuard(impl: NtfyRemoteGuard): RemoteGuard

    @Binds @Singleton
    abstract fun bindRemoteControl(impl: RemoteControlRepositoryImpl): RemoteControlRepository

    @Binds @Singleton
    abstract fun bindAgeKeyring(impl: GoAgeKeyring): AgeKeyring

    companion object {
        /**
         * Own client: no Logging plugin, which would buffer the endless
         * subscription stream, and no 60 s request timeout.
         */
        @Provides @Singleton
        fun provideNtfyClient(): NtfyClient = NtfyClient(
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    requestTimeoutMillis = 60_000
                }
            }
        )
    }
}
