package de.chromefiles.app.data.remote

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** Unterstützte Netzwerkprotokolle für entfernte Speicherorte. */
enum class RemoteProtocol(val labelDe: String, val defaultPort: Int) {
    SMB("SMB (Windows/Samba)", 445),
    SFTP("SFTP (SSH)", 22),
    WEBDAV("WebDAV (HTTP/HTTPS)", 443)
}

/** Verbindungsdaten für einen entfernten Speicherort. */
data class RemoteConnectionConfig(
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    val shareOrBasePath: String,
    val username: String,
    val password: String,
    val domain: String = "",
    val useHttps: Boolean = true,
    val privateKeyPem: String = "",
    val privateKeyPassphrase: String = ""
)

/** Ein Eintrag (Datei oder Ordner) auf dem entfernten Speicherort. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
)

/**
 * Fehler bei Netzwerkzugriffen. Die Meldung ist immer deutsch und für Benutzer verständlich formuliert.
 */
class RemoteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Einheitlicher, blockierender Zugriff auf entfernte Dateisysteme (Aufruf auf Dispatchers.IO). */
interface RemoteFileClient : java.io.Closeable {
    /** paths are always '/'-separated, relative to the share/base path, root = "" ; never start with '/' */
    fun list(path: String): List<RemoteEntry>

    /** Liefert null, wenn der Pfad nicht existiert. */
    fun stat(path: String): RemoteEntry?

    fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit)

    /** Legt die Datei an oder überschreibt sie. */
    fun upload(path: String, input: InputStream, lengthBytes: Long, onBytes: (Long) -> Unit)

    fun mkdir(path: String)

    /** Ordner werden rekursiv gelöscht. */
    fun delete(path: String, isDirectory: Boolean)

    fun rename(fromPath: String, toPath: String)
}

/** Baut eine authentifizierte Verbindung für das gewünschte Protokoll auf. */
object RemoteClientFactory {

    /**
     * Stellt die Verbindung her und meldet sich an. Blockiert – nur auf einem Hintergrund-Thread aufrufen.
     * Wirft eine [RemoteException] mit deutscher Fehlermeldung, falls etwas schiefgeht.
     */
    fun connect(config: RemoteConnectionConfig, hostKeyStore: HostKeyStore): RemoteFileClient {
        val host = config.host.trim()
        if (host.isEmpty()) throw RemoteException("Bitte einen Servernamen oder eine IP-Adresse angeben.")
        if (config.port !in 1..65535) throw RemoteException("Ungültiger Port: ${config.port}")
        val normalized = config.copy(host = host)
        return try {
            when (normalized.protocol) {
                RemoteProtocol.SMB -> SmbRemoteClient.connect(normalized)
                RemoteProtocol.SFTP -> SftpRemoteClient.connect(normalized, hostKeyStore)
                RemoteProtocol.WEBDAV -> WebDavRemoteClient.connect(normalized)
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteErrors.translate(e, normalized.host)
        }
    }
}

/** Hilfsfunktionen für Pfade und Fehlermeldungen, gemeinsam genutzt von allen Protokollen. */
internal object RemotePaths {
    const val BUFFER_SIZE = 64 * 1024

    /** Normalisiert einen Pfad auf '/'-getrennte Segmente ohne führenden oder abschließenden '/'. */
    fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    fun segments(path: String): List<String> = normalize(path).split('/').filter { it.isNotEmpty() }

    fun child(parent: String, name: String): String {
        val p = normalize(parent)
        return if (p.isEmpty()) name else "$p/$name"
    }

    fun name(path: String): String = normalize(path).substringAfterLast('/')

    /** Kopiert einen Datenstrom in 64-KiB-Blöcken und meldet die bisher übertragenen Bytes. */
    fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw RemoteException("Übertragung abgebrochen.")
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            out.write(buffer, 0, read)
            total += read
            onBytes(total)
        }
        out.flush()
        return total
    }
}

internal object RemoteErrors {
    /** Übersetzt allgemeine Netzwerkfehler in verständliche deutsche Meldungen. */
    fun translate(e: Throwable, host: String): RemoteException {
        if (e is RemoteException) return e
        var current: Throwable? = e
        while (current != null) {
            when (current) {
                is RemoteException -> return current
                is UnknownHostException -> return RemoteException("Server \"$host\" wurde nicht gefunden. Bitte Adresse prüfen.", e)
                is SocketTimeoutException -> return RemoteException("Server nicht erreichbar: Zeitüberschreitung bei der Verbindung zu \"$host\".", e)
                is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                    return RemoteException("Server nicht erreichbar. Bitte Adresse, Port und Netzwerkverbindung prüfen.", e)
                is SSLHandshakeException -> return RemoteException("Sichere Verbindung fehlgeschlagen: Das Zertifikat des Servers wird nicht vertraut.", e)
                is SSLException -> return RemoteException("Sichere Verbindung (TLS) fehlgeschlagen.", e)
            }
            current = current.cause
        }
        val detail = e.message?.takeIf { it.isNotBlank() }
        return RemoteException(
            if (detail != null) "Verbindungsfehler: $detail" else "Unbekannter Verbindungsfehler (${e.javaClass.simpleName}).",
            e
        )
    }
}
