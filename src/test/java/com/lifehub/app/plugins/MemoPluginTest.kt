package com.lifehub.app.plugins

import com.lifehub.app.plugin.runtime.dataSourceKey

import com.lifehub.app.plugins.testing.FakePluginStorage
import com.lifehub.pluginapi.PluginAction
import com.lifehub.pluginapi.PluginActionContext
import com.lifehub.pluginapi.PluginActionRequest
import com.lifehub.pluginapi.PluginActionResult
import com.lifehub.pluginapi.PluginDataResult
import com.lifehub.pluginapi.PluginPageDataContext
import com.lifehub.pluginapi.PluginPageDataRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ln

class MemoPluginTest {

    private val storage = FakePluginStorage()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun savesAndSearchesTasks() {
        save(title = "购物清单", body = "牛奶和面包")
        save(title = "读书笔记", body = "插件架构")

        assertEquals(2, loadItems().items.size)
        val filtered = loadItems(mapOf("query" to "牛奶"))
        assertEquals(1, filtered.items.size)
        assertEquals("购物清单", filtered.items.single().title)
    }

    @Test
    fun savesPriorityAndDeadline() {
        val deadline = LocalDate.now().plusDays(3).toString()
        save(title = "写报告", priority = 5, deadline = deadline)

        val item = loadItems().items.single()
        assertEquals("📝", item.leadingText)
        assertTrue(item.roomy)
        assertEquals(4, item.titleMaxLines)
        assertTrue(item.subtitle.contains("截止"))
        val edit = item.actions.first().action as PluginAction.Navigate
        assertEquals("5", edit.params["priority"])
        assertEquals(deadline, edit.params["deadline"])
    }

    @Test
    fun overdueTaskRemainsIncompleteAndVisibleInOverdueFilter() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        save(title = "逾期任务", deadline = yesterday)

        val item = loadItems().items.single()
        assertFalse(item.completed)
        assertTrue(item.subtitle.contains("已逾期"))

        val overdue = loadItems(mapOf("filter" to MemoPlugin.FILTER_OVERDUE)).items
        assertEquals(1, overdue.size)
        assertFalse(overdue.single().completed)
    }

    @Test
    fun editActionPreservesFilterAndSort() {
        save(title = "今天编辑", deadline = LocalDate.now().toString())

        val item = loadItems(
            mapOf(
                "filter" to MemoPlugin.FILTER_TODAY,
                "sort" to MemoPlugin.SORT_RECENT,
            ),
        ).items.single()
        val edit = item.actions.first().action as PluginAction.Navigate

        assertEquals(MemoPlugin.FILTER_TODAY, edit.params["filter"])
        assertEquals(MemoPlugin.SORT_RECENT, edit.params["sort"])
    }

    @Test
    fun savesSelectedEmoji() {
        save(title = "图标任务", emoji = "🎯")

        val item = loadItems().items.single()

        assertEquals("🎯", item.leadingText)
        val edit = item.actions.first().action as PluginAction.Navigate
        assertEquals("🎯", edit.params["emoji"])
    }

    @Test
    fun pinsTaskToTop() {
        save(title = "第一篇", body = "a")
        save(title = "第二篇", body = "b")
        val firstId = Json.decodeFromString<MemoEntry>(
            storage.list("memos").first { it.payload.contains("第一篇") }.payload,
        ).id

        val result = handle(MemoPlugin.ACTION_TOGGLE_PIN, mapOf("id" to firstId))

        assertTrue(result is PluginActionResult.Refresh)
        val items = loadItems()
        assertEquals("第一篇", items.items.first().title)
        assertEquals("置顶", items.items.first().groupTitle)
    }

    @Test
    fun deleteMovesTaskToTrashAndRestoreBringsItBack() {
        save(title = "待删除", body = "")
        val id = loadItems().items.single().id

        assertTrue(handle(MemoPlugin.ACTION_DELETE, mapOf("id" to id)) is PluginActionResult.Refresh)
        assertEquals(0, storage.count("memos"))
        assertEquals(1, storage.count("memos_trash"))
        assertEquals(1, loadTrash().items.size)

        assertTrue(handle(MemoPlugin.ACTION_RESTORE, mapOf("id" to id)) is PluginActionResult.Refresh)
        assertEquals(1, storage.count("memos"))
        assertEquals(0, storage.count("memos_trash"))
        assertEquals("待删除", loadItems().items.single().title)
    }

    @Test
    fun emptyTrashRemovesAllTasks() {
        save(title = "一", body = "")
        save(title = "二", body = "")
        val ids = loadItems().items.map { it.id }
        ids.forEach { handle(MemoPlugin.ACTION_DELETE, mapOf("id" to it)) }

        assertTrue(handle(MemoPlugin.ACTION_EMPTY_TRASH) is PluginActionResult.Refresh)
        assertEquals(0, storage.count("memos_trash"))
        assertEquals(0, loadTrash().items.size)
    }

    @Test
    fun globalSearchFindsTasksByTitleAndBody() {
        save(title = "买咖啡", body = "咖啡豆")
        save(title = "写报告", body = "季度总结")

        val result = loadSearch("咖啡")

        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("买咖啡", item.title)
        val action = item.clickAction as PluginAction.Navigate
        assertEquals("edit", action.pageId)
    }

    @Test
    fun rejectsBlankTitle() {
        val result = handle(
            MemoPlugin.ACTION_SAVE,
            mapOf("title" to "   ", "body" to "内容"),
        )

        assertTrue(result is PluginActionResult.Failure)
        assertTrue((result as PluginActionResult.Failure).message.contains("标题"))
    }

    @Test
    fun searchActionOpensSearchPage() {
        val result = handle(
            MemoPlugin.ACTION_SEARCH,
            mapOf(
                "query" to "  笔记  ",
                "sort" to MemoPlugin.SORT_RECENT,
                "filter" to MemoPlugin.FILTER_ALL,
            ),
        )

        assertTrue(result is PluginActionResult.Navigate)
        assertEquals("search", (result as PluginActionResult.Navigate).pageId)
        assertEquals(mapOf("query" to "笔记"), result.params)
    }

    @Test
    fun blankSearchShowsMessage() {
        val result = handle(MemoPlugin.ACTION_SEARCH, mapOf("query" to "   "))

        assertTrue(result is PluginActionResult.ShowMessage)
        assertTrue((result as PluginActionResult.ShowMessage).message.contains("关键词"))
    }

    @Test
    fun urgencyScoreMatchesFormula() {
        val today = LocalDate.now()
        val daily = memo(priority = 3, multiplier = 1)
        assertEquals(6.0, MemoPlugin.urgencyScore(daily, today), 0.0001)

        val inSevenDays = memo(priority = 4, deadline = today.plusDays(7).toString())
        assertEquals(4.0, MemoPlugin.urgencyScore(inSevenDays, today), 0.0001)

        val inThreeDays = memo(priority = 2, deadline = today.plusDays(3).toString())
        val expected = 2.0 / (ln(3.0) / ln(7.0))
        assertEquals(expected, MemoPlugin.urgencyScore(inThreeDays, today), 0.0001)

        assertTrue(
            MemoPlugin.urgencyScore(
                memo(priority = 1, deadline = today.toString()),
                today,
            ).isInfinite(),
        )
        assertTrue(
            MemoPlugin.urgencyScore(
                memo(priority = 1, deadline = today.plusDays(1).toString()),
                today,
            ).isInfinite(),
        )
    }

    @Test
    fun urgencySortPutsPinnedThenMostUrgentThenCompleted() {
        val today = LocalDate.now()
        val pinned = memo(id = "pinned", pinned = true, priority = 1, deadline = today.plusDays(30).toString())
        val highest = memo(id = "highest", priority = 5, deadline = today.toString())
        val daily = memo(id = "daily", priority = 5)
        val low = memo(id = "low", priority = 1, deadline = today.plusDays(30).toString())
        val completed = memo(
            id = "done",
            priority = 5,
            deadline = today.toString(),
            completedAt = 1L,
        )

        val sorted = MemoPlugin.sortTasks(
            listOf(low, completed, daily, highest, pinned),
            MemoPlugin.SORT_URGENCY,
            today,
        ).map { it.id }

        assertEquals(listOf("pinned", "highest", "daily", "low", "done"), sorted)
    }

    @Test
    fun recentSortOrdersByDeadlineThenDailyUrgency() {
        val today = LocalDate.now()
        val near = memo(id = "near", deadline = today.plusDays(2).toString())
        val far = memo(id = "far", priority = 5, deadline = today.plusDays(10).toString())
        val dailyLow = memo(id = "daily-low", priority = 1)
        val dailyHigh = memo(id = "daily-high", priority = 5)
        val completed = memo(
            id = "done",
            deadline = today.plusDays(1).toString(),
            completedAt = 1L,
        )

        val sorted = MemoPlugin.sortTasks(
            listOf(completed, dailyLow, far, dailyHigh, near),
            MemoPlugin.SORT_RECENT,
            today,
        ).map { it.id }

        assertEquals(listOf("near", "far", "daily-high", "daily-low", "done"), sorted)
    }

    @Test
    fun dailyMultiplierIncreasesUrgency() {
        save(title = "每日任务", priority = 2)
        val item = loadItems().items.single()
        val id = item.id
        assertEquals("紧急 2", item.footer)
        // 未提升倍率时不展示“×1”，并保留可见的完成入口。
        assertEquals("日常任务", item.subtitle)
        assertTrue(item.actions.any { it.label == "完成" })
        assertTrue(item.clickAction is PluginAction.Navigate)
        // 倍率提升只绑定在 emoji 上，不占用整行点击（整行仍用于编辑）。
        assertEquals("提升倍率", item.leadingAction?.label)
        assertEquals(
            PluginAction.Host(MemoPlugin.ACTION_INCREMENT_MULTIPLIER, mapOf("id" to id)),
            item.leadingAction?.action,
        )

        assertTrue(
            handle(MemoPlugin.ACTION_INCREMENT_MULTIPLIER, mapOf("id" to id)) is
                PluginActionResult.Refresh,
        )
        assertEquals("紧急 4", loadItems().items.single().footer)
        assertTrue(loadItems().items.single().subtitle.contains("倍率 ×2"))

        handle(MemoPlugin.ACTION_INCREMENT_MULTIPLIER, mapOf("id" to id))
        assertEquals("紧急 6", loadItems().items.single().footer)
    }

    @Test
    fun timedTaskHasNoLeadingMultiplierAction() {
        save(title = "有截止任务", deadline = LocalDate.now().plusDays(2).toString())

        assertNull(loadItems().items.single().leadingAction)
    }

    @Test
    fun completeAndReopenTask() {
        save(title = "可完成任务")
        val id = loadItems().items.single().id

        assertTrue(handle(MemoPlugin.ACTION_COMPLETE, mapOf("id" to id)) is PluginActionResult.Refresh)
        val completed = loadItems().items.single()
        assertTrue(completed.completed)
        assertEquals("已完成", completed.groupTitle)

        assertTrue(handle(MemoPlugin.ACTION_REOPEN, mapOf("id" to id)) is PluginActionResult.Refresh)
        assertTrue(!loadItems().items.single().completed)
    }

    @Test
    fun completeAndDeleteExposeUndoActions() {
        save(title = "可撤销任务")
        val id = loadItems().items.single().id

        val completed = handle(MemoPlugin.ACTION_COMPLETE, mapOf("id" to id))
        assertTrue(completed is PluginActionResult.Refresh)
        completed as PluginActionResult.Refresh
        assertEquals("撤销", completed.undoLabel)
        assertEquals(
            PluginAction.Host(MemoPlugin.ACTION_REOPEN, mapOf("id" to id)),
            completed.undoAction,
        )

        val deleted = handle(MemoPlugin.ACTION_DELETE, mapOf("id" to id))
        assertTrue(deleted is PluginActionResult.Refresh)
        deleted as PluginActionResult.Refresh
        assertEquals("撤销", deleted.undoLabel)
        assertEquals(
            PluginAction.Host(MemoPlugin.ACTION_RESTORE, mapOf("id" to id)),
            deleted.undoAction,
        )
    }

    @Test
    fun filterNarrowsTasks() {
        save(title = "今天到期", deadline = LocalDate.now().toString())
        save(title = "本周内", deadline = LocalDate.now().plusDays(5).toString())
        save(title = "更晚", deadline = LocalDate.now().plusDays(10).toString())
        save(title = "日常任务")

        assertEquals(4, loadItems(mapOf("filter" to MemoPlugin.FILTER_ALL)).items.size)
        assertEquals(2, loadItems(mapOf("filter" to MemoPlugin.FILTER_TODAY)).items.size)
        assertEquals(2, loadItems(mapOf("filter" to MemoPlugin.FILTER_WEEK)).items.size)
        assertEquals(1, loadItems(mapOf("filter" to MemoPlugin.FILTER_DAILY)).items.size)
        assertEquals(0, loadItems(mapOf("filter" to MemoPlugin.FILTER_OVERDUE)).items.size)
    }

    @Test
    fun todayViewGroupsTodayDailyAndCompleted() {
        save(title = "今天任务", deadline = LocalDate.now().toString())
        save(title = "日常任务")
        save(title = "完成任务", deadline = LocalDate.now().toString())
        val completedId = loadItems(mapOf("filter" to MemoPlugin.FILTER_TODAY))
            .items.first { it.title == "完成任务" }.id
        handle(MemoPlugin.ACTION_COMPLETE, mapOf("id" to completedId))

        val items = loadItems(mapOf("filter" to MemoPlugin.FILTER_TODAY)).items

        assertEquals(listOf("今天", "日常", "已完成"), items.map { it.groupTitle })
        assertEquals(
            listOf("今天任务", "日常任务", "完成任务"),
            items.map { it.title },
        )
    }

    @Test
    fun parsesQuickAddText() {
        val today = LocalDate.of(2026, 11, 13)

        val tomorrow = MemoPlugin.parseQuickAdd("明天 18:00 交报告 p5", today)!!
        assertEquals("交报告", tomorrow.title)
        assertEquals(today.plusDays(1), tomorrow.deadline)
        assertEquals(5, tomorrow.priority)

        val friday = MemoPlugin.parseQuickAdd("周五 复盘", today)!!
        assertEquals("复盘", friday.title)
        assertEquals(DayOfWeek.FRIDAY, friday.deadline?.dayOfWeek)

        val nextMonday = MemoPlugin.parseQuickAdd("下周一 开会", today)!!
        assertEquals(LocalDate.of(2026, 11, 16), nextMonday.deadline)

        val monthDay = MemoPlugin.parseQuickAdd("11月20日 交房租", today)!!
        assertEquals(LocalDate.of(2026, 11, 20), monthDay.deadline)

        assertNull(MemoPlugin.parseQuickAdd("   ", today))
    }

    @Test
    fun quickAddCreatesTaskAndSupportsUndo() {
        val result = handle(
            MemoPlugin.ACTION_QUICK_ADD,
            mapOf("quick" to "明天 18:00 交报告 p5"),
        )

        assertTrue(result is PluginActionResult.Refresh)
        result as PluginActionResult.Refresh
        assertEquals("撤销", result.undoLabel)
        assertTrue("quick" in result.clearInputIds)
        assertEquals(1, storage.count("memos"))

        val memo = Json.decodeFromString<MemoEntry>(storage.list("memos").single().payload)
        assertEquals("交报告", memo.title)
        assertEquals(LocalDate.now().plusDays(1).toString(), memo.deadline)
        assertEquals(5, memo.priority)

        assertTrue(
            handle(MemoPlugin.ACTION_DISCARD, mapOf("id" to memo.id)) is
                PluginActionResult.Refresh,
        )
        assertEquals(0, storage.count("memos"))
    }

    @Test
    fun quickAddRejectsBlankText() {
        val result = handle(MemoPlugin.ACTION_QUICK_ADD, mapOf("quick" to "   "))

        assertTrue(result is PluginActionResult.Failure)
    }

    @Test
    fun summaryCountsTodayDailyAndCompleted() {
        save(title = "今天到期", deadline = LocalDate.now().toString())
        save(title = "日常任务")
        save(title = "已完成任务")
        val completedId = loadItems().items.first { it.title == "已完成任务" }.id
        handle(MemoPlugin.ACTION_COMPLETE, mapOf("id" to completedId))

        val summary = loadSummary()

        assertEquals(listOf("今日待办", "日常任务", "已完成"), summary.metrics.map { it.label })
        assertEquals("1", summary.metrics[0].value)
        assertEquals("1", summary.metrics[1].value)
        assertEquals("1", summary.metrics[2].value)
    }

    @Test
    fun deleteForeverRemovesOnlyTrashEntry() {
        save(title = "彻底删除")
        val id = loadItems().items.single().id
        handle(MemoPlugin.ACTION_DELETE, mapOf("id" to id))

        assertTrue(
            handle(MemoPlugin.ACTION_DELETE_FOREVER, mapOf("id" to id)) is
                PluginActionResult.Refresh,
        )
        assertEquals(0, storage.count("memos_trash"))
        assertEquals(0, loadTrash().items.size)
    }

    @Test
    fun expiredTrashIsPurgedOnLoad() {
        val memo = memo(id = "expired", title = "过期任务")
        storage.insert(
            "memos_trash",
            json.encodeToString(
                TrashEntry(
                    memo = memo,
                    deletedAt = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000,
                ),
            ),
            memo.id,
        )

        assertEquals(0, loadTrash().items.size)
        assertEquals(0, storage.count("memos_trash"))
    }

    private fun memo(
        id: String = "memo-$idCounter",
        title: String = "任务",
        pinned: Boolean = false,
        priority: Int = 3,
        deadline: String? = null,
        multiplier: Int = 0,
        completedAt: Long? = null,
    ): MemoEntry {
        idCounter += 1
        return MemoEntry(
            id = id,
            title = title,
            pinned = pinned,
            priority = priority,
            deadline = deadline,
            multiplier = multiplier,
            completedAt = completedAt,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    private var idCounter = 0

    private fun save(
        title: String,
        body: String = "",
        priority: Int? = null,
        emoji: String? = null,
        deadline: String? = null,
    ): PluginActionResult {
        val params = buildMap {
            put("title", title)
            if (body.isNotEmpty()) put("body", body)
            priority?.let { put("priority", it.toString()) }
            emoji?.let { put("emoji", it) }
            deadline?.let { put("deadline", it) }
        }
        return handle(MemoPlugin.ACTION_SAVE, params)
    }

    private fun handle(
        actionId: String,
        params: Map<String, String> = emptyMap(),
    ): PluginActionResult =
        MemoPlugin.actionHandlers.getValue(actionId).handle(
            PluginActionContext(
                request = PluginActionRequest(
                    pluginId = MemoPlugin.PLUGIN_ID,
                    pageId = "edit",
                    actionId = actionId,
                    params = params,
                ),
                storage = storage,
            ),
        )

    private fun loadItems(params: Map<String, String> = emptyMap()): PluginDataResult =
        MemoPlugin.dataHandlers
            .getValue(dataSourceKey(MemoPlugin.PLUGIN_ID, MemoPlugin.SOURCE_ENTRIES))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = MemoPlugin.PLUGIN_ID,
                        pageId = "list",
                        sourceId = MemoPlugin.SOURCE_ENTRIES,
                        params = mapOf("filter" to MemoPlugin.FILTER_ALL) + params,
                    ),
                    storage = storage,
                ),
            )

    private fun loadTrash(): PluginDataResult =
        MemoPlugin.dataHandlers
            .getValue(dataSourceKey(MemoPlugin.PLUGIN_ID, MemoPlugin.SOURCE_TRASH))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = MemoPlugin.PLUGIN_ID,
                        pageId = "trash",
                        sourceId = MemoPlugin.SOURCE_TRASH,
                    ),
                    storage = storage,
                ),
            )

    private fun loadSummary(): PluginDataResult =
        MemoPlugin.dataHandlers
            .getValue(dataSourceKey(MemoPlugin.PLUGIN_ID, MemoPlugin.SOURCE_SUMMARY))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = MemoPlugin.PLUGIN_ID,
                        pageId = "home",
                        sourceId = MemoPlugin.SOURCE_SUMMARY,
                    ),
                    storage = storage,
                ),
            )

    private fun loadSearch(query: String): PluginDataResult =
        MemoPlugin.dataHandlers
            .getValue(dataSourceKey(MemoPlugin.PLUGIN_ID, MemoPlugin.SOURCE_SEARCH))
            .load(
                PluginPageDataContext(
                    request = PluginPageDataRequest(
                        pluginId = MemoPlugin.PLUGIN_ID,
                        pageId = "search",
                        sourceId = MemoPlugin.SOURCE_SEARCH,
                        params = mapOf("query" to query, "limit" to "10"),
                    ),
                    storage = storage,
                ),
            )
}
