package com.jake.duolauncher.postures

import android.app.Activity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The frozen contract from the build plan: the launcher's current fold posture. */
interface PostureProvider {
    val posture: StateFlow<DuoPosture>
}

/**
 * Where fold signals come from.
 *
 * Production uses [windowLayoutSignals]; tests supply a fake, which is why nothing below this
 * boundary knows about `WindowInfoTracker`.
 */
fun interface FoldSignalSource {
    fun signals(): Flow<List<FoldSignal>>
}

/**
 * The posture described by [signals].
 *
 * A half-opened feature wins over a flat one, because that is the state with a hinge to avoid; on
 * a device reporting several, the first half-opened feature in the window's own order is used.
 * No features at all means [DuoPosture.Unknown] — the API answered, but said nothing usable.
 */
fun postureOf(signals: List<FoldSignal>): DuoPosture {
    signals.firstOrNull { it.state == FoldState.HALF_OPENED }?.let {
        return DuoPosture.HalfOpened(it.bounds, it.orientation)
    }
    return if (signals.any { it.state == FoldState.FLAT }) DuoPosture.Flat else DuoPosture.Unknown
}

/**
 * [source] mapped to postures, de-duplicated, and degraded to [DuoPosture.Unknown] if it fails.
 *
 * The `catch` is the error table's "Posture API unavailable" row: a throwing or absent posture API
 * ends the stream on Unknown instead of propagating, so callers keep working on width alone.
 */
fun postureFlow(source: FoldSignalSource): Flow<DuoPosture> =
    source.signals()
        .map(::postureOf)
        .catch { emit(DuoPosture.Unknown) }
        .distinctUntilChanged()

/**
 * [PostureProvider] backed by `WindowInfoTracker`, collected only while its owner is started.
 *
 * Register it the way `DeviceStatusMonitor` is registered — build it in `onCreate` and
 * `lifecycle.addObserver(it)` — and it costs nothing while the launcher is stopped: the collection
 * job is cancelled in `onStop` and restarted in `onStart`, when the tracker immediately re-reports
 * the current layout. The last known posture is kept across a stop rather than flashing back to
 * Unknown.
 */
class WindowPostureProvider(private val source: FoldSignalSource) :
    PostureProvider, DefaultLifecycleObserver {

    /** Activity-scoped, which is the accurate `windowLayoutInfo` overload for a real window. */
    constructor(activity: Activity) : this(windowLayoutSignals(activity))

    private val mutable = MutableStateFlow<DuoPosture>(DuoPosture.Unknown)
    override val posture: StateFlow<DuoPosture> = mutable.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var job: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        if (job != null) return
        job = scope.launch { postureFlow(source).collect { mutable.value = it } }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        job = null
        scope.cancel()
    }
}

/**
 * Fold signals from `WindowInfoTracker` for [activity].
 *
 * If the tracker cannot be created at all — no window extensions, an OEM stub that throws — this
 * degrades to a stream of "no features", which [postureOf] turns into [DuoPosture.Unknown].
 */
fun windowLayoutSignals(activity: Activity): FoldSignalSource = FoldSignalSource {
    val layout = runCatching {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }.getOrNull()
    layout?.map { info ->
        info.displayFeatures.filterIsInstance<FoldingFeature>().mapNotNull(::foldSignalOf)
    } ?: flowOf(emptyList())
}

/**
 * [feature] as pure data, or null when it reports a state or orientation this build does not model.
 *
 * `FoldingFeature.State` and `Orientation` are open value classes rather than enums, so an unknown
 * future constant is dropped instead of being forced into the wrong bucket.
 */
internal fun foldSignalOf(feature: FoldingFeature): FoldSignal? {
    val state = when (feature.state) {
        FoldingFeature.State.FLAT -> FoldState.FLAT
        FoldingFeature.State.HALF_OPENED -> FoldState.HALF_OPENED
        else -> return null
    }
    val orientation = when (feature.orientation) {
        FoldingFeature.Orientation.VERTICAL -> FoldOrientation.VERTICAL
        FoldingFeature.Orientation.HORIZONTAL -> FoldOrientation.HORIZONTAL
        else -> return null
    }
    val bounds = feature.bounds
    return FoldSignal(
        bounds = PostureRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
        state = state,
        orientation = orientation,
        isSeparating = feature.isSeparating,
    )
}
