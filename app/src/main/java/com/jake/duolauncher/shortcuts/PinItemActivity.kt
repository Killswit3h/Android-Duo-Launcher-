package com.jake.duolauncher.shortcuts

import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val STATE_HANDLED = "duo.pin.handled"
private const val MAX_PIN_LABEL_CHARS = 120

/**
 * The pin-request confirmation sheet (FR-28), and one of Duo's two exported surfaces.
 *
 * ## Security posture (NFR-S6)
 *
 * This activity is reachable by any app on the device, so it is written on the assumption that the
 * caller is hostile. Four rules make that safe, and they are all final even though the visual
 * design is not:
 *
 * 1. **The only source of a request is `LauncherApps.getPinItemRequest(intent)`.** The incoming
 *    intent's extras, data URI and `ClipData` are never read. The request rides on a
 *    system-populated extra that an ordinary app cannot mint, so an app that copies the action and
 *    invents extras gets null back and is rejected.
 * 2. **No intent from the caller is ever executed.** This activity starts nothing, and a shortcut
 *    is pinned by handing the request back to the platform, never by firing an intent the caller
 *    supplied. There is no code path here that turns caller-controlled data into a launch.
 * 3. **The action is matched against constants this package owns**, and it must agree with the
 *    request's own `requestType`. A genuine widget request delivered on the shortcut action, or the
 *    reverse, is refused.
 * 4. **Validity is re-checked immediately before `accept()`**, and acceptance is single-shot. An
 *    arbitrary amount of time passes while the sheet is on screen, and a replayed intent, a double
 *    tap or a configuration change must not pin anything twice.
 *
 * The decision itself lives in [PinRequestGate] so all of it is unit-tested rather than reasoned
 * about. Labels drawn here come from the request's own `ShortcutInfo`/`AppWidgetProviderInfo`, are
 * rendered as text only, and are length-capped.
 *
 * The UI is intentionally plain; a later task restyles it as a glass sheet without touching any of
 * the above.
 */
class PinItemActivity : ComponentActivity() {

    /** The genuine request, or null when this activity is finishing without doing anything. */
    private var request: LauncherApps.PinItemRequest? = null
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean(STATE_HANDLED) == true

        val genuine = runCatching {
            getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent)
        }.getOrNull()

        val decision = PinRequestGate.evaluate(
            action = intent?.action,
            requestKind = genuine?.let(::kindOf),
            requestValid = runCatching { genuine?.isValid == true }.getOrDefault(false),
            layoutLocked = DuoPinRequests.isLayoutLocked(),
        )

        val kind = when {
            handled -> null
            decision is PinRequestDecision.Confirm -> decision.kind
            decision is PinRequestDecision.Locked -> decision.kind
            else -> null
        }
        if (kind == null || genuine == null) {
            // Rejected, or already answered. Finish silently: telling a caller why would hand it a
            // probe for which requests the launcher considers real.
            finish()
            return
        }

        request = genuine
        setFinishOnTouchOutside(true)
        val label = labelOf(genuine, kind)
        val startedLocked = decision is PinRequestDecision.Locked

        setContent {
            MaterialTheme {
                var locked by remember { mutableStateOf(startedLocked) }
                PinItemSheet(
                    label = label,
                    locked = locked,
                    onConfirm = {
                        accept()
                        finish()
                    },
                    onUnlockAndAdd = {
                        // Only an unlock that actually ran may lead to a pin. Falling through to
                        // accept() because a missing or throwing seam reported "not locked" would
                        // place the item on a layout the user locked (FR-49).
                        if (DuoPinRequests.unlockLayout() && !DuoPinRequests.isLayoutLocked()) {
                            accept()
                            finish()
                        } else {
                            locked = true
                        }
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_HANDLED, handled)
        super.onSaveInstanceState(outState)
    }

    /**
     * Accepts the request, then hands the result to whatever owns Home placement.
     *
     * `accept()` is what actually pins the item, so it runs first: if it fails there is nothing to
     * place. If placement then fails or no placer is registered yet, the item is still pinned at
     * the system level and [DuoShortcutRepository.pinnedShortcuts] can reconcile it.
     */
    private fun accept(): Boolean {
        val pending = request ?: return false
        val valid = runCatching { pending.isValid }.getOrDefault(false)
        if (!PinRequestGate.canAccept(valid, handled, DuoPinRequests.isLayoutLocked())) return false
        val kind = kindOf(pending) ?: return false
        handled = true
        val item = describe(pending, kind)
        val accepted = runCatching { pending.accept() }.getOrDefault(false)
        if (accepted) DuoPinRequests.place(item)
        return accepted
    }

    private fun kindOf(pending: LauncherApps.PinItemRequest): PinItemKind? =
        when (runCatching { pending.requestType }.getOrDefault(0)) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> PinItemKind.SHORTCUT
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> PinItemKind.APPWIDGET
            else -> null
        }

    private fun describe(pending: LauncherApps.PinItemRequest, kind: PinItemKind): PinnedItem =
        when (kind) {
            PinItemKind.SHORTCUT -> PinnedItem(
                kind = kind,
                shortcut = runCatching { pending.shortcutInfo }.getOrNull()?.let(::keyOf),
                label = labelOf(pending, kind),
            )
            PinItemKind.APPWIDGET -> PinnedItem(
                kind = kind,
                appWidgetProvider = runCatching {
                    pending.getAppWidgetProviderInfo(this)?.provider?.flattenToString()
                }.getOrNull(),
                label = labelOf(pending, kind),
            )
        }

    private fun keyOf(info: ShortcutInfo): PinnedShortcutKey? = runCatching {
        val serial = getSystemService(UserManager::class.java)
            ?.getSerialNumberForUser(info.userHandle)
        val owner = info.`package`
        if (serial == null || serial < 0 || info.id.isBlank() || owner.isBlank()) null
        else PinnedShortcutKey(owner, info.id, serial)
    }.getOrNull()

    /** Untrusted text from the requesting app: trimmed, capped, and only ever rendered. */
    private fun labelOf(pending: LauncherApps.PinItemRequest, kind: PinItemKind): String {
        val supplied = when (kind) {
            PinItemKind.SHORTCUT ->
                runCatching { pending.shortcutInfo?.shortLabel?.toString() }.getOrNull()
            PinItemKind.APPWIDGET -> runCatching {
                pending.getAppWidgetProviderInfo(this)?.loadLabel(packageManager)
            }.getOrNull()
        }
        return supplied?.trim()?.take(MAX_PIN_LABEL_CHARS)?.takeIf(String::isNotEmpty)
            ?: if (kind == PinItemKind.SHORTCUT) "this shortcut" else "this widget"
    }
}

@Composable
private fun PinItemSheet(
    label: String,
    locked: Boolean,
    onConfirm: () -> Unit,
    onUnlockAndAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = if (locked) "Home layout is locked" else "Add to Home?",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = if (locked) {
                        "Unlock Home layout to add $label."
                    } else {
                        "$label wants a place on your Home screen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = if (locked) onUnlockAndAdd else onConfirm) {
                        Text(if (locked) "Unlock and add" else "Add")
                    }
                }
            }
        }
    }
}
