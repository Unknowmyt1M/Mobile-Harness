package com.jarves.mh.mcp

import com.jarves.mh.model.RiskLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var registry: McpRegistry

    @Before
    fun setUp() {
        registry = McpRegistry()
    }

    @Test
    fun `registers server and redacts sensitive tokens in env and args`() {
        val server = McpServerConfig(
            id = "sqlite-mcp",
            name = "SQLite Inspector",
            transport = McpTransport.STDIO,
            command = "node",
            args = listOf("server.js", "--key=sk-ant-api03-1234567890abcdef1234567890abcdef-AA"),
            env = mapOf("AUTH_TOKEN" to "Bearer eyJhbGciOiJIUzI1NiJ9.testToken"),
        )
        registry.registerServer(server)

        val retrieved = registry.getServer("sqlite-mcp")!!
        assertFalse(retrieved.args[1].contains("1234567890abcdef"))
        assertTrue(retrieved.args[1].contains("sk-••••"))
        assertFalse(retrieved.env["AUTH_TOKEN"]!!.contains("testToken"))
        assertTrue(retrieved.env["AUTH_TOKEN"]!!.contains("Bearer ••••"))
    }

    @Test
    fun `evaluates tool safety tiers correctly`() {
        // Read tools -> SAFE
        assertEquals(RiskLevel.SAFE, registry.evaluateToolSafety("read_query"))
        assertEquals(RiskLevel.SAFE, registry.evaluateToolSafety("list_tables"))
        assertEquals(RiskLevel.SAFE, registry.evaluateToolSafety("get_schema"))

        // Mutating tools -> REVIEW
        assertEquals(RiskLevel.REVIEW, registry.evaluateToolSafety("write_record"))
        assertEquals(RiskLevel.REVIEW, registry.evaluateToolSafety("execute_migration"))
        assertEquals(RiskLevel.REVIEW, registry.evaluateToolSafety("delete_row"))

        // Destructive tools -> BLOCKED
        assertEquals(RiskLevel.BLOCKED, registry.evaluateToolSafety("drop_database"))
        assertEquals(RiskLevel.BLOCKED, registry.evaluateToolSafety("rm_rf_workspace"))
    }

    @Test
    fun `saves and loads servers from file`() {
        val configFile = File(tempFolder.newFolder("mcp_conf"), "mcp-servers.json")
        registry.registerServer(
            McpServerConfig(
                id = "git-mcp",
                name = "Git Tools",
                command = "python",
                args = listOf("mcp_git.py"),
            )
        )

        registry.saveToFile(configFile)
        assertTrue(configFile.exists())

        val newRegistry = McpRegistry()
        newRegistry.loadFromFile(configFile)
        val loaded = newRegistry.getServer("git-mcp")
        assertEquals("Git Tools", loaded?.name)
        assertEquals("python", loaded?.command)
    }

    @Test
    fun `formats json-rpc requests properly`() {
        val initReq = McpClient.createInitializeRequest(1)
        val initJson = JSONObject(initReq)
        assertEquals("2.0", initJson.getString("jsonrpc"))
        assertEquals(1, initJson.getInt("id"))
        assertEquals("initialize", initJson.getString("method"))

        val callReq = McpClient.createCallToolRequest(
            id = 5,
            toolName = "read_file",
            arguments = JSONObject().put("path", "App.kt")
        )
        val callJson = JSONObject(callReq)
        assertEquals("tools/call", callJson.getString("method"))
        assertEquals("read_file", callJson.getJSONObject("params").getString("name"))
        assertEquals("App.kt", callJson.getJSONObject("params").getJSONObject("arguments").getString("path"))
    }

    @Test
    fun `parses list tools response and tool call response`() {
        val listResp = """
            {
                "jsonrpc": "2.0",
                "id": 2,
                "result": {
                    "tools": [
                        {
                            "name": "search_code",
                            "description": "Searches repository code",
                            "inputSchema": {"type": "object"}
                        }
                    ]
                }
            }
        """.trimIndent()

        val tools = McpClient.parseListToolsResponse(listResp)
        assertEquals(1, tools.size)
        assertEquals("search_code", tools[0].name)
        assertEquals("Searches repository code", tools[0].description)

        val callResp = """
            {
                "jsonrpc": "2.0",
                "id": 3,
                "result": {
                    "isError": false,
                    "content": [
                        {"type": "text", "text": "Found secret sk-ant-api03-1234567890abcdef1234567890abcdef in config"}
                    ]
                }
            }
        """.trimIndent()

        val callResult = McpClient.parseCallToolResponse(callResp)
        assertFalse(callResult.isError)
        assertFalse(callResult.content.contains("1234567890abcdef"))
        assertTrue(callResult.content.contains("sk-••••"))
    }
}
