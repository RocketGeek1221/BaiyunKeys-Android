package com.xavior.baiyunkeys.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baiyunkeys.core.settings.AppSettings
import com.baiyunkeys.core.settings.ThemeMode
import com.xavior.baiyunkeys.ui.components.BaiyunCard
import com.xavior.baiyunkeys.ui.components.CardHeader
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.components.StepperRow
import com.xavior.baiyunkeys.ui.components.ToggleRow
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette

/**
 * 偏好设置，对应 iOS `ConfigView.preferenceCard`。
 *
 * 分组：开锁行为 / 显示与反馈 / 安全 / 长辈模式 / 外观。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BaiyunDimens.pagePadding.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        QuietButton(title = "← 返回", onClick = onBack)
        Text("偏好设置", color = palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        // 开锁行为
        BaiyunCard {
            CardHeader(title = "开锁行为", subtitle = "影响扫描与失败重试")
            ToggleRow(
                title = "打开即开锁",
                subtitle = "打开 App 后自动试一次",
                checked = settings.quickUnlock,
                onCheckedChange = { onChange(settings.copy(quickUnlock = it)) },
            )
            ToggleRow(
                title = "切回也开锁",
                subtitle = "从后台切回前台时静默尝试（只用缓存设备）",
                checked = settings.unlockOnResume,
                onCheckedChange = { onChange(settings.copy(unlockOnResume = it)) },
                enabled = settings.quickUnlock,
            )
            ToggleRow(
                title = "失败自动重试",
                subtitle = "扫描 / 连接 / 超时类失败会自动再来一遍",
                checked = settings.autoRetry,
                onCheckedChange = { onChange(settings.copy(autoRetry = it)) },
            )
            StepperRow(
                title = "重试次数",
                subtitle = "仅在开启自动重试时生效",
                value = settings.autoRetryCount,
                range = AppSettings.RETRY_RANGE,
                suffix = " 次",
                enabled = settings.autoRetry,
                onValueChange = { onChange(settings.copy(autoRetryCount = it)) },
            )
            StepperRow(
                title = "扫描超时",
                subtitle = "搜不到门锁时等待多久",
                value = settings.scanTimeoutSeconds,
                range = AppSettings.SCAN_TIMEOUT_RANGE,
                suffix = " 秒",
                onValueChange = { onChange(settings.copy(scanTimeoutSeconds = it)) },
            )
        }

        // 显示与反馈
        BaiyunCard {
            CardHeader(title = "显示与反馈")
            ToggleRow(
                title = "掩码显示",
                subtitle = "列表与详情里隐藏 MAC 与 Key",
                checked = settings.hideSensitive,
                onCheckedChange = { onChange(settings.copy(hideSensitive = it)) },
            )
            ToggleRow(
                title = "触感反馈",
                subtitle = "开锁成功 / 失败时的震动",
                checked = settings.haptics,
                onCheckedChange = { onChange(settings.copy(haptics = it)) },
            )
            ToggleRow(
                title = "保存开锁记录",
                subtitle = "关掉后不再写入新的记录",
                checked = settings.saveHistory,
                onCheckedChange = { onChange(settings.copy(saveHistory = it)) },
            )
        }

        // 安全
        BaiyunCard {
            CardHeader(title = "安全", subtitle = "冷启动验证")
            ToggleRow(
                title = "生物识别锁屏",
                subtitle = "打开 App 时验证指纹 / 面容或设备密码",
                checked = settings.requireBiometric,
                onCheckedChange = { onChange(settings.copy(requireBiometric = it)) },
            )
        }

        // 联网与隐私
        BaiyunCard {
            CardHeader(title = "联网与隐私", subtitle = "本 App 只有两处会联网")
            ToggleRow(
                title = "检查更新",
                subtitle = "冷启动时向 GitHub 查一次新版本（6 小时内不重复）",
                checked = settings.updateCheckEnabled,
                onCheckedChange = { onChange(settings.copy(updateCheckEnabled = it)) },
            )
            Text(
                "关掉后不再自动联网检查；关于页手动点「检查更新」仍会发一次请求。" +
                    "请求只带 IP 与 User-Agent，不含门禁数据。另一处联网是「自动获取配置」，" +
                    "由你在配置页主动触发。",
                color = palette.textTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        // 长辈模式
        BaiyunCard {
            CardHeader(title = "长辈模式", subtitle = "更大的按钮、更少的入口")
            ToggleRow(
                title = "开启长辈模式",
                subtitle = "隐藏「配置」Tab",
                checked = settings.elderMode,
                onCheckedChange = { onChange(settings.copy(elderMode = it)) },
            )
            ToggleRow(
                title = "禁用门禁选择器",
                subtitle = "首页只显示当前门禁，不能切换",
                checked = settings.elderLocksDoorSelector,
                onCheckedChange = { onChange(settings.copy(elderLocksDoorSelector = it)) },
                enabled = settings.elderMode,
            )
            ToggleRow(
                title = "隐藏日志",
                checked = settings.elderHidesLogs,
                onCheckedChange = { onChange(settings.copy(elderHidesLogs = it)) },
                enabled = settings.elderMode,
            )
            ToggleRow(
                title = "隐藏开锁记录",
                checked = settings.elderHidesHistory,
                onCheckedChange = { onChange(settings.copy(elderHidesHistory = it)) },
                enabled = settings.elderMode,
            )
        }

        // 外观
        BaiyunCard {
            CardHeader(title = "外观")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = { onChange(settings.copy(theme = mode)) },
                        label = { Text(mode.label, fontSize = 12.sp) },
                    )
                }
            }
        }

        Text(
            "好友提示：长辈模式也可以从首页顶栏长按 3 秒切换（iOS 的交互），这里直接给开关。",
            color = palette.textTertiary,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
    }
}
