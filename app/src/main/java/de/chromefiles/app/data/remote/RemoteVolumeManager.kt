package de.chromefiles.app.data.remote

import android.webkit.MimeTypeMap
import de.chromefiles.app.data.local.ChromeFilesDao
import de.chromefiles.app.model.VfsNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verwaltet die Verbindungen zu eingebundenen SMB-/SFTP-/WebDAV-Freigaben.
 *
 * Pfad-Schema: `remote://<volumeId>/<relativer/Pfad>` – der Wurzelordner ist `remote://<volumeId>`.
 * Für Auflisten/Umbenennen/Löschen wird pro Volume eine gemeinsame Verbindung (mit Mutex) genutzt;
 * große Übertragungen öffnen eine eigene Verbindung, damit die Oberfläche währenddessen weiter navigieren kann.
 */
@Singleton
class RemoteVolumeManager @Inject constructor(
    private val dao: ChromeFilesDao,
    private val credentialStore: RemoteCredentialStore
) {
    private val sharedClients = ConcurrentHashMap<String, RemoteFileClient>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    data class RemoteLocation(val volumeId: String, val relativePath: String)

    fun isRemotePath(path: String): Boolean = path.startsWith(VfsNode.REMOTE_SCHEME)

    fun parse(path: String): RemoteLocation {
        require(isRemotePath(path)) { "Kein Netzwerkpfad: $path" }
        val rest = path.removePrefix(VfsNode.REMOTE_SCHEME)
        val volumeId = rest.substringBefore('/')
        val relative = if (rest.contains('/')) RemotePaths.normalize(rest.substringAfter('/')) else ""
        return RemoteLocation(volumeId, relative)
    }

    fun rootPath(volumeId: String): String = VfsNode.REMOTE_SCHEME + volumeId

    fun pathFor(volumeId: String, relativePath: String): String {
        val rel = RemotePaths.normalize(relativePath)
        return if (rel.isEmpty()) rootPath(volumeId) else "${rootPath(volumeId)}/$rel"
    }

    fun childPath(parentRelative: String, name: String): String = RemotePaths.child(parentRelative, name)

    /** Baut die Verbindung mit den Formulardaten auf und prüft sie durch Auflisten des Wurzelordners. */
    suspend fun testAndRegister(volumeId: String, config: RemoteConnectionConfig) = withContext(Dispatchers.IO) {
        val client = RemoteClientFactory.connect(config, credentialStore)
        try {
            client.list("")
        } catch (e: Exception) {
            runCatching { client.close() }
            throw RemoteErrors.translate(e, config.host)
        }
        sharedClients.put(volumeId, client)?.let { old -> runCatching { old.close() } }
    }

    /** Führt [block] mit der gemeinsamen Verbindung des Volumes aus; bei einem Verbindungsabbruch wird einmal neu verbunden. */
    suspend fun <T> withClient(volumeId: String, block: (RemoteFileClient) -> T): T = withContext(Dispatchers.IO) {
        val lock = locks.getOrPut(volumeId) { Mutex() }
        lock.withLock {
            val client = sharedClients[volumeId] ?: openDedicated(volumeId).also { sharedClients[volumeId] = it }
            try {
                block(client)
            } catch (e: RemoteException) {
                throw e
            } catch (e: IOException) {
                runCatching { client.close() }
                sharedClients.remove(volumeId)
                val fresh = openDedicated(volumeId).also { sharedClients[volumeId] = it }
                block(fresh)
            }
        }
    }

    /** Öffnet eine separate Verbindung (der Aufrufer muss sie schließen). */
    suspend fun openDedicated(volumeId: String): RemoteFileClient = withContext(Dispatchers.IO) {
        val config = loadConfig(volumeId)
            ?: throw RemoteException("Die Zugangsdaten dieser Netzwerkfreigabe fehlen. Bitte Verbindung entfernen und neu anlegen.")
        RemoteClientFactory.connect(config, credentialStore)
    }

    suspend fun saveCredentials(volumeId: String, config: RemoteConnectionConfig) {
        credentialStore.savePassword(volumeId, config.password)
        credentialStore.savePrivateKey(volumeId, config.privateKeyPem, config.privateKeyPassphrase)
        credentialStore.saveExtras(volumeId, RemoteConnectionExtras(config.port, config.domain, config.useHttps))
    }

    /** Trennt die Verbindung und entfernt gespeicherte Zugangsdaten. */
    fun forget(volumeId: String) {
        sharedClients.remove(volumeId)?.let { runCatching { it.close() } }
        locks.remove(volumeId)
        credentialStore.remove(volumeId)
    }

    private suspend fun loadConfig(volumeId: String): RemoteConnectionConfig? {
        val entity = dao.getAllMountedConnections().firstOrNull { it.volumeId == volumeId } ?: return null
        val protocol = runCatching { RemoteProtocol.valueOf(entity.protocol) }.getOrNull() ?: return null
        val extras = credentialStore.loadExtras(volumeId) ?: return null
        val password = credentialStore.loadPassword(volumeId) ?: return null
        val privateKeyPem = credentialStore.loadPrivateKey(volumeId)
        val privateKeyPassphrase = credentialStore.loadPassphrase(volumeId)
        return RemoteConnectionConfig(
            protocol = protocol,
            host = entity.hostOrUri,
            port = extras.port,
            shareOrBasePath = entity.shareOrRemotePath,
            username = entity.username,
            password = password,
            domain = extras.domain,
            useHttps = extras.useHttps,
            privateKeyPem = privateKeyPem,
            privateKeyPassphrase = privateKeyPassphrase
        )
    }

    /** Wandelt einen entfernten Eintrag in einen einheitlichen [VfsNode] um. */
    fun toVfsNode(volumeId: String, entry: RemoteEntry): VfsNode {
        val relative = RemotePaths.normalize(entry.path)
        val parentRelative = relative.substringBeforeLast('/', "")
        val ext = if (entry.isDirectory) "" else entry.name.substringAfterLast('.', "").lowercase()
        val path = pathFor(volumeId, relative)
        return VfsNode(
            id = path,
            name = entry.name,
            path = path,
            parentPath = pathFor(volumeId, parentRelative),
            volumeId = volumeId,
            isDirectory = entry.isDirectory,
            sizeBytes = if (entry.isDirectory) 0L else entry.sizeBytes,
            lastModifiedMillis = entry.lastModifiedMillis,
            mimeType = mimeFor(entry.isDirectory, ext),
            extension = ext,
            isHidden = entry.name.startsWith("."),
            isReadOnly = false
        )
    }

    private fun mimeFor(isDirectory: Boolean, ext: String): String = when {
        isDirectory -> "vnd.android.document/directory"
        ext == "md" -> "text/markdown"
        ext == "kt" || ext == "kts" -> "text/x-kotlin"
        ext == "json" -> "application/json"
        ext == "yml" || ext == "yaml" -> "text/yaml"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
