package com.autolaunch.appscheduler

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autolaunch.appscheduler.databinding.ActivityLockBinding

/**
 * PIN 码界面 - 三种模式：
 * 1. 验证（启动时解锁）
 * 2. 设置新 PIN
 * 3. 修改 PIN（先验证旧 PIN，再输入新 PIN）
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var prefs: Prefs

    /** true = 仅验证模式（启动时需要解锁才能进主界面）*/
    private var checkOnly = false
    /** true = 修改 PIN 模式 */
    private var changeMode = false
    /** 修改 PIN 模式下：已验证过旧 PIN */
    private var oldPinVerified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        checkOnly = intent.getBooleanExtra(EXTRA_CHECK_ONLY, false)
        changeMode = intent.getBooleanExtra(EXTRA_CHANGE_MODE, false)

        setupUI()

        binding.btnOk.setOnClickListener { onOk() }
        binding.btnCancel.setOnClickListener {
            if (checkOnly) {
                // 启动时验证，取消则退出应用
                setResult(RESULT_CANCELED)
                finishAffinity()
            } else {
                finish()
            }
        }
        binding.btnClearPin.setOnClickListener { onClearPin() }
    }

    private fun setupUI() {
        val hasPin = prefs.pinHash.isNotEmpty()

        when {
            checkOnly -> {
                // 启动时验证
                binding.tvTitle.text = getString(R.string.pin_title)
                binding.tvHint.text = getString(R.string.pin_enter)
                binding.etPin.hint = getString(R.string.pin_enter)
                binding.tilPin2.visibility = View.GONE
                binding.btnCancel.visibility = View.GONE
                binding.btnClearPin.visibility = View.GONE
            }
            changeMode -> {
                // 修改 PIN
                if (hasPin) {
                    // 先验证旧 PIN
                    binding.tvTitle.text = getString(R.string.pin_title)
                    binding.tvHint.text = getString(R.string.pin_current)
                    binding.etPin.hint = getString(R.string.pin_current)
                    binding.tilPin2.visibility = View.GONE
                    binding.btnClearPin.visibility = View.VISIBLE
                } else {
                    // 还没设置过，直接设置新 PIN
                    setupNewPinUI()
                }
            }
            else -> {
                // 纯验证模式（其他地方可能用到）
                binding.tvTitle.text = getString(R.string.pin_title)
                binding.tvHint.text = getString(R.string.pin_enter)
                binding.etPin.hint = getString(R.string.pin_enter)
                binding.tilPin2.visibility = View.GONE
                binding.btnClearPin.visibility = if (hasPin) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupNewPinUI() {
        binding.tvTitle.text = getString(R.string.pin_title)
        binding.tvHint.text = getString(R.string.pin_new)
        binding.etPin.hint = getString(R.string.pin_new)
        binding.tilPin2.visibility = View.VISIBLE
        binding.btnClearPin.visibility = View.GONE
    }

    private fun onOk() {
        val pin1 = binding.etPin.text?.toString() ?: ""
        val pin2 = binding.etPin2.text?.toString() ?: ""

        if (checkOnly) {
            // 仅验证
            if (prefs.verifyPin(pin1)) {
                setResult(RESULT_OK)
                finish()
            } else {
                binding.tilPin.error = getString(R.string.pin_error)
                binding.etPin.text?.clear()
            }
            return
        }

        if (changeMode && !oldPinVerified) {
            // 先验证旧 PIN
            if (prefs.verifyPin(pin1)) {
                oldPinVerified = true
                setupNewPinUI()
                binding.etPin.text?.clear()
                binding.etPin2.text?.clear()
            } else {
                binding.tilPin.error = getString(R.string.pin_error)
                binding.etPin.text?.clear()
            }
            return
        }

        // 设置新 PIN
        if (pin1.isEmpty()) {
            binding.tilPin.error = getString(R.string.pin_empty)
            return
        }
        if (binding.tilPin2.visibility == View.VISIBLE) {
            if (pin1 != pin2) {
                binding.tilPin2.error = getString(R.string.pin_mismatch)
                return
            }
        }
        prefs.setPin(pin1)
        Toast.makeText(this, getString(R.string.pin_success), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun onClearPin() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pin_clear))
            .setMessage("确定要清除 PIN 码吗？清除后下次打开应用不再需要验证。")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.clearPin()
                Toast.makeText(this, "PIN 码已清除", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_CHECK_ONLY = "check_only"
        const val EXTRA_CHANGE_MODE = "change_mode"
    }
}
