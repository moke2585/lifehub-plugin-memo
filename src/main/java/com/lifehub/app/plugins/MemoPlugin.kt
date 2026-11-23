package com.lifehub.app.plugins

import com.lifehub.app.plugin.runtime.PluginModule
import com.lifehub.app.plugin.runtime.dataSourceKey
import com.lifehub.app.plugin.runtime.readAllRecords

import com.lifehub.pluginapi.PluginAction
import com.lifehub.pluginapi.PluginActionContext
import com.lifehub.pluginapi.PluginActionHandler
import com.lifehub.pluginapi.PluginActionResult
import com.lifehub.pluginapi.PluginConfirmDialog
import com.lifehub.pluginapi.PluginDataAction
import com.lifehub.pluginapi.PluginDataItem
import com.lifehub.pluginapi.PluginDataLimits
import com.lifehub.pluginapi.PluginDataResult
import com.lifehub.pluginapi.PluginDataTone
import com.lifehub.pluginapi.PluginMetric
import com.lifehub.pluginapi.PluginPageDataContext
import com.lifehub.pluginapi.PluginPageDataHandler
import com.lifehub.pluginapi.PluginStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import kotlin.math.ln
import kotlin.math.roundToInt

private const val DEFAULT_PRIORITY = 3
private const val DEFAULT_EMOJI = "📝"
private val MEMO_EMOJIS = listOf(
    "📝", "📌", "📚", "💼", "🏃", "🛒", "🎯", "💡", "❤️", "🍳",
    "🧹", "🛏️", "🚿", "🧺", "🐶", "🐱", "👶", "🎓", "🏋️", "🧘",
    "💊", "🏥", "💰", "🧾", "📞", "✈️", "🚗", "🏠", "🛠️", "🎨",
    "🎮", "🎵", "🎬", "📷", "🌱", "🌷", "☕", "🍺", "🎁", "⭐",
)

/**
 * 任务清单（原备忘录）：
 * - 可选截止日期；未设置截止日期视为日常任务
 * - 优先级 1..5，紧急指数按截止时间与优先级计算
 * - 左滑删除到回收站、右滑完成任务，删除保留 30 天
 * - 日常任务点击一次提升一档倍率
 */
@Serializable
internal data class MemoEntry(
    val id: String,
    val title: String,
    val body: String = "",
    val pinned: Boolean = false,
    val priority: Int = DEFAULT_PRIORITY,
    val emoji: String = DEFAULT_EMOJI,
    val deadline: String? = null,
    val multiplier: Int = 0,
    val completedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class TrashEntry(
    val memo: MemoEntry,
    val deletedAt: Long,
)

internal data class QuickAddResult(
    val title: String,
    val deadline: LocalDate?,
    val priority: Int,
)

internal val MemoEntry.deadlineDate: LocalDate?
    get() = deadline
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal val MemoEntry.isDaily: Boolean
    get() = deadlineDate == null

object MemoPlugin : PluginModule {
    const val PLUGIN_ID: String = "com.lifehub.plugin.memo"

    override val pluginId: String = PLUGIN_ID
    const val SOURCE_ENTRIES: String = "memo_entries"
    const val SOURCE_TRASH: String = "memo_trash"
    const val SOURCE_SUMMARY: String = "memo_summary"
    const val SOURCE_SEARCH: String = "memo_search"

    const val SORT_URGENCY: String = "urgency"
    const val SORT_RECENT: String = "recent"

    const val FILTER_ALL: String = "all"
    const val FILTER_TODAY: String = "today"
    const val FILTER_WEEK: String = "week"
    const val FILTER_OVERDUE: String = "overdue"
    const val FILTER_DAILY: String = "daily"

    const val TRASH_RETENTION_DAYS: Long = 30

    private const val COLLECTION = "memos"
    private const val TRASH_COLLECTION = "memos_trash"
    private const val PAGE_LIST = "list"
    private const val PAGE_EDIT = "edit"
    private const val PAGE_TRASH = "trash"
    private const val PAGE_SEARCH = "search"

    const val ACTION_SAVE: String = "$PLUGIN_ID.save"
    const val ACTION_QUICK_ADD: String = "$PLUGIN_ID.quick_add"
    const val ACTION_DISCARD: String = "$PLUGIN_ID.discard"
    const val ACTION_DELETE: String = "$PLUGIN_ID.delete"
    const val ACTION_TOGGLE_PIN: String = "$PLUGIN_ID.toggle_pin"
    const val ACTION_SEARCH: String = "$PLUGIN_ID.search"
    const val ACTION_SET_SORT: String = "$PLUGIN_ID.set_sort"
    const val ACTION_SET_FILTER: String = "$PLUGIN_ID.set_filter"
    const val ACTION_COMPLETE: String = "$PLUGIN_ID.complete"
    const val ACTION_REOPEN: String = "$PLUGIN_ID.reopen"
    const val ACTION_INCREMENT_MULTIPLIER: String = "$PLUGIN_ID.increment_multiplier"
    const val ACTION_RESTORE: String = "$PLUGIN_ID.restore"
    const val ACTION_DELETE_FOREVER: String = "$PLUGIN_ID.delete_forever"
    const val ACTION_EMPTY_TRASH: String = "$PLUGIN_ID.empty_trash"

    private const val MAX_BODY_LENGTH = 5_000
    private const val PREVIEW_LENGTH = 60
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val TRASH_RETENTION_MILLIS = TRASH_RETENTION_DAYS * DAY_MILLIS

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val monthDayFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val trashDateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

    private val confirmDeleteForever = PluginConfirmDialog(
        title = "彻底删除",
        message = "删除后无法恢复。",
        confirmLabel = "彻底删除",
        cancelLabel = "取消",
    )
    private val confirmEmptyTrash = PluginConfirmDialog(
        title = "清空回收站",
        message = "将彻底删除回收站中的所有任务，此操作无法恢复。",
        confirmLabel = "清空",
        cancelLabel = "取消",
    )

    override val actionHandlers: Map<String, PluginActionHandler> = mapOf(
        ACTION_SAVE to PluginActionHandler { context -> save(context) },
        ACTION_QUICK_ADD to PluginActionHandler { context -> quickAdd(context) },
        ACTION_DISCARD to PluginActionHandler { context -> discard(context) },
        ACTION_DELETE to PluginActionHandler { context -> moveToTrash(context) },
        ACTION_TOGGLE_PIN to PluginActionHandler { context -> togglePin(context) },
        ACTION_SEARCH to PluginActionHandler { context -> search(context) },
        ACTION_SET_SORT to PluginActionHandler { context -> setSort(context) },
        ACTION_SET_FILTER to PluginActionHandler { context -> setFilter(context) },
        ACTION_COMPLETE to PluginActionHandler { context -> complete(context) },
        ACTION_REOPEN to PluginActionHandler { context -> reopen(context) },
        ACTION_INCREMENT_MULTIPLIER to PluginActionHandler { context ->
            incrementMultiplier(context)
        },
        ACTION_RESTORE to PluginActionHandler { context -> restore(context) },
        ACTION_DELETE_FOREVER to PluginActionHandler { context -> deleteForever(context) },
        ACTION_EMPTY_TRASH to PluginActionHandler { context -> emptyTrash(context) },
    )

    override val dataHandlers: Map<String, PluginPageDataHandler> = mapOf(
        dataSourceKey(PLUGIN_ID, SOURCE_ENTRIES) to PluginPageDataHandler { context ->
            loadTasks(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_TRASH) to PluginPageDataHandler { context ->
            loadTrash(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_SUMMARY) to PluginPageDataHandler { context ->
            loadSummary(context)
        },
        dataSourceKey(PLUGIN_ID, SOURCE_SEARCH) to PluginPageDataHandler { context ->
            searchAll(context)
        },
    )

    private fun save(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val params = context.request.params
        val title = params["title"]?.trim().orEmpty()
        if (title.isBlank()) return PluginActionResult.Failure("标题不能为空")
        if (title.length > 100) return PluginActionResult.Failure("标题最多 100 个字")
        val body = params["body"].orEmpty()
        if (body.length > MAX_BODY_LENGTH) {
            return PluginActionResult.Failure("正文最多 $MAX_BODY_LENGTH 个字")
        }

        val id = params["id"]?.trim().orEmpty()
        val existing = if (id.isBlank()) null else storage.get(COLLECTION, id)
        if (id.isNotBlank() && existing == null) {
            return PluginActionResult.Failure("任务不存在")
        }
        val previous = existing?.let { record ->
            runCatching { json.decodeFromString<MemoEntry>(record.payload) }.getOrNull()
        }

        val priorityRaw = params["priority"]?.toIntOrNull()
        if (params.containsKey("priority") && priorityRaw == null) {
            return PluginActionResult.Failure("优先级不合法")
        }
        val priority = (priorityRaw ?: previous?.priority ?: DEFAULT_PRIORITY).coerceIn(1, 5)
        val emoji = params["emoji"]
            ?.takeIf { it in MEMO_EMOJIS }
            ?: previous?.emoji
            ?: DEFAULT_EMOJI

        val deadlineRaw = params["deadline"]?.trim().orEmpty()
        val deadline = if (deadlineRaw.isBlank()) {
            null
        } else {
            runCatching { LocalDate.parse(deadlineRaw) }.getOrElse {
                return PluginActionResult.Failure("截止日期格式不正确")
            }
        }

        val now = System.currentTimeMillis()
        return try {
            if (id.isBlank()) {
                val memo = MemoEntry(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    body = body,
                    priority = priority,
                    emoji = emoji,
                    deadline = deadline?.toString(),
                    multiplier = 0,
                    // 保存过去的截止日期时也保持未完成，让任务进入“已逾期”筛选。
                    completedAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
                storage.insert(COLLECTION, json.encodeToString(memo), memo.id)
            } else {
                val memo = MemoEntry(
                    id = id,
                    title = title,
                    body = body,
                    pinned = previous?.pinned ?: false,
                    priority = priority,
                    emoji = emoji,
                    deadline = deadline?.toString(),
                    multiplier = if (deadline == null) previous?.multiplier ?: 0 else 0,
                    completedAt = previous?.completedAt,
                    createdAt = previous?.createdAt ?: now,
                    updatedAt = now,
                )
                if (!storage.update(COLLECTION, id, json.encodeToString(memo))) {
                    return PluginActionResult.Failure("任务不存在")
                }
            }
            PluginActionResult.Navigate(PAGE_LIST, listParams(params))
        } catch (error: Exception) {
            PluginActionResult.Failure("保存失败：${error.message ?: "未知错误"}")
        }
    }

    private fun quickAdd(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val today = LocalDate.now()
        val raw = context.request.params["quick"].orEmpty()
        val parsed = parseQuickAdd(raw, today)
            ?: return PluginActionResult.Failure("请输入任务内容")
        val now = System.currentTimeMillis()
        val memo = MemoEntry(
            id = UUID.randomUUID().toString(),
            title = parsed.title,
            priority = parsed.priority,
            deadline = parsed.deadline?.toString(),
            // 逾期任务保留为未完成，交由用户在列表中显式完成或删除。
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        return runCatching {
            storage.insert(COLLECTION, json.encodeToString(memo), memo.id)
            PluginActionResult.Refresh(
                message = "已添加任务",
                undoLabel = "撤销",
                undoAction = PluginAction.Host(ACTION_DISCARD, mapOf("id" to memo.id)),
                clearInputIds = listOf("quick"),
            )
        }.getOrElse { error ->
            PluginActionResult.Failure("添加失败：${error.message ?: "未知错误"}")
        }
    }

    private fun discard(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少任务 ID")
        return if (storage.delete(COLLECTION, id)) {
            PluginActionResult.Refresh("已撤销添加")
        } else {
            PluginActionResult.Failure("任务不存在")
        }
    }

    private fun moveToTrash(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少任务 ID")
        val existing = storage.get(COLLECTION, id)
            ?: return PluginActionResult.Failure("任务不存在")
        val memo = runCatching {
            json.decodeFromString<MemoEntry>(existing.payload)
        }.getOrElse {
            return PluginActionResult.Failure("任务数据损坏")
        }
        val payload = json.encodeToString(TrashEntry(memo = memo, deletedAt = System.currentTimeMillis()))
        return try {
            if (storage.get(TRASH_COLLECTION, id) != null) {
                storage.update(TRASH_COLLECTION, id, payload)
            } else {
                storage.insert(TRASH_COLLECTION, payload, id)
            }
            if (!storage.delete(COLLECTION, id)) {
                storage.delete(TRASH_COLLECTION, id)
                return PluginActionResult.Failure("任务不存在")
            }
            PluginActionResult.Refresh(
                message = "已移入回收站",
                undoLabel = "撤销",
                undoAction = PluginAction.Host(ACTION_RESTORE, mapOf("id" to id)),
            )
        } catch (error: Exception) {
            PluginActionResult.Failure("删除失败：${error.message ?: "未知错误"}")
        }
    }

    private fun togglePin(context: PluginActionContext): PluginActionResult =
        updateMemo(context, "缺少任务 ID") { memo, now ->
            memo.copy(pinned = !memo.pinned, updatedAt = now) to
                (if (memo.pinned) "已取消置顶" else "已置顶")
        }

    private fun complete(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少任务 ID")
        val existing = storage.get(COLLECTION, id)
            ?: return PluginActionResult.Failure("任务不存在")
        val memo = runCatching {
            json.decodeFromString<MemoEntry>(existing.payload)
        }.getOrElse {
            return PluginActionResult.Failure("任务数据损坏")
        }
        if (memo.completedAt != null) return PluginActionResult.Refresh("任务已完成")
        val updated = memo.copy(
            completedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        return if (storage.update(COLLECTION, id, json.encodeToString(updated))) {
            PluginActionResult.Refresh(
                message = "已完成",
                undoLabel = "撤销",
                undoAction = PluginAction.Host(ACTION_REOPEN, mapOf("id" to id)),
            )
        } else {
            PluginActionResult.Failure("任务不存在")
        }
    }

    private fun reopen(context: PluginActionContext): PluginActionResult =
        updateMemo(context, "缺少任务 ID") { memo, now ->
            if (memo.completedAt == null) {
                memo to "任务尚未完成"
            } else {
                memo.copy(completedAt = null, updatedAt = now) to "已恢复为待办"
            }
        }

    private fun updateMemo(
        context: PluginActionContext,
        missingMessage: String,
        transform: (MemoEntry, Long) -> Pair<MemoEntry, String>,
    ): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure(missingMessage)
        val existing = storage.get(COLLECTION, id)
            ?: return PluginActionResult.Failure("任务不存在")
        val memo = runCatching {
            json.decodeFromString<MemoEntry>(existing.payload)
        }.getOrElse {
            return PluginActionResult.Failure("任务数据损坏")
        }
        val (updated, message) = transform(memo, System.currentTimeMillis())
        return if (storage.update(COLLECTION, id, json.encodeToString(updated))) {
            PluginActionResult.Refresh(message)
        } else {
            PluginActionResult.Failure("任务不存在")
        }
    }

    private fun incrementMultiplier(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少任务 ID")
        val existing = storage.get(COLLECTION, id)
            ?: return PluginActionResult.Failure("任务不存在")
        val memo = runCatching {
            json.decodeFromString<MemoEntry>(existing.payload)
        }.getOrElse {
            return PluginActionResult.Failure("任务数据损坏")
        }
        if (!memo.isDaily) {
            return PluginActionResult.Failure("只有日常任务可以提升倍率")
        }
        val next = (memo.multiplier + 1).coerceAtMost(99)
        val updated = memo.copy(multiplier = next, updatedAt = System.currentTimeMillis())
        return if (storage.update(COLLECTION, id, json.encodeToString(updated))) {
            PluginActionResult.Refresh("倍率已提升至 ×${next + 1}")
        } else {
            PluginActionResult.Failure("任务不存在")
        }
    }

    private fun restore(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少任务 ID")
        val record = storage.get(TRASH_COLLECTION, id)
            ?: return PluginActionResult.Failure("回收站中不存在该任务")
        val trash = runCatching {
            json.decodeFromString<TrashEntry>(record.payload)
        }.getOrElse {
            return PluginActionResult.Failure("回收站数据损坏")
        }
        val memo = trash.memo.copy(updatedAt = System.currentTimeMillis())
        return try {
            val payload = json.encodeToString(memo)
            if (storage.get(COLLECTION, id) != null) {
                storage.update(COLLECTION, id, payload)
            } else {
                storage.insert(COLLECTION, payload, id)
            }
            storage.delete(TRASH_COLLECTION, id)
            PluginActionResult.Refresh("已恢复到任务列表")
        } catch (error: Exception) {
            PluginActionResult.Failure("恢复失败：${error.message ?: "未知错误"}")
        }
    }

    private fun deleteForever(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val id = context.request.params["id"]?.trim().orEmpty()
        if (id.isBlank()) return PluginActionResult.Failure("缺少任务 ID")
        return if (storage.delete(TRASH_COLLECTION, id)) {
            PluginActionResult.Refresh("已彻底删除")
        } else {
            PluginActionResult.Failure("回收站中不存在该任务")
        }
    }

    private fun emptyTrash(context: PluginActionContext): PluginActionResult {
        val storage = context.storage
            ?: return PluginActionResult.Failure("插件未获得私有存储授权")
        val records = storage.readAllRecords(TRASH_COLLECTION)
        records.forEach { storage.delete(TRASH_COLLECTION, it.id) }
        return PluginActionResult.Refresh("回收站已清空")
    }

    private fun search(context: PluginActionContext): PluginActionResult {
        val query = context.request.params["query"]?.trim().orEmpty()
        if (query.isBlank()) return PluginActionResult.ShowMessage("请输入搜索关键词")
        return PluginActionResult.Navigate(PAGE_SEARCH, mapOf("query" to query))
    }

    private fun setSort(context: PluginActionContext): PluginActionResult =
        PluginActionResult.Navigate(PAGE_LIST, listParams(context.request.params))

    private fun setFilter(context: PluginActionContext): PluginActionResult =
        PluginActionResult.Navigate(PAGE_LIST, listParams(context.request.params))

    private fun loadTasks(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
            )
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        purgeExpiredTrash(storage, now)
        val query = context.request.params["query"]?.trim().orEmpty()
        val sort = normalizeSort(context.request.params["sort"])
        val filter = normalizeFilter(context.request.params["filter"])

        val tasks = storage.readAllRecords(COLLECTION)
            .mapNotNull { record ->
                runCatching { json.decodeFromString<MemoEntry>(record.payload) }.getOrNull()
            }
            .filter { memo -> matchesFilter(memo, filter, today) }
            .filter { memo ->
                query.isBlank() ||
                    memo.title.contains(query, ignoreCase = true) ||
                    memo.body.contains(query, ignoreCase = true)
            }

        val orderedTasks = sortTasks(tasks, sort, today).let { sorted ->
            if (filter == FILTER_TODAY) {
                sorted.sortedBy { taskGroupRank(it, today) }
            } else {
                sorted
            }
        }
        val items = orderedTasks
            .take(PluginDataLimits.MAX_ITEMS)
            .map { toDataItem(it, sort, today, filter) }
        return PluginDataResult(
            items = items,
            emptyTitle = when {
                query.isNotBlank() -> "没有匹配的任务"
                filter == FILTER_TODAY -> "今天没有任务"
                filter != FILTER_ALL -> "当前筛选下没有任务"
                else -> "暂无任务"
            },
            emptyMessage = when {
                query.isNotBlank() -> "尝试更换关键词，或清空搜索查看全部任务。"
                filter == FILTER_TODAY -> "切换“全部”查看其他任务，或新建一个任务。"
                filter != FILTER_ALL -> "切换到“全部”，或新建一个任务。"
                else -> "新建一个任务，设置截止日期，或作为日常任务持续跟进。"
            },
        )
    }

    private fun taskGroupRank(memo: MemoEntry, today: LocalDate): Int = when {
        memo.completedAt != null -> 3
        memo.deadlineDate == today -> 0
        memo.isDaily -> 1
        else -> 2
    }

    private fun searchAll(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val query = context.request.params["query"]?.trim().orEmpty()
        if (query.isBlank()) {
            return PluginDataResult(
                emptyTitle = "输入关键词",
                emptyMessage = "搜索任务标题或正文。",
            )
        }
        val limit = (context.request.params["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 20)
        val today = LocalDate.now()
        val items = storage.readAllRecords(COLLECTION)
            .mapNotNull { record ->
                runCatching { json.decodeFromString<MemoEntry>(record.payload) }.getOrNull()
            }
            .filter { memo ->
                memo.title.contains(query, ignoreCase = true) ||
                    memo.body.contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .map { memo ->
                val item = toDataItem(memo, SORT_RECENT, today, FILTER_ALL)
                item.copy(
                    subtitle = item.subtitle.replace('\n', ' '),
                    groupTitle = "",
                    titleMaxLines = 1,
                    subtitleMaxLines = 1,
                    footer = "",
                    roomy = false,
                    clickAction = item.actions
                        .firstOrNull { it.action is PluginAction.Navigate }
                        ?.action,
                    swipeRightAction = null,
                    swipeLeftAction = null,
                )
            }
        return PluginDataResult(
            items = items,
            emptyTitle = "没有匹配的任务",
            emptyMessage = "尝试更换关键词。",
        )
    }

    private fun loadSummary(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
                isError = true,
            )
        val today = LocalDate.now()
        val tasks = storage.readAllRecords(COLLECTION)
            .mapNotNull { record ->
                runCatching { json.decodeFromString<MemoEntry>(record.payload) }.getOrNull()
            }
        val todayCount = tasks.count { it.completedAt == null && it.deadlineDate == today }
        val dailyCount = tasks.count { it.completedAt == null && it.isDaily }
        val completedCount = tasks.count { it.completedAt != null }
        return PluginDataResult(
            metrics = listOf(
                PluginMetric(label = "今日待办", value = todayCount.toString()),
                PluginMetric(label = "日常任务", value = dailyCount.toString()),
                PluginMetric(label = "已完成", value = completedCount.toString()),
            ),
            emptyTitle = if (tasks.isEmpty()) "暂无任务" else "",
            emptyMessage = if (tasks.isEmpty()) {
                "新建一个任务，设置截止日期，或作为日常任务持续跟进。"
            } else {
                ""
            },
        )
    }

    private fun loadTrash(context: PluginPageDataContext): PluginDataResult {
        val storage = context.storage
            ?: return PluginDataResult(
                emptyTitle = "无法加载",
                emptyMessage = "插件未获得私有存储授权",
            )
        val now = System.currentTimeMillis()
        purgeExpiredTrash(storage, now)
        val items = storage.readAllRecords(TRASH_COLLECTION)
            .mapNotNull { record ->
                runCatching { json.decodeFromString<TrashEntry>(record.payload) }.getOrNull()
            }
            .sortedByDescending { it.deletedAt }
            .take(PluginDataLimits.MAX_ITEMS)
            .map { trash -> toTrashDataItem(trash, now) }
        return PluginDataResult(
            items = items,
            emptyTitle = "回收站为空",
            emptyMessage = "左滑删除的任务会在这里保留 30 天。",
        )
    }

    private fun purgeExpiredTrash(storage: PluginStorage, now: Long) {
        val cutoff = now - TRASH_RETENTION_MILLIS
        storage.readAllRecords(TRASH_COLLECTION).forEach { record ->
            val entry = runCatching {
                json.decodeFromString<TrashEntry>(record.payload)
            }.getOrNull()
            if (entry == null || entry.deletedAt <= cutoff) {
                storage.delete(TRASH_COLLECTION, record.id)
            }
        }
    }

    internal fun urgencyScore(memo: MemoEntry, today: LocalDate): Double {
        val deadline = memo.deadlineDate
        if (deadline == null) {
            return memo.priority.toDouble() * (memo.multiplier + 1)
        }
        val days = ChronoUnit.DAYS.between(today, deadline).toDouble()
        if (days <= 1.0) return Double.POSITIVE_INFINITY
        return memo.priority.toDouble() / (ln(days) / ln(7.0))
    }

    internal fun sortTasks(
        entries: List<MemoEntry>,
        sort: String,
        today: LocalDate,
    ): List<MemoEntry> {
        val completedLast = compareBy<MemoEntry> { it.completedAt != null }
            .thenByDescending { it.completedAt ?: 0L }
        return when (sort) {
            SORT_RECENT -> entries.sortedWith(
                completedLast
                    .thenBy { it.isDaily }
                    .thenBy { it.deadlineDate ?: LocalDate.MAX }
                    .thenByDescending { urgencyScore(it, today) }
                    .thenByDescending { it.updatedAt },
            )

            else -> entries.sortedWith(
                completedLast
                    .thenByDescending { it.pinned }
                    .thenByDescending { urgencyScore(it, today) }
                    .thenBy { it.deadlineDate ?: LocalDate.MAX }
                    .thenByDescending { it.updatedAt },
            )
        }
    }

    private fun toDataItem(
        memo: MemoEntry,
        sort: String,
        today: LocalDate,
        filter: String,
    ): PluginDataItem {
        val completed = memo.completedAt != null
        val deadline = memo.deadlineDate
        val days = deadline?.let { ChronoUnit.DAYS.between(today, it) }
        val meta = when {
            deadline == null && completed -> "日常任务 · 已完成"
            deadline == null && memo.multiplier > 0 -> "日常任务 · 倍率 ×${memo.multiplier + 1}"
            deadline == null -> "日常任务"
            completed -> "截止 ${deadline.format(monthDayFormatter)} · 已完成"
            days != null && days < 0 -> "截止 ${deadline.format(monthDayFormatter)} · 已逾期 ${-days} 天"
            days == 0L -> "截止 ${deadline.format(monthDayFormatter)} · 今天截止"
            else -> "截止 ${deadline.format(monthDayFormatter)} · 还剩 $days 天"
        }
        val preview = memo.body.replace('\n', ' ').trim()
        val subtitle = buildString {
            append(meta)
            if (preview.isNotEmpty()) {
                append('\n')
                append(
                    if (preview.length <= PREVIEW_LENGTH) {
                        preview
                    } else {
                        preview.take(PREVIEW_LENGTH) + "…"
                    },
                )
            }
        }

        val editParams = buildMap {
            put("id", memo.id)
            put("title", memo.title)
            put("body", memo.body)
            put("priority", memo.priority.toString())
            put("emoji", memo.emoji)
            put("filter", filter)
            put("sort", sort)
            memo.deadline?.takeIf { it.isNotBlank() }?.let { put("deadline", it) }
        }
        // 数据源单条最多 3 个动作：编辑 / 完成 / 置顶，倍率提升不占用动作位。
        val actions = buildList {
            add(PluginDataAction("编辑", PluginAction.Navigate(PAGE_EDIT, editParams)))
            if (!completed) {
                add(
                    PluginDataAction(
                        label = "完成",
                        action = PluginAction.Host(ACTION_COMPLETE, mapOf("id" to memo.id)),
                    ),
                )
            }
            add(
                PluginDataAction(
                    label = if (memo.pinned) "取消置顶" else "置顶",
                    action = PluginAction.Host(ACTION_TOGGLE_PIN, mapOf("id" to memo.id)),
                ),
            )
            if (completed) {
                add(
                    PluginDataAction(
                        label = "重新打开",
                        action = PluginAction.Host(ACTION_REOPEN, mapOf("id" to memo.id)),
                    ),
                )
            }
        }

        return PluginDataItem(
            id = memo.id,
            title = memo.title,
            subtitle = subtitle,
            trailing = "",
            leadingText = memo.emoji.ifBlank { DEFAULT_EMOJI },
            groupTitle = when {
                completed -> "已完成"
                filter == FILTER_TODAY && memo.deadlineDate == today -> "今天"
                filter == FILTER_TODAY && memo.isDaily -> "日常"
                sort != SORT_RECENT && memo.pinned -> "置顶"
                else -> "进行中"
            },
            tone = if (!completed && deadline != null && days != null && days <= 1) {
                PluginDataTone.NEGATIVE
            } else {
                PluginDataTone.NEUTRAL
            },
            completed = completed,
            titleMaxLines = 4,
            subtitleMaxLines = 2,
            footer = if (completed) "" else urgencyLabel(memo, today),
            roomy = true,
            leadingAction = if (!completed && memo.isDaily) {
                PluginDataAction(
                    label = "提升倍率",
                    action = PluginAction.Host(
                        ACTION_INCREMENT_MULTIPLIER,
                        mapOf("id" to memo.id),
                    ),
                )
            } else {
                null
            },
            clickAction = if (completed) null else PluginAction.Navigate(PAGE_EDIT, editParams),
            swipeRightAction = if (!completed) {
                PluginDataAction(
                    label = "完成",
                    action = PluginAction.Host(
                        ACTION_COMPLETE,
                        mapOf("id" to memo.id),
                    ),
                )
            } else {
                null
            },
            swipeLeftAction = PluginDataAction(
                label = "删除",
                action = PluginAction.Host(
                    ACTION_DELETE,
                    mapOf("id" to memo.id),
                ),
            ),
            actions = actions,
            actionsInMenu = true,
        )
    }

    private fun toTrashDataItem(trash: TrashEntry, now: Long): PluginDataItem {
        val memo = trash.memo
        val preview = memo.body.replace('\n', ' ').trim()
        val deletedOn = java.time.Instant.ofEpochMilli(trash.deletedAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(trashDateFormatter)
        val remainingDays = (
            (trash.deletedAt + TRASH_RETENTION_MILLIS - now).coerceAtLeast(0) + DAY_MILLIS - 1
            ) / DAY_MILLIS
        val subtitle = buildString {
            append("删除于 $deletedOn · ${remainingDays} 天后清理")
            if (preview.isNotEmpty()) {
                append('\n')
                append(
                    if (preview.length <= PREVIEW_LENGTH) {
                        preview
                    } else {
                        preview.take(PREVIEW_LENGTH) + "…"
                    },
                )
            }
        }

        return PluginDataItem(
            id = memo.id,
            title = memo.title,
            subtitle = subtitle,
            trailing = "",
            leadingText = memo.emoji.ifBlank { DEFAULT_EMOJI },
            titleMaxLines = 2,
            subtitleMaxLines = 2,
            roomy = true,
            actions = listOf(
                PluginDataAction(
                    label = "恢复",
                    action = PluginAction.Host(ACTION_RESTORE, mapOf("id" to memo.id)),
                ),
                PluginDataAction(
                    label = "彻底删除",
                    action = PluginAction.Host(
                        ACTION_DELETE_FOREVER,
                        mapOf("id" to memo.id),
                        confirm = confirmDeleteForever,
                    ),
                ),
            ),
        )
    }

    private fun urgencyLabel(memo: MemoEntry, today: LocalDate): String {
        val score = urgencyScore(memo, today)
        if (score.isInfinite()) return "最高紧急"
        return "紧急 ${formatScore(score)}"
    }

    private fun formatScore(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }

    internal fun parseQuickAdd(raw: String, today: LocalDate): QuickAddResult? {
        var text = raw.trim()
        if (text.isBlank()) return null

        var priority = DEFAULT_PRIORITY
        Regex("""(?i)(?:^|\s)p([1-5])(?=\s|$)""").find(text)?.let { match ->
            priority = match.groupValues[1].toInt()
            text = text.removeRange(match.range).trim()
        }

        val timeRegex = Regex("""\b\d{1,2}:\d{2}\b|\d{1,2}[点时](\d{1,2}分?)?""")
        text = text.replace(timeRegex, " ").replace(Regex("\\s+"), " ").trim()

        var deadline: LocalDate? = null
        Regex("^(今天|今日|明天|明日|后天)").find(text)?.let { match ->
            deadline = when (match.value) {
                "今天", "今日" -> today
                "明天", "明日" -> today.plusDays(1)
                else -> today.plusDays(2)
            }
            text = text.removeRange(match.range).trim()
        }

        if (deadline == null) {
            Regex("^下(?:周|星期|礼拜)([一二三四五六日天])").find(text)?.let { match ->
                val day = parseDayOfWeek(match.groupValues[1]) ?: return@let
                deadline = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    .plusDays(day.value - 1L)
                text = text.removeRange(match.range).trim()
            }
        }

        if (deadline == null) {
            Regex("^(?:周|星期|礼拜)([一二三四五六日天])").find(text)?.let { match ->
                val day = parseDayOfWeek(match.groupValues[1]) ?: return@let
                deadline = today.with(TemporalAdjusters.nextOrSame(day))
                text = text.removeRange(match.range).trim()
            }
        }

        if (deadline == null) {
            Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""").find(text)?.let { match ->
                val parsed = runCatching {
                    LocalDate.of(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                    )
                }.getOrNull()
                if (parsed != null) {
                    deadline = parsed
                    text = text.removeRange(match.range).trim()
                }
            }
        }

        if (deadline == null) {
            Regex("""^(\d{1,2})月(\d{1,2})[日号]?""").find(text)?.let { match ->
                val parsed = resolveMonthDay(
                    today,
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                )
                if (parsed != null) {
                    deadline = parsed
                    text = text.removeRange(match.range).trim()
                }
            }
        }

        if (deadline == null) {
            Regex("""^(\d{1,2})-(\d{1,2})(?=\s|$)""").find(text)?.let { match ->
                val parsed = resolveMonthDay(
                    today,
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                )
                if (parsed != null) {
                    deadline = parsed
                    text = text.removeRange(match.range).trim()
                }
            }
        }

        val title = text.replace(Regex("\\s+"), " ").trim().take(100)
        if (title.isBlank()) return null
        return QuickAddResult(title = title, deadline = deadline, priority = priority)
    }

    private fun parseDayOfWeek(value: String): DayOfWeek? = when (value) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        "日", "天" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun resolveMonthDay(today: LocalDate, month: Int, day: Int): LocalDate? =
        runCatching {
            val candidate = LocalDate.of(today.year, month, day)
            if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
        }.getOrNull()

    private fun normalizeSort(value: String?): String =
        if (value == SORT_RECENT) SORT_RECENT else SORT_URGENCY

    private fun normalizeFilter(value: String?): String = when (value) {
        FILTER_ALL, FILTER_TODAY, FILTER_WEEK, FILTER_OVERDUE, FILTER_DAILY -> value
        else -> FILTER_ALL
    }

    private fun matchesFilter(memo: MemoEntry, filter: String, today: LocalDate): Boolean {
        val deadline = memo.deadlineDate
        return when (filter) {
            FILTER_TODAY -> deadline == today || memo.isDaily
            FILTER_WEEK -> deadline != null &&
                !deadline.isBefore(today) &&
                ChronoUnit.DAYS.between(today, deadline) <= 7
            FILTER_OVERDUE -> deadline != null && deadline.isBefore(today)
            FILTER_DAILY -> memo.isDaily
            else -> true
        }
    }

    private fun listParams(params: Map<String, String>): Map<String, String> = buildMap {
        put("sort", normalizeSort(params["sort"]))
        put("filter", normalizeFilter(params["filter"]))
        params["query"]?.trim()?.takeIf { it.isNotBlank() }?.let { put("query", it) }
    }
}
