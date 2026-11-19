package laptimer.app

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import laptimer.core.Lap
import laptimer.core.TimingState
import laptimer.core.formatDelta
import laptimer.core.formatLapTime
import kotlin.math.abs

/** Faster than the best: the usual lap-timer green. */
private val FASTER = Color(0xFF2E7D32)
private val SLOWER = Color(0xFFC62828)

/** How long a finished lap's time stays in the big display. */
private const val HOLD_LAP_S = 5.0

/** The clock stops running if no fix has arrived for this long. */
private const val STALE_NANOS = 1_000_000_000L

/**
 * The live timing display: a big lap clock, then last and best laps and
 * the current lap's sectors. Readable at a glance from a phone mounted in
 * the car.
 */
@Composable
fun LiveTiming(trackName: String, timing: TimingState, timingAtNanos: Long, streaming: Boolean) {
    // Redraw every frame so the clock runs smoothly between fixes.
    var nowNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(streaming) {
        while (streaming) withFrameNanos { nowNanos = SystemClock.elapsedRealtimeNanos() }
    }
    val sinceFixNanos = (nowNanos - timingAtNanos).coerceAtLeast(0)
    val extraS = if (streaming && sinceFixNanos < STALE_NANOS) sinceFixNanos / 1e9 else 0.0

    val last = timing.lastLap
    val best = timing.bestLap
    val running = timing.currentLapS?.plus(extraS)
    // Just after a lap ends, show its time; drivers glance at it on the
    // straight. After a point-to-point run there is no next lap, so the
    // run's time stays up until the next start.
    val holdLast = last != null && (running == null || running < HOLD_LAP_S)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            trackName + (timing.currentLapNumber?.let { "   lap $it" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
        )
        val big = 72.sp
        when {
            holdLast -> Text(
                formatLapTime(last.timeS),
                fontSize = big,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = lapColor(last, best) ?: Color.Unspecified,
            )
            running != null -> Text(formatLapTime(running, decimals = 1), fontSize = big, fontFamily = FontFamily.Monospace)
            else -> Text("Cross the start line", style = MaterialTheme.typography.headlineMedium)
        }
        if (timing.currentLapHasGap) Text("GPS gap this lap: it won't count as a best", color = SLOWER)

        if (!holdLast) Delta(timing)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LabeledTime("Last", last?.let { formatLapTime(it.timeS) }, last?.let { lapColor(it, best) })
            LabeledTime("Best", best?.let { formatLapTime(it.timeS) }, null)
            // With one sector, the optimal lap is just the best lap.
            if (timing.bestSectorsS.size > 1) LabeledTime("Optimal", timing.optimalLapS?.let { formatLapTime(it) }, null)
        }
        if (last != null && best != null && last != best && !last.hadGap) {
            Text("Last lap ${formatDelta(last.timeS - best.timeS)} off the best", color = SLOWER)
        }

        if (timing.bestSectorsS.size > 1) SectorRow(timing, last, holdLast)
    }
}

/** The bar reaches the edge at this many seconds, either way from the middle. */
private const val DELTA_BAR_FULL_S = 2.0
private val DELTA_BAR_HEIGHT = 14.dp

/**
 * The predictive delta: how far ahead (green, negative) or behind (red)
 * of the reference lap the car is at this point of the lap, as a number
 * and a bar from the middle: left for ahead, right for behind.
 */
@Composable
private fun Delta(timing: TimingState) {
    val ref = timing.referenceLapS ?: return
    val d = timing.deltaS
    val color = when {
        d == null -> Color.Gray
        d <= 0 -> FASTER
        else -> SLOWER
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            // Hundredths are enough to read at a glance.
            d?.let { formatDelta(it).dropLast(1) } ?: "--.--",
            fontSize = 48.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Column {
            timing.predictedLapS?.let { Text("Predicted ${formatLapTime(it, decimals = 1)}", fontFamily = FontFamily.Monospace) }
            Text(
                "vs ${formatLapTime(ref)}" + if (timing.referenceFromThisSession) " (best today)" else " (your best)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(DELTA_BAR_HEIGHT),
    ) {
        val half = maxWidth / 2
        val fraction = ((d ?: 0.0) / DELTA_BAR_FULL_S).coerceIn(-1.0, 1.0).toFloat()
        val barWidth = half * abs(fraction)
        // Ahead grows left from the middle, behind grows right.
        val start = if (fraction < 0) half - barWidth else half
        Box(
            Modifier
                .offset(x = start)
                .size(barWidth, DELTA_BAR_HEIGHT)
                .background(color),
        )
        Box(
            Modifier
                .offset(x = half - 1.dp)
                .size(2.dp, DELTA_BAR_HEIGHT)
                .background(Color.Gray),
        )
    }
}

/** Green when the lap is the best, otherwise default. */
private fun lapColor(lap: Lap, best: Lap?): Color? = if (best != null && lap == best) FASTER else null

@Composable
private fun LabeledTime(label: String, value: String?, color: Color?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value ?: "-",
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            color = color ?: Color.Unspecified,
        )
    }
}

/**
 * Sector times: the current lap's sectors as they finish, or the lap just
 * finished while its time is held. Each shows its difference from the
 * best for that sector.
 */
@Composable
private fun SectorRow(timing: TimingState, last: Lap?, holdLast: Boolean) {
    val sectors: List<Double?> = if (holdLast && last != null) {
        last.sectorTimesS
    } else {
        // Splits are cumulative from the lap start; turn them into sector times.
        val splits = timing.currentSplitsS
        splits.indices.map { i ->
            val end = splits[i]
            val begin = if (i == 0) 0.0 else splits[i - 1]
            if (end == null || begin == null) null else end - begin
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (i in timing.bestSectorsS.indices) {
            val t = sectors.getOrNull(i)
            val bestS = timing.bestSectorsS[i]
            Column {
                Text("S${i + 1}", style = MaterialTheme.typography.labelMedium)
                Text(t?.let { formatLapTime(it, decimals = 2) } ?: "-", fontFamily = FontFamily.Monospace)
                if (t != null && bestS != null) {
                    val d = t - bestS
                    Text(
                        if (d <= 0.0005) "best" else formatDelta(d),
                        fontFamily = FontFamily.Monospace,
                        color = if (d <= 0.0005) FASTER else SLOWER,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** All laps of a session, for after it ends. */
@Composable
fun LapList(timing: TimingState) {
    val best = timing.bestLap
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (lap in timing.laps) {
            val sectors = if (lap.sectorTimesS.size > 1) {
                "   " + lap.sectorTimesS.joinToString("  ") { it?.let { s -> formatLapTime(s, 2) } ?: "-" }
            } else {
                ""
            }
            Text(
                "%2d  %s%s%s".format(lap.number, formatLapTime(lap.timeS), sectors, if (lap.hadGap) "  (GPS gap)" else ""),
                fontFamily = FontFamily.Monospace,
                color = if (lap == best) FASTER else Color.Unspecified,
            )
        }
        if (timing.bestSectorsS.size > 1) {
            timing.optimalLapS?.let { Text("Optimal ${formatLapTime(it)} from the best sectors", fontFamily = FontFamily.Monospace) }
        }
    }
}
