package com.xavior.baiyunkeys.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import com.baiyunkeys.core.net.OkHttpTransport
import com.baiyunkeys.core.settings.AppSettings
import com.baiyunkeys.core.update.GitHubReleaseApi
import com.baiyunkeys.core.update.ReleaseInfo
import com.baiyunkeys.core.update.UpdateException
import com.baiyunkeys.core.update.VersionCompare
import com.xavior.baiyunkeys.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 关于页那枚状态胶囊要显示的内容。 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/** 弹窗内容。开屏检测和关于页手动检查共用同一套。 */
sealed interface UpdatePrompt {
    data class Available(val release: ReleaseInfo) : UpdatePrompt
    data object UpToDate : UpdatePrompt
    data class Failed(val message: String) : UpdatePrompt
}

/**
 * 版本检测编排。
 *
 * 三件事：
 * 1. **开屏检测** —— 冷启动（解锁后）检查一次，6h 节流，失败静默、成功且是新版本才弹；
 * 2. **手动检查** —— 关于页点「检查更新」，绕过节流，结果一律用弹窗反馈；
 * 3. **打开浏览器** —— 「立即更新」开 APK 直链，「前往 GitHub 下载」开 Release 页。
 *
 * 下载交给系统浏览器，App 自己不写下载器，因此不需要存储权限，也不用申请安装权限。
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val currentVersion: String,
    private val currentVersionCode: Int = 0,
    private val api: GitHubReleaseApi = GitHubReleaseApi(OkHttpTransport()),
) {

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _prompt = MutableStateFlow<UpdatePrompt?>(null)
    val prompt: StateFlow<UpdatePrompt?> = _prompt.asStateFlow()

    private val _networkNotice = MutableStateFlow(false)

    /** 首次启动的「联网说明」是否要显示。只提示一次。 */
    val networkNotice: StateFlow<Boolean> = _networkNotice.asStateFlow()

    private var checkJob: Job? = null

    // MARK: - 开屏

    /** 冷启动时调用一次，内部按节流自行决定要不要真的发请求。 */
    fun checkOnLaunch() {
        val now = System.currentTimeMillis()
        val current = settings.settings.value
        if (!current.shouldCheckUpdate(now)) {
            // 节流期间不联网，但要把上次的结论亮出来，别让胶囊退回「检查更新」
            _status.value = seedStatus(current.updateKnownTag)
            log(
                "开屏检测跳过：距上次检查 ${(now - current.updateLastCheckAt) / 60_000} 分钟，" +
                    "沿用上次结论 ${current.updateKnownTag.ifEmpty { "无" }}",
            )
            return
        }
        log("开屏检测开始（上次检查 ${(now - current.updateLastCheckAt) / 60_000} 分钟前）")
        startCheck(interactive = false)
    }

    /**
     * 用磁盘上的结论点亮胶囊。
     *
     * 存的 tag 可能比当前版本还旧（用户已经装上去了），这种情况按「已是最新」处理。
     */
    private fun seedStatus(knownTag: String): UpdateStatus = when {
        knownTag.isEmpty() -> UpdateStatus.Idle
        knownTag == AppSettings.UPDATE_KNOWN_LATEST -> UpdateStatus.UpToDate
        !VersionCompare.isRemoteNewer(knownTag, currentVersion) -> UpdateStatus.UpToDate
        else -> UpdateStatus.Available(
            // 只存了 tag，够点亮胶囊；点开后手动检查会补全 Release 详情
            ReleaseInfo(
                tag = knownTag,
                version = VersionCompare.normalize(knownTag),
                title = knownTag,
                notes = "",
                pageUrl = RELEASES_PAGE,
                publishedAt = "",
                prerelease = false,
                asset = null,
            ),
        )
    }

    /** 首次启动弹「联网说明」，之后不再弹。 */
    fun maybeShowNetworkNotice() {
        if (!settings.settings.value.networkNoticeShown) {
            _networkNotice.value = true
        }
    }

    fun dismissNetworkNotice() {
        _networkNotice.value = false
        scope.launch { settings.update { it.copy(networkNoticeShown = true) } }
    }

    // MARK: - 手动

    /** 关于页的「检查更新」。绕过节流，结果一定给出反馈。 */
    fun checkManually() {
        if (_status.value is UpdateStatus.Checking) return
        // 已经查到新版本时，点胶囊直接复现弹窗，不必再打一次网络
        val current = _status.value
        if (current is UpdateStatus.Available) {
            _prompt.value = UpdatePrompt.Available(current.release)
            return
        }
        startCheck(interactive = true)
    }

    fun dismissPrompt() {
        val current = _prompt.value ?: return
        _prompt.value = null
        if (current is UpdatePrompt.Available) {
            val tag = current.release.tag
            scope.launch {
                settings.update {
                    it.copy(
                        updatePromptedVersion = tag,
                        updatePromptedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    // MARK: - 动作

    /**
     * 「立即更新」：打开**最新正式 Release 的构成产物的直链**（CI 里固定文件名 `BaiyunKeys-Android.apk`）。
     * 万一这条 Release 没挂 APK 资产，退回 Release 列表页，不会点了没反应。
     */
    fun openDownload(release: ReleaseInfo): Boolean {
        val url = release.asset?.downloadUrl?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE
        return openUrl(url)
    }

    /** 「前往 GitHub 下载」：打开**项目的 Release 页面**（列表页，不是某一个 tag 的详情页）。 */
    fun openReleasePage(): Boolean = openUrl(RELEASES_PAGE)

    // MARK: - 内部

    private fun startCheck(interactive: Boolean) {
        checkJob?.cancel()
        checkJob = scope.launch {
            _status.value = UpdateStatus.Checking
            if (!hasNetwork()) {
                val message = "当前没有网络连接"
                log("检测失败：无网络")
                _status.value = UpdateStatus.Failed(message)
                if (interactive) _prompt.value = UpdatePrompt.Failed(message)
                return@launch
            }

            val result = runCatching { api.latestRelease() }
            result.onSuccess { release ->
                // release == null 表示 GitHub 上还没有任何正式 Release（404），按「已是最新」处理
                val newer = release != null && VersionCompare.isRemoteNewer(
                    remoteVersion = release.version,
                    remoteVersionCode = release.versionCode,
                    localVersion = currentVersion,
                    localVersionCode = currentVersionCode,
                )

                log(
                    if (release == null) {
                        "检测完成：GitHub 上没有正式 Release，按已是最新处理"
                    } else {
                        "检测完成：远端 ${release.tag}（code=${release.versionCode ?: "?"}）/ " +
                            "本地 $currentVersion（code=$currentVersionCode）/ 有新版本=$newer"
                    },
                )

                _status.value = if (release == null || !newer) {
                    UpdateStatus.UpToDate
                } else {
                    UpdateStatus.Available(release)
                }

                val now = System.currentTimeMillis()
                val knownTag = if (release == null || !newer) {
                    AppSettings.UPDATE_KNOWN_LATEST
                } else {
                    release.tag
                }
                settings.update {
                    it.copy(updateLastCheckAt = now, updateKnownTag = knownTag)
                }

                if (release == null || !newer) {
                    // 手动检查要给反馈；开屏检测保持安静
                    if (interactive) _prompt.value = UpdatePrompt.UpToDate
                } else if (interactive || settings.settings.value.shouldPromptUpdate(release.tag, now)) {
                    _prompt.value = UpdatePrompt.Available(release)
                }
            }.onFailure { error ->
                val message = errorMessage(error)
                log("检测失败：$message")
                _status.value = UpdateStatus.Failed(message)
                // 开屏检测失败保持静默，别拿网络问题打扰用户
                if (interactive) _prompt.value = UpdatePrompt.Failed(message)
            }
        }
    }

    /** 真机排查用：`adb logcat -s BaiyunKeys`（与开锁链路同一个 tag）。 */
    private fun log(line: String) {
        runCatching { android.util.Log.d("BaiyunKeys", "[更新] $line") }
    }

    private fun errorMessage(error: Throwable): String = when (error) {
        is UpdateException -> error.message ?: "检测失败"
        else -> "检测失败，请稍后重试"
    }

    private fun hasNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private companion object {
        const val RELEASES_PAGE = "https://github.com/XaviorShen/BaiyunKeys-Android/releases"
    }
}

/**
 * 关于页那枚按钮的文案。
 *
 * 每个状态都写清「点下去会发生什么」，避免只写「已是最新」让人以为不可点。
 */
val UpdateStatus.chipLabel: String
    get() = when (this) {
        UpdateStatus.Idle -> "检查更新"
        UpdateStatus.Checking -> "正在检查…"
        UpdateStatus.UpToDate -> "已是最新 · 重新检查"
        is UpdateStatus.Available -> "检测到更新 · 查看详情"
        is UpdateStatus.Failed -> "检查失败 · 点此重试"
    }
