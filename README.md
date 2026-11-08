# Files Pro (`de.chromefiles.app`)

**Files Pro** ist ein kompromissloser, nativer 1:1 **ChromeOS Files Dateimanager** für **Googlebook OS (ChromeOS)** und **Android** – vollständig entwickelt in **100 % Kotlin** und **Jetpack Compose Material 3 Expressive & Dynamic Color (Material You)**, komplett auf **Deutsch (`de-DE`)** lokalisiert und für Touch, Tastatur, Maus/Trackpad und große Bildschirme optimiert.

---

## Highlights & Funktionsumfang (v1.2.0)

### 1. Exaktes 1:1 ChromeOS Files Layout (3-Spalten-Architektur)
- **Top Action Bar:** Interaktive Breadcrumb-Pfadleiste (jedes Segment anklickbar & als Drag-&-Drop-Ziel nutzbar), Echtzeit-Suchleiste mit den 3 originalen ChromeOS-Filter-Chips (*Ort*, *Änderungsdatum*, *Dateityp*) sowie Ansichtsumschalter (Detail-Tabelle vs. Thumbnail-Grid), Sortierung und Toggle für die rechte Info-Spalte (`i`-Button).
- **Linke Seitenleiste (Navigation Tree):** Einklappbar & per Maus in der Breite ziehbar, inkl. *Zuletzt verwendet* (mit Typ-Filter-Chips), *Favoriten* (mit Drag-&-Drop-Ziel), *Meine Dateien* (direkter Geräte-Speicher), *Linux-Dateien (Crostini)*, *Google Drive* (1-Klick Google-Konto-Anmeldung & SAF-Einbindung inkl. asynchronem Cloud-Laden), *Eingehängte Archive & Wechselmedien* (mit direktem `Eject`-Button), *Papierkorb* (mit Wiederherstellen & 30-Tage-Richtlinie) sowie *Speicherplatz-Indikator*.
- **Hauptbereich (Dual-Pane & Tabs):** Sortierbare Detail-Listenansicht mit per Maus ziehbaren Spaltenbreiten (*Name, Größe, Typ, Änderungsdatum*), direktem `☆`/`★`-Favoriten-Stern und `⋮`-Kontextmenü-Button pro Zeile/Kachel, gruppierte Thumbnail-Rasteransicht, Farb-Tags (5 Farben) und Unterstützung für Tabs (`Strg + T`) sowie Dual-Pane Split-View (`Strg + N`).
- **Rechte Info- & Metadaten-Spalte (Details Panel):** Großes Vorschaubild, Dateigröße, Pfad, EXIF-Kameradaten (Modell, Blende, Belichtung, ISO, GPS), Audio-ID3-Tags, Farb-Tag-Auswahl, Offline-Schalter und integrierter **MD5- & SHA-256-Prüfsummen-Rechner** mit Hash-Verifikation.

### 2. Echte Googlebook Desktop- & Touch-Interaktionen
- **Kontextmenüs überall:** Rechtsklick per Maus/Trackpad, Long-Press auf Smartphones/Tablets oder direkt über den `⋮`-Button an jeder Datei/jedem Ordner, plus **Auswahl-Aktionsleiste** am oberen Rand für schnelles Kopieren, Ausschneiden, Einfügen, Favorisieren, Komprimieren, Entpacken, Teilen und Löschen.
- **Drag & Drop für Dateioperationen & Favoriten:** Dateien und Ordner direkt in andere Ordner, Breadcrumbs, Seitenleisten-Ziele oder in die automatische **Schnell-Drop-Leiste** (*Zu Favoriten hinzufügen*, *In Überordner verschieben*, *In Papierkorb*) ziehen – sowohl mit der Maus als auch per Touch.
- **Leertaste = QuickView Modal:** Sofortige Vorschau für Bilder (mit Zoom & verlustfreiem Drehen/Zuschneiden/Skalieren), Videos & Audio (Media3 ExoPlayer mit Waveform/Scrubber), mehrseitige PDFs (`PdfRenderer`) sowie Code-/Markdown-/JSON-/Textdateien mit **integriertem Text-/Code-Editor (`Strg + S`)** und Pfeiltasten-Navigation (`←`/`→`).
- **Maus-Lasso (Marquee Selection):** Gummiband-Auswahl per gedrückter linker Maustaste inkl. automatischem Rand-Scrolling.
- **Vollständige Tastatur-Shortcuts:**
  - `Strg + C` / `Strg + X` / `Strg + V` / `Strg + A`
  - `Strg + E` / `Strg + Umschalt + N` (Neuer Ordner)
  - `Strg + Enter` (Inline-Umbenennen bzw. **Mehrfach-Umbenennen / Batch Rename** mit `{name}`, `{###}`, `{ext}` und Regex)
  - `Entf` / `Alt + Backspace` (Papierkorb) & `Umschalt + Entf` (Endgültig löschen)
  - `Strg + F` (Suche), `Strg + .` (Versteckte `.dotfiles` umschalten), `Strg + T` (Neuer Tab), `Strg + N` (Split-View)
- **Schwebendes Operations-Center:** Asynchrone Hintergrund-Jobs mit Übertragungsrate (MB/s), Restzeit, Pause/Fortsetzen/Abbrechen und Konfliktlösungs-Dialog (*Ersetzen*, *Beide behalten*, *Überspringen*).

### 3. Virtuelles Dateisystem (VFS), Archive, Cloud & Netzwerk
- **Lokaler Speicher & SAF:** Direkter Zugriff auf den echten internen Speicher (`/storage/emulated/0`) kombiniert mit dem Android Storage Access Framework (`DocumentsContract`, inkl. `EXTRA_LOADING`-Cloud-Synchronisation) für Google Drive, ChromeOS Linux-Dateien (Crostini), SD/USB-Medien und einen eigenen `ChromeFilesDocumentsProvider` für andere Apps.
- **Archive komprimieren, entpacken & einhängen:**
  - **Komprimieren:** Auswahl direkt als `.zip`, `.rar`, `.7z` oder `.tar.gz` (inkl. optionalem AES-256 Passwortschutz) verpacken.
  - **Hier entpacken:** `.zip`, `.rar`, `.7z`, `.tar.gz`, `.tgz` und `.tar` mit einem Klick direkt in einen Ordner entpacken.
  - **Als Laufwerk einhängen:** Archive schreibgeschützt als virtuelles Laufwerk in der linken Seitenleiste mounten.
- **Google Drive Integration:** 1-Klick Google-Konto-Anmeldung (`GoogleAuthUtil`) mit automatischem SAF-Drive-Fallback, serverseitige Drive-Suche, Upload/Download und Offline-Pinnung.
- **SMB / SFTP / WebDAV:** Native Clients (`smbj`, `sshj` inkl. TOFU-Host-Key-Prüfung sowie Passwort- & `id_ed25519`/`id_rsa` SSH-Private-Key-Authentifizierung, `OkHttp` WebDAV) mit AES-256-GCM-Verschlüsselung im Android Keystore.
- **Room SQLite FTS4 Index:** Echtzeit-Volltextsuche über Dateinamen, Favoriten, Tags und Text-/Code-Inhalte (`contentExcerpt`).

---

## Build & Installation

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```

Fertige **Debug- und signierte Release-APKs** stehen direkt unter **[Releases](../../releases)** zum Download bereit.
