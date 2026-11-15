package com.baiyunkeys.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本比较的边界用例。GitHub 的 tag 是自由文本，这里把可能的写法都钉死。 */
class VersionCompareTest {

    @Test
    fun normalizeStripsVPrefixAndSuffixes() {
        assertEquals("1.0.2", VersionCompare.normalize("v1.0.2"))
        assertEquals("1.0.2", VersionCompare.normalize("V1.0.2"))
        assertEquals("1.0.2", VersionCompare.normalize("  1.0.2  "))
        assertEquals("1.2.3", VersionCompare.normalize("v1.2.3-beta.1"))
        assertEquals("1.2.3", VersionCompare.normalize("1.2.3+build.7"))
    }

    @Test
    fun comparesPointSeparatedNumbers() {
        assertTrue(VersionCompare.compare("1.0.1", "v1.0.2") < 0)
        assertTrue(VersionCompare.compare("v1.0.2", "1.0.1") > 0)
        assertEquals(0, VersionCompare.compare("1.0.1", "v1.0.1"))
    }

    @Test
    fun comparesNumericallyNotLexicographically() {
        assertTrue("1.0.10 必须大于 1.0.9", VersionCompare.compare("1.0.10", "1.0.9") > 0)
        assertTrue(VersionCompare.compare("1.10", "1.9") > 0)
        assertTrue(VersionCompare.compare("2.0", "10.0") < 0)
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertEquals(0, VersionCompare.compare("1.0", "1.0.0"))
        assertEquals(0, VersionCompare.compare("1", "1.0.0.0"))
        assertTrue(VersionCompare.compare("1.1", "1.0.9") > 0)
        assertTrue(VersionCompare.compare("1.0", "1.0.1") < 0)
    }

    @Test
    fun prereleaseSuffixIsIgnored() {
        assertEquals(0, VersionCompare.compare("1.0.2-beta.1", "1.0.2"))
    }

    @Test
    fun isRemoteNewerRespectsDirection() {
        assertTrue(VersionCompare.isRemoteNewer("v1.0.2", "1.0.1"))
        assertFalse(VersionCompare.isRemoteNewer("v1.0.1", "1.0.1"))
        // 开发机版本高于线上时不能提示更新
        assertFalse(VersionCompare.isRemoteNewer("v1.0.1", "1.0.2"))
    }

    @Test
    fun nonNumericSegmentsFallBackToLexicographic() {
        assertTrue(VersionCompare.compare("1.0.alpha", "1.0.beta") < 0)
        // 纯数字段永远大于非数字段
        assertTrue(VersionCompare.compare("1.0.2", "1.0.beta") > 0)
    }

    @Test
    fun versionCodeWinsOverVersionName() {
        // 远端 code 更大就算更新，哪怕 tag 写得一样
        assertTrue(VersionCompare.isRemoteNewer("1.0.2", 4, "1.0.2", 3))
        // 远端 code 相同就不算更新（tag 写花了也不误导）
        assertFalse(VersionCompare.isRemoteNewer("1.0.9", 3, "1.0.2", 3))
        // 远端 code 更小同样不算
        assertFalse(VersionCompare.isRemoteNewer("1.0.9", 2, "1.0.2", 3))
    }

    @Test
    fun fallsBackToVersionNameWithoutVersionCode() {
        assertTrue(VersionCompare.isRemoteNewer("v1.0.3", null, "1.0.2", 3))
        assertFalse(VersionCompare.isRemoteNewer("v1.0.2", null, "1.0.2", 3))
        // 本地 code 未知（0）时也退回版本号比较
        assertTrue(VersionCompare.isRemoteNewer("v1.0.3", 4, "1.0.2", 0))
    }
}
