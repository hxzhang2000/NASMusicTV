package com.nasmusic.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * B-12: UiState 单元测试
 */
class UiStateTest {

    @Test
    fun `Loading dataOrNull returns null`() {
        assertNull(UiState.Loading.dataOrNull())
    }

    @Test
    fun `Loading isSuccess returns false`() {
        assertFalse(UiState.Loading.isSuccess)
    }

    @Test
    fun `Loading isLoading returns true`() {
        assertTrue(UiState.Loading.isLoading)
    }

    @Test
    fun `Loading isError returns false`() {
        assertFalse(UiState.Loading.isError)
    }

    @Test
    fun `Success dataOrNull returns data`() {
        val state = UiState.Success("hello")
        assertEquals("hello", state.dataOrNull())
    }

    @Test
    fun `Success with integer data`() {
        val state = UiState.Success(42)
        assertEquals(42, state.dataOrNull())
    }

    @Test
    fun `Success with list data`() {
        val list = listOf("a", "b", "c")
        val state = UiState.Success(list)
        assertEquals(list, state.dataOrNull())
        assertEquals(3, state.dataOrNull()?.size)
    }

    @Test
    fun `Success isSuccess returns true`() {
        assertTrue(UiState.Success("x").isSuccess)
    }

    @Test
    fun `Success isLoading returns false`() {
        assertFalse(UiState.Success("x").isLoading)
    }

    @Test
    fun `Success isError returns false`() {
        assertFalse(UiState.Success("x").isError)
    }

    @Test
    fun `Error dataOrNull returns null`() {
        val state = UiState.Error("fail")
        assertNull(state.dataOrNull())
    }

    @Test
    fun `Error stores message`() {
        val state = UiState.Error("connection failed")
        assertEquals("connection failed", state.message)
    }

    @Test
    fun `Error stores retry callback`() {
        var called = false
        val retry: () -> Unit = { called = true }
        val state = UiState.Error("fail", retry)
        state.retry?.invoke()
        assertTrue(called)
    }

    @Test
    fun `Error retry defaults to null`() {
        val state = UiState.Error("fail")
        assertNull(state.retry)
    }

    @Test
    fun `Error isError returns true`() {
        assertTrue(UiState.Error("fail").isError)
    }

    @Test
    fun `Error isLoading returns false`() {
        assertFalse(UiState.Error("fail").isLoading)
    }

    @Test
    fun `Error isSuccess returns false`() {
        assertFalse(UiState.Error("fail").isSuccess)
    }

    @Test
    fun `when expression covers all branches`() {
        val loading: UiState<Int> = UiState.Loading
        val success: UiState<Int> = UiState.Success(1)
        val error: UiState<Int> = UiState.Error("e")

        fun describe(state: UiState<Int>): String = when (state) {
            is UiState.Loading -> "loading"
            is UiState.Success -> "success:${state.data}"
            is UiState.Error -> "error:${state.message}"
        }

        assertEquals("loading", describe(loading))
        assertEquals("success:1", describe(success))
        assertEquals("error:e", describe(error))
    }
}
