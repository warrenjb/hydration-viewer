package com.example.hydrationviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.time.format.TextStyle
import java.util.Locale

/**
 * A stacked bar chart, one bar per day, one colored segment per water source.
 * Drawn directly with Canvas rather than a third-party charting library, since
 * we've already hit enough build-dependency surprises on this project.
 */
class BarChartView(context: Context) : View(context) {

    private var days: List<DailySourceTotals> = emptyList()
    private var sourceOrder: List<String> = emptyList()
    private val sourceColors = mutableMapOf<String, Int>()
    private val sourceLabels = mutableMapOf<String, String>()

    private val colorPalette = listOf(
        Color.parseColor("#4FC3F7"), // light blue
        Color.parseColor("#FF8A65"), // orange
        Color.parseColor("#AED581"), // green
        Color.parseColor("#BA68C8"), // purple
        Color.parseColor("#FFD54F"), // yellow
    )

    private val segmentPaint = Paint().apply { isAntiAlias = true }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
        isAntiAlias = true
    }
    private val valuePaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 24f
        isAntiAlias = true
    }
    private val legendPaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        textSize = 24f
        isAntiAlias = true
    }

    /**
     * [friendlyNameFor] maps a raw Health Connect source package name to a display
     * label (e.g. "com.example.hydrationviewer" -> "WaterH").
     */
    fun setData(newDays: List<DailySourceTotals>, friendlyNameFor: (String) -> String) {
        days = newDays
        sourceOrder = newDays
            .flatMap { it.litersBySource.keys }
            .distinct()
            .sorted()
        sourceOrder.forEachIndexed { index, source ->
            sourceColors.getOrPut(source) { colorPalette[index % colorPalette.size] }
            sourceLabels[source] = friendlyNameFor(source)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val legendRows = sourceOrder.size
        val height = 460 + legendRows * 44
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return

        val legendHeight = sourceOrder.size * 44
        val chartTop = 40f
        val chartBottom = height - 60f - legendHeight
        val chartHeight = chartBottom - chartTop
        val maxLiters = (days.maxOfOrNull { it.totalLiters } ?: 0.0).coerceAtLeast(0.5)
        val slotWidth = width.toFloat() / days.size
        val barWidth = slotWidth * 0.5f

        days.forEachIndexed { index, day ->
            val centerX = slotWidth * index + slotWidth / 2f
            var runningTop = chartBottom

            for (source in sourceOrder) {
                val liters = day.litersBySource[source] ?: continue
                if (liters <= 0.0) continue
                val segmentHeight = (liters / maxLiters).toFloat() * chartHeight
                val segmentTop = runningTop - segmentHeight
                segmentPaint.color = sourceColors[source] ?: Color.GRAY
                canvas.drawRect(
                    centerX - barWidth / 2f, segmentTop,
                    centerX + barWidth / 2f, runningTop,
                    segmentPaint
                )
                runningTop = segmentTop
            }

            if (day.totalLiters > 0.0) {
                canvas.drawText("%.1fL".format(day.totalLiters), centerX, runningTop - 12f, valuePaint)
            }

            val dayLabel = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            canvas.drawText(dayLabel, centerX, chartBottom + 36f, labelPaint)
        }

        // Legend: one row per source, below the chart.
        var legendY = chartBottom + 80f
        val swatchSize = 28f
        for (source in sourceOrder) {
            segmentPaint.color = sourceColors[source] ?: Color.GRAY
            canvas.drawRect(16f, legendY - swatchSize, 16f + swatchSize, legendY, segmentPaint)
            canvas.drawText(sourceLabels[source] ?: source, 16f + swatchSize + 16f, legendY - 4f, legendPaint)
            legendY += 44f
        }
    }
}
