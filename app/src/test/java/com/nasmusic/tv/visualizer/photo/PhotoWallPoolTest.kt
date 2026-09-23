package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.backend.photo.PhotoIds
import com.nasmusic.tv.backend.photo.PhotoRef
import com.nasmusic.tv.backend.photo.PhotoSourceKind
import com.nasmusic.tv.visualizer.VisualizerRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照片游标门禁 **G14**（§6.7「Fisher-Yates + 游标」的无重复遍历那一条）
 *
 * ## 为什么单独钉这一块
 *
 * 「无重复遍历」是用户**唯一能直接感知**的照片墙行为之一：
 * 抽到同一张连播两次，用户的第一反应是「这功能是不是坏了」。
 * 而它写错之后**编译过、lint 过、单帧也看不出来** —— 要连续观察几十次切换才发现。
 *
 * ## 与 G2（`PhotoTransitionPicker`）的对称
 *
 * 两者都是「避免重复」，但避让的对象不同：
 *
 * | | 避让对象 | 做法 |
 * |---|---|---|
 * | G2 转场 | 最近 **2 次**用过的值 | 重试 N 次后放行 |
 * | G14 照片 | 上一轮**末张**（新一轮首张） | 洗牌后做一次交换 |
 *
 * ⛔ 照片不能照搬 G2 的「重试」：池是**一次性分配的顺序**（游标消费），
 * 重试会破坏「一轮内不重复」这个更强的保证。
 *
 * ## 负向自证（⛔ 门规：没有它就无法区分「真的干净」与「空转」）
 *
 * `poolBeginRoundAvoidsLast` 里同时跑了**正确实现**与「只洗牌不避让」的错法：
 * - 错法必须**能观察到**重复（否则这条自证是空转）；
 * - 正确实现必须**一次都不重复**。
 */
class PhotoWallPoolTest {

    // ── 一轮内不重复 ────────────────────────────────────────────────

    @Test
    fun `a full round yields every photo exactly once`() {
        val pool = PhotoWallPool()
        pool.reset(photos(20))

        val seen = LinkedHashSet<String>()
        repeat(20) {
            val ref = pool.peek()
            assertNotNull("第 $it 次取应当拿到一张", ref)
            seen.add(ref!!.id)
            pool.advance()
        }
        assertEquals("一轮内每张恰好出现一次", 20, seen.size)
        assertNull("走完一轮后游标到末尾", pool.peek())
    }

    @Test
    fun `advance past the end does not overshoot`() {
        val pool = PhotoWallPool()
        pool.reset(photos(2))
        pool.advance()
        pool.advance()
        pool.advance() // 越界调用（防御性）
        pool.advance()
        assertNull(pool.peek())
        pool.beginNewRound(VisualizerRandom(1u))
        assertNotNull("重新洗牌后又能取到", pool.peek())
    }

    // ── 新一轮 ──────────────────────────────────────────────────────

    @Test
    fun `beginNewRound restarts the cursor and keeps the pool`() {
        val pool = PhotoWallPool()
        pool.reset(photos(5))
        val first = pool.peek()!!.id
        repeat(5) { pool.advance() }
        assertNull(pool.peek())

        pool.beginNewRound(VisualizerRandom(9u))
        assertNotNull("新一轮开始后必须能立刻取到", pool.peek())
        assertEquals("池大小不变", 5, pool.size)

        val seen = LinkedHashSet<String>()
        repeat(5) { seen.add(pool.peek()!!.id); pool.advance() }
        assertEquals("新一轮同样无重复", 5, seen.size)
        // ⚠️ 不断言「新一轮首张 != 第一轮首张」—— 那不是保证（洗牌是独立的）
        assertTrue(seen.contains(first))
    }

    @Test
    fun `negative proof - without the avoid-last swap the first of a new round repeats the previous last`() {
        var wrongRepeats = 0
        var correctRepeats = 0

        for (seed in 1u..60u) {
            // 两元素池：洗牌后首张是 A 或 B 各约一半 ⇒ 不避让的话必然能观察到重复
            val correct = PhotoWallPool()
            correct.reset(listOf(ref("A"), ref("B")))
            correct.advance()
            correct.advance() // 末张 = B
            correct.beginNewRound(VisualizerRandom(seed))
            if (correct.peek()?.id == idOf("B")) correctRepeats++

            // 错法：只 Fisher-Yates 洗牌，**不**做「首张 == 上一轮末张则交换」
            val wrong = shuffledWithoutAvoid(listOf(ref("A"), ref("B")), VisualizerRandom(seed))
            if (wrong.first().id == idOf("B")) wrongRepeats++
        }

        assertTrue(
            "错法必须能观察到重复 —— 否则这条负向自证是空转（说明「首尾撞车」根本不会发生）",
            wrongRepeats > 0,
        )
        assertEquals(
            "正确实现绝不允许新一轮首张 == 上一轮末张（否则用户看到「这张刚看过」）",
            0,
            correctRepeats,
        )
    }

    // ── 边界 ────────────────────────────────────────────────────────

    @Test
    fun `empty pool yields nothing and never crashes`() {
        val pool = PhotoWallPool()
        assertTrue(pool.isEmpty)
        assertNull(pool.peek())
        pool.advance()
        pool.beginNewRound(VisualizerRandom(1u))
        assertNull("空池怎么折腾都取不到", pool.peek())
    }

    @Test
    fun `single photo pool keeps returning that photo`() {
        val pool = PhotoWallPool()
        pool.reset(listOf(ref("only")))
        repeat(6) {
            assertEquals(idOf("only"), pool.peek()?.id)
            pool.advance()
            pool.beginNewRound(VisualizerRandom(it.toUInt()))
        }
        assertEquals("1 张的池无从避让，但也不该崩/不该丢", 1, pool.size)
    }

    @Test
    fun `clear drops the pool`() {
        val pool = PhotoWallPool()
        pool.reset(photos(3))
        assertEquals(3, pool.size)
        pool.clear()
        assertEquals(0, pool.size)
        assertTrue(pool.isEmpty)
        assertNull(pool.peek())
    }

    @Test
    fun `reset forgets the previous round memory`() {
        val pool = PhotoWallPool()
        pool.reset(listOf(ref("A"), ref("B")))
        pool.advance()
        pool.advance()
        // 换一批新照片：旧池的「末张」对新池没有意义 ⇒ 避让记忆必须清掉
        pool.reset(listOf(ref("X"), ref("Y")))
        pool.beginNewRound(VisualizerRandom(3u))
        val first = pool.peek()!!.id
        assertTrue(
            "新池的首张只能是新池里的元素（A/B 不该再出现）",
            first == idOf("X") || first == idOf("Y"),
        )
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** ⛔ `PhotoRef.id` 是 `"<kind>:<payload>"` —— 断言必须比**完整 id**，不能比裸名 */
    private fun idOf(payload: String): String = PhotoIds.of(PhotoSourceKind.EXTERNAL, payload)

    private fun photos(n: Int): List<PhotoRef> = List(n) { ref("p$it") }

    private fun ref(payload: String): PhotoRef = PhotoRef(
        id = PhotoIds.of(PhotoSourceKind.EXTERNAL, payload),
        displayName = "$payload.jpg",
        width = 1920,
        height = 1080,
        size = 1024L,
        lastModified = 0L,
        dateAdded = 0L,
        source = PhotoSourceKind.EXTERNAL,
    )

    /** 「只洗牌不避让」的错法（[PhotoWallPool.beginNewRound] 去掉交换那两行） */
    private fun shuffledWithoutAvoid(list: List<PhotoRef>, random: VisualizerRandom): List<PhotoRef> {
        if (list.size < 2) return list
        val a = ArrayList(list)
        for (i in a.size - 1 downTo 1) {
            val j = random.nextIndex(i + 1)
            if (i != j) {
                val tmp = a[i]
                a[i] = a[j]
                a[j] = tmp
            }
        }
        return a
    }
}
