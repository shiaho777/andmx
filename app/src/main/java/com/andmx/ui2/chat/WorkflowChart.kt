package com.andmx.ui2.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.agent.workflow.WorkflowDashboard
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

@Composable
fun WorkflowChart(
    chart: WorkflowDashboard.Chart,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
    )
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
    val valid = chart.series.map { series ->
        WorkflowDashboard.ChartSeries(
            label = series.label,
            unit = series.unit,
            points = series.points.filter { chart.scale != "log" || it.y > 0.0 },
        )
    }

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .padding(vertical = 4.dp),
        ) {
            fun transform(value: Double): Double =
                if (chart.scale == "log") log10(value.coerceAtLeast(1e-9)) else value

            val all = valid.flatMap { series -> series.points.map { transform(it.y) } } +
                listOfNotNull(chart.baseline?.let(::transform))
            if (all.isEmpty()) return@Canvas

            var low = all.min()
            var high = all.max()
            if (chart.type == "bar" && chart.scale == "linear") low = min(low, 0.0)
            if (high - low < 1e-9) {
                high += 1.0
                low -= 1.0
            }
            val pad = (high - low) * 0.08
            low -= pad
            high += pad
            val left = 6.dp.toPx()
            val right = size.width - 6.dp.toPx()
            val top = 6.dp.toPx()
            val bottom = size.height - 6.dp.toPx()
            val width = right - left
            val height = bottom - top

            fun yOf(value: Double): Float =
                (bottom - ((transform(value) - low) / (high - low)) * height).toFloat()

            fun xOf(index: Int): Float =
                if (chart.xLabels.size <= 1) {
                    left + width / 2
                } else {
                    left + width * index / (chart.xLabels.size - 1)
                }

            for (step in 0..4) {
                val y = top + height * step / 4
                drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
            }

            chart.baseline?.let { baseline ->
                drawLine(
                    axisColor,
                    Offset(left, yOf(baseline)),
                    Offset(right, yOf(baseline)),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }

            when (chart.type) {
                "bar" -> {
                    val slots = max(1, chart.xLabels.size)
                    val slot = width / slots
                    val barWidth = (slot / (valid.size + 1)) * 0.82f
                    val zeroY = yOf(0.0)
                    valid.forEachIndexed { seriesIndex, series ->
                        val color = colors[seriesIndex % colors.size]
                        series.points.forEach { point ->
                            val x = left + point.index * slot + slot * 0.09f + seriesIndex * barWidth
                            val y = yOf(point.y)
                            drawRect(
                                color,
                                topLeft = Offset(x, min(y, zeroY)),
                                size = Size(barWidth, max(1f, kotlin.math.abs(zeroY - y))),
                            )
                        }
                    }
                }
                "scatter" -> {
                    valid.forEachIndexed { seriesIndex, series ->
                        val color = colors[seriesIndex % colors.size]
                        series.points.forEach { point ->
                            drawCircle(color, 3.dp.toPx(), Offset(xOf(point.index), yOf(point.y)))
                        }
                    }
                }
                else -> {
                    valid.forEachIndexed { seriesIndex, series ->
                        val color = colors[seriesIndex % colors.size]
                        if (series.points.size > 1) {
                            val path = Path()
                            series.points.forEachIndexed { index, point ->
                                val x = xOf(point.index)
                                val y = yOf(point.y)
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(
                                path,
                                color,
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                        series.points.forEach { point ->
                            drawCircle(color, 2.5.dp.toPx(), Offset(xOf(point.index), yOf(point.y)))
                        }
                    }
                }
            }
        }

        if (chart.xLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    chart.xLabels.first(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chart.xLabels.size > 1) {
                    Text(
                        chart.xLabels.last(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        valid.forEachIndexed { index, series ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(colors[index % colors.size], CircleShape),
                )
                Text(
                    buildString {
                        append(series.label)
                        series.unit?.let { append(" (").append(it).append(')') }
                        append(" · ")
                        append(series.points.joinToString(", ") { "${it.x}=${it.y}" }.take(240))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
