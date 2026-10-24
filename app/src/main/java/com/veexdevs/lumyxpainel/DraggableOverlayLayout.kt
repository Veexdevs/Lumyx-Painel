package com.veexdevs.lumyxpainel

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.SeekBar
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Container arrastável para sobreposição (overlay).
 * Permite arrastar por áreas vazias, cabeçalho e até iniciando o gesto sobre botões,
 * preservando cliques normais, deslize de SeekBars e rolagem vertical quando houver muitos itens.
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
    private var delegatingToChildGesture = false

    var positionProvider: (() -> Pair<Int, Int>)? = null
    var onDragMove: ((newX: Int, newY: Int) -> Unit)? = null
    var onDragEnd: ((finalX: Int, finalY: Int) -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                delegatingToChildGesture = isTouchInsideInteractiveScrollOrSlider(this, ev.rawX, ev.rawY)
                downRawX = ev.rawX
                downRawY = ev.rawY
                val currentPos = positionProvider?.invoke() ?: (0 to 0)
                startX = currentPos.first
                startY = currentPos.second
                isDragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (delegatingToChildGesture) return false
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
                delegatingToChildGesture = false
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
                delegatingToChildGesture = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    val pos = positionProvider?.invoke() ?: (startX to startY)
                    onDragEnd?.invoke(pos.first, pos.second)
                }
                delegatingToChildGesture = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun isTouchInsideInteractiveScrollOrSlider(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false

        if (view is SeekBar) {
            if (isPointInsideView(view, rawX, rawY, touchSlop)) {
                return true
            }
        }

        if (view is ScrollView && (view.canScrollVertically(1) || view.canScrollVertically(-1))) {
            if (isPointInsideView(view, rawX, rawY, 0)) {
                return true
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (isTouchInsideInteractiveScrollOrSlider(view.getChildAt(i), rawX, rawY)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isPointInsideView(view: View, rawX: Float, rawY: Float, pad: Int): Boolean {
        view.getLocationOnScreen(tmpLocation)
        val left = tmpLocation[0] - pad
        val top = tmpLocation[1] - pad
        val right = tmpLocation[0] + view.width + pad
        val bottom = tmpLocation[1] + view.height + pad
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }
}
