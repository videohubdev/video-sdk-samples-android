package com.sample.videohub.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sample.videohub.rtm.RTMObject
import com.sample.videohub.databinding.FragmentChatBinding
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Item
import dev.videohub.utils.getFileInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ChatFragment : Fragment() {

    companion object {
        // Raw bytes, pre-base64 — the RTM socket carries small JSON chat
        // payloads, not media, so this is a deliberately tight cap.
        private const val MAX_GIF_BYTES = 300 * 1024

        // How long to wait with no new input before treating the user as
        // having stopped typing.
        private const val TYPING_STOP_DELAY_MS = 3000L
    }

    lateinit var binding: FragmentChatBinding

    lateinit var audienceAdapter : GroupieAdapter
    val viewmodel: ChatViewModel by viewModels()

    private val messageItems = mutableMapOf<String, Item<*>>()

    // fileId -> localId, populated once an in-flight upload's file_id is
    // known (see AttachmentSendEvent.Uploaded) — lets the receivedAttachmentMessage
    // collector recognize our own self-echoed broadcast and retire the
    // matching "shadow" placeholder instead of leaving a stale duplicate.
    private val pendingLocalIdByFileId = mutableMapOf<String, String>()

    // Where downloaded attachments land — must match the <cache-path> declared
    // in res/xml/file_paths.xml so FileProvider can grant a content:// Uri
    // for them (see openFile()).
    private val attachmentCacheDir: File by lazy {
        File(requireContext().cacheDir, "attachments").apply { mkdirs() }
    }

    // Local typing state — see setupTypingIndicator().
    private var isTyping = false
    private var typingStopJob: Job? = null

    private var replyTarget: ReplyTarget? = null

    var showCloseButton: Boolean = true

    // Must be registered unconditionally at field-init time (before the
    // fragment reaches STARTED) — registering inside a click listener would
    // throw.
    private val pickAttachment = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handlePickedAttachment(it) }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnEmoji.setOnClickListener {
            val emojis = listOf(
                "😀","😂","😍","❤️","👍","🔥","🎉","🙏"
            )

            PopupMenu(requireContext(), binding.btnEmoji).apply {
                emojis.forEachIndexed { index, emoji ->
                    menu.add(0, index, index, emoji)
                }

                setOnMenuItemClickListener {
                    binding.etMessage.append(it.title)
                    true
                }

                show()
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString()

            if (!text.isEmpty()) {
                val target = replyTarget
                if (target != null) {
                    viewmodel.sendReply(text, target)
                } else {
                    viewmodel.sendChatMessage(text)
                }
            }

            binding.etMessage.text = null
            clearReplyTarget()
        }

        binding.btnCancelReply.setOnClickListener {
            clearReplyTarget()
        }

        binding.btnAttachment.setOnClickListener {
            pickAttachment.launch("*/*")
        }

//        if (showCloseButton) {
//            binding.imgClose.visibility = VISIBLE
//            binding.imgClose.setOnClickListener {
//                activity?.finish()
//            }
//        } else {
//            binding.imgClose.visibility = GONE
//        }

        setupGifKeyboardInput()
        setupTypingIndicator()

        initAdapter()

//        SessionManager.authToken

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.receivedChatMessage.collect { message ->

                    message?.let { message ->
                        Log.e("TAG",  "myId >> ${RTMObject.myId} >>  message >> $message")
                        if (!isAdded) return@collect

                        requireActivity().runOnUiThread {
                            val item = ChatMessageItem(
                                item = message,
                                myId = viewmodel.identity ?: "",
                                onDeleteRequested = { messageId -> viewmodel.deleteMessage(messageId) },
                                onReactionRequested = { messageId, reaction -> viewmodel.addReaction(messageId, reaction) },
                                onReplyRequested = { target -> setReplyTarget(target) }
                            )
                            messageItems[message.message_id] = item
                            audienceAdapter.add(item)
                            scrollToBottom()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.receivedGifMessage.collect { message ->

                    message?.let { message ->
                        if (!isAdded) return@collect

                        requireActivity().runOnUiThread {
                            val item = GifMessageItem(
                                item = message,
                                myId = viewmodel.identity ?: "",
                                onDeleteRequested = { messageId -> viewmodel.deleteMessage(messageId) },
                                onReactionRequested = { messageId, reaction -> viewmodel.addReaction(messageId, reaction) },
                                onReplyRequested = { target -> setReplyTarget(target) }
                            )
                            messageItems[message.message_id] = item
                            audienceAdapter.add(item)
                            scrollToBottom()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.receivedReplyMessage.collect { message ->

                    message?.let { message ->
                        if (!isAdded) return@collect

                        requireActivity().runOnUiThread {
                            val item = ReplyMessageItem(
                                item = message,
                                myId = viewmodel.identity ?: "",
                                onDeleteRequested = { messageId -> viewmodel.deleteMessage(messageId) },
                                onReactionRequested = { messageId, reaction -> viewmodel.addReaction(messageId, reaction) },
                                onReplyRequested = { target -> setReplyTarget(target) }
                            )
                            messageItems[message.message_id] = item
                            audienceAdapter.add(item)
                            scrollToBottom()
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.receivedAttachmentMessage.collect { message ->
                    if (!isAdded) return@collect

                    requireActivity().runOnUiThread {
                        // If this is the self-echo of our own send, retire
                        // its "shadow" placeholder now that the real,
                        // full-info bubble below is about to take its place.
                        // (A message we sent only ever carries one file, but
                        // check every entry since a message from elsewhere
                        // could carry several.)
                        message.attachments.forEach { attachment ->
                            pendingLocalIdByFileId.remove(attachment.file_id)?.let { localId ->
                                messageItems.remove(localId)?.let { audienceAdapter.remove(it) }
                            }
                        }

                        val item = AttachmentGroupMessageItem(
                            item = message,
                            myId = viewmodel.identity ?: "",
                            cacheDir = attachmentCacheDir,
                            downloadScope = viewLifecycleOwner.lifecycleScope,
                            download = { attachmentId, destination -> viewmodel.downloadAttachment(attachmentId, destination) },
                            onOpenFile = { file, mimeType -> openFile(file, mimeType) },
                            onDeleteRequested = { messageId -> viewmodel.deleteMessage(messageId) },
                            onReactionRequested = { messageId, reaction -> viewmodel.addReaction(messageId, reaction) },
                            onReplyRequested = { target -> setReplyTarget(target) }
                        )
                        messageItems[message.message_id] = item
                        audienceAdapter.add(item)
                        scrollToBottom()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.attachmentSendEvents.collect { event ->
                    if (!isAdded) return@collect

                    requireActivity().runOnUiThread {
                        when (event) {
                            is AttachmentSendEvent.Progress -> {
                                (messageItems[event.localId] as? PendingAttachmentItem)?.progress = event.progress
                            }
                            // Upload + RTM send both succeeded, but that's not
                            // delivery — remember the link so the
                            // receivedAttachmentMessage collector above can
                            // retire this shadow once the real broadcast
                            // (self-echoed back to us) actually arrives,
                            // instead of removing it early and leaving a gap.
                            is AttachmentSendEvent.Uploaded -> {
                                pendingLocalIdByFileId[event.fileId] = event.localId
                            }
                            is AttachmentSendEvent.Failed -> {
                                messageItems.remove(event.localId)?.let { audienceAdapter.remove(it) }
                                Toast.makeText(requireContext(), "Failed to send attachment: ${event.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.deletedMessageId.collect { messageId ->
                    if (!isAdded) return@collect

                    requireActivity().runOnUiThread {
                        messageItems.remove(messageId)?.let { audienceAdapter.remove(it) }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.messageReaction.collect { data ->
                    if (!isAdded) return@collect

                    requireActivity().runOnUiThread {
                        when (val item = messageItems[data.message_id]) {
                            is ChatMessageItem -> item.addReaction(data.reaction)
                            is GifMessageItem -> item.addReaction(data.reaction)
                            is ReplyMessageItem -> item.addReaction(data.reaction)
                            is AttachmentGroupMessageItem -> item.addReaction(data.reaction)
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.presenceMessage.collect { text ->
                    if (!isAdded) return@collect

                    requireActivity().runOnUiThread {
                        audienceAdapter.add(CenterMessageItem(text))
                        scrollToBottom()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewmodel.typingUsers.collect { users ->
                    if (!isAdded) return@collect

                    requireActivity().runOnUiThread {
                        binding.txtTyping.visibility = if (users.isNotEmpty()) VISIBLE else GONE
                        binding.txtTyping.text = when (users.size) {
                            0 -> ""
                            1 -> "${users.first()} is typing…"
                            else -> "${users.joinToString(", ")} are typing…"
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewmodel.raisedHands.collect { users ->
                if (!isActive){
                    return@collect
                }

                requireActivity().runOnUiThread {

                    binding.txtRaised.visibility = if (users.isNotEmpty()) VISIBLE else GONE
                    binding.txtRaised.setText("\uD83E\uDD1A Raised Hand: ${users.joinToString(", ")}")

                }
            }
        }


        binding.floating.setOnClickListener {
            RTMObject.raiseHand()
        }

        lifecycleScope.launch {
            viewmodel.rtmEvents()
        }

    }



    // Advertises rich-content support to the IME so Gboard (etc.) shows its
    // animated GIF/sticker tray for this field, and handles what it commits.
    private fun setupGifKeyboardInput() {
        val mimeTypes = arrayOf("image/gif", "image/png", "image/webp", "image/jpeg")

        ViewCompat.setOnReceiveContentListener(binding.etMessage, mimeTypes) { _, payload ->
            val split = payload.partition { item -> item.uri != null }
            val uriContent = split.first
            val remaining = split.second

            uriContent?.let { content -> handleIncomingImageContent(content) }

            // Whatever wasn't a URI (e.g. plain typed/pasted text) is left
            // for the default handling to insert as normal.
            remaining
        }
    }

    private fun handleIncomingImageContent(content: ContentInfoCompat) {
        val clip = content.clip
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val mime = clip.description.getMimeType(0)

            val bytes = try {
                requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.e("ChatFragment", "failed to read IME content: ${e.message}")
                null
            } ?: continue

            // The RTM channel carries small JSON chat payloads, not media —
            // cap what we'll inline as base64 so one sticker can't blow up
            // the socket for every other client in the room.
            if (bytes.size > MAX_GIF_BYTES) {
                Toast.makeText(requireContext(), "GIF too large to send", Toast.LENGTH_SHORT).show()
                continue
            }

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            viewmodel.sendGifMessage(base64, mime)
        }

        // The GIF/sticker tray is part of the keyboard — picking one is the
        // end of that interaction, so dismiss it same as a text send would.
        hideKeyboard()
    }

    // File chosen from the system picker via btnAttachment — shows a
    // "shadow" placeholder bubble right away (see PendingAttachmentItem),
    // then hands the Uri straight to the ViewModel, which streams it to the
    // server over HTTP (Attachment.upload(context, uri) reads directly from
    // the content:// Uri — no local copy needed, and it enforces the 1GB
    // cap) and broadcasts the result over RTM (see
    // ChatViewModel.sendAttachment). Progress for that upload comes back via
    // attachmentSendEvents, collected above, which also retires the shadow
    // once the real, full-info bubble has been added.
    private fun handlePickedAttachment(uri: Uri) {
        val info = uri.getFileInfo(requireContext())
        Log.d("ChatFragment", "picked attachment: ${info.name} (${info.mimeType}, ${info.size}b) $uri")

        val localId = UUID.randomUUID().toString()

        val pendingItem = PendingAttachmentItem(localId, uri, info.mimeType, info.name, info.size)
        messageItems[localId] = pendingItem
        audienceAdapter.add(pendingItem)
        scrollToBottom()

        viewmodel.sendAttachment(localId, uri, requireContext(), attachmentCacheDir)
    }

    // Opens a downloaded attachment with whatever app the device has
    // registered for its mime type — needs a content:// Uri (not file://)
    // for the receiving app to read it, hence FileProvider (see
    // AndroidManifest.xml + res/xml/file_paths.xml).
    private fun openFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
    }

    // Chat.typingStart()/typingStop() only fire while text is present, and
    // typingStart() only fires once per "typing session" (not per
    // keystroke) — a debounce timer fires typingStop() after a pause with
    // no new input, since RTM has no built-in idea of "stopped typing".
    private fun setupTypingIndicator() {
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) {
                    stopTypingNow()
                } else {
                    startTypingIfNeeded()
                    scheduleTypingStop()
                }
            }
        })
    }

    private fun startTypingIfNeeded() {
        if (!isTyping) {
            isTyping = true
            viewmodel.typingStart()
        }
    }

    private fun scheduleTypingStop() {
        typingStopJob?.cancel()
        typingStopJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(TYPING_STOP_DELAY_MS)
            stopTypingNow()
        }
    }

    private fun stopTypingNow() {
        typingStopJob?.cancel()
        typingStopJob = null
        if (isTyping) {
            isTyping = false
            viewmodel.typingStop()
        }
    }

    // Shows the reply-preview bar above the input box, quoting whichever
    // message "Reply" was picked from — see the long-press menu wired into
    // every message item's onReplyRequested callback.
    private fun setReplyTarget(target: ReplyTarget) {
        replyTarget = target
        binding.cardReplyPreview.visibility = VISIBLE
        binding.txtReplyToSender.text = "Replying to ${target.senderId}"
        binding.txtReplyToText.text = target.snippet
    }

    private fun clearReplyTarget() {
        replyTarget = null
        binding.cardReplyPreview.visibility = GONE
    }

    override fun onDestroyView() {
        // Don't leave other participants' "is typing…" indicator stuck on if
        // this fragment goes away mid-type (e.g. chat panel closed).
        stopTypingNow()
        super.onDestroyView()
    }

    fun initAdapter(){
        audienceAdapter = GroupieAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
            adapter = audienceAdapter
        }
    }

    // Called after every audienceAdapter.add() — posted rather than run
    // inline because scrolling right after add() would target the layout
    // from before the newly inserted item's row was measured; posting runs
    // after RecyclerView has processed that layout pass.
    private fun scrollToBottom() {
        binding.rvMessages.post {
            val itemCount = audienceAdapter.itemCount
            if (itemCount > 0) {
                binding.rvMessages.scrollToPosition(itemCount - 1)
            }
        }
    }


}
