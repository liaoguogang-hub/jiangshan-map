package com.jiangshan.atlas.agent

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 桥接 WebView 里舆图自带的考据能力。
 *
 * 站点在 `DOMContentLoaded` 时挂了 `window.atlasAPI`：
 *   locate / resolvePlace / inspect / openPerson / search
 * 所以本地不需要重写任何地名匹配或政区沿革逻辑——
 * 直接让 JS 跑，把结构化结果拿回来。
 *
 * 所有对 WebView 的调用都在主线程；回调也在主线程。
 */
class AtlasBridge(private val webView: WebView) {

    private val main = Handler(Looper.getMainLooper())

    /** 等舆图脚本就绪（DOMContentLoaded 之后才有 atlasAPI） */
    fun awaitReady(timeoutMs: Long = 15_000, callback: (Boolean) -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        poll(deadline, callback)
    }

    private fun poll(deadline: Long, callback: (Boolean) -> Unit) {
        main.post {
            webView.evaluateJavascript("typeof window.atlasAPI !== 'undefined'") { raw ->
                val ready = raw?.trim() == "true"
                when {
                    ready -> callback(true)
                    System.currentTimeMillis() >= deadline -> callback(false)
                    else -> main.postDelayed({ poll(deadline, callback) }, 250)
                }
            }
        }
    }

    /** 查某年某地的政权、政区、今属、坐标与两千年沿革 */
    fun inspect(year: String, place: String, callback: (JSONObject?) -> Unit) {
        val args = JSONObject().put("year", year).put("place", place)
        evalObject("window.atlasAPI.inspect($args)", callback)
    }

    /** 查人物生平行迹 */
    fun inspectPerson(person: String, stop: Int?, callback: (JSONObject?) -> Unit) {
        val args = JSONObject().put("person", person)
        if (stop != null) args.put("stop", stop)
        evalObject("window.atlasAPI.inspect($args)", callback)
    }

    /** 在古城 / 名胜 / 名关 / 人物 / 年号 / 编年事件里检索 */
    fun search(query: String, limit: Int = 8, callback: (JSONArray?) -> Unit) {
        val arg = JSONObject.quote(query)
        main.post {
            webView.evaluateJavascript(
                "(function(){try{var r=window.atlasAPI.search($arg);return JSON.stringify(r.slice(0,$limit));}catch(e){return JSON.stringify({__error:String(e)});}})()"
            ) { raw ->
                val text = unquote(raw)
                callback(try { JSONArray(text) } catch (e: Exception) { null })
            }
        }
    }

    /** 在页面里打开人物行迹（会真的导航地图） */
    fun openPerson(person: String, index: Int = -1) {
        val arg = JSONObject.quote(person)
        main.post {
            webView.evaluateJavascript(
                "(function(){try{window.atlasAPI.openPerson($arg,$index);return '1';}catch(e){return '0';}})()",
                null
            )
        }
    }

    private fun evalObject(expr: String, callback: (JSONObject?) -> Unit) {
        main.post {
            webView.evaluateJavascript(
                "(function(){try{return JSON.stringify($expr);}catch(e){return JSON.stringify({__error:String(e)});}})()"
            ) { raw ->
                val text = unquote(raw)
                val obj = try { JSONObject(text) } catch (e: Exception) { null }
                callback(obj?.takeIf { !it.has("__error") })
            }
        }
    }

    /** evaluateJavascript 回传的是 JSON 编码后的值，字符串要先解一层 */
    private fun unquote(raw: String?): String {
        if (raw == null) return ""
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return ""
        return try {
            (JSONTokener(trimmed).nextValue() as? String) ?: trimmed
        } catch (e: Exception) {
            trimmed
        }
    }
}
