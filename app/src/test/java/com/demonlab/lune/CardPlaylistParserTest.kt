package com.demonlab.lune

import com.demonlab.lune.tools.playlist.M3uPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardPlaylistParserTest {

    private val root = "/storage/ABCD-1234"
    private val dir = "/storage/ABCD-1234/Playlists"

    @Test
    fun treeDocIds() {
        assertEquals("/storage/emulated/0" to "/storage/emulated/0/Playlists", M3uPlaylistParser.treeDocIdToPaths("primary:Playlists"))
        assertEquals(root to dir, M3uPlaylistParser.treeDocIdToPaths("ABCD-1234:Playlists"))
        assertEquals(root to root, M3uPlaylistParser.treeDocIdToPaths("ABCD-1234:"))
        assertNull(M3uPlaylistParser.treeDocIdToPaths("acc=1;doc=encoded=xyz"))
    }

    @Test
    fun entriesSkipDirectivesAndBom() {
        val text = "\uFEFF#EXTM3U\n#EXTINF:123,Artist - Title\n  ../Music/a.mp3  \n\nb.flac\r\n"
        assertEquals(listOf("../Music/a.mp3", "b.flac"), M3uPlaylistParser.entries(text))
    }

    @Test
    fun resolvesRelativeAndAbsolute() {
        assertEquals("$root/Music/a.mp3", M3uPlaylistParser.resolve("../Music/a.mp3", dir, root))
        assertEquals("$dir/b.flac", M3uPlaylistParser.resolve("./b.flac", dir, root))
        assertEquals("$root/Music/a.mp3", M3uPlaylistParser.resolve("/Music/a.mp3", dir, root))
        assertEquals("/storage/XYZ/Music/a.mp3", M3uPlaylistParser.resolve("/storage/XYZ/Music/a.mp3", dir, root))
        assertEquals("/storage/emulated/0/Music/a.mp3", M3uPlaylistParser.resolve("/sdcard/Music/a.mp3", dir, root))
        assertEquals("$root/Music/Artist/a.mp3", M3uPlaylistParser.resolve("E:\\Music\\Artist\\a.mp3", dir, root))
        assertEquals("$root/Music/Artist/a.mp3", M3uPlaylistParser.resolve("..\\Music\\Artist\\a.mp3", dir, root))
        assertNull(M3uPlaylistParser.resolve("../../../../../x.mp3", dir, root))
        assertNull(M3uPlaylistParser.resolve("http://radio.example/stream", dir, root))
        assertNull(M3uPlaylistParser.resolve("a.mp3", null, null))
    }

    @Test
    fun resolvesFileUrls() {
        assertEquals(
            "$root/Music/A B+C ñ.mp3",
            M3uPlaylistParser.resolve("file:///storage/ABCD-1234/Music/A%20B+C%20%C3%B1.mp3", dir, root)
        )
        assertEquals("$root/Music/a.mp3", M3uPlaylistParser.resolve("file://localhost/Music/a.mp3", dir, root))
    }

    @Test
    fun decodesUtf8AndWindows1252() {
        val utf8 = "Canción.mp3".toByteArray(Charsets.UTF_8)
        assertEquals("Canción.mp3", M3uPlaylistParser.decode(utf8))
        assertEquals("Canción.mp3", M3uPlaylistParser.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8))
        val ansi = "Canción.mp3".toByteArray(charset("windows-1252"))
        assertEquals("Canción.mp3", M3uPlaylistParser.decode(ansi))
    }

    @Test
    fun matchesExactlyIgnoringCaseThenBySuffix() {
        val index = M3uPlaylistParser.PathIndex(
            listOf(
                "$root/Music/Artist/Album/01 Song.flac" to 1L,
                "$root/Music/Other/Album/01 Song.flac" to 2L,
                "/storage/emulated/0/Music/Solo/track.mp3" to 3L,
            )
        )
        assertEquals(1L, index.find("$root/music/artist/album/01 song.FLAC", "x"))
        // Another drive, same layout: the last three segments name one track.
        assertEquals(1L, index.find("/storage/OTHER/Music/Artist/Album/01 Song.flac", "x"))
        // "Album/01 Song.flac" alone is two tracks: no guess.
        assertNull(index.find("/storage/OTHER/X/Album/01 Song.flac", "x"))
        // An entry that could not be resolved is still matched by its tail.
        assertEquals(3L, index.find(null, "Solo\\track.mp3"))
        assertNull(index.find("$root/Music/missing.mp3", "missing.mp3"))
    }
}
