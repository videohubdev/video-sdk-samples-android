package com.sample.videohub.rtm

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sample.videohub.AbstractActivity
import com.sample.videohub.R
import com.sample.videohub.databinding.ActivityGroupVideoChatBinding
import com.sample.videohub.utils.GridSpacingItemDecoration
import com.sample.videohub.utils.dpToPx
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.launch

class RTMActivity: AbstractActivity() {

    lateinit var binding:ActivityGroupVideoChatBinding

    var room: String? = null
    var isHostUser: Boolean = false

    lateinit var audienceAdapter : GroupieAdapter
    val viewModel: RTMViewModel by viewModels()

    private lateinit var fabScreenShare: FloatingActionButton
    // isScreenShareEnabled is a cold Flow (no .value) — addItem()'s onClick
    // is registered once, so it needs a synchronously readable mirror kept
    // up to date by screenShareObserver()'s collector.
    private var screenShareEnabled = false


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

        binding.appbar.imgBack.setOnClickListener { finishVideoChat() }
        onBackPressedDispatcher.addCallback {
            if (binding.fabMenu.isExpanded) {
                binding.fabMenu.collapse()
            } else {
                finishVideoChat()
            }
        }

        setupFabMenu()

        requestNeededPermissions {
            if (isHost == "host") {
                room?.let {
                    startRoomAsHost(it)
                }
            } else {
                joinAsGuest(room ?: "room111")
            }
        }

        initAdapter()

    }

    /***************************
     * expandable fab menu *
     ****************************/

    private fun setupFabMenu() {
        binding.fabMenu.addItem(R.drawable.baseline_person_add_24, "Invite") {

            Toast.makeText(this, "Room ID copied — share it to invite", Toast.LENGTH_SHORT).show()
        }

        binding.fabMenu.addItem(R.drawable.baseline_poll_24, "Poll") {
            viewModel.poll()
//            Toast.makeText(this, "Polls", Toast.LENGTH_SHORT).show()
        }

        binding.fabMenu.addItem(R.drawable.baseline_chat_24, "Chat") {
            startActivity(
                Intent(RTMActivity@this,
                    RTMChatActivity::class.java))
        }

        fabScreenShare = binding.fabMenu.addItem(R.drawable.baseline_cast_24, "Screen Share") {
            if (screenShareEnabled) {
                stopScreenShare()
            } else {
                requestMediaProjection()
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

            launch {
//                localParticipant()
                viewModel.participantsObserver().collect { items ->
                    audienceAdapter.update(items)
                }
            }

            launch {
                screenShareObserver()
            }

            launch {
                viewModel.disconnectListener.state.collect {
                    if(it==true) { finishVideoChat() }
                }
            }
        }
    }

    fun localParticipant(){
        val participant = viewModel.session.local.participant
    }

    private fun joinAsGuest(room:String){
        lifecycleScope.launch {
            Log.e("tag", "connecting ")

            viewModel.joinAsGuest(room)

            launch {
//                localParticipant()
                viewModel.participantsObserver().collect { items ->
                    audienceAdapter.update(items)
                }
            }

            launch {
                screenShareObserver()
            }

            launch {
                viewModel.disconnectListener.state.collect {
                    if(it==true) { finishVideoChat() }
                }
            }
        }
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


}
