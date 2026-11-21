package com.minhthai.floatclock

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Vẽ hàng chấm màu (dùng chung cho màn hình cài đặt và bảng chỉnh nhanh trên đồng hồ). */
object Swatches {

    fun render(
        container: LinearLayout,
        selected: Int,
        sizeDp: Int = 44,
        onPick: (Int) -> Unit,
        onCustom: (() -> Unit)? = null
    ) {
        container.removeAllViews()
        val d = container.resources.displayMetrics.density
        val size = (sizeDp * d).toInt()
        val margin = (6 * d).toInt()

        val colors = Prefs.PALETTE.toMutableList()
        if (selected !in colors) colors.add(0, selected) // màu tuỳ chọn hiện đầu hàng

        for (c in colors) {
            val isSel = c == selected
            val dot = TextView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, margin, margin, margin)
                }
                gravity = Gravity.CENTER
                text = if (isSel) "✓" else ""
                typeface = Typeface.DEFAULT_BOLD
                textSize = 18f
                setTextColor(if (ColorUtils.calculateLuminance(c) > 0.5) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke((1.5f * d).toInt(), 0x88FFFFFF.toInt())
                }
                setOnClickListener {
                    onPick(c)
                    render(container, c, sizeDp, onPick, onCustom)
                }
            }
            container.addView(dot)
        }

        if (onCustom != null) {
            val plus = TextView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, margin, margin, margin)
                }
                gravity = Gravity.CENTER
                text = "+"
                textSize = 24f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x22FFFFFF)
                    setStroke((1.5f * d).toInt(), 0xAAFFFFFF.toInt())
                }
                setOnClickListener { onCustom() }
            }
            container.addView(plus)
        }
    }
}
