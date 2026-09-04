package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E-2: ArtistSplitter 单元测试
 */
class ArtistSplitterTest {

    @Test
    fun `split with empty string returns empty list`() {
        assertEquals(emptyList<String>(), ArtistSplitter.split(""))
        assertEquals(emptyList<String>(), ArtistSplitter.split("  "))
    }

    @Test
    fun `split with single artist returns single element`() {
        assertEquals(listOf("周杰伦"), ArtistSplitter.split("周杰伦"))
        assertEquals(listOf("Taylor Swift"), ArtistSplitter.split("Taylor Swift"))
    }

    @Test
    fun `split with feat delimiter`() {
        assertEquals(
            listOf("周杰伦", "杨瑞代"),
            ArtistSplitter.split("周杰伦 feat. 杨瑞代")
        )
    }

    @Test
    fun `split with ft delimiter`() {
        assertEquals(
            listOf("Adele", "Bob Dylan"),
            ArtistSplitter.split("Adele ft. Bob Dylan")
        )
    }

    @Test
    fun `split with ampersand delimiter`() {
        assertEquals(
            listOf("张三", "李四"),
            ArtistSplitter.split("张三 & 李四")
        )
    }

    @Test
    fun `split with Chinese comma`() {
        assertEquals(
            listOf("王五", "赵六"),
            ArtistSplitter.split("王五、赵六")
        )
    }

    @Test
    fun `split with slash delimiter`() {
        assertEquals(
            listOf("Artist A", "Artist B"),
            ArtistSplitter.split("Artist A / Artist B")
        )
    }

    @Test
    fun `split deduplicates identical artists`() {
        assertEquals(
            listOf("周杰伦"),
            ArtistSplitter.split("周杰伦 feat. 周杰伦")
        )
    }

    @Test
    fun `isMultiArtist returns true for multi-artist strings`() {
        assertEquals(true, ArtistSplitter.isMultiArtist("A & B"))
        assertEquals(false, ArtistSplitter.isMultiArtist("A"))
    }

    @Test
    fun `split trims whitespace from parts`() {
        val result = ArtistSplitter.split("  A  &  B  ")
        assertEquals(listOf("A", "B"), result)
    }

    @Test
    fun `split full width slash delimiter`() {
        assertEquals(
            listOf("古天乐", "萱萱"),
            ArtistSplitter.split("古天乐／萱萱")
        )
    }

    @Test
    fun `split collaboration names written with half width slash`() {
        assertEquals(
            listOf("古天乐", "萱萱"),
            ArtistSplitter.split("古天乐/萱萱")
        )
    }

    @Test
    fun `split trims full width whitespace`() {
        assertEquals(listOf("古天乐"), ArtistSplitter.split("　古天乐 "))
    }

    @Test
    fun `normalizeKey merges visually identical names`() {
        assertEquals(ArtistSplitter.normalizeKey("古天乐"), ArtistSplitter.normalizeKey("古天乐 "))
        assertEquals(ArtistSplitter.normalizeKey("古天乐"), ArtistSplitter.normalizeKey("　古天乐　"))
        assertEquals(ArtistSplitter.normalizeKey("abc"), ArtistSplitter.normalizeKey("ＡＢＣ"))
        assertEquals(ArtistSplitter.normalizeKey("a b"), ArtistSplitter.normalizeKey("A　B"))
    }

    @Test
    fun `containsArtist matches by split and normalized name`() {
        assertEquals(true, ArtistSplitter.containsArtist("古天乐/萱萱", "古天乐"))
        assertEquals(true, ArtistSplitter.containsArtist("古天乐/萱萱", "萱萱 "))
        assertEquals(true, ArtistSplitter.containsArtist("古天乐", "古天乐"))
        // 未拆分的合唱名不应被当成独立艺术家匹配到歌曲
        assertEquals(false, ArtistSplitter.containsArtist("古天乐/萱萱", "古天乐/萱萱"))
    }
}
