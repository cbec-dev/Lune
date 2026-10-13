package com.demonlab.lune.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.demonlab.lune.data.MusicDatabase
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import android.media.MediaScannerConnection
import android.provider.DocumentsContract
import com.demonlab.lune.tools.playlist.M3uPlaylistParser
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class UriTypeAdapter : TypeAdapter<Uri>() {
    override fun write(out: JsonWriter, value: Uri?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.toString())
        }
    }

    override fun read(reader: JsonReader): Uri? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return reader.nextString().toUri() // Modern KTX string-to-Uri converter
    }
}

class MusicProvider(private val context: Context) {
    private val settingsManager = SettingsManager.getInstance(context)
    private val database = MusicDatabase.getDatabase(context)

    private val cacheFile = File(context.filesDir, "songs_cache.json")
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriTypeAdapter())
        .disableHtmlEscaping()
        .create()

    fun getCachedSongs(): List<Song> {
        if (!cacheFile.exists()) return emptyList()
        return try {
            val json = cacheFile.readText()
            val type = object : TypeToken<List<Song>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveToCache(songs: List<Song>) {
        try {
            val json = gson.toJson(songs)
            cacheFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateSongInCache(updatedSong: Song) {
        val songs = getCachedSongs().toMutableList()
        val idx = songs.indexOfFirst { it.id == updatedSong.id }
        if (idx >= 0) {
            songs[idx] = updatedSong
            saveToCache(songs)
        } else {
            songs.add(updatedSong)
            saveToCache(songs)
        }
    }

    private fun hasReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Re-reads the library from MediaStore. [onProgress] gets (songs done, total)
     * as it goes, from any thread.
     */
    suspend fun syncSongs(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<Song> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()
        val songList = mutableListOf<Song>()
        val hiFiInfo = HiFiInfoCache(context)

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.Audio.Media.GENRE
            } else {
                MediaStore.Audio.Media.ARTIST
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projectionList.add(MediaStore.Audio.Media.BITRATE)
        }
        val projection = projectionList.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val overrides = database.songOverrideDao().getAllOverrides().associateBy { it.songId }

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val genreColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else -1
            val bitrateColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            } else -1

            val total = cursor.count
            onProgress(0, total)
            while (cursor.moveToNext()) {
                // Not every row: a callback per song would cost more than the song.
                if (cursor.position % 25 == 0) onProgress(cursor.position, total)
                val id = cursor.getLong(idColumn)
                var title = cursor.getString(titleColumn)
                var artist = cursor.getString(artistColumn)
                var album = cursor.getString(albumColumn)
                val duration = cursor.getLong(durationColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val data = cursor.getString(dataColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                var trackNumber = if (trackColumn != -1) cursor.getInt(trackColumn) else 0
                var genre = if (genreColumn != -1) cursor.getString(genreColumn) else null

                val override = overrides[id]
                var coverUrl: String? = null
                var isFavorite = false

                if (override != null) {
                    override.title?.let { if (it.isNotBlank()) title = it }
                    override.artist?.let { if (it.isNotBlank()) artist = it }
                    override.album?.let { if (it.isNotBlank()) album = it }
                    override.genre?.let { if (it.isNotBlank()) genre = it }
                    coverUrl = override.coverUri
                    isFavorite = override.isFavorite
                }

                title = CharsetUtils.sanitizeText(title)
                artist = CharsetUtils.sanitizeText(artist)
                album = CharsetUtils.sanitizeText(album)

                val folderName = data.substringBeforeLast("/").substringAfterLast("/")
                val extension = data.substringAfterLast(".").lowercase()
                val isHiFiFile = extension == "flac" || extension == "wav" || extension == "alac" || extension == "dsf" || extension == "dff"
                val isHiFi = settingsManager.enableHiFi && isHiFiFile
                val format = when (extension) {
                    "mp3" -> "MP3"
                    "flac" -> "FLAC"
                    "wav" -> "WAV"
                    "aac", "m4a" -> "AAC"
                    "ogg" -> "OGG"
                    "opus" -> "OPUS"
                    "wma" -> "WMA"
                    "alac" -> "ALAC"
                    "dsf", "dff" -> "DSD"
                    else -> extension.uppercase()
                }
                val file = File(data)
                
                val mediaStoreBitrate = if (bitrateColumn != -1) {
                    val value = cursor.getInt(bitrateColumn)
                    if (value > 0) value else null
                } else null

                val calculatedBitrate = if (duration > 0 && file.exists()) {
                    ((file.length() * 8) / (duration / 1000f)).toInt()
                } else null

                val bitrate = mediaStoreBitrate ?: calculatedBitrate

                var sampleRate: Int? = null
                var bitDepth: Int? = null
                var isHiRes = false
                if (isHiFiFile && file.exists()) {
                    // Read from the file only when it is new or has changed.
                    val info = hiFiInfo.get(file)
                    if (!info.failed) {
                        sampleRate = info.sampleRate
                        bitDepth = info.bitDepth
                        val rate = info.sampleRate ?: 0
                        val depth = info.bitDepth ?: 0
                        if ((rate >= 48000 && depth >= 24) || rate >= 88200 || extension == "dsf" || extension == "dff") {
                            isHiRes = true
                        }

                        // Extract tags for formats where Android MediaStore does not parse metadata (e.g. WAV, DSF, DFF)
                        // or where MediaStore tags were missing/fallback unknown values
                        if (override == null) {
                            info.title?.let { title = CharsetUtils.sanitizeText(it) }
                            info.artist?.let {
                                if (artist.isBlank() || artist.equals("<unknown>", ignoreCase = true) || extension == "wav") {
                                    artist = CharsetUtils.sanitizeText(it)
                                }
                            }
                            info.album?.let {
                                if (album.isBlank() || album.equals("<unknown>", ignoreCase = true) || extension == "wav") {
                                    album = CharsetUtils.sanitizeText(it)
                                }
                            }
                            info.genre?.let {
                                if (genre.isNullOrBlank() || extension == "wav") {
                                    genre = CharsetUtils.sanitizeText(it)
                                }
                            }
                            info.track?.let {
                                if (trackNumber == 0 || extension == "wav") {
                                    trackNumber = it.substringBefore("/").toIntOrNull() ?: trackNumber
                                }
                            }
                        }
                    } else if (bitrate != null && bitrate >= 2304000) {
                        isHiRes = true
                    }
                }

                val contentUri: Uri = ContentUris.withAppendedId(collection, id)

                // Use the song's specific contentUri as albumArtUri so AudioThumbnailFetcher extracts
                // the per-song embedded picture rather than leaking shared MediaStore albumart (Fixes Issue #106)
                val albumArtUri = contentUri

                songList.add(
                    Song(
                        id, albumId, title, artist, album, duration, contentUri, data,
                        dateAdded, albumArtUri, genre, folderName, isHiFi, coverUrl, isFavorite, null,
                        format, bitrate, trackNumber, isHiRes, sampleRate, bitDepth
                    )
                )
            }
        }
        hiFiInfo.save()
        onProgress(songList.size, songList.size)
        saveToCache(songList)
        songList
    }

    /**
     * Rescans the picked music folder, and only it: every audio file in it, plus
     * the known songs in it whose file has gone (scanning a missing path is what
     * removes it from MediaStore). Without a picked folder nothing is scanned.
     *
     * [onProgress] is called with the phase and how far it has got (while listing,
     * total is unknown and done is the files found so far). It may be called from
     * any thread.
     */
    suspend fun refreshLibrary(onProgress: (phase: ScanPhase, done: Int, total: Int) -> Unit = { _, _, _ -> }): List<Song> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()

        val treeUri = settingsManager.musicFolderUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val rootDocId = treeUri?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        val rootPath = rootDocId?.let { M3uPlaylistParser.treeDocIdToPaths(it)?.second }
        val update = { done: Int, total: Int -> onProgress(ScanPhase.UPDATING, done, total) }
        if (treeUri == null || rootDocId == null || rootPath == null) return@withContext syncSongs(update)

        onProgress(ScanPhase.LISTING, 0, 0)
        val found = listAudioFiles(treeUri, rootDocId, rootPath) { onProgress(ScanPhase.LISTING, it, 0) }
        val foundSet = found.toHashSet()
        val gone = getCachedSongs()
            .map { it.path }
            .filter { it.startsWith("$rootPath/") && it !in foundSet && !File(it).exists() }
        val paths = (found + gone).toTypedArray()

        if (paths.isNotEmpty()) {
            val scanned = AtomicInteger(0)
            onProgress(ScanPhase.SCANNING, 0, paths.size)
            MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
                onProgress(ScanPhase.SCANNING, scanned.incrementAndGet(), paths.size)
            }
            // The scanner answers once per path. Wait for all of them, unless it
            // goes quiet for a long while (a file it chokes on, say).
            var last = -1
            var quietSince = System.currentTimeMillis()
            while (scanned.get() < paths.size) {
                val now = scanned.get()
                if (now != last) {
                    last = now
                    quietSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - quietSince > 30_000) {
                    break
                }
                delay(250)
            }
        }

        syncSongs(update)
    }

    private val scanExtensions = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "alac", "dsf", "dff")

    // The audio files under a picked folder, as paths. Hidden entries and folders
    // with a .nomedia file are skipped, as MediaStore skips them. [onFound] gets
    // the running count.
    //
    // From Android 11 the file system can be listed directly, which is far faster
    // than the folder's document tree (one provider query per folder, slow on a
    // big library on a card). Android 10 does not allow that, so it, and any folder
    // the file system will not list, goes through the document tree, which the app
    // has permission for on every version.
    private fun listAudioFiles(treeUri: Uri, rootDocId: String, rootPath: String, onFound: (Int) -> Unit): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listAudioFilesDirect(File(rootPath), onFound)?.let { return it }
        }
        return listAudioFilesTree(treeUri, rootDocId, rootPath, onFound)
    }

    private fun listAudioFilesDirect(root: File, onFound: (Int) -> Unit): List<String>? {
        if (root.listFiles() == null) return null
        val out = mutableListOf<String>()
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val children = pending.removeFirst().listFiles() ?: continue
            if (children.any { it.name == ".nomedia" }) continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    pending.addLast(child)
                } else if (child.extension.lowercase() in scanExtensions) {
                    out.add(child.absolutePath)
                }
            }
            onFound(out.size)
        }
        return out
    }

    private fun listAudioFilesTree(treeUri: Uri, rootDocId: String, rootPath: String, onFound: (Int) -> Unit): List<String> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val out = mutableListOf<String>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending.addLast(rootDocId to rootPath)
        while (pending.isNotEmpty()) {
            val (docId, path) = pending.removeFirst()
            val files = mutableListOf<String>()
            val dirs = mutableListOf<Pair<String, String>>()
            var noMedia = false
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (name == ".nomedia") noMedia = true
                        if (name.startsWith(".")) continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            dirs.add(id to "$path/$name")
                        } else if (name.substringAfterLast('.', "").lowercase() in scanExtensions) {
                            files.add("$path/$name")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (noMedia) continue
            out.addAll(files)
            dirs.forEach { pending.addLast(it) }
            onFound(out.size)
        }
        return out
    }
}