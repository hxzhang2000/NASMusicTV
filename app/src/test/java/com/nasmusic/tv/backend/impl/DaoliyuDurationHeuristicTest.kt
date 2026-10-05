package com.nasmusic.tv.backend.impl

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M1 修复回归（2026-10-06，代码审查报告 §4）：道理鱼 duration 单位启发式纯函数测试。
 *
 * ⛔ 本测试**锁定现状**而非锁定正确性：启发式的已知边界缺陷（100s 两侧跳变）
 * 在协议实证前无法根治，这里锁死的是「抽函数重构后行为与原实现逐值一致」，
 * 防止后续维护无意改动启发式阈值/方向。
 */
class DaoliyuDurationHeuristicTest {

    @Test
    fun `null 与缺失字段返回 0`() {
        assertEquals(0L, DaoliyuAdapter.parseDurationMs(null))
        assertEquals(0L, DaoliyuAdapter.parseDurationMs(0L))
    }

    @Test
    fun `秒单位路径 - 小值乘 1000`() {
        assertEquals(90_000L, DaoliyuAdapter.parseDurationMs(90L))       // 90s 歌
        assertEquals(100_000L, DaoliyuAdapter.parseDurationMs(100L))     // 阈值边界（≤阈值走秒）
        assertEquals(240_000L, DaoliyuAdapter.parseDurationMs(240L))     // 4 分钟的歌
    }

    @Test
    fun `毫秒单位路径 - 大值原样保留`() {
        assertEquals(100_001L, DaoliyuAdapter.parseDurationMs(100_001L)) // 阈值之上走毫秒
        assertEquals(3_600_000L, DaoliyuAdapter.parseDurationMs(3_600_000L))
    }

    @Test
    fun `已知边界缺陷的显式记录 - 阈值两侧语义跳变`() {
        // 秒单位下 101s 的歌（>100s 值但 ≤100000）仍走秒路径 ×1000 ⇒ 101000ms。
        // 这是启发式的已知缺陷语义：真正的误判场景是「毫秒单位下值恰好落在 0..100000」
        // 与「秒单位下值 >100000（>27.7 小时，实际不存在）」，锁定现状防静默变化；
        // 协议实证后此断言应随启发式一起删除。
        assertEquals(101_000L, DaoliyuAdapter.parseDurationMs(101L))
        // 毫秒单位下 90000ms（<100s 的歌）会被 ×1000 错判——锁定现状
        assertEquals(90_000_000L, DaoliyuAdapter.parseDurationMs(90_000L))
    }
}
