/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.content
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.utils.ktor.getPlatformKtorEngine

class UpdateChecker {
    /**
     * 检查是否有更新的版本. 返回最新版本的信息, 或者 `null` 表示没有新版本.
     */
    suspend fun checkLatestVersion(
        releaseClass: ReleaseClass,
        currentVersion: String = currentAniBuildConfig.versionName,
    ): NewVersion? {
        // ECH 分支：只跟自家仓库要更新，不碰官方更新服务器。
        // 自家尚无发版时返回空，不提示。
        return kotlin.runCatching { checkForkRelease(currentVersion) }.getOrNull()
    }

    private suspend fun checkForkRelease(currentVersion: String): NewVersion? {
        HttpClient(getPlatformKtorEngine()) {
            expectSuccess = true
        }.use { client ->
            val root = json.parseToJsonElement(
                client.get("https://api.github.com/repos/anglesgirl/animeko/releases/latest").bodyAsText(),
            ).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.content ?: return null
            if (!isNewerVersion(tag, currentVersion)) return null
            val apkUrl = root["assets"]?.jsonArray
                ?.mapNotNull { it.jsonObject["browser_download_url"]?.jsonPrimitive?.content }
                .orEmpty()
                .firstOrNull { it.endsWith("-arm64-v8a.apk") }
                ?: return null
            val body = root["body"]?.jsonPrimitive?.content.orEmpty()
            val published = root["published_at"]?.jsonPrimitive?.content.orEmpty()
            return NewVersion(
                name = tag,
                changelogs = listOf(Changelog(tag, published, body)),
                downloadUrlAlternatives = listOf(apkUrl),
                publishedAt = published,
            )
        }
    }

    // 标签如 ech-6.2.0-1；取数字段逐段比，标签新才提示。
    private fun isNewerVersion(tag: String, current: String): Boolean {
        fun nums(s: String) = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
        val a = nums(tag)
        val b = nums(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a.getOrElse(i) { 0 }) - (b.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return tag != current
    }

    private companion object {
        private val json = Json {
            ignoreUnknownKeys = true
        }
    }
}
