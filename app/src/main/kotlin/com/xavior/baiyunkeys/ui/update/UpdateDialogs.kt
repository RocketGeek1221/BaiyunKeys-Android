package com.xavior.baiyunkeys.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.baiyunkeys.core.update.ReleaseInfo
import com.baiyunkeys.core.update.simplifyReleaseNotes
import com.xavior.baiyunkeys.ui.components.FilledButton
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette
import com.xavior.baiyunkeys.update.UpdatePrompt

/**
 * 更新提示弹窗。开屏检测和关于页手动检查共用这一个。
 *
 * 视觉对齐 `docs/mockups/update-ui.png`：24dp 内边距、28dp 圆角、按钮区水平居中，
 * 「前往 GitHub 下载」作为一行小字放在按钮下方，同样居中。
 */
@Composable
fun UpdateDialog(
    prompt: UpdatePrompt,
    currentVersion: String,
    onDismiss: () -> Unit,
    onUpdate: (ReleaseInfo) -> Unit,
    onOpenPage: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogSurface {
            when (prompt) {
                is UpdatePrompt.Available -> AvailableContent(
                    release = prompt.release,
                    currentVersion = currentVersion,
                    onLater = onDismiss,
                    onUpdate = { onUpdate(prompt.release) },
                    onOpenPage = onOpenPage,
                )

                UpdatePrompt.UpToDate -> UpToDateContent(
                    currentVersion = currentVersion,
                    onDismiss = onDismiss,
                )

                is UpdatePrompt.Failed -> FailedContent(
                    message = prompt.message,
                    onDismiss = onDismiss,
                    onOpenPage = onOpenPage,
                )
            }
        }
    }
}

/** 首次启动的「联网说明」，只弹一次。 */
@Composable
fun NetworkNoticeDialog(onDismiss: () -> Unit) {
    val palette = LocalBaiyunPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogSurface {
            DialogTitle("联网说明")
            DialogMeta("首次使用 · 仅提示一次")
            DialogBody(
                "白云钥匙需要联网权限，仅用于检测 GitHub 上的新版本（以及你主动使用的「自动获取配置」）。" +
                    "\n\n不会上传任何门禁数据，也不会在后台联网。关掉「设置 → 联网与隐私 → 检查更新」即可完全停止。",
            )
            CenteredActions {
                FilledButton(title = "知道了", onClick = onDismiss)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "联网权限在「设置 → 应用 → 白云钥匙 → 权限」里可随时查看。",
                color = palette.textTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - 三种内容

@Composable
private fun AvailableContent(
    release: ReleaseInfo,
    currentVersion: String,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
    onOpenPage: () -> Unit,
) {
    DialogTitle("发现新版本 ${release.tag}")
    DialogMeta(metaLine(currentVersion, release))
    DialogBody(simplifyReleaseNotes(release.notes).ifEmpty { "• 本次更新没有提供说明" })
    CenteredActions {
        TextAction("稍后提醒", onClick = onLater)
        FilledButton(title = "立即更新", onClick = onUpdate)
    }
    LinkRow(onClick = onOpenPage)
    HintText("下载交给浏览器：若提示「应用未安装」，请允许浏览器「安装未知应用」并关闭「纯净模式」再装一次。")
}

/** 一行灰色小字，用于放容易踩坑但又不能占主位的提示。 */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        color = LocalBaiyunPalette.current.textTertiary,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    )
}

@Composable
private fun UpToDateContent(currentVersion: String, onDismiss: () -> Unit) {
    DialogTitle("已是最新版本")
    DialogMeta("当前版本 $currentVersion")
    DialogBody("你已经在使用最新正式版本，无需更新。")
    CenteredActions {
        FilledButton(title = "知道了", onClick = onDismiss)
    }
}

@Composable
private fun FailedContent(message: String, onDismiss: () -> Unit, onOpenPage: () -> Unit) {
    DialogTitle("检查更新失败")
    DialogMeta(message)
    DialogBody("可以稍后重试，或直接前往 GitHub 查看最新版本。")
    CenteredActions {
        FilledButton(title = "知道了", onClick = onDismiss)
    }
    LinkRow(onClick = onOpenPage)
}

// MARK: - 基础块

@Composable
private fun DialogSurface(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalBaiyunPalette.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(28.dp),
        color = palette.surface,
        tonalElevation = 0.dp,
        shadowElevation = 24.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp), content = content)
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        color = LocalBaiyunPalette.current.textPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DialogMeta(text: String) {
    Text(
        text = text,
        color = LocalBaiyunPalette.current.textTertiary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun DialogBody(text: String) {
    Text(
        text = text,
        color = LocalBaiyunPalette.current.textSecondary,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .heightIn(max = 168.dp)
            .verticalScroll(rememberScrollState()),
    )
}

/** 按钮区：水平居中，与设计稿一致。 */
@Composable
private fun CenteredActions(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** 「前往 GitHub 下载」这一行小字，居中。 */
@Composable
private fun LinkRow(onClick: () -> Unit) {
    val palette = LocalBaiyunPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "前往 GitHub 下载",
            color = palette.brand,
            fontSize = 12.sp,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun TextAction(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        color = LocalBaiyunPalette.current.textSecondary,
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

private fun metaLine(currentVersion: String, release: ReleaseInfo): String =
    listOfNotNull(
        "当前版本 $currentVersion",
        release.dateLabel.takeIf { it.isNotBlank() }?.let { "发布于 $it" },
        release.sizeLabel.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
