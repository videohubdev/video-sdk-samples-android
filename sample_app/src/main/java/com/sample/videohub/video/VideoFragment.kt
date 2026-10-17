package com.sample.videohub.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.sample.videohub.utils.VideoGroupItem
import com.sample.videohub.databinding.FragmentVideoBinding
import com.sample.videohub.utils.GridSpacingItemDecoration
import com.sample.videohub.utils.dpToPx
import com.sample.videohub.utils.launchActivity
import com.sample.videohub.utils.showCustomInputDialog
import com.xwray.groupie.GroupieAdapter

class  VideoFragment: Fragment() {

    lateinit var binding: FragmentVideoBinding

    private val videoGroupItems = mutableMapOf<String, VideoGroupItem?>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

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

                    context?.launchActivity<GroupVideoChatActivity>(Bundle().apply {
                        putString("as", option)
                        putString("room", room)
                    })
                }

            }
        }

        val audienceAdapter = GroupieAdapter()
        binding.onlineHosts.apply {
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



//        audienceAdapter.add(VideoGroupItem("Join Video Chat Group", "message") {
//            RTMObject.sendRichChat(
//                mapOf(
//                    "title" to "videoGroup",
//                    "roomId" to "group",
//                    "userId" to "dsfsdfsd",
//                    "state" to "add"
//                )
//            )
//        })


    }

    fun addAdapterItem(audienceAdapter: GroupieAdapter, id:String, room:String){
        val item = VideoGroupItem("Join Video Chat Group\n$id", room, "") { room1, pk ->
            context?.launchActivity<GroupVideoChatActivity>(Bundle().apply {
                putString("as", "guest")
                putString("room", room1)
            })
        }
        videoGroupItems[room] = item
        audienceAdapter.add(item)
    }


    override fun onDestroy() {
        super.onDestroy()
    }


}
