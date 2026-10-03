package top.wkbin.taixu.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ModelSwitchEvent
import top.wkbin.taixu.harness.SkillSuggestion
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.checkpoint.RewindScope
import top.wkbin.taixu.harness.session.ConversationBranch

/**
 * 单条消息渲染 + 折叠段渲染。
 *
 * 从 `ChatMessageList.kt` 外移的原因：
 * 1) `ChatMessageList.kt` 已触及 `architecture-policy.json` 的尺寸棘轮（基线只许下调），
 *    单条消息渲染整段迁出后，渲染层才有空间容纳「折叠段」新分支；
 * 2) 折叠段内的消息与列表项复用**同一份**渲染代码，段内逐条放出时表现完全一致。
 *
 * 契约：迁出的渲染代码与原 `ChatMessageList.kt` 内 `when (val message = item.message)`
 * 分支**逐字一致**（回调语义、默认值、间距规则均未改动）。
 */
internal data class ChatMessageItemEnv(
    val messages: List<HarnessMessage>,
    val toolResults: Map<String, ToolResult>,
    val running: Boolean,
    val status: String?,
    val workspace: String,
    val thinkingExpanded: Boolean,
    val thinkingAutoTranslate: Boolean,
    val thinkingLive: Boolean,
    val liveThinkingMessageId: String?,
    val lastAssistantMessageId: String?,
    val onNavigateToSettings: (() -> Unit)?,
    val knownMentionNames: List<String>,
    val onEditMessage: (UserMessage) -> Unit,
    val onDeleteMessage: (String) -> Unit,
    val onCreateBranch: (String) -> Unit,
    val onRewindMessage: (String, RewindScope) -> Unit,
    val onRegenerate: () -> Unit,
    val onRetryTool: (String) -> Unit,
    val onOpenFile: ((String, String) -> Unit)?,
    val onViewSubagentLanes: () -> Unit,
    val subagentBranches: List<ConversationBranch>,
    val onOpenSubagent: (ConversationBranch) -> Unit,
    val hiddenSkillSuggestions: Set<String>,
    val onApplySkillSuggestion: (SkillSuggestion, Boolean) -> Unit,
    val onDismissSkillSuggestion: (String) -> Unit,
)

/**
 * 列表项垂直间距：自 `ChatMessageList.kt` 原样外移的同一套规则，
 * 列表项与折叠段内消息共用，保证两处间距一致。
 *
 * 折叠段占位项自身不带间距（收拢态它是 0 高度的占位、摊开态其内部首条自带间距），
 * 这样收拢/摊开两态的视觉与重做前逐条平铺时**逐像素一致**。
 */
internal fun chatItemTopSpacing(previous: ChatRenderItem?, item: ChatRenderItem): Dp = when {
    item is ChatRenderItem.CollapsedSegmentItem -> 0.dp
    item is ChatRenderItem.MessageItem && item.message is UserMessage -> 12.dp
    // 思考过程、工具调用卡片、能力事件之间的垂直外边距压至紧凑的 2.5dp
    isThinkingOrActionItem(item) && isThinkingOrActionItem(previous) -> 2.5.dp
    // 思考/工具刚结束紧接的最终回复气泡间距压缩为 4dp
    item is ChatRenderItem.MessageItem && item.message is AssistantText && isThinkingOrActionItem(previous) -> 4.dp
    else -> 8.dp
}

/** 单条消息渲染（自 ChatMessageList.kt 原样外移，`when (message)` 分支逐字未改）。 */
@Composable
internal fun ChatMessageItemContent(item: ChatRenderItem.MessageItem, env: ChatMessageItemEnv) {
    with(env) {
        when (val message = item.message) {
            is CapabilityEvent -> CapabilityEventCard(message)
            is SkillSuggestion -> if (message.id !in hiddenSkillSuggestions) {
                SkillSuggestionCard(
                    suggestion = message,
                    onCreate = { onApplySkillSuggestion(message, true) },
                    onUpdate = { onApplySkillSuggestion(message, false) },
                    onDismiss = { onDismissSkillSuggestion(message.id) },
                )
            }
            is ModelSwitchEvent -> ModelSwitchCard(message)
            is UserMessage -> UserBubble(
                message = message,
                knownMentionNames = knownMentionNames,
                onEdit = { onEditMessage(message) },
                onDelete = { onDeleteMessage(message.id) },
                onCreateBranch = { onCreateBranch(message.id) },
                onRewind = { scope -> onRewindMessage(message.id, scope) },
            )
            is AssistantText -> AssistantBubble(
                message = message,
                defaultExpanded = thinkingExpanded,
                live = thinkingLive && message.id == liveThinkingMessageId,
                showRegenerate = message.id == lastAssistantMessageId,
                onRegenerate = onRegenerate,
                onCreateBranch = { onCreateBranch(message.id) },
                autoTranslate = thinkingAutoTranslate,
                onNavigateToSettings = onNavigateToSettings,
            )
            is ToolCall -> {
                // 原始下标由投影阶段预计算（见 projectChatMessages），组合期 O(1)，
                // 不再在每个 Lazy 项里对 messages 做 indexOfFirst 的 O(n) 全表扫描。
                val rawIndex = item.rawIndex
                if (message.tool == HarnessTool.SUBAGENT) {
                    SubagentCard(
                        call = message,
                        result = toolResults[message.id],
                        subagentBranches = subagentBranches,
                        onOpenSubagent = onOpenSubagent,
                        onViewDetails = onViewSubagentLanes,
                    )
                } else {
                    ToolCard(
                        call = message,
                        result = toolResults[message.id],
                        workspace = workspace,
                        onOpenFile = onOpenFile,
                        running = running,
                        liveStatus = status,
                        showReasoning = message.reasoning != null &&
                            !reasoningAlreadyShown(messages, rawIndex, message.reasoning),
                        defaultExpanded = thinkingExpanded,
                        onRetry = { onRetryTool(message.id) },
                    )
                }
            }
            is ToolResult -> Unit
        }
    }
}

/**
 * 折叠段：**单个** LazyColumn item 承载整段被折叠的历史过程。
 *
 * 收拢/摊开两态下这一项的条目数完全相同，只有高度在动画，于是
 * 「展开/收起」不再插入或删除 N 个条目，也就没有整表重排；
 * 段内再按帧逐条放出（[revealLimit]，见 RoundRevealState），使单帧组合量有上界。
 */
@Composable
internal fun CollapsedSegmentItemContent(
    item: ChatRenderItem.CollapsedSegmentItem,
    env: ChatMessageItemEnv,
    revealLimit: Int,
) {
    AnimatedVisibility(
        visible = item.isExpanded,
        enter = expandVertically(animationSpec = tween(COLLAPSE_SEGMENT_ANIM_MS)) +
            fadeIn(animationSpec = tween(COLLAPSE_SEGMENT_FADE_MS)),
        exit = shrinkVertically(animationSpec = tween(COLLAPSE_SEGMENT_ANIM_MS)) +
            fadeOut(animationSpec = tween(COLLAPSE_SEGMENT_FADE_MS)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val shown = item.hiddenItems.take(revealLimit)
            shown.forEachIndexed { innerIndex, hiddenItem ->
                Spacer(Modifier.height(chatItemTopSpacing(shown.getOrNull(innerIndex - 1), hiddenItem)))
                ChatMessageItemContent(item = hiddenItem, env = env)
            }
        }
    }
}

/** 折叠段高度动画时长（毫秒）：与 ChatMessageBubbles 内展开动效同量级。 */
private const val COLLAPSE_SEGMENT_ANIM_MS = 180

/** 折叠段淡入淡出时长（毫秒）：比高度动画略短，避免「先透明再撑高」的割裂感。 */
private const val COLLAPSE_SEGMENT_FADE_MS = 120
