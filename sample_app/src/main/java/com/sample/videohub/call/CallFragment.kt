package com.sample.videohub.call

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.sample.videohub.utils.VideoGroupItem
import com.sample.videohub.databinding.FragmentCallBinding
import com.sample.videohub.utils.GridSpacingItemDecoration
import com.sample.videohub.utils.dpToPx
import com.sample.videohub.utils.generateRandomText
import com.sample.videohub.utils.launchActivity
import com.sample.videohub.utils.showCustomInputDialog
import com.xwray.groupie.GroupieAdapter


class CallFragment: Fragment() {

    lateinit var binding: FragmentCallBinding
    private val videoGroupItems = mutableMapOf<String, VideoGroupItem?>()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentCallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.floatButton.setOnClickListener {
            context?.showCustomInputDialog(
                radioOptions = listOf("caller", "callee")
            ){ _, _,  option ->

                if ("caller" == option){

                    val caller = "Caller-${generateRandomText()}"
                    val callee = "Callee-${generateRandomText()}"
                    context?.launchActivity<CallActivity>(Bundle().apply {
                        putString("as", option)
                        putString("caller", caller)
                        putString("callee", callee)})

                } else {
                    context?.showCustomInputDialog (
                        "Join Call",
                        "Enter call Id",
                        ""
                    ) { caller, callee, _ ->

                        if (caller?.isEmpty()==true) return@showCustomInputDialog
//                        if (callee?.isEmpty()==true) return@showCustomInputDialog

                        context?.launchActivity<CallActivity>(Bundle().apply {
                            putString("as", option)
                            putString("caller", caller)
                            putString("callee", callee)})
                    }
                }
            }

        }

        val audienceAdapter = GroupieAdapter()
        binding.participants.apply {
            layoutManager = GridLayoutManager(context, 2)
            if (itemDecorationCount == 0) {
                addItemDecoration(
                    GridSpacingItemDecoration(
                        spanCount = 2,
                        spacing = context.dpToPx(12),
                        includeEdge = true
                    )
                )
            }
            adapter = audienceAdapter
        }


    }



    fun addAdapterItem(audienceAdapter: GroupieAdapter, id:String, room:String){
        val item = VideoGroupItem("Join Video Chat Group\n$id", room, "") { room1, pk ->
            goToCallActivity(room)
        }
        videoGroupItems[room] = item
        audienceAdapter.add(item)
    }

    fun goToCallActivity(callerId:String){
        context?.launchActivity<CallActivity>(Bundle().apply {
                            putString("as", "callee")
                            putString("callId", callerId)
//                            putString("room", room)
                        })
    }


}
