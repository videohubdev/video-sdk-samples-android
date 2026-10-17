package com.sample.videohub

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.sample.videohub.audio.FragmentAudio
import com.sample.videohub.call.CallFragment
import com.sample.videohub.databinding.MainActivityBinding
import com.sample.videohub.pk.PkFragment
import com.sample.videohub.video.VideoFragment
import com.sample.videohub.background.BackgroundFragment
import com.sample.videohub.utils.config
import com.google.android.material.tabs.TabLayoutMediator
import com.sample.videohub.rtm.RTMFragment
import dev.videohub.VideoHubSDK
import kotlinx.coroutines.launch
import kotlin.getValue

class MainActivity : AppCompatActivity() {

    lateinit var binding: MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        VideoHubSDK.create(this, config)

        val viewPager = binding.viewPager
        val tabLayout = binding.tabLayout

        val adapter = MainPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> TabType.VIDEO.title
                1 -> TabType.AUDIO.title
                2 -> TabType.CALL.title
                3 -> TabType.RTM.title
                4 -> TabType.Background.title
//                5 -> TabType.Chat.title

                else -> ""
            }
        }.attach()

        lifecycleScope.launch {
        }

        binding.button.setOnClickListener { }
        binding.button.visibility = GONE

    }



    override fun onDestroy() {
//        RTMObject.disconnect()
        super.onDestroy()

    }
}

enum class TabType(val title: String) {
    VIDEO("Video"),
//    PK("PK"),
    AUDIO("Audio"),
    CALL("Call"),
    RTM("Rtm"),
    Background("Background"),
//    Chat("Chat")
}

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = TabType.entries.size

    override fun createFragment(position: Int): Fragment {
        return when (TabType.entries[position]) {
            TabType.VIDEO -> VideoFragment()
            TabType.AUDIO -> FragmentAudio()
//            TabType.PK -> PkFragment()
            TabType.CALL -> CallFragment()
            TabType.RTM -> RTMFragment()
            TabType.Background -> BackgroundFragment()
//            TabType.Chat -> ChatFragment()
        }
    }
}

class TestFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).apply {
            addView(TextView(context).apply {
                text = "🎥 Test Module"
                textSize = 20f
            })
        }
    }
}
