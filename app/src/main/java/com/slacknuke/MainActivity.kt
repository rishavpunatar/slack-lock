package com.slacknuke

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var bigButton: Button
    private lateinit var statusText: TextView
    private lateinit var helpText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bigButton = findViewById(R.id.big_button)
        statusText = findViewById(R.id.status_text)
        helpText = findViewById(R.id.help_text)

        bigButton.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                showEnableAccessibilityDialog()
            } else {
                BlockState.startBlockUntilNext6am(this)
                refreshUi()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        if (BlockState.isBlocked(this)) {
            val until = Date(BlockState.blockUntilMillis(this))
            val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
            statusText.text = getString(R.string.status_blocked, fmt.format(until))
            statusText.visibility = View.VISIBLE
            bigButton.visibility = View.GONE
            helpText.text = getString(R.string.help_locked)
        } else {
            statusText.visibility = View.GONE
            bigButton.visibility = View.VISIBLE
            helpText.text = if (isAccessibilityEnabled())
                getString(R.string.help_ready)
            else
                getString(R.string.help_needs_permission)
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
}
