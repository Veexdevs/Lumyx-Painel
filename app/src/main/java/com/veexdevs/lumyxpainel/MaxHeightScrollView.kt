package com.veexdevs.lumyxpainel

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView
import kotlin.math.roundToInt

class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = (158f * context.resources.displayMetrics.density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cappedSpec = if (maxHeightPx > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val targetSize = if (mode == MeasureSpec.UNSPECIFIED) {
                maxHeightPx
            } else {
                size.coerceAtMost(maxHeightPx)
            }
            MeasureSpec.makeMeasureSpec(targetSize, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }
}
