package com.sample.videohub.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.View.generateViewId
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.videohub.utils.VideoHubConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


// this.launchActivity<SentTextMessageActivity>(Bundle().apply { putString("key", "host") })
inline fun <reified T : Activity> Context.launchActivity(
    extras: Bundle? = null,
) {
    val intent = Intent(this, T::class.java)
    extras?.let { intent.putExtras(it) }
    startActivity(intent)
}

fun generateRandomText(length: Int = 10): String {
    val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    return (1..length)
        .map { charset.random() }
        .joinToString("")
}

fun Context.dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density).toInt()
}

fun Context.showCustomInputDialog(
    title: String = "Enter Details",

    firstHint: String? = null,
    firstPrefill: String = "",

    secondHint: String? = null,
    secondPrefill: String = "",

    radioOptions: List<String> = emptyList(),
    selectedRadioIndex: Int = 0,
    radioOrientation: Int = RadioGroup.HORIZONTAL,

    onOkClick: (
        firstValue: String?,
        secondValue: String?,
        selectedRadio: String?
    ) -> Unit
) {

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 32, 48, 8)
    }

    var selectedRadio: String? = null

    // Radio buttons
    if (radioOptions.isNotEmpty()) {

        val radioGroup = RadioGroup(this).apply {
            orientation = radioOrientation
        }

        val buttons = mutableListOf<RadioButton>()
        // Text colors
       val textColors = ColorStateList(
           arrayOf(
               intArrayOf(android.R.attr.state_checked),
               intArrayOf()
           ),
           intArrayOf(
               Color.WHITE,      // checked text color
               Color.LTGRAY      // unchecked text color
           )
       )

       // Radio circle colors
       val buttonColors = ColorStateList(
           arrayOf(
               intArrayOf(android.R.attr.state_checked),
               intArrayOf()
           ),
           intArrayOf(
               Color.parseColor("#4CAF50"), // selected radio color
               Color.GRAY                   // unselected radio color
           )
       )
        radioOptions.forEachIndexed { index, option ->

            val rb = RadioButton(this).apply {
                text = option
                textSize = 16f
                id = generateViewId()
                setTextColor(textColors)
                buttonTintList = buttonColors
            }

            buttons.add(rb)
            radioGroup.addView(rb)

            if (index == selectedRadioIndex) {
                rb.isChecked = true
                selectedRadio = option
            }
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedRadio = buttons
                .firstOrNull { it.id == checkedId }
                ?.text
                ?.toString()
        }

        container.addView(radioGroup)
    }

    var firstLayout: TextInputLayout? = null
    var firstEdit: TextInputEditText? = null

    if (firstHint != null) {
        // First field
        firstLayout = TextInputLayout(this).apply {
            hint = firstHint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            defaultHintTextColor = ColorStateList.valueOf(Color.LTGRAY)
            boxStrokeColor = Color.WHITE
        }

        firstEdit = TextInputEditText(this).apply {
            setText(firstPrefill)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }

        firstLayout.addView(firstEdit)

        container.addView(
            firstLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        )
    }

    // Second field
    var secondEdit: TextInputEditText? = null

    if (secondHint != null) {

        val secondLayout = TextInputLayout(this).apply {
            hint = secondHint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            defaultHintTextColor = ColorStateList.valueOf(Color.LTGRAY)
            boxStrokeColor = Color.WHITE
        }

        secondEdit = TextInputEditText(this).apply {
            setText(secondPrefill)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }

        secondLayout.addView(secondEdit)

        container.addView(
            secondLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        )
    }

    val titleView = TextView(this).apply {
        text = title
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(60, 40, 60, 20)
    }


    val dialog = MaterialAlertDialogBuilder(this)
        .setCustomTitle(titleView)
        .setView(container)
        .setPositiveButton("Save", null)
        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        .show()

    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setTextColor(Color.GRAY)

    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        .setTextColor(Color.GRAY)

    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {

        val firstValue = firstEdit?.text?.toString()?.trim()
        val secondValue = secondEdit?.text?.toString()?.trim()

        if (firstValue?.isEmpty() == true) {
            firstLayout?.error = "Required"
            return@setOnClickListener
        }

        firstLayout?.error = null

        onOkClick(
            firstValue,
            secondValue,
            selectedRadio
        )

        dialog.dismiss()
    }

    val color = ContextCompat.getColor(this, com.sample.videohub.R.color.deep_navy_bg_02)

    dialog.window?.setBackgroundDrawable(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f

            // Alpha: 0 = fully transparent, 255 = fully opaque
            setColor(ColorUtils.setAlphaComponent(color, 220))
        }
    )
}


object EdgeSnapDragHelper {

    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        view: View,
        margin: Float = 24f,
        snapDuration: Long = 280L
    ) {

        var dX = 0f
        var dY = 0f

        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { v, event ->

            val parent = v.parent as? ViewGroup
                ?: return@setOnTouchListener false

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    dX = v.x - event.rawX
                    dY = v.y - event.rawY

                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    v.animate().cancel()

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val newX = event.rawX + dX
                    val newY = event.rawY + dY

                    val minX = margin
                    val minY = margin

                    val maxX = parent.width - v.width - margin
                    val maxY = parent.height - v.height - margin

                    v.x = min(max(minX, newX), maxX)
                    v.y = min(max(minY, newY), maxY)

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    val parentWidth = parent.width
                    val parentHeight = parent.height

                    val centerX = v.x + (v.width / 2f)
                    val centerY = v.y + (v.height / 2f)

                    val leftDistance = centerX
                    val rightDistance = parentWidth - centerX

                    val topDistance = centerY
                    val bottomDistance = parentHeight - centerY

                    val minDistance = minOf(
                        leftDistance,
                        rightDistance,
                        topDistance,
                        bottomDistance
                    )

                    val targetX: Float
                    val targetY: Float

                    when (minDistance) {

                        leftDistance -> {
                            targetX = margin
                            targetY = v.y
                        }

                        rightDistance -> {
                            targetX = parentWidth - v.width - margin
                            targetY = v.y
                        }

                        topDistance -> {
                            targetX = v.x
                            targetY = margin
                        }

                        else -> {
                            targetX = v.x
                            targetY = parentHeight - v.height - margin
                        }
                    }

                    v.animate()
                        .x(targetX)
                        .y(targetY)
                        .setDuration(snapDuration)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()

                    val clickThreshold = 10

                    val isClick =
                        abs(event.rawX - initialTouchX) < clickThreshold &&
                                abs(event.rawY - initialTouchY) < clickThreshold

                    isClick
                }

                else -> false
            }
        }
    }
}


val config1 = VideoHubConfig(
    api_key = "vhub_live_S_-7Pr9AZ0XjAri1ardkVSyiVKFELlmr6HOsxN69hV0=",
    app_id = "89a59440-41ce-490a-851b-fa0a01ad1768",
    app_secret = "4XiEGhQW9gT_9BQsjOxaPkUg_Tg8Tv4xyWrX7Fw6HII=",
    app_identifier = "realtimechat.com",
    app_name = "RealTimeChat",
    domain = "client.bindaslive.com",
    username = "demo@gmail.com",
    password = "123456789"
)

// initialise VideoHub object
// initialise once, access every where as sdk = VideoHubSDK.videoHub
val config = VideoHubConfig(
    api_key = "vhub_live_XdX460eFguhgxy2ZewV2b4yGaLWJU--OZtLarzaZo6Y=",
    app_id = "5b95205d-2153-4972-8f7a-b9700176cb66",
    app_secret = "c3q5JN1dYixF9GhlM4yphUae2tByS56AQ2OY1t1X6GY=",
    app_identifier = "livechat.com",
    app_platform = "web",
    app_name = "Live Chat",
    domain = "livechat.com",
    username = "saurabh2.letscms@gmail.com",
    password = "Letscms@2018",
    debug = true
)


fun consoleLog(vararg message:String){
    Log.e("LOG", message.joinToString(" | "))
}
