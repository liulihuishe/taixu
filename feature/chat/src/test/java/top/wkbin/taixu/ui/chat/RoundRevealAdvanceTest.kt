package top.wkbin.taixu.ui.chat

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 折叠段「段内分帧放出」的驱动测试。
 *
 * `RoundRevealState.advanceByFrame()` 依赖 `withFrameNanos`，因此用 compose-runtime 自带的
 * [BroadcastFrameClock] 手动送帧：[BroadcastFrameClock] 的 `onNewAwaiters` 回调保证我们只在
 * 「状态机已经挂在帧上」时送帧，避免丢帧导致的挂起。
 */
class RoundRevealAdvanceTest {

    private suspend fun awaitFrameWaiter(waiters: Channel<Unit>) {
        withTimeout(WAIT_TIMEOUT_MS) { waiters.receive() }
    }

    /** 轮询等待状态机把限流推进到期望值（推进发生在其它线程上）。 */
    private suspend fun awaitLimit(state: RoundRevealState, roundKey: String, expected: Int) {
        withTimeout(WAIT_TIMEOUT_MS) {
            while (state.limitFor(roundKey) != expected) delay(1)
        }
    }

    @Test
    fun `advanceByFrame releases one more hidden item per frame and stops when done`() = runBlocking {
        val waiters = Channel<Unit>(Channel.UNLIMITED)
        val clock = BroadcastFrameClock(onNewAwaiters = { waiters.trySend(Unit) })
        val state = RoundRevealState()
        state.start("u1", totalItems = 4)

        val job = launch(Dispatchers.Default + clock) { state.advanceByFrame() }

        // 首帧：1 -> 2
        awaitFrameWaiter(waiters)
        clock.sendFrame(1L)
        awaitLimit(state, "u1", 2)

        // 第二帧：2 -> 3
        awaitFrameWaiter(waiters)
        clock.sendFrame(2L)
        awaitLimit(state, "u1", 3)

        // 第三帧：3 -> 4（放完）
        awaitFrameWaiter(waiters)
        clock.sendFrame(3L)
        job.join()

        assertEquals(
            "放完后状态机必须自动复位，段内不再限流",
            Int.MAX_VALUE,
            state.limitFor("u1"),
        )
    }

    @Test
    fun `stopping in the middle leaves other rounds unlimited`() = runBlocking {
        val waiters = Channel<Unit>(Channel.UNLIMITED)
        val clock = BroadcastFrameClock(onNewAwaiters = { waiters.trySend(Unit) })
        val state = RoundRevealState()
        state.start("u1", totalItems = 3)

        val job = launch(Dispatchers.Default + clock) { state.advanceByFrame() }
        awaitFrameWaiter(waiters)
        clock.sendFrame(1L)
        awaitLimit(state, "u1", 2)

        // 收起：立即复位，且与正在推进的其它轮次互不干扰
        state.stop()
        assertEquals(Int.MAX_VALUE, state.limitFor("u1"))
        assertEquals(Int.MAX_VALUE, state.limitFor("u2"))

        job.cancel()
    }

    private companion object {
        /** 单测里手送帧，正常几毫秒内即可完成；留足超时只为避免极端 CI 抖动。 */
        const val WAIT_TIMEOUT_MS = 10_000L
    }
}
