package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQuestionTest {

    @Test
    fun serializesAndDeserializesSelectOneQuestion() {
        val options = listOf(
            QuestionOption(id = "sqlite", label = "SQLite", description = "Embedded local database", isRecommended = true),
            QuestionOption(id = "postgres", label = "PostgreSQL", description = "Network database", isRecommended = false),
        )
        val question = AgentQuestion(
            id = "q_1001",
            taskId = "task_99",
            sessionId = "session_88",
            type = QuestionType.SELECT_ONE,
            title = "Database Selection",
            question = "Which database engine would you prefer?",
            options = options,
            required = true,
            defaultOptionId = "sqlite",
            allowCustomAnswer = true,
        )

        val json = question.toJson()
        val restored = AgentQuestion.fromJson(json)

        assertEquals(question.id, restored.id)
        assertEquals(question.taskId, restored.taskId)
        assertEquals(question.type, restored.type)
        assertEquals(question.title, restored.title)
        assertEquals(question.question, restored.question)
        assertEquals(2, restored.options.size)
        assertEquals("sqlite", restored.options[0].id)
        assertTrue(restored.options[0].isRecommended)
        assertEquals("postgres", restored.options[1].id)
        assertFalse(restored.options[1].isRecommended)
        assertTrue(restored.allowCustomAnswer)
    }

    @Test
    fun serializesAndDeserializesCustomAnswer() {
        val answer = AgentAnswer(
            questionId = "q_1001",
            taskId = "task_99",
            selectedOptionIds = emptyList(),
            textValue = "DuckDB embedded",
            isCustom = true,
        )

        val json = answer.toJson()
        val restored = AgentAnswer.fromJson(json)

        assertEquals("q_1001", restored.questionId)
        assertEquals("task_99", restored.taskId)
        assertTrue(restored.selectedOptionIds.isEmpty())
        assertEquals("DuckDB embedded", restored.textValue)
        assertTrue(restored.isCustom)
    }

    @Test
    fun serializesAndDeserializesMultipleChoiceAnswer() {
        val answer = AgentAnswer(
            questionId = "q_2002",
            taskId = "task_100",
            selectedOptionIds = listOf("auth", "storage", "analytics"),
            textValue = null,
            isCustom = false,
        )

        val json = answer.toJson()
        val restored = AgentAnswer.fromJson(json)

        assertEquals(3, restored.selectedOptionIds.size)
        assertTrue(restored.selectedOptionIds.contains("auth"))
        assertTrue(restored.selectedOptionIds.contains("storage"))
        assertTrue(restored.selectedOptionIds.contains("analytics"))
        assertFalse(restored.isCustom)
    }
}
