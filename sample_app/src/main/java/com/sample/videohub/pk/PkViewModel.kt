package com.sample.videohub.pk

import android.util.Log
import androidx.lifecycle.ViewModel
import com.sample.videohub.rtm.EventMessage
import com.sample.videohub.rtm.RTMObject
import dev.videohub.VideoHubSDK
import dev.videohub.android.renderer.TextureViewRenderer
import dev.videohub.core.RoomSession
import dev.videohub.core.VideoHub
import kotlinx.coroutines.delay

class PkViewModel: ViewModel() {

    val sdk:VideoHub by lazy {
        VideoHubSDK.videoHub
    }

    lateinit var session: RoomSession
    var sessionPK: RoomSession? = null
    val pkRoomTopic = "vh.sdk.video.pk"

    fun initRenderer(rendererLocal: TextureViewRenderer) {
        session.initVideoRenderer(rendererLocal)
    }
    suspend fun startRoomAsHost(room:String){
            Log.e("tag", "connecting ")
            session = sdk.join(roomName = room, audio = true, video = true)

            session.local.participant.identity?.value?.let {
//                RTMObject.sendVideoGroup(EventMessage.PK.title,room, it)
            }
    }

    fun switchCam(){
        session.local.switchCamera()
    }

    /*
    guest
     */
    suspend fun joinAsGuest(room:String){
            Log.e("tag", "connecting ")
            sdk.leaveRoom(room)
            session = sdk.connect(roomName = room)
        }


    fun initRemoteRenderer(rendererLocal: TextureViewRenderer) {
        sessionPK?.initVideoRenderer(rendererLocal)
    }

    suspend fun startPk(room:String, isHost: Boolean = true) {
         if (sessionPK != null)
             return

         Log.e("tag", "connecting ")
         sessionPK = sdk.connect(roomName = room)

         if (isHost) {
             sessionPK?.local?.publishVideoStream(withCamera = false)
             sendPkRoom()
         }
    }

    fun sendPkRoom(action:String = "add"){
        session.local.participant.identity?.value?.let {
//            RTMObject.sendVideoGroup(EventMessage.PK.title,"room", it, "")
        }
    }

    suspend fun stopPk(isHost: Boolean){
            sessionPK?.let {
                if(isHost) {
//                    sendPkRoom("remove")
                    delay(500)
                }
                it.local.unpublishVideoStream()
//                boundVideoTrackRemote?.removeRenderer(binding.rendererRemote)
//                boundVideoTrackRemote = null

                sdk.leaveRoom(it)
//                showLocalFullScreen()
            }

            sessionPK = null

    }



}
