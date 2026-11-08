package de.chromefiles.app.data.remote

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.EnumSet

/** SFTP-Zugriff über sshj mit Host-Key-Prüfung nach dem Prinzip „Trust on first use“. */
internal class SftpRemoteClient private constructor(
    private val ssh: SSHClient,
    private val sftp: SFTPClient,
    /** Absolutes (oder vom Benutzer vorgegebenes) Basisverzeichnis ohne abschließenden '/' (außer bei "/"). */
    private val basePath: String
) : RemoteFileClient {

    companion object {
        private const val TIMEOUT_MS = 15_000

        @Volatile
        private var providerInstalled = false

        /**
         * Android liefert einen stark eingeschränkten "BC"-Provider aus. Er wird einmalig durch den vollständigen
         * Bouncy-Castle-Provider ersetzt, damit sshj alle Schlüsselaustausch- und Signaturverfahren nutzen kann.
         */
        private fun ensureBouncyCastle() {
            if (providerInstalled) return
            synchronized(this) {
                if (providerInstalled) return
                val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
                if (current == null || current.javaClass != BouncyCastleProvider::class.java) {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Security.addProvider(BouncyCastleProvider())
                }
                providerInstalled = true
            }
        }

        fun connect(config: RemoteConnectionConfig, hostKeyStore: HostKeyStore): SftpRemoteClient {
            ensureBouncyCastle()
            val ssh = SSHClient(DefaultConfig())
            ssh.connectTimeout = TIMEOUT_MS
            ssh.timeout = TIMEOUT_MS
            val verifier = TofuHostKeyVerifier(hostKeyStore)
            ssh.addHostKeyVerifier(verifier)
            try {
                try {
                    ssh.connect(config.host, config.port)
                } catch (e: Exception) {
                    verifier.rejection?.let { throw RemoteException(it, e) }
                    throw RemoteErrors.translate(e, config.host)
                }
                try {
                    if (config.privateKeyPem.isNotBlank()) {
                        val finder = if (config.privateKeyPassphrase.isNotEmpty()) {
                            net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(config.privateKeyPassphrase.toCharArray())
                        } else {
                            null
                        }
                        val keyProvider = ssh.loadKeys(config.privateKeyPem.trim(), null, finder)
                        ssh.authPublickey(config.username.trim(), keyProvider)
                    } else {
                        ssh.authPassword(config.username.trim(), config.password)
                    }
                } catch (e: UserAuthException) {
                    throw RemoteException(
                        if (config.privateKeyPem.isNotBlank()) {
                            "Anmeldung mit SSH-Private-Key fehlgeschlagen. Bitte Schlüssel, Passphrase und Benutzernamen prüfen."
                        } else {
                            "Anmeldung fehlgeschlagen. Benutzername oder Passwort ist falsch."
                        },
                        e
                    )
                } catch (e: Exception) {
                    throw RemoteException("SSH-Schlüssel konnte nicht geladen werden: ${e.message ?: "Ungültiges Format"}", e)
                }
                val sftp = try {
                    ssh.newSFTPClient()
                } catch (e: Exception) {
                    throw RemoteException("Der Server unterstützt kein SFTP oder der SFTP-Dienst ist deaktiviert.", e)
                }
                val base = resolveBasePath(sftp, config.shareOrBasePath)
                return SftpRemoteClient(ssh, sftp, base)
            } catch (e: Exception) {
                runCatching { ssh.disconnect() }
                runCatching { ssh.close() }
                throw if (e is RemoteException) e else RemoteErrors.translate(e, config.host)
            }
        }

        private fun resolveBasePath(sftp: SFTPClient, configured: String): String {
            val trimmed = configured.trim().replace('\\', '/')
            val raw = if (trimmed.isEmpty()) "." else trimmed
            val resolved = try {
                sftp.canonicalize(raw)
            } catch (e: SFTPException) {
                throw RemoteException(
                    if (trimmed.isEmpty()) "Das Benutzerverzeichnis auf dem Server konnte nicht ermittelt werden."
                    else "Der Ordner \"$trimmed\" existiert auf dem Server nicht.",
                    e
                )
            }
            val attrs = try {
                sftp.stat(resolved)
            } catch (e: SFTPException) {
                throw RemoteException("Der Ordner \"$resolved\" existiert auf dem Server nicht.", e)
            }
            if (attrs.type != FileMode.Type.DIRECTORY) {
                throw RemoteException("\"$resolved\" ist kein Ordner.")
            }
            return if (resolved.length > 1) resolved.trimEnd('/') else resolved
        }

        /** Fingerabdruck im OpenSSH-Format: "<Schlüsseltyp> SHA256:<Base64 ohne Padding>". */
        internal fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
            return "${KeyType.fromKey(key)} SHA256:$b64"
        }
    }

    /**
     * Speichert beim ersten Kontakt den Host-Key und lehnt spätere, abweichende Schlüssel ab.
     */
    private class TofuHostKeyVerifier(private val store: HostKeyStore) : HostKeyVerifier {
        @Volatile
        var rejection: String? = null

        private fun id(hostname: String, port: Int) = "$hostname:$port"

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val id = id(hostname, port)
            val actual = fingerprint(key)
            val known = store.fingerprintFor(id)
            if (known == null) {
                store.storeFingerprint(id, actual)
                return true
            }
            if (known == actual) return true
            rejection = "Der Sicherheitsschlüssel des Servers $id hat sich geändert! " +
                "Möglicherweise versucht jemand, die Verbindung abzuhören (Man-in-the-Middle-Angriff). " +
                "Gespeichert: $known – erhalten: $actual. Falls der Server neu eingerichtet wurde, " +
                "entfernen Sie die Verbindung und legen Sie sie neu an."
            return false
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
            val known = store.fingerprintFor(id(hostname, port)) ?: return emptyList()
            val type = known.substringBefore(' ')
            return when (type) {
                "ssh-rsa" -> listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa")
                "" -> emptyList()
                else -> listOf(type)
            }
        }
    }

    private fun fullPath(path: String): String {
        val relative = RemotePaths.normalize(path)
        return when {
            relative.isEmpty() -> basePath
            basePath.endsWith("/") -> basePath + relative
            else -> "$basePath/$relative"
        }
    }

    private inline fun <T> sftpCall(path: String, block: () -> T): T = try {
        block()
    } catch (e: SFTPException) {
        val shown = if (path.isEmpty()) "/" else path
        throw RemoteException(
            when (e.statusCode) {
                Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> "\"$shown\" wurde nicht gefunden."
                Response.StatusCode.PERMISSION_DENIED -> "Zugriff verweigert: \"$shown\"."
                Response.StatusCode.FILE_ALREADY_EXISTS -> "\"$shown\" existiert bereits."
                Response.StatusCode.NO_SPACE_ON_FILESYSTEM -> "Auf dem Server ist nicht genügend Speicherplatz frei."
                Response.StatusCode.DIR_NOT_EMPTY -> "Der Ordner \"$shown\" ist nicht leer."
                else -> "SFTP-Fehler bei \"$shown\": ${e.message ?: e.statusCode}"
            },
            e
        )
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw RemoteErrors.translate(e, ssh.remoteHostname ?: "")
    }

    private fun toEntry(relativePath: String, name: String, attrs: FileAttributes, isDirectory: Boolean) = RemoteEntry(
        name = name,
        path = relativePath,
        isDirectory = isDirectory,
        sizeBytes = if (isDirectory) 0L else attrs.size,
        lastModifiedMillis = attrs.mtime * 1000L
    )

    override fun list(path: String): List<RemoteEntry> = sftpCall(path) {
        sftp.ls(fullPath(path))
            .asSequence()
            .filter { it.name != "." && it.name != ".." }
            .map { info ->
                var attrs = info.attributes
                if (attrs.type == FileMode.Type.SYMLINK) {
                    // Symbolische Links auflösen, damit verlinkte Ordner als Ordner erscheinen.
                    attrs = runCatching { sftp.stat(info.path) }.getOrDefault(attrs)
                }
                toEntry(RemotePaths.child(path, info.name), info.name, attrs, attrs.type == FileMode.Type.DIRECTORY)
            }
            .toList()
    }

    override fun stat(path: String): RemoteEntry? = sftpCall(path) {
        val attrs = sftp.statExistence(fullPath(path)) ?: return@sftpCall null
        val normalized = RemotePaths.normalize(path)
        toEntry(normalized, RemotePaths.name(normalized), attrs, attrs.type == FileMode.Type.DIRECTORY)
    }

    override fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit) {
        sftpCall(path) {
            sftp.open(fullPath(path), EnumSet.of(OpenMode.READ)).use { file ->
                file.RemoteFileInputStream().use { input -> RemotePaths.copy(input, out, onBytes) }
            }
        }
    }

    override fun upload(path: String, input: InputStream, lengthBytes: Long, onBytes: (Long) -> Unit) {
        sftpCall(path) {
            sftp.open(fullPath(path), EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
                file.RemoteFileOutputStream(0L, 16).use { output -> RemotePaths.copy(input, output, onBytes) }
            }
        }
    }

    override fun mkdir(path: String) {
        sftpCall(path) { sftp.mkdir(fullPath(path)) }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        if (RemotePaths.normalize(path).isEmpty()) {
            throw RemoteException("Das Basisverzeichnis kann nicht gelöscht werden.")
        }
        sftpCall(path) {
            if (isDirectory) deleteRecursively(fullPath(path)) else sftp.rm(fullPath(path))
        }
    }

    private fun deleteRecursively(absolutePath: String) {
        for (child in sftp.ls(absolutePath)) {
            if (child.name == "." || child.name == "..") continue
            // Symbolische Links werden nur entfernt, nicht verfolgt.
            if (child.attributes.type == FileMode.Type.DIRECTORY) {
                deleteRecursively(child.path)
            } else {
                sftp.rm(child.path)
            }
        }
        sftp.rmdir(absolutePath)
    }

    override fun rename(fromPath: String, toPath: String) {
        if (RemotePaths.normalize(fromPath).isEmpty()) {
            throw RemoteException("Das Basisverzeichnis kann nicht umbenannt werden.")
        }
        sftpCall(fromPath) { sftp.rename(fullPath(fromPath), fullPath(toPath)) }
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
        runCatching { ssh.close() }
    }
}
