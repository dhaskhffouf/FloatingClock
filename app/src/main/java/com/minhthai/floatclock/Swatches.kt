package com.minhthai.floatclock

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

object Swatches {
    val PALETTE: List<Pair<String, Int>> = listOf(
        "Trắng" to Color.WHITE,
        "Đen" to Color.BLACK,
        "Đỏ san hô" to Color.parseColor("#FF6B6B"),
        "Vàng" to Color.parseColor("#FFD166"),
        "Xanh ngọc" to Color.parseColor("#06D6A0"),
        "Xanh dương" to Color.parseColor("#4EA8DE"),
        "Tím nhạt" to Color.parseColor("#C77DFF")
    )

    fun render(
        parent: LinearLayout,
        selectedColor: Int,
        sizeDp: Int = 36,
        onPick: (Int) -> Unit,
        onCustom: (() -> Unit)? = null
    ) {
        parent.removeAllViews()
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val px = (sizeDp * density).toInt()
        val margin = (6 * density).toInt()
        val strokePx = maxOf(1, (2 * density).toInt())

        for ((label, colorInt) in PALETTE) {
            val isSelected = colorInt == selectedColor
            val item = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(px, px).apply {
                    marginEnd = margin
                }
                gravity = Gravity.CENTER
                contentDescription = label
                text = if (isSelected) "✓" else ""
                setTextColor(if (isSelected) Color.BLACK else Color.TRANSPARENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                    setStroke(strokePx, if (isSelected) Color.WHITE else 0x55FFFFFF)
                }
                setOnClickListener {
                    onPick(colorInt)
                    render(parent, colorInt, sizeDp, onPick, onCustom)
                }
            }
            parent.addView(item)
        }

        if (onCustom != null) {
            val isCustom = PALETTE.none { it.second == selectedColor }
            val customBtn = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(px, px)
                gravity = Gravity.CENTER
                contentDescription = "Màu tùy chỉnh"
                text = if (isCustom) "✓" else "+"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isCustom) selectedColor else 0x22FFFFFF)
                    setStroke(strokePx, if (isCustom) Color.WHITE else 0x66FFFFFF)
                }
                setOnClickListener { onCustom() }
            }
            parent.addView(customBtn)
        }
    }
}