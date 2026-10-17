package com.sample.videohub.rtm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sample.videohub.databinding.FragmentRtmBinding
import com.sample.videohub.utils.launchActivity
import com.sample.videohub.utils.showCustomInputDialog
import kotlin.text.isEmpty

class RTMFragment: Fragment() {

    lateinit var binding: FragmentRtmBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?
    = FragmentRtmBinding.inflate(inflater, container, false)
            .also {
                binding = it
            }.root


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.floatButton.setOnClickListener {
            context?.showCustomInputDialog(
                "Create Room",
                "Enter Room Name",
                radioOptions = listOf("host", "guest"),
            ) { room, _, option ->

                room?.let {
                    if (room.isEmpty()) return@showCustomInputDialog

                    context?.launchActivity<RTMActivity>(Bundle().apply {
                        putString("as", option)
                        putString("room", room)
                    })
                }

            }
        }


    }



}
