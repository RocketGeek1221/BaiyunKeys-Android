package com.baiyunkeys.core.settings

import com.baiyunkeys.core.unlock.ScanFilter
import com.baiyunkeys.core.unlock.UnlockOptions

/** 外观：跟随系统 / 强制浅色 / 强制深色（对应 `AppStore.Theme`）。 */
enum class ThemeMode(val raw: String, val label: String) {
    Auto("auto", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色"),
    ;

    companion object {
        fun of(raw: String?): ThemeMode = entries.firstOrNull { it.raw == raw } ?: Auto
    }
}

/** 「自动获取配置」的三种通道（对应 `AppStore.FetchMethod`）。 */
enum class FetchMethod(val raw: String, val label: String) {
    IdCard("idcard", "手机号 + 身份证（接口失效）"),
    OpenId("openid", "手机号 + OpenID（免身份证）"),
    OpenIdSm2("openid_sm2", "手机号 + OpenID + SM2（加密版）"),
    ;

    companion object {
        fun of(raw: String?): FetchMethod = entries.firstOrNull { it.raw == raw } ?: OpenId
    }
}

/**
 * 偏好设置。键名与 iOS `AppStore.Keys` 保持一致（方案 §4.5）。
 *
 * 取值一律走 [sanitized] 收敛到合法区间：UI 的步进器与磁盘上的脏数据都不会越界。
 */
data class AppSettings(
    // 显示与反馈
    val hideSensitive: Boolean = true,
    val haptics: Boolean = true,

    // 开锁行为
    val autoRetry: Boolean = false,
    val autoRetryCount: Int = 3,
    val scanTimeoutSeconds: Int = 10,
    val saveHistory: Boolean = true,
    /** 打开 App 后自动开锁一次。 */
    val quickUnlock: Boolean = false,
    /** 「打开即开锁」的子开关：切回前台也静默尝试一次（只用缓存直连）。 */
    val unlockOnResume: Boolean = false,

    // 安全
    val requireBiometric: Boolean = false,

    // 外观
    val theme: ThemeMode = ThemeMode.Auto,

    // 长辈模式
    val elderMode: Boolean = false,
    val elderLocksDoorSelector: Boolean = false,
    val elderHidesLogs: Boolean = false,
    val elderHidesHistory: Boolean = false,

    // 展示状态
    val showLogsExpanded: Boolean = false,
    val showHistoryExpanded: Boolean = false,

    // 自动获取配置（隐藏入口）
    val fetchMethod: FetchMethod = FetchMethod.OpenId,
    val openId: String = "",
    val smPublicKey: String = "",
    val smPrivateKey: String = "",
    val autoFetchVisible: Boolean = false,
    /** 上次显示自动获取的时间点（毫秒），用于 24h TTL。 */
    val autoFetchShownAt: Long = 0,

    // 设备标识（登录接口 deviceInfo.udid）
    val udid: String = "",

    // 更新检测
    /** 是否允许自动检查更新（联网）。关掉后冷启动不再发任何请求，手动检查仍可用。 */
    val updateCheckEnabled: Boolean = true,
    /** 上次检查更新的时间点（毫秒）。开屏检测按这个做节流。 */
    val updateLastCheckAt: Long = 0,
    /**
     * 上次检查的结论，用于进程重启后把胶囊直接点亮（不用再联网）：
     * 空串表示「从没查过」，[UPDATE_KNOWN_LATEST] 表示上次查到的就是最新，其余是远端 tag。
     */
    val updateKnownTag: String = "",
    /** 上次弹过更新提示的版本号（原始 tag，例如 `v1.0.2`）。 */
    val updatePromptedVersion: String = "",
    /** 上次弹更新提示的时间点（毫秒），与版本号一起做 24h 抑制。 */
    val updatePromptedAt: Long = 0,
    /** 「联网说明」是否已经提示过（只提示一次）。 */
    val networkNoticeShown: Boolean = false,
) {

    fun sanitized(): AppSettings = copy(
        autoRetryCount = autoRetryCount.coerceIn(RETRY_RANGE),
        scanTimeoutSeconds = scanTimeoutSeconds.coerceIn(SCAN_TIMEOUT_RANGE),
        openId = openId.trim(),
        smPublicKey = smPublicKey.trim(),
        smPrivateKey = smPrivateKey.trim(),
    )

    /**
     * 「自动获取配置」是否该显示：开关打开 **且** 距上次显示未超过 24h。
     * 超过 TTL 自动翻回隐藏（与 iOS `checkAutoFetchExpiry` 一致）。
     */
    fun isAutoFetchVisible(nowMillis: Long): Boolean =
        autoFetchVisible && nowMillis - autoFetchShownAt in 0 until AUTO_FETCH_TTL_MS

    /** 距上次检查更新是否已经超过 6h，且用户没有关掉自动检查。 */
    fun shouldCheckUpdate(nowMillis: Long): Boolean =
        updateCheckEnabled && nowMillis - updateLastCheckAt >= UPDATE_CHECK_INTERVAL_MS

    /**
     * 同一个版本 24 小时内是否还该再弹更新提示。
     *
     * 「稍后提醒」和「立即更新」都会记录时间戳，所以点过之后至少隔一天才会再打扰一次。
     */
    fun shouldPromptUpdate(tag: String, nowMillis: Long): Boolean =
        tag != updatePromptedVersion || nowMillis - updatePromptedAt >= UPDATE_PROMPT_TTL_MS

    /** 换算成开锁选项：重试与扫描超时直接来自偏好。 */
    fun toUnlockOptions(
        silent: Boolean = false,
        scanFilter: ScanFilter = ScanFilter.None,
    ): UnlockOptions {
        val safe = sanitized()
        return UnlockOptions(
            autoRetry = safe.autoRetry,
            autoRetryCount = safe.autoRetryCount,
            scanTimeoutMs = safe.scanTimeoutSeconds * 1000L,
            scanFilter = scanFilter,
            silent = silent,
        )
    }

    companion object {
        val RETRY_RANGE = 1..20
        val SCAN_TIMEOUT_RANGE = 3..60
        const val AUTO_FETCH_TTL_MS = 24L * 60 * 60 * 1000

        /** 开屏检测更新的最小间隔。 */
        const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** 同一版本的更新弹窗抑制窗口。 */
        const val UPDATE_PROMPT_TTL_MS = 24L * 60 * 60 * 1000

        /** [updateKnownTag] 的哨兵值：上次检查的结论是「已是最新」。 */
        const val UPDATE_KNOWN_LATEST = "latest"

        /** 「自动获取」的访问码（iOS 里也是这个常量）。 */
        const val AUTO_FETCH_ACCESS_CODE = "123456789"
    }
}
