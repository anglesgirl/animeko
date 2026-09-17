package me.him188.ani.android.ech

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 轻量文件日志：写入 App 外部文件目录（无需权限，用户可用文件管理器访问），同步 logcat。
// 供登录/ECH 流程排查；错误页可展示日志尾部，测试失败时把错误页文字发回即可定位。
// 正式版(release)自动关闭：不写文件、不打 logcat，零开销。
object EchLog {
    private const val TAG = "ECH"
    private const val MAX_BYTES = 512 * 1024L
    private const val ENABLED = me.him188.ani.android.BuildConfig.DEBUG
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (!ENABLED || logFile != null) return
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, "ech-log.txt")
            log("== log init: ${logFile?.absolutePath} ==")
        }
    }

    @Synchronized
    fun log(msg: String) {
        if (!ENABLED) return
        Log.i(TAG, msg)
        val f = logFile ?: return
        try {
            if (f.length() > MAX_BYTES) f.delete()
            f.appendText("[${fmt.format(Date())}] $msg\n")
        } catch (e: Exception) {
            Log.w(TAG, "log write failed: ${e.message}")
        }
    }

    fun tail(n: Int = 60): String {
        if (!ENABLED) return "(日志已关闭)"
        val f = logFile ?: return "(日志未初始化)"
        return try {
            f.readLines().takeLast(n).joinToString("\n")
        } catch (e: Exception) {
            "(读取日志失败: ${e.message})"
        }
    }

    fun path(): String = logFile?.absolutePath ?: "(日志未初始化)"
}
