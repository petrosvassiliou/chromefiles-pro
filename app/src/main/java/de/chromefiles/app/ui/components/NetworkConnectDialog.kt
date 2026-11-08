package de.chromefiles.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.chromefiles.app.data.remote.RemoteConnectionConfig
import de.chromefiles.app.data.remote.RemoteProtocol
import de.chromefiles.app.ui.viewmodel.NetworkDialogState

/**
 * Dialog „Netzwerkfreigabe hinzufügen“ (wie „Dateifreigabe hinzufügen“ in ChromeOS) für SMB, SFTP und WebDAV.
 * Die Verbindung wird beim Klick auf „Verbinden“ real aufgebaut und geprüft.
 */
@Composable
fun NetworkConnectDialog(
    state: NetworkDialogState,
    onConnect: (title: String, config: RemoteConnectionConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var protocol by rememberSaveable { mutableStateOf(RemoteProtocol.SMB) }
    var title by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(RemoteProtocol.SMB.defaultPort.toString()) }
    var portEdited by rememberSaveable { mutableStateOf(false) }
    var share by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }
    var useHttps by rememberSaveable { mutableStateOf(true) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var useSshKey by rememberSaveable { mutableStateOf(false) }
    var privateKeyPem by rememberSaveable { mutableStateOf("") }
    var privateKeyPassphrase by rememberSaveable { mutableStateOf("") }

    fun defaultPortFor(p: RemoteProtocol, https: Boolean) =
        if (p == RemoteProtocol.WEBDAV && !https) 80 else p.defaultPort

    val portNumber = port.toIntOrNull()
    val shareRequired = protocol == RemoteProtocol.SMB
    val sshKeyValid = protocol != RemoteProtocol.SFTP || !useSshKey || privateKeyPem.isNotBlank()
    val canConnect = !state.isConnecting && host.isNotBlank() && portNumber != null && portNumber in 1..65535 &&
        (!shareRequired || share.isNotBlank()) && sshKeyValid

    fun submit() {
        if (!canConnect) return
        onConnect(
            title.trim(),
            RemoteConnectionConfig(
                protocol = protocol,
                host = host.trim(),
                port = portNumber!!,
                shareOrBasePath = share.trim(),
                username = username.trim(),
                password = password,
                domain = domain.trim(),
                useHttps = useHttps,
                privateKeyPem = if (protocol == RemoteProtocol.SFTP && useSshKey) privateKeyPem.trim() else "",
                privateKeyPassphrase = if (protocol == RemoteProtocol.SFTP && useSshKey) privateKeyPassphrase else ""
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Netzwerkfreigabe hinzufügen") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    RemoteProtocol.entries.forEachIndexed { index, p ->
                        SegmentedButton(
                            selected = protocol == p,
                            onClick = {
                                protocol = p
                                if (!portEdited) port = defaultPortFor(p, useHttps).toString()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, RemoteProtocol.entries.size),
                            enabled = !state.isConnecting
                        ) { Text(p.name.replace("WEBDAV", "WebDAV")) }
                    }
                }
                Text(protocol.labelDe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text("Anzeigename (optional)") }, enabled = !state.isConnecting,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it }, singleLine = true,
                        label = { Text("Server (Name oder IP)") }, enabled = !state.isConnecting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { v -> port = v.filter { it.isDigit() }.take(5); portEdited = true },
                        singleLine = true, label = { Text("Port") }, enabled = !state.isConnecting,
                        isError = portNumber == null || portNumber !in 1..65535,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp)
                    )
                }
                OutlinedTextField(
                    value = share, onValueChange = { share = it }, singleLine = true,
                    label = {
                        Text(
                            when (protocol) {
                                RemoteProtocol.SMB -> "Freigabe (z. B. Dokumente)"
                                RemoteProtocol.SFTP -> "Startordner (optional, z. B. /home/nutzer)"
                                RemoteProtocol.WEBDAV -> "Pfad (optional, z. B. /remote.php/dav/files/nutzer)"
                            }
                        )
                    },
                    enabled = !state.isConnecting,
                    modifier = Modifier.fillMaxWidth()
                )
                if (protocol == RemoteProtocol.SMB) {
                    OutlinedTextField(
                        value = domain, onValueChange = { domain = it }, singleLine = true,
                        label = { Text("Domäne (optional)") }, enabled = !state.isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (protocol == RemoteProtocol.WEBDAV) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Verschlüsselt (HTTPS)", modifier = Modifier.weight(1f))
                        Switch(
                            checked = useHttps,
                            onCheckedChange = {
                                useHttps = it
                                if (!portEdited) port = defaultPortFor(protocol, it).toString()
                            },
                            enabled = !state.isConnecting
                        )
                    }
                }
                OutlinedTextField(
                    value = username, onValueChange = { username = it }, singleLine = true,
                    label = { Text("Benutzername") }, enabled = !state.isConnecting,
                    modifier = Modifier.fillMaxWidth()
                )
                if (protocol == RemoteProtocol.SFTP) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mit SSH-Private-Key anmelden (id_ed25519 / id_rsa)", modifier = Modifier.weight(1f))
                        Switch(
                            checked = useSshKey,
                            onCheckedChange = { useSshKey = it },
                            enabled = !state.isConnecting
                        )
                    }
                }
                if (protocol == RemoteProtocol.SFTP && useSshKey) {
                    OutlinedTextField(
                        value = privateKeyPem,
                        onValueChange = { privateKeyPem = it },
                        minLines = 3,
                        maxLines = 5,
                        label = { Text("SSH-Private-Key (PEM / OpenSSH)") },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY----- ...") },
                        enabled = !state.isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = privateKeyPassphrase,
                        onValueChange = { privateKeyPassphrase = it },
                        singleLine = true,
                        label = { Text("Key-Passphrase (optional)") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        enabled = !state.isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("Passwort") }, enabled = !state.isConnecting,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showPassword) "Passwort verbergen" else "Passwort anzeigen"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "Zugangsdaten und SSH-Schlüssel werden verschlüsselt im Android-Keystore gespeichert." +
                        if (protocol == RemoteProtocol.SFTP) " Der SSH-Hostschlüssel wird beim ersten Verbinden gespeichert und danach geprüft." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (protocol == RemoteProtocol.WEBDAV && !useHttps) {
                    Text(
                        "Achtung: Ohne HTTPS werden Passwort und Dateien unverschlüsselt übertragen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = canConnect) {
                if (state.isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Verbinde …")
                } else {
                    Text("Verbinden")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isConnecting) { Text("Abbrechen") }
        }
    )
}
