package com.checky.app.debug

import com.checky.app.domain.AuthHealth
import org.json.JSONArray
import org.json.JSONObject

internal enum class SafeJsonType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL, UNKNOWN }

internal data class SafeFieldShape(val name: String, val type: SafeJsonType)

internal data class SafeArrayShape(
    val key: String,
    val length: Int,
    val firstItemFields: List<SafeFieldShape>,
    val taskKeys: List<String> = emptyList()
)

internal data class SafeSchemaObservation(
    val endpoint: String,
    val httpStatus: Int,
    val businessCode: Int,
    val topLevelFields: List<SafeFieldShape>,
    val dataType: SafeJsonType,
    val dataFields: List<SafeFieldShape>,
    val dataArrayLength: Int?,
    val dataArrayFirstItemFields: List<SafeFieldShape>,
    val nestedArrays: List<SafeArrayShape>
) {
    fun render(): String = buildString {
        append(endpoint).append(" http=").append(httpStatus)
            .append(" code=").append(businessCode)
            .append(" top=").append(renderFields(topLevelFields))
            .append(" dataType=").append(dataType)
        if (dataArrayLength != null) {
            append(" dataLength=").append(dataArrayLength)
                .append(" dataFirst=").append(renderFields(dataArrayFirstItemFields))
        } else {
            append(" data=").append(renderFields(dataFields))
        }
        if (nestedArrays.isNotEmpty()) append(" nested=").append(
            nestedArrays.joinToString(prefix = "[", postfix = "]") {
                "${it.key}{length=${it.length},first=${renderFields(it.firstItemFields)}" +
                    it.taskKeys.takeIf { keys -> keys.isNotEmpty() }
                        ?.let { keys -> ",taskKeys=$keys" }.orEmpty() + "}"
            }
        )
    }
}

internal data class CredentialContinuityDiagnostic(
    val label: String,
    val entryExists: Boolean,
    val decryptable: Boolean,
    val authHealth: AuthHealth,
    val hasAuthHealthRecord: Boolean
) {
    fun render() = "$label exists=$entryExists decryptable=$decryptable " +
        "authHealth=$authHealth authHealthRecord=$hasAuthHealthRecord"
}

internal fun observeTaygedoSchema(
    endpoint: String,
    httpStatus: Int,
    businessCode: Int,
    json: JSONObject
): SafeSchemaObservation {
    val dataValue = if (json.has("data")) json.opt("data") else null
    val dataArray = dataValue as? JSONArray
    val dataObject = dataValue as? JSONObject
    val nested = dataObject?.let { objectValue ->
        STRUCTURAL_ARRAY_KEYS.mapNotNull { key ->
            (objectValue.opt(key) as? JSONArray)?.let { array ->
                SafeArrayShape(
                    key = key,
                    length = array.length(),
                    firstItemFields = (array.opt(0) as? JSONObject)?.let(::fields).orEmpty(),
                    taskKeys = if (key == "task_list3") safeTaskKeys(array) else emptyList()
                )
            }
        }
    }.orEmpty()
    return SafeSchemaObservation(
        endpoint = endpoint,
        httpStatus = httpStatus,
        businessCode = businessCode,
        topLevelFields = fields(json),
        dataType = safeType(dataValue),
        dataFields = dataObject?.let(::fields).orEmpty(),
        dataArrayLength = dataArray?.length(),
        dataArrayFirstItemFields = (dataArray?.opt(0) as? JSONObject)?.let(::fields).orEmpty(),
        nestedArrays = nested
    )
}

private fun safeTaskKeys(array: JSONArray): List<String> = buildList {
    for (index in 0 until array.length()) {
        val key = (array.optJSONObject(index)?.opt("taskKey") as? String)?.trim()
        if (key != null && TASK_KEY_PATTERN.matches(key)) add(key)
    }
}

private fun fields(value: JSONObject): List<SafeFieldShape> = value.keys().asSequence()
    .filter { it.isNotBlank() && it.length <= MAX_KEY_LENGTH && !isSensitiveKey(it) }
    .sorted()
    .map { key -> SafeFieldShape(key, safeType(value.opt(key))) }
    .take(MAX_FIELDS)
    .toList()

private fun safeType(value: Any?): SafeJsonType = when (value) {
    is JSONObject -> SafeJsonType.OBJECT
    is JSONArray -> SafeJsonType.ARRAY
    is String -> SafeJsonType.STRING
    is Number -> SafeJsonType.NUMBER
    is Boolean -> SafeJsonType.BOOLEAN
    null, JSONObject.NULL -> SafeJsonType.NULL
    else -> SafeJsonType.UNKNOWN
}

private fun renderFields(fields: List<SafeFieldShape>) =
    fields.joinToString(prefix = "[", postfix = "]") { "${it.name}:${it.type}" }

private fun isSensitiveKey(key: String): Boolean =
    SENSITIVE_KEY_MARKERS.any { key.contains(it, ignoreCase = true) }

private const val MAX_FIELDS = 40
private const val MAX_KEY_LENGTH = 80
private val SENSITIVE_KEY_MARKERS = setOf(
    "token", "cookie", "auth", "session", "uid", "user", "account", "role",
    "device", "identity", "password", "credential", "secret", "header"
)
private val STRUCTURAL_ARRAY_KEYS = setOf("task_list3", "rewards", "reward", "list", "posts", "items")
private val TASK_KEY_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
