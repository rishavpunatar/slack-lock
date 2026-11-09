package com.slacklock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [GmailServiceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BlockerServiceTest {
    private lateinit var service: BlockerService
    private lateinit var shadow: GmailServiceShadow

    @Before
    fun setUp() {
        service = Robolectric.buildService(BlockerService::class.java).create().get()
        shadow = Shadow.extract(service)
        service.serviceInfo = AccessibilityServiceInfo()
        BlockState.prefs(service).edit().clear().commit()
        BlockState.setWorkGmailAccount(service, "jane@work.example.org")
        BlockState.startBlockUntil(service, listOf(LockTarget.WORK_GMAIL), System.currentTimeMillis() + 60_000)
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
    }

    @After
    fun tearDown() { service.onDestroy() }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun node(pkg: String = LockTarget.WORK_GMAIL.packageName) = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg
        isVisibleToUser = true
    }

    private fun button(email: String, visible: Boolean = true) = node().apply {
        viewIdResourceName = "com.google.android.gm:id/selected_account_disc_gmail"
        contentDescription = "Signed in as Jane $email"
        isVisibleToUser = visible
    }

    private fun show(vararg buttons: AccessibilityNodeInfo) {
        shadow.activeRoot = node().also { root -> buttons.forEach { shadowOf(root).addChild(it) } }
        event()
    }

    private fun event(pkg: String = LockTarget.WORK_GMAIL.packageName, type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        service.onAccessibilityEvent(AccessibilityEvent.obtain(type).apply { packageName = pkg })
    }

    private fun covered(): Boolean {
        val curtain = ReflectionHelpers.getField<Curtain>(service, "curtain")
        return ReflectionHelpers.getField<View?>(curtain, "view") != null
    }

    @Test
    fun personalAccountRemainsUsableAcrossRepeatedOpensAndContentEvents() {
        repeat(3) {
            show(button("jane@gmail.com"))
            repeat(20) {
                event(type = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                advance(100)
            }
            assertFalse(covered())
            assertTrue(shadow.globalActionsPerformed.isEmpty())
            event("com.example.launcher")
        }
        assertEquals(false, BlockState.lastGmailCheck(service)?.first)
    }

    @Test
    fun configuredWorkAccountStillGoesHome() {
        show(button("jane@work.example.org"))
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_HOME), shadow.globalActionsPerformed)
    }

    @Test
    fun switchingFromPersonalToWorkStillEnforcesLock() {
        show(button("jane@gmail.com"))
        shadow.activeRoot = button("jane@work.example.org")
        event(type = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        advance(100)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_HOME), shadow.globalActionsPerformed)
    }

    @Test
    fun hiddenWorkButtonCannotBlockVisiblePersonalAccount() {
        show(button("jane@work.example.org", visible = false), button("jane@gmail.com"))
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        assertFalse(covered())
    }

    @Test
    fun conflictingAccountButtonsNeverPickWorkByTraversalOrder() {
        show(button("jane@work.example.org"), button("jane@gmail.com"))
        advance(4000)
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        assertFalse(covered())
    }

    @Test
    fun unreadableScreenNeverNavigatesAwayOrKeepsCoverAlive() {
        show()
        assertTrue(covered())
        repeat(100) {
            advance(100)
            event(type = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        advance(100)
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        assertFalse(covered())
        // Even after the timeout, a later identifiable work account must be blocked.
        show(button("jane@work.example.org"))
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, shadow.globalActionsPerformed.last())
    }

    @Test
    fun pendingAccountCheckCannotNavigateAnotherApp() {
        show()
        // Simulate a foreground change before its accessibility event is delivered.
        shadow.activeRoot = node("com.android.settings")
        advance(4000)
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        assertFalse(covered())
    }

    @Test
    fun leavingForUnlockedSlackCancelsPendingGmailChecks() {
        show()
        shadow.activeRoot = node(LockTarget.SLACK.packageName)
        event(LockTarget.SLACK.packageName)
        advance(4000)
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        assertFalse(covered())
    }

    @Test
    fun slackLockStillGoesHome() {
        BlockState.startBlockUntil(service, listOf(LockTarget.SLACK), System.currentTimeMillis() + 60_000)
        shadow.activeRoot = node(LockTarget.SLACK.packageName)
        event(LockTarget.SLACK.packageName)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_HOME), shadow.globalActionsPerformed)
    }

    @Test
    fun endingLockReleasesCoverAndDoesNotPressBack() {
        show()
        BlockState.prefs(service).edit().putLong(LockTarget.WORK_GMAIL.prefsKey, 0L).commit()
        advance(4000)
        assertTrue(shadow.globalActionsPerformed.isEmpty())
        assertFalse(covered())
    }

    @Test
    fun repeatedCurtainShowCannotExtendFourSecondSafetyDeadline() {
        val curtain = Curtain(service)
        curtain.show()
        repeat(5) { advance(700); curtain.show() }
        advance(501)
        assertNull(ReflectionHelpers.getField<View?>(curtain, "view"))
    }
}

/** Supplies the current window while retaining Robolectric's global-action recording. */
@Implements(AccessibilityService::class)
class GmailServiceShadow : ShadowAccessibilityService() {
    var activeRoot: AccessibilityNodeInfo? = null

    @Implementation
    protected fun getRootInActiveWindow(): AccessibilityNodeInfo? = activeRoot
}
