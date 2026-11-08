package de.chromefiles.app

import de.chromefiles.app.data.archive.ArchiveFormat
import de.chromefiles.app.data.archive.ArchivePasswordRequiredException
import de.chromefiles.app.data.archive.MountableArchiveManager
import de.chromefiles.app.data.media.ChecksumCalculator
import de.chromefiles.app.data.remote.RemoteConnectionConfig
import de.chromefiles.app.data.remote.RemoteProtocol
import de.chromefiles.app.data.remote.WebDavRemoteClient
import de.chromefiles.app.model.VfsNode
import de.chromefiles.app.ui.components.BatchRenameEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class PowerFeaturesAndArchivesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBatchRenamePatternAndRegex() {
        val nodes = listOf(
            VfsNode(
                id = "/tmp/IMG_100.jpg",
                name = "IMG_100.jpg",
                path = "/tmp/IMG_100.jpg",
                parentPath = "/tmp",
                volumeId = "my_files",
                isDirectory = false,
                sizeBytes = 1024,
                lastModifiedMillis = 0L,
                mimeType = "image/jpeg",
                extension = "jpg"
            ),
            VfsNode(
                id = "/tmp/IMG_101.jpg",
                name = "IMG_101.jpg",
                path = "/tmp/IMG_101.jpg",
                parentPath = "/tmp",
                volumeId = "my_files",
                isDirectory = false,
                sizeBytes = 2048,
                lastModifiedMillis = 0L,
                mimeType = "image/jpeg",
                extension = "jpg"
            )
        )

        val preview = BatchRenameEngine.computePreview(
            nodes = nodes,
            searchText = "IMG",
            replaceText = "Reise",
            useRegex = false,
            pattern = "{name}_{###}",
            startNumber = 1
        )

        assertEquals(2, preview.size)
        assertEquals("Reise_100_001.jpg", preview[0].newName)
        assertEquals("Reise_101_002.jpg", preview[1].newName)
        assertTrue(preview.all { it.isChanged })
    }

    @Test
    fun testChecksumCalculatorMd5AndSha256() = runBlocking {
        val file = tempFolder.newFile("checksum_test.txt")
        file.writeText("ChromeFiles Pro 2026", Charsets.UTF_8)

        val hashes = ChecksumCalculator.computeHashes(file).getOrThrow()
        assertEquals(32, hashes.md5.length)
        assertEquals(64, hashes.sha256.length)

        val hashesAgain = ChecksumCalculator.computeHashes(file).getOrThrow()
        assertEquals(hashes.md5, hashesAgain.md5)
        assertEquals(hashes.sha256, hashesAgain.sha256)
    }

    @Test
    fun testArchiveFormatsAndEncryptedZipMounting() = runBlocking {
        val manager = MountableArchiveManager()
        val srcDir = tempFolder.newFolder("source_files")
        val f1 = File(srcDir, "hallo.txt").apply { writeText("Hallo ChromeOS!", Charsets.UTF_8) }
        val sub = File(srcDir, "unterordner").apply { mkdirs() }
        File(sub, "notiz.md").writeText("# Notiz", Charsets.UTF_8)

        val mountWorkspace = tempFolder.newFolder("mounts")

        // 1. TAR.GZ erstellen & mounten
        val tarGzFile = File(tempFolder.root, "test.tar.gz")
        manager.compressNodesToArchive(
            sourceFiles = listOf(f1, sub),
            destinationFile = tarGzFile,
            format = ArchiveFormat.TAR_GZ
        ).getOrThrow()
        assertTrue(tarGzFile.exists() && tarGzFile.length() > 0)

        val tarVolume = manager.mountArchive(tarGzFile, mountWorkspace).getOrThrow()
        assertEquals("Hallo ChromeOS!", File(tarVolume.rootPath, "hallo.txt").readText(Charsets.UTF_8))
        assertEquals("# Notiz", File(tarVolume.rootPath, "unterordner/notiz.md").readText(Charsets.UTF_8))

        // 2. 7z erstellen & mounten
        val sevenZFile = File(tempFolder.root, "test.7z")
        manager.compressNodesToArchive(
            sourceFiles = listOf(f1, sub),
            destinationFile = sevenZFile,
            format = ArchiveFormat.SEVEN_Z
        ).getOrThrow()
        assertTrue(sevenZFile.exists() && sevenZFile.length() > 0)

        val sevenZVolume = manager.mountArchive(sevenZFile, mountWorkspace).getOrThrow()
        assertEquals("Hallo ChromeOS!", File(sevenZVolume.rootPath, "hallo.txt").readText(Charsets.UTF_8))

        // 3. Verschlüsseltes ZIP erstellen & Passwort-Prüfung testen
        val encZipFile = File(tempFolder.root, "geheim.zip")
        manager.compressNodesToArchive(
            sourceFiles = listOf(f1),
            destinationFile = encZipFile,
            format = ArchiveFormat.ZIP,
            password = "sicheresPasswort123".toCharArray()
        ).getOrThrow()

        val withoutPw = manager.mountArchive(encZipFile, mountWorkspace, password = null)
        assertTrue(withoutPw.isFailure)
        assertTrue(withoutPw.exceptionOrNull() is ArchivePasswordRequiredException)

        val withPwVolume = manager.mountArchive(encZipFile, mountWorkspace, password = "sicheresPasswort123".toCharArray()).getOrThrow()
        assertEquals("Hallo ChromeOS!", File(withPwVolume.rootPath, "hallo.txt").readText(Charsets.UTF_8))
    }

    @Test
    fun testWebDavRemoteClientAgainstEmbeddedServer() {
        val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        files["/dav/test.txt"] = "Inhalt über WebDAV".toByteArray(Charsets.UTF_8)

        val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(start = true, isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                socket.use { client ->
                    val input = client.getInputStream()
                    val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                    val requestLine = reader.readLine() ?: return@use
                    val parts = requestLine.split(' ')
                    val method = parts.getOrNull(0).orEmpty()
                    val reqPath = parts.getOrNull(1).orEmpty()

                    var contentLength = 0
                    var depth = "1"
                    while (true) {
                        val header = reader.readLine()
                        if (header.isNullOrEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                        } else if (header.startsWith("Depth:", ignoreCase = true)) {
                            depth = header.substringAfter(':').trim()
                        }
                    }
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (r <= 0) break
                        readTotal += r
                    }
                    val bodyText = String(bodyChars, 0, readTotal)

                    val out = client.getOutputStream()
                    when (method) {
                        "PROPFIND" -> {
                            val xml = buildString {
                                append("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""")
                                append("""<d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
                                if (depth != "0") {
                                    for ((p, bytes) in files) {
                                        append("""<d:response><d:href>$p</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${bytes.size}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
                                    }
                                }
                                append("""</d:multistatus>""")
                            }.toByteArray(Charsets.UTF_8)
                            val resp = "HTTP/1.1 207 Multi-Status\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${xml.size}\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                            out.write(xml)
                        }
                        "GET" -> {
                            val bytes = files[reqPath] ?: ByteArray(0)
                            val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.UTF_8))
                            out.write(bytes)
                        }
                        "PUT" -> {
                            files[reqPath] = bodyText.toByteArray(Charsets.UTF_8)
                            out.write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        }
                        "DELETE" -> {
                            files.remove(reqPath)
                            out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        }
                        else -> {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        }
                    }
                    out.flush()
                }
            }
        }

        try {
            val config = RemoteConnectionConfig(
                protocol = RemoteProtocol.WEBDAV,
                host = "127.0.0.1",
                port = serverSocket.localPort,
                shareOrBasePath = "/dav",
                username = "admin",
                password = "secret",
                useHttps = false
            )
            WebDavRemoteClient.connect(config).use { client ->
                val entries = client.list("")
                assertEquals(1, entries.size)
                assertEquals("test.txt", entries[0].name)

                val out = ByteArrayOutputStream()
                client.download("test.txt", out) {}
                assertEquals("Inhalt über WebDAV", out.toString("UTF-8"))

                val uploadData = "Neue Datei".toByteArray(Charsets.UTF_8)
                client.upload("neu.txt", uploadData.inputStream(), uploadData.size.toLong()) {}
                assertEquals(2, client.list("").size)

                client.delete("neu.txt", isDirectory = false)
                assertEquals(1, client.list("").size)
            }
        } finally {
            runCatching { serverSocket.close() }
            serverThread.join(1000)
        }
    }
}
