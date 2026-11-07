package com.jiangshan.atlas

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * 江山时序 · 中国历史交互舆图的 Android 封装。
 *
 * 站点是零构建的纯静态资源，全部打包在 assets/www 下，
 * 通过 WebViewAssetLoader 以 https://appassets.androidplatform.net/ 这个
 * 正规来源提供给 WebView，从而：
 *   - 不需要 file:// 的放宽配置，fetch / localStorage 行为与线上一致
 *   - 不声明 INTERNET 权限也能正常工作
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(PAPER_COLOR)
            isVerticalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = false
                useWideViewPort = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                // 站点的字体走 Google Fonts，离线拿不到，用系统字体兜底即可
                textZoom = 100
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url
                    // 站内资源由 AssetLoader 提供
                    if (url.host == ASSET_DOMAIN) {
                        return assetLoader.shouldInterceptRequest(url)
                    }
                    // 站外资源（统计脚本 / CDN 字体）一律拦截，避免等待超时
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
            }
        }

        setContentView(webView)
        addResearchEntry()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl(resolveStartUrl())
    }

    /** 右下浮动按钮：打开考据助手 */
    private fun addResearchEntry() {
        val btn = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = "考据"
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 10, 20, 10)
            textSize = 14f
            elevation = 12f
            stateListAnimator = null
            setOnClickListener {
                researchLauncher.launch(Intent(this@MainActivity, ResearchActivity::class.java))
            }
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(0xE62B2622.toInt())
            setStroke(1, 0x66FFFFFF)
        }
        btn.background = bg

        val lp = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            setMargins(24, 0, 24, 240)
        }
        (webView.parent as? ViewGroup)?.addView(btn, lp)
    }

    /** 考据助手返回的地图深链 */
    private val researchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(ResearchActivity.EXTRA_QUERY)?.let { query ->
                webView.loadUrl("$BASE_URL/index.html?$query")
            }
        }
    }

    /** 支持 jiangshan://atlas?year=741&place=长安 这类深链 */
    private fun resolveStartUrl(): String {
        val data = intent?.data
        val query = if (data != null && data.scheme == "jiangshan") {
            data.query?.let { "?$it" }.orEmpty()
        } else {
            ""
        }
        return "$BASE_URL/index.html$query"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    companion object {
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"
        private const val BASE_URL = "https://$ASSET_DOMAIN/assets/www"
        private const val PAPER_COLOR = 0xFFF3EEE4.toInt()
    }
}
