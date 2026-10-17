package com.sample.videohub.audio

import android.Manifest
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.sample.videohub.AbstractActivity
import com.sample.videohub.R
import com.sample.videohub.chat.ChatFragment
import com.sample.videohub.databinding.ActivityAudioChatBinding
import com.sample.videohub.rtm.RTMObject
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.launch

class AudioChatActivity : AbstractActivity() {

    lateinit var binding: ActivityAudioChatBinding

    val viewModel: AudioViewModel by viewModels()

    lateinit var audienceAdapter: GroupieAdapter
    private lateinit var fabAudioDevice: FloatingActionButton
    private lateinit var fabNoiseCancellation: FloatingActionButton

    var isHostUser: Boolean = false
    var room: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAudioChatBinding.inflate(layoutInflater)
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

        // Audio-only room — never ask for CAMERA, or a user who declines it
        // would be blocked from an audio call that never touches the camera.
        requestNeededPermissions(listOf(Manifest.permission.RECORD_AUDIO)) {
            if (isHost == "host") {
                room?.let { startRoomAsHost(it) }
            } else {
                joinAsGuest(room ?: "room111")
            }
        }

        onBackPressedDispatcher.addCallback {
            finishAudioChat()
        }

        binding.appbar.imgBack.setOnClickListener {
            finishAudioChat()
        }

        setupAudioDeviceMenu()
        setupNoiseCancellationToggle()
    }

    // Mic switching + speaker selection — Android ties call input and output
    // to the same physical device (built-in / wired headset / Bluetooth
    // headset), so this single AudioSwitch-backed picker covers both.
    private fun setupAudioDeviceMenu() {
        // PopupMenu needs an anchor view; addItem() only returns the FAB
        // after creating it, so onClick reads this field rather than a
        // (not-yet-existent) lambda parameter.
        fabAudioDevice = binding.fabMenu.addItem(R.drawable.volume_up_48px, "Audio Device") {
            showAudioDeviceMenu()
        }
    }

    private fun showAudioDeviceMenu() {
        val devices = viewModel.availableAudioDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "No audio devices found", Toast.LENGTH_SHORT).show()
            return
        }

        PopupMenu(this, fabAudioDevice).apply {
            devices.forEachIndexed { index, device ->
                menu.add(0, index, index, device.name)
            }
            setOnMenuItemClickListener { item ->
                viewModel.selectAudioDevice(devices[item.itemId])
                true
            }
            show()
        }
    }

    // Toggles noise suppression + echo cancellation. This restarts the local
    // mic track under the hood (native WebRTC constraints can't change on a
    // live track), so the FAB tint reflects the new state once the restart
    // resolves rather than assuming it immediately.
    private fun setupNoiseCancellationToggle() {
        fabNoiseCancellation = binding.fabMenu.addItem(R.drawable.voice_wave_24, "Noise Cancellation") {
            lifecycleScope.launch {
                val enabled = viewModel.toggleNoiseAndEchoCancellation()
                updateNoiseCancellationTint(enabled)
                Toast.makeText(
                    this@AudioChatActivity,
                    if (enabled) "Noise & echo cancellation on" else "Noise & echo cancellation off",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        updateNoiseCancellationTint(enabled = false) // matches AudioViewModel's initial join-time default
    }

    private fun updateNoiseCancellationTint(enabled: Boolean) {
        val color = if (enabled) 0xFF4CAF50.toInt() else 0xFF6B7280.toInt()
        fabNoiseCancellation.backgroundTintList = ColorStateList.valueOf(color)
    }

    // Chat lives inline in the bottom half instead of a separate
    // RTMChatActivity — showCloseButton = false since there's nothing to
    // "close" in a panel that's always half the screen (and its default
    // close action would finish this whole Activity, ending the call).
    //
    // Attached only after join()/joinAsGuest() completes, not from
    // onCreate(): RTMObject.rtm is null until that finishes, and
    // ChatFragment wires its RTM listeners as soon as its view is created —
    // attaching any earlier would register them against a null rtm and chat
    // would silently never receive anything.
    private fun attachChatFragment() {
        if (supportFragmentManager.findFragmentById(R.id.chatContainer) != null) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.chatContainer, ChatFragment().apply { showCloseButton = false })
            .commit()
    }

    fun finishAudioChat() {
        lifecycleScope.launch {
            viewModel.deleteRoom(isHostUser)
            finish()
        }
    }

    fun initAdapter() {
        audienceAdapter = GroupieAdapter()
        binding.participants.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = audienceAdapter
        }
    }

    private fun startRoomAsHost(room: String) {
        lifecycleScope.launch {
            viewModel.join(room)
            attachChatFragment()

            launch {
                viewModel.participantsObserver().collect { items ->
                    audienceAdapter.update(items)
                }
            }

            launch {
                viewModel.disconnectListener.state.collect {
                    if(it==true) { finishAudioChat() }
                }
            }
        }
    }

    private fun joinAsGuest(room: String) {
        lifecycleScope.launch {
            viewModel.joinAsGuest(room)
            attachChatFragment()

            launch {
                viewModel.participantsObserver().collect { items ->
                    audienceAdapter.update(items)
                }
            }

            launch {
                viewModel.disconnectListener.state.collect {
                    if(it==true) { finishAudioChat() }
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            viewModel.disconnect()
            RTMObject.dismiss()
        } catch (e: Exception) {
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
