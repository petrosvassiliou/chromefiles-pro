package de.chromefiles.app

import de.chromefiles.app.data.archive.MountableArchiveManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MountableArchiveManagerTest {

    @Test
    fun `compressNodesToZip and mountArchive mounts virtual volume and unmounts cleanly`() = runBlocking {
        val tempRoot = Files.createTempDirectory("chromefiles_test").toFile()
        try {
            val sourceDir = File(tempRoot, "source").apply { mkdirs() }
            val sampleDoc = File(sourceDir, "Bericht_2026.md").apply {
                writeText("# ChromeOS Files Test\nVolle ZIP-Mounting-Unterstützung.")
            }
            val zipOut = File(tempRoot, "Release_Test.zip")
            val mountsDir = File(tempRoot, "mounts").apply { mkdirs() }

            val manager = MountableArchiveManager()
            val zipResult = manager.compressNodesToZip(listOf(sampleDoc), zipOut)
            assertTrue("ZIP creation should succeed", zipResult.isSuccess)
            assertTrue("ZIP file should exist on disk", zipOut.exists())

            val mountResult = manager.mountArchive(zipOut, mountsDir)
            assertTrue("Archive mount should succeed", mountResult.isSuccess)

            val mountedVolume = mountResult.getOrThrow()
            assertEquals("Release_Test.zip", mountedVolume.title)
            assertTrue(mountedVolume.isEjectable)

            val extractedFile = File(mountedVolume.rootPath, "Bericht_2026.md")
            assertTrue("Mounted archive should expose Bericht_2026.md", extractedFile.exists())

            val ejected = manager.unmountArchive(mountedVolume.id)
            assertTrue("Unmounting archive should return true", ejected)
            assertTrue("Mounted volume list should be empty after eject", manager.getMountedVolumeList().isEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `mountArchive recognizes rar extension and fails cleanly on invalid rar payload`() = runBlocking {
        val tempRoot = Files.createTempDirectory("chromefiles_rar_test").toFile()
        try {
            val fakeRar = File(tempRoot, "Defekt.rar").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21)) }
            val mountsDir = File(tempRoot, "mounts").apply { mkdirs() }
            val manager = MountableArchiveManager()
            val res = manager.mountArchive(fakeRar, mountsDir)
            assertTrue("Invalid RAR stream should return failure Result", res.isFailure)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
