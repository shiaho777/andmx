package com.andmx.ui2.files

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andmx.ui2.settings.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CommitGraphView(
    nodes: List<CommitNode>,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    if (loading) {
        Row(
            modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        return
    }
    if (nodes.isEmpty()) {
        Text(
            tr("暂无提交记录。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 8.dp),
        )
        return
    }

    var selected by remember(nodes) { mutableStateOf<CommitNode?>(null) }
    val laneColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    val rowHeight = 34.dp
    val graphWidth = 76.dp
    val maxLane = nodes.maxOf { it.lane }
    val laneCount = maxLane + 1
    val indexByHash = remember(nodes) { nodes.mapIndexed { i, n -> n.hash to i }.toMap() }

    Column(modifier) {
        selected?.let { node -> CommitDetailCard(node) { selected = null } }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Canvas(
                Modifier
                    .width(graphWidth)
                    .height(rowHeight * nodes.size)
                    .pointerInput(nodes) {
                        detectTapGestures { offset ->
                            val row = (offset.y / rowHeight.toPx()).toInt()
                            if (row in nodes.indices) {
                                val node = nodes[row]
                                selected = if (selected == node) null else node
                            }
                        }
                    },
            ) {
                val laneWidth = size.width / laneCount
                val rowPx = rowHeight.toPx()
                fun xOf(lane: Int): Float = laneWidth * (lane + 0.5f)
                fun yOf(index: Int): Float = rowPx * (index + 0.5f)
                val stroke = 2.dp.toPx()

                fun drawEdge(childIndex: Int, childLane: Int, parentHash: String, color: Color) {
                    val cx = xOf(childLane)
                    val cy = yOf(childIndex)
                    val parentIndex = indexByHash[parentHash]
                    if (parentIndex == null) {
                        drawLine(color, Offset(cx, cy), Offset(cx, size.height), stroke, cap = StrokeCap.Round)
                        return
                    }
                    val parentLane = nodes[parentIndex].lane
                    val py = yOf(parentIndex)
                    if (parentLane == childLane) {
                        drawLine(color, Offset(cx, cy), Offset(cx, py), stroke, cap = StrokeCap.Round)
                    } else {
                        val px = xOf(parentLane)
                        val bendTop = py - rowPx
                        if (bendTop > cy) {
                            drawLine(color, Offset(cx, cy), Offset(cx, bendTop), stroke, cap = StrokeCap.Round)
                        }
                        val path = Path().apply {
                            moveTo(cx, maxOf(cy, bendTop))
                            cubicTo(cx, py, px, bendTop, px, py)
                        }
                        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }

                nodes.forEachIndexed { index, node ->
                    val color = laneColors[node.lane % laneColors.size]
                    node.parents.firstOrNull()?.let { drawEdge(index, node.lane, it, color) }
                    node.parents.drop(1).forEach { parentHash ->
                        val parentLane = indexByHash[parentHash]?.let { nodes[it].lane } ?: (node.lane + 1)
                        drawEdge(index, node.lane, parentHash, laneColors[parentLane % laneColors.size])
                    }
                }

                nodes.forEachIndexed { index, node ->
                    val color = laneColors[node.lane % laneColors.size]
                    val center = Offset(xOf(node.lane), yOf(index))
                    if (selected == node) {
                        drawCircle(color.copy(alpha = 0.3f), 9.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                    }
                    drawCircle(color, if (node.isMerge) 5.dp.toPx() else 4.dp.toPx(), center)
                    if (node.isMerge) {
                        drawCircle(Color.White.copy(alpha = 0.9f), 2.dp.toPx(), center)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                nodes.forEach { node ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .clickable { selected = if (selected == node) null else node }
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            node.subject.ifBlank { node.shortHash },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected == node) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${node.shortHash} · ${node.author}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitDetailCard(
    node: CommitNode,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        onClick = onDismiss,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                node.subject.ifBlank { node.shortHash },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${node.shortHash} · ${node.author} · ${formatCommitTime(node.timestamp)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (node.isMerge) {
                Text(
                    tr("合并提交") + " · " + node.parents.joinToString(" ") { it.take(7) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun formatCommitTime(epochSeconds: Long): String =
    runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
    }.getOrDefault("")