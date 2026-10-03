package top.wkbin.taixu.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

class RoundCollapseTest {

    private fun makeUser(id: String, text: String = "query") =
        UserMessage(id = id, createdAt = 1000L, text = text)

    private fun makeAssistant(id: String, text: String) =
        AssistantText(id = id, createdAt = 1000L, text = text)

    private fun makeToolCall(id: String, tool: HarnessTool = HarnessTool.READ) =
        ToolCall(id = id, createdAt = 1000L, tool = tool, args = buildJsonObject {})

    private fun makeToolResult(toolCallId: String, durationMs: Long? = 1000L) =
        ToolResult(
            id = "res_$toolCallId",
            createdAt = 1000L,
            toolCallId = toolCallId,
            success = true,
            output = "ok",
            durationMs = durationMs,
        )

    private fun makeCapability(id: String) =
        CapabilityEvent(
            id = id,
            createdAt = 1000L,
            kind = CapabilityEvent.Kind.SKILL,
            name = "taixu-custom-iteration",
        )

    private fun messageIds(items: List<ChatRenderItem>): List<String> =
        items.filterIsInstance<ChatRenderItem.MessageItem>().map { it.message.id }

    private fun buttons(items: List<ChatRenderItem>): List<ChatRenderItem.CollapseButtonItem> =
        items.filterIsInstance<ChatRenderItem.CollapseButtonItem>()

    private fun segments(items: List<ChatRenderItem>): List<ChatRenderItem.CollapsedSegmentItem> =
        items.filterIsInstance<ChatRenderItem.CollapsedSegmentItem>()

    // ==================== 默认行为：关闭自动折叠（自然单行流） ====================

    @Test
    fun `empty messages returns empty list`() {
        val items = projectChatMessages(emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `round with 2 or fewer steps is never collapsed and shows no button`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Done"),
            makeUser("u2"), // creates a new round so u1 becomes historical
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages)
        // Check u1 round: should have u1, t1, t2, a1 (no CollapseButtonItem)
        val buttons = items.filterIsInstance<ChatRenderItem.CollapseButtonItem>()
        assertTrue("≤ 2 步的历史轮不应该有折叠按钮", buttons.isEmpty())

        assertEquals(listOf("u1", "t1", "t2", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `tool results are excluded from direct render items`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1", 1200L),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(messages)
        assertEquals(listOf("u1", "t1", "a1"), messageIds(items))
    }

    @Test
    fun `all assistant and user messages and tool calls are preserved naturally in order`() {
        val messages = listOf(
            makeAssistant("a_init", "Welcome"),
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Answer"),
            makeUser("u2"),
            makeAssistant("a2", "Next"),
        )
        val items = projectChatMessages(messages)
        assertEquals(listOf("a_init", "u1", "t1", "t2", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `disabled collapse keeps flat stream even when a historical round has many steps`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        // 显式传 false 与使用默认值必须完全等价
        val explicitOff = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = false)
        val implicitOff = projectChatMessages(messages)
        assertTrue("关闭折叠时不得产生任何折叠按钮", buttons(explicitOff).isEmpty())
        assertEquals(messageIds(implicitOff), messageIds(explicitOff))
        assertEquals(listOf("u1", "t1", "t2", "t3", "a1", "u2", "a2"), messageIds(explicitOff))
    }

    // ==================== 开启自动折叠 ====================

    @Test
    fun `enabled collapse folds historical round keeping the newest two steps`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1", 1200L),
            makeToolCall("t2"),
            makeToolResult("t2", 300L),
            makeToolCall("t3"),
            makeToolResult("t3", 500L),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val toolResults = mapOf(
            "t1" to makeToolResult("t1", 1200L),
            "t2" to makeToolResult("t2", 300L),
            "t3" to makeToolResult("t3", 500L),
        )
        val items = projectChatMessages(messages, toolResults, emptyMap(), collapseEnabled = true)

        val foldButtons = buttons(items)
        assertEquals(1, foldButtons.size)
        assertEquals("u1", foldButtons[0].roundKey)
        assertEquals("仅隐藏最旧的 1 步", 1, foldButtons[0].hiddenSteps)
        assertEquals(3, foldButtons[0].totalSteps)
        assertEquals("隐藏步骤耗时应累加对应 ToolResult 的 durationMs", 1200L, foldButtons[0].hiddenDurationMs)
        assertFalse(foldButtons[0].isExpanded)

        // 折叠段重做：隐藏消息合并进**单个**段渲染项，不再逐条产出
        val segment = segments(items).single()
        assertEquals("u1", segment.roundKey)
        assertFalse(segment.isExpanded)
        assertEquals("段内隐藏集合 = 收拢时消失的那些消息", listOf("t1"), segment.hiddenMessages.map { it.id })

        // 用户气泡 + 折叠条 + 折叠段 + 最新 2 步 + 最终正文；下一轮不受影响
        assertEquals(listOf("u1", "t2", "t3", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `enabled collapse keeps never collapsing a round with two or fewer steps`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertTrue(buttons(items).isEmpty())
        assertTrue("≤ 2 步的轮次也不应产出折叠段", segments(items).isEmpty())
        assertEquals(listOf("u1", "t1", "t2", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `last round is never collapsed even when it has many steps`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeToolCall("t4"),
            makeToolResult("t4"),
            makeToolCall("t5"),
            makeToolResult("t5"),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertTrue("进行中的最后一轮必须始终摊开", buttons(items).isEmpty())
        assertTrue("进行中的最后一轮不应产出折叠段", segments(items).isEmpty())
        assertEquals(listOf("u1", "t1", "t2", "t3", "t4", "t5", "a1"), messageIds(items))
    }

    @Test
    fun `manual expand override reveals every step and shows expanded button`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(
            messages,
            emptyMap(),
            mapOf("u1" to true),
            collapseEnabled = true,
        )
        val foldButtons = buttons(items)
        assertEquals(1, foldButtons.size)
        assertTrue(foldButtons[0].isExpanded)
        assertEquals("摊开态下折叠条数值仍为 N = M-2（文案显示为「收起」）", 1, foldButtons[0].hiddenSteps)
        assertEquals("隐藏条数需在收拢/摊开两态一致，展开时据此启动分帧揭示", 1, foldButtons[0].hiddenItemCount)

        // 隐藏消息只存在于段内（段内自行控制逐帧放出量），不再作为独立条目产出
        val segment = segments(items).single()
        assertTrue(segment.isExpanded)
        assertEquals(listOf("t1"), segment.hiddenMessages.map { it.id })
        assertEquals(listOf("u1", "t2", "t3", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `manual collapse override wins over the last-round rule`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(
            messages,
            emptyMap(),
            mapOf("u1" to false),
            collapseEnabled = true,
        )
        val foldButtons = buttons(items)
        assertEquals(1, foldButtons.size)
        assertFalse("用户显式收拢的意图优先于末轮规则", foldButtons[0].isExpanded)
        assertFalse(segments(items).single().isExpanded)
        assertEquals(listOf("u1", "t2", "t3", "a1"), messageIds(items))
    }

    @Test
    fun `middle assistant text follows its preceding hidden tool call`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeAssistant("a_mid", "Let me keep digging"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a_final", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        // t1 被隐藏 → 紧随其后的中间正文 a_mid 一并隐藏；最终正文永远可见
        assertEquals(listOf("u1", "t2", "t3", "a_final", "u2", "a2"), messageIds(items))
        assertEquals(
            "中间正文与其前置隐藏工具卡一起进段（顺序保持原始顺序）",
            listOf("t1", "a_mid"),
            segments(items).single().hiddenMessages.map { it.id },
        )
    }

    @Test
    fun `leading assistant text before any tool call always stays visible`() {
        val messages = listOf(
            makeUser("u1"),
            makeAssistant("a_intro", "I will inspect the repo"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a_final", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertEquals(
            listOf("u1", "a_intro", "t2", "t3", "a_final", "u2", "a2"),
            messageIds(items),
        )
    }

    @Test
    fun `capability event stays visible inside a collapsed round`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeCapability("cap1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a_final", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertEquals(
            listOf("u1", "cap1", "t2", "t3", "a_final", "u2", "a2"),
            messageIds(items),
        )
    }

    @Test
    fun `rawIndex always points into the original message list in both modes`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )

        fun rawIndexById(items: List<ChatRenderItem>, id: String): Int =
            items.filterIsInstance<ChatRenderItem.MessageItem>()
                .first { it.message.id == id }
                .rawIndex

        val flat = projectChatMessages(messages)
        assertEquals(3, rawIndexById(flat, "t2"))
        assertEquals(7, rawIndexById(flat, "a1"))

        val folded = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertEquals("折叠态下 rawIndex 语义必须与单行流一致", 3, rawIndexById(folded, "t2"))
        assertEquals(7, rawIndexById(folded, "a1"))
    }

    // ==================== 折叠段：隐藏集合 / 条目数不变性 / 分帧揭示契约 ====================

    /** 3 步轮次里，被折叠时会消失的渲染条目 = 最旧 1 个工具卡 + 跟随它的中间正文 = 2 条。 */
    private fun revealFixtures(): List<HarnessMessage> = listOf(
        makeUser("u1"),
        makeToolCall("t1"),
        makeToolResult("t1"),
        makeAssistant("a_mid", "Let me keep digging"),
        makeToolCall("t2"),
        makeToolResult("t2"),
        makeToolCall("t3"),
        makeToolResult("t3"),
        makeAssistant("a_final", "Done"),
        makeUser("u2"),
        makeAssistant("a2", "Hello"),
    )

    @Test
    fun `expanded segment keeps exactly the same hidden messages as collapsed one`() {
        val collapsed = projectChatMessages(revealFixtures(), emptyMap(), emptyMap(), collapseEnabled = true)
        val expanded = projectChatMessages(revealFixtures(), emptyMap(), mapOf("u1" to true), collapseEnabled = true)

        val collapsedSegment = segments(collapsed).single()
        val expandedSegment = segments(expanded).single()
        assertEquals(listOf("t1", "a_mid"), collapsedSegment.hiddenMessages.map { it.id })
        assertEquals(
            "两态的 hiddenMessages 必须完全一致（只有 isExpanded 不同）",
            collapsedSegment.hiddenMessages.map { it.id },
            expandedSegment.hiddenMessages.map { it.id },
        )
        assertFalse(collapsedSegment.isExpanded)
        assertTrue(expandedSegment.isExpanded)
    }

    @Test
    fun `hidden messages are never emitted as standalone render items`() {
        val collapsed = projectChatMessages(revealFixtures(), emptyMap(), emptyMap(), collapseEnabled = true)
        val expanded = projectChatMessages(revealFixtures(), emptyMap(), mapOf("u1" to true), collapseEnabled = true)
        listOf(collapsed, expanded).forEach { items ->
            val ids = messageIds(items)
            assertTrue("隐藏消息不得作为独立渲染项产出（否则会重新引入插入/删除重排）", ids.none { it == "t1" || it == "a_mid" })
        }
    }

    @Test
    fun `item count and keys stay identical between collapsed and expanded states`() {
        val collapsed = projectChatMessages(revealFixtures(), emptyMap(), emptyMap(), collapseEnabled = true)
        val expanded = projectChatMessages(revealFixtures(), emptyMap(), mapOf("u1" to true), collapseEnabled = true)

        assertEquals(
            "同一轮收拢/摊开两态条目数差值必须为 0（折叠段重做的核心保证）",
            0,
            collapsed.size - expanded.size,
        )
        assertEquals(
            "条目 key 序列一致 → LazyColumn 不会把展开/收起当成插入/删除",
            collapsed.map { it.stableKey },
            expanded.map { it.stableKey },
        )
    }

    @Test
    fun `segment carries raw indexes so the segment can render without rescanning messages`() {
        val expanded = projectChatMessages(revealFixtures(), emptyMap(), mapOf("u1" to true), collapseEnabled = true)
        val hiddenItems = segments(expanded).single().hiddenItems
        assertEquals(listOf("t1", "a_mid"), hiddenItems.map { it.message.id })
        // revealFixtures 中 t1 = index 1、a_mid = index 3
        assertEquals(listOf(1, 3), hiddenItems.map { it.rawIndex })
    }

    @Test
    fun `reveal layer reports no limit while idle and one item per frame after start`() {
        val state = RoundRevealState()
        assertEquals("未启动揭示时限流应为 Int.MAX_VALUE（段内一次放完）", Int.MAX_VALUE, state.limitFor("u1"))

        state.start("u1", 4)
        assertEquals("首帧放出 1 条", 1, state.limitFor("u1"))
        assertEquals("其它轮次不受影响", Int.MAX_VALUE, state.limitFor("u2"))

        state.stop()
        assertEquals("停止后回到 Int.MAX_VALUE", Int.MAX_VALUE, state.limitFor("u1"))
    }

    @Test
    fun `toggling with a single hidden item never starts frame-by-frame reveal`() {
        val state = RoundRevealState()
        state.onToggled(roundKey = "u1", hiddenItemCount = 1, wasExpanded = false, enabled = true)
        assertEquals("隐藏段 ≤ 1 条时立即放完，不启动揭示", Int.MAX_VALUE, state.limitFor("u1"))

        state.onToggled(roundKey = "u1", hiddenItemCount = 2, wasExpanded = false, enabled = true)
        assertEquals("隐藏段 > 1 条时启动揭示", 1, state.limitFor("u1"))

        state.onToggled(roundKey = "u1", hiddenItemCount = 2, wasExpanded = true, enabled = true)
        assertEquals("收起时立即复位，不做延迟", Int.MAX_VALUE, state.limitFor("u1"))
    }

    @Test
    fun `collapsed round reports the hidden item count so expanding can start the reveal`() {
        val items = projectChatMessages(revealFixtures(), emptyMap(), emptyMap(), collapseEnabled = true)
        val button = buttons(items).single()
        assertFalse(button.isExpanded)
        assertEquals(
            "收拢态必须上报隐藏条数，否则点击展开时无从启动分帧揭示（旧版恒为 0）",
            2,
            button.hiddenItemCount,
        )
    }
}
