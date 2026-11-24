package com.annieplayer.annie_music.kt.data.sources.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotesTest {

    @Test
    fun `空更新日志解析成空列表`() {
        assertTrue(parseUpdateNotes("").isEmpty())
        assertTrue(parseUpdateNotes("   \n\n  ").isEmpty())
    }

    @Test
    fun `标题与列表项按类型拆开`() {
        val markdown = """
            ## 新增
            - 支持 DSD 直通
            - 新增 LAN 遥控
            ### 修复
            * 修复封面闪烁
        """.trimIndent()
        val lines = parseUpdateNotes(markdown)
        assertEquals(
            listOf(
                UpdateNoteLine.Heading("新增", 2),
                UpdateNoteLine.Bullet("支持 DSD 直通"),
                UpdateNoteLine.Bullet("新增 LAN 遥控"),
                UpdateNoteLine.Heading("修复", 3),
                UpdateNoteLine.Bullet("修复封面闪烁"),
            ),
            lines,
        )
    }

    @Test
    fun `代码块内容整行保留且不解析标记`() {
        val markdown = """
            修复配置：
            ```
            - 这不是列表
            ## 这不是标题
            ```
        """.trimIndent()
        val lines = parseUpdateNotes(markdown)
        assertEquals(UpdateNoteLine.Plain("修复配置："), lines[0])
        assertEquals(UpdateNoteLine.Code("- 这不是列表"), lines[1])
        assertEquals(UpdateNoteLine.Code("## 这不是标题"), lines[2])
    }

    @Test
    fun `行内标记被清理成纯文本`() {
        val markdown = "- **重点** 与 `代码` 与 [文档](https://example.com)"
        val lines = parseUpdateNotes(markdown)
        assertEquals(UpdateNoteLine.Bullet("重点 与 代码 与 文档"), lines.single())
    }

    @Test
    fun `versionCode 标记行不进更新日志`() {
        val markdown = "versionCode: 2\n## 更新内容\n- 修了个 bug"
        val lines = parseUpdateNotes(markdown)
        assertEquals(
            listOf(
                UpdateNoteLine.Heading("更新内容", 2),
                UpdateNoteLine.Bullet("修了个 bug"),
            ),
            lines,
        )
    }

    @Test
    fun `空行不产生条目`() {
        val lines = parseUpdateNotes("第一条\n\n\n第二条")
        assertEquals(
            listOf(UpdateNoteLine.Plain("第一条"), UpdateNoteLine.Plain("第二条")),
            lines,
        )
    }
}
