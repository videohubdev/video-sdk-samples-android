package com.sample.videohub.utils

import android.graphics.Color
import android.view.View
import androidx.core.graphics.ColorUtils
import com.sample.videohub.R
import com.sample.videohub.databinding.ItemVideoGroupBinding
import com.xwray.groupie.viewbinding.BindableItem

class VideoGroupItem(val title:String, val room:String, val pkRoom:String, val onTap: (String, String) -> Unit) : BindableItem<ItemVideoGroupBinding>() {

    val colors = listOf(
        ColorUtils.setAlphaComponent(Color.parseColor("#F44336"), 200),
        ColorUtils.setAlphaComponent(Color.parseColor("#E91E63"), 200),
        ColorUtils.setAlphaComponent(Color.parseColor("#9C27B0"), 200),
        ColorUtils.setAlphaComponent(Color.parseColor("#673AB7"), 200),
        ColorUtils.setAlphaComponent(Color.parseColor("#3F51B5"), 200),
        ColorUtils.setAlphaComponent(Color.parseColor("#2196F3"), 200),
        ColorUtils.setAlphaComponent(Color.parseColor("#009688"), 180),
        ColorUtils.setAlphaComponent(Color.parseColor("#4CAF50"), 180),
        ColorUtils.setAlphaComponent(Color.parseColor("#FF9800"), 180),
        ColorUtils.setAlphaComponent(Color.parseColor("#795548"), 180)
    )

    override fun initializeViewBinding(view: View) = ItemVideoGroupBinding.bind(view)

    override fun bind(viewBinding: ItemVideoGroupBinding, position: Int) {

        viewBinding.title.text = "$title"
        viewBinding.textRoom.text = "$room"
        viewBinding.textPkRoom.text = "$pkRoom"
        viewBinding.card.setCardBackgroundColor(colors.random())
        viewBinding.root.setOnClickListener {
//            if(text.isEmpty()){
//                return@setOnClickListener
//            }
            onTap.invoke(room, pkRoom)

        }
    }

    override fun getLayout() = R.layout.item_video_group

}
