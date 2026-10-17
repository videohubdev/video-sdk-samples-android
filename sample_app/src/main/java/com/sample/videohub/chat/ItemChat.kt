package com.sample.videohub.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.sample.videohub.R
import com.sample.videohub.databinding.ItemAttachmentRowBinding
import com.sample.videohub.databinding.ItemMessageAttachmentsLeftBinding
import com.sample.videohub.databinding.ItemMessageAttachmentsRightBinding
import com.sample.videohub.databinding.ItemMessageCenterBinding
import com.sample.videohub.databinding.ItemMessageGifLeftBinding
import com.sample.videohub.databinding.ItemMessageGifRightBinding
import com.sample.videohub.databinding.ItemMessageLeftBinding
import com.sample.videohub.databinding.ItemMessagePendingFileBinding
import com.sample.videohub.databinding.ItemMessagePendingImageBinding
import com.sample.videohub.databinding.ItemMessageRightBinding
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import dev.videohub.data.repository.network.AttachmentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

// Long-press menu shared by every message item type: quick reactions and
// "Reply" for any message, "Delete" only when it's your own (RTM's
// deleteMessage() has no server-side ownership check, so gating who can even
// send the request is the only thing enforcing "you can only delete your own").
private val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢")

private fun showMessageActionsMenu(
    anchor: View,
    canDelete: Boolean,
    onReaction: (String) -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit
) {
    PopupMenu(anchor.context, anchor).apply {
        QUICK_REACTIONS.forEachIndexed { index, emoji ->
            menu.add(0, index, index, emoji)
        }
        val replyId = QUICK_REACTIONS.size
        menu.add(0, replyId, replyId, "Reply")
        val deleteId = replyId + 1
        if (canDelete) {
            menu.add(0, deleteId, deleteId, "Delete")
        }
        setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                replyId -> onReply()
                deleteId -> if (canDelete) onDelete()
                else -> onReaction(QUICK_REACTIONS[menuItem.itemId])
            }
            true
        }
        show()
    }
}

// Renders an aggregated "emoji count" summary, e.g. "❤️ 2 👍 1", or hides
// the row entirely when there are no reactions yet.
private fun bindReactions(reactionsView: TextView, counts: Map<String, Int>) {
    if (counts.isEmpty()) {
        reactionsView.visibility = View.GONE
        return
    }
    reactionsView.visibility = View.VISIBLE
    reactionsView.text = counts.entries.joinToString(" ") { (emoji, count) -> "$emoji $count" }
}

class ChatMessageItem(
    private val item: ChatMessageObj,
    val myId: String,
    private val onDeleteRequested: ((String) -> Unit)? = null,
    private val onReactionRequested: ((String, String) -> Unit)? = null, // (messageId, reaction)
    private val onReplyRequested: ((ReplyTarget) -> Unit)? = null
) : BindableItem<ViewBinding>() {

    // Keyed by emoji -> count. Updated from outside via addReaction() as
    // REACTION_ADD events arrive, then notifyChanged() triggers a rebind —
    // Groupie items are otherwise immutable/data-driven.
    private val reactionCounts = mutableMapOf<String, Int>()

    fun addReaction(reaction: String) {
        reactionCounts[reaction] = (reactionCounts[reaction] ?: 0) + 1
        notifyChanged()
    }

    // message_id-based so ChatFragment can look this item up again to
    // remove it when a CHAT_MESSAGE_DELETE event arrives, or add a reaction
    // to it when a REACTION_ADD event arrives.
    override fun getId(): Long = item.message_id.hashCode().toLong()

    override fun getLayout(): Int {
        return if (item.sender_id == myId) {
            R.layout.item_message_right
        } else {
            R.layout.item_message_left
        }
    }

    override fun bind(viewBinding: ViewBinding, position: Int) {
        when (viewBinding) {
            is ItemMessageLeftBinding -> {
                viewBinding.tvName.text = item.sender_id
                viewBinding.tvMessage.text = item.message
                bindReactions(viewBinding.tvReactions, reactionCounts)
            }

            is ItemMessageRightBinding -> {
                viewBinding.tvMessage.text = item.message
                bindReactions(viewBinding.tvReactions, reactionCounts)
            }
        }

        viewBinding.root.setOnLongClickListener {
            showMessageActionsMenu(
                anchor = viewBinding.root,
                canDelete = item.sender_id == myId,
                onReaction = { reaction -> onReactionRequested?.invoke(item.message_id, reaction) },
                onReply = {
                    onReplyRequested?.invoke(ReplyTarget(item.message_id, item.sender_id, item.message))
                },
                onDelete = { onDeleteRequested?.invoke(item.message_id) }
            )
            true
        }
    }

    override fun initializeViewBinding(view: View): ViewBinding {
        return when (view.id) {
            R.id.leftRoot -> ItemMessageLeftBinding.bind(view)
            else -> ItemMessageRightBinding.bind(view)
        }
    }

}

// Animated GIF/sticker sent from the IME's rich-content tray (see
// ChatFragment's OnReceiveContentListener + ChatViewModel.sendGifMessage).
class GifMessageItem(
    private val item: GifMessageObj,
    val myId: String,
    private val onDeleteRequested: ((String) -> Unit)? = null,
    private val onReactionRequested: ((String, String) -> Unit)? = null,
    private val onReplyRequested: ((ReplyTarget) -> Unit)? = null
) : BindableItem<ViewBinding>() {

    private val reactionCounts = mutableMapOf<String, Int>()

    fun addReaction(reaction: String) {
        reactionCounts[reaction] = (reactionCounts[reaction] ?: 0) + 1
        notifyChanged()
    }

    override fun getId(): Long = item.message_id.hashCode().toLong()

    override fun getLayout(): Int {
        return if (item.sender_id == myId) {
            R.layout.item_message_gif_right
        } else {
            R.layout.item_message_gif_left
        }
    }

    override fun bind(viewBinding: ViewBinding, position: Int) {
        val imgGif = when (viewBinding) {
            is ItemMessageGifLeftBinding -> {
                viewBinding.tvName.text = item.sender_id
                bindReactions(viewBinding.tvReactions, reactionCounts)
                viewBinding.imgGif
            }
            is ItemMessageGifRightBinding -> {
                bindReactions(viewBinding.tvReactions, reactionCounts)
                viewBinding.imgGif
            }
            else -> return
        }

        val bytes = Base64.decode(item.data, Base64.DEFAULT)
        Glide.with(imgGif).load(bytes).into(imgGif)

        viewBinding.root.setOnLongClickListener {
            showMessageActionsMenu(
                anchor = viewBinding.root,
                canDelete = item.sender_id == myId,
                onReaction = { reaction -> onReactionRequested?.invoke(item.message_id, reaction) },
                onReply = {
                    // No text to quote from a GIF — a placeholder snippet stands in for it.
                    onReplyRequested?.invoke(ReplyTarget(item.message_id, item.sender_id, "📎 GIF"))
                },
                onDelete = { onDeleteRequested?.invoke(item.message_id) }
            )
            true
        }
    }

    override fun initializeViewBinding(view: View): ViewBinding {
        return when (view.id) {
            R.id.leftGifRoot -> ItemMessageGifLeftBinding.bind(view)
            else -> ItemMessageGifRightBinding.bind(view)
        }
    }

}

// A reply to an earlier message — a rich message (see ChatModel.ReplyPayload)
// rendered with the quoted original above its own text.
class ReplyMessageItem(
    private val item: ReplyMessageObj,
    val myId: String,
    private val onDeleteRequested: ((String) -> Unit)? = null,
    private val onReactionRequested: ((String, String) -> Unit)? = null,
    private val onReplyRequested: ((ReplyTarget) -> Unit)? = null
) : BindableItem<ViewBinding>() {

    private val reactionCounts = mutableMapOf<String, Int>()

    fun addReaction(reaction: String) {
        reactionCounts[reaction] = (reactionCounts[reaction] ?: 0) + 1
        notifyChanged()
    }

    override fun getId(): Long = item.message_id.hashCode().toLong()

    override fun getLayout(): Int {
        return if (item.sender_id == myId) {
            R.layout.item_message_right
        } else {
            R.layout.item_message_left
        }
    }

    override fun bind(viewBinding: ViewBinding, position: Int) {
        val quoted = "${item.reply_to_sender}: ${item.reply_to_text}"

        when (viewBinding) {
            is ItemMessageLeftBinding -> {
                viewBinding.tvName.text = item.sender_id
                viewBinding.tvQuoted.text = quoted
                viewBinding.tvQuoted.visibility = View.VISIBLE
                viewBinding.tvMessage.text = item.text
                bindReactions(viewBinding.tvReactions, reactionCounts)
            }

            is ItemMessageRightBinding -> {
                viewBinding.tvQuoted.text = quoted
                viewBinding.tvQuoted.visibility = View.VISIBLE
                viewBinding.tvMessage.text = item.text
                bindReactions(viewBinding.tvReactions, reactionCounts)
            }
        }

        viewBinding.root.setOnLongClickListener {
            showMessageActionsMenu(
                anchor = viewBinding.root,
                canDelete = item.sender_id == myId,
                onReaction = { reaction -> onReactionRequested?.invoke(item.message_id, reaction) },
                onReply = {
                    onReplyRequested?.invoke(ReplyTarget(item.message_id, item.sender_id, item.text))
                },
                onDelete = { onDeleteRequested?.invoke(item.message_id) }
            )
            true
        }
    }

    override fun initializeViewBinding(view: View): ViewBinding {
        return when (view.id) {
            R.id.leftRoot -> ItemMessageLeftBinding.bind(view)
            else -> ItemMessageRightBinding.bind(view)
        }
    }

}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}

// Per-attachment download bookkeeping for AttachmentGroupMessageItem, one
// per entry in the message's attachments list. Held by the (long-lived)
// Item instance rather than the (recycled) row View, same reasoning as
// AttachmentGroupMessageItem itself: a scrolled-away-and-back row shouldn't
// restart a download or forget a completed one.
private class AttachmentRowState(val attachment: AttachmentItem, cacheDir: File) {
    val destination: File = attachmentCacheFile(cacheDir, attachment.file_id, attachment.name)
    var localFile: File? = destination.takeIf { it.exists() }
    var isDownloading: Boolean = false
    var lastProgress: Int = 0
    var downloadJob: Job? = null
}

// One CHAT_ATTACHMENT message (see ChatModel.AttachmentGroupMessageObj),
// rendered as a single bubble listing every file it carries — a row per
// entry in item.attachments — rather than one bubble per file. Rows are
// inflated dynamically into attachmentsContainer since the count varies.
// Images auto-download and render inline as soon as their row is bound
// (WhatsApp-style); other files show name/size and only download when
// tapped, per the caller-supplied `download` flow factory.
class AttachmentGroupMessageItem(
    private val item: AttachmentGroupMessageObj,
    val myId: String,
    private val cacheDir: File,
    private val downloadScope: CoroutineScope,
    private val download: (attachmentId: String, destination: File) -> Flow<AttachmentState<File>>,
    private val onOpenFile: (File, String) -> Unit,
    private val onDeleteRequested: ((String) -> Unit)? = null,
    private val onReactionRequested: ((String, String) -> Unit)? = null,
    private val onReplyRequested: ((ReplyTarget) -> Unit)? = null
) : BindableItem<ViewBinding>() {

    private val reactionCounts = mutableMapOf<String, Int>()
    private val rows = item.attachments.map { AttachmentRowState(it, cacheDir) }

    fun addReaction(reaction: String) {
        reactionCounts[reaction] = (reactionCounts[reaction] ?: 0) + 1
        notifyChanged()
    }

    override fun getId(): Long = item.message_id.hashCode().toLong()

    override fun getLayout(): Int =
        if (item.sender_id == myId) R.layout.item_message_attachments_right else R.layout.item_message_attachments_left

    override fun bind(viewBinding: ViewBinding, position: Int) {
        val (container, reactionsView) = when (viewBinding) {
            is ItemMessageAttachmentsLeftBinding -> {
                viewBinding.tvName.text = item.sender_id
                viewBinding.attachmentsContainer to viewBinding.tvReactions
            }
            is ItemMessageAttachmentsRightBinding -> viewBinding.attachmentsContainer to viewBinding.tvReactions
            else -> return
        }

        bindReactions(reactionsView, reactionCounts)

        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        rows.forEach { row ->
            val rowBinding = ItemAttachmentRowBinding.inflate(inflater, container, true)
            bindAttachmentRow(row, rowBinding)
        }

        viewBinding.root.setOnLongClickListener {
            showMessageActionsMenu(
                anchor = viewBinding.root,
                canDelete = item.sender_id == myId,
                onReaction = { reaction -> onReactionRequested?.invoke(item.message_id, reaction) },
                onReply = {
                    val label = if (item.attachments.size == 1) item.attachments.first().name else "${item.attachments.size} files"
                    onReplyRequested?.invoke(ReplyTarget(item.message_id, item.sender_id, "📎 $label"))
                },
                onDelete = { onDeleteRequested?.invoke(item.message_id) }
            )
            true
        }
    }

    private fun bindAttachmentRow(row: AttachmentRowState, binding: ItemAttachmentRowBinding) {
        binding.tvFileName.text = row.attachment.name
        binding.tvFileSize.text = formatFileSize(row.attachment.size)
        val isImage = row.attachment.mime_type.startsWith("image/")

        val cached = row.localFile
        when {
            cached != null -> showDownloaded(row, binding, cached, isImage)
            row.isDownloading -> {
                binding.imgIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
                binding.imgIcon.setImageResource(R.drawable.ic_insert_drive_file_24)
                binding.btnAction.visibility = View.GONE
                binding.progress.visibility = View.VISIBLE
                binding.progress.progress = row.lastProgress
                binding.rowRoot.setOnClickListener(null)
            }
            else -> {
                binding.imgIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
                binding.imgIcon.setImageResource(R.drawable.ic_insert_drive_file_24)
                binding.progress.visibility = View.GONE
                binding.btnAction.visibility = View.VISIBLE
                binding.btnAction.setImageResource(R.drawable.ic_download_24)
                val startClick = View.OnClickListener { startDownload(row, binding) }
                binding.rowRoot.setOnClickListener(startClick)
                binding.btnAction.setOnClickListener(startClick)

                // WhatsApp-style: images fetch themselves immediately, other
                // files wait for a tap.
                if (isImage) startDownload(row, binding)
            }
        }
    }

    private fun showDownloaded(row: AttachmentRowState, binding: ItemAttachmentRowBinding, file: File, isImage: Boolean) {
        binding.progress.visibility = View.GONE
        binding.btnAction.visibility = View.VISIBLE
        binding.btnAction.setImageResource(R.drawable.outline_arrow_circle_right_24)

        if (isImage) {
            binding.imgIcon.imageTintList = null
            Glide.with(binding.imgIcon).load(file).into(binding.imgIcon)
        } else {
            binding.imgIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            binding.imgIcon.setImageResource(R.drawable.ic_insert_drive_file_24)
        }

        val openClick = View.OnClickListener { onOpenFile(file, row.attachment.mime_type) }
        binding.rowRoot.setOnClickListener(openClick)
        binding.btnAction.setOnClickListener(openClick)
    }

    private fun startDownload(row: AttachmentRowState, binding: ItemAttachmentRowBinding) {
        val isImage = row.attachment.mime_type.startsWith("image/")

        binding.btnAction.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.progress.progress = 0
        binding.rowRoot.setOnClickListener(null)
        row.isDownloading = true
        row.lastProgress = 0

        row.downloadJob?.cancel()
        row.downloadJob = downloadScope.launch {
            download(row.attachment.file_id, row.destination).collectLatest { state ->
                when (state) {
                    is AttachmentState.Progress -> {
                        row.lastProgress = state.progress
                        binding.progress.progress = state.progress
                    }
                    is AttachmentState.Success -> {
                        row.isDownloading = false
                        row.localFile = state.data
                        showDownloaded(row, binding, state.data, isImage)
                    }
                    is AttachmentState.Error -> {
                        row.isDownloading = false
                        binding.progress.visibility = View.GONE
                        binding.btnAction.visibility = View.VISIBLE
                    }
                    else -> Unit
                }
            }
        }
    }

    // Cancels every row's in-flight download when the View is recycled for
    // another message, so a late progress/success callback never lands on a
    // ViewHolder that's since been rebound elsewhere — and clears
    // isDownloading so the next bind() (fresh row Views, since bind()
    // re-inflates attachmentsContainer) restarts it instead of getting stuck
    // showing an indicator no job is driving.
    override fun unbind(viewHolder: GroupieViewHolder<ViewBinding>) {
        rows.forEach { row ->
            row.downloadJob?.cancel()
            row.downloadJob = null
            row.isDownloading = false
        }
        super.unbind(viewHolder)
    }

    override fun initializeViewBinding(view: View): ViewBinding {
        return if (item.sender_id == myId) ItemMessageAttachmentsRightBinding.bind(view)
        else ItemMessageAttachmentsLeftBinding.bind(view)
    }
}

// A "shadow"/ghost bubble shown the instant a file is picked, before the
// upload even starts — WhatsApp-style optimistic UI. Always right-aligned:
// only the sender ever sees their own send in flight, since remote
// participants only learn about the attachment once it's fully sent (see
// ChatViewModel.rtmEvents' CHAT_ATTACHMENT handler). ChatFragment removes
// this item (keyed by localId, not any server id) once
// ChatViewModel.attachmentSendEvents reports Sent or Failed.
class PendingAttachmentItem(
    val localId: String,
    // The upload streams straight from this content:// Uri now (see
    // Attachment.upload(context, uri)) — there's no local temp File to
    // preview from anymore, but Glide loads a Uri just as well as a File.
    private val localUri: Uri,
    private val mimeType: String,
    private val displayName: String,
    private val size: Long
) : BindableItem<ViewBinding>() {

    private val isImage = mimeType.startsWith("image/")

    var progress: Int = 0
        set(value) {
            field = value
            notifyChanged()
        }

    override fun getId(): Long = localId.hashCode().toLong()

    override fun getLayout(): Int =
        if (isImage) R.layout.item_message_pending_image else R.layout.item_message_pending_file

    override fun bind(viewBinding: ViewBinding, position: Int) {
        when (viewBinding) {
            is ItemMessagePendingImageBinding -> {
                Glide.with(viewBinding.imgAttachment).load(localUri).into(viewBinding.imgAttachment)
                viewBinding.progressImage.progress = progress
            }
            is ItemMessagePendingFileBinding -> {
                viewBinding.tvFileName.text = displayName
                viewBinding.tvFileSize.text = formatFileSize(size)
                viewBinding.progressDownload.progress = progress
            }
        }
    }

    override fun initializeViewBinding(view: View): ViewBinding =
        if (isImage) ItemMessagePendingImageBinding.bind(view) else ItemMessagePendingFileBinding.bind(view)
}

// System message shown centered in the chat list — e.g. "X joined" / "X left".
class CenterMessageItem(
    private val text: String
) : BindableItem<ItemMessageCenterBinding>() {

    override fun getLayout(): Int = R.layout.item_message_center

    override fun bind(viewBinding: ItemMessageCenterBinding, position: Int) {
        viewBinding.txt.text = text
    }

    override fun initializeViewBinding(view: View): ItemMessageCenterBinding {
        return ItemMessageCenterBinding.bind(view)
    }

}
