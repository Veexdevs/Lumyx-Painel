package com.veexdevs.lumyxpainel

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

object RedxUiDrawables {

    fun createScreenBackground(): Drawable = RedxScreenBackgroundDrawable()

    fun createPillButtonBackground(density: Float): Drawable {
        val content = RedxPillDrawable(density)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(0x44, 0xFF, 0x14, 0x28)),
            content,
            mask
        )
    }

    private class RedxScreenBackgroundDrawable : Drawable() {
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0x03, 0x01, 0x01)
        }
        private val topLeftGlow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val topRightGlow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bottomRightGlow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bottomLeftGlow = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat().coerceAtLeast(1f)
            val h = bounds.height().toFloat().coerceAtLeast(1f)

            topLeftGlow.shader = RadialGradient(
                0f,
                0f,
                w * 0.85f,
                intArrayOf(
                    Color.argb(0x78, 0x5C, 0x01, 0x04),
                    Color.argb(0x2E, 0x38, 0x01, 0x03),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )

            topRightGlow.shader = RadialGradient(
                w,
                h * 0.04f,
                w * 0.9f,
                intArrayOf(
                    Color.argb(0x88, 0x6E, 0x02, 0x06),
                    Color.argb(0x33, 0x40, 0x01, 0x03),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )

            bottomRightGlow.shader = RadialGradient(
                w * 0.96f,
                h * 0.98f,
                w * 0.95f,
                intArrayOf(
                    Color.argb(0x96, 0x7A, 0x02, 0x06),
                    Color.argb(0x38, 0x42, 0x01, 0x03),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )

            bottomLeftGlow.shader = RadialGradient(
                w * 0.05f,
                h * 0.96f,
                w * 0.78f,
                intArrayOf(
                    Color.argb(0x6E, 0x54, 0x01, 0x04),
                    Color.argb(0x24, 0x2E, 0x01, 0x02),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b, basePaint)
            canvas.drawRect(b, topLeftGlow)
            canvas.drawRect(b, topRightGlow)
            canvas.drawRect(b, bottomLeftGlow)
            canvas.drawRect(b, bottomRightGlow)
        }

        override fun setAlpha(alpha: Int) {
            basePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            basePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private class RedxPillDrawable(private val density: Float) : Drawable() {
        private val rect = RectF()
        private val clipPath = Path()

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(0x06, 0x03, 0x04)
        }
        private val leftGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rightGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bottomRimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val topSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.25f * density
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val inset = 0.8f * density
            rect.set(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset
            )
            val w = rect.width().coerceAtLeast(1f)
            val h = rect.height().coerceAtLeast(1f)
            val radius = h / 2f

            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

            val glowRadius = (w * 0.42f).coerceAtLeast(h)
            leftGlowPaint.shader = RadialGradient(
                rect.left + h * 0.18f,
                rect.bottom - h * 0.12f,
                glowRadius,
                intArrayOf(
                    Color.argb(0xB8, 0xB8, 0x04, 0x1C),
                    Color.argb(0x4C, 0x68, 0x02, 0x0E),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )

            rightGlowPaint.shader = RadialGradient(
                rect.right - h * 0.18f,
                rect.bottom - h * 0.12f,
                glowRadius,
                intArrayOf(
                    Color.argb(0xB8, 0xB8, 0x04, 0x1C),
                    Color.argb(0x4C, 0x68, 0x02, 0x0E),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )

            bottomRimPaint.shader = LinearGradient(
                rect.centerX(),
                rect.bottom,
                rect.centerX(),
                rect.bottom - h * 0.42f,
                intArrayOf(
                    Color.argb(0x6E, 0x9E, 0x04, 0x18),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )

            topSheenPaint.shader = LinearGradient(
                rect.centerX(),
                rect.top,
                rect.centerX(),
                rect.top + h * 0.30f,
                intArrayOf(
                    Color.argb(0x1C, 0xFF, 0xFF, 0xFF),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )

            strokePaint.shader = LinearGradient(
                rect.left,
                rect.centerY(),
                rect.right,
                rect.centerY(),
                intArrayOf(
                    Color.rgb(0xC8, 0x14, 0x24),
                    Color.rgb(0x4C, 0x1C, 0x22),
                    Color.rgb(0x3A, 0x1A, 0x1E),
                    Color.rgb(0x4C, 0x1C, 0x22),
                    Color.rgb(0xC8, 0x14, 0x24)
                ),
                floatArrayOf(0f, 0.24f, 0.5f, 0.76f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            val radius = rect.height() / 2f
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(rect, leftGlowPaint)
            canvas.drawRect(rect, rightGlowPaint)
            canvas.drawRect(rect, bottomRimPaint)
            canvas.drawRect(rect, topSheenPaint)
            canvas.restoreToCount(saveCount)

            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
