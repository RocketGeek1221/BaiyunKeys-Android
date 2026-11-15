package com.baiyunkeys.core.update

/**
 * 版本号比较。
 *
 * 只处理「点分数字」这一种形态（`1.0.1`、`v1.0.2`、`1.2`）。GitHub 的 tag 允许任意写法，
 * 所以这里做两件事：
 * - 去掉 `v` / `V` 前缀；
 * - 裁掉预发布与构建后缀（`-beta.1`、`+build`），侧载场景用不到语义化版本的完整规则。
 *
 * 缺段按 `0` 补，因此 `1.0` 与 `1.0.0` 判定为相等。
 */
object VersionCompare {

    /** 把 `v1.2.3-beta.1` 归一化成 `1.2.3`；已经是干净的版本号则原样返回。 */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val withoutPrefix = if (trimmed.startsWith("v", ignoreCase = true)) trimmed.substring(1) else trimmed
        return withoutPrefix
            .takeWhile { it != '-' && it != '+' && !it.isWhitespace() }
            .trim()
    }

    /**
     * 逐段比较两个版本号。
     *
     * @return 负数表示 [a] 更旧，0 表示相等，正数表示 [a] 更新。
     */
    fun compare(a: String, b: String): Int {
        val left = normalize(a).split('.')
        val right = normalize(b).split('.')
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val result = compareSegment(left.getOrNull(index), right.getOrNull(index))
            if (result != 0) return result
        }
        return 0
    }

    /** 远端是否比本地新。本地版本高于远端时返回 false（开发机常出现这种情况）。 */
    fun isRemoteNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    /**
     * 判「远端更新」的**首选**方式：比 versionCode。
     *
     * versionName 是给人看的，versionCode 才是系统判定能否覆盖安装的依据；
     * 远端 Release 正文里带 `versionCode=n` 时优先用它，解析不到才退回版本号比较。
     */
    fun isRemoteNewer(
        remoteVersion: String,
        remoteVersionCode: Int?,
        localVersion: String,
        localVersionCode: Int,
    ): Boolean {
        if (remoteVersionCode != null && remoteVersionCode > 0 && localVersionCode > 0) {
            return remoteVersionCode > localVersionCode
        }
        return isRemoteNewer(remoteVersion, localVersion)
    }

    private fun compareSegment(left: String?, right: String?): Int {
        val l = left?.takeIf { it.isNotEmpty() } ?: "0"
        val r = right?.takeIf { it.isNotEmpty() } ?: "0"
        val lp = l.toIntOrNull()
        val rp = r.toIntOrNull()
        return when {
            lp != null && rp != null -> lp.compareTo(rp)
            lp != null -> 1
            rp != null -> -1
            else -> l.compareTo(r)
        }
    }
}
