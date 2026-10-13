package com.demonlab.lune.tools.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.demonlab.lune.data.MusicDatabase
import com.demonlab.lune.data.Playlist
import com.demonlab.lune.data.PlaylistSong
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.tools.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The card playlists folder.
 *
 * With SettingsManager.useCardPlaylists on, the .m3u / .m3u8 files in the picked
 * folder are the playlists. Each is mirrored into a Playlist row of its own, marked
 * by its sourceUri and kept apart from Lune's own playlists, so that everything
 * which lists, opens, counts or plays a playlist does it the one way it always has.
 *
 * A file is read again only when it has changed (its modification time and size
 * are kept on its row), or when the library has, since entries are resolved to
 * library tracks.
 *
 * Read only: the files are kept on a computer and copied over, so Lune writing into
 * them would only be undone by the next copy. MusicViewModel refuses every edit
 * while this is on.
 */
object CardPlaylistSync {

    private const val TAG = "CardPlaylistSync"
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private val lock = Mutex()

    private data class Child(val documentId: String, val name: String, val lastModified: Long, val size: Long)

    suspend fun sync(context: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
        lock.withLock { syncLocked(context.applicationContext, songs) }
    }

    private suspend fun syncLocked(context: Context, songs: List<Song>) {
        val settings = SettingsManager.getInstance(context)
        val treeUri = settings.cardPlaylistsFolderUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        val dao = MusicDatabase.getDatabase(context).playlistDao()

        // A folder that would not list is not a folder with no playlists: keep
        // what is there until it lists again.
        val children = listChildren(context, treeUri) ?: return

        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val paths = treeDocId?.let { M3uPlaylistParser.treeDocIdToPaths(it) }
        val volumeRoot = paths?.first
        val playlistDir = paths?.second

        val fingerprint = fingerprintOf(songs)
        val libraryChanged = fingerprint != settings.cardPlaylistsLibraryFingerprint
        val index by lazy { M3uPlaylistParser.PathIndex(songs.map { it.path to it.id }) }

        val existing = dao.getExternalPlaylists().associateBy { it.sourceUri }
        val seenUris = HashSet<String>()
        val seenNames = HashSet<String>()
        var readAgain = 0

        // By name, so that of "Mix.m3u" and "Mix.m3u8" it is always the same one
        // that is the playlist.
        for (child in children.sortedBy { it.name.lowercase() }) {
            val name = child.name.substringBeforeLast('.').trim()
            if (name.isEmpty() || !seenNames.add(name.lowercase())) continue

            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId).toString()
            seenUris.add(docUri)

            val row = existing[docUri]
            if (row != null && !libraryChanged &&
                row.sourceLastModified == child.lastModified && row.sourceSize == child.size && row.name == name
            ) {
                continue // unchanged since it was last read
            }

            val text = readText(context, Uri.parse(docUri)) ?: continue
            val songIds = M3uPlaylistParser.entries(text).mapNotNull { entry ->
                index.find(M3uPlaylistParser.resolve(entry, playlistDir, volumeRoot), entry)
            }

            val playlistId = if (row != null) {
                // In place, so the id -- and the stats and shuffle setting kept under
                // it -- stays the same.
                dao.updatePlaylist(
                    row.copy(name = name, createdAt = child.lastModified, sourceLastModified = child.lastModified, sourceSize = child.size)
                )
                dao.deleteSongsForPlaylist(row.id)
                row.id
            } else {
                dao.insertPlaylist(
                    Playlist(
                        name = name,
                        createdAt = child.lastModified,
                        sourceUri = docUri,
                        sourceLastModified = child.lastModified,
                        sourceSize = child.size
                    )
                )
            }
            // An empty file is still a playlist: it is on the card, and it would be
            // stranger for it to vanish from the list than to be empty.
            if (songIds.isNotEmpty()) {
                val now = System.currentTimeMillis()
                dao.addSongsToPlaylist(songIds.mapIndexed { i, id -> PlaylistSong(playlistId, id, addedAt = now, position = i) })
            }
            readAgain++
        }

        // And the rows whose file has gone: deleted or renamed on a computer, or a
        // different folder picked.
        var dropped = 0
        for ((uri, row) in existing) {
            if (uri !in seenUris) {
                dao.deletePlaylist(row)
                dropped++
            }
        }

        settings.cardPlaylistsLibraryFingerprint = fingerprint
        if (readAgain > 0 || dropped > 0) {
            Log.d(TAG, "card folder: $readAgain read, $dropped gone")
        }
    }

    /** The playlist files directly in the folder, or null if it could not be listed. */
    private fun listChildren(context: Context, treeUri: Uri): List<Child>? = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val out = mutableListOf<Child>()
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                // Dot files too: a Mac leaves a "._Name.m3u" beside every file it
                // copies, and that is not a playlist.
                if (name.startsWith(".") || mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext != "m3u" && ext != "m3u8") continue
                out.add(Child(id, name, if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not list $treeUri", e)
        null
    }

    private fun readText(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_FILE_BYTES) null else M3uPlaylistParser.decode(bytes)
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read $uri", e)
        null
    }

    private fun fingerprintOf(songs: List<Song>): String {
        var hash = 17L
        for (s in songs.sortedBy { it.id }) {
            hash = hash * 31 + s.id
            hash = hash * 31 + s.path.hashCode()
        }
        return "${songs.size}:$hash"
    }
}
