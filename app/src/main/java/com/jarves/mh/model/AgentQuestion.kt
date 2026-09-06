package com.jarves.mh.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class QuestionType {
    SELECT_ONE,
    SELECT_MULTIPLE,
    YES_NO,
    TEXT,
    NUMBER,
    PATH,
    CONFIRMATION,
}

enum class QuestionStatus {
    PENDING,
    ANSWERED,
    EXPIRED,
    CANCELLED,
}

data class QuestionOption(
    val id: String,
    val label: String,
    val description: String = "",
    val isRecommended: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("description", description)
        put("isRecommended", isRecommended)
    }

    companion object {
        fun fromJson(json: JSONObject): QuestionOption = QuestionOption(
            id = json.optString("id", UUID.randomUUID().toString()),
            label = json.optString("label", ""),
            description = json.optString("description", ""),
            isRecommended = json.optBoolean("isRecommended", false),
        )
    }
}

enum class ResolutionPolicy {
    USER_REQUIRED,
    AUTO_RESOLVE,
}

data class AgentQuestion(
    val id: String = "q_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val sessionId: String,
    val type: QuestionType,
    val title: String = "Question from Agent",
    val question: String,
    val options: List<QuestionOption> = emptyList(),
    val required: Boolean = true,
    val defaultOptionId: String? = null,
    val allowCustomAnswer: Boolean = true,
    val resolutionPolicy: ResolutionPolicy = ResolutionPolicy.USER_REQUIRED,
    val autoResolveTimeoutSeconds: Int = 300, // 5 minutes default when AUTO_RESOLVE
    val status: QuestionStatus = QuestionStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("taskId", taskId)
        put("sessionId", sessionId)
        put("type", type.name)
        put("title", title)
        put("question", question)
        val optsArray = JSONArray()
        options.forEach { optsArray.put(it.toJson()) }
        put("options", optsArray)
        put("required", required)
        defaultOptionId?.let { put("defaultOptionId", it) }
        put("allowCustomAnswer", allowCustomAnswer)
        put("resolutionPolicy", resolutionPolicy.name)
        put("autoResolveTimeoutSeconds", autoResolveTimeoutSeconds)
        put("status", status.name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): AgentQuestion {
            val optionsList = mutableListOf<QuestionOption>()
            json.optJSONArray("options")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { optionsList.add(QuestionOption.fromJson(it)) }
                }
            }
            return AgentQuestion(
                id = json.optString("id", "q_${System.currentTimeMillis()}"),
                taskId = json.optString("taskId", ""),
                sessionId = json.optString("sessionId", ""),
                type = runCatching { QuestionType.valueOf(json.optString("type")) }.getOrDefault(QuestionType.SELECT_ONE),
                title = json.optString("title", "Question from Agent"),
                question = json.optString("question", ""),
                options = optionsList,
                required = json.optBoolean("required", true),
                defaultOptionId = json.optString("defaultOptionId").takeIf { it.isNotBlank() },
                allowCustomAnswer = json.optBoolean("allowCustomAnswer", true),
                resolutionPolicy = runCatching { ResolutionPolicy.valueOf(json.optString("resolutionPolicy")) }.getOrDefault(ResolutionPolicy.USER_REQUIRED),
                autoResolveTimeoutSeconds = json.optInt("autoResolveTimeoutSeconds", 300),
                status = runCatching { QuestionStatus.valueOf(json.optString("status")) }.getOrDefault(QuestionStatus.PENDING),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}

data class AgentAnswer(
    val questionId: String,
    val taskId: String,
    val selectedOptionIds: List<String> = emptyList(),
    val textValue: String? = null,
    val isCustom: Boolean = false,
    val answeredAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("questionId", questionId)
        put("taskId", taskId)
        val selArray = JSONArray()
        selectedOptionIds.forEach { selArray.put(it) }
        put("selectedOptionIds", selArray)
        textValue?.let { put("textValue", it) }
        put("isCustom", isCustom)
        put("answeredAt", answeredAt)
    }

    companion object {
        fun fromJson(json: JSONObject): AgentAnswer {
            val selected = mutableListOf<String>()
            json.optJSONArray("selectedOptionIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i)
                    if (item.isNotBlank()) selected.add(item)
                }
            }
            return AgentAnswer(
                questionId = json.optString("questionId", ""),
                taskId = json.optString("taskId", ""),
                selectedOptionIds = selected,
                textValue = json.optString("textValue").takeIf { it.isNotBlank() },
                isCustom = json.optBoolean("isCustom", false),
                answeredAt = json.optLong("answeredAt", System.currentTimeMillis()),
            )
        }
    }
}
