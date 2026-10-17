package com.sample.videohub.rtm

import android.util.Log
import dev.videohub.VideoHubSDK
import dev.videohub.core.RoomSession
import dev.videohub.core.rtm.RTM
import dev.videohub.domain.models.AttachmentObject


object RTMObject {



    val myId:String? by lazy {
        rtm?.identity
    }
    var roomId:String = ""
    var rtm: RTM? = null

    var raised = false

    fun raiseHand(){
        raised = !raised
        rtm?.chat?.raiseHand(roomId, raised)
    }

    fun sendChat(txt:String){
        rtm?.chat?.send(roomId, txt)
    }

    fun sendRichChat(map: Map<String, String>){
        rtm?.chat?.sendRichMessage(roomId, map)
    }

    fun deleteMessage(messageId: String){
        rtm?.chat?.deleteMessage(roomId, messageId)
    }

    fun typingStart(){
        rtm?.chat?.typingStart(roomId)
    }

    fun typingStop(){
        rtm?.chat?.typingStop(roomId)
    }

    fun addReaction(messageId: String, reaction: String){
        rtm?.chat?.reactionAdd(roomId, messageId, reaction)
    }
    fun attachment(fileId:         String,
                   name:           String,
                   size:           Long,
                   mimeType:       String,
                   url:            String? = null,
                   thumbnailUrl:   String? = null) =

        rtm?.attachment?.send(
            listOf(AttachmentObject(fileId, name, size, mimeType, url, thumbnailUrl))
        )


    fun poll() =
        rtm?.userAction?.poll(roomId, myId ?: "", "")


    suspend fun init(session: RoomSession, room: String) {
        RTMObject.roomId = room
        RTMObject.rtm =  session.rtm

    }

    fun dismiss(){
//        myId = ""
        roomId = ""
        rtm?.disconnect()
        rtm = null
        raised = false
    }

    suspend fun testJoin(){
        VideoHubSDK.videoHub.join("").bindRtm()
    }
}
