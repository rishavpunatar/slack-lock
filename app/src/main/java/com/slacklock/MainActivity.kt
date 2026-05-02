package com.slacklock

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {

    private val presetDurationsMinutes = longArrayOf(30, 60, 120, 240)
    private val customMinuteValues = intArrayOf(0, 15, 30, 45)

    private lateinit var bigButton: Button
    private lateinit var statusText: TextView
    private lateinit var helpText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bigButton = findViewById(R.id.big_button)
        statusText = findViewById(R.id.status_text)
        helpText = findViewById(R.id.help_text)

        bigButton.setOnClickListener { handleButtonPress() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val accessibilityEnabled = isAccessibilityEnabled()
        if (BlockState.isBlocked(this)) {
            val untilText = formatUntil(BlockState.blockUntilMillis(this))
            statusText.text = if (accessibilityEnabled)
                getString(R.string.status_blocked_enforcing, untilText)
            else
                getString(R.string.status_blocked_not_enforcing, untilText)
            statusText.visibility = View.VISIBLE
            bigButton.visibility = if (accessibilityEnabled) View.GONE else View.VISIBLE
            bigButton.text = getString(R.string.enable_enforcement_text)
            helpText.text = if (accessibilityEnabled)
                getString(R.string.help_locked)
            else
                getString(R.string.help_locked_not_enforcing)
        } else {
            statusText.visibility = View.GONE
            bigButton.visibility = View.VISIBLE
            bigButton.text = getString(R.string.big_button_text)
            helpText.text = if (accessibilityEnabled)
                getString(R.string.help_ready)
            else
                getString(R.string.help_needs_permission)
        }
    }

    private fun handleButtonPress() {
        if (!BlockState.hasAcceptedAccessibilityDisclosure(this)) {
            showAccessibilityDisclosureDialog()
            return
        }

        if (!isAccessibilityEnabled()) {
            showEnableAccessibilityDialog()
            return
        }

        if (!BlockState.isBlocked(this)) {
            showDurationPickerDialog()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, BlockerService::class.java).flattenToString()
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (info in enabled) {
            val id = info.id ?: continue
            if (id.equals(expected, ignoreCase = true)) return true
            // Some OEMs format the id slightly differently — fall back to substring check.
            if (id.contains(packageName) && id.contains("BlockerService")) return true
        }
        // Belt-and-braces: also check the secure setting string.
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun showEnableAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.enable_title)
            .setMessage(R.string.enable_message)
            .setPositiveButton(R.string.enable_open) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAccessibilityDisclosureDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setPositiveButton(R.string.disclosure_accept) { _, _ ->
                BlockState.acceptAccessibilityDisclosure(this)
                if (isAccessibilityEnabled()) {
                    showDurationPickerDialog()
                } else {
                    showEnableAccessibilityDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDurationPickerDialog() {
        val labels = arrayOf(
            getString(R.string.duration_30_minutes),
            getString(R.string.duration_1_hour),
            getString(R.string.duration_2_hours),
            getString(R.string.duration_4_hours),
            getString(R.string.duration_until_6_am),
            getString(R.string.duration_custom)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.duration_title)
            .setItems(labels) { _, which ->
                when {
                    which < presetDurationsMinutes.size -> {
                        val until = BlockState.blockUntilMillisForDurationMinutes(
                            presetDurationsMinutes[which]
                        )
                        showStartBlockConfirmation(until)
                    }
                    which == presetDurationsMinutes.size -> {
                        showStartBlockConfirmation(BlockState.nextBlockUntilMillis())
                    }
                    else -> showCustomDurationDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomDurationDialog() {
        val hoursPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 24
            displayedValues = Array(25) { getString(R.string.duration_hours_picker, it) }
            value = 1
            wrapSelectorWheel = false
        }
        val minutesPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = customMinuteValues.lastIndex
            displayedValues = customMinuteValues.map {
                getString(R.string.duration_minutes_picker, it)
            }.toTypedArray()
            value = 0
            wrapSelectorWheel = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(hoursPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(minutesPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.custom_duration_title)
            .setView(container)
            .setPositiveButton(R.string.duration_continue) { _, _ ->
                val durationMinutes = hoursPicker.value * 60L + customMinuteValues[minutesPicker.value]
                if (durationMinutes <= 0) {
                    Toast.makeText(this, R.string.duration_invalid, Toast.LENGTH_SHORT).show()
                    showCustomDurationDialog()
                } else {
                    showStartBlockConfirmation(
                        BlockState.blockUntilMillisForDurationMinutes(durationMinutes)
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showStartBlockConfirmation(untilMillis: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(getString(R.string.confirm_message, formatUntil(untilMillis)))
            .setPositiveButton(R.string.confirm_lock) { _, _ ->
                BlockState.startBlockUntil(this, untilMillis)
                refreshUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatUntil(untilMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(untilMillis))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
