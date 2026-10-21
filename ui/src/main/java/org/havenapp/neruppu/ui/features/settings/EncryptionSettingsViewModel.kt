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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.havenapp.neruppu.domain.repository.EncryptionKeyRepository
import javax.inject.Inject

data class EncryptionUiState(
    val recipientsInput: String = "",
    val isConfigured: Boolean = false,
    val error: String? = null
)

/**
 * Edits the age recipients (public keys) that captures are encrypted to.
 * Only public keys are accepted; validation is delegated to the age library.
 */
@HiltViewModel
class EncryptionSettingsViewModel @Inject constructor(
    private val keyRepository: EncryptionKeyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EncryptionUiState(
            recipientsInput = keyRepository.recipients,
            isConfigured = keyRepository.isConfigured
        )
    )
    val uiState: StateFlow<EncryptionUiState> = _uiState.asStateFlow()

    fun onRecipientsChange(value: String) {
        _uiState.update { it.copy(recipientsInput = value, error = null) }
    }

    /** Returns true when the keys were accepted and stored. */
    fun save(): Boolean {
        val keys = _uiState.value.recipientsInput.lines()
        val saved = keyRepository.setRecipients(keys)
        _uiState.update {
            if (saved) {
                it.copy(recipientsInput = keyRepository.recipients, isConfigured = true, error = null)
            } else {
                it.copy(error = "Not a valid age public key (age1…). Never paste a private key here.")
            }
        }
        return saved
    }

    fun clear() {
        keyRepository.clear()
        _uiState.update { EncryptionUiState() }
    }

    /** Reloads after the keys changed elsewhere (e.g. remote-control pairing). */
    fun refresh() {
        _uiState.update {
            EncryptionUiState(recipientsInput = keyRepository.recipients, isConfigured = keyRepository.isConfigured)
        }
    }

    /** Discards unsaved edits. */
    fun reset() {
        _uiState.update {
            it.copy(recipientsInput = keyRepository.recipients, error = null)
        }
    }
}
