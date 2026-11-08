package com.jiangshan.atlas

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.jiangshan.atlas.agent.AgentPanel
import com.jiangshan.atlas.agent.AtlasBridge
import java.io.ByteArrayInputStream

/**
 * 江山时序 · 中国历史交互舆图。
 *
 * 站点资源零构建打包在 assets/www，通过 WebViewAssetLoader 以
 * https://appassets.androidplatform.net/ 提供，完全离线。
 *
 * 顶部工具栏（.control-strip）里由本类注入两个按钮：
 *   「设置」→ 模型服务配置
 *   「考据」→ 提问与结果
 * 点击通过 JavascriptInterface 回到原生。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var content: FrameLayout
    private lateinit var agentPanel: AgentPanel

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
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
                textZoom = 100
            }

            addJavascriptInterface(WebAppBridge(), JS_BRIDGE_NAME)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url
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

                override fun onPageFinished(view: WebView, url: String) {
                    injectToolbarButtons(view)
                }
            }
        }

        content = FrameLayout(this).apply {
            addView(
                webView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(content)

        agentPanel = AgentPanel(
            this,
            content,
            AtlasBridge(webView),
            onOpenMap = { query -> webView.loadUrl("$BASE_URL/index.html?$query") },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    agentPanel.isShowing() -> agentPanel.hide()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        webView.loadUrl(resolveStartUrl())
    }

    /**
     * 往站点顶部工具栏插入「设置」「考据」两个按钮。
     *
     * 按钮沿用站点自己的 `action-btn subtle` 样式，因此明暗主题、宣纸配色都一致。
     * 用守卫避免重复注入（每次页面加载都会调一次）。
     */
    private fun injectToolbarButtons(view: WebView) {
        view.evaluateJavascript(
            """
            (function () {
              try {
                if (document.getElementById('android-tools')) return 'exists';
                var strip = document.querySelector('.control-strip');
                if (!strip) return 'no-strip';

                var wrap = document.createElement('span');
                wrap.id = 'android-tools';
                wrap.style.display = 'inline-flex';
                wrap.style.alignItems = 'center';
                wrap.style.gap = '4px';
                wrap.style.marginRight = '4px';

                function make(id, label, title, handler) {
                  var b = document.createElement('button');
                  b.id = id;
                  b.type = 'button';
                  b.className = 'action-btn subtle';
                  b.title = title;
                  b.textContent = label;
                  b.addEventListener('click', function (e) {
                    e.preventDefault();
                    e.stopPropagation();
                    if (window.$JS_BRIDGE_NAME) handler();
                  });
                  return b;
                }

                wrap.appendChild(make('android-settings', '设置', '设置：模型服务配置', function () {
                  window.$JS_BRIDGE_NAME.openSettings();
                }));
                wrap.appendChild(make('android-agent', '考据', '考据助手', function () {
                  window.$JS_BRIDGE_NAME.openAgent();
                }));

                strip.insertBefore(wrap, strip.firstChild);
                return 'ok';
              } catch (err) {
                return 'err:' + err;
              }
            })();
            """.trimIndent(),
            null
        )
    }

    /** 暴露给网页的桥：只做两件事，且都在主线程回调 */
    private inner class WebAppBridge {

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { agentPanel.showSettings() }
        }

        @JavascriptInterface
        fun openAgent() {
            runOnUiThread { agentPanel.showAsk() }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webView.loadUrl(resolveStartUrl())
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
        private const val JS_BRIDGE_NAME = "JiangshanAndroid"
    }
}
