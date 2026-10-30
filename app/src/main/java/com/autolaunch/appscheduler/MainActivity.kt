package com.autolaunch.appscheduler

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
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
        private const val REQ_LOCK = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        // 首次启动如果设置了 PIN 码，要求解锁
        if (prefs.pinHash.isNotEmpty()) {
            val lockIntent = Intent(this, LockActivity::class.java)
            lockIntent.putExtra(LockActivity.EXTRA_CHECK_ONLY, false)
            startActivityForResult(lockIntent, REQ_LOCK)
        }

        setupTimePickers()
        loadAppSelection()

        // TimePicker 改了就自动保存 + 重设闹钟（如果服务运行中）
        setupTimePickerListeners()

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

        // 修改 PIN 码
        binding.btnChangePin.setOnClickListener {
            val lockIntent = Intent(this, LockActivity::class.java)
            lockIntent.putExtra(LockActivity.EXTRA_CHECK_ONLY, prefs.pinHash.isNotEmpty())
            lockIntent.putExtra(LockActivity.EXTRA_CHANGE_MODE, true)
            startActivity(lockIntent)
        }

        // 诊断按钮
        binding.btnDiagnose.setOnClickListener {
            val diag = AlarmScheduler.diagnose(this)
            android.app.AlertDialog.Builder(this)
                .setTitle("诊断结果")
                .setMessage(diag)
                .setPositiveButton("好", null)
                .show()
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

    override fun onResume() {
        super.onResume()
        updateUI()
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

    private fun setupTimePickerListeners() {
        val pickers = listOf(binding.time1, binding.time2, binding.time3, binding.time4)
        pickers.forEachIndexed { index, picker ->
            picker.setOnTimeChangedListener { _, hour, minute ->
                val timeStr = "%02d:%02d".format(hour, minute)
                prefs.setTime(index, timeStr)
                // 如果服务正在运行，重新调度闹钟
                if (prefs.serviceRunning && prefs.targetPackage.isNotEmpty()) {
                    AlarmScheduler.scheduleAll(this, prefs.getTimes())
                }
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

    /**
     * 检查 AlarmManager 里是否真的存在我们的闹钟（比 prefs.serviceRunning 更可靠）
     */
    private fun hasActiveAlarms(): Boolean {
        return AlarmScheduler.hasActiveAlarms(this)
    }

    private fun updateUI() {
        val alarmCount = countActiveAlarms()
        val running = prefs.serviceRunning && alarmCount > 0
        if (running) {
            binding.tvStatus.text = "✓ 定时服务运行中 ($alarmCount/4 闹钟)"
            binding.tvStatus.setTextColor(getColor(R.color.status_green))
        } else {
            if (prefs.serviceRunning) prefs.serviceRunning = false
            binding.tvStatus.text = "○ 定时服务已停止 (活跃闹钟: $alarmCount/4)"
            binding.tvStatus.setTextColor(getColor(R.color.status_red))
        }
        // PIN 码设置按钮的文案
        val hasPin = prefs.pinHash.isNotEmpty()
        binding.btnChangePin.text = getString(if (hasPin) R.string.pin_change else R.string.pin_setup)
    }

    private fun countActiveAlarms(): Int {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        var count = 0
        for (i in 0..3) {
            val intent = AlarmScheduler.buildIntent(this, i)
            val pi = PendingIntent.getBroadcast(
                this,
                AlarmScheduler.REQUEST_CODE_BASE + i,
                intent,
                PendingIntent.FLAG_NO_CREATE or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            if (pi != null) count++
        }
        return count
    }

    private fun startScheduler() {
        if (prefs.targetPackage.isEmpty()) {
            Toast.makeText(this, "请先选择要自动打开的应用", Toast.LENGTH_SHORT).show()
            return
        }

        val times = collectTimes()
        val diag = AlarmScheduler.scheduleAll(this, times)

        // 启动前台服务
        val svcIntent = Intent(this, SchedulerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }

        prefs.serviceRunning = true
        updateUI()

        // 显示详细设置结果
        android.app.AlertDialog.Builder(this)
            .setTitle("定时服务已启动")
            .setMessage(diag + "\n当前状态: ${if (countActiveAlarms() > 0) "✓ 有活跃闹钟" else "✗ 没查到活跃闹钟！"}")
            .setPositiveButton("好", null)
            .show()
    }

    private fun stopScheduler() {
        AlarmScheduler.cancelAll(this)
        try { stopService(Intent(this, SchedulerService::class.java)) } catch (_: Exception) {}
        prefs.serviceRunning = false
        updateUI()
        Toast.makeText(this, "定时服务已停止", Toast.LENGTH_SHORT).show()
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
        when (requestCode) {
            REQ_APP_PICKER -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PKG) ?: return
                    val name = data.getStringExtra(AppPickerActivity.EXTRA_NAME) ?: ""
                    prefs.targetPackage = pkg
                    prefs.targetAppName = name
                    loadAppSelection()
                    Toast.makeText(this, "已选择: $name", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_LOCK -> {
                if (resultCode != RESULT_OK) {
                    // 解锁失败，退出
                    finish()
                }
            }
        }
    }
}
