package com.sample.videohub.pk

import android.os.Bundle
import android.util.Log
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.TransitionManager
import com.sample.videohub.AbstractActivity
import com.sample.videohub.R
import com.sample.videohub.databinding.ActivityPkBinding
import com.sample.videohub.utils.remoteVideoTracksFlow
import com.sample.videohub.utils.showCustomInputDialog
import com.sample.videohub.utils.singleVideoTrackFlow
import dev.videohub.android.room.track.VideoTrack
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PkActivity : AbstractActivity() {

    lateinit var binding: ActivityPkBinding
    val viewmodel: PkViewModel by viewModels()


    var remoteRenderInitialize: Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }


        val isHost = intent.extras?.getString("as")
        val room = intent.extras?.getString("room")
        val roomPK = intent.extras?.getString("roomPK")

        requestNeededPermissions {
            if (isHost == "host") {
                room?.let {
                    startRoomAsHost(room)
                    roomPK?.let { it1 -> startPk(it1) }
                }
            } else {
                joinAsGuest(room ?: "room111")
            }
        }

        binding.llPk.setOnClickListener {
            if (viewmodel.sessionPK!=null){
                stopPk(true)
                return@setOnClickListener
            }
//            startJoinPk()
        }

        binding.imgBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnCloseRemote.setOnClickListener {
//            sendPkRoom("remove")
        }

        binding.imgCamera.setOnClickListener {
           viewmodel.switchCam()
        }

        onBackPressedDispatcher.addCallback {
            leaveRoom()
        }

        showLocalFullScreen()
    }

    /// view constraint animation
    private fun showLocalFullScreen() {

        val set = ConstraintSet()
        set.clone(binding.main)

        // LOCAL FULL SCREEN
        set.constrainWidth(binding.rendererLocal.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(binding.rendererLocal.id, ConstraintSet.MATCH_CONSTRAINT)

        set.connect(
            binding.rendererLocal.id,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP
        )

        set.connect(
            binding.rendererLocal.id,
            ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID,
            ConstraintSet.BOTTOM
        )

        set.connect(
            binding.rendererLocal.id,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START
        )

        set.connect(
            binding.rendererLocal.id,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END
        )

        set.setDimensionRatio(binding.rendererLocal.id, null)

        // HIDE REMOTE
        set.constrainWidth(binding.rendererRemote.id, 0)
        set.constrainHeight(binding.rendererRemote.id, 0)

        TransitionManager.beginDelayedTransition(binding.main)

        set.applyTo(binding.main)
    }

    private fun showPkMode() {

        val set = ConstraintSet()
        set.clone(binding.main)

        // LOCAL
        set.constrainWidth(binding.rendererLocal.id, 0)
        set.constrainHeight(binding.rendererLocal.id, 0)

        set.connect(
            binding.rendererLocal.id,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP
        )

        set.connect(
            binding.rendererLocal.id,
            ConstraintSet.START,
            ConstraintSet.PARENT_ID,
            ConstraintSet.START
        )

        set.connect(
            binding.rendererLocal.id,
            ConstraintSet.END,
            binding.rendererRemote.id,
            ConstraintSet.START
        )

        set.clear(binding.rendererLocal.id, ConstraintSet.BOTTOM)

        set.setDimensionRatio(binding.rendererLocal.id, "9:16")

        // REMOTE
        set.constrainWidth(binding.rendererRemote.id, 0)
        set.constrainHeight(binding.rendererRemote.id, 0)

        set.connect(
            binding.rendererRemote.id,
            ConstraintSet.TOP,
            ConstraintSet.PARENT_ID,
            ConstraintSet.TOP
        )

        set.connect(
            binding.rendererRemote.id,
            ConstraintSet.START,
            binding.rendererLocal.id,
            ConstraintSet.END
        )

        set.connect(
            binding.rendererRemote.id,
            ConstraintSet.END,
            ConstraintSet.PARENT_ID,
            ConstraintSet.END
        )

        set.setDimensionRatio(binding.rendererRemote.id, "9:16")

        TransitionManager.beginDelayedTransition(binding.main)

        set.applyTo(binding.main)
    }

    private fun pkText(isPk: Boolean){
        binding.txtPk.text = if(isPk) "remove PK" else "add PK"
        binding.imgMore.setImageResource(if(isPk) R.drawable.outline_check_indeterminate_small_24 else R.drawable.outline_add_24)
    }

    fun leaveRoom() {
        lifecycleScope.launch {

//            if (::session.isInitialized) {
//                Log.e("Group", "------ back")
//                sessionPK?.let { sdk.leaveRoom(it) }
//                session.messaging.unregisterTextStreamHandler(pkRoomTopic)
//                sdk.leaveRoom(session)
//
//            }

            finish()
        }
    }

    private fun startRoomAsHost(room:String){
        lifecycleScope.launch {
            Log.e("tag", "connecting ")
           viewmodel.startRoomAsHost(room)
            viewmodel.initRenderer(binding.rendererLocal)
            setupLocalVideoTrack()
        }
    }


    private var boundVideoTrackLocal: VideoTrack? = null
    private var boundVideoTrackRemote: VideoTrack? = null

    fun setupLocalVideoTrack(){
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {

                viewmodel.session.local.participant.singleVideoTrackFlow().collectLatest { track ->
                    if (boundVideoTrackLocal == track) return@collectLatest

                    boundVideoTrackLocal?.removeRenderer(binding.rendererLocal)

                    boundVideoTrackLocal = track

                    track?.addRenderer(binding.rendererLocal)
                }
            }
        }
    }

    private fun joinAsGuest(room:String){
        lifecycleScope.launch {
            viewmodel.joinAsGuest(room)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /*
     * --------- PK ------- Mode ---------
     */

    fun remoteParticipants(){
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {

                viewmodel.sessionPK?.remote?.participants?.remoteVideoTracksFlow()?.collectLatest { tracks ->
                    val videoTrack = tracks.firstOrNull()

                    // use videoTrack here
                    if (boundVideoTrackRemote == videoTrack) return@collectLatest

                    boundVideoTrackRemote?.removeRenderer(binding.rendererRemote)

                    boundVideoTrackRemote = videoTrack

                    videoTrack?.addRenderer(binding.rendererRemote)

                    //UI change
                    showPkMode()
                    pkText(true)

                }
            }
        }
    }

    fun startJoinPk(){
        this.showCustomInputDialog(
            "Enter pk room name",
            "Room Name"
        ) { pkRoom, _, _ ->
            pkRoom?.let {
                if (pkRoom.isEmpty()) {
                    return@showCustomInputDialog
                }
                startPk(pkRoom)
            }
        }
    }

    private fun startPk(room:String, isHost: Boolean = true){

        lifecycleScope.launch {
            Log.e("tag", "connecting ")
            viewmodel.startPk(room, isHost)

            if (!remoteRenderInitialize) {
                viewmodel.initRemoteRenderer(binding.rendererRemote)
                remoteRenderInitialize = true
            }

            remoteParticipants()
        }
    }

    private fun stopPk(isHost: Boolean){
        lifecycleScope.launch {
//            sessionPK?.let {
//                if(isHost) {
//                    sendPkRoom("remove")
//                    delay(500)
//                }
//                it.local.unpublishVideoStream()
//                boundVideoTrackRemote?.removeRenderer(binding.rendererRemote)
//                boundVideoTrackRemote = null
//
//                sdk.leaveRoom(it)
//
//                showLocalFullScreen()
//            }
//
//            sessionPK = null
        }
    }

    /* inform remote room to subscribe pk room*/
    fun sendPkRoom(action:String = "add"){
//        val roomLocal = session.roomName
//        val json = JSONObject().apply {
//            put("action", action)
//            put("room", roomLocal)
//        }
//        lifecycleScope.launch {
//            sessionPK?.messaging?.send(pkRoomTopic, json.toString())
//        }


    }



}
