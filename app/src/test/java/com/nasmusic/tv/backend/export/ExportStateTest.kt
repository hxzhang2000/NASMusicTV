package com.nasmusic.tv.backend.export

import org.junit.Assert.*
import org.junit.Test

/**
 * 单元测试：ExportState sealed interface + ExportError enum
 *
 * 覆盖状态创建、状态属性、错误枚举值
 */
class ExportStateTest {

    @Test
    fun `Idle state is singleton`() {
        val a = ExportState.Idle
        val b = ExportState.Idle
        assertSame(a, b)
    }

    @Test
    fun `Cancelled state is singleton`() {
        val a = ExportState.Cancelled
        val b = ExportState.Cancelled
        assertSame(a, b)
    }

    @Test
    fun `Preparing state stores total`() {
        val state = ExportState.Preparing(10)
        assertEquals(10, state.total)
    }

    @Test
    fun `Running state stores progress fields`() {
        val state = ExportState.Running(
            done = 5,
            total = 20,
            current = "song.mp3",
            skipped = 2,
            failed = 1
        )
        assertEquals(5, state.done)
        assertEquals(20, state.total)
        assertEquals("song.mp3", state.current)
        assertEquals(2, state.skipped)
        assertEquals(1, state.failed)
    }

    @Test
    fun `Running state defaults skipped and failed to 0`() {
        val state = ExportState.Running(done = 0, total = 5, current = "")
        assertEquals(0, state.skipped)
        assertEquals(0, state.failed)
    }

    @Test
    fun `Completed state stores counts`() {
        val state = ExportState.Completed(done = 10, skipped = 3, failed = 0)
        assertEquals(10, state.done)
        assertEquals(3, state.skipped)
        assertEquals(0, state.failed)
    }

    @Test
    fun `Failed state stores error`() {
        val state = ExportState.Failed(ExportError.NO_SPACE)
        assertEquals(ExportError.NO_SPACE, state.reason)
    }

    @Test
    fun `ExportError has all expected values`() {
        val values = ExportError.entries
        assertEquals(6, values.size)
        assertTrue(values.contains(ExportError.NO_DEVICE))
        assertTrue(values.contains(ExportError.NO_PERMISSION))
        assertTrue(values.contains(ExportError.NO_SPACE))
        assertTrue(values.contains(ExportError.DEVICE_REMOVED))
        assertTrue(values.contains(ExportError.IO))
        assertTrue(values.contains(ExportError.NOTHING_TO_EXPORT))
    }

    @Test
    fun `ExportError ordinal matches name`() {
        assertEquals(0, ExportError.NO_DEVICE.ordinal)
        assertEquals(1, ExportError.NO_PERMISSION.ordinal)
        assertEquals(2, ExportError.NO_SPACE.ordinal)
        assertEquals(3, ExportError.DEVICE_REMOVED.ordinal)
        assertEquals(4, ExportError.IO.ordinal)
        assertEquals(5, ExportError.NOTHING_TO_EXPORT.ordinal)
    }

    @Test
    fun `ExportState is sealed`() {
        // Verify all states are subtypes
        val states: List<ExportState> = listOf(
            ExportState.Idle,
            ExportState.Preparing(0),
            ExportState.Running(0, 0, ""),
            ExportState.Completed(0, 0, 0),
            ExportState.Failed(ExportError.IO),
            ExportState.Cancelled
        )
        assertEquals(6, states.size)
    }
}
