package com.jiangshan.atlas.agent

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** 一次考据的结果 */
data class AgentResult(
    val answer: String = "",
    val year: String = "",
    val place: String = "",
    val person: String = "",
    /** 舆图 inspect 返回的结构化数据 */
    val atlas: JSONObject? = null,
    /** 检索命中列表（search_atlas 分支） */
    val hits: JSONArray? = null,
    /** 交给 WebView 加载的 query，例如 year=819&place=蓝田关,潮州 */
    val deepLinkQuery: String? = null,
    val note: String = "",
    val error: String? = null,
)

/**
 * 手机内的考据 Agent。
 *
 * 分工和 PC 版一致，只是把「调模型」搬到手机上：
 *   1. 模型负责听懂问题 → 输出 {answer, tool, year, place, person}
 *   2. 舆图（WebView 里的 atlasAPI）负责查真实史料 → 年号/政权/政区/坐标/沿革
 *
 * 模型碰不到坐标和政区字段，想编也编不出来。
 */
class AgentEngine(private val bridge: AtlasBridge) {

    private val main = Handler(Looper.getMainLooper())

    fun ask(
        question: String,
        config: LlmConfig,
        onStage: (String) -> Unit,
        onResult: (AgentResult) -> Unit,
    ) {
        Thread({
            onStage("正在请求 ${LlmConfig.displayName(config.providerId)} …")

            val llm = LlmClient.chat(config, SYSTEM_PROMPT, buildPrompt(question))
            if (!llm.ok) {
                main.post { onResult(AgentResult(error = llm.text)) }
                return@Thread
            }

            val parsed = LlmClient.extractJson(llm.text)
            if (parsed == null) {
                main.post {
                    onResult(
                        AgentResult(
                            answer = llm.text,
                            error = "模型没有返回预期结构，已把原文显示在下方",
                        )
                    )
                }
                return@Thread
            }

            val answer = parsed.optString("answer", "")
            val tool = parsed.optString("tool", "none")
            val year = parsed.optString("year", "")
            val place = parsed.optString("place", "")
            val person = parsed.optString("person", "")
            val query = parsed.optString("query", "")

            // 不需要查舆图
            if (tool == "none" || (tool == "inspect_place" && place.isBlank()) ||
                (tool == "inspect_person" && person.isBlank()) ||
                (tool == "search_atlas" && query.isBlank())
            ) {
                main.post { onResult(AgentResult(answer = answer, year = year, place = place, person = person)) }
                return@Thread
            }

            main.post { onStage("正在查舆图史料 …") }

            when (tool) {
                "inspect_person" -> bridge.inspectPerson(person, null) { atlas ->
                    main.post {
                        onResult(
                            AgentResult(
                                answer = answer,
                                year = year,
                                place = place,
                                person = person,
                                atlas = atlas,
                                deepLinkQuery = queryOf(atlas),
                            )
                        )
                    }
                }

                "search_atlas" -> bridge.search(query) { hits ->
                    main.post {
                        onResult(
                            AgentResult(
                                answer = answer,
                                year = year,
                                place = place,
                                person = person,
                                hits = hits,
                            )
                        )
                    }
                }

                else -> bridge.inspect(year.ifBlank { "0" }, place) { atlas ->
                    main.post {
                        onResult(
                            AgentResult(
                                answer = answer,
                                year = year,
                                place = place,
                                person = person,
                                atlas = atlas,
                                deepLinkQuery = queryOf(atlas),
                                note = if (atlas == null) "舆图里没查到「$place」，换个标准地名试试" else "",
                            )
                        )
                    }
                }
            }
        }, "agent-ask").start()
    }

    /** 从 atlas.url 里取出 query 部分，供 WebView 直接加载 */
    private fun queryOf(atlas: JSONObject?): String? {
        val url = atlas?.optString("url", "").orEmpty()
        val i = url.indexOf('?')
        return if (i < 0 || i == url.length - 1) null else url.substring(i + 1)
    }

    companion object {

        private const val SYSTEM_PROMPT =
            "你是历史地理考据助手。用户会问一个关于中国历史、古典诗词、战役、历史人物或古地名的问题。" +
                "你的任务是把提问拆解成江山时序舆图工具可以执行的参数，并给出中文解答。" +
                "严格只输出 JSON，不要任何额外文字、不要 markdown 代码块。"

        private fun buildPrompt(question: String) = """
            江山时序舆图提供三个查询工具：
            1) inspect_place(year, place) —— 查某年某地的政权、道/路/行省、郡州府、今省、两千年沿革与坐标
            2) inspect_person(person) —— 查历史人物的生平行迹与坐标
            3) search_atlas(query, limit) —— 在 1753 座古城、185 处诗词名胜/古战场、20 处名关、125+ 位历史人物、351 个年号、473 条编年大事中检索

            请分析用户的提问，输出 JSON：
            {"answer": "对用户提问的中文解答（含考据过程）",
             "tool": "inspect_place" | "inspect_person" | "search_atlas" | "none",
             "year": 整数或逗号分隔的整数串（公元前用负数，公元前 403 年写作 -403；题干只说朝代没有具体年份时取该朝代中间年份），
             "place": "地点名（中文/英文/拼音皆可，多处用逗号分隔）",
             "person": "人物名（多个用逗号分隔，最多 4 个）",
             "query": "检索关键词"}

            用户提问：$question
        """.trimIndent()
    }
}
