package com.jake.duolauncher.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jake.duolauncher.CellularSignalVisual
import com.jake.duolauncher.DeviceStatus
import com.jake.duolauncher.WifiSignalVisual
import com.jake.duolauncher.cellularSignalVisual
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors
import com.jake.duolauncher.deviceStatusDescription
import com.jake.duolauncher.signalAlpha
import com.jake.duolauncher.wifiSignalVisual
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Duo status cluster (FR-41): the status bar as a circular glass disc in the dock-side corner,
 * the shape the iPhone Duo uses in place of a full-width status bar.
 *
 * It shows exactly what the vertical rail shows and reads from exactly the same `DeviceStatusMonitor`
 * data — the time, a battery ring, Wi-Fi and cellular — laid out concentrically instead of stacked:
 *
 * - the battery ring is the outer arc, so charge reads as how far round the disc the light goes
 * - the time sits in the middle, in the tabular clock face so the digits do not shuffle
 * - Wi-Fi arcs and cellular dots sit below the time, inside the ring
 *
 * The signal rules are shared with the rail through `StatusSignalMapping`, so an unknown Wi-Fi or
 * cellular reading is drawn at neutral strength here too and is never fabricated into full signal.
 */
@Composable
internal fun StatusCluster(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    diameter: Dp = CLUSTER_DIAMETER,
    locationInUse: Boolean = false,
) {
    val colors = currentDuoColors()
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_050L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val pattern = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    val timeFormatter = remember(pattern) { DateTimeFormatter.ofPattern(pattern) }
    val description = deviceStatusDescription(
        status = status,
        dateTimeText = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, $pattern")),
        locationInUse = locationInUse,
    )
    val wifi = wifiSignalVisual(status.wifiConnected, status.wifiLevel)
    val cellular = cellularSignalVisual(status.cellularLevel, status.airplane)
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(
        modifier = modifier
            .testTag("status-cluster")
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        // Never wider than the rail it replaces, and never so small that the clock clips: the disc
        // shrinks with the window, the type shrinks with it, and both stop at a legible floor.
        val size = minOf(diameter, maxWidth, CLUSTER_DIAMETER).coerceAtLeast(CLUSTER_MINIMUM)
        val timeSize = minOf(17f, size.value / (3.1f * fontScale)).coerceAtLeast(10f).sp
        GlassSurface(level = GlassLevel.BAR, shape = CircleShape, modifier = Modifier.size(size)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                val center = Offset(w / 2f, w / 2f)
                val radius = w * RING_RADIUS
                val ringWidth = w * RING_WIDTH
                val arcSize = Size(radius * 2, radius * 2)
                val topLeft = Offset(center.x - radius, center.y - radius)

                // Battery: a 300° ring opening at the bottom, so the gap reads as the "empty" end.
                drawArc(
                    color = colors.edge.copy(alpha = RING_SHADOW_ALPHA),
                    startAngle = RING_START, sweepAngle = RING_SWEEP, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = ringWidth * 1.2f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = colors.specular.copy(alpha = RING_TRACK_ALPHA),
                    startAngle = RING_START, sweepAngle = RING_SWEEP, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = ringWidth, cap = StrokeCap.Round),
                )
                status.battery?.let { level ->
                    drawArc(
                        color = colors.specular,
                        startAngle = RING_START, sweepAngle = RING_SWEEP * level / 100f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = ringWidth, cap = StrokeCap.Round),
                    )
                }

                // Wi-Fi: three arcs and a dot under the clock. A disconnected radio is a slash, and
                // an unknown level draws every element at neutral rather than at full strength.
                val wifiOrigin = Offset(center.x - w * WIFI_SPREAD, w * WIFI_BASELINE)
                if (wifi is WifiSignalVisual.Connected) {
                    for (index in 1..3) {
                        val r = w * (WIFI_INNER + index * WIFI_STEP)
                        drawArc(
                            color = colors.specular.copy(alpha = signalAlpha(wifi.elements[index])),
                            startAngle = WIFI_START, sweepAngle = WIFI_SWEEP, useCenter = false,
                            topLeft = Offset(wifiOrigin.x - r, wifiOrigin.y - r),
                            size = Size(r * 2, r * 2),
                            style = Stroke(w * WIFI_STROKE, cap = StrokeCap.Round),
                        )
                    }
                    drawCircle(
                        color = colors.specular.copy(alpha = signalAlpha(wifi.elements[0])),
                        radius = w * WIFI_DOT, center = wifiOrigin,
                    )
                } else {
                    drawLine(
                        color = colors.specular.copy(alpha = DISCONNECTED_ALPHA),
                        start = Offset(wifiOrigin.x - w * WIFI_STEP, wifiOrigin.y - w * WIFI_STEP),
                        end = Offset(wifiOrigin.x + w * WIFI_STEP, wifiOrigin.y + w * WIFI_STEP),
                        strokeWidth = w * WIFI_STROKE, cap = StrokeCap.Round,
                    )
                }

                // Cellular: the same five dots the rail uses, on a short arc opposite Wi-Fi.
                val activeDots = (cellular as? CellularSignalVisual.Available)?.activeDots ?: 0
                for (index in 0..4) {
                    val angle = Math.toRadians((DOT_START - index * DOT_STEP).toDouble())
                    val dotCenter = Offset(
                        center.x + w * DOT_RADIUS * cos(angle).toFloat(),
                        center.y + w * DOT_RADIUS * sin(angle).toFloat(),
                    )
                    drawCircle(
                        color = colors.specular.copy(alpha = if (index < activeDots) 1f else DOT_DIM_ALPHA),
                        radius = w * DOT_SIZE, center = dotCenter,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = now.format(timeFormatter),
                    style = DuoTokens.type.clock.copy(fontSize = timeSize),
                    color = colors.label1,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.testTag("status-cluster-time"),
                )
                Box(Modifier.size(width = 1.dp, height = (size.value * CLOCK_LIFT).dp))
            }
        }
    }
}

/** The disc's nominal size: the same visual weight as the rail's battery glyph, in one piece. */
internal val CLUSTER_DIAMETER = 76.dp
private val CLUSTER_MINIMUM = 56.dp

private const val RING_RADIUS = 0.44f
private const val RING_WIDTH = 0.062f
private const val RING_START = 120f
private const val RING_SWEEP = 300f
private const val RING_TRACK_ALPHA = 0.3f
private const val RING_SHADOW_ALPHA = 0.16f

private const val WIFI_BASELINE = 0.70f
private const val WIFI_SPREAD = 0.13f
private const val WIFI_INNER = 0.03f
private const val WIFI_STEP = 0.045f
private const val WIFI_STROKE = 0.042f
private const val WIFI_DOT = 0.028f
private const val WIFI_START = 225f
private const val WIFI_SWEEP = 90f
private const val DISCONNECTED_ALPHA = 0.75f

private const val DOT_START = 52f
private const val DOT_STEP = 13f
private const val DOT_RADIUS = 0.30f
private const val DOT_SIZE = 0.026f
private const val DOT_DIM_ALPHA = 0.3f

/** How far the clock sits above the disc's centre, leaving the lower half for the signal glyphs. */
private const val CLOCK_LIFT = 0.30f
