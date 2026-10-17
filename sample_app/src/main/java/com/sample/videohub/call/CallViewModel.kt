package com.sample.videohub.call

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.videohub.rtm.RTMObject
import dev.videohub.VideoHubSDK
import dev.videohub.core.RoomSession
import dev.videohub.core.VideoHubEvent
import dev.videohub.core.call.Call
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CallViewModel: ViewModel() {

    val mutalbleCallId = MutableLiveData<String>()
    val sdk = VideoHubSDK.videoHub
    var session : RoomSession? = null

    val call : Call by lazy {
        sdk.call
    }

    private val _onCallEnd = MutableStateFlow<Boolean>(false)
    val onCallEnd = _onCallEnd.asStateFlow()


    suspend fun startCall(callerId:String, calleeId: String) {
        session = call.start(callerId, calleeId, true, true){ callerId, roomId ->
            Log.e("Call","callID: $callerId room: $roomId")
            mutalbleCallId.value = callerId
        }
           ?.bindRtm()

        session?.let {
            RTMObject.init(it, it.roomName)
        }

        viewModelScope.launch {
            observeEvents()
        }
    }


    suspend fun joinCall(callId:String) {
        session =  call.join(callId, true, true)
            ?.bindRtm()

        session?.let {
            RTMObject.init(it, it.roomName)
        }

        viewModelScope.launch {
            observeEvents()
        }
    }

    suspend fun endCall(){
        session?.let { sdk.leaveRoom(it) }
        call.end()
    }

    fun testCallObject(){
        Log.e("Call", "callObj: ${call}")
    }

    fun invite(targetUserId: String,
               roomId: String,
               callerName: String): Map<String, String>? {
       return RTMObject.rtm?.call?.invite(targetUserId, roomId, callerName)
    }

    fun accept(
        roomId: String,
        callerId: String,
        userId: String
    ): Map<String, String>? {
        return RTMObject.rtm?.call?.accept(roomId, callerId, userId)
    }

    fun reject(
        roomId: String,
        callerId: String,
        userId: String
    ): Map<String, String>? {
        return RTMObject.rtm?.call?.reject(roomId, callerId, userId)
    }

    suspend fun observeEvents(){
        session?.observeEvents()?.collect {
            when (it) {
                is VideoHubEvent.Connected -> {
//                            uiEnabled()
                }

                is VideoHubEvent.Disconnected -> {
                    Log.e("Call", "Disconnected")
                    _onCallEnd.value = true
                }
                is VideoHubEvent.Reconnecting -> {}
                is VideoHubEvent.Reconnected -> {}
                is VideoHubEvent.ParticipantConnected -> {}
                is VideoHubEvent.ParticipantDisconnected -> {}
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

    fun resume(){
        session?.resume()
    }

    fun pause(){
        session?.pause()
    }

}
