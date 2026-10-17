package com.sample.videohub.video

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sample.videohub.AbstractActivity
import com.sample.videohub.R
import com.sample.videohub.chat.ChatFragment
import com.sample.videohub.databinding.ActivityGroupVideoChatBinding
import com.sample.videohub.rtm.RTMChatActivity
import com.sample.videohub.rtm.RTMObject
import com.sample.videohub.utils.GridSpacingItemDecoration
import com.sample.videohub.utils.dpToPx
import com.xwray.groupie.GroupieAdapter
import dev.videohub.android.room.track.VideoPreset169
import dev.videohub.core.RoomSessionState
import dev.videohub.core.rtm.EVENT
import kotlinx.coroutines.launch

class GroupVideoChatActivity : AbstractActivity() {

    lateinit var binding: ActivityGroupVideoChatBinding

    val viewModel : VideoViewModel by viewModels()

    lateinit var audienceAdapter : GroupieAdapter
    private lateinit var fabScreenShare: FloatingActionButton
    private lateinit var fabQuality: FloatingActionButton
    // isScreenShareEnabled is a cold Flow (no .value) — addItem()'s onClick
    // is registered once, so it needs a synchronously readable mirror kept
    // up to date by screenShareObserver()'s collector.
    private var screenShareEnabled = false

    var isHostUser: Boolean = false
    var room: String? = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityGroupVideoChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val isHost = intent.extras?.getString("as")
        room = intent.extras?.getString("room")

        isHostUser = isHost == "host"
        initAdapter()

        requestNeededPermissions {
            if (isHost == "host") {
                startRoomAsHost(room ?: "room111")
            } else {
                joinAsGuest(room ?: "room111")
            }
        }

        onBackPressedDispatcher.addCallback {
            finishVideoChat()
        }

//        binding.fabMenu.addItem(R.drawable.baseline_chat_24, "Chat") {
////                viewModel.raiseHand()
//                startActivity(
//                    Intent(GroupVideoChatActivity@this, RTMChatActivity::class.java))
//        }

        fabScreenShare = binding.fabMenu.addItem(R.drawable.baseline_cast_24, "Screen Share") {
            if (screenShareEnabled) {
                stopScreenShare()
            } else {
                requestMediaProjection()
            }
        }

//        fabQuality = binding.fabMenu.addItem(R.drawable.baseline_tune_24, "Quality") {
//            showQualityMenu(fabQuality)
//        }

        // Host-only moderation shortcut. There's no unmute-all counterpart:
        // RemoteItem.mute()/RTM Moderation.muteUser() are the only mute
        // primitives the SDK exposes, and both are one-directional
        // force-mutes (same as the per-row img_mute in StreamItem) — a host
        // can silence a participant's mic but can't remotely switch it back
        // on, so this only ever mutes everyone currently in the room.
        if (isHostUser) {
            binding.fabMenu.addItem(R.drawable.outline_mic_off_24, "Mute All") {
                muteAllParticipants()
            }
        }

        binding.appbar.imgBack.setOnClickListener {
            finishVideoChat()
        }
        if (savedInstanceState == null) {
            // ChatFragment's close ("X") button defaults to finishing the
            // host Activity — appropriate when chat is its own screen (see
            // RTMChatActivity), wrong here where it's just an embedded panel
            // alongside the video grid. Leaving the call goes through
            // appbar.imgBack / the system back gesture instead.

        }
    }

    // Same two calls StreamItem.muteRemote() makes per-row, just looped
    // over every remote participant currently in session.remote.participants
    // — the REST call (session.remote.moderation(...).mute) is the actual
    // server-side enforcement, RTMObject's muteUser is the real-time
    // signaling notification. Snapshots .value rather than observing the
    // flow: this is a one-shot action on whoever's in the room right now,
    // not something that should re-fire as participants join later.
    private fun muteAllParticipants() {
        val session = try {
            viewModel.session
        } catch (e: UninitializedPropertyAccessException) {
            return
        }

        lifecycleScope.launch {
            session.remote.participants.value.values.forEach { participant ->
                val identity = participant.identity?.value ?: return@forEach
                session.remote.moderation(participant).mute(identity)
                RTMObject.rtm?.moderation?.muteUser(session.roomName, identity)
            }
        }
    }

    fun finishVideoChat(){
        lifecycleScope.launch {
            viewModel.deleteRoom(isHostUser)
            finish()
        }
    }

    fun initAdapter(){
        audienceAdapter = GroupieAdapter()
        binding.participants.apply {
            layoutManager = GridLayoutManager(context, 2)
            if (itemDecorationCount == 0) {
                addItemDecoration(
                    GridSpacingItemDecoration(
                        spanCount = 2,
                        spacing = context.dpToPx(12),
                        includeEdge = true
                    )
                )
            }
            adapter = audienceAdapter
        }
    }

    private fun startRoomAsHost(room:String){

        lifecycleScope.launch {
            Log.e("tag", "connecting ")
            viewModel.join(room)
            listenForKick()

            launch {
                collectParticipants()
            }

            launch {
                screenShareObserver()
            }

            launch {
                viewModel.disconnectListener.state.collect {
                    if(it==true) { finishVideoChat() }
                }
            }

            addChat()
        }
    }

    private fun joinAsGuest(room:String){
        lifecycleScope.launch {
            Log.e("tag", "connecting ")

            viewModel.joinAsGuest(room)
            listenForKick()

            launch {
                collectParticipants()
            }

            launch {
                screenShareObserver()
            }
            launch {
                viewModel.disconnectListener.state.collect {
                    if(it==true) { finishVideoChat() }
                }
            }

            addChat()
        }
    }

    fun addChat(){
        val chatFragment = ChatFragment().apply { showCloseButton = false }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, chatFragment)
            .commit()
    }

    // Kicking someone (StreamItem.kickRemote() / muteAllParticipants()) is a
    // REST call to remove them server-side plus this RTM notification — but
    // nothing previously listened for it, so the kicked user's own client
    // never actually left the room. If the server-side removal doesn't
    // promptly broadcast a disconnect back over the signaling socket (or
    // doesn't happen for the kicked user's own connection at all), their
    // RemoteParticipant entry just sits in everyone else's
    // session.remote.participants with no track publications — the blank
    // "Unknown" tile that doesn't go away. Reacting to the notification
    // directly makes the kicked client leave for real, which is what
    // actually removes their tile everywhere else.
    private fun listenForKick() {
        RTMObject.rtm?.on(EVENT.USER_KICK) { payload ->
            val targetUserId = payload?.optString("target_user_id")
            if (!targetUserId.isNullOrBlank() && targetUserId == RTMObject.rtm?.identity) {
                finishVideoChat()
            }
        }
    }

    // On a "full reconnect" the SDK disposes every RemoteParticipant and
    // fires ParticipantDisconnected for each, then rebuilds fresh instances
    // once reconnected — a real, if brief, gap in session.remote.participants
    // even though nobody actually left. Applying that gap to the adapter
    // flickers the grid down to just the local tile and back. Skip updates
    // while RECONNECTING; the emission once participants repopulate (after
    // RECONNECTED) still comes through and brings the grid back correctly.
    private suspend fun collectParticipants() {
        viewModel.participantsObserver().collect { items ->
            if (viewModel.session.sessionState == RoomSessionState.RECONNECTING) {
                return@collect
            }
            audienceAdapter.update(items)
        }
    }


    override fun onDestroy() {
//        session.release()
        try {
            viewModel.disconnect()
            RTMObject.dismiss()
        }catch (e: Exception){
            e.printStackTrace()
        }

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resume()
    }

    override fun onPause() {
        viewModel.pause()
        super.onPause()
    }

    /***************************
    * screen share option *
    ****************************/

    fun screenShareObserver(){
        Log.e("groupVideo", "screen share2")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.screen.isScreenShareEnabled.collect { enabled ->
                    Log.e("groupVideo", "screen share1: $enabled")

                    screenShareEnabled = enabled
                    fabScreenShare.setImageResource(
                        if (enabled) {
                            R.drawable.baseline_cast_connected_24
                        } else {
                            R.drawable.baseline_cast_24
                        },
                    )
                }
            }
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private val screenCaptureIntentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val resultCode = result.resultCode
            val data = result.data
            if (resultCode != RESULT_OK || data == null) {
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                viewModel.startScreenShare(data)
            }
        }

    fun stopScreenShare(){
        viewModel.stopScreenShare()
    }

    /***************************
    * video quality option *
    ****************************/

    private fun showQualityMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, "Low (180p)")
            menu.add(0, 1, 1, "Medium (360p)")
            menu.add(0, 2, 2, "High (720p)")
            menu.add(0, 3, 3, "HD (1080p)")

            setOnMenuItemClickListener { item ->
                val preset = when (item.itemId) {
                    0 -> VideoPreset169.H180
                    1 -> VideoPreset169.H360
                    2 -> VideoPreset169.H720
                    3 -> VideoPreset169.H1080
                    else -> VideoPreset169.H360
                }
                viewModel.changeVideoQuality(preset.capture)
                true
            }

            show()
        }
    }

}
