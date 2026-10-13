package com.demonlab.lune.tools.playlist

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads .m3u / .m3u8 files and works out which library file each entry means.
 * Pure Kotlin, no Android, so it can be unit tested.
 */
object M3uPlaylistParser {

    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** UTF-8 (the .m3u8 rule, and what most tools write now), else Windows-1252. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            // Old Windows tools write .m3u in the ANSI code page.
            String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }

    /** The entries of a playlist, in order: every line that is not blank or a # directive. */
    fun entries(text: String): List<String> =
        text.removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    /**
     * The folder a SAF tree document id stands for, and the root of its volume.
     * "primary:Playlists" -> ("/storage/emulated/0", "/storage/emulated/0/Playlists"),
     * "ABCD-1234:Playlists" -> ("/storage/ABCD-1234", "/storage/ABCD-1234/Playlists").
     * Null when the id names no storage volume (a cloud provider, for instance).
     */
    fun treeDocIdToPaths(docId: String): Pair<String, String>? {
        if (docId.startsWith("raw:")) {
            val dir = normalize(docId.removePrefix("raw:")) ?: return null
            return (volumeRootOf(dir) ?: return null) to dir
        }
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1)
        val root = when (volume) {
            "primary" -> PRIMARY_ROOT
            "home" -> "$PRIMARY_ROOT/Documents"
            else -> if (volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f]{8,16}"))) "/storage/$volume" else return null
        }
        val dir = normalize(if (relative.isEmpty()) root else "$root/$relative") ?: return null
        return (if (volume == "home") PRIMARY_ROOT else root) to dir
    }

    private fun volumeRootOf(path: String): String? {
        if (path.startsWith("$PRIMARY_ROOT/") || path == PRIMARY_ROOT) return PRIMARY_ROOT
        val m = Regex("^(/storage/[^/]+)").find(path) ?: return null
        return m.groupValues[1]
    }

    /**
     * The absolute path an entry means, normalized, or null when it cannot mean a
     * local file (a URL, or a relative entry with no folder to take it from).
     *
     * - relative entries are taken from the playlist's own folder;
     * - "/storage/..." and "/mnt/..." are used as they are, "/sdcard/..." is the primary volume;
     * - any other absolute path, and Windows drive paths ("E:\Music\a.mp3"), are taken
     *   from the root of the volume the playlist is on, as a music player's card is.
     */
    fun resolve(entry: String, playlistDir: String?, volumeRoot: String?): String? {
        var e = entry.trim()
        if (e.startsWith("file://", ignoreCase = true)) {
            e = percentDecode(e.substring(7)).removePrefix("localhost")
        } else if (e.contains("://")) {
            return null
        }
        e = e.replace('\\', '/')

        if (Regex("^[A-Za-z]:/").containsMatchIn(e)) {
            e = e.substring(2)
        }

        val absolute = when {
            e.startsWith("/storage/") || e.startsWith("/mnt/") -> e
            e == "/sdcard" || e.startsWith("/sdcard/") -> PRIMARY_ROOT + e.removePrefix("/sdcard")
            e.startsWith("/") -> if (volumeRoot != null) volumeRoot + e else e
            else -> if (playlistDir != null) "$playlistDir/$e" else return null
        }
        return normalize(absolute)
    }

    /** "/a/./b/../c//d" -> "/a/c/d". Null if ".." climbs above the root. */
    fun normalize(path: String): String? {
        val out = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isEmpty()) return null else out.removeLast()
                else -> out.addLast(part)
            }
        }
        return "/" + out.joinToString("/")
    }

    // Not URLDecoder: that one also turns '+' into a space, and '+' is a legal
    // character in a file name.
    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                    continue
                }
            }
            // Everything up to the next '%' as it is, so surrogate pairs stay whole.
            val next = s.indexOf('%', i + 1).let { if (it < 0) s.length else it }
            bytes.write(s.substring(i, next).toByteArray(Charsets.UTF_8))
            i = next
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * The library, looked up by path. Case is ignored, as FAT/exFAT cards ignore it.
     * When the exact path is not there, the last three, then two, path segments are
     * tried, and taken only if one track alone ends that way: a playlist made on a
     * computer names its own drive, not the phone's.
     */
    class PathIndex(tracks: List<Pair<String, Long>>) {
        private val byPath = HashMap<String, Long>(tracks.size * 2)
        private val bySuffix3 = HashMap<String, MutableList<Long>>()
        private val bySuffix2 = HashMap<String, MutableList<Long>>()

        init {
            for ((path, id) in tracks) {
                val key = normalize(path.replace('\\', '/'))?.lowercase() ?: continue
                byPath.putIfAbsent(key, id)
                suffix(key, 3)?.let { bySuffix3.getOrPut(it) { mutableListOf() }.add(id) }
                suffix(key, 2)?.let { bySuffix2.getOrPut(it) { mutableListOf() }.add(id) }
            }
        }

        fun find(resolvedPath: String?, rawEntry: String): Long? {
            val key = resolvedPath?.lowercase()
            if (key != null) byPath[key]?.let { return it }

            val tail = key ?: rawEntry.replace('\\', '/').lowercase()
            suffix(tail, 3)?.let { s -> bySuffix3[s]?.singleOrNull()?.let { return it } }
            suffix(tail, 2)?.let { s -> bySuffix2[s]?.singleOrNull()?.let { return it } }
            return null
        }

        private fun suffix(path: String, segments: Int): String? {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.size < segments) return null
            return parts.takeLast(segments).joinToString("/")
        }
    }
}
