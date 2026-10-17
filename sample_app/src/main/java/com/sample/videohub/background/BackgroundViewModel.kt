package com.sample.videohub.background

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.videohub.android.room.track.CameraPosition
import dev.videohub.android.room.track.LocalVideoTrack
import dev.videohub.android.room.track.LocalVideoTrackOptions
import dev.videohub.android.room.track.VideoTrack
import dev.videohub.android.room.track.video.CameraCapturerUtils
import dev.videohub.android.track.processing.video.VirtualBackgroundVideoProcessor
import dev.videohub.core.RoomSession
import com.sample.videohub.R
import com.sample.videohub.utils.StateFlowable
import com.sample.videohub.utils.generateRandomText
import com.sample.videohub.video.StreamItem
import dev.videohub.VideoHubSDK
import dev.videohub.android.VideoHubOverrides
import dev.videohub.android.room.participant.Participant
import dev.videohub.android.room.track.Track
import dev.videohub.core.VideoHubEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import livekit.org.webrtc.CameraXHelper
import livekit.org.webrtc.EglBase

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class BackgroundViewModel(application: Application) : AndroidViewModel(application) {

    val eglBase = EglBase.create()

    var session: RoomSession? = null
    val disconnectListener = StateFlowable<Boolean>()

    private val virtualBackground = (AppCompatResources.getDrawable(application, R.drawable.background_image) as BitmapDrawable).bitmap

    private var blur = 16f
    private val processor = VirtualBackgroundVideoProcessor(eglBase, Dispatchers.IO, initialBlurRadius = blur).apply {
        backgroundImage = virtualBackground
    }

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null

    private var imageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                // LocalVideoTrack has default aspect ratio 16:9 VideoPreset169.H720
                // ImageAnalysis of CameraX has default aspect ratio 4:3
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build(),
        )
        .build()
        .apply { setAnalyzer(Dispatchers.IO.asExecutor(), processor.imageAnalyzer) }

    init {
        CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get(), arrayOf(imageAnalysis)).let {
            if (it.isSupported(application)) {
                CameraCapturerUtils.registerCameraProvider(it)
                cameraProvider = it
            }
        }
    }

    val track = MutableLiveData<LocalVideoTrack?>(null)

    fun localParticipant(): Participant? {
        val participant = session?.local?.participant
        return participant
    }

    suspend fun startCapture(room:String) {

            session = VideoHubSDK.videoHub
                .join(room, overrides = VideoHubOverrides(eglBase = eglBase))

            val videoTrack = session!!.local.participant.createVideoTrack(
                options = LocalVideoTrackOptions(position = CameraPosition.FRONT),
                videoProcessor = processor,
            )

        videoTrack.startCapture()

        // createVideoTrack() only builds the track and wires the
        // virtual-background processor into it — startCapture() makes it
        // render locally, but a viewer never sees it until it's actually
        // published. Local.publishVideoStream() can't be used here since it
        // creates its own default camera track with no way to attach our
        // processor, so this goes straight to the raw LocalParticipant.
        // Mic audio has no processor involved, so the normal
        // setMicrophoneEnabled() path (create + publish a default mic
        // track) is fine as-is.
        session!!.local.participant.publishVideoTrack(videoTrack)
        session!!.local.setMicrophoneEnabled(true)

        track.postValue(videoTrack)

        viewModelScope.launch {
            observeEvents()
        }
    }


    suspend fun joinStream(room:String){
        VideoHubSDK.videoHub.leaveRoom(room)
        session = VideoHubSDK.videoHub.connect(roomName = room)
        viewModelScope.launch {
            observeEvents()
        }
    }

    // The host's video is published as Track.Source.CAMERA on their
    // LocalParticipant (see startCapture()), so it shows up like any other
    // remote camera track — a viewer just needs to render whichever one
    // shows up first, since this screen only ever has a single broadcaster.
    fun remoteVideoTracks(): Flow<List<VideoTrack>> =
        session?.remote?.videoTracks() ?: flowOf(emptyList())

    override fun onCleared() {
        super.onCleared()
        track.value?.stopCapture()
        processor.dispose()
        cameraProvider?.let {
            CameraCapturerUtils.unregisterCameraProvider(it)
        }
    }

    suspend fun observeEvents(){
        session!!.observeEvents().collect {
            Log.i("TAG", "startRoom: ${it} >> ")

            when (it) {

                is VideoHubEvent.Disconnected -> {
                    disconnectListener.emit(true)
                }

                else -> {
                    Log.i("TAG", "startRoom: $it")
                }

            }

            Log.e("tag", "event $it")
        }
    }

    fun toggleProcessor(): Boolean {
        val newState = !processor.enabled
        processor.enabled = newState
        return newState
    }

    fun decreaseBlur() {
        blur -= 5
        processor.updateBlurRadius(blur)
    }

    fun increaseBlur() {
        blur += 5
        processor.updateBlurRadius(blur)
    }

    /** Swap the virtual background at runtime — pass null to fall back to blur-only. */
    fun setBackgroundImage(bitmap: Bitmap?) {
        processor.backgroundImage = bitmap
    }

    /** The bundled preset image, for callers offering a "Default" background option. */
    fun defaultBackgroundImage(): Bitmap = virtualBackground

    fun flipCamera() {
        val videoTrack = track.value ?: return
        val newPosition = when (videoTrack.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> CameraPosition.FRONT
        }
        videoTrack.switchCamera(position = newPosition)
    }

    suspend fun deleteRoom(isHost: Boolean){
        if(isHost){
            session?.let { VideoHubSDK.videoHub.clearRoom(it.roomName) }
        }
    }

    fun disconnect(){
        session?.let { VideoHubSDK.videoHub.leaveRoom(it) }
    }

    fun resume(){
            session?.resume()
    }

    fun pause(){
            session?.pause()
    }


}
