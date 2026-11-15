package com.baiyunkeys.core.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Release 里挂的一个文件。本项目只关心 APK，但模型保留通用字段。 */
data class ReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
)

/**
 * GitHub Release 的领域模型。
 *
 * [tag] 保留原始写法（`v1.0.2`）用于展示，[version] 是归一化后的版本号用于比较。
 */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val asset: ReleaseAsset?,
    /**
     * 发布说明里机器可读的 versionCode（`<!-- versionCode=3 -->`）。
     *
     * GitHub Release 本身没有这个字段，但安装成败看的是它而不是 tag 字符串，
     * 所以发版模板里必须带上；解析不到时退回按 [version] 比较。
     */
    val versionCode: Int? = null,
) {
    /** 「立即更新」的目标：有 APK 就给 APK 直链，没有就退回 Release 页。 */
    val downloadUrl: String get() = asset?.downloadUrl ?: pageUrl

    /** 「前往 GitHub 下载」的目标。 */
    val htmlUrl: String get() = pageUrl

    val sizeLabel: String get() = asset?.let { formatSize(it.size) }.orEmpty()

    val dateLabel: String get() = formatDate(publishedAt)

    /** 标题为空时退回 tag，避免弹窗里出现一行空白。 */
    val displayTitle: String get() = title.trim().ifEmpty { tag }
}

/** 发版说明里的 versionCode 标记：`<!-- versionCode=3 -->` 或 `versionCode: 3`。 */
private val VERSION_CODE_MARKER = Regex("""versionCode\s*[=:]\s*(\d+)""", RegexOption.IGNORE_CASE)

/** 从 Release 正文里解析 versionCode；没有就返回 null，交给 versionName 兜底。 */
fun parseVersionCode(notes: String): Int? =
    VERSION_CODE_MARKER.find(notes)?.groupValues?.getOrNull(1)?.toIntOrNull()

/**
 * Release notes 是 Markdown，弹窗里按纯文本展示，所以把最常见的标记符号去掉。
 *
 * 只做「去噪」，不做渲染：保留换行与项目符号，行首的 `#`、`>`、列表标记统一收敛成「• 」
 * 或直接删除，正文里的强调符号也一并去掉。
 */
fun simplifyReleaseNotes(raw: String, maxLines: Int = 6, maxChars: Int = 220): String {
    if (raw.isBlank()) return ""
    val lines = raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("<!--") || it.startsWith("**Full Changelog**") }
        .map { line ->
            line.substringBefore("<!--").trim()
                .removePrefix("###").removePrefix("##").removePrefix("#")
                .removePrefix(">").removePrefix("* ").removePrefix("- ")
                .replace("**", "").replace("__", "").replace("`", "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .take(maxLines)

    val text = lines.joinToString("\n") { "• $it" }
    return if (text.length > maxChars) text.take(maxChars).trimEnd() + "…" else text
}

/** `12.4 MB` / `980 KB` / `512 B`。 */
fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** ISO8601 → `2026-11-10`。解析失败就原样返回，不让格式化问题把整次检查搞挂。 */
fun formatDate(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE)
}.getOrElse { iso.take(10) }

// MARK: - GitHub 响应 DTO

@Serializable
internal data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
) {
    fun toModel(): ReleaseInfo? {
        val tag = tagName.trim()
        if (tag.isEmpty()) return null
        return ReleaseInfo(
            tag = tag,
            version = VersionCompare.normalize(tag),
            title = name,
            notes = body,
            pageUrl = htmlUrl,
            publishedAt = publishedAt,
            prerelease = prerelease,
            asset = pickApk(assets),
            versionCode = parseVersionCode(body),
        )
    }

    /** 优先固定文件名 `BaiyunKeys-Android.apk`（CI 里就是这个），否则退回第一个 APK。 */
    private fun pickApk(list: List<AssetDto>): ReleaseAsset? {
        val apk = list.firstOrNull { it.name.equals(APK_NAME, ignoreCase = true) }
            ?: list.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return null
        return ReleaseAsset(
            name = apk.name,
            size = apk.size,
            downloadUrl = apk.downloadUrl,
        )
    }

    internal companion object {
        const val APK_NAME = "BaiyunKeys-Android.apk"
    }
}

@Serializable
internal data class AssetDto(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
)
