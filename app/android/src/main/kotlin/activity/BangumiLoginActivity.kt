package me.him188.ani.android.activity

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.him188.ani.android.ech.BangumiNativeLogin

// 原生登录界面：账号+密码+图形验证码（点击刷新），字段已对齐 Bangumi 真实登录/授权请求。
// 授权完成后 animeko 服务器完成绑定，App 现有 OAuthConfigurator 轮询会自动收到。
class BangumiLoginActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
    }

    private var prepare: BangumiNativeLogin.Prepare? = null
    private var loading = false
    private lateinit var status: TextView
    private lateinit var captchaRow: LinearLayout
    private lateinit var captchaImage: ImageView

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
        val captchaInput = EditText(this).apply {
            hint = "验证码（看不清点图片刷新）"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val captchaImageV = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            setOnClickListener { loadPrepare() }
        }
        captchaImage = captchaImageV
        val captchaRowV = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(captchaImageV, LinearLayout.LayoutParams(320, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(captchaInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 12 })
        }
        captchaRow = captchaRowV
        val statusTv = TextView(this).apply { textSize = 13f; setTextColor(Color.GRAY) }
        val error = TextView(this).apply { textSize = 13f; setTextColor(Color.RED) }
        val loginBtn = Button(this).apply { text = "登录" }
        status = statusTv

        fun onLoginClicked() {
            if (loading) return
            val p = prepare ?: run { error.text = "登录页未就绪，请稍候"; return }
            val u = email.text.toString().trim()
            val pw = password.text.toString()
            val cp = captchaInput.text.toString().trim()
            val needLogin = p.captchaImage != null
            if (needLogin && (u.isBlank() || pw.isBlank() || cp.isBlank())) {
                error.text = "请输入账号、密码和验证码"
                return
            }
            error.text = ""
            loading = true
            loginBtn.isEnabled = false
            lifecycleScope.launch {
                BangumiNativeLogin.submit(p, u, pw, cp) { s -> runOnUiThread { status.text = s } }
                    .onSuccess {
                        status.text = "授权完成，正在跳转…"
                        runCatching { Thread.sleep(600) }
                        finish()
                    }
                    .onFailure { e ->
                        loading = false
                        runOnUiThread {
                            loginBtn.isEnabled = true
                            error.text = e.message ?: "登录失败"
                            status.text = ""
                            // 失败后刷新验证码（可能验证码错误/过期）
                            loadPrepare()
                        }
                    }
            }
        }

        loginBtn.setOnClickListener { onLoginClicked() }

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
            addView(captchaRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
            addView(loginBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
            addView(statusTv)
            addView(error)
        }
        setContentView(root)

        // 首次加载登录页 + 验证码
        status.text = "加载登录页…"
        lifecycleScope.launch {
            BangumiNativeLogin.prepare(authorizeUrl) { s -> runOnUiThread { status.text = s } }
                .onSuccess { p ->
                    prepare = p
                    runOnUiThread {
                        status.text = ""
                        if (p.captchaImage != null) {
                            captchaRow.visibility = View.VISIBLE
                            captchaImage.setImageBitmap(BitmapFactory.decodeByteArray(p.captchaImage, 0, p.captchaImage.size))
                        } else {
                            captchaRow.visibility = View.GONE
                            status.text = "检测到已登录，可直接授权"
                            loginBtn.text = "授权确认"
                        }
                    }
                }
                .onFailure { e ->
                    runOnUiThread {
                        loading = false
                        loginBtn.isEnabled = true
                        error.text = e.message ?: "加载登录页失败"
                    }
                }
        }
    }

    // 刷新登录页 + 验证码（点击验证码图片 / 登录失败后调用）
    private fun loadPrepare() {
        val authorizeUrl = intent.getStringExtra(EXTRA_URL) ?: return
        lifecycleScope.launch {
            BangumiNativeLogin.prepare(authorizeUrl) { s -> runOnUiThread { status.text = s } }
                .onSuccess { p ->
                    prepare = p
                    runOnUiThread {
                        status.text = ""
                        if (p.captchaImage != null) {
                            captchaRow.visibility = View.VISIBLE
                            captchaImage.setImageBitmap(BitmapFactory.decodeByteArray(p.captchaImage, 0, p.captchaImage.size))
                        }
                    }
                }
                .onFailure { e -> runOnUiThread { status.text = e.message ?: "刷新失败" } }
        }
    }
}
