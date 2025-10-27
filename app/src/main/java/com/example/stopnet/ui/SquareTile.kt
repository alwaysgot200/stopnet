package com.example.stopnet.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class SquareTile @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            width > 0 -> width
            height > 0 -> height
            else -> 0
        }
        if (size > 0) {
            val exact = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
            super.onMeasure(exact, exact)
        } else {
            // Fallback
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}