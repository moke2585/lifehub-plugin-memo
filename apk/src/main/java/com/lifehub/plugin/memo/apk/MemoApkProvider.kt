package com.lifehub.plugin.memo.apk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.lifehub.app.plugins.MemoPlugin
import com.lifehub.pluginapi.PluginActionContext
import com.lifehub.pluginapi.PluginActionRequest
import com.lifehub.pluginapi.PluginApkProtocol
import com.lifehub.pluginapi.PluginPageDataContext
import com.lifehub.pluginapi.PluginPageDataRequest
import com.lifehub.pluginapi.PluginPathRules
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 任务清单 APK 插件的 Binder 入口。
 *
 * 宿主只通过 JSON 调用 provider；页面仍由宿主渲染，插件不包含 Compose。
 */
class MemoApkProvider : ContentProvider() {

    private lateinit var storage: MemoApkStorage
    private val json = PluginApkProtocol.json

    override fun onCreate(): Boolean {
        storage = MemoApkStorage(requireNotNull(context))
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!isTrustedCaller()) {
            return errorBundle("未授权的调用方")
        }
        return try {
            when (method) {
                PluginApkProtocol.METHOD_VERSION -> Bundle().apply {
                    putInt(PluginApkProtocol.KEY_VERSION, PluginApkProtocol.VERSION)
                }

                PluginApkProtocol.METHOD_MANIFEST -> jsonBundle(readAsset(MANIFEST_FILE))

                PluginApkProtocol.METHOD_PAGE -> {
                    val path = arg ?: return errorBundle("缺少页面路径")
                    if (!PluginPathRules.isSafeRelativePath(path)) {
                        return errorBundle("页面路径不安全：$path")
                    }
                    jsonBundle(readAsset(path))
                }

                PluginApkProtocol.METHOD_ACTION -> {
                    val request = json.decodeFromString<PluginActionRequest>(
                        arg ?: return errorBundle("缺少 action 请求"),
                    )
                    if (request.pluginId != MemoPlugin.PLUGIN_ID) {
                        return errorBundle("插件 ID 不匹配：${request.pluginId}")
                    }
                    val handler = MemoPlugin.actionHandlers[request.actionId]
                        ?: return errorBundle("未注册的插件动作：${request.actionId}")
                    val result = handler.handle(
                        PluginActionContext(
                            request = request,
                            storage = storage,
                            assetReader = { path ->
                                runCatching { readAsset(path) }.getOrNull()
                            },
                        ),
                    )
                    jsonBundle(json.encodeToString(result))
                }

                PluginApkProtocol.METHOD_DATA -> {
                    val request = json.decodeFromString<PluginPageDataRequest>(
                        arg ?: return errorBundle("缺少数据请求"),
                    )
                    if (request.pluginId != MemoPlugin.PLUGIN_ID) {
                        return errorBundle("插件 ID 不匹配：${request.pluginId}")
                    }
                    val handler = MemoPlugin.dataHandlers["${request.pluginId}/${request.sourceId}"]
                        ?: return errorBundle("未注册的数据源：${request.sourceId}")
                    val result = handler.load(
                        PluginPageDataContext(
                            request = request,
                            storage = storage,
                            assetReader = { path ->
                                runCatching { readAsset(path) }.getOrNull()
                            },
                        ),
                    )
                    jsonBundle(json.encodeToString(result))
                }

                PluginApkProtocol.METHOD_EXPORT_BACKUP -> jsonBundle(storage.exportSnapshot())

                PluginApkProtocol.METHOD_IMPORT_BACKUP -> {
                    val count = storage.importSnapshot(
                        arg ?: return errorBundle("缺少备份数据"),
                    )
                    Bundle().apply {
                        putBoolean(PluginApkProtocol.KEY_OK, true)
                        putInt(KEY_RECORD_COUNT, count)
                    }
                }

                else -> errorBundle("未知 provider 方法：$method")
            }
        } catch (error: Exception) {
            errorBundle(error.message ?: "插件执行失败")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun readAsset(path: String): String {
        require(PluginPathRules.isSafeRelativePath(path)) { "插件资产路径不安全：$path" }
        return requireNotNull(context).assets
            .open("plugins/${MemoPlugin.PLUGIN_ID}/$path")
            .bufferedReader()
            .use { it.readText() }
    }

    private fun jsonBundle(value: String): Bundle {
        val bytes = value.toByteArray(Charsets.UTF_8).size
        if (bytes > PluginApkProtocol.MAX_PAYLOAD_BYTES) {
            return errorBundle("响应 $bytes 字节超过 Binder 负载上限")
        }
        return Bundle().apply {
            putBoolean(PluginApkProtocol.KEY_OK, true)
            putString(PluginApkProtocol.KEY_JSON, value)
        }
    }

    private fun errorBundle(message: String): Bundle = Bundle().apply {
        putBoolean(PluginApkProtocol.KEY_OK, false)
        putString(PluginApkProtocol.KEY_ERROR, message)
    }

    private fun isTrustedCaller(): Boolean {
        val caller = callingPackage
        if (caller == HOST_PACKAGE || caller == HOST_DEBUG_PACKAGE) return true
        return Binder.getCallingUid() == Process.myUid()
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val KEY_RECORD_COUNT = "recordCount"
        const val HOST_PACKAGE = "com.lifehub.app"
        const val HOST_DEBUG_PACKAGE = "com.lifehub.app.debug"
    }
}
