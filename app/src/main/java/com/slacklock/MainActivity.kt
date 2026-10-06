package com.slacklock

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {

    private val presetDurationsMinutes = longArrayOf(
        30L,
        60L,
        120L,
        240L,
        24L * 60L,
        3L * 24L * 60L,
        7L * 24L * 60L,
        BlockState.MAX_DURATION_MINUTES
    )
    private val customMinuteValues = intArrayOf(0, 15, 30, 45)

    private lateinit var bigButton: Button
    private lateinit var statusText: TextView
    private lateinit var helpText: TextView
    private lateinit var accountButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bigButton = findViewById(R.id.big_button)
        statusText = findViewById(R.id.status_text)
        helpText = findViewById(R.id.help_text)
        accountButton = findViewById(R.id.account_button)

        bigButton.setOnClickListener { handleButtonPress() }
        accountButton.setOnClickListener { showWorkAccountDialog(onSaved = ::refreshUi) }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val accessibilityEnabled = isAccessibilityEnabled()
        val locked = BlockState.lockedTargets(this)
        val unlocked = LockTarget.entries - locked.toSet()

        if (locked.isEmpty()) {
            statusText.visibility = View.GONE
        } else {
            val lines = locked.map {
                getString(
                    R.string.status_target_locked,
                    targetName(it),
                    formatUntil(BlockState.blockUntilMillis(this, it))
                )
            }.toMutableList()
            lines += getString(
                if (accessibilityEnabled) R.string.status_enforcing else R.string.status_not_enforcing
            )
            if (LockTarget.WORK_GMAIL in locked) {
                BlockState.lastGmailCheck(this)?.let { (sawWork, at) ->
                    lines += getString(
                        if (sawWork) R.string.status_gmail_check_work else R.string.status_gmail_check_other,
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
                    )
                }
            }
            statusText.text = lines.joinToString("\n")
            statusText.visibility = View.VISIBLE
        }

        when {
            locked.isNotEmpty() && !accessibilityEnabled -> {
                bigButton.visibility = View.VISIBLE
                bigButton.text = getString(R.string.enable_enforcement_text)
                helpText.text = getString(R.string.help_locked_not_enforcing)
            }
            unlocked.isEmpty() -> {
                bigButton.visibility = View.GONE
                helpText.text = getString(R.string.help_locked)
            }
            else -> {
                bigButton.visibility = View.VISIBLE
                bigButton.text = getString(R.string.big_button_text)
                helpText.text = when {
                    locked.isNotEmpty() -> getString(R.string.help_partly_locked)
                    accessibilityEnabled -> getString(R.string.help_ready)
                    else -> getString(R.string.help_needs_permission)
                }
            }
        }

        val account = BlockState.workGmailAccount(this)
        accountButton.text = when {
            account == null -> getString(R.string.work_account_unset)
            LockTarget.WORK_GMAIL in locked -> getString(R.string.work_account_locked, account)
            else -> getString(R.string.work_account_set, account)
        }
        accountButton.isEnabled = LockTarget.WORK_GMAIL !in locked
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

        if (BlockState.lockedTargets(this).size < LockTarget.entries.size) {
            showTargetPickerDialog()
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
                    showTargetPickerDialog()
                } else {
                    showEnableAccessibilityDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTargetPickerDialog() {
        val targets = LockTarget.entries - BlockState.lockedTargets(this).toSet()
        val account = BlockState.workGmailAccount(this)
        val labels = targets.map {
            when {
                it != LockTarget.WORK_GMAIL -> targetName(it)
                account != null -> getString(R.string.target_work_gmail_with_account, account)
                else -> getString(R.string.target_work_gmail_needs_setup)
            }
        }.toTypedArray()
        val checked = BooleanArray(targets.size) { targets[it] == LockTarget.SLACK }

        AlertDialog.Builder(this)
            .setTitle(R.string.targets_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.duration_continue) { _, _ ->
                val selected = targets.filterIndexed { i, _ -> checked[i] }
                when {
                    selected.isEmpty() -> {
                        Toast.makeText(this, R.string.targets_none_selected, Toast.LENGTH_SHORT).show()
                        showTargetPickerDialog()
                    }
                    LockTarget.WORK_GMAIL in selected && BlockState.workGmailAccount(this) == null ->
                        showWorkAccountDialog(onSaved = { showDurationPickerDialog(selected) })
                    else -> showDurationPickerDialog(selected)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWorkAccountDialog(onSaved: () -> Unit) {
        if (BlockState.isBlocked(this, LockTarget.WORK_GMAIL)) {
            Toast.makeText(this, R.string.work_account_cannot_change, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = getString(R.string.work_account_hint)
            setText(BlockState.workGmailAccount(this@MainActivity) ?: "")
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.work_account_title)
            .setMessage(R.string.work_account_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val normalised = WorkAccount.normalise(input.text.toString())
                when {
                    normalised == null -> {
                        Toast.makeText(this, R.string.work_account_invalid, Toast.LENGTH_SHORT).show()
                        showWorkAccountDialog(onSaved)
                    }
                    !BlockState.setWorkGmailAccount(this, normalised) ->
                        Toast.makeText(this, R.string.work_account_cannot_change, Toast.LENGTH_SHORT).show()
                    else -> onSaved()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDurationPickerDialog(targets: List<LockTarget>) {
        val labels = arrayOf(
            getString(R.string.duration_30_minutes),
            getString(R.string.duration_1_hour),
            getString(R.string.duration_2_hours),
            getString(R.string.duration_4_hours),
            getString(R.string.duration_1_day),
            getString(R.string.duration_3_days),
            getString(R.string.duration_7_days),
            getString(R.string.duration_14_days),
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
                        showStartBlockConfirmation(targets, until)
                    }
                    which == presetDurationsMinutes.size -> {
                        showStartBlockConfirmation(targets, BlockState.nextBlockUntilMillis())
                    }
                    else -> showCustomDurationDialog(targets)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomDurationDialog(targets: List<LockTarget>) {
        val daysPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = BlockState.MAX_DURATION_DAYS
            displayedValues = Array(BlockState.MAX_DURATION_DAYS + 1) {
                getString(R.string.duration_days_picker, it)
            }
            value = 0
            wrapSelectorWheel = false
        }
        val hoursPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            displayedValues = Array(24) { getString(R.string.duration_hours_picker, it) }
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
            addView(daysPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(hoursPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(minutesPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.custom_duration_title)
            .setView(container)
            .setPositiveButton(R.string.duration_continue) { _, _ ->
                val durationMinutes = daysPicker.value * 24L * 60L +
                    hoursPicker.value * 60L +
                    customMinuteValues[minutesPicker.value]
                if (!BlockState.isValidDurationMinutes(durationMinutes)) {
                    Toast.makeText(this, R.string.duration_invalid, Toast.LENGTH_SHORT).show()
                    showCustomDurationDialog(targets)
                } else {
                    showStartBlockConfirmation(
                        targets,
                        BlockState.blockUntilMillisForDurationMinutes(durationMinutes)
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showStartBlockConfirmation(targets: List<LockTarget>, untilMillis: Long) {
        val names = when (targets.size) {
            1 -> targetName(targets[0])
            else -> getString(R.string.targets_and, targetName(targets[0]), targetName(targets[1]))
        }
        var message = getString(R.string.confirm_message, names, formatUntil(untilMillis))
        if (LockTarget.WORK_GMAIL in targets) message += getString(R.string.confirm_gmail_note)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_title, names))
            .setMessage(message)
            .setPositiveButton(R.string.confirm_lock) { _, _ ->
                BlockState.startBlockUntil(this, targets, untilMillis)
                refreshUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun targetName(target: LockTarget): String = getString(
        when (target) {
            LockTarget.SLACK -> R.string.target_slack
            LockTarget.WORK_GMAIL -> R.string.target_work_gmail
        }
    )

    private fun formatUntil(untilMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(untilMillis))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
