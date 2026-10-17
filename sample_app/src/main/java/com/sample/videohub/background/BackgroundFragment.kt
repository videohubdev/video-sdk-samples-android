package com.sample.videohub.background

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sample.videohub.databinding.FragmentBackgroundBinding
import com.sample.videohub.utils.launchActivity
import com.sample.videohub.utils.showCustomInputDialog

class BackgroundFragment: Fragment() {

    lateinit var binding: FragmentBackgroundBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentBackgroundBinding.inflate(inflater, container, false)
        binding.btnAdd.setOnClickListener{ onTap() }
        return binding.root
    }
    fun onTap(){
        context?.showCustomInputDialog(
            "Create Room",
            "Enter Room Name",
            radioOptions = listOf("host", "guest"),
        ) { room, _, option->

            if (room?.isEmpty() == true) return@showCustomInputDialog

            context?.launchActivity<ActivityBackground>(Bundle().apply {
                putString("as", option)
                putString("room", room)})

        }
    }

}
