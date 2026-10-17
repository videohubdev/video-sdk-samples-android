package com.sample.videohub.utils

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.sample.videohub.R

class IconTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val imageView: ImageView
    private val textView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        LayoutInflater.from(context)
            .inflate(R.layout.view_icon_text, this, true)

        imageView = findViewById(R.id.ivIcon)
        textView = findViewById(R.id.tvText)

        attrs?.let {
            val ta = context.obtainStyledAttributes(
                it,
                R.styleable.IconTextView
            )

            ta.getDrawable(R.styleable.IconTextView_icon)?.let { drawable ->
                imageView.setImageDrawable(drawable)
            }

            textView.text =
                ta.getString(R.styleable.IconTextView_text) ?: ""

            ta.recycle()
        }
    }

    fun setText(text: String) {
        textView.text = text
    }

    fun getText() = textView.text

    fun setIcon(@DrawableRes drawableRes: Int) {
        imageView.setImageResource(drawableRes)
    }
}
