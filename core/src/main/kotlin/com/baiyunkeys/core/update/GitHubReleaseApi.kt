package com.baiyunkeys.core.update

import com.baiyunkeys.core.net.HttpTransport
import com.baiyunkeys.core.net.HttpRequest
import kotlinx.serialization.json.Json

/** 检测更新过程中的错误，文案直接给用户看。 */
class UpdateException(message: String, val retryable: Boolean = true) : Exception(message)

/**
 * 只读 GitHub Releases API，用于检测新版本。
 *
 * 走 [HttpTransport] 抽象，测试注入假实现即可离线断言整条解析链路。
 *
 * 端点用 `/releases/latest`：GitHub 只会返回**非 prerelease、非 draft** 的最新条目，
 * 正好对应 CI 里 `v*` tag 产出的正式版本；滚动 `latest` 那个包是 prerelease，不会混进来。
 */
class GitHubReleaseApi(
    private val http: HttpTransport,
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 拉取最新正式 Release。
     *
     * @return 没有正式 Release 时返回 `null`（GitHub 对这种情况回 404，属于正常状态而非错误）。
     * @throws UpdateException 网络异常、限流、返回体解析失败。
     */
    suspend fun latestRelease(): ReleaseInfo? {
        val response = try {
            http.execute(
                HttpRequest(
                    method = "GET",
                    url = "$API_BASE/repos/$owner/$repo/releases/latest",
                    headers = mapOf(
                        "Accept" to "application/vnd.github+json",
                        "User-Agent" to USER_AGENT,
                        "X-GitHub-Api-Version" to API_VERSION,
                    ),
                    timeoutMs = TIMEOUT_MS,
                ),
            )
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException(networkMessage(e))
        }

        return when (response.code) {
            200 -> parse(response.body)
            404 -> null
            403, 429 -> throw UpdateException("请求过于频繁，请稍后再试")
            in 500..599 -> throw UpdateException("GitHub 服务异常（HTTP ${response.code}）")
            else -> throw UpdateException("检测失败：HTTP ${response.code}")
        }
    }

    /**
     * 拉取固定 tag `latest` 的**滚动构建**。目前 UI 不接，
     * 留着是因为它和正式版共用同一套解析，将来要做「滚动构建通道」时不用重写。
     */
    suspend fun rollingBuild(): ReleaseInfo? {
        val response = try {
            http.execute(
                HttpRequest(
                    method = "GET",
                    url = "$API_BASE/repos/$owner/$repo/releases/tags/$ROLLING_TAG",
                    headers = mapOf(
                        "Accept" to "application/vnd.github+json",
                        "User-Agent" to USER_AGENT,
                    ),
                    timeoutMs = TIMEOUT_MS,
                ),
            )
        } catch (e: Exception) {
            throw UpdateException(networkMessage(e))
        }
        return if (response.code == 200) parse(response.body) else null
    }

    /**
     * 网络异常翻成人话。
     *
     * 用户看到的应该是「超时 / 域名解析失败 / HTTPS 失败 / 被系统拒绝」，
     * 而不是 `socket failed: EACCES (Permission denied)` 这种原文。
     */
    private fun networkMessage(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试"
        is java.net.UnknownHostException -> "无法解析 api.github.com，请检查网络或代理设置"
        is javax.net.ssl.SSLException -> "HTTPS 连接失败，请检查网络环境"
        is java.net.SocketException -> "网络请求被系统拒绝（无网络权限或网络策略限制）"
        else -> "网络不可用，请检查连接后重试"
    }

    private fun parse(body: String): ReleaseInfo {
        if (body.isBlank()) throw UpdateException("返回数据为空")
        val dto = try {
            json.decodeFromString<ReleaseDto>(body)
        } catch (e: Exception) {
            throw UpdateException("返回数据解析失败")
        }
        return dto.toModel() ?: throw UpdateException("返回数据缺少版本号")
    }

    companion object {
        const val DEFAULT_OWNER = "XaviorShen"
        const val DEFAULT_REPO = "BaiyunKeys-Android"
        const val API_BASE = "https://api.github.com"
        const val ROLLING_TAG = "latest"
        const val USER_AGENT = "BaiyunKeys-Android"

        /** 检测只是锦上添花，超时要短，别让用户在关于页干等。 */
        const val TIMEOUT_MS = 10_000L

        private const val API_VERSION = "2022-11-28"
    }
}
