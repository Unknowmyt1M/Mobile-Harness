package com.jarves.mh.runtime.tool

import android.content.Context
import com.jarves.mh.policy.AgentPolicyEngine
import com.jarves.mh.runtime.PermissionManager
import com.jarves.mh.runtime.QuestionManager
import org.json.JSONArray
import org.json.JSONObject

interface AgentTool {
    val name: String
    val description: String
    val parametersSchema: JSONObject
    
    suspend fun execute(sessionId: String, projectId: String?, arguments: JSONObject): ToolExecutionResult
}

data class ToolExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val isPermissionRequired: Boolean = false,
    val permissionRequestId: String? = null,
)

class ToolRegistry(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val questionManager: QuestionManager,
    private val policyEngine: AgentPolicyEngine,
) {
    private val tools = mutableMapOf<String, AgentTool>()

    init {
        register(BashTool(context, permissionManager, policyEngine))
        register(ReadFileTool(context, policyEngine))
        register(WriteFileTool(context, permissionManager, policyEngine))
        register(EditFileTool(context, permissionManager, policyEngine))
        register(ListDirectoryTool(context))
        register(AskQuestionTool(questionManager))
        register(RequestPermissionTool(permissionManager))
    }

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): AgentTool? = tools[name]

    fun getAllTools(): List<AgentTool> = tools.values.toList()

    fun toOpenAIToolsJson(): JSONArray {
        val array = JSONArray()
        for (tool in tools.values) {
            val fn = JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parametersSchema)
            }
            array.put(JSONObject().apply {
                put("type", "function")
                put("function", fn)
            })
        }
        return array
    }
}
