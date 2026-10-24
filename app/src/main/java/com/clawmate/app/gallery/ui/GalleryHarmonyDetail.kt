package com.clawmate.app.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clawmate.app.gallery.model.GalleryPhotoItem
import com.clawmate.app.ui.*
import com.clawmate.app.ui.theme.HarmonyTheme
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HarmonyGalleryDetail(item: GalleryPhotoItem, onClose: () -> Unit, onManage: () -> Unit) {
    HarmonyDetailModal(GalleryDetailLabels.displayTitle(item), harmonyGalleryCategory(item.category), onClose,
        aiGenerated = item.hasCompletedAnalysis(), subtitleColor = HarmonyTheme.text(galleryCategoryTone(item.category).foreground)) {
        var previewFailed by remember(item.uri) { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(220.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            AsyncImage(item.uri, "原始图片预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onError = { previewFailed = true }, onSuccess = { previewFailed = false })
            if (previewFailed) Text("图片预览不可用", fontSize = 12.sp, color = HarmonyTheme.mutedText)
        }
        Text(item.summary.ifBlank { "暂无分析摘要" }, fontSize = 13.sp, lineHeight = 20.sp, color = HarmonyTheme.text("#4D5566"), modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        HarmonyDetailSection("基础信息")
        HarmonyDetailInfo("时间", if (item.createdAt > 0) "入库 ${DateFormat.getDateTimeInstance().format(Date(item.createdAt))}" else "未记录")
        HarmonyDetailInfo("日期", item.date.ifBlank { "未知" })
        HarmonyDetailInfo("状态", GalleryDetailLabels.analysisStateLabel(item.analysisState))
        HarmonyDetailInfo("ID", item.id)
        item.exif?.dateTimeOriginal?.takeIf { it.isNotBlank() }?.let { HarmonyDetailInfo("拍摄时间", it) }
        item.exif?.deviceModel?.takeIf { it.isNotBlank() }?.let { HarmonyDetailInfo("拍摄设备", it) }
        if (item.latitude != null && item.longitude != null) HarmonyDetailInfo("拍摄位置", "${item.latitude}, ${item.longitude}")
        if (item.address.isNotBlank()) HarmonyDetailInfo("地理位置", item.address)
        item.exif?.let { exif -> if ((exif.imageWidth ?: 0) > 0 && (exif.imageHeight ?: 0) > 0) HarmonyDetailInfo("图片尺寸", "${exif.imageWidth} x ${exif.imageHeight}") }
        if (item.details.isNotEmpty()) {
            HarmonyDetailSection("分析明细")
            item.details.take(8).forEach { Text("· $it", fontSize = 12.sp, lineHeight = 18.sp, color = HarmonyTheme.text("#4D5566"), modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) }
        }
        if (item.tags.isNotEmpty()) {
            HarmonyDetailSection("标签")
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                item.tags.forEach { tag -> Text(tag, fontSize = 11.sp, color = HarmonyTheme.text("#4B5F9F"),
                    modifier = Modifier.padding(end = 6.dp, bottom = 6.dp).background(HarmonyTheme.background("#EEF3FF"), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp)) }
            }
        }
        if (item.ocrText.isNotBlank()) {
            HarmonyDetailSection("OCR 文本")
            SelectionContainer { Text(item.ocrText, fontSize = 12.sp, lineHeight = 18.sp, color = HarmonyTheme.text("#4D5566"),
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(HarmonyTheme.background("#F7F9FC"), RoundedCornerShape(10.dp)).padding(10.dp)) }
        }
        HarmonyButton("编辑与分析", onManage, Modifier.padding(top = 16.dp), height = 34.dp)
        Spacer(Modifier.height(8.dp))
    }
}
