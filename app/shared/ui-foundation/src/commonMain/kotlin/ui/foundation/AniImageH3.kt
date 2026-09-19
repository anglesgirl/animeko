/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import com.github.panpf.sketch.http.HttpStack

/**
 * 静态图片的 **HTTP/3(QUIC) + ECH 优先**加载钩子。
 *
 * 图片加载链路是 Sketch → [ScopedHttpClientHttpStack] → Ktor `ScopedHttpClient`。
 * 这里在 HTTP 栈最前面开一个可选钩子：Android 侧（`AniEchH3`）注册后，先用 QUIC+H3 拉一次；
 * 返回 **null 视为“不适用 / 失败”**，调用方原样走下面的 HTTP 栈（fail-closed，不改变任何既有行为）。
 *
 * 做成钩子而不是在 common 代码里直接依赖 Android，是因为 ui-foundation 是 KMP 共享模块：
 * 桌面 / iOS 不注册（保持 null），行为与改动前逐字节一致。
 */
object AniImageH3 {
    /** 返回图片原始字节；null = 没走通，回落既有 HTTP 栈。 */
    @Volatile
    var h3Loader: (suspend (url: String) -> ByteArray?)? = null
}

/**
 * 由 H3 通道直接拿到的响应：状态固定 200（非 200 时钩子已返回 null 让上层回落）。
 * [contentType] 按魔数嗅探，避免解码器因为缺 MIME 挑不出解码器。
 */
internal class AniImageH3Response(
    private val bytes: ByteArray,
) : HttpStack.Response {
    override val code: Int = 200
    override val message: String = "OK"
    override val contentLength: Long = bytes.size.toLong()
    override val contentType: String? = imageContentTypeOf(bytes)

    override fun getHeaderField(name: String): String? = null

    override suspend fun content(): HttpStack.Content = object : HttpStack.Content {
        private var pos = 0

        override suspend fun read(buffer: ByteArray): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(buffer.size, bytes.size - pos)
            if (n <= 0) return 0
            bytes.copyInto(buffer, 0, pos, pos + n)
            pos += n
            return n
        }

        override fun close() = Unit
    }
}

private const val B_R = 0x52.toByte() // R
private const val B_I = 0x49.toByte() // I
private const val B_F = 0x46.toByte() // F
private const val B_W = 0x57.toByte() // W
private const val B_E = 0x45.toByte() // E
private const val B_P = 0x50.toByte() // P
private const val B_f = 0x66.toByte() // f
private const val B_t = 0x74.toByte() // t
private const val B_y = 0x79.toByte() // y
private const val B_lp = 0x70.toByte() // p (ftyp 的第四个小写字母)

/** 魔数 → MIME。H3 通道只在这几种图片格式下放行（`AniEchH3` 也用它做图片校验，保持单一实现）。 */
fun imageContentTypeOf(bytes: ByteArray): String? {
    if (bytes.size < 12) return null
    return when {
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "image/gif"
        bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> "image/bmp"
        bytes[0] == B_R && bytes[1] == B_I && bytes[2] == B_F && bytes[3] == B_F &&
                bytes[8] == B_W && bytes[9] == B_E && bytes[10] == B_P && bytes[11] == B_P -> "image/webp"
        bytes[4] == B_f && bytes[5] == B_t && bytes[6] == B_y && bytes[7] == B_lp -> "image/avif"
        else -> null
    }
}
