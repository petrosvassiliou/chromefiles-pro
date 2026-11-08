package de.chromefiles.app.data.remote

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.UnknownServiceException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** WebDAV-Zugriff (z. B. Nextcloud, ownCloud, NAS) über OkHttp. */
internal class WebDavRemoteClient private constructor(
    private val http: OkHttpClient,
    /** Basis-URL ohne abschließenden '/'. */
    private val baseUrl: HttpUrl,
    private val host: String
) : RemoteFileClient {

    companion object {
        private const val DAV_NS = "DAV:"
        private val XML_MEDIA_TYPE: MediaType = "application/xml; charset=utf-8".toMediaType()
        private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
  </d:prop>
</d:propfind>"""

        fun connect(config: RemoteConnectionConfig): WebDavRemoteClient {
            val cleanHost = config.host.trim()
                .removePrefix("https://").removePrefix("http://")
                .substringBefore('/')
                .let { if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1) else it }
            val baseBuilder = try {
                HttpUrl.Builder()
                    .scheme(if (config.useHttps) "https" else "http")
                    .host(cleanHost)
                    .port(config.port)
            } catch (e: IllegalArgumentException) {
                throw RemoteException("Ungültige Serveradresse: \"${config.host}\".", e)
            }
            RemotePaths.segments(config.shareOrBasePath).forEach { baseBuilder.addPathSegment(it) }
            val baseUrl = baseBuilder.build()

            val username = config.username.trim()
            val authHeader = if (username.isNotEmpty()) Credentials.basic(username, config.password, Charsets.UTF_8) else null
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val withAuth = if (authHeader != null && request.header("Authorization") == null) {
                        request.newBuilder().header("Authorization", authHeader).build()
                    } else {
                        request
                    }
                    chain.proceed(withAuth)
                }
                .build()

            val client = WebDavRemoteClient(http, baseUrl, cleanHost)
            try {
                client.execute(client.propfindRequest(client.urlFor("", directory = true), depth = 0)).use { response ->
                    when (response.code) {
                        207 -> Unit
                        401 -> throw RemoteException("Anmeldung fehlgeschlagen. Benutzername oder Passwort ist falsch.")
                        403 -> throw RemoteException("Zugriff verweigert. Das Konto hat keine Berechtigung für diesen Pfad.")
                        404 -> throw RemoteException("Der WebDAV-Pfad \"/${RemotePaths.normalize(config.shareOrBasePath)}\" wurde auf dem Server nicht gefunden.")
                        405, 501 -> throw RemoteException("Der Server unterstützt unter dieser Adresse kein WebDAV.")
                        else -> if (response.code in 200..299) {
                            throw RemoteException("Der Server unterstützt unter dieser Adresse kein WebDAV (HTTP ${response.code}).")
                        } else {
                            throw RemoteException("Verbindung fehlgeschlagen: Server antwortet mit HTTP ${response.code}.")
                        }
                    }
                }
            } catch (e: Exception) {
                client.close()
                throw if (e is RemoteException) e else RemoteErrors.translate(e, cleanHost)
            }
            return client
        }

        private fun parseHttpDate(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            return try {
                ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            } catch (e: Exception) {
                0L
            }
        }
    }

    private data class DavResource(
        val href: String,
        val isCollection: Boolean,
        val contentLength: Long,
        val lastModifiedMillis: Long
    )

    /** URL für einen relativen Pfad; Segmente werden einzeln korrekt kodiert. */
    private fun urlFor(path: String, directory: Boolean = false): HttpUrl {
        val builder = baseUrl.newBuilder()
        RemotePaths.segments(path).forEach { builder.addPathSegment(it) }
        if (directory) builder.addPathSegment("")
        return builder.build()
    }

    private fun propfindRequest(url: HttpUrl, depth: Int): Request = Request.Builder()
        .url(url)
        .header("Depth", depth.toString())
        .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
        .build()

    private fun execute(request: Request): Response = try {
        http.newCall(request).execute()
    } catch (e: UnknownServiceException) {
        throw RemoteException(
            "Unverschlüsselte HTTP-Verbindungen sind nicht erlaubt. Bitte HTTPS verwenden.",
            e
        )
    } catch (e: IOException) {
        throw RemoteErrors.translate(e, host)
    }

    private fun httpError(code: Int, path: String): RemoteException {
        val shown = if (path.isEmpty()) "/" else path
        return RemoteException(
            when (code) {
                401 -> "Anmeldung fehlgeschlagen. Benutzername oder Passwort ist falsch."
                403 -> "Zugriff verweigert: \"$shown\"."
                404 -> "\"$shown\" wurde nicht gefunden."
                405, 412 -> "\"$shown\" existiert bereits oder die Aktion ist hier nicht erlaubt."
                409 -> "Der übergeordnete Ordner von \"$shown\" existiert nicht."
                413 -> "Die Datei ist für den Server zu groß."
                423 -> "\"$shown\" ist gesperrt."
                507 -> "Auf dem Server ist nicht genügend Speicherplatz frei."
                in 500..599 -> "Serverfehler (HTTP $code) bei \"$shown\"."
                else -> "Unerwartete Serverantwort (HTTP $code) bei \"$shown\"."
            }
        )
    }

    /** Wandelt einen href aus der Multistatus-Antwort in Pfadsegmente relativ zur Basis-URL um. */
    private fun relativeSegments(href: String): List<String>? {
        val resolved = baseUrl.resolve(href.trim()) ?: return null
        val baseSegments = baseUrl.pathSegments.filter { it.isNotEmpty() }
        val segments = resolved.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < baseSegments.size) return null
        if (segments.subList(0, baseSegments.size) != baseSegments) return null
        return segments.subList(baseSegments.size, segments.size)
    }

    private fun parseMultistatus(stream: InputStream): List<DavResource> {
        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(stream, null)

        val result = ArrayList<DavResource>()
        var inResponse = false
        var inPropstat = false
        var href: String? = null
        var isCollection = false
        var contentLength = 0L
        var lastModified = 0L
        var psCollection = false
        var psLength: Long? = null
        var psModified: Long? = null
        var psStatus: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "response" -> {
                        inResponse = true
                        href = null
                        isCollection = false
                        contentLength = 0L
                        lastModified = 0L
                    }
                    "href" -> if (inResponse && !inPropstat) href = parser.nextText()
                    "propstat" -> if (inResponse) {
                        inPropstat = true
                        psCollection = false
                        psLength = null
                        psModified = null
                        psStatus = null
                    }
                    "collection" -> if (inPropstat) psCollection = true
                    "getcontentlength" -> if (inPropstat) psLength = parser.nextText().trim().toLongOrNull()
                    "getlastmodified" -> if (inPropstat) psModified = parseHttpDate(parser.nextText())
                    "status" -> if (inPropstat) psStatus = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.namespace == DAV_NS) {
                when (parser.name) {
                    "propstat" -> if (inPropstat) {
                        inPropstat = false
                        val code = psStatus?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull()
                        if (code == null || code in 200..299) {
                            if (psCollection) isCollection = true
                            psLength?.let { contentLength = it }
                            psModified?.let { lastModified = it }
                        }
                    }
                    "response" -> if (inResponse) {
                        inResponse = false
                        href?.let { result.add(DavResource(it, isCollection, contentLength, lastModified)) }
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun toEntry(resource: DavResource, segments: List<String>) = RemoteEntry(
        name = segments.lastOrNull() ?: "",
        path = segments.joinToString("/"),
        isDirectory = resource.isCollection,
        sizeBytes = if (resource.isCollection) 0L else resource.contentLength,
        lastModifiedMillis = resource.lastModifiedMillis
    )

    override fun list(path: String): List<RemoteEntry> {
        val requested = RemotePaths.segments(path)
        execute(propfindRequest(urlFor(path, directory = true), depth = 1)).use { response ->
            if (response.code != 207) throw httpError(response.code, path)
            val body = response.body ?: throw RemoteException("Leere Antwort des Servers beim Auflisten von \"$path\".")
            val resources = try {
                body.byteStream().use { parseMultistatus(it) }
            } catch (e: IOException) {
                throw RemoteErrors.translate(e, host)
            } catch (e: Exception) {
                throw RemoteException("Die Antwort des Servers konnte nicht gelesen werden.", e)
            }
            return resources.mapNotNull { resource ->
                val segments = relativeSegments(resource.href) ?: return@mapNotNull null
                if (segments == requested) return@mapNotNull null
                if (segments.size != requested.size + 1 || segments.subList(0, requested.size) != requested) {
                    return@mapNotNull null
                }
                toEntry(resource, segments)
            }
        }
    }

    override fun stat(path: String): RemoteEntry? {
        val normalized = RemotePaths.normalize(path)
        execute(propfindRequest(urlFor(normalized), depth = 0)).use { response ->
            if (response.code == 404) return null
            if (response.code != 207) throw httpError(response.code, normalized)
            val body = response.body ?: return null
            val resources = try {
                body.byteStream().use { parseMultistatus(it) }
            } catch (e: IOException) {
                throw RemoteErrors.translate(e, host)
            } catch (e: Exception) {
                throw RemoteException("Die Antwort des Servers konnte nicht gelesen werden.", e)
            }
            val resource = resources.firstOrNull() ?: return null
            return toEntry(resource, RemotePaths.segments(normalized))
        }
    }

    override fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit) {
        val request = Request.Builder().url(urlFor(path)).get().build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw httpError(response.code, path)
            val body = response.body ?: throw RemoteException("Leere Antwort des Servers beim Herunterladen von \"$path\".")
            try {
                body.byteStream().use { input -> RemotePaths.copy(input, out, onBytes) }
            } catch (e: RemoteException) {
                throw e
            } catch (e: IOException) {
                throw RemoteErrors.translate(e, host)
            }
        }
    }

    override fun upload(path: String, input: InputStream, lengthBytes: Long, onBytes: (Long) -> Unit) {
        val request = Request.Builder()
            .url(urlFor(path))
            .put(ProgressRequestBody(input, lengthBytes, onBytes))
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw httpError(response.code, path)
        }
    }

    override fun mkdir(path: String) {
        val request = Request.Builder().url(urlFor(path, directory = true)).method("MKCOL", null).build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw httpError(response.code, path)
        }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        if (RemotePaths.normalize(path).isEmpty()) {
            throw RemoteException("Das Basisverzeichnis kann nicht gelöscht werden.")
        }
        val request = Request.Builder().url(urlFor(path, directory = isDirectory)).delete().build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw httpError(response.code, path)
        }
    }

    override fun rename(fromPath: String, toPath: String) {
        if (RemotePaths.normalize(fromPath).isEmpty()) {
            throw RemoteException("Das Basisverzeichnis kann nicht umbenannt werden.")
        }
        val isDirectory = stat(fromPath)?.isDirectory ?: throw httpError(404, fromPath)
        val request = Request.Builder()
            .url(urlFor(fromPath, directory = isDirectory))
            .method("MOVE", null)
            .header("Destination", urlFor(toPath, directory = isDirectory).toString())
            .header("Overwrite", "F")
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw httpError(response.code, toPath)
        }
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /** Streamt den Upload direkt aus dem InputStream und meldet den Fortschritt. */
    private class ProgressRequestBody(
        private val input: InputStream,
        private val lengthBytes: Long,
        private val onBytes: (Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM

        override fun contentLength(): Long = if (lengthBytes >= 0) lengthBytes else -1L

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            val buffer = ByteArray(RemotePaths.BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                sink.write(buffer, 0, read)
                total += read
                onBytes(total)
            }
            sink.flush()
        }
    }
}
