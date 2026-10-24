package com.screenlink.share

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
    private lateinit var findBtn: Button

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
        findBtn = findViewById(R.id.findBtn)

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
        findBtn.setOnClickListener { findChromebook() }
        stopBtn.setOnClickListener { stopShare() }
        controlCheck.setOnCheckedChangeListener { _, _ -> refreshAccessUi() }

        applyDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDeepLink(intent)
    }

    private fun applyDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (data.scheme != "screenlink") return

        var hostUrl = data.getQueryParameter("url")
            ?: data.getQueryParameter("host")
            ?: data.getQueryParameter("u")
        if (hostUrl.isNullOrBlank()) {
            val ssp = data.schemeSpecificPart ?: ""
            val idx = ssp.indexOf("http://")
            val idxs = ssp.indexOf("https://")
            val start = when {
                idx >= 0 && (idxs < 0 || idx < idxs) -> idx
                idxs >= 0 -> idxs
                else -> -1
            }
            if (start >= 0) hostUrl = ssp.substring(start)
        }
        if (hostUrl.isNullOrBlank()) return

        hostUrl = hostUrl.trim()
        hostInput.setText(hostUrl)
        getSharedPreferences("screenlink", MODE_PRIVATE).edit()
            .putString("host", hostUrl)
            .apply()
        statusText.text = "Link received via deep link. Enable Accessibility if needed, then Start."
        Toast.makeText(this, "Chromebook link loaded", Toast.LENGTH_SHORT).show()
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
                statusText.text = "Accessibility on. Find Chromebook or paste link, then Start."
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

    private fun findChromebook() {
        findBtn.isEnabled = false
        statusText.text = "Scanning Wi‑Fi for ScreenLink…"
        Thread {
            val found = mutableListOf<String>()
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val ipInt = wm.connectionInfo.ipAddress
                if (ipInt == 0) {
                    runOnUiThread {
                        statusText.text = "Not on Wi‑Fi — connect phone to the same network as the Chromebook"
                        findBtn.isEnabled = true
                    }
                    return@Thread
                }
                val a = ipInt and 0xff
                val b = ipInt shr 8 and 0xff
                val c = ipInt shr 16 and 0xff
                val base = "$a.$b.$c."
                val self = ipInt shr 24 and 0xff
                for (i in 1..254) {
                    if (i == self) continue
                    val host = "$base$i"
                    try {
                        val url = java.net.URL("http://$host:8080/")
                        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 180
                            readTimeout = 180
                            requestMethod = "GET"
                            instanceFollowRedirects = false
                        }
                        val code = conn.responseCode
                        val body = try {
                            conn.inputStream.bufferedReader().readText()
                        } catch (_: Exception) {
                            try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { "" }
                        } ?: ""
                        conn.disconnect()
                        if (code in 200..499 && body.contains("ScreenLink", ignoreCase = true)) {
                            found.add("http://$host:8080")
                            if (found.size >= 3) break
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Scan failed: ${e.message}"
                    findBtn.isEnabled = true
                }
                return@Thread
            }
            runOnUiThread {
                findBtn.isEnabled = true
                if (found.isEmpty()) {
                    statusText.text = "No ScreenLink receiver found. Start receiver on Chromebook, same Wi‑Fi."
                } else {
                    val pick = found[0]
                    val cur = hostInput.text.toString()
                    val token = Regex("[?&]t=([A-Za-z0-9_-]+)").find(cur)?.groupValues?.getOrNull(1)
                    val filled = if (!token.isNullOrBlank()) "$pick?t=$token" else pick
                    hostInput.setText(filled)
                    statusText.text = if (token.isNullOrBlank()) {
                        "Found ${found.size}: $pick — add ?t=TOKEN from Chromebook /session"
                    } else {
                        "Found ${found.size}: filled $filled"
                    }
                    Toast.makeText(this, "Chromebook found", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
