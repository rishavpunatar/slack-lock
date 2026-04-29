package com.slacknuke

import android.content.Context
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Persists the "blocked until" timestamp.
 *
 * The block UI offers no way to clear or shorten an active block —
 * the only way out is to wait until 6am, or uninstall the app.
 */
object BlockState {

    private const val PREFS = "slack_nuke_prefs"
    private const val KEY_BLOCK_UNTIL = "block_until_millis"

    /** Slack's Play Store package name. */
    const val SLACK_PACKAGE = "com.Slack"

    fun blockUntilMillis(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_BLOCK_UNTIL, 0L)

    fun isBlocked(ctx: Context, nowMillis: Long = System.currentTimeMillis()): Boolean =
        blockUntilMillis(ctx) > nowMillis

    /**
     * Sets the block to expire at the next occurrence of 06:00 in the device's local time zone.
     * Pressed at 23:00 → blocks until 06:00 tomorrow (7 hours).
     * Pressed at 03:00 → blocks until 06:00 today (3 hours).
     */
    fun startBlockUntilNext6am(ctx: Context) {
        val now = ZonedDateTime.now()
        var target = now.toLocalDate().atTime(LocalTime.of(6, 0)).atZone(now.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val until = target.toInstant().toEpochMilli()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BLOCK_UNTIL, until)
            .apply()
    }
}
