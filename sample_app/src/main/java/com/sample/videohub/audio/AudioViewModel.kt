package com.sample.videohub.audio

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.videohub.rtm.RTMObject
import com.sample.videohub.utils.StateFlowable
import com.twilio.audioswitch.AudioDevice
import dev.videohub.VideoHubSDK
import dev.videohub.core.RoomSession
import dev.videohub.core.VideoHub
import dev.videohub.core.VideoHubEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AudioViewModel : ViewModel() {

    val sdk: VideoHub by lazy { VideoHubSDK.videoHub }

    lateinit var session: RoomSession

    // Only the host sees kick/mute controls on remote participants — see
    // participantsObserver()'s AudioItem(isHost = ...) wiring.
    var isHost: Boolean = false
        private set

    val disconnectListener = StateFlowable<Boolean>()

    suspend fun join(room: String) {
        Log.e("tag", "connecting ")
        isHost = true
        session = sdk.join(roomName = room, audio = true, video = false)
            .bindRtm()

        RTMObject.init(session, room)

        viewModelScope.launch {
            observeEvents()
        }
    }

    suspend fun joinAsGuest(room: String) {
        Log.e("tag", "connecting ")
        isHost = false

        sdk.leaveRoom(room)
        session = sdk.connect(roomName = room)
            .bindRtm()

        // Audio-only room: publish the mic, never the camera.
        session.local.publishAudioStream()

        RTMObject.init(session, room)

        viewModelScope.launch {
            observeEvents()
        }
    }

    // One row per participant — unlike video, mic on/off never adds/removes
    // a row, so this only needs to react to people joining/leaving. Each
    // row's own mic/speaking state is collected inside AudioItem.bind().
    fun participantsObserver(): Flow<List<AudioItem>> {
        return session.remote.participants
            .map { remoteMap ->
                val all = listOf(session.local.participant) + remoteMap.values.toList()
                all.map { participant -> AudioItem(session, participant, isHost) }
            }
    }

    suspend fun observeEvents() {
        session.observeEvents().collect {
            Log.e("AudioVM", "event $it")
            when (it) {
                is VideoHubEvent.Disconnected -> {
                    disconnectListener.emit(true)
                }
                else -> {
                    Log.e("AudioVM", "event $it")
                }
            }
        }
    }

    suspend fun deleteRoom(isHost: Boolean) {
        if (isHost) {
            sdk.clearRoom(session.roomName)
        }
    }

    fun disconnect() {
        if (::session.isInitialized) {
            sdk.leaveRoom(session)
        }
    }

    fun resume() {
        if (::session.isInitialized) {
            session.resume()
        }
    }

    fun pause() {
        if (::session.isInitialized) {
            session.pause()
        }
    }

    // Android routes mic input and call output through the same physical
    // device together, so one list/selection covers both "microphone
    // switching" and "speaker selection" — see Local's audio-device docs.
    fun availableAudioDevices(): List<AudioDevice> =
        if (::session.isInitialized) session.local.availableAudioDevices else emptyList()

    fun selectAudioDevice(device: AudioDevice) {
        if (::session.isInitialized) {
            session.local.selectAudioDevice(device)
        }
    }

    // VideoHubImpl.join()/connect() don't set withNoiseCancellation(), so
    // RoomOptions default noiseSuppression/echoCancellation to false — this
    // mirrors that starting state rather than claiming they start "on".
    private var noiseAndEchoCancellationEnabled = false

    // Restarting the mic track (see Local.setNoiseAndEchoCancellationEnabled)
    // is fire-and-forget from the UI's perspective — returns the new state
    // immediately so the toggle button can reflect it optimistically.
    suspend fun toggleNoiseAndEchoCancellation(): Boolean {
        if (!::session.isInitialized) return noiseAndEchoCancellationEnabled
        noiseAndEchoCancellationEnabled = !noiseAndEchoCancellationEnabled
        session.local.setNoiseAndEchoCancellationEnabled(noiseAndEchoCancellationEnabled)
        return noiseAndEchoCancellationEnabled
    }

}
