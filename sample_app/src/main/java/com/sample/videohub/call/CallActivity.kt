package com.sample.videohub.call

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sample.videohub.AbstractActivity
import com.sample.videohub.utils.EdgeSnapDragHelper
import com.sample.videohub.R
import com.sample.videohub.databinding.ActivityCallBinding
import dev.videohub.android.room.track.Track
import dev.videohub.android.room.track.VideoTrack
import dev.videohub.android.util.flow
import dev.videohub.core.RoomSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

inline fun ComponentActivity.launchAndRepeatWithLifecycle(
    state: Lifecycle.State = Lifecycle.State.CREATED,
    crossinline block: suspend CoroutineScope.() -> Unit
) {
    lifecycleScope.launch {
        repeatOnLifecycle(state) {
            block()
        }
    }
}

class CallActivity: AbstractActivity() {

    lateinit var binding : ActivityCallBinding
    val viewModel: CallViewModel by viewModels()
    private var boundVideoTrackRemote: VideoTrack? = null
    var isHostUser: Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val isHost = intent.extras?.getString("as")
        val caller = intent.extras?.getString("caller") ?: "caller"
        val callee = intent.extras?.getString("callee") ?: "callee"

        isHostUser = isHost == "caller"

        requestNeededPermissions {
            if (isHost == "caller") {
                startCall(caller, callee)
            } else {
                joinCall(caller)
            }
        }

        EdgeSnapDragHelper.attach(binding.rendererRemote)
        ui()

        // Camera switch / video / audio all act on the LOCAL camera+mic —
        // imgVideo/imgAudio previously called viewModel.call.videoOff(true)/
        // mute(true), which is CallImpl's REMOTE-participant REST API
        // (targets remoteParticipantId), hardcoded to `true` so it could
        // never even toggle back. bindLocalControls() (called once the
        // session exists, from startCall()/joinCall()) wires these properly.
        binding.imgCamera.setOnClickListener {
            viewModel.session?.local?.switchCamera()
        }

        // Kick / stop-remote-screen-share are legitimately REMOTE-participant
        // actions via the Call REST API (CallImpl.kick()/screenOff() act on
        // remoteParticipantId) — moderator-only, so only the call's
        // initiator (caller) gets them.
        binding.imgRemoteVideo.visibility = if (isHostUser) VISIBLE else GONE
        binding.imgRemoteMute.visibility = if (isHostUser) VISIBLE else GONE
        binding.imgRemoteScreen.visibility = if (isHostUser) VISIBLE else GONE
        binding.imgRemoteKick.visibility = if (isHostUser) VISIBLE else GONE

        // imgRemoteVideo/imgRemoteMute click wiring lives in
        // bindRemoteControls() (called once the session exists, alongside
        // bindLocalControls()) — it needs the remote participant's current
        // enabled state to actually toggle, rather than always forcing off.

        binding.imgRemoteScreen.setOnClickListener {
            launchAndRepeatWithLifecycle{
                viewModel.call.screenOff()
            }
        }
        binding.imgRemoteKick.setOnClickListener {
            launchAndRepeatWithLifecycle{
                viewModel.call.kick()
            }
        }

        onBackPressedDispatcher.addCallback {
            leaveRoom()
        }

        viewModel.mutalbleCallId.observe(this){
            binding.txtCallId.setText(it)
            binding.txtCallId.setOnClickListener {
                val text = binding.txtCallId.getText().toString()

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                val clip = ClipData.newPlainText("Copied Text", text)
                clipboard.setPrimaryClip(clip)

                Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            viewModel.onCallEnd.collect {
                if(it){
                    finish()
                }
            }
        }

//        binding.imgVideo.visibility = if(isHost=="caller") VISIBLE else GONE
    }

    fun leaveRoom(){
        lifecycleScope.launch {
            viewModel.endCall()
            finish()
        }
    }
    fun startCall(caller:String, callee:String){
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {

                viewModel.startCall(caller, callee)

                delay(500)
                viewModel.call.initVideoRenderer(binding.rendererLocal)
                viewModel.call.initVideoRenderer(binding.rendererRemote)

                // show local video track
                // Method: 1
                val track = viewModel.session?.local
                    ?.videoTrack(Track.Source.CAMERA)
//                    ?.addRenderer(binding.rendererLocal)
                viewModel.session?.addRenderer(track,binding.rendererLocal)

                bindLocalControls()
                bindRemoteControls()

                // Method:
                launch {
                    remoteParticipants()
                }
            }
        }
    }

    fun joinCall(callId:String){
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {

                viewModel.joinCall(callId)
                viewModel.session?.initVideoRenderer(binding.rendererLocal)
                viewModel.session?.initVideoRenderer(binding.rendererRemote)

                val track = viewModel.session?.local
                    ?.videoTrack(Track.Source.CAMERA)
                viewModel.session?.addRenderer(track,binding.rendererLocal)

                bindLocalControls()
                bindRemoteControls()

                launch {
                    remoteParticipants()
                }

            }
        }
    }

    // Wires imgVideo/imgAudio to the LOCAL camera/mic — see the onCreate
    // comment for why they used to call the wrong (remote-participant) API.
    // Reactive rather than flipping a local boolean in the click handler, so
    // the icon/label stay correct even if the enabled state changes from
    // elsewhere (e.g. RoomSession.pause()/resume() toggling the camera).
    private fun bindLocalControls() {
        val session = viewModel.session ?: return
        val participant = session.local.participant

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                participant::isCameraEnabled.flow.collect { enabled ->
                    binding.imgVideo.setIcon(
                        if (enabled) R.drawable.outline_videocam_24 else R.drawable.outline_videocam_off_24
                    )
                    binding.imgVideo.setText(if (enabled) "camera: on" else "camera: off")

                    // Same "video off" treatment as StreamItem's grid tiles:
                    // a videocam-off placeholder sits behind the renderer in
                    // activity_call.xml, and hiding the renderer (rather than
                    // just leaving it be) is what reveals it. clearImage()
                    // on top of that drops the last painted frame so it
                    // isn't what pops back into view the instant the camera
                    // is re-enabled, before the next frame arrives.
                    binding.rendererLocal.visibility = if (enabled) VISIBLE else INVISIBLE
                    if (!enabled) {
                        binding.rendererLocal.clearImage()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                participant::isMicrophoneEnabled.flow.collect { enabled ->
                    binding.imgAudio.setIcon(
                        if (enabled) R.drawable.outline_mic_24 else R.drawable.outline_mic_off_24
                    )
                    binding.imgAudio.setText(if (enabled) "Mic: on" else "Mic: off")
                }
            }
        }

        binding.imgVideo.setOnClickListener {
            lifecycleScope.launch {
                session.local.setCameraEnabled(!participant.isCameraEnabled)
            }
        }
        binding.imgAudio.setOnClickListener {
            lifecycleScope.launch {
                session.local.setMicrophoneEnabled(!participant.isMicrophoneEnabled)
            }
        }
    }

    // Wires imgRemoteVideo/imgRemoteMute (host-only, see onCreate's
    // visibility gating) to the REMOTE participant's actual camera/mic
    // state via Call.videoOff()/mute() — those take the state to force
    // the participant *into*, not a toggle flag, so onCreate previously
    // hardcoding `true` could only ever push video/audio off and never
    // back on. Track the latest observed enabled state and pass it
    // straight through: enabled==true means "on right now", so forcing
    // disable/mute to that same value is exactly "turn it off"; once the
    // participant reacts and the flow reports enabled==false, the next
    // click naturally flips it back on.
    private fun bindRemoteControls() {
        val session = viewModel.session ?: return

        var remoteCameraEnabled = true
        var remoteMicEnabled = true

        val firstRemoteParticipant = session.remote.participants
            .map { it.values.firstOrNull() }
            .distinctUntilChanged()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                firstRemoteParticipant
                    .flatMapLatest { participant -> participant?.let { it::isCameraEnabled.flow } ?: flowOf(true) }
                    .collect { enabled ->
                        remoteCameraEnabled = enabled
                        binding.imgRemoteVideo.setIcon(
                            if (enabled) R.drawable.outline_videocam_24 else R.drawable.outline_videocam_off_24
                        )
                        binding.imgRemoteVideo.setText(if (enabled) "Video: on" else "Video: off")
                    }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                firstRemoteParticipant
                    .flatMapLatest { participant -> participant?.let { it::isMicrophoneEnabled.flow } ?: flowOf(true) }
                    .collect { enabled ->
                        remoteMicEnabled = enabled
                        binding.imgRemoteMute.setIcon(
                            if (enabled) R.drawable.outline_mic_24 else R.drawable.outline_mic_off_24
                        )
                        binding.imgRemoteMute.setText(if (enabled) "Mic: on" else "Mic: off")
                    }
            }
        }

        binding.imgRemoteVideo.setOnClickListener {
            launchAndRepeatWithLifecycle {
                viewModel.call.videoOff(remoteCameraEnabled)
            }
        }
        binding.imgRemoteMute.setOnClickListener {
            launchAndRepeatWithLifecycle {
                viewModel.call.mute(remoteMicEnabled)
            }
        }
    }

    fun observable(){
        lifecycleScope.launch {
            viewModel.session?.observeState()?.collect {

                when(it){
                    RoomSessionState.DISCONNECTED -> {
                        Log.e("Call", "${it.name}")
                    }

                    else -> {

                    }
                }
            }
        }
    }

    fun ui(){
        binding.imgCancel.setOnClickListener {
            Log.e("TAG", "cancel call ", )

            lifecycleScope.launch {
                viewModel.endCall()
                finish()
            }
        }

    }

    fun remoteParticipants() {

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {

                viewModel.session!!.remote.videoTracks().collect { tracks ->
                    val videoTrack = tracks.firstOrNull()

                    Log.e("call", "remote track")

                    if (boundVideoTrackRemote == videoTrack) return@collect
                    boundVideoTrackRemote?.let { viewModel.session!!.removeRenderer(it, binding.rendererRemote) }

                    boundVideoTrackRemote = videoTrack
                    viewModel.session!!.addRenderer(videoTrack, binding.rendererRemote)
                }
            }
        }


    }

    override fun onDestroy() {
        lifecycleScope.launch {
            viewModel.sdk.leaveAllRooms()
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
