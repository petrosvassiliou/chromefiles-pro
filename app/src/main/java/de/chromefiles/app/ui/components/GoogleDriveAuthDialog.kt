package de.chromefiles.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.chromefiles.app.ui.viewmodel.DriveAuthDialogState

/**
 * Dialog zur echten Google-Drive-Anmeldung:
 * - **1-Klick System-Konto:** Direktes Anmelden mit dem auf dem Android-/ChromeOS-Gerät hinterlegten Google-Konto.
 * - **1-Klick SAF-Integration:** Einbinden des offiziellen Google-Drive-System-Providers.
 * - **Erweitert (OAuth 2.0 PKCE / Access-Token):** Eigene Client-ID oder direktes Token.
 */
@Composable
fun GoogleDriveAuthDialog(
    state: DriveAuthDialogState,
    initialClientId: String,
    onBuildPkceUrl: (String) -> String,
    onConnectWithCode: (clientId: String, codeOrUrl: String) -> Unit,
    onConnectWithToken: (accessToken: String, refreshToken: String, clientId: String) -> Unit,
    onPickSystemGoogleAccount: () -> Unit = {},
    onPickGoogleDriveSaf: () -> Unit = {},
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 = OAuth 2.0 PKCE, 1 = Access-Token
    var clientId by remember { mutableStateOf(initialClientId) }
    var authCodeOrUrl by remember { mutableStateOf("") }
    var accessToken by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!state.isConnecting) onDismiss() },
        icon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
        title = { Text("Google Drive verbinden") },
        text = {
            Column(
                modifier = Modifier.width(460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.connectedEmail != null) {
                    Text(
                        "Verbunden als: ${state.connectedEmail}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Ihr Google Drive ist direkt mit ChromeFiles Pro verbunden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Button(
                        onClick = {
                            onDismiss()
                            onPickSystemGoogleAccount()
                        },
                        enabled = !state.isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Google-Konto vom Gerät wählen (1-Klick)")
                    }
                    FilledTonalButton(
                        onClick = {
                            onDismiss()
                            onPickGoogleDriveSaf()
                        },
                        enabled = !state.isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Google Drive über Android-Dateisystem einbinden")
                    }

                    HorizontalDivider()

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = mode == 0,
                            onClick = { mode = 0 },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("OAuth 2.0 (PKCE)") }
                        SegmentedButton(
                            selected = mode == 1,
                            onClick = { mode = 1 },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("Access-Token") }
                    }

                    if (mode == 0) {
                        Text(
                            "1. OAuth-Client-ID (Typ „Desktop-App“ oder „Webanwendung“ mit Redirect " +
                                "`http://127.0.0.1:53682/oauth2callback`) eintragen und im Browser anmelden.\n" +
                                "2. Nach der Zustimmung den `code`-Parameter oder die komplette Redirect-URL unten einfügen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            singleLine = true,
                            label = { Text("OAuth 2.0 Client-ID") },
                            placeholder = { Text("123456789-abc.apps.googleusercontent.com") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                val url = onBuildPkceUrl(clientId)
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            enabled = clientId.isNotBlank() && !state.isConnecting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Im Browser bei Google anmelden (PKCE)")
                        }
                        OutlinedTextField(
                            value = authCodeOrUrl,
                            onValueChange = { authCodeOrUrl = it },
                            singleLine = true,
                            label = { Text("Autorisierungscode oder Redirect-URL") },
                            placeholder = { Text("4/0AeanS0... oder http://127.0.0.1:53682/oauth2callback?code=...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            "Direkte Anmeldung mit einem OAuth-2.0-Access-Token (Scope `https://www.googleapis.com/auth/drive`). " +
                                "Das Token wird AES-256-GCM-verschlüsselt im Android Keystore gespeichert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = accessToken,
                            onValueChange = { accessToken = it },
                            singleLine = true,
                            label = { Text("OAuth 2.0 Access-Token (ya29.…)") },
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(
                                        if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = "Token ein-/ausblenden"
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = refreshToken,
                            onValueChange = { refreshToken = it },
                            singleLine = true,
                            label = { Text("Refresh-Token (optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            singleLine = true,
                            label = { Text("OAuth Client-ID (optional, für Auto-Refresh)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (state.isConnecting) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Google Drive REST API v3 wird geprüft …", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (state.connectedEmail != null) {
                TextButton(onClick = onDisconnect, enabled = !state.isConnecting) {
                    Text("Konto trennen", color = MaterialTheme.colorScheme.error)
                }
            } else {
                val canSubmit = !state.isConnecting && if (mode == 0) {
                    clientId.isNotBlank() && authCodeOrUrl.isNotBlank()
                } else {
                    accessToken.isNotBlank()
                }
                TextButton(
                    onClick = {
                        if (mode == 0) onConnectWithCode(clientId, authCodeOrUrl)
                        else onConnectWithToken(accessToken, refreshToken, clientId)
                    },
                    enabled = canSubmit
                ) {
                    Text("Verbinden")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isConnecting) {
                Text("Schließen")
            }
        }
    )
}
