package com.clawmate.platform.display

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WirelessPairingActivity : Activity() {
    companion object {
        const val EXTRA_AUTO_SETUP = "wireless_auto_setup"
        const val EXTRA_TICKET = "wireless_chat_ticket"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var feedback: TextView
    private lateinit var pairPort: EditText
    private lateinit var code: EditText
    private lateinit var connectPort: EditText
    private lateinit var pairButton: Button
    private lateinit var connectButton: Button
    private lateinit var forgetButton: Button
    private lateinit var autoButton: Button
    private var autoDialog: AlertDialog? = null
    private var busy = false
    private var visibleState: WirelessDisplayPairing.State? = null
    private var statusWatch: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.clawmate.platform.R.style.ClawMateSetup)
        super.onCreate(savedInstanceState)
        title = "无线配对"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            isFocusableInTouchMode = true
        }
        feedback = TextView(this).apply {
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(feedback)
        })
        stateText = text("", heading = true)
        text("无线调试用于启动这台手机上的虚拟屏助手。首次使用需要你在系统设置中开启无线调试，并提供一次六位配对码。")
        autoButton = button("自动配对") { confirmAutoSetup() }
        button("打开开发者选项") { openDeveloperSettings() }
        val manual = button("手动设置端口") {}
        val firstManual = content.childCount
        text("配对设备", heading = true)
        text("在开发者选项开启「无线调试」，先记下首页「IP 地址和端口」末尾的连接端口，再点「使用配对码配对设备」。保持弹窗打开，在这里输入弹窗中的配对端口和六位码。")
        pairPort = numberField("配对端口")
        code = numberField("六位配对码", secret = true)
        pairButton = button("配对") {
            val port = portNumber(pairPort) ?: return@button
            val entered = code.text.toString()
            if (!entered.matches(Regex("[0-9]{6}"))) {
                code.error = "请输入六位数字"
                code.requestFocus()
                return@button
            }
            code.text.clear()
            perform("正在配对…", "配对成功，可使用连接端口启动助手。") {
                WirelessDisplayPairing.pair(applicationContext, port, entered)
            }
        }
        text("连接后台屏助手", heading = true)
        text("输入无线调试首页的连接端口。手机重启或重新开启无线调试后，请核对这个端口。")
        connectPort = numberField("连接端口")
        connectButton = button("连接助手") {
            val port = portNumber(connectPort) ?: return@button
            perform("正在连接后台屏助手…", "后台屏助手已连接。") {
                WirelessDisplayPairing.connect(applicationContext, port)
                returnToChatIfReady()
            }
        }
        val manualViews = (firstManual until content.childCount).map { content.getChildAt(it) }
        fun showManual(open: Boolean) {
            manualViews.forEach { it.visibility = if (open) View.VISIBLE else View.GONE }
            manual.text = if (open) "收起手动设置" else "手动设置端口"
        }
        showManual(false)
        manual.setOnClickListener { showManual(manualViews.first().visibility != View.VISIBLE) }
        forgetButton = button("忘记配对") {
            AlertDialog.Builder(this, com.clawmate.platform.R.style.ClawMateDialog).setTitle("忘记本机无线配对？")
                .setMessage("这会移除应用保存的配对身份。还可在系统「无线调试」的已配对设备中撤销授权。")
                .setNegativeButton("取消", null).setPositiveButton("忘记") { _, _ ->
                    perform("正在移除配对…", "已忘记本机配对。") {
                        WirelessDisplayPairing.forget(applicationContext)
                        pairPort.text.clear()
                        connectPort.text.clear()
                        code.text.clear()
                    }
                }.show()
        }
        if (savedInstanceState?.getBoolean("autoDialogOpen") ?: intent.getBooleanExtra(EXTRA_AUTO_SETUP, false)) confirmAutoSetup()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("autoDialogOpen", autoDialog?.isShowing == true)
    }

    private fun confirmAutoSetup() {
        if (busy || autoDialog?.isShowing == true) return
        val state = WirelessDisplayPairing.status(applicationContext)
        if (!state.supported || state.helperAvailable) {
            refresh(state)
            if (state.helperAvailable) returnToChatIfReady()
            return
        }
        val field = if (state.paired) null else numberField("六位配对码", secret = true, attach = false)
        val explanation = (if (state.paired)
            "已保存本机配对。请确认系统无线调试已开启，ClawMate 将自动查找端口并启动虚拟屏助手。"
        else "ClawMate 将使用本机无线调试，在虚拟屏操作你选择的应用。\n\n点「系统设置」，开启无线调试并选择「使用配对码配对设备」。保持系统弹窗打开，在分屏的此处输入六位码。") +
            if (callingActivity != null) "\n\n连接成功后会返回聊天，继续当前任务。" else ""
        val dialog = AlertDialog.Builder(this, com.clawmate.platform.R.style.ClawMateDialog)
            .setTitle(if (state.paired) "允许连接本机虚拟屏助手？" else "允许与本机无线配对？")
            .setMessage(explanation).setNegativeButton("取消") { _, _ -> if (callingActivity != null) finish() }
            .setNeutralButton("系统设置", null).setPositiveButton("授权并连接", null)
            .apply {
                if (field != null) setView(LinearLayout(this@WirelessPairingActivity).apply {
                    val inset = (20 * resources.displayMetrics.density).toInt()
                    setPadding(inset, inset / 2, inset, 0)
                    addView(field, LinearLayout.LayoutParams(-1, -2))
                })
            }.create()
        autoDialog = dialog
        dialog.setOnDismissListener { field?.text?.clear(); autoDialog = null }
        dialog.setOnCancelListener { if (callingActivity != null) finish() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { openDeveloperSettings() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = field?.text?.toString()
            if (field != null && (entered == null || !entered.matches(Regex("[0-9]{6}")))) {
                field.error = "请输入系统配对弹窗中的六位数字"
                field.requestFocus()
                return@setOnClickListener
            }
            dialog.dismiss()
            perform("正在查找本机无线调试服务…", "虚拟屏助手已连接。") {
                WirelessDisplayPairing.autoSetup(applicationContext, entered, ::showFeedback)
                returnToChatIfReady()
            }
        }
    }

    private fun returnToChatIfReady() {
        if (intent.getBooleanExtra(EXTRA_AUTO_SETUP, false) && callingActivity != null && BackgroundDisplay.available) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TICKET, intent.getStringExtra(EXTRA_TICKET)))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        statusWatch = scope.launch {
            while (isActive) {
                delay(2000)
                val state = withContext(Dispatchers.IO) { WirelessDisplayPairing.status(applicationContext) }
                if (state != visibleState) refresh(state)
            }
        }
    }

    override fun onPause() {
        statusWatch?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        autoDialog?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    private fun refresh(state: WirelessDisplayPairing.State = WirelessDisplayPairing.status(applicationContext)) {
        if (visibleState == null) state.lastConnectPort?.let { connectPort.setText(it.toString()) }
        if (visibleState?.connected == true && !state.connected && !busy && feedback.text == "后台屏助手已连接。") {
            showFeedback("无线助手已断开，请核对连接端口后重新连接。")
        }
        visibleState = state
        autoButton.text = if (state.paired) "自动连接助手" else "自动配对"
        autoButton.isEnabled = !busy && state.supported && !state.helperAvailable
        stateText.text = when {
            !state.supported -> "后台屏助手需要 Android 14 或更高版本"
            state.connected -> "无线后台屏助手已连接"
            state.paired -> "已保存配对资料 · 无线助手未连接"
            else -> "本机尚未配对"
        }
        if (state.helperAvailable && !state.connected) stateText.append(" · 后台屏助手已通过其他方式连接。切换无线前请先断开原助手。")
        pairButton.text = if (state.paired) "重新配对" else "配对"
        pairButton.isEnabled = !busy && state.supported && !state.connected
        connectButton.isEnabled = !busy && state.supported && state.paired && !state.helperAvailable
        forgetButton.isEnabled = !busy && state.paired
    }

    private fun perform(progress: String, success: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        refresh()
        showFeedback(progress)
        scope.launch {
            try {
                action()
                showFeedback(success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFeedback(e.localizedMessage ?: "操作失败，请检查系统无线调试状态后重试。")
            } finally {
                busy = false
                if (!isDestroyed && !isFinishing) refresh()
            }
        }
    }

    private fun showFeedback(message: String) {
        feedback.text = message
        feedback.visibility = View.VISIBLE
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
        } catch (e: ActivityNotFoundException) {
            showFeedback("请从系统设置打开开发者选项，在最近任务中选择分屏。")
        } catch (e: SecurityException) {
            showFeedback("系统限制了开发者选项，请检查设备或工作资料的管理设置。")
        }
    }

    private fun portNumber(field: EditText): Int? {
        val value = field.text.toString().toIntOrNull()
        if (value != null && value in 1..65535) return value
        field.error = "端口范围为 1 至 65535"
        field.requestFocus()
        return null
    }

    private fun numberField(label: String, secret: Boolean = false, attach: Boolean = true) = EditText(this).apply {
        hint = label
        inputType = InputType.TYPE_CLASS_NUMBER or (if (secret) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0)
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
        filters = arrayOf(InputFilter.LengthFilter(if (secret) 6 else 5))
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (attach) content.addView(this)
    }

    private fun text(value: String, heading: Boolean = false) = TextView(this).apply {
        text = value
        textSize = if (heading) 19f else 15f
        if (heading) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        content.addView(this)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
        content.addView(this)
    }
}
