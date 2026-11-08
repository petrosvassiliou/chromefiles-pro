package de.chromefiles.app.data.drive

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import de.chromefiles.app.model.BreadcrumbSegment
import de.chromefiles.app.model.VfsNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Echte **Google Drive REST API v3**-Anbindung inkl. OAuth-2.0-PKCE-Flow (Loopback/Code-Austausch
 * oder direktes Access-Token) und Keystore-verschlüsselter Token-Speicherung.
 *
 * Pfad-Schema für Live-Drive-Knoten:
 * - `gdrive://my_drive` (Meine Ablage)
 * - `gdrive://shared_with_me` (Für mich freigegeben)
 * - `gdrive://shared_drives` (Geteilte Ablagen)
 * - `gdrive://starred` (Markiert)
 * - `gdrive://folder/<folderId>/<encodedName>` (Unterordner in Drive)
 * - `gdrive://file/<fileId>/<encodedName>` (Einzeldatei in Drive)
 *
 * Ist kein OAuth-Token hinterlegt, fällt `VirtualFileSystemRepository` nahtlos auf den
 * lokalen Demo-Ordnerbaum zurück.
 */
@Singleton
class GoogleDriveManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class DriveAccountInfo(
        val email: String,
        val displayName: String,
        val totalQuotaBytes: Long,
        val usedQuotaBytes: Long
    )

    companion object {
        const val GDRIVE_SCHEME = "gdrive://"
        const val ROOT_MY_DRIVE = "gdrive://my_drive"
        const val ROOT_SHARED_WITH_ME = "gdrive://shared_with_me"
        const val ROOT_SHARED_DRIVES = "gdrive://shared_drives"
        const val ROOT_COMPUTERS = "gdrive://computers"

        private const val PREFS_NAME = "google_drive_oauth"
        private const val KEY_ALIAS = "chromefiles_gdrive_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private const val KEY_ENC_ACCESS = "enc_access_token"
        private const val KEY_ENC_REFRESH = "enc_refresh_token"
        private const val KEY_CLIENT_ID = "oauth_client_id"
        private const val KEY_EMAIL = "account_email"
        private const val KEY_DISPLAY_NAME = "account_name"
        private const val KEY_TOTAL_BYTES = "quota_total"
        private const val KEY_USED_BYTES = "quota_used"
        private const val KEY_PKCE_VERIFIER = "pkce_verifier"

        private const val REDIRECT_URI = "http://127.0.0.1:53682/oauth2callback"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient()

    private val _accountFlow = MutableStateFlow<DriveAccountInfo?>(loadCachedAccount())
    val accountFlow: StateFlow<DriveAccountInfo?> = _accountFlow.asStateFlow()

    fun isConnected(): Boolean = _accountFlow.value != null && !loadSecret(KEY_ENC_ACCESS).isNullOrBlank()

    fun isDriveApiPath(path: String): Boolean = path.startsWith(GDRIVE_SCHEME)

    fun savedClientId(): String = prefs.getString(KEY_CLIENT_ID, "").orEmpty()

    @Volatile
    private var loopbackServerSocket: java.net.ServerSocket? = null

    /**
     * Erzeugt die Google-OAuth-2.0-Autorisierungs-URL (PKCE S256) für den Browser.
     */
    fun buildPkceAuthorizationUrl(clientId: String): String {
        val cleanClientId = clientId.trim()
        prefs.edit().putString(KEY_CLIENT_ID, cleanClientId).apply()
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        prefs.edit().putString(KEY_PKCE_VERIFIER, verifier).apply()
        val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(
            challengeBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return android.net.Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
            .buildUpon()
            .appendQueryParameter("client_id", cleanClientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", DRIVE_SCOPE)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /**
     * Startet einen lokalen Loopback-HTTP-Listener auf `127.0.0.1:53682`, der den OAuth-2.0-PKCE-Redirect
     * (`/oauth2callback?code=...`) automatisch vom Browser entgegennimmt.
     */
    suspend fun awaitLoopbackAuthCode(timeoutMillis: Int = 180_000): String? = withContext(Dispatchers.IO) {
        runCatching { loopbackServerSocket?.close() }
        runCatching {
            val server = java.net.ServerSocket(53682, 1, java.net.InetAddress.getByName("127.0.0.1"))
            loopbackServerSocket = server
            server.soTimeout = timeoutMillis
            server.use { srv ->
                srv.accept().use { client ->
                    client.soTimeout = 10_000
                    val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = reader.readLine().orEmpty()
                    val pathPart = requestLine.split(' ').getOrNull(1).orEmpty()
                    val parsedUri = android.net.Uri.parse("http://127.0.0.1:53682$pathPart")
                    val code = parsedUri.getQueryParameter("code")
                    val html = if (!code.isNullOrBlank()) {
                        """
                        <!DOCTYPE html><html lang="de"><head><meta charset="utf-8">
                        <title>ChromeFiles Pro – Google Drive verbunden</title>
                        <style>body{font-family:sans-serif;background:#1e1f22;color:#e3e3e3;display:flex;align-items:center;justify-content:center;height:90vh;margin:0}
                        .card{background:#2b2d30;padding:32px;border-radius:16px;max-width:440px;text-align:center;box-shadow:0 8px 24px rgba(0,0,0,.4)}
                        h2{color:#8ab4f8;margin-top:0}</style></head>
                        <body><div class="card"><h2>Anmeldung erfolgreich</h2>
                        <p>Der OAuth-2.0-Code wurde an <b>ChromeFiles Pro</b> übergeben. Sie können dieses Browser-Tab jetzt schließen.</p>
                        </div></body></html>
                        """.trimIndent()
                    } else {
                        """
                        <!DOCTYPE html><html lang="de"><head><meta charset="utf-8"><title>Anmeldung fehlgeschlagen</title></head>
                        <body><h3>Kein Autorisierungscode empfangen.</h3></body></html>
                        """.trimIndent()
                    }
                    val bodyBytes = html.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    val out = client.getOutputStream()
                    out.write(header.toByteArray(Charsets.UTF_8))
                    out.write(bodyBytes)
                    out.flush()
                    code?.takeIf { it.isNotBlank() }
                }
            }
        }.getOrNull()
    }

    fun stopLoopbackListener() {
        runCatching { loopbackServerSocket?.close() }
        loopbackServerSocket = null
    }

    /**
     * Tauscht einen OAuth-2.0-Autorisierungscode (oder eine vollständige Redirect-URL mit `?code=...`)
     * gegen Access- und Refresh-Token ein und verifiziert das Konto über `drive.about().get()`.
     */
    suspend fun connectWithAuthCode(clientId: String, codeOrRedirectUrl: String): Result<DriveAccountInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanClientId = clientId.trim()
                require(cleanClientId.isNotEmpty()) { "Bitte eine OAuth-Client-ID angeben." }
                val raw = codeOrRedirectUrl.trim()
                val code = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    android.net.Uri.parse(raw).getQueryParameter("code")
                        ?: throw IllegalArgumentException("Kein 'code'-Parameter in der Redirect-URL gefunden.")
                } else {
                    raw
                }
                require(code.isNotEmpty()) { "Autorisierungscode darf nicht leer sein." }
                val verifier = prefs.getString(KEY_PKCE_VERIFIER, null)
                    ?: throw IllegalStateException("PKCE-Verifier fehlt – bitte zuerst 'Im Browser anmelden' klicken.")

                val body = FormBody.Builder()
                    .add("client_id", cleanClientId)
                    .add("code", code)
                    .add("code_verifier", verifier)
                    .add("grant_type", "authorization_code")
                    .add("redirect_uri", REDIRECT_URI)
                    .build()
                val req = Request.Builder()
                    .url("https://oauth2.googleapis.com/token")
                    .post(body)
                    .build()
                val json = httpClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("OAuth-Token-Austausch fehlgeschlagen (HTTP ${resp.code}): $text")
                    }
                    JSONObject(text)
                }
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token", "")
                prefs.edit().putString(KEY_CLIENT_ID, cleanClientId).apply()
                saveSecret(KEY_ENC_ACCESS, accessToken)
                if (refreshToken.isNotBlank()) saveSecret(KEY_ENC_REFRESH, refreshToken)
                verifyAndStoreAccount(accessToken)
            }
        }

    /**
     * Verbindet direkt mit einem OAuth-2.0-Access-Token (z. B. aus dem Google OAuth Playground
     * oder `gcloud auth print-access-token`) und prüft sofort das Drive-Kontingent via REST API v3.
     */
    suspend fun connectWithAccessToken(accessToken: String, refreshToken: String = "", clientId: String = ""): Result<DriveAccountInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val clean = accessToken.trim().removePrefix("Bearer ").trim()
                require(clean.isNotEmpty()) { "Access-Token darf nicht leer sein." }
                saveSecret(KEY_ENC_ACCESS, clean)
                if (refreshToken.isNotBlank()) saveSecret(KEY_ENC_REFRESH, refreshToken.trim())
                if (clientId.isNotBlank()) prefs.edit().putString(KEY_CLIENT_ID, clientId.trim()).apply()
                verifyAndStoreAccount(clean)
            }
        }

    fun disconnect() {
        stopLoopbackListener()
        prefs.edit().clear().apply()
        _accountFlow.value = null
        File(context.cacheDir, "gdrive_cache").deleteRecursively()
    }

    private fun verifyAndStoreAccount(accessToken: String): DriveAccountInfo {
        val service = buildDriveService(accessToken)
        val about = service.about().get()
            .setFields("user(displayName,emailAddress),storageQuota(limit,usage)")
            .execute()
        val email = about.user?.emailAddress ?: "Google-Konto"
        val name = about.user?.displayName ?: email
        val total = about.storageQuota?.limit ?: (15L * 1024 * 1024 * 1024)
        val used = about.storageQuota?.usage ?: 0L
        val info = DriveAccountInfo(email = email, displayName = name, totalQuotaBytes = total, usedQuotaBytes = used)
        prefs.edit()
            .putString(KEY_EMAIL, info.email)
            .putString(KEY_DISPLAY_NAME, info.displayName)
            .putLong(KEY_TOTAL_BYTES, info.totalQuotaBytes)
            .putLong(KEY_USED_BYTES, info.usedQuotaBytes)
            .apply()
        _accountFlow.value = info
        return info
    }

    private fun buildDriveService(accessToken: String? = null): Drive {
        val token = accessToken ?: loadSecret(KEY_ENC_ACCESS)
            ?: throw IllegalStateException("Google Drive ist nicht verbunden.")
        val credentials = GoogleCredentials.create(AccessToken(token, null))
            .createScoped(listOf(DRIVE_SCOPE))
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials)
        ).setApplicationName("ChromeFiles Pro").build()
    }

    private fun <T> withDrive(block: (Drive) -> T): T {
        return try {
            block(buildDriveService())
        } catch (e: Exception) {
            val refreshed = tryRefreshAccessToken()
            if (refreshed != null) {
                block(buildDriveService(refreshed))
            } else {
                throw e
            }
        }
    }

    private fun tryRefreshAccessToken(): String? {
        val refresh = loadSecret(KEY_ENC_REFRESH)?.takeIf { it.isNotBlank() } ?: return null
        val clientId = savedClientId().takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build()
            val req = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val newAccess = json.optString("access_token", "").takeIf { it.isNotBlank() } ?: return@use null
                saveSecret(KEY_ENC_ACCESS, newAccess)
                newAccess
            }
        }.getOrNull()
    }

    suspend fun list(gdrivePath: String, volumeId: String, showHiddenFiles: Boolean): List<VfsNode> =
        withContext(Dispatchers.IO) {
            withDrive { service ->
                val query = when {
                    gdrivePath == ROOT_MY_DRIVE || gdrivePath == GDRIVE_SCHEME ->
                        "'root' in parents and trashed = false"
                    gdrivePath == ROOT_SHARED_WITH_ME ->
                        "sharedWithMe = true and trashed = false"
                    gdrivePath == ROOT_SHARED_DRIVES -> {
                        val drives = service.drives().list().setPageSize(50).execute().drives.orEmpty()
                        return@withDrive drives.map { drv ->
                            val p = "${GDRIVE_SCHEME}folder/${drv.id}/${android.net.Uri.encode(drv.name ?: drv.id)}"
                            VfsNode(
                                id = p,
                                name = drv.name ?: drv.id,
                                path = p,
                                parentPath = gdrivePath,
                                volumeId = volumeId,
                                isDirectory = true,
                                sizeBytes = 0L,
                                lastModifiedMillis = System.currentTimeMillis(),
                                mimeType = "vnd.android.document/directory",
                                extension = ""
                            )
                        }
                    }
                    gdrivePath == ROOT_COMPUTERS ->
                        "'root' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
                    gdrivePath.startsWith("${GDRIVE_SCHEME}folder/") -> {
                        val rest = gdrivePath.removePrefix("${GDRIVE_SCHEME}folder/")
                        val folderId = rest.substringBefore('/')
                        "'$folderId' in parents and trashed = false"
                    }
                    else -> "'root' in parents and trashed = false"
                }

                val listResp = service.files().list()
                    .setQ(query)
                    .setPageSize(200)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setFields("files(id,name,mimeType,size,modifiedTime,starred)")
                    .execute()

                listResp.files.orEmpty()
                    .filter { f -> showHiddenFiles || !(f.name ?: "").startsWith(".") }
                    .map { f -> driveFileToNode(f, gdrivePath, volumeId) }
            }
        }

    /**
     * Serverseitige Volltext-/Dateinamensuche über die Google Drive REST API v3.
     */
    suspend fun search(queryText: String, volumeId: String, showHiddenFiles: Boolean): List<VfsNode> =
        withContext(Dispatchers.IO) {
            if (!isConnected()) return@withContext emptyList()
            runCatching {
                withDrive { service ->
                    val escaped = queryText.replace("\\", "\\\\").replace("'", "\\'")
                    val q = if (escaped.isBlank()) {
                        "trashed = false"
                    } else {
                        "(name contains '$escaped' or fullText contains '$escaped') and trashed = false"
                    }
                    val resp = service.files().list()
                        .setQ(q)
                        .setPageSize(100)
                        .setSupportsAllDrives(true)
                        .setIncludeItemsFromAllDrives(true)
                        .setFields("files(id,name,mimeType,size,modifiedTime,starred)")
                        .execute()
                    resp.files.orEmpty()
                        .filter { f -> showHiddenFiles || !(f.name ?: "").startsWith(".") }
                        .map { f -> driveFileToNode(f, ROOT_MY_DRIVE, volumeId) }
                }
            }.getOrDefault(emptyList())
        }

    fun buildBreadcrumbs(gdrivePath: String, volumeId: String): List<BreadcrumbSegment> {
        val rootSeg = BreadcrumbSegment("Google Drive", ROOT_MY_DRIVE, volumeId)
        return when {
            gdrivePath == ROOT_MY_DRIVE -> listOf(rootSeg, BreadcrumbSegment("Meine Ablage", ROOT_MY_DRIVE, volumeId))
            gdrivePath == ROOT_SHARED_WITH_ME -> listOf(rootSeg, BreadcrumbSegment("Für mich freigegeben", ROOT_SHARED_WITH_ME, volumeId))
            gdrivePath == ROOT_SHARED_DRIVES -> listOf(rootSeg, BreadcrumbSegment("Geteilte Ablagen", ROOT_SHARED_DRIVES, volumeId))
            gdrivePath == ROOT_COMPUTERS -> listOf(rootSeg, BreadcrumbSegment("Computer", ROOT_COMPUTERS, volumeId))
            gdrivePath.startsWith("${GDRIVE_SCHEME}folder/") -> {
                val rest = gdrivePath.removePrefix("${GDRIVE_SCHEME}folder/")
                val title = android.net.Uri.decode(rest.substringAfter('/', "Ordner"))
                listOf(
                    rootSeg,
                    BreadcrumbSegment("Meine Ablage", ROOT_MY_DRIVE, volumeId),
                    BreadcrumbSegment(title, gdrivePath, volumeId)
                )
            }
            else -> listOf(rootSeg)
        }
    }

    suspend fun createFolder(parentGdrivePath: String, folderName: String, volumeId: String): VfsNode =
        withContext(Dispatchers.IO) {
            withDrive { service ->
                val parentId = extractFolderId(parentGdrivePath)
                val metadata = com.google.api.services.drive.model.File().apply {
                    name = folderName
                    mimeType = "application/vnd.google-apps.folder"
                    parents = listOf(parentId)
                }
                val created = service.files().create(metadata)
                    .setSupportsAllDrives(true)
                    .setFields("id,name,mimeType,size,modifiedTime,starred")
                    .execute()
                driveFileToNode(created, parentGdrivePath, volumeId)
            }
        }

    suspend fun rename(node: VfsNode, newName: String): VfsNode = withContext(Dispatchers.IO) {
        withDrive { service ->
            val fileId = extractItemId(node.path)
            val update = com.google.api.services.drive.model.File().apply { name = newName }
            val updated = service.files().update(fileId, update)
                .setSupportsAllDrives(true)
                .setFields("id,name,mimeType,size,modifiedTime,starred")
                .execute()
            driveFileToNode(updated, node.parentPath, node.volumeId)
        }
    }

    suspend fun delete(node: VfsNode): Boolean = withContext(Dispatchers.IO) {
        withDrive { service ->
            val fileId = extractItemId(node.path)
            service.files().delete(fileId).setSupportsAllDrives(true).execute()
            true
        }
    }

    suspend fun uploadLocalFile(
        localFile: File,
        parentGdrivePath: String,
        mimeType: String,
        volumeId: String
    ): VfsNode = withContext(Dispatchers.IO) {
        withDrive { service ->
            val parentId = extractFolderId(parentGdrivePath)
            val meta = com.google.api.services.drive.model.File().apply {
                name = localFile.name
                parents = listOf(parentId)
            }
            val mediaContent = FileContent(mimeType.ifBlank { "application/octet-stream" }, localFile)
            val uploaded = service.files().create(meta, mediaContent)
                .setSupportsAllDrives(true)
                .setFields("id,name,mimeType,size,modifiedTime,starred")
                .execute()
            driveFileToNode(uploaded, parentGdrivePath, volumeId)
        }
    }

    suspend fun updateFileContent(
        node: VfsNode,
        localFile: File
    ): VfsNode = withContext(Dispatchers.IO) {
        withDrive { service ->
            val fileId = extractItemId(node.path)
            val meta = com.google.api.services.drive.model.File().apply { name = node.name }
            val mediaContent = FileContent(node.mimeType.ifBlank { "text/plain" }, localFile)
            val updated = service.files().update(fileId, meta, mediaContent)
                .setSupportsAllDrives(true)
                .setFields("id,name,mimeType,size,modifiedTime,starred")
                .execute()
            val cached = File(context.cacheDir, "gdrive_cache/${fileId}_${node.name}")
            if (cached.exists() && cached.absolutePath != localFile.absolutePath) {
                localFile.copyTo(cached, overwrite = true)
            }
            driveFileToNode(updated, node.parentPath, node.volumeId)
        }
    }

    suspend fun materializeToCache(node: VfsNode): File = withContext(Dispatchers.IO) {
        withDrive { service ->
            val fileId = extractItemId(node.path)
            val cached = File(context.cacheDir, "gdrive_cache/${fileId}_${node.name}")
            if (cached.exists() && node.sizeBytes > 0L && cached.length() == node.sizeBytes) {
                return@withDrive cached
            }
            cached.parentFile?.mkdirs()
            FileOutputStream(cached).use { out ->
                if (node.mimeType.startsWith("application/vnd.google-apps.")) {
                    service.files().export(fileId, "application/pdf").executeMediaAndDownloadTo(out)
                } else {
                    service.files().get(fileId).setSupportsAllDrives(true).executeMediaAndDownloadTo(out)
                }
            }
            if (node.lastModifiedMillis > 0L) cached.setLastModified(node.lastModifiedMillis)
            cached
        }
    }

    private fun extractFolderId(gdrivePath: String): String = when {
        gdrivePath.startsWith("${GDRIVE_SCHEME}folder/") ->
            gdrivePath.removePrefix("${GDRIVE_SCHEME}folder/").substringBefore('/')
        else -> "root"
    }

    private fun extractItemId(gdrivePath: String): String = when {
        gdrivePath.startsWith("${GDRIVE_SCHEME}folder/") ->
            gdrivePath.removePrefix("${GDRIVE_SCHEME}folder/").substringBefore('/')
        gdrivePath.startsWith("${GDRIVE_SCHEME}file/") ->
            gdrivePath.removePrefix("${GDRIVE_SCHEME}file/").substringBefore('/')
        else -> gdrivePath.removePrefix(GDRIVE_SCHEME).substringBefore('/')
    }

    private fun driveFileToNode(
        f: com.google.api.services.drive.model.File,
        parentPath: String,
        volumeId: String
    ): VfsNode {
        val isDir = f.mimeType == "application/vnd.google-apps.folder"
        val name = f.name ?: f.id
        val encodedName = android.net.Uri.encode(name)
        val path = if (isDir) {
            "${GDRIVE_SCHEME}folder/${f.id}/$encodedName"
        } else {
            "${GDRIVE_SCHEME}file/${f.id}/$encodedName"
        }
        val ext = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
        return VfsNode(
            id = path,
            name = name,
            path = path,
            parentPath = parentPath,
            volumeId = volumeId,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else (f.getSize() ?: 0L),
            lastModifiedMillis = f.modifiedTime?.value ?: System.currentTimeMillis(),
            mimeType = if (isDir) "vnd.android.document/directory" else (f.mimeType ?: "application/octet-stream"),
            extension = ext,
            isStarred = f.starred == true,
            isHidden = name.startsWith("."),
            isReadOnly = false
        )
    }

    private fun loadCachedAccount(): DriveAccountInfo? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        if (loadSecret(KEY_ENC_ACCESS).isNullOrBlank()) return null
        return DriveAccountInfo(
            email = email,
            displayName = prefs.getString(KEY_DISPLAY_NAME, email) ?: email,
            totalQuotaBytes = prefs.getLong(KEY_TOTAL_BYTES, 15L * 1024 * 1024 * 1024),
            usedQuotaBytes = prefs.getLong(KEY_USED_BYTES, 0L)
        )
    }

    @Synchronized
    private fun saveSecret(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(key, payload).apply()
    }

    @Synchronized
    private fun loadSecret(key: String): String? {
        val payload = prefs.getString(key, null) ?: return null
        val parts = payload.split(':')
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
