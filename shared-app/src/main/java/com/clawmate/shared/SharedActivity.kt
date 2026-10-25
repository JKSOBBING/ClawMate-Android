package com.clawmate.shared

import android.os.Bundle
import android.graphics.Color
import android.view.View
import ohos.stage.ability.adapter.StageActivity

class SharedActivity : StageActivity() {
    private lateinit var platform: PlatformBridge

    override fun onCreate(state: Bundle?) {
        platform = PlatformBridge(this)
        setInstanceName("com.clawmate.shared:entry:EntryAbility:")
        super.onCreate(state)
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }
}
