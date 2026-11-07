package com.jiangshan.atlas.mcp

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URI

/**
 * MCP 客户端：通过 [WebSocketClient] 发起 JSON-RPC 2.0 请求。
 *
 * 只实现 App 用到的少数方法：initialize / tools/list / tools/call / ping。
 * 所有回调都切回主线程。
 */
class McpClient(
    private val onStatus: (String) -> Unit,
) {

    private var client: WebSocketClient? = null
    private var nextId = 1
    private val pending = HashMap<Int, (JSONObject) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    /** 连接 ws://host:port/mcp，成功后自动完成 initialize 握手（异步） */
    fun connect(host: String, port: Int, onReady: (Boolean) -> Unit) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            onStatus("请填写服务器地址")
            onReady(false)
            return
        }
        if (client?.isConnected() == true) {
            onStatus("已连接 $trimmed:$port")
            onReady(true)
            return
        }

        val uri = URI("ws", null, trimmed, port, "/mcp", null, null)
        val ws = WebSocketClient(
            uri,
            onMessage = { dispatch(it) },
            onOpen = {
                onStatus("已连接 $trimmed:$port")
                request("initialize", JSONObject()) {
                    onStatus("初始化完成")
                    onReady(true)
                }
            },
            onClose = { reason ->
                client = null
                onStatus(reason ?: "连接已断开")
                onReady(false)
            },
        )
        client = ws
        onStatus("正在连接 $trimmed:$port …")
        ws.connect()
    }

    fun disconnect() {
        client?.let {
            it.sendClose()
            it.isConnected()
        }
        client = null
    }

    fun isConnected() = client?.isConnected() == true

    /** 拉取工具列表 */
    fun listTools(callback: (List<String>?, String?) -> Unit) {
        request("tools/list", JSONObject()) { result ->
            val names = ArrayList<String>()
            result.optJSONObject("tools")?.let { tools ->
                // Android 的 org.json 里 keys() 返回 Iterator<String>
                val keys = tools.keys()
                while (keys.hasNext()) names.add(keys.next())
            }
            callback(names, null)
        }
    }

    /**
     * 调用工具。成功返回 (true, 工具结果 JSON 文本)，失败返回 (false, 错误信息)。
     */
    fun callTool(name: String, args: JSONObject, callback: (Boolean, String) -> Unit) {
        val params = JSONObject().put("name", name).put("arguments", args)
        request("tools/call", params) { result ->
            if (result.optBoolean("isError", false)) {
                callback(false, firstText(result))
            } else {
                callback(true, firstText(result))
            }
        }
    }

    fun ping(callback: (Boolean) -> Unit) {
        request("ping", JSONObject()) { callback(true) }
    }

    private fun firstText(result: JSONObject): String {
        val arr = result.optJSONArray("content") ?: return ""
        val first = arr.optJSONObject(0) ?: return ""
        return first.optString("text", "")
    }

    private fun request(method: String, params: JSONObject, cb: (JSONObject) -> Unit): Boolean {
        val c = client
        if (c == null || !c.isConnected()) {
            onStatus("未连接")
            cb(JSONObject().put("__error", "未连接"))
            return false
        }
        val id = nextId++
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).put("params", params)
        synchronized(pending) { pending[id] = cb }
        c.sendText(msg.toString())
        return true
    }

    private fun dispatch(raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        if (!msg.has("id")) return
        val id = msg.optInt("id")
        val cb = synchronized(pending) { pending.remove(id) } ?: return
        handler.post {
            cb(msg.optJSONObject("result") ?: JSONObject().put("__error", "无 result"))
        }
    }
}
