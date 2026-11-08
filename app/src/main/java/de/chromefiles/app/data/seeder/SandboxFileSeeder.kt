package de.chromefiles.app.data.seeder

import de.chromefiles.app.data.local.ChromeFilesDao
import de.chromefiles.app.data.local.FileMetadataEntity
import de.chromefiles.app.data.local.TrashEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erstellt beim ersten App-Start (oder für die Sandbox-/Googlebook-Umgebung)
 * eine realistische ChromeOS-Verzeichnisstruktur auf Deutsch inkl. echter Beispiel-Dateien,
 * Quellcode, Bildern (SVG), einem echten mountbaren .zip-Archiv sowie einem Papierkorb-Eintrag.
 */
@Singleton
class SandboxFileSeeder @Inject constructor(
    private val dao: ChromeFilesDao
) {
    suspend fun ensureSeeded(baseSandboxRoot: File) = withContext(Dispatchers.IO) {
        val markerFile = File(baseSandboxRoot, ".chromefiles_seeded_v1")
        if (markerFile.exists()) return@withContext

        val myFilesRoot = File(baseSandboxRoot, "Meine Dateien").apply { mkdirs() }
        val downloadsDir = File(myFilesRoot, "Downloads").apply { mkdirs() }
        val playFilesDir = File(myFilesRoot, "Play-Dateien").apply { mkdirs() }
        val cameraDir = File(myFilesRoot, "Kamera").apply { mkdirs() }
        val projects2026Dir = File(myFilesRoot, "Projekte/2026").apply { mkdirs() }

        val linuxRoot = File(baseSandboxRoot, "Linux-Dateien").apply { mkdirs() }
        val driveRoot = File(baseSandboxRoot, "Google Drive").apply { mkdirs() }
        val myDriveDir = File(driveRoot, "Meine Ablage").apply { mkdirs() }
        val sharedDrivesDir = File(driveRoot, "Geteilte Ablagen/Produktdesign-Team").apply { mkdirs() }
        val sharedWithMeDir = File(driveRoot, "Für mich freigegeben").apply { mkdirs() }
        val computersDir = File(driveRoot, "Computer/Googlebook Pro 16").apply { mkdirs() }
        val offlineDir = File(driveRoot, "Offline verfügbar").apply { mkdirs() }

        val trashRoot = File(baseSandboxRoot, ".Trash").apply { mkdirs() }

        // 1. Meine Dateien -> Projekte/2026
        val architectureDoc = File(projects2026Dir, "ChromeOS_Architektur_Spezifikation.md").apply {
            writeText(
                """
                # ChromeFiles Pro – Googlebook Desktop Architektur (2026)

                ## Übersicht
                ChromeFiles Pro bildet die offizielle **ChromeOS Files App** 1:1 nativ in Kotlin & Jetpack Compose Material 3 ab.

                ### Kernfunktionen
                - **3-Spalten-Desktop-Layout:** Größenverstellbare Baum-Navigation, Dateibereich & Detail-Inspektor (`i`-Button).
                - **Mountbare Archive:** `.zip`, `.tar.gz` und `.7z` werden ohne Entpacken direkt als Laufwerk eingehängt.
                - **Leertaste = QuickView:** Sofortige Vorschau für Bilder, PDFs, Audio/Video und Quellcode.
                - **Tastatur & Maus:** Gummiband-Auswahl (Marquee), `Strg+C/X/V`, `Strg+Enter`, `Entf` & Rechtsklick-Kontextmenü.
                """.trimIndent()
            )
        }

        val budgetCsv = File(projects2026Dir, "Q4_Hardware_Budget_2026.csv").apply {
            writeText(
                """
                Position,Kategorie,Menge,Einzelpreis_EUR,Gesamt_EUR,Status
                Googlebook Pixel Pro,Hardware,12,1499.00,17988.00,Genehmigt
                USB-C NVMe Dock 4TB,Speicher,12,249.00,2988.00,Bestellt
                Testgeräte Android 15,QA,6,699.00,4194.00,Geliefert
                """.trimIndent()
            )
        }

        val dotConfigFile = File(projects2026Dir, ".editorconfig").apply {
            writeText(
                """
                root = true

                [*]
                charset = utf-8
                indent_style = space
                indent_size = 4
                insert_final_newline = true
                """.trimIndent()
            )
        }

        // 2. Meine Dateien -> Kamera (Hochauflösende SVG-Vorschaubilder)
        val wallpaperSvg = File(cameraDir, "Googlebook_Alpen_Panorama.svg").apply {
            writeText(
                """
                <svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800" viewBox="0 0 1200 800">
                  <defs>
                    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stop-color="#0B2545"/>
                      <stop offset="60%" stop-color="#134074"/>
                      <stop offset="100%" stop-color="#8DA9C4"/>
                    </linearGradient>
                  </defs>
                  <rect width="1200" height="800" fill="url(#sky)"/>
                  <circle cx="920" cy="190" r="72" fill="#FFD166"/>
                  <polygon points="0,800 280,340 620,800" fill="#1D3557"/>
                  <polygon points="340,800 690,240 1080,800" fill="#2A4D69"/>
                  <polygon points="680,800 960,390 1200,800" fill="#4B86B4"/>
                </svg>
                """.trimIndent()
            )
        }

        val uiMockupSvg = File(cameraDir, "ChromeOS_MaterialYou_Design.svg").apply {
            writeText(
                """
                <svg xmlns="http://www.w3.org/2000/svg" width="960" height="640" viewBox="0 0 960 640">
                  <rect width="960" height="640" rx="24" fill="#1A73E8"/>
                  <rect x="40" y="40" width="220" height="560" rx="16" fill="#E8F0FE"/>
                  <rect x="280" y="40" width="640" height="560" rx="16" fill="#FFFFFF"/>
                  <circle cx="340" cy="110" r="28" fill="#34A853"/>
                  <circle cx="420" cy="110" r="28" fill="#FBBC04"/>
                  <circle cx="500" cy="110" r="28" fill="#EA4335"/>
                </svg>
                """.trimIndent()
            )
        }

        // 3. Meine Dateien -> Downloads (inkl. echtem mountbaren ZIP-Archiv!)
        val releaseNotesTxt = File(downloadsDir, "Versionshinweise_ChromeOS_132.txt").apply {
            writeText(
                """
                ChromeOS 132 Release Notes (Deutsch)
                ===================================
                - Verbesserte Unterstützung für eingehängte ZIP- und 7z-Archive
                - Schnellere Volltextsuche über lokale Dateien und Google Drive
                - Optimierte QuickView-Vorschau per Leertaste
                """.trimIndent()
            )
        }

        val zipStagingDir = File(baseSandboxRoot, ".zip_staging").apply { mkdirs() }
        val stagedReadme = File(zipStagingDir, "LIESMICH.md").apply {
            writeText(
                """
                # Projekt Release v2.4 (Eingehängtes Archiv)
                Dieses Archiv wurde direkt in **ChromeFiles Pro** als virtuelles Laufwerk eingehängt –
                ganz ohne manuelles Entpacken!
                """.trimIndent()
            )
        }
        val stagedSrcDir = File(zipStagingDir, "src").apply { mkdirs() }
        File(stagedSrcDir, "ChromeBookWindowController.kt").writeText(
            """
            package de.chromefiles.release

            class ChromeBookWindowController {
                fun enableFreeformDesktopMode(): Boolean = true
            }
            """.trimIndent()
        )
        File(stagedSrcDir, "manifest.json").writeText(
            """
            {
              "package": "de.chromefiles.app",
              "version": "2.4.0",
              "platform": "ChromeOS / Googlebook"
            }
            """.trimIndent()
        )

        val sampleZipFile = File(downloadsDir, "Projekt_Release_v2.4.zip")
        if (sampleZipFile.exists()) sampleZipFile.delete()
        ZipFile(sampleZipFile).apply {
            val params = ZipParameters()
            addFile(stagedReadme, params)
            addFolder(stagedSrcDir, params)
        }
        zipStagingDir.deleteRecursively()

        // 4. Linux-Dateien (Crostini Container)
        val rustFile = File(linuxRoot, "vfs_daemon.rs").apply {
            writeText(
                """
                use std::path::PathBuf;

                pub struct CrostiniMountBridge {
                    pub container_name: String,
                    pub mount_point: PathBuf,
                }

                impl CrostiniMountBridge {
                    pub fn new() -> Self {
                        Self {
                            container_name: String::from("penguin"),
                            mount_point: PathBuf::from("/mnt/chromeos/MyFiles"),
                        }
                    }
                }
                """.trimIndent()
            )
        }

        val dockerComposeFile = File(linuxRoot, "docker-compose.yml").apply {
            writeText(
                """
                services:
                  chromefiles-webdav:
                    image: nginx:alpine
                    ports:
                      - "8080:80"
                    volumes:
                      - ./data:/var/www/html:ro
                """.trimIndent()
            )
        }

        File(linuxRoot, ".bashrc").writeText(
            """
            # ~/.bashrc: Crostini Linux-Umgebung (penguin)
            export EDITOR=nano
            alias ll='ls -lah --color=auto'
            """.trimIndent()
        )

        // 5. Google Drive -> Meine Ablage & Offline verfügbar
        val driveRoadmap = File(myDriveDir, "Googlebook_Produkt_Roadmap_2026.md").apply {
            writeText(
                """
                # Produkt-Roadmap 2026 (Google Drive Synchronisiert)
                1. **Phase 1:** Virtuelles Dateisystem, Mountable Archives & Room FTS-Index
                2. **Phase 2:** 3-Spalten ChromeOS Desktop-UI & Such-Filterchips
                3. **Phase 3:** Maus-Lasso, Drag & Drop, Rechtsklick-Menüs & Tastaturkürzel
                4. **Phase 4:** Leertaste-QuickView, Operations-Center & Cloud/SMB-Integration
                """.trimIndent()
            )
        }

        val sharedDesignSpec = File(sharedDrivesDir, "Material3_Expressive_Tokens.json").apply {
            writeText(
                """
                {
                  "theme": "ChromeOS Material 3 Expressive",
                  "primary": "#0B57D0",
                  "surfaceContainer": "#F0F4F9",
                  "cornerRadius": {
                    "sidebarItem": "24dp",
                    "searchBar": "28dp",
                    "fileRow": "12dp"
                  }
                }
                """.trimIndent()
            )
        }

        val sharedPresentation = File(sharedWithMeDir, "Quartalsbericht_DACH_2026.md").apply {
            writeText(
                """
                # Quartalsbericht DACH (Für mich freigegeben)
                Freigegeben von: **Anna Bergmann (Produktleitung)**
                Status: Bereit für Review auf dem Googlebook.
                """.trimIndent()
            )
        }

        File(offlineDir, "Offline_Notfallhandbuch_IT.txt").writeText(
            """
            Offline-Notfallhandbuch (Lokal gepinnt)
            =======================================
            Dieses Dokument ist für den Offline-Zugriff auf Ihrem Googlebook gespeichert.
            """.trimIndent()
        )

        File(playFilesDir, "Android_App_Backup_Config.json").writeText(
            """
            {
              "backupEnabled": true,
              "container": "ARC++ / ARCVM"
            }
            """.trimIndent()
        )

        File(computersDir, "Desktop_Sync_Protokoll.txt").writeText(
            "Synchronisiert mit Googlebook Pro 16 am 07.11.2026."
        )

        // 6. Beispiel-Eintrag im Papierkorb (.Trash)
        val trashedFile = File(trashRoot, "Alte_Entwurfsnotizen_2025.txt").apply {
            writeText("Diese Datei wurde in den Papierkorb verschoben und kann jederzeit wiederhergestellt werden.")
        }
        dao.insertTrashEntry(
            TrashEntryEntity(
                trashPath = trashedFile.absolutePath,
                originalPath = File(downloadsDir, "Alte_Entwurfsnotizen_2025.txt").absolutePath,
                originalVolumeId = "my_files",
                fileName = trashedFile.name,
                isDirectory = false,
                sizeBytes = trashedFile.length(),
                trashedAtMillis = System.currentTimeMillis() - (2L * 24 * 60 * 60 * 1000)
            )
        )

        // 7. Initialen Volltext-Index & Favoriten / Offline-Pins in Room eintragen
        val seededEntities = listOf(
            FileMetadataEntity(
                path = architectureDoc.absolutePath,
                name = architectureDoc.name,
                volumeId = "my_files",
                mimeType = "text/markdown",
                tags = "architektur,chromeos,spezifikation,2026",
                contentExcerpt = architectureDoc.readText().take(500),
                isStarred = true,
                isOfflinePinned = true,
                lastModifiedMillis = architectureDoc.lastModified(),
                sizeBytes = architectureDoc.length()
            ),
            FileMetadataEntity(
                path = budgetCsv.absolutePath,
                name = budgetCsv.name,
                volumeId = "my_files",
                mimeType = "text/csv",
                tags = "budget,hardware,finanzen",
                contentExcerpt = budgetCsv.readText().take(500),
                isStarred = false,
                isOfflinePinned = false,
                lastModifiedMillis = budgetCsv.lastModified(),
                sizeBytes = budgetCsv.length()
            ),
            FileMetadataEntity(
                path = wallpaperSvg.absolutePath,
                name = wallpaperSvg.name,
                volumeId = "my_files",
                mimeType = "image/svg+xml",
                tags = "bild,alpen,panorama,kamera",
                contentExcerpt = "Alpen Panorama Vektorgrafik",
                isStarred = true,
                isOfflinePinned = false,
                lastModifiedMillis = wallpaperSvg.lastModified(),
                sizeBytes = wallpaperSvg.length()
            ),
            FileMetadataEntity(
                path = uiMockupSvg.absolutePath,
                name = uiMockupSvg.name,
                volumeId = "my_files",
                mimeType = "image/svg+xml",
                tags = "design,material3,ui",
                contentExcerpt = "ChromeOS Material You Design Mockup",
                isStarred = false,
                isOfflinePinned = false,
                lastModifiedMillis = uiMockupSvg.lastModified(),
                sizeBytes = uiMockupSvg.length()
            ),
            FileMetadataEntity(
                path = sampleZipFile.absolutePath,
                name = sampleZipFile.name,
                volumeId = "my_files",
                mimeType = "application/zip",
                tags = "archiv,release,zip",
                contentExcerpt = "Projekt Release v2.4 ZIP-Archiv",
                isStarred = true,
                isOfflinePinned = false,
                lastModifiedMillis = sampleZipFile.lastModified(),
                sizeBytes = sampleZipFile.length()
            ),
            FileMetadataEntity(
                path = releaseNotesTxt.absolutePath,
                name = releaseNotesTxt.name,
                volumeId = "my_files",
                mimeType = "text/plain",
                tags = "release,chromeos,notes",
                contentExcerpt = releaseNotesTxt.readText().take(500),
                isStarred = false,
                isOfflinePinned = false,
                lastModifiedMillis = releaseNotesTxt.lastModified(),
                sizeBytes = releaseNotesTxt.length()
            ),
            FileMetadataEntity(
                path = rustFile.absolutePath,
                name = rustFile.name,
                volumeId = "linux_files",
                mimeType = "text/x-rust",
                tags = "linux,crostini,rust,code",
                contentExcerpt = rustFile.readText().take(500),
                isStarred = false,
                isOfflinePinned = false,
                lastModifiedMillis = rustFile.lastModified(),
                sizeBytes = rustFile.length()
            ),
            FileMetadataEntity(
                path = dockerComposeFile.absolutePath,
                name = dockerComposeFile.name,
                volumeId = "linux_files",
                mimeType = "text/yaml",
                tags = "linux,docker,container",
                contentExcerpt = dockerComposeFile.readText().take(500),
                isStarred = false,
                isOfflinePinned = false,
                lastModifiedMillis = dockerComposeFile.lastModified(),
                sizeBytes = dockerComposeFile.length()
            ),
            FileMetadataEntity(
                path = driveRoadmap.absolutePath,
                name = driveRoadmap.name,
                volumeId = "google_drive",
                mimeType = "text/markdown",
                tags = "drive,roadmap,2026",
                contentExcerpt = driveRoadmap.readText().take(500),
                isStarred = true,
                isOfflinePinned = true,
                lastModifiedMillis = driveRoadmap.lastModified(),
                sizeBytes = driveRoadmap.length()
            ),
            FileMetadataEntity(
                path = sharedDesignSpec.absolutePath,
                name = sharedDesignSpec.name,
                volumeId = "google_drive",
                mimeType = "application/json",
                tags = "drive,design,tokens,material3",
                contentExcerpt = sharedDesignSpec.readText().take(500),
                isStarred = false,
                isOfflinePinned = true,
                lastModifiedMillis = sharedDesignSpec.lastModified(),
                sizeBytes = sharedDesignSpec.length()
            ),
            FileMetadataEntity(
                path = sharedPresentation.absolutePath,
                name = sharedPresentation.name,
                volumeId = "google_drive",
                mimeType = "text/markdown",
                tags = "drive,quartalsbericht,dach",
                contentExcerpt = sharedPresentation.readText().take(500),
                isStarred = false,
                isOfflinePinned = false,
                lastModifiedMillis = sharedPresentation.lastModified(),
                sizeBytes = sharedPresentation.length()
            )
        )
        dao.upsertMetadataAll(seededEntities)

        markerFile.writeText("seeded_at=${System.currentTimeMillis()}")
    }
}
