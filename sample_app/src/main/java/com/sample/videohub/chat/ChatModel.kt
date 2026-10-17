package com.sample.videohub.chat

import com.google.gson.Gson
import org.json.JSONObject
import java.io.File


data class ChatMessageObj(
    val app_id: String,
    val message: String,
    val message_id: String,
    val room_id: String,
    val sender_id: String,
    val timestamp: String,
    val myId: String,
)

fun String.toData(): ChatMessageObj{
    val data = Gson().fromJson<ChatMessageObj>(this, ChatMessageObj::class.java)
    return data
}

data class HandRaiseMessage(
    val app_id: String,
    val raised: Boolean,
    val room_id: String,
    val timestamp: Long,
    val user_id: String
)

fun String.toRaisedHandData(): HandRaiseMessage{
    val data = Gson().fromJson<HandRaiseMessage>(this, HandRaiseMessage::class.java)
    return data
}

data class UserPresenceMessage(
    val user_id: String,
    val room_id: String? = null,
    val reason: String? = null
)

fun String.toUserPresenceData(): UserPresenceMessage {
    val data = Gson().fromJson<UserPresenceMessage>(this, UserPresenceMessage::class.java)
    return data
}

// Rich-message payload embedded (JSON-stringified) inside ChatMessageObj.message
// by Chat.sendRichMessage() — see RTMActivity's sendRichMessage/ChatAction wiring.
data class GifPayload(
    val type: String = "gif",
    val data: String,
    val mime: String = "image/gif"
)

fun String.toGifPayload(): GifPayload {
    val data = Gson().fromJson<GifPayload>(this, GifPayload::class.java)
    return data
}

// Envelope fields (sender_id/message_id/room_id) come from the outer
// ChatMessageObj that wraps the GifPayload — combined here for rendering.
data class GifMessageObj(
    val sender_id: String,
    val message_id: String,
    val room_id: String,
    val data: String,
    val mime: String
)

// user_id is nullable — the outgoing ChatAction.reactionAdd() payload only
// ever sends room_id/message_id/reaction (see RTMPayloads.reactionAdd), so
// whether the incoming broadcast is server-enriched with the reactor's id
// isn't guaranteed the way it is for USER_JOINED/TYPING_START.
data class ReactionMessage(
    val message_id: String,
    val reaction: String,
    val room_id: String? = null,
    val user_id: String? = null
)

fun String.toReactionData(): ReactionMessage {
    val data = Gson().fromJson<ReactionMessage>(this, ReactionMessage::class.java)
    return data
}

// Rich-message payload for a reply — same embedding convention as
// GifPayload: JSON-stringified into ChatMessageObj.message via
// Chat.sendRichMessage(), since the RTM protocol has no native "reply to"
// field. reply_to_text/reply_to_sender are captured client-side at send time
// (from the message being replied to) rather than looked up server-side.
data class ReplyPayload(
    val type: String = "reply",
    val text: String,
    val reply_to_id: String,
    val reply_to_text: String,
    val reply_to_sender: String
)

fun String.toReplyPayload(): ReplyPayload {
    val data = Gson().fromJson<ReplyPayload>(this, ReplyPayload::class.java)
    return data
}

// Envelope fields (sender_id/message_id/room_id) come from the outer
// ChatMessageObj that wraps the ReplyPayload — combined here for rendering.
data class ReplyMessageObj(
    val sender_id: String,
    val message_id: String,
    val room_id: String,
    val text: String,
    val reply_to_id: String,
    val reply_to_text: String,
    val reply_to_sender: String
)

// What ChatFragment is currently composing a reply to — captured client-side
// when "Reply" is picked from a message's long-press menu, cleared on send
// or cancel.
data class ReplyTarget(
    val messageId: String,
    val senderId: String,
    val snippet: String
)

// One file within an attachment message — see AttachmentGroupMessageObj.
data class AttachmentItem(
    val file_id: String,
    val name: String,
    val size: Long,
    val mime_type: String
)

// One CHAT_ATTACHMENT message, broadcast over RTM via Chat.attachment() (see
// RTMObject.attachment / ChatViewModel.sendRTMAttachment) after the upload(s)
// complete over HTTP — the RTM payload only carries the resulting
// attachment(s)' metadata, not their bytes. A single message can carry more
// than one file (see the `attachments` array in JSONObject
// .toAttachmentGroupMessage), so this renders as one chat bubble listing
// every attachment rather than one bubble per file (see
// AttachmentGroupMessageItem).
data class AttachmentGroupMessageObj(
    val sender_id: String,
    val message_id: String,
    val attachments: List<AttachmentItem>
)

// Single source of truth for where a given attachment's bytes live on disk,
// so the upload side (ChatViewModel, pre-populating the sender's own copy)
// and the render side (AttachmentGroupMessageItem, deciding whether to
// download) can never disagree on the path.
fun attachmentCacheFile(cacheDir: File, fileId: String, name: String): File =
    File(cacheDir, "${fileId}_$name")

// Progress/result events for an in-flight attachment upload, keyed by a
// client-generated localId assigned when the file is picked — before any
// server-assigned file_id exists — so ChatFragment can update or retire the
// right "shadow" placeholder bubble (see PendingAttachmentItem).
//
// Uploaded fires once the HTTP upload succeeds and the RTM broadcast has
// been sent — NOT full delivery. The self-echoed CHAT_ATTACHMENT broadcast
// (see ChatViewModel.rtmEvents) is what actually confirms the message went
// out, so ChatFragment keeps the shadow bubble alive (using the localId <->
// fileId link this event provides) until that echo's file_id matches, then
// swaps it for the real, full-info bubble — same latency the sender would
// see for a message from anyone else, just correlated back to their own
// pending send.
sealed class AttachmentSendEvent {
    data class Progress(val localId: String, val progress: Int) : AttachmentSendEvent()
    data class Uploaded(val localId: String, val fileId: String) : AttachmentSendEvent()
    data class Failed(val localId: String, val message: String) : AttachmentSendEvent()
}

// Real broadcast shape for EVENT.CHAT_ATTACHMENT — an envelope carrying
// sender_id/message_id (server-enriched, same as CHAT_MESSAGE) plus an
// `attachments` array, e.g.:
// {
//   "message_id": "...", "room_id": "...", "sender_id": "...",
//   "attachments": [{ "file_id": "...", "name": "...", "size": 227649,
//                      "mime_type": "image/png", "url": "...", "thumbnail_url": "..." }],
//   "timestamp": 1786625369
// }
// url/thumbnail_url are whatever the *sender's* client passed at send time —
// for this app that's the sender's own local cache path, meaningless on any
// other device — so they're intentionally not read here. Every client,
// sender included, fetches attachment bytes solely via file_id + the
// download API (see AttachmentGroupMessageItem).
// Renders as a single chat bubble listing every entry in `attachments`,
// rather than one bubble per file — see AttachmentGroupMessageItem.
fun JSONObject.toAttachmentGroupMessage(): AttachmentGroupMessageObj? {
    val senderId = optString("sender_id")
    val messageId = optString("message_id")
    val attachmentsArray = optJSONArray("attachments") ?: return null

    val attachments = (0 until attachmentsArray.length()).mapNotNull { index ->
        val attachment = attachmentsArray.optJSONObject(index) ?: return@mapNotNull null
        val fileId = attachment.optString("file_id")
        if (fileId.isBlank()) return@mapNotNull null

        AttachmentItem(
            file_id = fileId,
            name = attachment.optString("name", "attachment"),
            size = attachment.optLong("size", 0L),
            mime_type = attachment.optString("mime_type", "application/octet-stream")
        )
    }

    if (attachments.isEmpty()) return null

    return AttachmentGroupMessageObj(
        sender_id = senderId,
        message_id = messageId.ifBlank { attachments.first().file_id },
        attachments = attachments
    )
}

