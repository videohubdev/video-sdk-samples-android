package com.sample.videohub.background

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.videohub.android.renderer.TextureViewRenderer
import dev.videohub.android.room.track.VideoTrack
import dev.videohub.android.util.flow
import com.sample.videohub.R
import com.sample.videohub.rtm.RTMObject
import com.sample.videohub.utils.IconTextView
import dev.videohub.android.room.track.Track
import kotlinx.coroutines.launch

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class ActivityBackground : AppCompatActivity() {

     val viewModel: BackgroundViewModel by viewModels()

    private lateinit var controls: BackgroundControlsView

    var isHostUser: Boolean = false
    var room: String? = null

    private var boundRemoteVideoTrack: VideoTrack? = null

    // Modern system Photo Picker — no storage permission needed. Returns
    // null if the user backs out without picking anything.
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult

        val bitmap = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }

        if (bitmap != null) {
            viewModel.setBackgroundImage(bitmap)
            controls.setBackgroundLabel("Background: Custom")
        } else {
            Toast.makeText(this, "Couldn't load that image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_background)

        controls = findViewById(R.id.backgroundControls)

        // Matches BackgroundViewModel's actual starting state: the processor
        // is enabled and the background defaults to the bundled image.
        controls.setProcessorEnabled(true)
        controls.setBackgroundLabel("Background: Default")

        controls.onToggleProcessor = {
            controls.setProcessorEnabled(viewModel.toggleProcessor())
        }
        controls.onBackgroundOptionSelected = { option ->
            when (option) {
                BackgroundOption.NONE -> {
                    viewModel.setBackgroundImage(null)
                    controls.setBackgroundLabel("Background: Off")
                }
                BackgroundOption.DEFAULT -> {
                    viewModel.setBackgroundImage(viewModel.defaultBackgroundImage())
                    controls.setBackgroundLabel("Background: Default")
                }
                BackgroundOption.CUSTOM -> {
                    pickImageLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
        }
        controls.onFlipCamera = {
            viewModel.flipCamera()
        }
        controls.onIncreaseBlur = {
            viewModel.increaseBlur()
        }
        controls.onDecreaseBlur = {
            viewModel.decreaseBlur()
        }

        val isHost = intent.extras?.getString("as")
        room = intent.extras?.getString("room")
        isHostUser = isHost == "host"

        if(room==null){
            finish()
        }

        val renderer = findViewById<TextureViewRenderer>(R.id.renderer)
        val imgVideo = findViewById<IconTextView>(R.id.imgVideo)
        val imgAudio = findViewById<IconTextView>(R.id.imgAudio)

        // Only the host publishes anything, so only the host has a camera/
        // mic to toggle — a viewer just watches (see joinStream()'s use of
        // VideoHub.connect(), a subscribe-only join with nothing published).
        imgVideo.visibility = if (isHostUser) View.VISIBLE else View.GONE
        imgAudio.visibility = if (isHostUser) View.VISIBLE else View.GONE

//        viewModel.track.observe(this) { track ->
//            track?.addRenderer(renderer)
//        }

        requestNeededPermissions {
            lifecycleScope.launch {
                if (isHostUser) {
                    viewModel.startCapture(room!!)
                    viewModel.session?.initVideoRenderer(renderer)
                    bindHostControls(imgVideo, imgAudio)
                }else{
                    viewModel.joinStream(room!!)
                    viewModel.session?.initVideoRenderer(renderer)
                    observeRemoteStream(renderer)
                }

                launch {
                    viewModel.disconnectListener.state.collect {
                        if(it==true) { finishVideoChat() }
                    }
                }

                launch {
                    val videoPublication = viewModel.localParticipant()
                        ?.trackPublications
                        ?.values
                        ?.firstOrNull { publication ->
                            publication.kind == Track.Kind.VIDEO
                        }

                    val videoTrack = videoPublication?.track as? VideoTrack
                    videoTrack?.addRenderer(renderer)
                }
            }
        }

        onBackPressedDispatcher.addCallback {
            finishVideoChat()
        }
    }

    // Viewer-side rendering: the host's published camera track shows up as
    // a plain remote camera track (see BackgroundViewModel.remoteVideoTracks()),
    // so this mirrors CallActivity's remoteParticipants() — detach the
    // previous track before attaching a new one so a host reconnect/republish
    // doesn't end up feeding two tracks into the same renderer at once.
    private fun observeRemoteStream(renderer: TextureViewRenderer) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.remoteVideoTracks().collect { tracks ->
                    val videoTrack = tracks.firstOrNull()
                    if (boundRemoteVideoTrack == videoTrack) return@collect

                    boundRemoteVideoTrack?.let { viewModel.session?.removeRenderer(it, renderer) }
                    boundRemoteVideoTrack = videoTrack
                    viewModel.session?.addRenderer(videoTrack, renderer)
                }
            }
        }
    }

    // Host-only camera/mic toggles for the locally published stream — same
    // reactive pattern as CallActivity.bindLocalControls(): driven off the
    // participant's actual state rather than a locally tracked boolean, so
    // it stays correct regardless of what changed it.
    private fun bindHostControls(imgVideo: IconTextView, imgAudio: IconTextView) {
        val participant = viewModel.session?.local?.participant ?: return

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                participant::isCameraEnabled.flow.collect { enabled ->
                    imgVideo.setIcon(
                        if (enabled) R.drawable.outline_videocam_24 else R.drawable.outline_videocam_off_24
                    )
                    imgVideo.setText(if (enabled) "camera: on" else "camera: off")
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                participant::isMicrophoneEnabled.flow.collect { enabled ->
                    imgAudio.setIcon(
                        if (enabled) R.drawable.outline_mic_24 else R.drawable.outline_mic_off_24
                    )
                    imgAudio.setText(if (enabled) "Mic: on" else "Mic: off")
                }
            }
        }

        imgVideo.setOnClickListener {
            lifecycleScope.launch {
                viewModel.session?.local?.setCameraEnabled(!participant.isCameraEnabled)
            }
        }
        imgAudio.setOnClickListener {
            lifecycleScope.launch {
                viewModel.session?.local?.setMicrophoneEnabled(!participant.isMicrophoneEnabled)
            }
        }
    }

    ////finish--task////

    fun finishVideoChat(){
        lifecycleScope.launch {
            viewModel.deleteRoom(isHostUser)
            finish()
        }
    }

    override fun onDestroy() {
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

fun ComponentActivity.requestNeededPermissions(onPermissionsGranted: (() -> Unit)? = null) {
    val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            // Check if any permissions weren't granted.
            for (grant in grants.entries) {
                if (!grant.value) {
                    Toast.makeText(
                        this,
                        "Missing permission: ${grant.key}",
                        Toast.LENGTH_SHORT,
                    )
                        .show()
                }
            }

            // If all granted, notify if needed.
            if (onPermissionsGranted != null && grants.all { it.value }) {
                onPermissionsGranted()
            }
        }

    val neededPermissions = listOf(Manifest.permission.CAMERA)
        .filter { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED }
        .toTypedArray()

    if (neededPermissions.isNotEmpty()) {
        requestPermissionLauncher.launch(neededPermissions)
    } else {
        onPermissionsGranted?.invoke()
    }

}
