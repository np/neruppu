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

package org.havenapp.neruppu.ui.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.havenapp.neruppu.domain.di.NtfyTransport
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.HttpsConfigRepository
import org.havenapp.neruppu.domain.repository.MatrixConfigRepository
import org.havenapp.neruppu.domain.repository.NtfyAlertConfigRepository
import org.havenapp.neruppu.domain.repository.TelegramConfigRepository
import org.havenapp.neruppu.domain.transport.AlertTransport
import javax.inject.Inject

data class NtfyUiState(
    val server: String = NtfyAlertConfigRepository.DEFAULT_SERVER,
    val topic: String = "",
    val accessToken: String = "",
    val attachCaptures: Boolean = true,
    override val isSaved: Boolean = false,
    override val isLoading: Boolean = false,
    override val testStatus: TestStatus? = null
) : IntegrationConfigUiState

@HiltViewModel
class NtfySettingsViewModel @Inject constructor(
    private val configRepository: NtfyAlertConfigRepository,
    private val telegramConfigRepository: TelegramConfigRepository,
    private val matrixConfigRepository: MatrixConfigRepository,
    private val httpsConfigRepository: HttpsConfigRepository,
    private val alertTargetRepository: AlertTargetRepository,
    @NtfyTransport private val alertTransport: AlertTransport
) : ViewModel(), IntegrationConfigActions {

    private val _uiState = MutableStateFlow(storedState())
    val uiState: StateFlow<NtfyUiState> = _uiState.asStateFlow()

    /** Stored settings; a fresh random topic proposed when none is set yet. */
    private fun storedState() = NtfyUiState(
        server = configRepository.server,
        topic = configRepository.topic.ifBlank { NtfyAlertConfigRepository.randomTopic() },
        accessToken = configRepository.accessToken,
        attachCaptures = configRepository.attachCaptures,
        isSaved = configRepository.isComplete
    )

    fun refresh() { _uiState.value = storedState() }

    fun onServerChange(value: String) = _uiState.update { it.copy(server = value) }
    fun onTopicChange(value: String) = _uiState.update { it.copy(topic = value) }
    fun onAccessTokenChange(value: String) = _uiState.update { it.copy(accessToken = value) }
    fun onAttachCapturesChange(value: Boolean) = _uiState.update { it.copy(attachCaptures = value) }
    fun newTopic() = _uiState.update { it.copy(topic = NtfyAlertConfigRepository.randomTopic()) }

    override fun saveConfig() {
        val state = _uiState.value
        val problem = when {
            !NtfyAlertConfigRepository.isValidServer(state.server) -> "Server must start with https://"
            !NtfyAlertConfigRepository.isValidTopic(state.topic) -> "Topic: 1 to 64 letters, digits, - or _"
            else -> null
        }
        if (problem != null) {
            _uiState.update { it.copy(testStatus = TestStatus(success = false, error = problem)) }
            return
        }
        // One channel at a time; clearing the others also resets the target, so set it last.
        telegramConfigRepository.clear()
        matrixConfigRepository.clear()
        httpsConfigRepository.clear()
        configRepository.server = state.server
        configRepository.topic = state.topic
        configRepository.accessToken = state.accessToken
        configRepository.attachCaptures = state.attachCaptures
        alertTargetRepository.setActiveTarget(AlertTarget.NTFY)
        _uiState.update { it.copy(isSaved = configRepository.isComplete, testStatus = null) }
    }

    override fun testConnection() = run(isMock = false)

    override fun sendMockMessage() = run(isMock = true)

    private fun run(isMock: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, testStatus = null) }
            val result = if (isMock) {
                alertTransport.send(
                    AlertPayload.TextAlert(SensorType.POWER, "This is a test message from Neruppu.", System.currentTimeMillis())
                )
            } else {
                alertTransport.testConnection()
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    testStatus = TestStatus(
                        success = result.isSuccess,
                        error = if (result.isSuccess) "Sent: check the ntfy app" else result.exceptionOrNull()?.message
                    )
                )
            }
        }
    }
}
