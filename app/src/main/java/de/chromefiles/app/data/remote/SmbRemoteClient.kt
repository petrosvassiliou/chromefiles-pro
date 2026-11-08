package de.chromefiles.app.data.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** SMB2/3-Zugriff über smbj. */
internal class SmbRemoteClient private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    /** Basisverzeichnis innerhalb der Freigabe, '\\'-getrennt, ohne führenden '\\'. */
    private val basePrefix: String
) : RemoteFileClient {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val OPERATION_TIMEOUT_S = 30L

        fun connect(config: RemoteConnectionConfig): SmbRemoteClient {
            val segments = RemotePaths.segments(config.shareOrBasePath)
            if (segments.isEmpty()) {
                throw RemoteException("Bitte den Namen der Freigabe angeben (z. B. \"Daten\").")
            }
            val shareName = segments.first()
            val basePrefix = segments.drop(1).joinToString("\\")

            val smbConfig = SmbConfig.builder()
                .withTimeout(OPERATION_TIMEOUT_S, TimeUnit.SECONDS)
                .withSocketFactory(ConnectTimeoutSocketFactory(CONNECT_TIMEOUT_MS))
                .build()
            val client = SMBClient(smbConfig)
            var connection: Connection? = null
            var session: Session? = null
            try {
                connection = try {
                    client.connect(config.host, config.port)
                } catch (e: Exception) {
                    throw RemoteErrors.translate(e, config.host)
                }
                val auth = if (config.username.isBlank()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(config.username.trim(), config.password.toCharArray(), config.domain.trim())
                }
                session = try {
                    connection.authenticate(auth)
                } catch (e: SMBApiException) {
                    throw RemoteException(authMessage(e.status), e)
                } catch (e: Exception) {
                    throw RemoteException("Anmeldung fehlgeschlagen. Bitte Benutzername und Passwort prüfen.", e)
                }
                val share = try {
                    session.connectShare(shareName)
                } catch (e: SMBApiException) {
                    throw RemoteException(
                        when (e.status) {
                            NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_BAD_NETWORK_PATH ->
                                "Die Freigabe \"$shareName\" wurde auf dem Server nicht gefunden."
                            NtStatus.STATUS_ACCESS_DENIED ->
                                "Zugriff auf die Freigabe \"$shareName\" verweigert."
                            else -> "Freigabe \"$shareName\" konnte nicht geöffnet werden (${e.status})."
                        },
                        e
                    )
                }
                if (share !is DiskShare) {
                    runCatching { share.close() }
                    throw RemoteException("\"$shareName\" ist keine Datei-Freigabe (z. B. Drucker oder IPC).")
                }
                val result = SmbRemoteClient(client, connection, session, share, basePrefix)
                if (basePrefix.isNotEmpty()) {
                    val exists = try {
                        share.folderExists(basePrefix)
                    } catch (e: SMBApiException) {
                        false
                    }
                    if (!exists) {
                        result.close()
                        throw RemoteException("Der Ordner \"${basePrefix.replace('\\', '/')}\" existiert in der Freigabe \"$shareName\" nicht.")
                    }
                }
                return result
            } catch (e: Exception) {
                runCatching { session?.close() }
                runCatching { connection?.close() }
                runCatching { client.close() }
                throw if (e is RemoteException) e else RemoteErrors.translate(e, config.host)
            }
        }

        private fun authMessage(status: NtStatus?): String = when (status) {
            NtStatus.STATUS_LOGON_FAILURE -> "Anmeldung fehlgeschlagen. Benutzername oder Passwort ist falsch."
            NtStatus.STATUS_ACCOUNT_DISABLED -> "Anmeldung fehlgeschlagen: Das Benutzerkonto ist deaktiviert."
            NtStatus.STATUS_PASSWORD_EXPIRED -> "Anmeldung fehlgeschlagen: Das Passwort ist abgelaufen."
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED -> "Anmeldung fehlgeschlagen: Dieser Anmeldetyp ist für das Konto nicht erlaubt."
            NtStatus.STATUS_ACCESS_DENIED -> "Anmeldung fehlgeschlagen: Zugriff verweigert."
            else -> "Anmeldung fehlgeschlagen${if (status != null) " ($status)" else ""}."
        }

        private val NOT_FOUND_STATUSES = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_NOT_FOUND,
            NtStatus.STATUS_BAD_NETWORK_PATH
        )
    }

    /** Wandelt einen relativen '/'-Pfad in einen smbj-Pfad innerhalb der Freigabe um. */
    private fun smbPath(path: String): String {
        val relative = RemotePaths.segments(path).joinToString("\\")
        return when {
            basePrefix.isEmpty() -> relative
            relative.isEmpty() -> basePrefix
            else -> "$basePrefix\\$relative"
        }
    }

    private inline fun <T> smbCall(path: String, block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        val shown = if (path.isEmpty()) "/" else path
        throw RemoteException(
            when (e.status) {
                in NOT_FOUND_STATUSES -> "\"$shown\" wurde nicht gefunden."
                NtStatus.STATUS_ACCESS_DENIED -> "Zugriff verweigert: \"$shown\"."
                NtStatus.STATUS_OBJECT_NAME_COLLISION -> "\"$shown\" existiert bereits."
                NtStatus.STATUS_NOT_A_DIRECTORY -> "\"$shown\" ist kein Ordner."
                NtStatus.STATUS_SHARING_VIOLATION -> "\"$shown\" wird gerade von einem anderen Programm verwendet."
                NtStatus.STATUS_DISK_FULL -> "Auf dem Server ist nicht genügend Speicherplatz frei."
                NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> "Der Ordner \"$shown\" ist nicht leer."
                else -> "SMB-Fehler bei \"$shown\": ${e.status}"
            },
            e
        )
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw RemoteErrors.translate(e, connection.remoteHostname ?: "")
    }

    override fun list(path: String): List<RemoteEntry> = smbCall(path) {
        val dir = smbPath(path)
        share.list(dir)
            .asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDir = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                RemoteEntry(
                    name = info.fileName,
                    path = RemotePaths.child(path, info.fileName),
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else info.endOfFile,
                    lastModifiedMillis = info.lastWriteTime.toEpochMillis()
                )
            }
            .toList()
    }

    override fun stat(path: String): RemoteEntry? {
        val info: FileAllInformation = try {
            share.getFileInformation(smbPath(path))
        } catch (e: SMBApiException) {
            if (e.status in NOT_FOUND_STATUSES) return null
            return smbCall(path) { throw e }
        } catch (e: Exception) {
            throw RemoteErrors.translate(e, connection.remoteHostname ?: "")
        }
        val isDir = info.standardInformation.isDirectory ||
            EnumWithValue.EnumUtils.isSet(info.basicInformation.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        val normalized = RemotePaths.normalize(path)
        return RemoteEntry(
            name = RemotePaths.name(normalized),
            path = normalized,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else info.standardInformation.endOfFile,
            lastModifiedMillis = info.basicInformation.lastWriteTime.toEpochMillis()
        )
    }

    override fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit) {
        smbCall(path) {
            share.openFile(
                smbPath(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            ).use { file ->
                file.inputStream.use { input -> RemotePaths.copy(input, out, onBytes) }
            }
        }
    }

    override fun upload(path: String, input: InputStream, lengthBytes: Long, onBytes: (Long) -> Unit) {
        smbCall(path) {
            share.openFile(
                smbPath(path),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            ).use { file ->
                file.outputStream.use { output -> RemotePaths.copy(input, output, onBytes) }
            }
        }
    }

    override fun mkdir(path: String) {
        smbCall(path) { share.mkdir(smbPath(path)) }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        if (RemotePaths.normalize(path).isEmpty()) {
            throw RemoteException("Das Stammverzeichnis der Freigabe kann nicht gelöscht werden.")
        }
        smbCall(path) {
            if (isDirectory) share.rmdir(smbPath(path), true) else share.rm(smbPath(path))
        }
    }

    override fun rename(fromPath: String, toPath: String) {
        if (RemotePaths.normalize(fromPath).isEmpty()) {
            throw RemoteException("Das Stammverzeichnis der Freigabe kann nicht umbenannt werden.")
        }
        smbCall(fromPath) {
            share.open(
                smbPath(fromPath),
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { entry -> entry.rename(smbPath(toPath), false) }
        }
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    /** Socket-Fabrik mit Verbindungs-Timeout, da smbj selbst keinen Connect-Timeout setzt. */
    private class ConnectTimeoutSocketFactory(private val timeoutMs: Int) : SocketFactory() {
        override fun createSocket(): Socket = Socket()

        override fun createSocket(host: String, port: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            Socket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port), timeoutMs)
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            Socket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port), timeoutMs)
            }
    }
}
