package me.him188.ani.android.activity

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.him188.ani.android.ech.BangumiNativeLogin

// 原生登录界面：不走 WebView。输入账号密码 → 原生 ECH HTTP 完成 Bangumi 登录授权 → 完成。
// 授权完成后 animeko 服务器完成绑定，App 现有 OAuthConfigurator 轮询会自动收到，无需本页通知。
class BangumiLoginActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
    }

    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authorizeUrl = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        val email = EditText(this).apply {
            hint = "账号（邮箱 / 用户名）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val password = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val status = TextView(this).apply { textSize = 13f; setTextColor(Color.GRAY) }
        val error = TextView(this).apply { textSize = 13f; setTextColor(Color.RED) }
        val loginBtn = Button(this).apply { text = "登录" }

        loginBtn.setOnClickListener {
            if (loading) return@setOnClickListener
            val u = email.text.toString().trim()
            val p = password.text.toString()
            if (u.isBlank() || p.isBlank()) { error.text = "请输入账号和密码"; return@setOnClickListener }
            loading = true
            error.text = ""
            status.text = "开始登录…"
            loginBtn.isEnabled = false
            lifecycleScope.launch {
                BangumiNativeLogin.login(authorizeUrl, u, p) { s ->
                    runOnUiThread { status.text = s }
                }.onSuccess {
                    status.text = "授权完成，正在跳转…"
                    runCatching { Thread.sleep(600) }
                    finish()
                }.onFailure { e ->
                    loading = false
                    runOnUiThread {
                        loginBtn.isEnabled = true
                        error.text = e.message ?: "登录失败"
                        status.text = ""
                    }
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 0, 48, 0)
            addView(TextView(this@BangumiLoginActivity).apply {
                text = "Bangumi 登录"
                textSize = 20f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@BangumiLoginActivity).apply {
                text = "用于番剧授权（走 ECH 加密通道）"
                textSize = 12f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
            })
            addView(email, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 32 })
            addView(password, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
            addView(loginBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
            addView(status)
            addView(error)
        }
        setContentView(root)
    }
}
