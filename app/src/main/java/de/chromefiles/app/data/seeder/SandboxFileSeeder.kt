package de.chromefiles.app.data.seeder

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entfernt etwaige alte Sandbox-/Demo-Dateien früherer Versionen.
 * ChromeFiles Pro arbeitet ausschließlich mit dem echten lokalen Dateisystem,
 * echten SAF-Ordnern, echten Netzwerkfreigaben und echtem Google Drive.
 */
@Singleton
class SandboxFileSeeder @Inject constructor() {

    fun ensureSeeded(sandboxRootDir: File) {
        // Alte Fake-/Demo-Verzeichnisse restlos löschen, falls von einer Vorgängerversion vorhanden.
        runCatching {
            if (sandboxRootDir.exists()) {
                File(sandboxRootDir, "Meine Dateien").deleteRecursively()
                File(sandboxRootDir, "Linux-Dateien").deleteRecursively()
                File(sandboxRootDir, "Google Drive").deleteRecursively()
                File(sandboxRootDir, ".seeded_v1").delete()
            }
        }
    }
}
