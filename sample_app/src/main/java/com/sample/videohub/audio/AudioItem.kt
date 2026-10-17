package com.sample.videohub.audio

import android.view.View
import com.sample.videohub.R
import com.sample.videohub.databinding.ItemAudioBinding
import com.sample.videohub.rtm.RTMObject
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import dev.videohub.android.room.participant.Participant
import dev.videohub.android.room.participant.RemoteParticipant
import dev.videohub.android.util.flow
import dev.videohub.core.RoomSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AudioItem(
    private val session: RoomSession,
    private val participant: Participant,
    private val isHost: Boolean = false, // gates img_mute/img_kick on remote rows
) : BindableItem<ItemAudioBinding>() {

    private var coroutineScope: CoroutineScope? = null

    // Stable per-participant ID — one row per participant, no source split.
    override fun getId(): Long = (participant.identity?.value ?: "").hashCode().toLong()

    override fun initializeViewBinding(view: View): ItemAudioBinding =
        ItemAudioBinding.bind(view)

    override fun getLayout() = R.layout.item_audio

    override fun bind(viewBinding: ItemAudioBinding, position: Int) {
        if (coroutineScope == null) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        }

        coroutineScope?.launch {
            participant::identity.flow.collect { identity ->
                viewBinding.identityText.text = identity?.value ?: "Unknown"
            }
        }

        coroutineScope?.launch {
            participant::isSpeaking.flow.collect { speaking ->
                if (speaking) viewBinding.speakingRing.start() else viewBinding.speakingRing.stop()
            }
        }

        coroutineScope?.launch {
            participant::isMicrophoneEnabled.flow.collect { enabled ->
                viewBinding.imgAudio.setImageResource(
                    if (enabled) R.drawable.outline_mic_24 else R.drawable.outline_mic_off_24
                )
            }
        }

        if (participant == session.local.participant) {
            // Own row: img_audio is a clickable self mute/unmute toggle.
            // Moderation controls never apply to yourself.
            viewBinding.imgAudio.isClickable = true
            viewBinding.imgAudio.setOnClickListener {
                coroutineScope?.launch {
                    session.local.setMicrophoneEnabled(!participant.isMicrophoneEnabled)
                }
            }
            viewBinding.imgMute.visibility = View.GONE
            viewBinding.imgKick.visibility = View.GONE
        } else {
            // Remote row: img_audio is a passive mic-state badge, not a button.
            viewBinding.imgAudio.isClickable = false
            viewBinding.imgAudio.setOnClickListener(null)

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

    override fun unbind(viewHolder: GroupieViewHolder<ItemAudioBinding?>) {
        coroutineScope?.cancel()
        coroutineScope = null
        super.unbind(viewHolder)
    }
}
