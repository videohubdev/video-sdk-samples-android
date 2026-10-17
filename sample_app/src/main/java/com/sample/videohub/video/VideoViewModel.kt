package com.sample.videohub.video

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.videohub.rtm.RTMObject
import com.sample.videohub.utils.StateFlowable
import dev.videohub.VideoHubSDK
import dev.videohub.android.room.participant.Participant
import dev.videohub.android.room.track.Track
import dev.videohub.android.room.track.VideoCaptureParameter
import dev.videohub.android.util.flow
import dev.videohub.core.RoomSession
import dev.videohub.core.VideoHub
import dev.videohub.core.VideoHubEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VideoViewModel: ViewModel() {

    val sdk:VideoHub by lazy { VideoHubSDK.videoHub }

    lateinit var session: RoomSession

    // Only the host sees kick/mute controls on remote participants — see
    // participantsObserver()'s StreamItem(isHost = ...) wiring.
    var isHost: Boolean = false
        private set

    val disconnectListener = StateFlowable<Boolean>()


    suspend fun join(room: String){
        Log.e("tag", "connecting ")
        isHost = true
        session = sdk.join(roomName = room, audio = true, video = true)
            .bindRtm()


        RTMObject.init(session, room)

        viewModelScope.launch {
            observeEvents()
        }
    }

    suspend fun joinAsGuest(room:String){
        Log.e("tag", "connecting ")
        isHost = false

        sdk.leaveRoom(room)
        session = sdk.connect(roomName = room)
            .bindRtm()

        session.local.publishVideoStream()
        session.local.publishAudioStream()

        RTMObject.init(session, room)

        viewModelScope.launch {
            observeEvents()
        }

    }

    fun localParticipant():StreamItem{
        val participant = session.local.participant
        return StreamItem(session, participant, Track.Source.CAMERA, isHost)
    }

    fun remoteTracks(): Flow<List<StreamItem>> {
        return session.remote.participants.flatMapLatest { remoteMap ->

            val participants = remoteMap.values.toList()

            if (participants.isEmpty()) {
                flowOf(emptyList())
            } else {
                val participantFlows = participants.map { participant ->
                    participant::videoTrackPublications.flow.map { participant }
                }

                combine(participantFlows) { updatedParticipants ->
                    updatedParticipants.flatMap { participant ->
                        buildList {
                            if (participant.getTrackPublication(Track.Source.CAMERA) != null) {
                                add(StreamItem(session, participant, Track.Source.CAMERA, isHost))
                            }

                            if (participant.getTrackPublication(Track.Source.SCREEN_SHARE) != null) {
                                add(StreamItem(session, participant, Track.Source.SCREEN_SHARE, isHost))
                            }
                        }
                    }
                }
            }
        }
    }

    fun participantsObserver(): Flow<List<StreamItem>> {
        return session.remote.participants
            .flatMapLatest { remoteMap ->

                val all = listOf(session.local.participant) + remoteMap.values.toList()

                val perParticipantFlows = all.map { participant ->
                    participant::videoTrackPublications.flow
                        .map { participant }
                }

                if (perParticipantFlows.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(perParticipantFlows) { participants ->
                        participants
                            .distinctBy { it.identity }
                            // Kicking a participant is a REST call + an RTM
                            // notification, neither of which removes them
                            // from session.remote.participants by itself —
                            // that only happens once the server actually
                            // broadcasts a disconnect over the signaling
                            // socket. If that broadcast is delayed (or the
                            // participant's own client is slow to react and
                            // actually leave), this map still holds a
                            // DISCONNECTED-state entry with no track
                            // publications, which otherwise rendered as a
                            // blank "Unknown" tile.
                            .filter { it.state != Participant.State.DISCONNECTED }
                            .flatMap { participant ->
                            buildList {
                                val hasCam =
                                    participant.getTrackPublication(Track.Source.CAMERA) != null

                                if (hasCam || participant == session.local.participant) {
                                    add(StreamItem(session, participant, Track.Source.CAMERA, isHost))
                                }

                                if (participant.getTrackPublication(Track.Source.SCREEN_SHARE) != null) {
                                    add(StreamItem(session, participant, Track.Source.SCREEN_SHARE, isHost))
                                }
                            }
                        }
                    }
                }
            }
    }


    suspend fun observeEvents(){
        session.observeEvents().collect {
            Log.i("TAG", "startRoom: ${it} >> ")

            when (it) {
                is VideoHubEvent.Connected -> {
//                            uiEnabled()
                }

                is VideoHubEvent.Disconnected -> {
                    disconnectListener.emit(true)
                }
                is VideoHubEvent.Reconnecting -> {}
                is VideoHubEvent.Reconnected -> {}
                is VideoHubEvent.ParticipantConnected -> {}
                is VideoHubEvent.ParticipantDisconnected -> {
//                    it.participant.events
                }
                is VideoHubEvent.TrackSubscribed -> {}
                is VideoHubEvent.TrackUnsubscribed -> {}
                is VideoHubEvent.TrackPublished -> {

                }

                is VideoHubEvent.TrackUnpublished -> {}
                is VideoHubEvent.DataReceived -> {}
                is VideoHubEvent.ActiveSpeakersChanged -> {}
                is VideoHubEvent.ConnectionQualityChanged -> {}
                is VideoHubEvent.Error -> {
                    Log.i("TAG", "startRoom: $it")
                }

            }

            Log.e("tag", "event $it")
        }
    }

    val screen by lazy {
        session.local.screen
    }

    suspend fun startScreenShare(data: Intent) {
        screen.start(data)
    }

    fun stopScreenShare(){
        screen.stop()
    }

    suspend fun deleteRoom(isHost: Boolean){
        if(isHost){
            sdk.clearRoom(session.roomName)
        }
    }

    fun disconnect(){
        if (::session.isInitialized) {
            Log.e("Group", "------ back")
            sdk.leaveRoom(session)
        }
    }

    fun resume(){
        if (::session.isInitialized) {
            session.resume()
        }
    }

    fun pause(){
        if (::session.isInitialized) {
            session.pause()
        }
    }

    fun changeVideoQuality(params: VideoCaptureParameter) {
        if (::session.isInitialized) {
            session.changeVideoQuality(params)
            val participant = session.local.participant
//            audienceAdapter.add(item)
        }
    }



}
