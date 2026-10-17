package com.sample.videohub.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.videohub.rtm.RTMObject
import com.sample.videohub.utils.consoleLog
import dev.videohub.VideoHubSDK
import dev.videohub.core.rtm.EVENT
import dev.videohub.data.repository.network.AttachmentState
import dev.videohub.utils.getFileInfo
import dev.videohub.utils.isJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ChatViewModel: ViewModel() {

    // Every user currently holding their hand up, keyed by user_id, in raise
    // order. A single last-event slot can't represent multiple hands raised
    // at once — the next raise/lower would just overwrite the previous one.
    private val _raisedHands = MutableStateFlow<LinkedHashSet<String>>(LinkedHashSet())
    val raisedHands = _raisedHands.asStateFlow()

    private val _receivedChatMessage = MutableStateFlow<ChatMessageObj?>(null)
    val receivedChatMessage = _receivedChatMessage.asStateFlow()

    private val _receivedGifMessage = MutableStateFlow<GifMessageObj?>(null)
    val receivedGifMessage = _receivedGifMessage.asStateFlow()

    private val _receivedReplyMessage = MutableStateFlow<ReplyMessageObj?>(null)
    val receivedReplyMessage = _receivedReplyMessage.asStateFlow()

    // One emission per CHAT_ATTACHMENT message — a message can carry several
    // files (see JSONObject.toAttachmentGroupMessage), rendered as a single
    // bubble listing all of them rather than one bubble per file.
    private val _receivedAttachmentMessage = MutableSharedFlow<AttachmentGroupMessageObj>(extraBufferCapacity = 16)
    val receivedAttachmentMessage = _receivedAttachmentMessage.asSharedFlow()

    // Progress/result for an attachment upload in flight — see
    // AttachmentSendEvent. SharedFlow (not StateFlow): every pick is its own
    // localId, so there's no single "latest" state worth replaying.
    private val _attachmentSendEvents = MutableSharedFlow<AttachmentSendEvent>(extraBufferCapacity = 32)
    val attachmentSendEvents = _attachmentSendEvents.asSharedFlow()

    // message_id of whichever message was just deleted (by anyone) — a
    // SharedFlow since two different messages could share nothing but the
    // "delete" moment and both need to come through.
    private val _deletedMessageId = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val deletedMessageId = _deletedMessageId.asSharedFlow()

    // Presence system messages ("X joined" / "X left") to show as center items.
    // SharedFlow (not StateFlow) so two back-to-back identical messages both emit.
    private val _presenceMessage = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val presenceMessage = _presenceMessage.asSharedFlow()

    // Every user_id currently typing, mirrors raisedHands' add/remove-by-id
    // pattern so multiple people typing at once are all represented.
    private val _typingUsers = MutableStateFlow<LinkedHashSet<String>>(LinkedHashSet())
    val typingUsers = _typingUsers.asStateFlow()

    // Each reaction as it arrives — aggregation (per message_id, per emoji
    // count) happens in ChatFragment, which is the one holding the actual
    // message rows to update.
    private val _messageReaction = MutableSharedFlow<ReactionMessage>(extraBufferCapacity = 32)
    val messageReaction = _messageReaction.asSharedFlow()

    val identity: String? by lazy {
        RTMObject.rtm?.identity
    }

    fun sendChatMessage(text:String){
        RTMObject.sendChat(text)
    }

    // base64: raw image/gif bytes captured from the IME's sticker/GIF tray
    // (see ChatFragment's OnReceiveContentListener) — sent as a rich message
    // so existing (non-GIF-aware) clients simply ignore the JSON `message`.
    fun sendGifMessage(base64: String, mime: String) {
        RTMObject.sendRichChat(mapOf("type" to "gif", "data" to base64, "mime" to mime))
    }

    // Chat.deleteMessage() was already implemented at the RTM layer
    // (ChatAction/RTMUtilityImpl) but nothing in the app ever called it.
    fun deleteMessage(messageId: String) {
        RTMObject.deleteMessage(messageId)
    }

    // Chat.typingStart()/typingStop() were also already implemented at the
    // RTM layer with nothing calling them — see ChatFragment's debounced
    // TextWatcher for when these actually get called.
    fun typingStart() {
        RTMObject.typingStart()
    }

    fun typingStop() {
        RTMObject.typingStop()
    }

    // Chat.reactionAdd() was also already implemented at the RTM layer with
    // nothing calling it. reaction: an emoji or short text, e.g. "❤️" or "+1".
    fun addReaction(messageId: String, reaction: String) {
        RTMObject.addReaction(messageId, reaction)
    }

    // Replies have no native RTM field — same embedding convention as GIFs:
    // a JSON payload (see ChatModel.ReplyPayload) stringified into the
    // regular chat envelope's `message` field via sendRichMessage().
    fun sendReply(text: String, replyTarget: ReplyTarget) {
        RTMObject.sendRichChat(
            mapOf(
                "type" to "reply",
                "text" to text,
                "reply_to_id" to replyTarget.messageId,
                "reply_to_text" to replyTarget.snippet,
                "reply_to_sender" to replyTarget.senderId
            )
        )
    }

    // Uploads over HTTP first (attachments are too big for the RTM socket,
    // which only carries small JSON payloads — see MAX_GIF_BYTES in
    // ChatFragment) — once the server has the bytes, sendRTMAttachment()
    // broadcasts just the resulting metadata over RTM, which self-echoes
    // back to us the same as every other message type (see
    // receivedChatMessage) and is what actually adds the bubble to the list.
    //
    // uri/context: Attachment.upload(context, uri) streams straight from the
    // picked content:// Uri (resolving name/size/mime and enforcing the 1GB
    // cap itself via Uri.getFileInfo) — no local temp-file copy needed.
    //
    // localId: client-generated before the upload starts (ChatFragment has
    // no server-assigned file_id yet at pick time) — lets the Fragment tie
    // upload progress back to the "shadow" placeholder bubble it showed
    // immediately on pick (see AttachmentSendEvent).
    //
    // cacheDir: before broadcasting, the uploaded bytes are re-read from
    // `uri` and copied into attachmentCacheFile(cacheDir, fileId, name) —
    // the exact path AttachmentGroupMessageItem checks before deciding to
    // download — so by the time our own echo arrives, it renders from that
    // local copy instead of re-downloading the file we just sent.
    fun sendAttachment(localId: String, uri: Uri, context: Context, cacheDir: File) {
        viewModelScope.launch {
            VideoHubSDK.videoHub.getSession(RTMObject.roomId)
                ?.attachment?.upload(context, uri)
                ?.collectLatest { state ->
                    when (state) {
                        is AttachmentState.Progress -> {
                            _attachmentSendEvents.tryEmit(AttachmentSendEvent.Progress(localId, state.progress))
                        }
                        is AttachmentState.Success -> {
                            val response = state.data
                            val fileId = response.attachment_id
                            if (fileId != null) {
                                val info = uri.getFileInfo(context)
                                val name = response.file_name ?: info.name

                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val destination = attachmentCacheFile(cacheDir, fileId, name)
                                        context.contentResolver.openInputStream(uri)?.use { input ->
                                            destination.outputStream().use { output -> input.copyTo(output) }
                                        }
                                    }
                                }

                                sendRTMAttachment(
                                    fileId = fileId,
                                    name = name,
                                    size = response.file_size?.toLongOrNull() ?: info.size,
                                    mimeType = response.mime_type ?: info.mimeType,
                                    url = "",
                                    thumb = ""
                                )

                                _attachmentSendEvents.tryEmit(AttachmentSendEvent.Uploaded(localId, fileId))
                            } else {
                                _attachmentSendEvents.tryEmit(AttachmentSendEvent.Failed(localId, "Upload succeeded with no attachment id"))
                            }
                        }
                        is AttachmentState.Error -> {
                            consoleLog("RTMChat", "attachment upload failed: ${state.message}")
                            _attachmentSendEvents.tryEmit(AttachmentSendEvent.Failed(localId, state.message))
                        }
                        else -> Unit
                    }
                }
        }
    }

    // Broadcasts the attachment's metadata over RTM — same self-echo
    // convention as every other message type (see receivedChatMessage):
    // this call doesn't add anything to the chat list itself, the
    // CHAT_ATTACHMENT listener in rtmEvents() does, once the broadcast
    // round-trips back (including to the sender).
    fun sendRTMAttachment(fileId:         String,
                          name:           String,
                          size:           Long,
                          mimeType:      String,
                          url:              String,
                          thumb:              String,
                          ){
        RTMObject.attachment(fileId, name, size, mimeType, url, thumb)
    }

    // destination: caller-chosen path (e.g. context.cacheDir/<file>) — the
    // library has no Context of its own to pick one.
    fun downloadAttachment(attachmentId: String, destination: File): Flow<AttachmentState<File>> {
        consoleLog("ChatVM", "$attachmentId", "${destination.path}")
        return VideoHubSDK.videoHub.getSession(RTMObject.roomId)
            ?.attachment?.download(attachmentId, destination)
            ?: flowOf(AttachmentState.Error("Not connected to room"))
    }

    suspend fun rtmEvents(){
        RTMObject.rtm?.on(EVENT.USER_JOINED){
            Log.e("TAG", "on user join >> $it")
            it?.let {
                val data = "$it".toUserPresenceData()
                _presenceMessage.tryEmit("${data.user_id} joined")
            }
        }
        RTMObject.rtm?.on(EVENT.USER_LEFT){
            Log.e("TAG", "on user left >> $it")
            it?.let {
                val data = "$it".toUserPresenceData()
                _presenceMessage.tryEmit("${data.user_id} left")
            }
        }
        RTMObject.rtm?.on(EVENT.RAISE_HAND){
            Log.e("TAG", "message >> $it")
            val data = "$it".toRaisedHandData()
            _raisedHands.value = LinkedHashSet(_raisedHands.value).apply {
                if (data.raised) add(data.user_id) else remove(data.user_id)
            }
        }

        RTMObject.rtm?.on(EVENT.CHAT_MESSAGE){
            Log.e("rtm", "RTM: ${it}")
            it?.let {

                if (it.getString("message").isJsonObject()){
                    val envelope = "$it".toData()
                    // Check `type` before picking a parser — GifPayload and
                    // ReplyPayload have different required fields, so
                    // parsing as the wrong one throws/nulls non-null fields.
                    when (JSONObject(envelope.message).optString("type")) {
                        "gif" -> {
                            val gif = envelope.message.toGifPayload()
                            _receivedGifMessage.value = GifMessageObj(
                                sender_id = envelope.sender_id,
                                message_id = envelope.message_id,
                                room_id = envelope.room_id,
                                data = gif.data,
                                mime = gif.mime
                            )
                        }
                        "reply" -> {
                            val reply = envelope.message.toReplyPayload()
                            _receivedReplyMessage.value = ReplyMessageObj(
                                sender_id = envelope.sender_id,
                                message_id = envelope.message_id,
                                room_id = envelope.room_id,
                                text = reply.text,
                                reply_to_id = reply.reply_to_id,
                                reply_to_text = reply.reply_to_text,
                                reply_to_sender = reply.reply_to_sender
                            )
                        }
                    }
                } else {
                    val data = "$it".toData()
                    _receivedChatMessage.value = data
                }
            }
        }

        RTMObject.rtm?.on(EVENT.CHAT_MESSAGE_DELETE){
            it?.let {
                val messageId = it.optString("message_id")
                if (messageId.isNotBlank()) {
                    _deletedMessageId.tryEmit(messageId)
                }
            }
        }

        RTMObject.rtm?.on(EVENT.TYPING_START){
            it?.let {
                val data = "$it".toUserPresenceData()
                // Don't show your own typing indicator back to yourself.
                if (data.user_id != identity) {
                    _typingUsers.value = LinkedHashSet(_typingUsers.value).apply { add(data.user_id) }
                }
            }
        }
        RTMObject.rtm?.on(EVENT.TYPING_STOP){
            it?.let {
                val data = "$it".toUserPresenceData()
                _typingUsers.value = LinkedHashSet(_typingUsers.value).apply { remove(data.user_id) }
            }
        }

        RTMObject.rtm?.on(EVENT.REACTION_ADD){
            it?.let {
                _messageReaction.tryEmit("$it".toReactionData())
            }
        }

        // Receive RTM Info — see JSONObject.toAttachmentGroupMessage() for the
        // real broadcast shape (an envelope wrapping an `attachments` array).
        RTMObject.rtm?.on(EVENT.CHAT_ATTACHMENT) {
            it?.toAttachmentGroupMessage()?.let { group ->
                _receivedAttachmentMessage.tryEmit(group)
            }
        }

    }


}
