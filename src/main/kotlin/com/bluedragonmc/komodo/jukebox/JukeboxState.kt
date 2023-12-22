package com.bluedragonmc.komodo.jukebox

import com.bluedragonmc.jukebox.Song
import com.bluedragonmc.jukebox.event.SongEndEvent
import com.bluedragonmc.jukebox.event.SongStartEvent
import com.github.benmanes.caffeine.cache.Caffeine
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.proxy.Player
import java.time.Duration
import kotlin.io.path.Path

data class PlayerSongState(
    val isPlaying: Boolean,
    val queue: List<SongState>,
)

data class SongState(
    val songName: String,
    val fileName: String,
    val timeInTicks: Int,
    val lengthInTicks: Int,
    val tags: List<String>,
)

object JukeboxState {

    private val songStates = mutableMapOf<Player, PlayerSongState>()

    private val defaultSongState = PlayerSongState(false, emptyList())

    internal fun getSongState(player: Player) = songStates[player] ?: defaultSongState

    private fun updateQueue(player: Player, updater: (MutableList<SongState>) -> List<SongState>): List<SongState> {
        val oldState = getSongState(player)
        val queue = updater(oldState.queue.toMutableList())
        val newState = oldState.copy(queue = queue)
        songStates[player] = newState

        if (oldState.isPlaying) {
            if (newState.queue.isEmpty()) {
                // All songs were removed from the queue; stop the music
                Song.stop(player)
            } else if (oldState.queue.isEmpty() || newState.queue.first() != oldState.queue.first()) {
                // The currently-playing song was changed; play the new one
                val entry = newState.queue.first()
                val song = getSong(entry.fileName)
                Song.play(song, player, entry.timeInTicks)
            }
        }

        return newState.queue
    }

    private fun addToQueue(player: Player, index: Int, state: SongState) {
        val current = getSongState(player)
        val newQueue = current.queue.toMutableList()
        val clampedIndex = index.coerceIn(0..newQueue.size)

        newQueue.add(clampedIndex, state)

        if (clampedIndex == 0) {
            // Stop the current song and record its current time
            val currentTime = Song.getCurrentSong(player)?.currentTimeInTicks
            if (currentTime != null) {
                newQueue[1] = newQueue[1].copy(timeInTicks = currentTime)
            }
            Song.stop(player)
            // Play the new song
            val song = getSong(state.fileName)
            Song.play(song, player, state.timeInTicks)
        }

        updateQueue(player) { newQueue }
    }

    fun addToQueue(
        player: Player,
        fileName: String,
        startTime: Int,
        queuePos: Int,
        tags: List<String>,
    ) {
        val current = getSongState(player)
        val newIndex = if (queuePos == -1) current.queue.size else queuePos
        val song = getSong(fileName)

        addToQueue(player, newIndex, SongState(song.songName, fileName, startTime, song.durationInTicks, tags))
    }

    fun removeByName(player: Player, name: String) = updateQueue(player) { queue ->
        queue.filter { item -> item.fileName != name }
    }

    fun removeByTags(player: Player, matchTags: List<String>) = updateQueue(player) { queue ->
        queue.filter { item -> item.tags.none { itemTag -> matchTags.contains(itemTag) } }
    }

    fun clearQueue(player: Player) = updateQueue(player) { emptyList() }

    private val songCache = Caffeine
        .newBuilder()
        .expireAfterAccess(Duration.ofMinutes(10))
        .build<String, Song>()

    private fun getSong(fileName: String): Song = songCache.get(fileName) { _ ->
        Song.loadRelative(Path(fileName))
    }

    @Subscribe
    fun onSongEnd(event: SongEndEvent) {
        val player = event.player
        val state = getSongState(player)

        if (event.song.fileName != state.queue.firstOrNull()?.fileName) {
            // A song *did* end, but it wasn't the first in the queue. There is no need to play the next song in this case.
            return
        }

        val newQueue = updateQueue(player) { queue ->
            if (queue.isNotEmpty()) {
                queue.removeFirst()
            }
            return@updateQueue queue
        }

        if (newQueue.isNotEmpty() && state.isPlaying) {
            // Play the next song
            val nextSong = newQueue.first()
            val song = getSong(nextSong.fileName)
            Song.play(song, player, nextSong.timeInTicks)
        }
    }

    @Subscribe
    fun onSongStart(event: SongStartEvent) {
        // If a song is started that isn't part of the queue (for example, from another plugin),
        // this method places it in the front of the queue to keep our state in sync.

        val player = event.player
        val state = getSongState(player)

        val firstItem = state.queue.firstOrNull()

        if (firstItem == null || firstItem.songName != event.song.songName) {
            updateQueue(player) { queue ->
                queue.add(
                    0,
                    SongState(
                        event.song.songName,
                        event.song.fileName,
                        event.startTimeInTicks,
                        event.song.durationInTicks,
                        emptyList()
                    )
                )
                return@updateQueue queue
            }
            songStates[player] = songStates[player]!!.copy(isPlaying = true)
        }
    }
}
