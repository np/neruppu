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
import org.havenapp.neruppu.domain.di.HttpsTransport
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.HttpsConfigRepository
import org.havenapp.neruppu.domain.repository.MatrixConfigRepository
import org.havenapp.neruppu.domain.repository.TelegramConfigRepository
import org.havenapp.neruppu.domain.transport.AlertTransport
import org.havenapp.neruppu.domain.util.HttpsBodyEncoding
import org.havenapp.neruppu.domain.util.HttpsPreset
import org.havenapp.neruppu.domain.util.HttpsRequestTemplate
import javax.inject.Inject

data class HttpsUiState(
    val endpointUrl: String = "",
    val encoding: HttpsBodyEncoding = HttpsBodyEncoding.MULTIPART,
    val headers: String = "",
    val fields: String = "",
    val fileField: String = "",
    override val isSaved: Boolean = false,
    override val isLoading: Boolean = false,
    override val testStatus: TestStatus? = null
) : IntegrationConfigUiState

@HiltViewModel
class HttpsSettingsViewModel @Inject constructor(
    private val configRepository: HttpsConfigRepository,
    private val telegramConfigRepository: TelegramConfigRepository,
    private val matrixConfigRepository: MatrixConfigRepository,
    private val alertTargetRepository: AlertTargetRepository,
    @HttpsTransport private val alertTransport: AlertTransport
) : ViewModel(), IntegrationConfigActions {

    private val _uiState = MutableStateFlow(
        if (configRepository.endpointUrl.isBlank()) {
            HttpsPreset.NERUPPU_RECEIVER.toState()
        } else {
            HttpsUiState(
                endpointUrl = configRepository.endpointUrl,
                encoding = configRepository.bodyEncoding,
                headers = configRepository.headers,
                fields = configRepository.fields,
                fileField = configRepository.fileField,
                isSaved = configRepository.isComplete
            )
        }
    )
    val uiState: StateFlow<HttpsUiState> = _uiState.asStateFlow()

    fun onEndpointUrlChange(value: String) = _uiState.update { it.copy(endpointUrl = value) }
    fun onEncodingChange(value: HttpsBodyEncoding) = _uiState.update { it.copy(encoding = value) }
    fun onHeadersChange(value: String) = _uiState.update { it.copy(headers = value) }
    fun onFieldsChange(value: String) = _uiState.update { it.copy(fields = value) }
    fun onFileFieldChange(value: String) = _uiState.update { it.copy(fileField = value) }

    /** Fills the form only; nothing is stored until Save. */
    fun applyPreset(preset: HttpsPreset) {
        _uiState.update { preset.toState().copy(isSaved = it.isSaved) }
    }

    override fun saveConfig() {
        val state = _uiState.value
        val parsed = HttpsRequestTemplate.parse(
            state.endpointUrl, state.encoding, state.headers, state.fields, state.fileField
        )
        if (parsed.isFailure) {
            _uiState.update {
                it.copy(testStatus = TestStatus(success = false, error = parsed.exceptionOrNull()?.message))
            }
            return
        }
        configRepository.endpointUrl = state.endpointUrl
        configRepository.bodyEncoding = state.encoding
        configRepository.headers = state.headers
        configRepository.fields = state.fields
        configRepository.fileField = state.fileField
        _uiState.update { it.copy(isSaved = configRepository.isComplete, testStatus = null) }
        if (configRepository.isComplete) {
            telegramConfigRepository.clear()
            matrixConfigRepository.clear()
            alertTargetRepository.setActiveTarget(AlertTarget.HTTPS)
        }
    }

    override fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, testStatus = null) }
            val result = alertTransport.testConnection()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    testStatus = TestStatus(
                        success = result.isSuccess,
                        error = result.exceptionOrNull()?.message
                    )
                )
            }
        }
    }

    override fun sendMockMessage() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, testStatus = null) }
            val payload = AlertPayload.TextAlert(
                sensorType = SensorType.POWER,
                message = "This is a test message from Neruppu to verify your connection.",
                timestamp = System.currentTimeMillis()
            )
            val result = alertTransport.send(payload)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    testStatus = TestStatus(
                        success = result.isSuccess,
                        error = if (result.isSuccess) "Mock message sent!" else result.exceptionOrNull()?.message
                    )
                )
            }
        }
    }
}

private fun HttpsPreset.toState() = HttpsUiState(
    endpointUrl = url,
    encoding = encoding,
    headers = headers,
    fields = fields,
    fileField = fileField
)
