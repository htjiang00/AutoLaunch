package com.autolaunch.appscheduler

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autolaunch.appscheduler.databinding.ActivityAppPickerBinding

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding

    data class AppInfo(
        val name: String,
        val packageName: String,
        val iconResource: android.graphics.drawable.Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val apps = loadLaunchableApps()
        val adapter = AppAdapter(apps) { appInfo ->
            val result = Intent().apply {
                putExtra(EXTRA_PKG, appInfo.packageName)
                putExtra(EXTRA_NAME, appInfo.name)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun loadLaunchableApps(): List<AppInfo> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val list = pm.queryIntentActivities(launcherIntent, 0)
        val apps = mutableListOf<AppInfo>()
        val selfPkg = packageName

        for (ri in list) {
            val pkg = ri.activityInfo.packageName
            if (pkg == selfPkg) continue // 跳过自己
            val name = try {
                val label = ri.loadLabel(pm).toString()
                if (label.isNotEmpty()) label else pkg
            } catch (_: Exception) { pkg }
            val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
            apps.add(AppInfo(name, pkg, icon))
        }
        // 按名称排序
        apps.sortBy { it.name.lowercase() }
        return apps
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_NAME = "name"
    }
}
