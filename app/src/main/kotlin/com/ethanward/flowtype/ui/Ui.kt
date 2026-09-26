package com.ethanward.flowtype.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.ethanward.flowtype.R
import com.google.android.material.R as M
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Builders for Flowtype's screens: plain views in code, Material 3 styled, no
 * XML layouts. Each screen is a top app bar over a scrolling column of cards.
 */

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/** A screen: app bar (with a back arrow unless [up] is false) over a scrolling column. */
fun AppCompatActivity.page(title: String, up: Boolean = true, build: LinearLayout.() -> Unit): LinearLayout {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(32))
    }
    column.build()
    val scroll = NestedScrollView(this).apply {
        addView(column)
        isFillViewport = true
        clipToPadding = false
    }
    val bar = MaterialToolbar(this).apply {
        this.title = title
        if (up) {
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationIcon?.setTint(themeColor(M.attr.colorOnSurface))
            navigationContentDescription = "Back"
            setNavigationOnClickListener { finish() }
        }
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(themeColor(M.attr.colorSurface))
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
        scroll.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
        insets
    }
    setContentView(root)
    return column
}

/** A filled card holding a column. [padded] = false for cards of rows, which pad themselves. */
fun LinearLayout.card(padded: Boolean = true, build: LinearLayout.() -> Unit): MaterialCardView {
    val inner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (padded) setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    inner.build()
    val card = MaterialCardView(context, null, M.attr.materialCardViewFilledStyle).apply { addView(inner) }
    addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(12)
    })
    return card
}

/** The column inside a card made by [card]. */
val MaterialCardView.column: LinearLayout get() = getChildAt(0) as LinearLayout

/** A small colored label above a group of cards. */
fun LinearLayout.section(text: String): TextView = TextView(context).also {
    it.text = text
    it.setTextAppearance(M.style.TextAppearance_Material3_LabelLarge)
    it.setTextColor(context.themeColor(M.attr.colorPrimary))
    it.setPadding(dp(4), dp(16), dp(4), dp(8))
    addView(it)
}

/** A heading inside a card. */
fun LinearLayout.heading(text: String): TextView = TextView(context).also {
    it.text = text
    it.setTextAppearance(M.style.TextAppearance_Material3_TitleMedium)
    it.setPadding(0, if (childCount == 0) 0 else dp(16), 0, dp(4))
    addView(it)
}

fun LinearLayout.text(text: String = "", secondary: Boolean = false): TextView = TextView(context).also {
    it.text = text
    it.setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
    if (secondary) it.setTextColor(context.themeColor(M.attr.colorOnSurfaceVariant))
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

/**
 * A tappable row: title, optional subtitle, chevron. Returns the subtitle so
 * the caller can update it.
 */
fun LinearLayout.navRow(title: String, subtitle: String = "", onClick: () -> Unit): TextView {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(12), dp(14))
        isClickable = true
        isFocusable = true
        setBackgroundResource(context.run {
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            tv.resourceId
        })
        setOnClickListener { onClick() }
    }
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(context).apply {
        text = title
        setTextAppearance(M.style.TextAppearance_Material3_TitleMedium)
    })
    val sub = TextView(context).apply {
        text = subtitle
        setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
        setTextColor(context.themeColor(M.attr.colorOnSurfaceVariant))
        visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        // Callers fill the subtitle in later: show it once it has text.
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
    }
    texts.addView(sub)
    row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron_right)
        imageTintList = ColorStateList.valueOf(context.themeColor(M.attr.colorOnSurfaceVariant))
    }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
    addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return sub
}

fun LinearLayout.divider() = addView(MaterialDivider(context))

enum class ButtonKind { FILLED, TONAL, OUTLINED, TEXT }

fun LinearLayout.button(label: String, kind: ButtonKind = ButtonKind.FILLED, onClick: (View) -> Unit): MaterialButton {
    val attr = when (kind) {
        ButtonKind.FILLED -> M.attr.materialButtonStyle
        ButtonKind.TONAL -> R.attr.tonalButtonStyle
        ButtonKind.OUTLINED -> M.attr.materialButtonOutlinedStyle
        ButtonKind.TEXT -> M.attr.borderlessButtonStyle
    }
    return MaterialButton(context, null, attr).also {
        it.text = label
        it.isAllCaps = false
        it.setOnClickListener(onClick)
        addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/** An outlined text field; returns the EditText inside it. */
fun LinearLayout.field(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
    val layout = TextInputLayout(context, null, M.attr.textInputOutlinedStyle).apply { this.hint = hint }
    val edit = TextInputEditText(layout.context).apply {
        setText(value)
        inputType = type
    }
    layout.addView(edit)
    addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(4)
        bottomMargin = dp(4)
    })
    return edit
}

fun LinearLayout.check(label: String, checked: Boolean): MaterialCheckBox = MaterialCheckBox(context).also {
    it.text = label
    it.isChecked = checked
    addView(it)
}

/** Views side by side, sharing the width. */
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

private fun View.dp(v: Int) = context.dp(v)
