package com.slacklock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

/**
 * Enforces active locks by sending the user back to the home screen.
 *
 * Slack: any Slack window during a Slack lock is sent home.
 *
 * Work Gmail: Gmail is one app for every account, so during a Gmail lock we read
 * Gmail's account button to see which account is showing. Work account → home.
 * Another account → left alone. Some Gmail screens (an open email, compose) have
 * no account button. If the account remains unreadable after a brief check,
 * Gmail stays usable; an unknown account is never a reason to navigate away.
 *
 * Android only reports a window after it's on screen, so while the account is
 * unknown, and while the Home animation plays, a [Curtain] covers the screen to
 * stop locked content flashing up.
 *
 * Outside a Gmail lock the service narrows itself back to Slack window events
 * only, exactly as before Gmail support existed.
 */
class BlockerService : AccessibilityService() {

    private enum class GmailAccount { UNKNOWN, WORK, OTHER }

    private var gmailAccount = GmailAccount.UNKNOWN
    private var unknownSince: Long? = null
    private var unknownTimedOut = false
    private val handler = Handler(Looper.getMainLooper())
    private val recheckGmail = Runnable { enforceGmail() }
    private var contentCheckPending = false
    private val contentCheck = Runnable {
        contentCheckPending = false
        enforceGmail()
    }
    private lateinit var curtain: Curtain
    private var widened: Boolean? = null
    private var transientPackages: Set<String> = emptySet()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LockTarget.WORK_GMAIL.prefsKey) updateEventScope()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        curtain = Curtain(this)
        // Windows that float over Gmail without the user leaving it.
        val imm = getSystemService(InputMethodManager::class.java)
        transientPackages = buildSet {
            add("com.android.systemui")
            add(packageName)
            imm?.enabledInputMethodList?.forEach { add(it.packageName) }
        }
        BlockState.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        updateEventScope()
    }

    override fun onDestroy() {
        BlockState.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacks(recheckGmail)
        handler.removeCallbacks(contentCheck)
        if (::curtain.isInitialized) curtain.hide()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        updateEventScope()
        when (pkg) {
            LockTarget.SLACK.packageName -> {
                if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
                forgetGmailAccount()
                if (BlockState.isBlocked(this, LockTarget.SLACK)) goHome()
                else curtain.hide()
            }
            LockTarget.WORK_GMAIL.packageName -> {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    enforceGmail()
                } else if (!contentCheckPending) {
                    // Content changes come in bursts (scrolling, loading): check at most every 100 ms.
                    contentCheckPending = true
                    handler.postDelayed(contentCheck, CONTENT_CHECK_MS)
                }
            }
            else -> {
                // The user moved to another app; recheck the account next time Gmail opens.
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    pkg !in transientPackages
                ) {
                    forgetGmailAccount()
                    curtain.hideAfter(HOME_SETTLE_MS)
                }
            }
        }
    }

    private fun enforceGmail() {
        if (!BlockState.isBlocked(this, LockTarget.WORK_GMAIL)) {
            updateEventScope()
            curtain.hide()
            return
        }
        val workAccount = BlockState.workGmailAccount(this) ?: return
        // Delayed events may arrive after the user leaves Gmail or opens a keyboard.
        // Never cover or navigate another app, or act on a cached work account then.
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != LockTarget.WORK_GMAIL.packageName) {
            curtain.hide()
            return
        }

        val signedIn = findSignedInAccount(root)
        if (signedIn != null) {
            val isWork = WorkAccount.matches(workAccount, signedIn)
            val detected = if (isWork) GmailAccount.WORK else GmailAccount.OTHER
            if (detected != gmailAccount) BlockState.recordGmailCheck(this, isWork)
            gmailAccount = detected
            unknownSince = null
            unknownTimedOut = false
            handler.removeCallbacks(recheckGmail)
        }

        when (gmailAccount) {
            GmailAccount.WORK -> goHome()
            GmailAccount.UNKNOWN -> {
                // An unknown account is not evidence of a work account. Give the
                // avatar time to load, then leave Gmail usable without pressing Back.
                if (unknownTimedOut) {
                    curtain.hide()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val since = unknownSince
                if (since == null) {
                    unknownSince = now
                    curtain.show()
                    handler.postDelayed(recheckGmail, ACCOUNT_GRACE_MS)
                } else if (now - since >= ACCOUNT_GRACE_MS) {
                    unknownTimedOut = true
                    curtain.hide()
                }
            }
            GmailAccount.OTHER -> curtain.hide()
        }
    }

    private fun goHome() {
        forgetGmailAccount()
        curtain.show()
        curtain.hideAfter(HOME_SETTLE_MS)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun forgetGmailAccount() {
        gmailAccount = GmailAccount.UNKNOWN
        unknownSince = null
        unknownTimedOut = false
        handler.removeCallbacks(recheckGmail)
        handler.removeCallbacks(contentCheck)
        contentCheckPending = false
    }

    /** Only visible, unambiguous account buttons can identify the active account. */
    private fun findSignedInAccount(root: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        val accounts = mutableSetOf<String>()
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (!node.isVisibleToUser) continue
            WorkAccount.signedInAccountFromNode(node.viewIdResourceName, node.contentDescription)
                ?.let { accounts.add(it) }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        // A truncated tree or account picker cannot safely identify a work account.
        return if (stack.isEmpty()) accounts.singleOrNull() else null
    }

    /**
     * During a Gmail lock: all apps' window changes (to notice leaving Gmail) plus
     * Gmail content changes (to catch account switches). Otherwise: Slack only.
     */
    private fun updateEventScope() {
        val wide = BlockState.isBlocked(this, LockTarget.WORK_GMAIL)
        if (wide == widened) return
        val info = serviceInfo ?: return
        if (wide) {
            info.packageNames = null
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            info.packageNames = arrayOf(LockTarget.SLACK.packageName)
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            forgetGmailAccount()
        }
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
        widened = wide
    }

    override fun onInterrupt() {
        forgetGmailAccount()
        if (::curtain.isInitialized) curtain.hide()
    }

    private companion object {
        const val ACCOUNT_GRACE_MS = 1500L
        const val CONTENT_CHECK_MS = 100L
        const val HOME_SETTLE_MS = 600L
        const val MAX_NODES = 3000
    }
}
