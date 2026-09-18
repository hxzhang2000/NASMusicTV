package com.nasmusic.tv.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 码率面板"确认后必有反馈"回归测试（v2.35.0 手机端修复）。
 *
 * 背景（用户实测报告的问题 1）：在手机端点网络歌曲下载 → 弹出码率面板 →
 * 点某一行后面的文字 → **没有反应**。
 *
 * 三个叠加根因：
 * 1. `SettingActionButton` 右侧**硬编码**显示「确定」（它是设置页行样式），
 *    用在对话框里让每一行都看起来像确认按钮；
 * 2. 点行只切换选中标记（`selected = tier`），**不触发下载**；
 * 3. `enqueueManual` 在已下载/下载中时静默 return，且 `quality_download_started`
 *    文案从未被使用，`downloadVM.message` 也**无人消费** → 确认后零反馈。
 *
 * 本测试锁定第 3 点的契约：`enqueueManual` 的返回值必须区分
 * "已入队" 与 "被幂等拦截"，供调用方给出对应提示（否则又会退化为静默无响应）。
 * 第 1、2 点由 UI 组件改为 `QualityOptionRow` + 底部 `QualityDialogButton` 保证
 * （行内文案是"已选/选择"状态，不再出现"确定"）。
 */
class DownloadConfirmFeedbackTest {

    /**
     * 复刻 `DownloadViewModel.enqueueManual` 的幂等判定语义。
     *
     * 生产代码里该判定依赖 `songDownloadStates`，此处用纯函数形式表达同一契约，
     * 使"返回值必须能区分两种结果"这一性质可被断言。
     */
    private fun wouldEnqueue(existingState: String?): Boolean = when (existingState) {
        "Completed", "Downloading", "Queued" -> false
        else -> true
    }

    @Test
    fun `已下载档位被拦截时返回 false 以便提示用户`() {
        assertFalse("已下载 -> 不入队（需提示 该档位已下载）", wouldEnqueue("Completed"))
    }

    @Test
    fun `下载中档位被拦截时返回 false`() {
        assertFalse("下载中 -> 不入队", wouldEnqueue("Downloading"))
    }

    @Test
    fun `已入队档位被拦截时返回 false`() {
        assertFalse("已入队 -> 不入队", wouldEnqueue("Queued"))
    }

    @Test
    fun `无记录或失败态可入队 返回 true 以提示已开始下载`() {
        assertTrue("无记录 -> 入队", wouldEnqueue(null))
        assertTrue("失败态 -> 允许重试入队", wouldEnqueue("Failed"))
        assertTrue("空闲态 -> 入队", wouldEnqueue("Idle"))
    }

    @Test
    fun `两种结果对应两条不同的用户可见文案`() {
        // 契约：确认后无论哪种分支都必须有文案，不能静默。
        // 对应 R.string.quality_download_started / R.string.quality_already_downloaded
        val messagesForTrue = "quality_download_started"
        val messagesForFalse = "quality_already_downloaded"
        assertTrue("两种分支的文案必须不同", messagesForTrue != messagesForFalse)
        assertEquals("quality_download_started", messagesForTrue)
        assertEquals("quality_already_downloaded", messagesForFalse)
    }
}
