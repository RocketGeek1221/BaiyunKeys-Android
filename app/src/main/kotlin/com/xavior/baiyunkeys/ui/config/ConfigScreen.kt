package com.xavior.baiyunkeys.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import android.content.ClipData
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baiyunkeys.core.deeplink.UnlockLink
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.settings.AppSettings
import com.baiyunkeys.core.settings.HiddenEntryGate
import com.baiyunkeys.core.text.DoorTextExporter
import com.xavior.baiyunkeys.ui.components.BaiyunCard
import com.xavior.baiyunkeys.ui.components.CardHeader
import com.xavior.baiyunkeys.ui.components.FilledButton
import com.xavior.baiyunkeys.ui.components.KeyValueRow
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette
import com.xavior.baiyunkeys.update.UpdateStatus
import com.xavior.baiyunkeys.update.chipLabel
import kotlinx.coroutines.launch

/** 配置页要调用的动作集合，省得函数签名排一长串 lambda。 */
class ConfigActions(
    val onSelect: (String) -> Unit,
    val onEdit: (DoorConfig) -> Unit,
    val onDelete: (String) -> Unit,
    val onAdd: () -> Unit,
    val onOpenTransfer: () -> Unit,
    val onOpenQrScanner: () -> Unit,
    val onOpenBleScan: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onFetch: (phone: String, idCard: String) -> Unit,
    val onSettingsChange: (AppSettings) -> Unit,
    val onGenerateSm2: () -> Unit,
    val onRevealAutoFetch: () -> Unit,
    val onHideAutoFetch: () -> Unit,
    /** 关于卡片：检查更新（绕过节流，结果用弹窗反馈）。 */
    val onCheckUpdate: () -> Unit,
)

/**
 * 配置页：门禁列表 / 导入导出 / 扫描 / 偏好 / 关于 + 隐藏的「自动获取配置」。
 *
 * 隐藏入口按方案 §4.6：标题连点 10 次 → 访问码 → 显示；已显示时连点 5 次 → 隐藏；24h 自动翻回隐藏。
 */
@Composable
fun ConfigScreen(
    doors: List<DoorConfig>,
    currentId: String?,
    settings: AppSettings,
    actions: ConfigActions,
    autoFetchBusy: Boolean,
    autoFetchResult: AutoFetchResult?,
    appVersion: String,
    updateStatus: UpdateStatus,
) {
    val palette = LocalBaiyunPalette.current
    val gate = remember { HiddenEntryGate() }
    var askCode by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val autoFetchVisible = settings.isAutoFetchVisible(System.currentTimeMillis())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BaiyunDimens.pagePadding.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 标题可连点（隐藏入口）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    when (gate.onTap(visibleNow = autoFetchVisible)) {
                        HiddenEntryGate.Result.AskForCode -> {
                            codeInput = ""
                            codeError = false
                            askCode = true
                        }

                        HiddenEntryGate.Result.Hide -> actions.onHideAutoFetch()
                        else -> Unit
                    }
                },
        ) {
            Text("配置", color = palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "门禁 ${doors.size} 套 · 偏好与数据搬运",
                color = palette.textSecondary,
                fontSize = 12.sp,
            )
        }

        // MARK: 门禁列表

        BaiyunCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardHeader(title = "门禁列表", subtitle = "点一行切换当前门禁")
                QuietButton(title = "+ 新增", onClick = actions.onAdd)
            }

            if (doors.isEmpty()) {
                Text("还没有门禁", color = palette.textTertiary, fontSize = 12.sp)
            } else {
                doors.forEach { door ->
                    val selected = door.id == currentId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.onSelect(door.id) }
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (if (selected) "● " else "") + door.displayName,
                                color = if (selected) palette.brand else palette.textPrimary,
                                fontSize = 14.sp,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                QuietButton(
                                    title = "复制",
                                    onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(
                                                    ClipData.newPlainText(
                                                        "白云钥匙",
                                                        DoorTextExporter.export(door),
                                                    ),
                                                ),
                                            )
                                        }
                                    },
                                    tint = palette.info,
                                )
                                QuietButton(
                                    title = "链接",
                                    onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(
                                                    ClipData.newPlainText(
                                                        "白云钥匙",
                                                        UnlockLink.build(door.id),
                                                    ),
                                                ),
                                            )
                                        }
                                    },
                                    tint = palette.info,
                                )
                                QuietButton(title = "编辑", onClick = { actions.onEdit(door) })
                                QuietButton(
                                    title = "删除",
                                    onClick = { actions.onDelete(door.id) },
                                    tint = palette.danger,
                                )
                            }
                        }
                        KeyValueRow("MAC", door.maskedMac(hidden = settings.hideSensitive))
                        KeyValueRow("Key", door.maskedKey(hidden = settings.hideSensitive))
                    }
                }
            }
        }

        // MARK: 导入 / 导出

        BaiyunCard {
            CardHeader(title = "导入 / 导出", subtitle = "在设备之间搬运门禁参数")
            Text(
                "导出的内容包含门禁 Key，等同实体钥匙，请勿发到聊天群或公开位置。",
                color = palette.textSecondary,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(title = "文本导入 / 导出", onClick = actions.onOpenTransfer, tint = palette.info)
                QuietButton(title = "扫码导入", onClick = actions.onOpenQrScanner)
            }
        }

        // MARK: 扫描

        BaiyunCard {
            CardHeader(title = "蓝牙", subtitle = "查看附近广播的设备")
            QuietButton(title = "打开扫描面板", onClick = actions.onOpenBleScan)
        }

        // MARK: 自动获取（隐藏）

        if (autoFetchVisible) {
            AutoFetchCard(
                settings = settings,
                busy = autoFetchBusy,
                result = autoFetchResult,
                onSettingsChange = actions.onSettingsChange,
                onFetch = actions.onFetch,
                onGenerateSm2 = actions.onGenerateSm2,
            )
        }

        // MARK: 偏好与关于

        BaiyunCard {
            CardHeader(title = "偏好设置", subtitle = "开锁行为、外观、长辈模式")
            QuietButton(title = "打开偏好设置", onClick = actions.onOpenSettings)
        }

        BaiyunCard {
            CardHeader(title = "关于")
            // 检查更新的唯一入口就是这个胶囊：点它 = 检查 / 重看结果
            VersionRow(
                version = appVersion,
                status = updateStatus,
                onCheck = actions.onCheckUpdate,
            )
            KeyValueRow("协议", "以 iOS 实现为唯一事实源")
            Text(
                "联网仅用于检测 GitHub 新版本（以及你主动使用的「自动获取配置」）；" +
                    "本 App 只是把你本人已获授权的门禁凭据在本地离线复用，不上传任何数据。",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    if (askCode) {
        AlertDialog(
            onDismissRequest = { askCode = false },
            title = { Text("访问码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "输入访问码后可显示「自动获取配置」。",
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it
                            codeError = false
                        },
                        singleLine = true,
                        isError = codeError,
                        shape = RoundedCornerShape(BaiyunDimens.fieldRadius.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        ),
                    )
                    if (codeError) {
                        Text("访问码不对", color = palette.danger, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (gate.onSubmitCode(codeInput)) {
                        HiddenEntryGate.Result.Reveal -> {
                            askCode = false
                            actions.onRevealAutoFetch()
                        }

                        else -> codeError = true
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { askCode = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 「版本 x.y.z  [⬤ 已是最新 ⟳]」这一行。
 *
 * 检查更新只有一个入口：右边这枚胶囊。它是实心填充的按钮样式，点一下
 * - 还没查过 / 查过但被 6h 节流跳过 → 真的去查；
 * - 已经查到新版本 → 直接重看上次的弹窗。
 */
@Composable
private fun VersionRow(
    version: String,
    status: UpdateStatus,
    onCheck: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current
    val checking = status is UpdateStatus.Checking
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("版本", color = palette.textSecondary, fontSize = 12.sp)
            Text(
                text = version,
                color = palette.textPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        UpdateChip(
            status = status,
            enabled = !checking,
            onClick = onCheck,
        )
    }
}

/**
 * 状态胶囊（本质是个按钮）。
 *
 * 视觉上分三档，让人一眼看出可以点：
 * - 有更新：品牌色实底 + 白字，最醒目；
 * - 已是最新 / 未检查：浅品牌底 + 品牌字；
 * - 检查中 / 失败：中性或警示色，并禁用点击。
 */
@Composable
private fun UpdateChip(
    status: UpdateStatus,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current
    val shape = RoundedCornerShape(999.dp)
    val solid = status is UpdateStatus.Available
    val background = when (status) {
        is UpdateStatus.Available -> palette.brand
        is UpdateStatus.Failed -> palette.dangerSoft
        is UpdateStatus.Checking -> palette.surfaceAlt
        else -> palette.brandSoft
    }
    val contentColor = when {
        !enabled -> palette.textTertiary
        status is UpdateStatus.Available -> Color.White
        status is UpdateStatus.Failed -> palette.danger
        else -> palette.brand
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, shape)
            .then(
                if (solid) Modifier else Modifier.border(1.dp, contentColor.copy(alpha = 0.28f), shape),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status is UpdateStatus.Available) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(contentColor, shape),
                )
            }
            Text(
                text = status.chipLabel,
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
