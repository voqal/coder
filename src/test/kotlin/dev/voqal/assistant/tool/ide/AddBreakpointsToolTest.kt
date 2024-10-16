package dev.voqal.assistant.tool.ide

import com.aallam.openai.api.chat.ToolCall
import com.intellij.lang.Language
import dev.voqal.JBTest
import dev.voqal.assistant.VoqalDirective
import dev.voqal.assistant.context.AssistantContext
import dev.voqal.assistant.context.DeveloperContext
import dev.voqal.assistant.context.IdeContext
import dev.voqal.assistant.context.code.ViewingCode
import dev.voqal.assistant.tool.code.CreateClassTool.Companion.getFileExtensionForLanguage
import dev.voqal.config.settings.PromptSettings
import io.vertx.core.json.JsonObject
import java.io.File

class AddBreakpointsToolTest : JBTest() {

    fun `test add by line number`() {
        if (System.getenv("VQL_LANG") !in setOf(null, "JAVA")) {
            log.info("Ignoring java test in non-java mode")
            return
        }
        val lang = Language.findLanguageByID(System.getenv("VQL_LANG") ?: "JAVA")!!
        log.info("Testing language: $lang")

        val fileExt = getFileExtensionForLanguage(lang)
        val addMethodFile = File("src/test/resources/$fileExt/AddMethod.$fileExt")
        val addMethodCode = addMethodFile.readText()
        val transcription = "Add a breakpoint to line 3"
        val directive = VoqalDirective(
            assistant = AssistantContext(
                memorySlice = getMemorySystem().getMemorySlice(),
                availableActions = listOf(AddBreakpointsTool()),
                promptSettings = PromptSettings(promptName = "Idle Mode"),
                languageModelSettings = TEST_CONFIG.languageModelsSettings.models.first()
            ),
            ide = IdeContext(project),
            developer = DeveloperContext(
                transcription = transcription,
                viewingCode = ViewingCode(addMethodCode)
            )
        )
        val response = launchAndReturn { directive.assistant.memorySlice.addMessage(directive) }

        assertEquals(response.toString(), 1, response.toolCalls.size)
        val toolCall = response.toolCalls[0] as ToolCall.Function
        val functionCall = toolCall.function
        assertEquals(AddBreakpointsTool.NAME, functionCall.name)

        val json = JsonObject(functionCall.arguments)
        val lineNumbers = json.getJsonArray("line_numbers").map { it.toString().toInt() }
        assertEquals(listOf(3), lineNumbers)
    }

    fun `test add to print lines`() {
        if (System.getenv("VQL_LANG") !in setOf(null, "JAVA")) {
            log.info("Ignoring java test in non-java mode")
            return
        }
        val lang = Language.findLanguageByID(System.getenv("VQL_LANG") ?: "JAVA")!!
        log.info("Testing language: $lang")

        val fileExt = getFileExtensionForLanguage(lang)
        val addMethodFile = File("src/test/resources/java/AddBreakpoints.$fileExt")
        val addMethodCode = addMethodFile.readText()
        val transcription = "Add breakpoints to the print lines"
        val directive = VoqalDirective(
            assistant = AssistantContext(
                memorySlice = getMemorySystem().getMemorySlice(),
                availableActions = listOf(AddBreakpointsTool()),
                promptSettings = PromptSettings(promptName = "Idle Mode"),
                languageModelSettings = TEST_CONFIG.languageModelsSettings.models.first()
            ),
            ide = IdeContext(project),
            developer = DeveloperContext(
                transcription = transcription,
                viewingCode = ViewingCode(addMethodCode)
            )
        )
        val response = launchAndReturn { directive.assistant.memorySlice.addMessage(directive) }

        if (response.toolCalls.size == 1) {
            assertEquals(response.toString(), 1, response.toolCalls.size)
            val toolCall = response.toolCalls[0] as ToolCall.Function
            val functionCall = toolCall.function
            assertEquals(AddBreakpointsTool.NAME, functionCall.name)

            val json = JsonObject(functionCall.arguments)
            val lineNumbers = json.getJsonArray("line_numbers").map { it.toString().toInt() }
            assertEquals(listOf(4, 10), lineNumbers)
        } else {
            assertEquals(response.toString(), 2, response.toolCalls.size)
            assertTrue(response.toString(), response.toolCalls.map { it as ToolCall.Function }.all {
                it.function.name == AddBreakpointsTool.NAME
            })

            val lineNumbers = response.toolCalls.flatMap {
                JsonObject((it as ToolCall.Function).function.arguments).getJsonArray("line_numbers").map {
                    it.toString().toInt()
                }
            }
            assertEquals(listOf(4, 10), lineNumbers)
        }
    }
}
