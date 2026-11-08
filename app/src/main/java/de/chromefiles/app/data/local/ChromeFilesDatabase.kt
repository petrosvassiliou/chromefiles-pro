package de.chromefiles.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Persistente Metadaten-Tabelle für Dateien & Ordner (Favoriten/Starred, Offline-Verfügbarkeit,
 * Tags, Text-Auszug für Volltextsuche und "Zuletzt verwendet"-Zeitstempel).
 */
@Entity(tableName = "file_metadata")
data class FileMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "volume_id")
    val volumeId: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "tags")
    val tags: String = "",
    @ColumnInfo(name = "content_excerpt")
    val contentExcerpt: String = "",
    @ColumnInfo(name = "is_starred")
    val isStarred: Boolean = false,
    @ColumnInfo(name = "is_offline_pinned")
    val isOfflinePinned: Boolean = false,
    @ColumnInfo(name = "last_accessed_millis")
    val lastAccessedMillis: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_modified_millis")
    val lastModifiedMillis: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0L,
    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean = false
)

/**
 * Volltext-Suchindex (SQLite FTS) über Dateiname, Pfad, Tags und Dateiinhalt.
 */
@Fts4(contentEntity = FileMetadataEntity::class)
@Entity(tableName = "file_metadata_fts")
data class FileSearchFtsEntity(
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "tags")
    val tags: String,
    @ColumnInfo(name = "content_excerpt")
    val contentExcerpt: String
)

/**
 * Papierkorb-Eintrag (.Trash) mit Ursprungspfad und Löschdatum (30-Tage-Auto-Löschung wie in ChromeOS).
 */
@Entity(tableName = "trash_entries")
data class TrashEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "trash_path")
    val trashPath: String,
    @ColumnInfo(name = "original_path")
    val originalPath: String,
    @ColumnInfo(name = "original_volume_id")
    val originalVolumeId: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "trashed_at_millis")
    val trashedAtMillis: Long
)

/**
 * Gespeicherte SMB / SFTP / SAF-Mount-Verbindungen.
 */
@Entity(tableName = "mounted_connections")
data class MountedConnectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "volume_id")
    val volumeId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "protocol")
    val protocol: String, // SMB, SFTP, WEBDAV, SAF
    @ColumnInfo(name = "host_or_uri")
    val hostOrUri: String,
    @ColumnInfo(name = "share_or_remote_path")
    val shareOrRemotePath: String,
    @ColumnInfo(name = "username")
    val username: String,
    @ColumnInfo(name = "local_mount_path")
    val localMountPath: String,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface ChromeFilesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(entity: FileMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadataAll(entities: List<FileMetadataEntity>)

    @Query("SELECT * FROM file_metadata WHERE path = :path LIMIT 1")
    suspend fun getMetadataByPath(path: String): FileMetadataEntity?

    @Query("SELECT * FROM file_metadata WHERE path IN (:paths)")
    suspend fun getMetadataForPaths(paths: List<String>): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE is_starred = 1 ORDER BY name ASC")
    fun observeStarredFiles(): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata WHERE is_starred = 1 ORDER BY name ASC")
    suspend fun getStarredFiles(): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE is_offline_pinned = 1 ORDER BY name ASC")
    suspend fun getOfflinePinnedFiles(): List<FileMetadataEntity>

    @Query("SELECT * FROM file_metadata WHERE is_directory = 0 ORDER BY last_accessed_millis DESC LIMIT :limit")
    suspend fun getRecentFiles(limit: Int = 100): List<FileMetadataEntity>

    @Query("UPDATE file_metadata SET is_starred = :starred WHERE path = :path")
    suspend fun updateStarred(path: String, starred: Boolean)

    @Query("UPDATE file_metadata SET is_offline_pinned = :pinned WHERE path = :path")
    suspend fun updateOfflinePinned(path: String, pinned: Boolean)

    @Query("DELETE FROM file_metadata WHERE path = :path")
    suspend fun deleteMetadataByPath(path: String)

    @Query("DELETE FROM file_metadata WHERE path LIKE :pathPrefix || '%'")
    suspend fun deleteMetadataByPathPrefix(pathPrefix: String)

    /**
     * Echtzeit-Volltextsuche über SQLite FTS4/FTS5 inkl. Fallback auf Teilstring-Suche.
     */
    @Query(
        """
        SELECT file_metadata.* FROM file_metadata
        JOIN file_metadata_fts ON file_metadata.rowid = file_metadata_fts.rowid
        WHERE file_metadata_fts MATCH :ftsQuery
        ORDER BY file_metadata.last_modified_millis DESC
        """
    )
    suspend fun searchFullText(ftsQuery: String): List<FileMetadataEntity>

    @Query(
        """
        SELECT * FROM file_metadata
        WHERE name LIKE '%' || :rawQuery || '%'
           OR content_excerpt LIKE '%' || :rawQuery || '%'
           OR tags LIKE '%' || :rawQuery || '%'
        ORDER BY last_modified_millis DESC
        """
    )
    suspend fun searchSubstring(rawQuery: String): List<FileMetadataEntity>

    // Papierkorb-Operationen (.Trash)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashEntry(entry: TrashEntryEntity)

    @Query("SELECT * FROM trash_entries ORDER BY trashed_at_millis DESC")
    suspend fun getAllTrashEntries(): List<TrashEntryEntity>

    @Query("SELECT * FROM trash_entries ORDER BY trashed_at_millis DESC")
    fun observeTrashEntries(): Flow<List<TrashEntryEntity>>

    @Query("SELECT * FROM trash_entries WHERE trash_path = :trashPath LIMIT 1")
    suspend fun getTrashEntry(trashPath: String): TrashEntryEntity?

    @Query("DELETE FROM trash_entries WHERE trash_path = :trashPath")
    suspend fun deleteTrashEntry(trashPath: String)

    @Query("DELETE FROM trash_entries")
    suspend fun clearAllTrashEntries()

    @Query("SELECT * FROM trash_entries WHERE trashed_at_millis < :cutoffMillis")
    suspend fun getExpiredTrashEntries(cutoffMillis: Long): List<TrashEntryEntity>

    // Netzwerk- & SAF-Mounts
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMountedConnection(connection: MountedConnectionEntity)

    @Query("SELECT * FROM mounted_connections ORDER BY created_at_millis ASC")
    suspend fun getAllMountedConnections(): List<MountedConnectionEntity>

    @Query("DELETE FROM mounted_connections WHERE volume_id = :volumeId")
    suspend fun deleteMountedConnection(volumeId: String)
}

@Database(
    entities = [
        FileMetadataEntity::class,
        FileSearchFtsEntity::class,
        TrashEntryEntity::class,
        MountedConnectionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ChromeFilesDatabase : RoomDatabase() {
    abstract fun chromeFilesDao(): ChromeFilesDao
}
