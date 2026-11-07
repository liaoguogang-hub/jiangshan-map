package com.jiangshan.atlas.agent

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.jiangshan.atlas.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 考据助手浮层。
 *
 * 之所以做成 MainActivity 里的浮层（而不是独立 Activity）：
 * Agent 需要调用 WebView 里的 `window.atlasAPI`，同进程同一份 WebView 最省事，
 * 也不用把 60MB 的站点资源再加载一遍。
 */
class AgentPanel(
    private val activity: Activity,
    private val parent: ViewGroup,
    bridge: AtlasBridge,
    private val onOpenMap: (String) -> Unit,
) {

    val view: View = activity.layoutInflater.inflate(R.layout.panel_agent, parent, false)

    private val engine = AgentEngine(bridge)
    private val store = LlmConfigStore(activity)

    private val providerRow: LinearLayout = view.findViewById(R.id.providerRow)
    private val modelEdit: EditText = view.findViewById(R.id.modelEdit)
    private val baseUrlEdit: EditText = view.findViewById(R.id.baseUrlEdit)
    private val apiKeyEdit: EditText = view.findViewById(R.id.apiKeyEdit)
    private val configHint: TextView = view.findViewById(R.id.configHint)
    private val questionEdit: EditText = view.findViewById(R.id.questionEdit)
    private val askBtn: Button = view.findViewById(R.id.askBtn)
    private val progress: ProgressBar = view.findViewById(R.id.progress)
    private val stageText: TextView = view.findViewById(R.id.stageText)
    private val answerText: TextView = view.findViewById(R.id.answerText)
    private val metaText: TextView = view.findViewById(R.id.metaText)
    private val openMapBtn: Button = view.findViewById(R.id.openMapBtn)

    private var currentProvider = store.activeProvider
    private var deepLinkQuery: String? = null

    init {
        view.visibility = View.GONE
        // 需要高于 MainActivity 里浮动按钮的 elevation(12dp)，否则会被压在下面并截走触摸
        view.elevation = 24f
        parent.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        view.findViewById<TextView>(R.id.closeBtn).setOnClickListener { hide() }
        // 点击卡片外的暗色区域关闭；卡片自身可点击以吞掉事件
        view.setOnClickListener { hide() }
        view.findViewById<View>(R.id.agentCard).setOnClickListener { /* 吞掉点击 */ }

        buildProviderChips()
        loadProvider(currentProvider)

        val examples = mapOf(
            R.id.ex1 to "雪拥蓝关马不前是哪一年，韩愈当时被贬在哪里",
            R.id.ex2 to "李白和杜甫在哪里相遇",
            R.id.ex3 to "赤壁在今天的哪里",
        )
        examples.forEach { (id, text) ->
            view.findViewById<Button>(id).setOnClickListener { questionEdit.setText(text) }
        }

        view.findViewById<Button>(R.id.saveBtn).setOnClickListener {
            val cfg = readForm()
            if (cfg.apiKey.isBlank()) {
                toast("先填 API Key")
                return@setOnClickListener
            }
            store.save(cfg)
            updateHint(cfg)
            toast("已保存")
            hideKeyboard()
        }

        askBtn.setOnClickListener { ask() }

        openMapBtn.setOnClickListener {
            val q = deepLinkQuery
            if (q.isNullOrEmpty()) {
                toast("这次没有可跳转的地点")
                return@setOnClickListener
            }
            hide()
            onOpenMap(q)
        }
    }

    fun show() {
        view.visibility = View.VISIBLE
        view.bringToFront()
    }

    fun hide() {
        hideKeyboard()
        view.visibility = View.GONE
    }

    fun isShowing() = view.visibility == View.VISIBLE

    // ---------------------------------------------------------------- 厂商切换

    private fun buildProviderChips() {
        providerRow.removeAllViews()
        LlmConfig.presetIds().forEach { id ->
            val chip = TextView(activity).apply {
                text = LlmConfig.displayName(id)
                textSize = 12f
                setPadding(26, 14, 26, 14)
                setOnClickListener {
                    saveSilently()
                    currentProvider = id
                    loadProvider(id)
                    refreshChips()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16 }
            providerRow.addView(chip, lp)
        }
        refreshChips()
    }

    private fun refreshChips() {
        for (i in 0 until providerRow.childCount) {
            val chip = providerRow.getChildAt(i) as TextView
            val id = LlmConfig.presetIds()[i]
            val selected = id == currentProvider
            chip.background = GradientDrawable().apply {
                cornerRadius = 30f
                setColor(if (selected) 0xFF2B2622.toInt() else 0x00FFFFFF)
                if (!selected) setStroke(1, 0xFFC0B4A2.toInt())
            }
            chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF5C5346.toInt())
        }
    }

    private fun loadProvider(id: String) {
        val cfg = store.load(id)
        baseUrlEdit.setText(cfg.baseUrl)
        apiKeyEdit.setText(cfg.apiKey)
        modelEdit.setText(cfg.model)
        updateHint(cfg)
        refreshChips()
    }

    private fun readForm(): LlmConfig = LlmConfig(
        providerId = currentProvider,
        baseUrl = baseUrlEdit.text.toString().trim(),
        apiKey = apiKeyEdit.text.toString().trim(),
        model = modelEdit.text.toString().trim(),
    )

    private fun saveSilently() {
        val cfg = readForm()
        if (cfg.apiKey.isNotBlank()) store.save(cfg)
    }

    private fun updateHint(cfg: LlmConfig) {
        configHint.text = if (cfg.apiKey.isBlank()) {
            "还没填 API Key —— 在 ${LlmConfig.displayName(cfg.providerId)} 开放平台申请后粘贴到上面"
        } else {
            "当前模型：${cfg.model} · ${cfg.endpoint()}"
        }
    }

    // ---------------------------------------------------------------- 提问

    private fun ask() {
        val question = questionEdit.text.toString().trim()
        if (question.isEmpty()) {
            toast("先输入问题")
            return
        }
        val cfg = readForm().let { if (it.apiKey.isBlank()) store.load(currentProvider) else it }
        if (!cfg.isUsable()) {
            toast("请先选择厂商并填入 API Key")
            return
        }
        store.save(cfg)

        hideKeyboard()
        askBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        answerText.visibility = View.GONE
        metaText.visibility = View.GONE
        openMapBtn.visibility = View.GONE
        deepLinkQuery = null

        engine.ask(question, cfg, onStage = { stage ->
            stageText.text = stage
        }, onResult = { result ->
            progress.visibility = View.GONE
            askBtn.isEnabled = true
            stageText.text = ""
            render(result)
        })

        stageText.text = "正在请求模型 …"
    }

    private fun render(result: AgentResult) {
        val sb = StringBuilder()

        if (result.error != null) {
            sb.append("⚠ ").append(result.error)
            if (result.answer.isNotBlank()) sb.append("\n\n").append(result.answer)
            answerText.text = sb.toString()
            answerText.visibility = View.VISIBLE
            metaText.visibility = View.GONE
            openMapBtn.visibility = View.GONE
            return
        }

        answerText.text = result.answer.ifBlank { "（模型未给出解答）" }
        answerText.visibility = View.VISIBLE

        val lines = mutableListOf<String>()

        val atlas = result.atlas
        if (atlas != null) {
            val era = atlas.optString("era", "")
            if (era.isNotEmpty()) lines.add("● $era")

            val polity = atlas.optString("polity", "")
            if (polity.isNotEmpty()) lines.add("政权：$polity")

            val regions = atlas.optJSONArray("regions")
            if (regions != null && regions.length() > 0) {
                lines.add("政区：" + (0 until regions.length()).joinToString("、") { regions.getString(it) })
            }

            val province = atlas.optString("modernProvince", "")
            val modern = atlas.optString("modern", "")
            if (province.isNotEmpty()) {
                lines.add("今属：$province${if (modern.isNotEmpty()) " · $modern" else ""}")
            }

            val tag = atlas.optJSONObject("lore")?.optString("tag", "").orEmpty()
            if (tag.isNotEmpty()) lines.add("形胜：$tag")

            val coord = atlas.optJSONArray("coord")
            if (coord != null && coord.length() == 2) {
                lines.add("坐标：${coord.getDouble(0)}, ${coord.getDouble(1)}")
            }

            if (!result.deepLinkQuery.isNullOrEmpty()) {
                lines.add("（已生成地图深链，点下方按钮打开）")
            }
        }

        val hits: JSONArray? = result.hits
        if (hits != null && hits.length() > 0) {
            val first = hits.optJSONObject(0)
            if (first != null) {
                lines.add("● 检索到 ${hits.length()} 条，首条：${first.optString("titleZh", "")}")
                val c = first.optJSONArray("coord")
                if (c != null && c.length() == 2) {
                    lines.add("坐标：${c.getDouble(0)}, ${c.getDouble(1)}")
                }
            }
        }

        if (result.place.isNotBlank()) lines.add("地点：${result.place}")
        if (result.person.isNotBlank()) lines.add("人物：${result.person}")
        if (result.year.isNotBlank()) lines.add("年份：${result.year}")
        if (result.note.isNotBlank()) lines.add("提示：${result.note}")

        metaText.text = lines.joinToString("\n")
        metaText.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE

        deepLinkQuery = result.deepLinkQuery
        openMapBtn.visibility = if (result.deepLinkQuery.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- 工具

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }
}
