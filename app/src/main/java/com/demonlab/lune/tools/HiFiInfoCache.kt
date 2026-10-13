package com.demonlab.lune.tools

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * What reading a hi-fi file's header and tags with jaudiotagger found, kept per
 * file so that a library sync opens only the files that are new or have changed
 * (by size and modification time). Opening every FLAC on each sync took minutes
 * on a big library on a card.
 *
 * The raw tag values are kept, not the decision to use them: that still depends
 * on what MediaStore says and on the user's overrides, which can change on their
 * own.
 */
class HiFiInfoCache(context: Context) {

    data class Entry(
        val size: Long,
        val lastModified: Long,
        // The file could not be read; it is not tried again until it changes.
        val failed: Boolean = false,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val genre: String? = null,
        val track: String? = null
    )

    private val file = File(context.filesDir, "hifi_info_cache.json")
    private val gson = Gson()
    private val entries: HashMap<String, Entry> = load()
    private val seen = HashSet<String>()
    private var changed = false

    /** The entry for [audioFile], read from the file only if there is none for its current size and date. */
    fun get(audioFile: File): Entry {
        val path = audioFile.path
        seen.add(path)
        val size = audioFile.length()
        val lastModified = audioFile.lastModified()
        entries[path]?.let { if (it.size == size && it.lastModified == lastModified) return it }

        val entry = read(audioFile, size, lastModified)
        entries[path] = entry
        changed = true
        return entry
    }

    /** Writes the cache, without the files no longer in the library. */
    fun save() {
        if (entries.keys.retainAll(seen)) changed = true
        if (!changed) return
        synchronized(lock) {
            try {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(gson.toJson(entries))
                tmp.renameTo(file)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun read(audioFile: File, size: Long, lastModified: Long): Entry = try {
        val audio = org.jaudiotagger.audio.AudioFileIO.read(audioFile)
        val header = audio.audioHeader
        val tag = audio.tag
        fun field(key: org.jaudiotagger.tag.FieldKey) =
            tag?.let { runCatching { it.getFirst(key) }.getOrNull()?.trim()?.takeIf { v -> v.isNotEmpty() } }
        Entry(
            size = size,
            lastModified = lastModified,
            sampleRate = header.sampleRateAsNumber,
            bitDepth = header.bitsPerSample,
            title = field(org.jaudiotagger.tag.FieldKey.TITLE),
            artist = field(org.jaudiotagger.tag.FieldKey.ARTIST) ?: field(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST),
            album = field(org.jaudiotagger.tag.FieldKey.ALBUM),
            genre = field(org.jaudiotagger.tag.FieldKey.GENRE),
            track = field(org.jaudiotagger.tag.FieldKey.TRACK)
        )
    } catch (_: Exception) {
        Entry(size = size, lastModified = lastModified, failed = true)
    }

    private fun load(): HashMap<String, Entry> = synchronized(lock) {
        try {
            if (!file.exists()) return HashMap()
            val type = object : TypeToken<HashMap<String, Entry>>() {}.type
            gson.fromJson<HashMap<String, Entry>>(file.readText(), type) ?: HashMap()
        } catch (e: Exception) {
            e.printStackTrace()
            HashMap()
        }
    }

    private companion object {
        val lock = Any()
    }
}
