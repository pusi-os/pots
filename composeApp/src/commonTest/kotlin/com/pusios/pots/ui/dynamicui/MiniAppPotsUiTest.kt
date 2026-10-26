package com.pusios.pots.ui.dynamicui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MiniAppPotsUiTest {

    @Test
    fun parsesRecordsNodeAndStoreActions() {
        val json = """
            {"type":"column","children":[
              {"type":"records","collection":"tasks","title":"Tasks","fields":["title"]},
              {"type":"button","label":"Add","action":{"type":"store_put","collection":"tasks","collectFrom":["title"]}}
            ]}
        """.trimIndent()
        val result = PotsUiParser.parseUiBlockBody(json)
        assertIs<PotsUiParser.UiBlockResult.Ui>(result)
        val column = assertIs<ColumnNode>(result.node)
        assertEquals(2, column.children.size)
        val records = assertIs<RecordsNode>(column.children[0])
        assertEquals("tasks", records.collection)
        assertEquals(listOf("title"), records.fields)
        val button = assertIs<ButtonNode>(column.children[1])
        val action = assertIs<StorePutAction>(button.action)
        assertEquals("tasks", action.collection)
        assertEquals(listOf("title"), action.collectFrom)
    }

    @Test
    fun parsesDeleteAndNavigateActions() {
        val json = """
            {"type":"column","children":[
              {"type":"button","label":"Delete","action":{"type":"store_delete","collection":"tasks","recordId":"{{record.id}}"}},
              {"type":"button","label":"Next","action":{"type":"navigate","screenId":"s2"}}
            ]}
        """.trimIndent()
        val result = PotsUiParser.parseUiBlockBody(json)
        assertIs<PotsUiParser.UiBlockResult.Ui>(result)
        val column = assertIs<ColumnNode>(result.node)
        val delete = assertIs<StoreDeleteAction>((column.children[0] as ButtonNode).action)
        assertEquals("{{record.id}}", delete.recordId)
        val navigate = assertIs<NavigateAction>((column.children[1] as ButtonNode).action)
        assertEquals("s2", navigate.screenId)
    }

    @Test
    fun parsesHostCallAction() {
        val json = """{"type":"button","label":"Load","action":{"type":"host_call","service":"calendar.list","params":{"from":"2026-01-01T00:00:00Z"},"storeTo":"events"}}"""
        val result = PotsUiParser.parseUiBlockBody(json)
        assertIs<PotsUiParser.UiBlockResult.Ui>(result)
        val action = assertIs<HostCallAction>((result.node as ButtonNode).action)
        assertEquals("calendar.list", action.service)
        assertEquals("events", action.storeTo)
        assertEquals("2026-01-01T00:00:00Z", action.params?.get("from")?.content)
    }

    @Test
    fun infersStorePutFromCollectFromWithoutData() {
        val json = """{"type":"button","label":"Save","action":{"collection":"notes","collectFrom":["title"]}}"""
        val result = PotsUiParser.parseUiBlockBody(json)
        assertIs<PotsUiParser.UiBlockResult.Ui>(result)
        val action = assertIs<StorePutAction>((result.node as ButtonNode).action)
        assertEquals(listOf("title"), action.collectFrom)
    }

    @Test
    fun infersStoreActionFromCollectionAndData() {
        val json = """{"type":"button","label":"Save","action":{"collection":"notes","data":{"title":"x"}}}"""
        val result = PotsUiParser.parseUiBlockBody(json)
        assertIs<PotsUiParser.UiBlockResult.Ui>(result)
        val action = assertIs<StorePutAction>((result.node as ButtonNode).action)
        assertTrue(action.data?.containsKey("title") == true)
    }
}
