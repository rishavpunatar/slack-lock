package com.slacklock

import android.content.Context
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Persists the "blocked until" timestamp.
 *
 * The app UI offers no way to clear or shorten an active block. Early exits
 * require leaving the app to disable the accessibility service or uninstall.
 */
object BlockState {

    private const val PREFS = "slack_lock_prefs"
    private const val KEY_BLOCK_UNTIL = "block_until_millis"
    private const val KEY_ACCESSIBILITY_DISCLOSURE_ACCEPTED = "accessibility_disclosure_accepted"
    private val WAKE_TIME = LocalTime.of(6, 0)

    /** Slack's Play Store package name. */
    const val SLACK_PACKAGE = "com.Slack"

    fun blockUntilMillis(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_BLOCK_UNTIL, 0L)

    fun isBlocked(ctx: Context, nowMillis: Long = System.currentTimeMillis()): Boolean =
        blockUntilMillis(ctx) > nowMillis

    fun hasAcceptedAccessibilityDisclosure(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACCESSIBILITY_DISCLOSURE_ACCEPTED, false)

    fun acceptAccessibilityDisclosure(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCESSIBILITY_DISCLOSURE_ACCEPTED, true)
            .apply()
    }

    fun nextBlockUntilMillis(now: ZonedDateTime = ZonedDateTime.now()): Long {
        var target = now.toLocalDate().atTime(WAKE_TIME).atZone(now.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    fun blockUntilMillisForDurationMinutes(
        durationMinutes: Long,
        now: ZonedDateTime = ZonedDateTime.now()
    ): Long = now.plusMinutes(durationMinutes).toInstant().toEpochMilli()

    fun startBlockUntil(ctx: Context, untilMillis: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BLOCK_UNTIL, untilMillis)
            .apply()
    }

    /**
     * Sets the block to expire at the next occurrence of 06:00 in the device's local time zone.
     * Pressed at 23:00 → blocks until 06:00 tomorrow (7 hours).
     * Pressed at 03:00 → blocks until 06:00 today (3 hours).
     */
    fun startBlockUntilNext6am(ctx: Context) {
        startBlockUntil(ctx, nextBlockUntilMillis())
    }
}
