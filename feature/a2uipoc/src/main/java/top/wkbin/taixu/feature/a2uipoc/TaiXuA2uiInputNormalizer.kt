package top.wkbin.taixu.feature.a2uipoc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 值型组件（TextField/CheckBox/ChoicePicker/Slider/DateTimeInput）的「常量 value」归一化。
 *
 * 背景（源码级已核实）：官方 A2uiBasicCatalogV1 的这五类组件都用
 * `val onValueChange = properties.bindUpdater(ValueProperty)`、`val isEnabled = onValueChange != null`
 * 判定可交互性；而 `A2uiComponentScopeImpl.bindUpdater` 只在 `value` 形如 path 绑定时才返回可写
 * updater，写常量则返回 null → 组件被**静默禁用**（能渲染、不能输入、不回传、无提示）。
 *
 * 本归一化器把常量 value 改写为数据绑定 `{"path": "/__taixu_inputs/<组件id>"}`，
 * 并在**引用它的 updateComponents 之前**插入 updateDataModel 把原常量种进数据模型。
 * 顺序至关重要：CheckBox/Slider 用 `checkNotNull(properties.bind(ValueProperty))` 取值，
 * 路径若尚未种值会解析为 null → 组件当场抛错而**完全不渲染**（TextField 无 checkNotNull，
 * 所以历史上只有它看起来是好的）；种子必须先生效。
 *
 * 幂等：已是数据绑定的 value 不改写，重复归一化结果一致。
 */
internal object TaiXuA2uiInputNormalizer {

    /** 归一化后输入值所在的数据模型路径前缀（JSON Pointer）。 */
    const val PROXY_PATH_PREFIX = "/__taixu_inputs/"

    private val VALUE_COMPONENT_TYPES =
        setOf("TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput")

    data class Result(
        val messagesJson: String,
        val paths: Map<String, Map<String, String>>,
        val fixedCount: Int,
        val dataModelForced: Boolean,
    )

    fun normalize(messagesJson: String): Result {
        val array = Json.parseToJsonElement(messagesJson).jsonArray
        val paths = mutableMapOf<String, Map<String, String>>()
        var fixedCount = 0
        var dataModelForced = false

        val rewritten = array.flatMap { element ->
            val message = element.jsonObject.toMutableMap()
            val seeds = mutableListOf<JsonElement>()

            // sendDataModel 默认关闭 → 出站事件不带数据模型，用户输入的值就回不到智能体。
            // 打开后，用户与卡片的任何交互都会把整棵数据模型（含输入值）附在事件里带回。
            message["createSurface"]?.jsonObject?.let { surface ->
                if (surface["sendDataModel"]?.jsonPrimitive?.booleanOrNull != true) {
                    message["createSurface"] = JsonObject(surface + ("sendDataModel" to JsonPrimitive(true)))
                    dataModelForced = true
                }
            }

            val update = message["updateComponents"]?.jsonObject?.toMutableMap()
                ?: return@flatMap listOf(JsonObject(message))
            val surfaceId = update["surfaceId"]?.jsonPrimitive?.contentOrNull
                ?: return@flatMap listOf(JsonObject(message))
            val components = update["components"]?.jsonArray
                ?: return@flatMap listOf(JsonObject(message))

            val perSurface = mutableMapOf<String, String>()
            val normalized = components.map { component ->
                val obj = component.jsonObject.toMutableMap()
                val type = obj["component"]?.jsonPrimitive?.contentOrNull
                val constant = obj["value"]
                if (type == null || type !in VALUE_COMPONENT_TYPES) return@map component
                if (constant == null || (constant is JsonObject && constant.containsKey("path"))) {
                    return@map component
                }
                val componentId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@map component
                val path = PROXY_PATH_PREFIX + componentId
                obj["value"] = JsonObject(mapOf("path" to JsonPrimitive(path)))
                perSurface[componentId] = path
                seeds.add(
                    JsonObject(
                        mapOf(
                            "updateDataModel" to
                                JsonObject(
                                    mapOf(
                                        "surfaceId" to JsonPrimitive(surfaceId),
                                        "path" to JsonPrimitive(path),
                                        "value" to constant,
                                    )
                                )
                        )
                    )
                )
                fixedCount++
                JsonObject(obj)
            }

            if (perSurface.isEmpty()) return@flatMap listOf(JsonObject(message))
            update["components"] = JsonArray(normalized)
            message["updateComponents"] = JsonObject(update)
            paths[surfaceId] = perSurface
            // 种子必须先于引用它的 updateComponents 生效，否则组件取值拿到 null。
            seeds + JsonObject(message)
        }

        return Result(JsonArray(rewritten).toString(), paths, fixedCount, dataModelForced)
    }
}
