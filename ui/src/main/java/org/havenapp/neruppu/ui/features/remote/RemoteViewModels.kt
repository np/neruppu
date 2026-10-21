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

package org.havenapp.neruppu.ui.features.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.havenapp.neruppu.domain.remote.RemoteCommand
import org.havenapp.neruppu.domain.remote.RemoteControlRepository
import org.havenapp.neruppu.domain.remote.RemoteRole
import org.havenapp.neruppu.domain.remote.RemoteStatus
import org.havenapp.neruppu.domain.remote.RemoteUpdate
import javax.inject.Inject

data class RemotePairingUiState(
    val role: RemoteRole = RemoteRole.NONE,
    /** Shown as QR on the remote; contains the channel secret. */
    val pairingCode: String? = null,
    val error: String? = null
)

@HiltViewModel
class RemotePairingViewModel @Inject constructor(
    private val repository: RemoteControlRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemotePairingUiState())
    val uiState: StateFlow<RemotePairingUiState> = _uiState.asStateFlow()

    init {
        // EncryptedSharedPreferences: keep the first read off the main thread.
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh(error: String? = null) {
        val (role, code) = withContext(Dispatchers.IO) { repository.role to repository.pairingCode() }
        _uiState.value = RemotePairingUiState(role, code, error)
    }

    fun becomeRemote(server: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.createControllerPairing(server) } }
            refresh(result.exceptionOrNull()?.message)
        }
    }

    fun pairAsGuard(code: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.pairAsGuard(code) }
            refresh(result.exceptionOrNull()?.message)
        }
    }

    fun unpair() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.unpair() }
            refresh()
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}

data class RemoteControlUiState(
    val connected: Boolean = false,
    val status: RemoteStatus? = null,
    val statusAt: Long? = null,
    val photo: ByteArray? = null,
    val photoAt: Long? = null,
    val waitingFor: RemoteCommand? = null,
    val error: String? = null
)

@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val repository: RemoteControlRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteControlUiState())
    val uiState: StateFlow<RemoteControlUiState> = _uiState.asStateFlow()
    private var listening: Job? = null

    /** Subscribes while the remote screen is visible. */
    fun start() {
        if (listening?.isActive == true) return
        listening = viewModelScope.launch(Dispatchers.IO) {
            repository.updates().collect { update ->
                _uiState.update { state ->
                    when (update) {
                        is RemoteUpdate.Connected -> state.copy(connected = update.connected)
                        is RemoteUpdate.Status -> state.copy(
                            status = update.status, statusAt = update.at, error = null,
                            waitingFor = state.waitingFor.takeIf { it != RemoteCommand.STATUS }
                        )
                        is RemoteUpdate.Photo -> state.copy(
                            photo = update.jpeg, photoAt = update.at, error = null,
                            waitingFor = state.waitingFor.takeIf { it != RemoteCommand.PHOTO }
                        )
                        is RemoteUpdate.Failure -> state.copy(error = update.message, waitingFor = null)
                    }
                }
            }
        }
        send(RemoteCommand.STATUS)
    }

    fun stop() {
        listening?.cancel()
        listening = null
    }

    fun send(command: RemoteCommand) {
        _uiState.update { it.copy(waitingFor = command, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.send(command) }
            result.exceptionOrNull()?.let { e ->
                _uiState.update { it.copy(waitingFor = null, error = e.message ?: "Could not reach ntfy") }
            }
        }
    }
}
