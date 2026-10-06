package com.slacklock

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalTime
import java.time.ZonedDateTime

/** Something Slack Lock can lock, each with its own independent timer. */
enum class LockTarget(val packageName: String, internal val prefsKey: String) {
    // Slack keeps the original key so a lock started before the upgrade survives it.
    SLACK("com.Slack", "block_until_millis"),
    WORK_GMAIL("com.google.android.gm", "work_gmail_block_until_millis")
}

/**
 * Persists the "blocked until" timestamps and the work Gmail account setting.
 *
 * The app UI offers no way to clear or shorten an active block. Early exits
 * require leaving the app to disable the accessibility service or uninstall.
 */
object BlockState {

    private const val PREFS = "slack_lock_prefs"
    // v2: the disclosure changed when Gmail support added screen reading, so it must be re-accepted.
    private const val KEY_ACCESSIBILITY_DISCLOSURE_ACCEPTED = "accessibility_disclosure_accepted_v2"
    private const val KEY_WORK_GMAIL_ACCOUNT = "work_gmail_account"
    private const val KEY_GMAIL_LAST_SAW_WORK = "gmail_last_saw_work"
    private const val KEY_GMAIL_LAST_CHECKED_AT = "gmail_last_checked_at"
    private val WAKE_TIME = LocalTime.of(6, 0)
    const val MAX_DURATION_DAYS = 14
    const val MAX_DURATION_MINUTES = MAX_DURATION_DAYS * 24L * 60L

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun blockUntilMillis(ctx: Context, target: LockTarget): Long =
        prefs(ctx).getLong(target.prefsKey, 0L)

    fun isBlocked(
        ctx: Context,
        target: LockTarget,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = blockUntilMillis(ctx, target) > nowMillis

    fun lockedTargets(ctx: Context): List<LockTarget> =
        LockTarget.entries.filter { isBlocked(ctx, it) }

    fun hasAcceptedAccessibilityDisclosure(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ACCESSIBILITY_DISCLOSURE_ACCEPTED, false)

    fun acceptAccessibilityDisclosure(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ACCESSIBILITY_DISCLOSURE_ACCEPTED, true).apply()
    }

    /** The normalised work account ("name@example.org") or domain ("@example.org"), if set. */
    fun workGmailAccount(ctx: Context): String? =
        prefs(ctx).getString(KEY_WORK_GMAIL_ACCOUNT, null)

    /**
     * Saves the work account. Refused while a Gmail lock is active, otherwise
     * changing it would be a one-tap way out of the lock.
     */
    fun setWorkGmailAccount(ctx: Context, normalisedAccount: String): Boolean {
        if (isBlocked(ctx, LockTarget.WORK_GMAIL)) return false
        prefs(ctx).edit().putString(KEY_WORK_GMAIL_ACCOUNT, normalisedAccount).apply()
        return true
    }

    /** Records the outcome of the latest Gmail account check, so the app can show it's working. */
    fun recordGmailCheck(ctx: Context, sawWorkAccount: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        prefs(ctx).edit()
            .putBoolean(KEY_GMAIL_LAST_SAW_WORK, sawWorkAccount)
            .putLong(KEY_GMAIL_LAST_CHECKED_AT, nowMillis)
            .apply()
    }

    /** (sawWorkAccount, checkedAtMillis) of the latest Gmail check, or null if there hasn't been one. */
    fun lastGmailCheck(ctx: Context): Pair<Boolean, Long>? {
        val at = prefs(ctx).getLong(KEY_GMAIL_LAST_CHECKED_AT, 0L)
        if (at == 0L) return null
        return prefs(ctx).getBoolean(KEY_GMAIL_LAST_SAW_WORK, false) to at
    }

    fun nextBlockUntilMillis(now: ZonedDateTime = ZonedDateTime.now()): Long {
        var target = now.toLocalDate().atTime(WAKE_TIME).atZone(now.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    fun blockUntilMillisForDurationMinutes(
        durationMinutes: Long,
        now: ZonedDateTime = ZonedDateTime.now()
    ): Long {
        require(isValidDurationMinutes(durationMinutes)) {
            "Duration must be between 1 minute and $MAX_DURATION_DAYS days."
        }
        return now.plusMinutes(durationMinutes).toInstant().toEpochMilli()
    }

    fun isValidDurationMinutes(durationMinutes: Long): Boolean =
        durationMinutes in 1..MAX_DURATION_MINUTES

    /** Starts (or extends) a lock on each target. An existing lock is never shortened. */
    fun startBlockUntil(ctx: Context, targets: Collection<LockTarget>, untilMillis: Long) {
        val editor = prefs(ctx).edit()
        for (target in targets) {
            editor.putLong(target.prefsKey, maxOf(blockUntilMillis(ctx, target), untilMillis))
        }
        editor.apply()
    }
}
