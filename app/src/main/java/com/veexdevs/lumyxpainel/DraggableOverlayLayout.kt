package com.veexdevs.lumyxpainel

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Container arrastável para sobreposição (overlay).
 * Permite arrastar tanto por áreas vazias/cabeçalho quanto iniciando o gesto sobre botões,
 * sem impedir cliques normais em botões/switches nem o deslize de SeekBars.
 */
class DraggableOverlayLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val tmpLocation = IntArray(2)

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var isDragging = false
    private var ignoringSliderGesture = false

    var positionProvider: (() -> Pair<Int, Int>)? = null
    var onDragMove: ((newX: Int, newY: Int) -> Unit)? = null
    var onDragEnd: ((finalX: Int, finalY: Int) -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoringSliderGesture = isTouchInsideSeekBar(this, ev.rawX, ev.rawY)
                downRawX = ev.rawX
                downRawY = ev.rawY
                val currentPos = positionProvider?.invoke() ?: (0 to 0)
                startX = currentPos.first
                startY = currentPos.second
                isDragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignoringSliderGesture) return false
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (hypot(dx, dy) > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                ignoringSliderGesture = false
                return false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!isDragging && hypot(dx, dy) > touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    val targetX = startX + dx.roundToInt()
                    val targetY = startY + dy.roundToInt()
                    onDragMove?.invoke(targetX, targetY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    val pos = positionProvider?.invoke() ?: (startX to startY)
                    onDragEnd?.invoke(pos.first, pos.second)
                } else if (isClickable) {
                    performClick()
                }
                ignoringSliderGesture = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    val pos = positionProvider?.invoke() ?: (startX to startY)
                    onDragEnd?.invoke(pos.first, pos.second)
                }
                ignoringSliderGesture = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun isTouchInsideSeekBar(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view is SeekBar) {
            view.getLocationOnScreen(tmpLocation)
            val pad = touchSlop
            val left = tmpLocation[0] - pad
            val top = tmpLocation[1] - pad
            val right = tmpLocation[0] + view.width + pad
            val bottom = tmpLocation[1] + view.height + pad
            return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (isTouchInsideSeekBar(view.getChildAt(i), rawX, rawY)) {
                    return true
                }
            }
        }
        return false
    }
}
