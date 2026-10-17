package com.sample.videohub.background

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.button.MaterialButton
import com.sample.videohub.R

/** Options offered by the background button's picker menu. */
enum class BackgroundOption { NONE, DEFAULT, CUSTOM }

/**
 * Background-removal / blur controls for a video call screen: FX toggle,
 * a background picker (blur-only / bundled default / any photo), camera
 * flip, and blur +/-.
 *
 * Owns no ViewModel or processor reference itself — wire the on* callbacks
 * and call [setProcessorEnabled]/[setBackgroundLabel] after each change so
 * labels always reflect real state. [onBackgroundOptionSelected] only tells
 * the host which option was picked; the host owns actually loading/setting
 * the bitmap (CUSTOM needs a photo picker, which only an Activity can
 * launch) and then reports the result back via [setBackgroundLabel]. That's
 * what makes it a drop-in: any screen using VirtualBackgroundVideoProcessor
 * can add this view and wire a handful of callbacks instead of
 * re-implementing the button row.
 */
class BackgroundControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val btnToggleProcessor: MaterialButton
    private val btnBackground: MaterialButton
    private val btnFlipCamera: MaterialButton
    private val btnDecreaseBlur: MaterialButton
    private val btnIncreaseBlur: MaterialButton

    var onToggleProcessor: (() -> Unit)? = null
    var onBackgroundOptionSelected: ((BackgroundOption) -> Unit)? = null
    var onFlipCamera: (() -> Unit)? = null
    var onIncreaseBlur: (() -> Unit)? = null
    var onDecreaseBlur: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        LayoutInflater.from(context)
            .inflate(R.layout.view_background_controls, this, true)

        btnToggleProcessor = findViewById(R.id.btnToggleProcessor)
        btnBackground = findViewById(R.id.btnToggleBackground)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnDecreaseBlur = findViewById(R.id.btnDecreaseBlur)
        btnIncreaseBlur = findViewById(R.id.btnIncreaseBlur)

        btnToggleProcessor.setOnClickListener { onToggleProcessor?.invoke() }
        btnBackground.setOnClickListener { showBackgroundMenu() }
        btnFlipCamera.setOnClickListener { onFlipCamera?.invoke() }
        btnDecreaseBlur.setOnClickListener { onDecreaseBlur?.invoke() }
        btnIncreaseBlur.setOnClickListener { onIncreaseBlur?.invoke() }
    }

    private fun showBackgroundMenu() {
        PopupMenu(context, btnBackground).apply {
            menu.add(0, 0, 0, "Blur Only (No Background)")
            menu.add(0, 1, 1, "Default Background")
            menu.add(0, 2, 2, "Choose Photo…")
            setOnMenuItemClickListener { item ->
                val option = when (item.itemId) {
                    0 -> BackgroundOption.NONE
                    1 -> BackgroundOption.DEFAULT
                    else -> BackgroundOption.CUSTOM
                }
                onBackgroundOptionSelected?.invoke(option)
                true
            }
            show()
        }
    }

    fun setProcessorEnabled(enabled: Boolean) {
        btnToggleProcessor.text = if (enabled) "Disable FX" else "Enable FX"
    }

    /** e.g. "Background: Default" — reflects whichever [BackgroundOption] is actually applied. */
    fun setBackgroundLabel(text: String) {
        btnBackground.text = text
    }
}
