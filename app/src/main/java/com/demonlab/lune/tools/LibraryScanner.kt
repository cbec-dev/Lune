package com.demonlab.lune.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.demonlab.lune.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Where a rescan has got to. */
enum class ScanPhase {
    // Looking for the audio files in the music folder.
    LISTING,
    // Android's media scanner reading them.
    SCANNING,
    // Lune reading the library back from MediaStore.
    UPDATING
}

/**
 * The library rescan, for the whole app rather than for the screen that started
 * it: it keeps going when Settings is closed, a screen opened later sees how far
 * it has got, and only one runs at a time.
 */
object LibraryScanner {

    data class State(
        val running: Boolean = false,
        val phase: ScanPhase = ScanPhase.LISTING,
        // How far the phase has got. While listing, total is unknown (0) and done
        // is the files found so far.
        val done: Int = 0,
        val total: Int = 0
    )

    var state by mutableStateOf(State())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var current: Deferred<List<Song>>? = null

    // Whether anyone who asked for the running scan wants to hear when it ends.
    @Volatile
    private var announce = false

    /**
     * Starts a rescan, or joins the one already running, and returns it. With
     * [announceResult], the song count is shown in a toast when it ends, wherever
     * the user is by then.
     */
    @Synchronized
    fun rescan(context: Context, announceResult: Boolean = false): Deferred<List<Song>> {
        if (announceResult) announce = true
        current?.takeIf { it.isActive }?.let { return it }

        val appContext = context.applicationContext
        state = State(running = true)
        return scope.async {
            val songs = try {
                MusicProvider(appContext).refreshLibrary { phase, done, total ->
                    state = State(running = true, phase = phase, done = done, total = total)
                }
            } finally {
                state = State()
            }
            if (announce) {
                announce = false
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.rescan_library_success, songs.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            songs
        }.also { current = it }
    }
}
