package com.sample.videohub

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Reusable speed-dial FAB: a main button that expands into a vertical stack
 * of labeled mini-FABs added via [addItem]. Drop it into any screen's layout
 * — item content isn't hardcoded here, callers decide what goes in the menu.
 */
class ExpandableFabMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val itemsContainer: LinearLayout
    val mainFab: FloatingActionButton

    var isExpanded = false
        private set

    init {
        orientation = VERTICAL
        gravity = Gravity.END

        LayoutInflater.from(context)
            .inflate(R.layout.view_expandable_fab_menu, this, true)

        itemsContainer = findViewById(R.id.fabItemsContainer)
        mainFab = findViewById(R.id.fabMain)

        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.ExpandableFabMenu)
            ta.getResourceId(R.styleable.ExpandableFabMenu_fabMainIcon, -1)
                .takeIf { id -> id != -1 }
                ?.let { id -> mainFab.setImageResource(id) }
            ta.recycle()
        }

        mainFab.setOnClickListener { toggle() }
    }

    /**
     * Adds a labeled mini-FAB to the menu. Tapping it always collapses the
     * menu before invoking [onClick] — callers don't need to call
     * [collapse] themselves.
     */
    fun addItem(@DrawableRes iconRes: Int, label: String, onClick: () -> Unit): FloatingActionButton {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.item_expandable_fab, itemsContainer, false)

        val fab = row.findViewById<FloatingActionButton>(R.id.itemFab)
        val text = row.findViewById<TextView>(R.id.itemLabel)

        fab.setImageResource(iconRes)
        text.text = label
        row.visibility = if (isExpanded) View.VISIBLE else View.GONE

        fab.setOnClickListener {
            collapse()
            onClick()
        }

        itemsContainer.addView(row)
        return fab
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    fun expand() {
        if (isExpanded) return
        isExpanded = true
        forEachItem { it.visibility = View.VISIBLE }
        mainFab.animate().rotation(45f).setDuration(200).start()
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        forEachItem { it.visibility = View.GONE }
        mainFab.animate().rotation(0f).setDuration(200).start()
    }

    private inline fun forEachItem(action: (View) -> Unit) {
        for (i in 0 until itemsContainer.childCount) {
            action(itemsContainer.getChildAt(i))
        }
    }
}
