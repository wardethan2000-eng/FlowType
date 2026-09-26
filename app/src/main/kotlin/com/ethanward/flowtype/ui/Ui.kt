package com.ethanward.flowtype.ui

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton

/** Tiny builders for the settings and developer screens (plain views, no XML). */

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** A scrolling column with room for the system bars and the keyboard. */
fun Activity.page(title: String, build: LinearLayout.() -> Unit): LinearLayout {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(24))
    }
    column.addView(TextView(this).apply {
        text = title
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        setPadding(0, 0, 0, dp(8))
    })
    column.build()
    val scroll = ScrollView(this).apply {
        addView(column)
        isFillViewport = true
    }
    ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
        insets
    }
    setContentView(scroll)
    return column
}

fun LinearLayout.heading(text: String): TextView = TextView(context).also {
    it.text = text
    it.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
    it.setPadding(0, dp(20), 0, dp(4))
    addView(it)
}

fun LinearLayout.text(text: String = ""): TextView = TextView(context).also {
    it.text = text
    it.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
    it.setPadding(0, dp(2), 0, dp(2))
    addView(it)
}

/** Selectable monospace output for logs and results. */
fun LinearLayout.mono(text: String = ""): TextView = TextView(context).also {
    it.text = text
    it.typeface = Typeface.MONOSPACE
    it.textSize = 11f
    it.setTextIsSelectable(true)
    it.setPadding(0, dp(6), 0, dp(6))
    addView(it)
}

fun LinearLayout.button(label: String, onClick: (View) -> Unit): MaterialButton =
    MaterialButton(context).also {
        it.text = label
        it.isAllCaps = false
        it.setOnClickListener(onClick)
        addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

fun LinearLayout.field(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText =
    EditText(context).also {
        it.hint = hint
        it.setText(value)
        it.inputType = type
        addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

fun LinearLayout.check(label: String, checked: Boolean): CheckBox = CheckBox(context).also {
    it.text = label
    it.isChecked = checked
    addView(it)
}

/** Buttons side by side. */
fun LinearLayout.row(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).also {
    it.orientation = LinearLayout.HORIZONTAL
    it.build()
    for (i in 0 until it.childCount) {
        it.getChildAt(i).layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (i > 0) marginStart = dp(8)
        }
    }
    addView(it)
}

private fun View.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
