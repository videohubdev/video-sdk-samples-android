package com.sample.videohub.pk

import android.util.Log
import com.sample.videohub.utils.remoteVideoTracksFlow
import dev.videohub.VideoHubSDK
import dev.videohub.android.renderer.SurfaceViewRenderer
import dev.videohub.android.room.track.VideoTrack
import dev.videohub.core.RoomSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

class ChannelControl(
    private val isHost: Boolean,
    private val session: RoomSession,
    private val renderer: SurfaceViewRenderer?,
    private val handler: ChannelEventHandler?
)
{

    companion object {
        const val PK_ROOM_TOPIC = "vh.sdk.video.pk"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var sessionPK: RoomSession? = null

    private var boundVideoTrack: VideoTrack? = null

    private var remoteRendererInitialized = false

    private var ignoreFirstMessage = false

    private var remoteCollectJob: Job? = null



    // =========================================================
    // TEXT MESSAGE
    // =========================================================



    // =========================================================
    // JOIN
    // =========================================================

    fun joinChannel(channel: String) {

        if (sessionPK != null) return

        scope.launch {

            runCatching {

                Log.e("PK", "connecting -> $channel")

                val pkSession = VideoHubSDK.videoHub.connect(
                    roomName = channel
                )

                sessionPK = pkSession

                if (isHost) {

                    pkSession.local.publishVideoStream(
                        withCamera = false
                    )

                    delay(300)

                    sendPkRoom()
                }

                initRendererIfNeeded(pkSession)

                observeRemoteTracks(pkSession)

            }.onFailure {

                Log.e("PK", "join error ${it.message}")

                cleanup()
            }
        }
    }

    // =========================================================
    // RENDERER
    // =========================================================

    private fun initRendererIfNeeded(
        pkSession: RoomSession
    ) {

        if (remoteRendererInitialized) return

        renderer?.let {
            pkSession.initVideoRenderer(it)
        }

        remoteRendererInitialized = true
    }

    // =========================================================
    // REMOTE TRACKS
    // =========================================================

    private fun observeRemoteTracks(
        pkSession: RoomSession
    ) {

        remoteCollectJob?.cancel()

        remoteCollectJob = scope.launch {

            pkSession.remote
                .participants
                .remoteVideoTracksFlow()
                .collectLatest { tracks ->

                    val newTrack = tracks.firstOrNull()

                    if (boundVideoTrack == newTrack) {
                        return@collectLatest
                    }

                    val oldTrack = boundVideoTrack

                    boundVideoTrack = newTrack

                    if (newTrack != null) {

                        handler?.addRenderer(
                            oldTrack,
                            newTrack
                        )

                        handler?.onReadyChannel()

                    } else {

                        handler?.removeRenderer(oldTrack)

                        handler?.onEndChannel()
                    }
                }
        }
    }

    // =========================================================
    // SEND ROOM
    // =========================================================

    suspend fun sendPkRoom(
        action: String = "add"
    ) {

        if (!ignoreFirstMessage) {
            ignoreFirstMessage = true
            return
        }

        val json = JSONObject().apply {

            put("action", action)

            put("room", session.roomName)
        }


    }

    // =========================================================
    // LEAVE
    // =========================================================

    fun leaveChannel() {

        scope.launch {

            val pkSession = sessionPK ?: return@launch

            runCatching {

                sendPkRoom("remove")

                delay(300)

                if (isHost) {
                    pkSession.local.unpublishVideoStream()
                }

                boundVideoTrack?.let {
                    handler?.removeRenderer(it)
                }

                VideoHubSDK.videoHub.leaveRoom(pkSession)

            }.onFailure {

                Log.e("PK", "leave error ${it.message}")

            }.also {

                cleanup()

                handler?.onEndChannel()
            }
        }
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    private fun cleanup() {

        remoteCollectJob?.cancel()
        remoteCollectJob = null

        boundVideoTrack = null

        sessionPK = null

        ignoreFirstMessage = false


    }

    fun release() {

        leaveChannel()

        scope.cancel()
    }
}

interface ChannelEventHandler {

    fun addRenderer(
        oldTrack: VideoTrack?,
        newTrack: VideoTrack
    )

    fun removeRenderer(
        videoTrack: VideoTrack?
    )

    fun onReadyChannel()

    fun onEndChannel()
}

