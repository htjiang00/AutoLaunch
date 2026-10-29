package com.autolaunch.appscheduler

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.autolaunch.appscheduler.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    companion object {
        private const val REQ_APP_PICKER = 1001
        private const val REQ_NOTIFY_PERM = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        setupTimePickers()
        loadAppSelection()
        updateUI()

        // 选择应用
        binding.btnSelectApp.setOnClickListener {
            startActivityForResult(Intent(this, AppPickerActivity::class.java), REQ_APP_PICKER)
        }

        // 立即测试启动
        binding.btnTestLaunch.setOnClickListener {
            testLaunchApp()
        }

        // 启动定时服务
        binding.btnStart.setOnClickListener {
            startScheduler()
        }

        // 停止服务
        binding.btnStop.setOnClickListener {
            stopScheduler()
        }

        // 权限按钮
        binding.btnNotifyPerm.setOnClickListener { requestNotificationPermission() }
        binding.btnBatteryOpt.setOnClickListener { ignoreBatteryOptimization() }
        binding.btnExactAlarm.setOnClickListener { requestExactAlarmPermission() }

        // 自动申请通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY_PERM)
            }
        }
    }

    private fun setupTimePickers() {
        val times = prefs.getTimes()
        val pickers = listOf(binding.time1, binding.time2, binding.time3, binding.time4)
        pickers.forEachIndexed { index, picker ->
            val parts = times[index].split(":")
            if (parts.size == 2) {
                picker.hour = parts[0].toIntOrNull() ?: 0
                picker.minute = parts[1].toIntOrNull() ?: 0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                picker.setIs24HourView(true)
            }
        }
    }

    private fun loadAppSelection() {
        val pkg = prefs.targetPackage
        if (pkg.isNotEmpty()) {
            binding.tvAppName.text = prefs.targetAppName.ifEmpty { pkg }
            try {
                val icon = packageManager.getApplicationIcon(pkg)
                binding.ivAppIcon.setImageDrawable(icon)
            } catch (_: Exception) {}
        } else {
            binding.tvAppName.setText(R.string.not_selected)
        }
    }

    private fun updateUI() {
        if (prefs.serviceRunning) {
            binding.tvStatus.setText(R.string.status_running)
            binding.tvStatus.setTextColor(getColor(R.color.status_green))
        } else {
            binding.tvStatus.setText(R.string.status_stopped)
            binding.tvStatus.setTextColor(getColor(R.color.status_red))
        }
    }

    private fun collectTimes(): List<String> {
        val pickers = listOf(binding.time1, binding.time2, binding.time3, binding.time4)
        val result = mutableListOf<String>()
        pickers.forEachIndexed { index, picker ->
            val h = picker.hour
            val m = picker.minute
            val timeStr = "%02d:%02d".format(h, m)
            prefs.setTime(index, timeStr)
            result.add(timeStr)
        }
        return result
    }

    private fun startScheduler() {
        if (prefs.targetPackage.isEmpty()) {
            Toast.makeText(this, "请先选择要自动打开的应用", Toast.LENGTH_SHORT).show()
            return
        }

        val times = collectTimes()
        AlarmScheduler.scheduleAll(this, times)

        // 启动前台服务
        val svcIntent = Intent(this, SchedulerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }

        prefs.serviceRunning = true
        updateUI()
        Toast.makeText(this, "定时服务已启动！每天 ${times.joinToString(" / ")} 自动打开目标应用", Toast.LENGTH_LONG).show()
    }

    private fun stopScheduler() {
        AlarmScheduler.cancelAll(this)
        stopService(Intent(this, SchedulerService::class.java))
        prefs.serviceRunning = false
        updateUI()
        Toast.makeText(this, "定时服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun testLaunchApp() {
        val pkg = prefs.targetPackage
        if (pkg.isEmpty()) {
            Toast.makeText(this, "请先选择要自动打开的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "正在打开: ${prefs.targetAppName}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "无法打开该应用", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 权限相关 ==========

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY_PERM)
        } else {
            Toast.makeText(this, "当前系统无需申请", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ignoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "已忽略电池优化 ✓", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (am.canScheduleExactAlarms()) {
                Toast.makeText(this, "已有精确闹钟权限 ✓", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
    }

    // ========== 结果处理 ==========

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_APP_PICKER && resultCode == Activity.RESULT_OK && data != null) {
            val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PKG) ?: return
            val name = data.getStringExtra(AppPickerActivity.EXTRA_NAME) ?: ""
            prefs.targetPackage = pkg
            prefs.targetAppName = name
            loadAppSelection()
            Toast.makeText(this, "已选择: $name", Toast.LENGTH_SHORT).show()
        }
    }
}
