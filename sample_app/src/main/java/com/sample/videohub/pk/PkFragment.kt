package com.sample.videohub.pk

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.sample.videohub.utils.VideoGroupItem
import com.sample.videohub.databinding.FragmentVideoBinding
import com.sample.videohub.utils.GridSpacingItemDecoration
import com.sample.videohub.utils.dpToPx
import com.sample.videohub.utils.launchActivity
import com.sample.videohub.utils.showCustomInputDialog
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PkFragment: Fragment() {

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
                "Enter a room name",
                "Room Name"
            ) { room, _, _->
                room?.let {
                    if (room.isEmpty()) {
                        return@showCustomInputDialog
                    }

                    context?.launchActivity<PkActivity>(
                        Bundle()
                            .apply {
                                putString("as", "host")
                                putString("room", room)
                            }
                    )
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



    }

    fun addAdapterItem(audienceAdapter: GroupieAdapter, id: String, room: String, pkRoomId: String){
        val item = VideoGroupItem("Join Video Chat Group\n$id", room, "") { room1, pk ->
            context?.launchActivity<PkActivity>(Bundle().apply {
                putString("as", "guest")
                putString("room", room1)
                putString("roomPK", pk)
            })
        }
        videoGroupItems[room] = item
        audienceAdapter.add(item)
    }


    fun startJoinPk(roomPk:String){
        context?.showCustomInputDialog(
            "Enter a room name",
            "Room Name"
        ) { room, _, _ ->
            room?.let {
                if (room.isEmpty()) {
                    return@showCustomInputDialog
                }

                context?.launchActivity<PkActivity>(Bundle().apply {
                    putString("as", "guest")
                    putString("room", room)
                    putString("roomPK", roomPk)
                })

            }
        }
    }

}
