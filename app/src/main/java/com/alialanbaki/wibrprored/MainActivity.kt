package com.alialanbaki.wibrprored

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private val requestPermissionsCode = 201

    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager

    private lateinit var tvConnectionInfo: TextView
    private lateinit var lvNetworks: ListView
    private lateinit var tvSelectedNetworkSummary: TextView
    private lateinit var spnWordlist: Spinner
    private lateinit var tvWordlistDetails: TextView
    private lateinit var tvDelaySeconds: TextView

    private val discoveredTargets = mutableListOf<WifiTarget>()
    private val displayRows = mutableListOf<String>()
    private lateinit var listAdapter: ArrayAdapter<String>

    private var customWordlistLoaded = false

    private val wordlistPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                loadWordlistFromUri(uri)
            }
        }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fresh = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true) ?: true
            populateDiscoveredNetworks(fresh)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        tvConnectionInfo = findViewById(R.id.tvConnectionInfo)
        lvNetworks = findViewById(R.id.lvNetworks)
        tvSelectedNetworkSummary = findViewById(R.id.tvSelectedNetworkSummary)
        spnWordlist = findViewById(R.id.spnWordlist)
        tvWordlistDetails = findViewById(R.id.tvWordlistDetails)
        tvDelaySeconds = findViewById(R.id.tvDelaySeconds)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, displayRows)
        lvNetworks.adapter = listAdapter

        val presetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            WordlistPresets.presetNames()
        )
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spnWordlist.adapter = presetAdapter

        spnWordlist.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!customWordlistLoaded) {
                    val presetName = WordlistPresets.presetNames()[position]
                    val list = WordlistPresets.passwordsForPresetIndex(position)
                    AppSession.activeWordlistName = presetName
                    AppSession.activePasswords = list
                    tvWordlistDetails.text = "القاموس النشط: $presetName (${list.size} كلمة)"
                }
                customWordlistLoaded = false
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnHelp).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        findViewById<Button>(R.id.btnSearchWifi).setOnClickListener {
            ensurePermissionsAndScan()
        }

        findViewById<Button>(R.id.btnPickWordlistFile).setOnClickListener {
            wordlistPickerLauncher.launch(arrayOf("text/*"))
        }

        findViewById<Button>(R.id.btnDelayMinus).setOnClickListener {
            if (AppSession.delaySeconds > 1) {
                AppSession.delaySeconds -= 1
                updateDelayLabel()
            }
        }

        findViewById<Button>(R.id.btnDelayPlus).setOnClickListener {
            if (AppSession.delaySeconds < 60) {
                AppSession.delaySeconds += 1
                updateDelayLabel()
            }
        }

        lvNetworks.setOnItemClickListener { _, _, position, _ ->
            if (position in discoveredTargets.indices) {
                val chosen = discoveredTargets[position]
                AppSession.selectedTarget = chosen
                tvSelectedNetworkSummary.text =
                    "الشبكة المختارة: ${chosen.ssid} (${chosen.signalPercent}%)"
            }
        }

        findViewById<Button>(R.id.btnStartCracking).setOnClickListener {
            val target = AppSession.selectedTarget
            if (target == null) {
                Toast.makeText(this, "اختر شبكة من القائمة أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (AppSession.activePasswords.isEmpty()) {
                Toast.makeText(this, "القاموس فارغ، اختر قاموساً أو أضف ملفاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, MonitorActivity::class.java))
        }

        updateDelayLabel()
        refreshConnectionStatusBanner()
        ensurePermissionsAndScan()
    }

    override fun onResume() {
        super.onResume()
        updateDelayLabel()
        refreshConnectionStatusBanner()
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: Exception) {
        }
    }

    private fun updateDelayLabel() {
        val s = AppSession.delaySeconds
        tvDelaySeconds.text = "سرعة الربط: $s ${if (s == 1) "ثانية" else "ثواني"}"
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshConnectionStatusBanner() {
        val wifiState = if (wifiManager.isWifiEnabled) "الواي فاي مفعّل" else "الواي فاي مطفأ"
        val gpsState = if (isLocationEnabled()) "الموقع (GPS) مفعّل" else "الموقع (GPS) مطفأ"
        @Suppress("DEPRECATION")
        val connInfo = wifiManager.connectionInfo
        @Suppress("DEPRECATION")
        val rawSsid = connInfo?.ssid?.replace("\"", "") ?: ""
        val connectedText = if (wifiManager.isWifiEnabled && rawSsid.isNotBlank() && rawSsid != "<unknown ssid>") {
            "متصل حالياً بـ: $rawSsid (يُفضل فصل الشبكة قبل الفحص)"
        } else {
            "غير متصل بشبكة حالياً (جاهز للفحص)"
        }
        tvConnectionInfo.text = "$wifiState | $gpsState\n$connectedText"
    }

    private fun ensurePermissionsAndScan() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestPermissionsCode)
            return
        }
        startNetworkScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPermissionsCode) {
            startNetworkScan()
        }
    }

    private fun startNetworkScan() {
        refreshConnectionStatusBanner()
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "فعّل الواي فاي والموقع (GPS) أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        val ok = wifiManager.startScan()
        if (!ok) {
            populateDiscoveredNetworks(false)
        }
    }

    private fun populateDiscoveredNetworks(fresh: Boolean) {
        refreshConnectionStatusBanner()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        @Suppress("DEPRECATION")
        val scanList = wifiManager.scanResults.orEmpty()
        discoveredTargets.clear()
        displayRows.clear()

        for (item in scanList) {
            @Suppress("DEPRECATION")
            val ssid = if (item.SSID.isNullOrBlank()) "<شبكة مخفية>" else item.SSID
            val bssid = item.BSSID ?: "00:00:00:00:00:00"
            val caps = item.capabilities ?: ""
            val signal = WifiManager.calculateSignalLevel(item.level, 100)
            val target = WifiTarget(ssid, bssid, caps, signal)
            discoveredTargets.add(target)
            displayRows.add(target.displayLabel())
        }

        listAdapter.notifyDataSetChanged()
        if (discoveredTargets.isEmpty()) {
            tvSelectedNetworkSummary.text =
                "لم تظهر شبكات — تأكد من تفعيل GPS ومنح إذن الموقع وفصل الشبكة الحالية"
        } else {
            val tag = if (fresh) "تحديث جديد" else "نتائج مخزنة"
            tvSelectedNetworkSummary.text =
                "تم العثور على ${discoveredTargets.size} شبكة ($tag) — اختر شبكة"
        }
    }

    private fun loadWordlistFromUri(uri: Uri) {
        try {
            val words = mutableListOf<String>()
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val cleaned = line.trim()
                        if (cleaned.isNotEmpty()) {
                            words.add(cleaned)
                        }
                        line = reader.readLine()
                    }
                }
            }
            if (words.isEmpty()) {
                Toast.makeText(this, "الملف فارغ أو غير صالح", Toast.LENGTH_SHORT).show()
                return
            }
            customWordlistLoaded = true
            val fileLabel = uri.lastPathSegment?.substringAfterLast('/') ?: "قاموس من الذاكرة"
            AppSession.activeWordlistName = fileLabel
            AppSession.activePasswords = words
            tvWordlistDetails.text = "القاموس النشط: $fileLabel (${words.size} كلمة)"
            Toast.makeText(this, "تم تحميل ${words.size} كلمة بنجاح", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر قراءة الملف: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}