package ink.underflo.wristbrief.mobile.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ink.underflo.wristbrief.mobile.R
import kotlinx.coroutines.launch

/**
 * Maps gateway email-auth error codes to user-facing strings. Upstream details
 * (exception messages, provider JSON) are never shown to the user.
 */
fun emailAuthErrorMessageRes(code: String?): Int = when (code) {
    "invalid_credentials" -> R.string.auth_error_invalid_credentials
    "registration_closed" -> R.string.auth_error_registration_closed
    "email_already_registered" -> R.string.auth_error_email_taken
    "weak_password", "invalid_email" -> R.string.auth_error_weak_request
    "rate_limited" -> R.string.auth_error_rate_limited
    "auth_network_error" -> R.string.auth_error_network
    "auth_not_configured" -> R.string.auth_error_network
    else -> R.string.auth_error_generic
}

/** Email + password sign-in / registration block shown while signed out. */
@Composable
internal fun EmailSignInSection(
    busy: Boolean,
    onSignIn: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String) -> Unit,
    onForgotPassword: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.auth_email_label)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.auth_password_label)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = { onSignIn(email.trim(), password) },
            ) {
                Text(stringResource(R.string.auth_sign_in_email))
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = { onRegister(email.trim(), password) },
            ) {
                Text(stringResource(R.string.auth_create_account))
            }
        }
        TextButton(enabled = !busy, onClick = onForgotPassword) {
            Text(stringResource(R.string.auth_forgot_password))
        }
    }
}

/** Two-step password recovery: request a reset link, then apply the token. */
@Composable
internal fun PasswordResetDialog(
    busy: Boolean,
    message: String?,
    onDismissRequest: () -> Unit,
    onRequestReset: suspend (email: String) -> EmailFlowResult,
    onResetPassword: suspend (token: String, newPassword: String) -> EmailFlowResult,
) {
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        title = { Text(stringResource(R.string.auth_reset_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.auth_email_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy && email.isNotBlank(),
                    onClick = {
                        scope.launch { onRequestReset(email.trim()) }
                    },
                ) {
                    Text(stringResource(R.string.auth_reset_request))
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.auth_reset_token_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text(stringResource(R.string.auth_new_password_label)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && token.isNotBlank() && newPassword.isNotBlank(),
                onClick = {
                    scope.launch { onResetPassword(token.trim(), newPassword) }
                },
            ) {
                Text(stringResource(R.string.auth_reset_submit))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismissRequest) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Scenario A: add email sign-in to the currently signed-in account. */
@Composable
internal fun LinkEmailIdentitySection(
    busy: Boolean,
    onLink: (email: String, password: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(enabled = !busy, onClick = { expanded = !expanded }) {
            Text(stringResource(R.string.auth_link_email_title))
        }
        if (expanded) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.auth_email_label)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.auth_new_password_label)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                onClick = { onLink(email.trim(), password) },
            ) {
                Text(stringResource(R.string.auth_link_email_submit))
            }
        }
    }
}
