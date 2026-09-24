package com.music.rizumu.data

import com.music.rizumu.data.model.Song
import com.music.rizumu.data.sources.ServerLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes star writes per track, for every surface that can make one.
 *
 * A tap flips [ServerLikeState] optimistically and asks this for the write; a
 * second tap while the first request is still on the wire does not cancel it
 * and does not start a second one beside it. Cancelling a coroutine does not
 * stop a blocking OkHttp call already in flight, so two overlapping requests
 * can reach the server in either order and leave it disagreeing with the
 * screen. Instead the writes for one track queue on a mutex, and the write
 * that runs reads the *current* desired state under that mutex — so a rapid
 * star/unstar sends the state the user last asked for, after the first write
 * has finished, and the server ends where the UI is.
 *
 * The queue is shared rather than owned by the ViewModel because the
 * notification's heart writes the same state, and the two must not race each
 * other either.
 */
object ServerStarQueue {

    private const val TAG = "Rizumu"

    /** Its own scope: the tap's caller may be gone by the time the queue runs. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One lock per track id, held for as long as the track is being toggled. */
    private val locks = mutableMapOf<String, Mutex>()

    /**
     * Writes the track's latest desired star state to [library].
     *
     * [song] is the row the tap was made on, so a failed write can roll the
     * optimistic state back with enough metadata for the lists that show it.
     * The returned job is for tests to await; callers do not need it.
     */
    fun request(videoId: String, song: Song, library: ServerLibrary, songId: String): Job =
        scope.launch {
            val lock = synchronized(locks) { locks.getOrPut(videoId) { Mutex() } }
            lock.withLock {
                val desired = ServerLikeState.isStarred(videoId)
                try {
                    library.setSongStarred(songId, desired)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    TrackLog.w(TAG, "server star failed: ${failure.message}", about = videoId)
                    // Rolled back only while this is still the user's latest
                    // intent: a tap that arrived during the write has already
                    // set the state that must survive.
                    if (ServerLikeState.isStarred(videoId) == desired) {
                        ServerLikeState.set(videoId, !desired, song)
                    }
                }
            }
        }
}
