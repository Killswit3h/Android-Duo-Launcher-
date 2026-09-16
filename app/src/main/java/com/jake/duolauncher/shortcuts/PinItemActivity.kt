package com.jake.duolauncher.shortcuts

import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jake.duolauncher.DuoAppearanceRuntime
import com.jake.duolauncher.DuoTheme
import com.jake.duolauncher.design.DuoTokens
import com.jake.duolauncher.design.GlassLevel
import com.jake.duolauncher.design.GlassSurface
import com.jake.duolauncher.design.currentDuoColors

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
 * [PinItemSheet] is the glass presentation of that decision (FR-28). It is only a visual layer: it
 * receives an already-vetted kind and an already-capped label, and it can do nothing but call back.
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
            DuoTheme(DuoAppearanceRuntime.dark) {
                var locked by remember { mutableStateOf(startedLocked) }
                PinItemSheet(
                    label = label,
                    kind = kind,
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

/**
 * The confirmation sheet (FR-28): a Liquid Glass panel with a preview, **Add** and **Cancel**.
 *
 * The preview is drawn from [kind] — the request type the platform reported — and never from
 * artwork the requesting app supplies, so a hostile caller cannot hand this sheet a drawable to
 * decode. [label] has already been trimmed and length-capped by the activity, and is rendered as
 * text only.
 */
@Composable
private fun PinItemSheet(
    label: String,
    kind: PinItemKind,
    locked: Boolean,
    onConfirm: () -> Unit,
    onUnlockAndAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = currentDuoColors()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = SHEET_SCRIM))
            .padding(DuoTokens.space.xxl),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            level = GlassLevel.PANEL,
            shape = DuoTokens.radius.sheet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(DuoTokens.space.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(PREVIEW_SIZE)
                        .background(
                            colors.glassTint.copy(alpha = PREVIEW_FILL),
                            DuoTokens.radius.iconRadiusFor(PREVIEW_SIZE),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (kind == PinItemKind.SHORTCUT) Icons.Rounded.Bolt else Icons.Rounded.Widgets,
                        contentDescription = null,
                        tint = colors.label1,
                        modifier = Modifier.size(PREVIEW_GLYPH),
                    )
                }
                Spacer(Modifier.height(DuoTokens.space.lg))
                Text(
                    text = if (locked) "Home layout is locked" else "Add to Home?",
                    style = DuoTokens.type.title3,
                    color = colors.label1,
                )
                Spacer(Modifier.height(DuoTokens.space.sm))
                Text(
                    text = if (locked) {
                        "Unlock Home layout to add $label."
                    } else {
                        "$label wants a place on your Home screen."
                    },
                    style = DuoTokens.type.footnote,
                    color = colors.label2,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(DuoTokens.space.xl))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DuoTokens.space.md),
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).heightIn(min = MIN_TARGET),
                    ) { Text("Cancel") }
                    Button(
                        onClick = if (locked) onUnlockAndAdd else onConfirm,
                        modifier = Modifier.weight(1f).heightIn(min = MIN_TARGET),
                    ) { Text(if (locked) "Unlock and add" else "Add") }
                }
            }
        }
    }
}

private const val SHEET_SCRIM = 0.4f
private const val PREVIEW_FILL = 0.5f
private val PREVIEW_SIZE = 64.dp
private val PREVIEW_GLYPH = 32.dp
private val MIN_TARGET = 48.dp
