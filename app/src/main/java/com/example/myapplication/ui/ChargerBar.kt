package com.example.myapplication.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.model.Charger

/** 충전기 한 대를 한 칸으로 그려 충전소 상태를 한눈에 보여주는 막대. */
class ChargerBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = resources.displayMetrics.density * 6f
    private val freeColor = ContextCompat.getColor(context, R.color.accent_free)
    private val busyColor = ContextCompat.getColor(context, R.color.accent_busy)
    private val mineColor = ContextCompat.getColor(context, R.color.accent_mine)
    private var colors: List<Int> = emptyList()

    fun setChargers(chargers: List<Charger>) {
        val next = chargers.map {
            when {
                it.reservedByMe -> mineColor
                it.isCharging -> busyColor
                else -> freeColor
            }
        }
        if (next == colors) return
        colors = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (colors.isEmpty()) return
        val cell = (width - gap * (colors.size - 1)) / colors.size
        val radius = height / 2f
        colors.forEachIndexed { index, color ->
            val left = index * (cell + gap)
            paint.color = color
            canvas.drawRoundRect(left, 0f, left + cell, height.toFloat(), radius, radius, paint)
        }
    }
}
