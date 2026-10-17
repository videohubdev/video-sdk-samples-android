package com.sample.videohub.video

import android.view.View
import com.sample.videohub.R
import com.sample.videohub.databinding.ItemParticipantBinding
import com.sample.videohub.rtm.RTMObject
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import dev.videohub.android.room.participant.ConnectionQuality
import dev.videohub.android.room.participant.Participant
import dev.videohub.android.room.participant.RemoteParticipant
import dev.videohub.android.room.track.CameraPosition
import dev.videohub.android.room.track.LocalVideoTrack
import dev.videohub.android.room.track.Track
import dev.videohub.android.room.track.VideoTrack
import dev.videohub.android.util.flow
import dev.videohub.core.RoomSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class StreamItem(
    private val session: RoomSession,
    private val participant: Participant,
    val source: Track.Source,           // CAMERA or SCREEN_SHARE — exposed for span lookup
    private val isHost: Boolean = false, // gates img_mute/img_kick on remote rows
) : BindableItem<ItemParticipantBinding>() {

    private var boundVideoTrack: VideoTrack? = null
    private var coroutineScope: CoroutineScope? = null

    override fun getId(): Long =
        "${participant.sid.value}_${source.name}".hashCode().toLong()

    override fun initializeViewBinding(view: View): ItemParticipantBinding {
        val binding = ItemParticipantBinding.bind(view)
        session.initVideoRenderer(binding.renderer)
        return binding
    }

    private fun ensureScope() {
        if (coroutineScope == null)
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun bind(viewBinding: ItemParticipantBinding, position: Int) {
        ensureScope()

        // ── Label ────────────────────────────────────────────────────────────
        coroutineScope?.launch {
            participant::identity.flow.collect { identity ->
                viewBinding.identityText.text = when (source) {
                    Track.Source.SCREEN_SHARE -> "🖥 ${identity?.value ?: "Unknown"}"
                    else                      -> identity?.value ?: "Unknown"
                }
            }
        }

        // ── Track publication flow for THIS source only ───────────────────
        val trackPubFlow = participant::videoTrackPublications.flow
            .flatMapLatest { flowOf(participant.getTrackPublication(source)) }

        // ── Render video ─────────────────────────────────────────────────────
        coroutineScope?.launch {
            trackPubFlow
                .flatMapLatestOrNull { pub -> pub::track.flow }
                .collectLatest { track ->
                    setupVideoIfNeeded(track as? VideoTrack, viewBinding)
                }
        }

        // ── Mute / visibility ────────────────────────────────────────────────
        coroutineScope?.launch {
            trackPubFlow
                .flatMapLatestOrNull { pub -> pub::muted.flow }
                .collectLatest { muted ->
                    viewBinding.renderer.visibleOrInvisible(!(muted ?: true))
                }
        }

        // ── Camera-only UI ───────────────────────────────────────────────────
        if (source == Track.Source.CAMERA) {
            bindCameraExtras(viewBinding, trackPubFlow)
        } else {
            hideCameraExtras(viewBinding)
        }
    }

    private fun bindCameraExtras(
        viewBinding: ItemParticipantBinding,
        trackPubFlow: Flow<dev.videohub.android.room.track.TrackPublication?>,
    ) {
        // Speaking indicator
        coroutineScope?.launch {
            participant::isSpeaking.flow.collect { speaking ->
                viewBinding.speakingIndicator.visibility =
                    if (speaking) View.VISIBLE else View.INVISIBLE
            }
        }

        // Mic mute badge
        coroutineScope?.launch {
            participant::isMicrophoneEnabled.flow.collect { enabled ->
                viewBinding.muteIndicator.visibility =
                    if (enabled) View.VISIBLE else View.INVISIBLE
            }
        }

        // Connection quality badge
        coroutineScope?.launch {
            participant::connectionQuality.flow.collect { quality ->
                viewBinding.connectionQuality.visibility =
                    if (quality == ConnectionQuality.POOR) View.VISIBLE else View.INVISIBLE
            }
        }

        // Local participant extras: mirror + switch-camera button
        if (participant == session.local.participant) {
            coroutineScope?.launch {
                trackPubFlow
                    .flatMapLatestOrNull { pub -> pub::track.flow }
                    .flatMapLatestOrNull { track ->
                        ((track as? LocalVideoTrack)?.let {
                            it::options.flow
                        }) ?: flowOf(null)
                    }
                    .collectLatest { options ->
                        viewBinding.renderer.setMirror(options?.position == CameraPosition.FRONT)
                    }
            }
            viewBinding.camera.visibility = View.VISIBLE
            // Moderation controls are for someone else's row, never your own.
            viewBinding.imgMute.visibility = View.GONE
            viewBinding.imgKick.visibility = View.GONE
            viewBinding.camera.setOnClickListener { session.local.switchCamera() }

            // Own-stream video/audio toggles — img_mute/img_kick stay
            // reserved for remote-participant moderation.
            viewBinding.imgVideo.visibility = View.VISIBLE
            viewBinding.imgAudio.visibility = View.VISIBLE

            coroutineScope?.launch {
                participant::isCameraEnabled.flow.collect { enabled ->
                    viewBinding.imgVideo.setImageResource(
                        if (enabled) R.drawable.outline_videocam_24 else R.drawable.outline_videocam_off_24
                    )
                }
            }
            coroutineScope?.launch {
                participant::isMicrophoneEnabled.flow.collect { enabled ->
                    viewBinding.imgAudio.setImageResource(
                        if (enabled) R.drawable.outline_mic_24 else R.drawable.outline_mic_off_24
                    )
                }
            }

            viewBinding.imgVideo.setOnClickListener {
                coroutineScope?.launch {
                    session.local.setCameraEnabled(!participant.isCameraEnabled)
                }
            }
            viewBinding.imgAudio.setOnClickListener {
                coroutineScope?.launch {
                    session.local.setMicrophoneEnabled(!participant.isMicrophoneEnabled)
                }
            }
        } else {
            viewBinding.camera.visibility = View.GONE
            viewBinding.imgVideo.visibility = View.GONE
            viewBinding.imgAudio.visibility = View.GONE

            // Remote-moderation controls — only the host can mute/kick, and
            // only ever shown on someone else's row, never your own.
            viewBinding.imgMute.visibility = if (isHost) View.VISIBLE else View.GONE
            viewBinding.imgKick.visibility = if (isHost) View.VISIBLE else View.GONE

            if (isHost) {
                viewBinding.imgMute.setOnClickListener {
                    coroutineScope?.launch { muteRemote() }
                }
                viewBinding.imgKick.setOnClickListener {
                    coroutineScope?.launch { kickRemote() }
                }
            }
        }
    }

    // Host-only remote moderation: session.remote.moderation(...) does the
    // real server-side enforcement (REST call — actually mutes/removes the
    // target), RTMObject.rtm.moderation notifies over the signaling channel
    // in real time. Both need a RemoteParticipant specifically — `participant`
    // is typed as the base Participant since this class binds local rows
    // too, so it needs casting; only ever called from the isHost-gated,
    // remote-only branch above, where the cast always succeeds.
    private suspend fun muteRemote() {
        val remoteParticipant = participant as? RemoteParticipant ?: return
        val identity = remoteParticipant.identity?.value ?: return
        session.remote.moderation(remoteParticipant).mute(identity)
        RTMObject.rtm?.moderation?.muteUser(session.roomName, identity)
    }

    private suspend fun kickRemote() {
        val remoteParticipant = participant as? RemoteParticipant ?: return
        val identity = remoteParticipant.identity?.value ?: return
        session.remote.moderation(remoteParticipant).kick(identity)
        RTMObject.rtm?.moderation?.kickUser(session.roomName, identity)
    }

    private fun hideCameraExtras(viewBinding: ItemParticipantBinding) {
        viewBinding.speakingIndicator.visibility  = View.INVISIBLE
        viewBinding.muteIndicator.visibility      = View.INVISIBLE
        viewBinding.connectionQuality.visibility  = View.INVISIBLE
        viewBinding.camera.visibility             = View.GONE
        viewBinding.imgMute.visibility            = View.GONE
        viewBinding.imgKick.visibility            = View.GONE
        viewBinding.imgVideo.visibility           = View.GONE
        viewBinding.imgAudio.visibility           = View.GONE
    }

    private fun setupVideoIfNeeded(videoTrack: VideoTrack?, viewBinding: ItemParticipantBinding) {
        if (boundVideoTrack == videoTrack) return

        // Detach the previous track's renderer before attaching a new one.
        // Leaving it registered keeps this renderer view receiving frames
        // from whatever it was previously bound to — including across
        // RecyclerView view-holder recycling onto a *different*
        // participant's row (e.g. the whole grid tearing down and rebuilding
        // on a leave+rejoin) — which shows up as the view flickering between
        // two different videos.
        boundVideoTrack?.let { session.removeRenderer(it, viewBinding.renderer) }

        boundVideoTrack = videoTrack
        videoTrack ?: return

        session.addRenderer(videoTrack, viewBinding.renderer)
    }

    override fun getLayout() = R.layout.item_participant

    override fun unbind(viewHolder: GroupieViewHolder<ItemParticipantBinding?>) {
        coroutineScope?.cancel()
        coroutineScope = null
        super.unbind(viewHolder)
        viewHolder.binding?.let { binding ->
            boundVideoTrack?.let { session.removeRenderer(it, binding.renderer) }
        }
        boundVideoTrack = null
    }

}

// ── Helpers ───────────────────────────────────────────────────────────────────

fun View.visibleOrInvisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.INVISIBLE
}

private fun showFocus(binding: ItemParticipantBinding) {
    binding.speakingIndicator.visibility = View.VISIBLE
}

private fun hideFocus(binding: ItemParticipantBinding) {
    binding.speakingIndicator.visibility = View.INVISIBLE
}

private inline fun <T, R> Flow<T?>.flatMapLatestOrNull(
    crossinline transform: suspend (value: T) -> Flow<R>,
): Flow<R?> = flatMapLatest { if (it == null) flowOf(null) else transform(it) }
