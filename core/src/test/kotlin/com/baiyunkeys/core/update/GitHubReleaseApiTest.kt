package com.baiyunkeys.core.update

import com.baiyunkeys.core.net.HttpRequest
import com.baiyunkeys.core.net.HttpResponse
import com.baiyunkeys.core.net.HttpTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** GitHub Release 解析链路：请求形状、字段映射、404/403 分支，全程离线。 */
class GitHubReleaseApiTest {

    private class FakeTransport : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var responder: (HttpRequest) -> HttpResponse = { HttpResponse(200, "{}") }

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return responder(request)
        }
    }

    private fun api(transport: HttpTransport) = GitHubReleaseApi(transport)

    private val fullRelease = """
        {
          "tag_name": "v1.0.2",
          "name": "v1.0.2",
          "body": "## 更新内容\n\n- 修复蓝牙扫描\n- 新增搜索",
          "html_url": "https://github.com/XaviorShen/BaiyunKeys-Android/releases/tag/v1.0.2",
          "published_at": "2026-11-10T12:34:56Z",
          "prerelease": false,
          "draft": false,
          "assets": [
            {
              "name": "BaiyunKeys-Android.apk",
              "size": 13002342,
              "browser_download_url": "https://github.com/XaviorShen/BaiyunKeys-Android/releases/download/v1.0.2/BaiyunKeys-Android.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun latestReleaseHitsExpectedEndpointWithHeaders() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(200, fullRelease) }

        api(transport).latestRelease()

        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://api.github.com/repos/XaviorShen/BaiyunKeys-Android/releases/latest",
            request.url,
        )
        assertEquals("application/vnd.github+json", request.headers["Accept"])
        assertEquals(GitHubReleaseApi.USER_AGENT, request.headers["User-Agent"])
        assertEquals(GitHubReleaseApi.TIMEOUT_MS, request.timeoutMs)
    }

    @Test
    fun parsesVersionCodeMarkerFromBody() = runTest {
        val transport = FakeTransport()
        transport.responder = {
            HttpResponse(200, """{"tag_name":"v1.0.3","body":"<!-- versionCode=7 -->\n\n## 修复"}""")
        }

        val release = api(transport).latestRelease()

        assertEquals(7, release?.versionCode)
    }

    @Test
    fun versionCodeMarkerNeverShowsUpInDialogText() {
        val text = simplifyReleaseNotes("<!-- versionCode=7 -->\n\n- 修复网络异常")

        assertFalse(text.contains("versionCode"))
        assertTrue(text.contains("修复网络异常"))
    }

    @Test
    fun timeoutIsReportedAsTimeout() = runTest {
        val transport = FakeTransport()
        transport.responder = { throw java.net.SocketTimeoutException() }

        val error = runCatching { api(transport).latestRelease() }.exceptionOrNull()

        assertEquals("请求超时，请检查网络后重试", error?.message)
    }

    @Test
    fun unknownHostIsReportedAsDnsFailure() = runTest {
        val transport = FakeTransport()
        transport.responder = { throw java.net.UnknownHostException("api.github.com") }

        val error = runCatching { api(transport).latestRelease() }.exceptionOrNull()

        assertEquals("无法解析 api.github.com，请检查网络或代理设置", error?.message)
    }

    @Test
    fun parsesReleaseFieldsAndAsset() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(200, fullRelease) }

        val release = api(transport).latestRelease()
        assertNotNull(release)
        release!!

        assertEquals("v1.0.2", release.tag)
        assertEquals("1.0.2", release.version)
        assertEquals("https://github.com/XaviorShen/BaiyunKeys-Android/releases/tag/v1.0.2", release.pageUrl)
        assertEquals("2026-11-10", release.dateLabel)
        assertEquals("12.4 MB", release.sizeLabel)
        assertEquals(
            "https://github.com/XaviorShen/BaiyunKeys-Android/releases/download/v1.0.2/BaiyunKeys-Android.apk",
            release.downloadUrl,
        )
        assertTrue(release.notes.contains("修复蓝牙扫描"))
    }

    @Test
    fun prefersNamedApkOverOtherAssets() = runTest {
        val transport = FakeTransport()
        transport.responder = {
            HttpResponse(
                200,
                """
                {
                  "tag_name": "v1.1.0",
                  "html_url": "https://example.invalid/r",
                  "assets": [
                    {"name": "checksums.txt", "size": 10, "browser_download_url": "https://example.invalid/c"},
                    {"name": "BaiyunKeys-Android.apk", "size": 2048, "browser_download_url": "https://example.invalid/apk"},
                    {"name": "other.apk", "size": 4096, "browser_download_url": "https://example.invalid/other"}
                  ]
                }
                """.trimIndent(),
            )
        }

        val release = api(transport).latestRelease()!!
        assertEquals("https://example.invalid/apk", release.downloadUrl)
        assertEquals("BaiyunKeys-Android.apk", release.asset?.name)
    }

    @Test
    fun fallsBackToFirstApkWhenNameDiffers() = runTest {
        val transport = FakeTransport()
        transport.responder = {
            HttpResponse(
                200,
                """
                {
                  "tag_name": "v1.1.0",
                  "html_url": "https://example.invalid/r",
                  "assets": [
                    {"name": "notes.md", "size": 10, "browser_download_url": "https://example.invalid/md"},
                    {"name": "app-release.apk", "size": 4096, "browser_download_url": "https://example.invalid/apk"}
                  ]
                }
                """.trimIndent(),
            )
        }

        assertEquals("https://example.invalid/apk", api(transport).latestRelease()!!.downloadUrl)
    }

    @Test
    fun downloadUrlFallsBackToPageWhenNoAsset() = runTest {
        val transport = FakeTransport()
        transport.responder = {
            HttpResponse(
                200,
                """{"tag_name":"v1.2.0","html_url":"https://example.invalid/page","assets":[]}""",
            )
        }

        val release = api(transport).latestRelease()!!
        assertNull(release.asset)
        assertEquals("https://example.invalid/page", release.downloadUrl)
    }

    @Test
    fun notFoundMeansNoFormalRelease() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(404, """{"message":"Not Found"}""") }

        assertNull("404 是「还没有正式 Release」，不是错误", api(transport).latestRelease())
    }

    @Test
    fun rateLimitIsReportedAsRetryableError() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(403, """{"message":"API rate limit exceeded"}""") }

        try {
            api(transport).latestRelease()
            fail("限流必须抛异常")
        } catch (e: UpdateException) {
            assertTrue(e.message.orEmpty().contains("频繁"))
            assertTrue(e.retryable)
        }
    }

    @Test
    fun transportFailureBecomesFriendlyMessage() = runTest {
        val transport = FakeTransport()
        transport.responder = { throw java.io.IOException("no network") }

        try {
            api(transport).latestRelease()
            fail("网络异常必须抛异常")
        } catch (e: UpdateException) {
            assertTrue(e.message.orEmpty().contains("网络"))
        }
    }

    @Test
    fun malformedJsonIsReported() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(200, "not json") }

        try {
            api(transport).latestRelease()
            fail("坏 JSON 必须抛异常")
        } catch (e: UpdateException) {
            assertTrue(e.message.orEmpty().contains("解析"))
        }
    }

    @Test
    fun missingTagIsReported() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(200, """{"html_url":"https://example.invalid"}""") }

        try {
            api(transport).latestRelease()
            fail("缺版本号必须抛异常")
        } catch (e: UpdateException) {
            assertTrue(e.message.orEmpty().contains("版本号"))
        }
    }

    // MARK: - 展示辅助

    @Test
    fun notesAreSimplifiedToPlainText() {
        val raw = """
            ## 更新内容

            - 修复**蓝牙**扫描
            * 新增 `搜索`
            [查看详情](https://example.invalid)

            **Full Changelog**: https://example.invalid/compare
        """.trimIndent()

        val text = simplifyReleaseNotes(raw)
        assertTrue(text.contains("• 更新内容"))
        assertTrue(text.contains("• 修复蓝牙扫描"))
        assertTrue(text.contains("• 新增 搜索"))
        assertTrue(text.contains("• 查看详情"))
        assertTrue("Full Changelog 行必须被丢掉", !text.contains("Full Changelog"))
        assertTrue("不能残留 Markdown 标记", !text.contains("**") && !text.contains("`"))
    }

    @Test
    fun notesRespectLimits() {
        val raw = (1..10).joinToString("\n") { "- 第 $it 条" }
        val text = simplifyReleaseNotes(raw, maxLines = 3)
        assertEquals(3, text.lines().size)

        val long = simplifyReleaseNotes("- " + "长".repeat(500), maxChars = 20)
        assertTrue(long.length <= 21)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun blankNotesProduceEmptyString() {
        assertEquals("", simplifyReleaseNotes("   \n\n "))
    }

    @Test
    fun formatsSizes() {
        assertEquals("512 B", formatSize(512))
        assertEquals("2 KB", formatSize(2048))
        assertEquals("12.4 MB", formatSize(13_002_342))
        assertEquals("", formatSize(0))
    }
}
