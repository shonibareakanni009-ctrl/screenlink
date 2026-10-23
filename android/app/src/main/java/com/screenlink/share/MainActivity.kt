package com.screenlink.share

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var audioCheck: CheckBox
    private lateinit var controlCheck: CheckBox
    private lateinit var qualitySpinner: Spinner
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var accessBtn: Button

    private val reqProjection = 1001
    private val reqPerms = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.hostInput)
        audioCheck = findViewById(R.id.audioCheck)
        controlCheck = findViewById(R.id.controlCheck)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        statusText = findViewById(R.id.statusText)
        accessBtn = findViewById(R.id.accessBtn)

        val prefs = getSharedPreferences("screenlink", MODE_PRIVATE)
        hostInput.setText(prefs.getString("host", "http://192.168.1.1:8080"))
        qualitySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Low (faster)", "Medium", "High", "HD", "Full")
        )
        qualitySpinner.setSelection(prefs.getInt("quality", 0))

        if (Build.VERSION.SDK_INT < 29) {
            audioCheck.isChecked = false
            audioCheck.isEnabled = false
        }

        accessBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        startBtn.setOnClickListener { ensurePermsThenStart() }
        stopBtn.setOnClickListener { stopShare() }
        controlCheck.setOnCheckedChangeListener { _, _ -> refreshAccessUi() }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessUi()
    }

    private fun accessibilityOn(): Boolean = TapAccessibilityService.instance != null

    private fun refreshAccessUi() {
        val on = accessibilityOn()
        if (on) {
            accessBtn.visibility = View.GONE
            if (controlCheck.isChecked) {
                statusText.text = "Accessibility on. Paste Chromebook link and Start."
            }
        } else {
            accessBtn.visibility = View.VISIBLE
            accessBtn.text = if (controlCheck.isChecked) {
                "Enable Accessibility (required for control)"
            } else {
                "Open Accessibility settings (optional)"
            }
            accessBtn.isEnabled = true
        }
    }

    private fun ensurePermsThenStart() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (audioCheck.isChecked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), reqPerms)
            return
        }
        if (controlCheck.isChecked && !accessibilityOn()) {
            Toast.makeText(
                this,
                "Turn on ScreenLink in Accessibility, then return here",
                Toast.LENGTH_LONG
            ).show()
            accessBtn.visibility = View.VISIBLE
            return
        }
        requestProjection()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPerms) {
            if (controlCheck.isChecked && !accessibilityOn()) {
                refreshAccessUi()
                return
            }
            requestProjection()
        }
    }

    private fun requestProjection() {
        val host = hostInput.text.toString().trim().trimEnd('/')
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            Toast.makeText(this, "Enter full URL like http://192.168.x.x:8080?t=TOKEN", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("screenlink", MODE_PRIVATE).edit()
            .putString("host", host)
            .putInt("quality", qualitySpinner.selectedItemPosition)
            .apply()
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqProjection) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            statusText.text = "Screen capture denied"
            return
        }
        val host = hostInput.text.toString().trim().trimEnd('/')
        val i = Intent(this, ShareService::class.java).apply {
            action = ShareService.ACTION_START
            putExtra(ShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ShareService.EXTRA_DATA, data)
            putExtra(ShareService.EXTRA_HOST, host)
            putExtra(ShareService.EXTRA_QUALITY, qualitySpinner.selectedItemPosition)
            putExtra(ShareService.EXTRA_AUDIO, audioCheck.isChecked)
            putExtra(ShareService.EXTRA_CONTROL, controlCheck.isChecked && accessibilityOn())
        }
        ContextCompat.startForegroundService(this, i)
        startBtn.isEnabled = false
        stopBtn.isEnabled = true
        statusText.text = "Linked to $host"
    }

    private fun stopShare() {
        startService(Intent(this, ShareService::class.java).apply { action = ShareService.ACTION_STOP })
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
        statusText.text = "Stopped"
        refreshAccessUi()
    }
}
