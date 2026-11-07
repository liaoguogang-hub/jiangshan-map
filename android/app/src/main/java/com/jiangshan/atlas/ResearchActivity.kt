package com.jiangshan.atlas

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jiangshan.atlas.mcp.McpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 考据助手：让 AI 把自然语言问题考据成「年份 + 地点 / 人物」，再跳进地图。
 *
 * 手机 App 作为 MCP 客户端，通过 WebSocket 连到 PC 上的
 * `scripts/mcp_ws_server.js`；自然语言由服务端的 LLM 拆解，
 * App 只负责展示与跳转。
 *
 * 点「在地图中打开」时，把 deep link 的 query 回传给 [MainActivity] 再加载。
 */
class ResearchActivity : AppCompatActivity() {

    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var questionEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var answerText: TextView
    private lateinit var metaText: TextView
    private lateinit var connectBtn: Button
    private lateinit var askBtn: Button
    private lateinit var openMapBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var divider: View

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS, 0)
    }
    private val client by lazy { McpClient(onStatus = { setStatus(it) }) }
    private var currentQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_research)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "考据助手"

        hostEdit = findViewById(R.id.hostEdit)
        portEdit = findViewById(R.id.portEdit)
        questionEdit = findViewById(R.id.questionEdit)
        statusText = findViewById(R.id.statusText)
        answerText = findViewById(R.id.answerText)
        metaText = findViewById(R.id.metaText)
        connectBtn = findViewById(R.id.connectBtn)
        askBtn = findViewById(R.id.askBtn)
        openMapBtn = findViewById(R.id.openMapBtn)
        progress = findViewById(R.id.progress)
        divider = findViewById(R.id.resultDivider)

        hostEdit.setText(prefs.getString(KEY_HOST, "127.0.0.1"))
        portEdit.setText(prefs.getString(KEY_PORT, "8798"))
        portEdit.inputType = InputType.TYPE_CLASS_NUMBER

        hostEdit.addTextChangedListener(prefsSaver(KEY_HOST))
        portEdit.addTextChangedListener(prefsSaver(KEY_PORT))

        // 示例提问，点一下填入
        val examples = mapOf(
            R.id.ex1 to "雪拥蓝关马不前是哪一年，韩愈当时被贬在哪里",
            R.id.ex2 to "李白和杜甫在哪里相遇",
            R.id.ex3 to "赤壁在今天的哪里",
        )
        examples.forEach { (id, text) ->
            findViewById<Button>(id).setOnClickListener { questionEdit.setText(text) }
        }

        connectBtn.setOnClickListener {
            if (client.isConnected()) {
                client.disconnect()
                connectBtn.text = "连接"
                return@setOnClickListener
            }
            val port = portEdit.text.toString().trim().toIntOrNull() ?: 8798
            connectBtn.text = "断开"
            client.connect(hostEdit.text.toString().trim(), port) { ok ->
                connectBtn.text = if (ok) "断开" else "连接"
            }
        }

        askBtn.setOnClickListener {
            val question = questionEdit.text.toString().trim()
            if (question.isEmpty()) {
                setStatus("请先输入问题")
                return@setOnClickListener
            }
            if (!client.isConnected()) {
                setStatus("请先连接服务器")
                return@setOnClickListener
            }
            askBtn.isEnabled = false
            progress.visibility = View.VISIBLE
            answerText.visibility = View.GONE
            metaText.visibility = View.GONE
            divider.visibility = View.GONE
            openMapBtn.visibility = View.GONE
            currentQuery = null

            val args = JSONObject().put("question", question).put("lang", "zh")
            client.callTool("ask_question", args) { ok, text ->
                progress.visibility = View.GONE
                askBtn.isEnabled = true
                if (!ok) {
                    answerText.text = text.ifEmpty { "（服务端返回错误）" }
                    answerText.visibility = View.VISIBLE
                    divider.visibility = View.VISIBLE
                    return@callTool
                }
                renderResult(text)
            }
        }

        openMapBtn.setOnClickListener {
            val q = currentQuery
            if (q.isNullOrEmpty()) {
                setStatus("没有可跳转的地点")
                return@setOnClickListener
            }
            val result = Intent()
            result.putExtra(EXTRA_QUERY, q)
            setResult(RESULT_OK, result)
            finish()
        }
    }

    /** 解析 ask_question 的返回 */
    private fun renderResult(text: String) {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            answerText.text = text.ifEmpty { "解析失败：$e" }
            answerText.visibility = View.VISIBLE
            divider.visibility = View.VISIBLE
            return
        }

        val answer = root.optString("answer", "")
        answerText.text = if (answer.isNotEmpty()) answer else text
        answerText.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE

        val lines = mutableListOf<String>()

        val atlas = root.optJSONObject("atlas")
        if (atlas != null) {
            val era = atlas.optString("era", "")
            if (era.isNotEmpty()) lines.add("● $era")

            val polity = atlas.optString("polity", "")
            val regions = atlas.optJSONArray("regions")
            val modern = atlas.optString("modern", "")
            val province = atlas.optString("modernProvince", "")
            val tag = atlas.optJSONObject("lore")?.optString("tag", "") ?: ""

            if (polity.isNotEmpty()) lines.add("政权：$polity")
            if (regions != null && regions.length() > 0) {
                lines.add("政区：" + (0 until regions.length()).joinToString("、") { regions.getString(it) })
            }
            if (province.isNotEmpty()) lines.add("今属：$province${if (modern.isNotEmpty()) " · $modern" else ""}")
            if (tag.isNotEmpty()) lines.add("形胜：$tag")

            val coord = atlas.optJSONArray("coord")
            if (coord != null && coord.length() == 2) {
                lines.add("坐标：${coord.getDouble(0)}, ${coord.getDouble(1)}")
            }

            val url = atlas.optString("url", "")
            if (url.isNotEmpty()) {
                val q = queryOf(url)
                if (q.isNotEmpty()) {
                    currentQuery = q
                    lines.add("（已生成地图深链，点下方按钮打开）")
                }
            }
        } else {
            val results: JSONArray = root.optJSONArray("results") ?: JSONArray()
            if (results.length() > 0) {
                val first = results.optJSONObject(0)
                if (first != null) {
                    val title = first.optString("titleZh", "")
                    lines.add("● 检索到 ${results.length()} 条，首条：$title")
                    if (first.opt("year") != JSONObject.NULL) lines.add("年份：${first.optString("year", "")}")
                    if (first.opt("dynasty") != JSONObject.NULL) {
                        lines.add("朝代：${dynastyLabel(first.optString("dynasty", ""))}")
                    }
                    val c = first.optJSONArray("coord")
                    if (c != null && c.length() == 2) {
                        lines.add("坐标：${c.getDouble(0)}, ${c.getDouble(1)}")
                    }
                    lines.add("（检索结果暂不提供跳转）")
                }
            }
        }

        val place = root.optString("place", "")
        val person = root.optString("person", "")
        val year = root.optString("year", "")
        if (place.isNotEmpty()) lines.add("地点：$place")
        if (person.isNotEmpty()) lines.add("人物：$person")
        if (year.isNotEmpty()) lines.add("年份：$year")

        if (lines.isEmpty()) lines.add("（没有解析出地点或人物）")

        metaText.text = lines.joinToString("\n")
        metaText.visibility = View.VISIBLE
        openMapBtn.visibility = if (currentQuery.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun dynastyLabel(key: String) = DYNASTY_NAMES[key] ?: key

    private fun queryOf(url: String): String {
        val i = url.indexOf('?')
        return if (i < 0) "" else url.substring(i + 1)
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun prefsSaver(key: String) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            prefs.edit().putString(key, s?.toString() ?: "").apply()
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "jiangshan_atlas_prefs"
        const val KEY_HOST = "mcp_host"
        const val KEY_PORT = "mcp_port"
        const val EXTRA_QUERY = "jiangshan_atlas_query"

        private val DYNASTY_NAMES = mapOf(
            "zhanguo" to "战国", "xihan" to "西汉", "donghan" to "东汉", "sanguo" to "三国",
            "dongjin" to "东晋", "nanbei" to "南北朝", "sui" to "隋", "tang" to "唐",
            "wudai" to "五代十国", "nansong" to "南宋", "beisong" to "北宋",
            "yuan" to "元", "ming" to "明", "qing" to "清", "roc" to "民国", "prc" to "现代",
        )
    }
}
