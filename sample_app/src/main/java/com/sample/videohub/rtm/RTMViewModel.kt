package com.sample.videohub.rtm

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.videohub.utils.StateFlowable
import com.sample.videohub.video.StreamItem
import dev.videohub.VideoHubSDK
import dev.videohub.android.room.track.Track
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
import okhttp3.internal.userAgent

class RTMViewModel: ViewModel() {

    val sdk:VideoHub by lazy { VideoHubSDK.videoHub }

    lateinit var session: RoomSession


    val userIdsRTC = mutableSetOf<String>()
    val poll = mutableSetOf<String>()

    val disconnectListener = StateFlowable<Boolean>()

    suspend fun join(room: String){
        Log.e("tag", "connecting ")
        session = sdk.join(roomName = room, audio = true, video = true)
            .bindRtm()

        RTMObject.init(session, room)

        viewModelScope.launch {
            observeEvents()
        }
    }

    suspend fun joinAsGuest(room:String){
        Log.e("tag", "connecting ")

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

    suspend fun observeEvents(){
        session.observeEvents().collect {
//            Log.i("TAG", "startRoom: ${it} >> ")

            when (it) {
                is VideoHubEvent.Connected -> {
//                            uiEnabled()
                }

                is VideoHubEvent.Disconnected -> {
                    disconnectListener.emit(true)
                }
                is VideoHubEvent.Reconnecting -> {}
                is VideoHubEvent.Reconnected -> {}
                is VideoHubEvent.ParticipantConnected -> {
                    Log.e("RTM", "---------------------------------------------")
                    Log.e("RTM", "participant ParticipantConnected")
                    val ident = it.participant.identity.toString()
                    if (!userIdsRTC.contains(ident)){
                        userIdsRTC.add(ident)
                    }
                }
                is VideoHubEvent.ParticipantDisconnected -> {
                    Log.e("RTM", "---------------------------------------------")
                    Log.e("RTM", "participant ParticipantDisconnected")


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
                        participants.flatMap { participant ->
                            buildList {
                                val hasCam =
                                    participant.getTrackPublication(Track.Source.CAMERA) != null

                                if (hasCam || participant == session.local.participant) {
                                    add(StreamItem(session, participant, Track.Source.CAMERA))
                                }

                                if (participant.getTrackPublication(Track.Source.SCREEN_SHARE) != null) {
                                    add(StreamItem(session, participant, Track.Source.SCREEN_SHARE))
                                }
                            }
                        }
                    }
                }
            }
    }


    // ---------------------------------------------------------------
    ////////////////////screen share/////////////////
    //----------------------------------------------------------------
    val screen by lazy {
        session.local.screen
    }

    suspend fun startScreenShare(data: Intent) {
        screen.start(data)
    }

    fun stopScreenShare(){
        screen.stop()
    }

    //////////////////////////////////////////////////
    //////////////////////////////////////////////////
    suspend fun deleteRoom(isHost: Boolean){
        if(isHost){
            sdk.clearRoom(session.roomName)
        }
    }

    fun disconnect(){
        if (::session.isInitialized) {
            Log.e("Group", "------ back")
//            RTMObject.
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

    //////////////////////////////////////////////////////

    fun poll() =
        RTMObject.poll()

    fun invite(userId:String){
        RTMObject.sendRichChat(
            mapOf(
                "event" to "invite",
                "to" to userId
            )
        )
    }


}
