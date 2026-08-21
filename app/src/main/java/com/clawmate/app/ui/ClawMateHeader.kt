package com.clawmate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared page header mirroring HarmonyOS AppHeader. */
@Composable
fun ClawMateHeader(
    title: String,
    iconRes: Int,
    subtitle: String? = null,
    pcServerVisible: Boolean = false,
    pcServerConnected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 18.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.padding(start = 9.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    fontSize = 25.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF172033),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFF717684),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(0xFFF0ECFF), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("盾", fontSize = 12.sp, color = Color(0xFF8066FF))
                }
                Text("隐私守护中", fontSize = 13.sp, color = Color(0xFF6B6F7C), modifier = Modifier.padding(horizontal = 5.dp))
                Text(">", fontSize = 16.sp, color = Color(0xFF8B909A))
            }
            if (pcServerVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (pcServerConnected) Color(0xFF22B07D) else Color(0xFFA7AFBD),
                                RoundedCornerShape(4.dp),
                            ),
                    )
                    Text(
                        if (pcServerConnected) "PC 已连接" else "PC 未连接",
                        fontSize = 11.sp,
                        color = if (pcServerConnected) Color(0xFF167456) else Color(0xFF747B87),
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
    }
}
