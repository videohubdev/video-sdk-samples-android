package com.sample.videohub.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sample.videohub.databinding.FragmentAudioBinding
import com.sample.videohub.utils.launchActivity
import com.sample.videohub.utils.showCustomInputDialog
import com.xwray.groupie.GroupieAdapter

class FragmentAudio: Fragment() {

    lateinit var binding: FragmentAudioBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?)
    = FragmentAudioBinding.inflate(inflater, container, false)
        .also {
            binding = it
        }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.onlineHosts.adapter = GroupieAdapter()

        binding.floatButton.setOnClickListener {
            context?.showCustomInputDialog(
                "Create Audio Room",
                "Enter Room Name",
                radioOptions = listOf("host", "guest"),
            ) { room, _, option ->

                room?.let {
                    if (room.isEmpty()) return@showCustomInputDialog

                    context?.launchActivity<AudioChatActivity>(Bundle().apply {
                        putString("as", option)
                        putString("room", room)
                    })
                }
            }
        }
    }

}
