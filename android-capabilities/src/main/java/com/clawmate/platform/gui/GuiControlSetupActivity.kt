package com.clawmate.platform.gui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.clawmate.platform.files.DeviceFilesGrant

/** System accessibility "Settings" entry; never accepts a target/action intent extra. */
class GuiControlSetupActivity : Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var status:TextView
    private val update=object:Runnable {
        override fun run(){status.text=(if(GuiControlService.connected!=null)"无障碍服务已连接" else "无障碍服务未连接")+"\n"+GuiControlService.lastStatus;handler.postDelayed(this,500)}
    }
    override fun onCreate(state:Bundle?) {
        setTheme(com.clawmate.platform.R.style.ClawMateSetup)
        super.onCreate(state)
        actionBar?.hide()
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,48,24,24)}
        layout.addView(TextView(this).apply{text="ClawMate 界面控制";textSize=22f})
        layout.addView(TextView(this).apply{text="选择要操作的应用。手动控制可按次确认，也可记住授权，直接执行浮窗手势。支持 Android 14 及以上。";textSize=16f})
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    runCatching(action).onFailure {
                        Toast.makeText(this@GuiControlSetupActivity, it.message ?: "操作失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        button("无障碍权限说明"){GuiControlEntry.settings(this)}
        button("Mika 应用访问"){GuiControlEntry.mikaAppAccess(this)}
        button("选择应用并开启手动控制"){GuiControlEntry.open(this)}
        button("手动控制授权"){GuiControlEntry.manualGrants(this)}
        button("选择应用并开始采集"){GuiControlEntry.open(this, collectOnly = true)}
        button("查看本机采集记录"){GuiControlEntry.showCollections(this)}
        button("授权 Mika 读取文件夹"){pickDirectory()}
        button("撤销文件授权"){revokeDirectory()}
        button("停止控制会话"){GuiControlService.connected?.stop()}
        status=TextView(this).apply{textSize=15f;contentDescription="ClawMate control status"};layout.addView(status)
        button("返回"){finish()};setContentView(android.widget.ScrollView(this).apply { addView(layout) })
    }
    /** 选一个文件夹给 Mika 只读使用；授权持久化在本机，撤销前一直有效。 */
    private fun pickDirectory() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
            REQUEST_TREE,
        )
    }

    private fun revokeDirectory() {
        DeviceFilesGrant.read(this)?.let { uri ->
            runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        DeviceFilesGrant.write(this, null)
        Toast.makeText(this, "已撤销文件授权，Mika 不再读取该目录", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        DeviceFilesGrant.write(this, uri)
        Toast.makeText(this, "已授权目录给 Mika（只读）", Toast.LENGTH_SHORT).show()
    }

    override fun onResume(){super.onResume();handler.post(update)}
    override fun onPause(){handler.removeCallbacks(update);super.onPause()}
    override fun onDestroy(){handler.removeCallbacks(update);super.onDestroy()}

    private companion object {
        const val REQUEST_TREE = 7311
    }
}
