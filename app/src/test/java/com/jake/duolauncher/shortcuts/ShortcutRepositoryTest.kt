package com.jake.duolauncher.shortcuts

import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CHROME = "com.android.chrome"
private const val CHROME_ID = "com.android.chrome/.Main"
private const val CHROME_WORK_ID = "duo-profile:v1:42:com.android.chrome/.Main"
private const val WORK_SERIAL = 42L

private fun shortcut(id: String, rank: Int = 0, userSerial: Long = 0L, enabled: Boolean = true) =
    DuoShortcut(
        id = id,
        packageName = CHROME,
        userSerial = userSerial,
        label = id,
        rank = rank,
        isDynamic = true,
        isEnabled = enabled,
    )

/**
 * A stand-in for `LauncherApps`. Nothing here touches an Android API, so the gating rules can be
 * exercised on the JVM.
 */
private class FakeShortcutSource(
    var hostPermission: Boolean = true,
    var personalSerial: Long = 0L,
    var published: Map<ShortcutTarget, List<DuoShortcut>> = emptyMap(),
    var byKey: Map<PinnedShortcutKey, DuoShortcut> = emptyMap(),
    var pinnedShortcuts: List<DuoShortcut> = emptyList(),
) : ShortcutSource {
    var queries = 0
    var starts = 0
    var pins = 0
    var iconRenders = 0
    var startResult = true
    var pinResult = true

    override fun hasHostPermission(): Boolean = hostPermission
    override fun personalSerial(): Long = personalSerial

    override fun query(target: ShortcutTarget): List<DuoShortcut> {
        queries++
        return published[target].orEmpty()
    }

    override fun shortcut(key: PinnedShortcutKey): DuoShortcut? = byKey[key]

    override fun pinned(): List<DuoShortcut> = pinnedShortcuts

    override fun start(shortcut: DuoShortcut, bounds: Rect?): Boolean {
        starts++
        return startResult
    }

    override fun pin(shortcut: DuoShortcut): Boolean {
        pins++
        return pinResult
    }

    override fun icon(shortcut: DuoShortcut, sizePx: Int): ImageBitmap? {
        iconRenders++
        return null
    }
}

/** A source that fails the way `LauncherApps` does when Duo stops being Home mid-call. */
private class ThrowingShortcutSource : ShortcutSource {
    override fun hasHostPermission(): Boolean = true
    override fun personalSerial(): Long = 0L
    override fun query(target: ShortcutTarget): List<DuoShortcut> = throw SecurityException("not host")
    override fun shortcut(key: PinnedShortcutKey): DuoShortcut? = throw SecurityException("not host")
    override fun pinned(): List<DuoShortcut> = throw SecurityException("not host")
    override fun start(shortcut: DuoShortcut, bounds: Rect?): Boolean = throw SecurityException("not host")
    override fun pin(shortcut: DuoShortcut): Boolean = throw SecurityException("not host")
    override fun icon(shortcut: DuoShortcut, sizePx: Int): ImageBitmap? = throw SecurityException("not host")
}

class ShortcutRepositoryTest {

    @Test fun shortcutsComeBackOrderedAndCappedAtFive() = runBlocking {
        val source = FakeShortcutSource(
            published = mapOf(
                ShortcutTarget(CHROME, 0L) to (0..9).map { shortcut("s$it", rank = it) },
            ),
        )
        val repository = DuoShortcutRepository(source)

        val shortcuts = repository.shortcutsFor(CHROME_ID)

        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), shortcuts.map(DuoShortcut::id))
    }

    @Test fun theWorkCopyQueriesItsOwnProfile() = runBlocking {
        val source = FakeShortcutSource(
            published = mapOf(
                ShortcutTarget(CHROME, 0L) to listOf(shortcut("personal")),
                ShortcutTarget(CHROME, WORK_SERIAL) to listOf(shortcut("work", userSerial = WORK_SERIAL)),
            ),
        )
        val repository = DuoShortcutRepository(source)

        assertEquals(listOf("personal"), repository.shortcutsFor(CHROME_ID).map(DuoShortcut::id))
        assertEquals(listOf("work"), repository.shortcutsFor(CHROME_WORK_ID).map(DuoShortcut::id))
    }

    @Test fun anIdWithNoSerialFollowsThePersonalProfile() = runBlocking {
        val source = FakeShortcutSource(
            personalSerial = 11L,
            published = mapOf(ShortcutTarget(CHROME, 11L) to listOf(shortcut("personal"))),
        )
        assertEquals(listOf("personal"), DuoShortcutRepository(source).shortcutsFor(CHROME_ID).map(DuoShortcut::id))
    }

    @Test fun withoutHostPermissionQueriesAreEmptyAndTheStateSaysWhy() = runBlocking {
        val source = FakeShortcutSource(
            hostPermission = false,
            published = mapOf(ShortcutTarget(CHROME, 0L) to listOf(shortcut("new-tab"))),
        )
        val repository = DuoShortcutRepository(source)

        assertTrue(repository.shortcutsFor(CHROME_ID).isEmpty())
        assertFalse(repository.hostPermission.value)
        // The UI can show the FR-30 row instead of an empty menu, and nothing was even asked for.
        assertEquals(0, source.queries)
    }

    @Test fun becomingHomePublishesHostPermissionWithoutARestart() = runBlocking {
        val source = FakeShortcutSource(
            hostPermission = false,
            published = mapOf(ShortcutTarget(CHROME, 0L) to listOf(shortcut("new-tab"))),
        )
        val repository = DuoShortcutRepository(source)
        assertTrue(repository.shortcutsFor(CHROME_ID).isEmpty())

        source.hostPermission = true

        assertTrue(repository.refreshHostPermission())
        assertTrue(repository.hostPermission.value)
        assertEquals(listOf("new-tab"), repository.shortcutsFor(CHROME_ID).map(DuoShortcut::id))
    }

    @Test fun startingAndPinningAreRefusedWhileDuoIsNotHome() = runBlocking {
        val source = FakeShortcutSource(hostPermission = false)
        val repository = DuoShortcutRepository(source)

        assertFalse(repository.startShortcut(shortcut("new-tab"), null))
        assertFalse(repository.pin(shortcut("new-tab")))
        assertEquals(0, source.starts)
        assertEquals(0, source.pins)
    }

    @Test fun startingAndPinningWorkWhileDuoIsHome() = runBlocking {
        val source = FakeShortcutSource()
        val repository = DuoShortcutRepository(source)

        assertTrue(repository.startShortcut(shortcut("new-tab"), null))
        assertTrue(repository.pin(shortcut("new-tab")))
        assertEquals(1, source.starts)
        assertEquals(1, source.pins)
    }

    @Test fun aPlacementResolvesToItsLiveShortcut() = runBlocking {
        val key = PinnedShortcutKey(CHROME, "new-tab", 0L)
        val live = shortcut("new-tab")
        val repository = DuoShortcutRepository(FakeShortcutSource(byKey = mapOf(key to live)))

        assertEquals(PinnedShortcutState.Available(key, live), repository.resolve(key))
    }

    @Test fun aRemovedOrDisabledPlacementReportsWhyRatherThanVanishing() = runBlocking {
        val removed = PinnedShortcutKey(CHROME, "gone", 0L)
        val disabled = PinnedShortcutKey(CHROME, "locked", 0L)
        val repository = DuoShortcutRepository(
            FakeShortcutSource(byKey = mapOf(disabled to shortcut("locked", enabled = false))),
        )

        assertEquals(
            PinnedShortcutState.Unavailable(removed, ShortcutUnavailableReason.REMOVED),
            repository.resolve(removed),
        )
        assertEquals(
            PinnedShortcutState.Unavailable(disabled, ShortcutUnavailableReason.DISABLED_BY_APP),
            repository.resolve(disabled),
        )
    }

    @Test fun aPlacementIsNeverCalledRemovedJustBecauseDuoIsNotHome() = runBlocking {
        val key = PinnedShortcutKey(CHROME, "new-tab", 0L)
        val repository = DuoShortcutRepository(FakeShortcutSource(hostPermission = false))

        assertEquals(
            PinnedShortcutState.Unavailable(key, ShortcutUnavailableReason.NO_HOST_PERMISSION),
            repository.resolve(key),
        )
    }

    @Test fun pinnedShortcutsAreOnlyListedWhileDuoIsHome() = runBlocking {
        val source = FakeShortcutSource(pinnedShortcuts = listOf(shortcut("new-tab")))
        val repository = DuoShortcutRepository(source)
        assertEquals(1, repository.pinnedShortcuts().size)

        source.hostPermission = false

        assertTrue(repository.pinnedShortcuts().isEmpty())
    }

    @Test fun iconsAreACacheHitOrNullAndAreNeverRenderedWithoutPermission() = runBlocking {
        val source = FakeShortcutSource(hostPermission = false)
        val repository = DuoShortcutRepository(source)
        val target = shortcut("new-tab")

        assertEquals(null, repository.iconFor(target, 128))
        assertEquals(null, repository.loadIcon(target, 128))
        assertEquals(0, source.iconRenders)
        assertEquals(null, repository.iconFor(target, 0))
    }

    @Test fun aSourceThatThrowsReadsAsNothingAvailable() = runBlocking {
        val repository = DuoShortcutRepository(ThrowingShortcutSource())
        val key = PinnedShortcutKey(CHROME, "new-tab", 0L)

        assertTrue(repository.shortcutsFor(CHROME_ID).isEmpty())
        assertTrue(repository.pinnedShortcuts().isEmpty())
        assertFalse(repository.startShortcut(shortcut("new-tab"), null))
        assertFalse(repository.pin(shortcut("new-tab")))
        assertEquals(
            PinnedShortcutState.Unavailable(key, ShortcutUnavailableReason.REMOVED),
            repository.resolve(key),
        )
    }
}
