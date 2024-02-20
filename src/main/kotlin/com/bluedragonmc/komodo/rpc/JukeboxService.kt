package com.bluedragonmc.komodo.rpc

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.jukebox.api.SongLoader
import com.bluedragonmc.jukebox.api.SongPlayer
import com.bluedragonmc.komodo.RPCUtils.handleRPC
import com.bluedragonmc.komodo.jukebox.JukeboxState
import com.google.protobuf.Empty
import com.velocitypowered.api.proxy.ProxyServer
import java.util.*
import kotlin.jvm.optionals.getOrNull

class JukeboxService(
    private val proxyServer: ProxyServer,
    private val songPlayer: SongPlayer,
    songLoader: SongLoader
) :
    JukeboxGrpcKt.JukeboxCoroutineImplBase() {

    internal val jukeboxHandler = JukeboxState(songPlayer, songLoader)

    override suspend fun getSongInfo(request: JukeboxOuterClass.SongInfoRequest): JukeboxOuterClass.PlayerSongQueue =
        handleRPC {
            val player = proxyServer.getPlayer(UUID.fromString(request.playerUuid)).getOrNull()
                ?: return playerSongQueue { isPlaying = false }
            val state = jukeboxHandler.getSongState(player)

            return playerSongQueue {
                isPlaying = state.isPlaying
                state.queue.forEach { song ->
                    songs.add(playerSongInfo {
                        this.songName = song.songName
                        this.playerUuid = request.playerUuid
                        this.songLengthTicks = song.lengthInTicks
                        this.songProgressTicks = song.timeInTicks
                        song.tags.forEach { tag -> this.tags.add(tag) }
                    })
                }
            }
        }

    override suspend fun playSong(request: JukeboxOuterClass.PlaySongRequest): JukeboxOuterClass.PlaySongResponse =
        handleRPC {
            val player = proxyServer.getPlayer(UUID.fromString(request.playerUuid)).getOrNull()
                ?: return playSongResponse {
                    playerUuid = request.playerUuid
                    songName = request.songName
                    startedPlaying = false
                }

            jukeboxHandler.addToQueue(
                player,
                request.songName,
                request.startTimeTicks,
                request.queuePosition,
                request.tagsList
            )

            return playSongResponse {
                playerUuid = request.playerUuid
                songName = request.songName
                startedPlaying = request.queuePosition == 0
            }
        }

    override suspend fun removeSong(request: JukeboxOuterClass.SongRemoveRequest): Empty = handleRPC {
        val player = proxyServer.getPlayer(UUID.fromString(request.playerUuid)).getOrNull()
            ?: return Empty.getDefaultInstance()

        jukeboxHandler.removeByName(player, request.songName)

        return Empty.getDefaultInstance()
    }

    override suspend fun removeSongs(request: JukeboxOuterClass.BatchSongRemoveRequest): Empty = handleRPC {
        val player = proxyServer.getPlayer(UUID.fromString(request.playerUuid)).getOrNull()
            ?: return Empty.getDefaultInstance()

        jukeboxHandler.removeByTags(player, request.matchTagsList)

        return Empty.getDefaultInstance()
    }

    override suspend fun stopSong(request: JukeboxOuterClass.StopSongRequest): Empty = handleRPC {
        val player = proxyServer.getPlayer(UUID.fromString(request.playerUuid)).getOrNull()
            ?: return Empty.getDefaultInstance()

        jukeboxHandler.clearQueue(player)
        songPlayer.stop(player)

        return Empty.getDefaultInstance()
    }
}