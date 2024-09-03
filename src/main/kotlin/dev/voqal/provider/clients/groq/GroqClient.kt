package dev.voqal.provider.clients.groq

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.exception.*
import com.intellij.openapi.project.Project
import dev.voqal.assistant.VoqalDirective
import dev.voqal.provider.LlmProvider
import dev.voqal.services.getVoqalLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class GroqClient(
    override val name: String,
    private val project: Project,
    private val providerKey: String
) : LlmProvider {

    companion object {
        const val DEFAULT_MODEL = "llama-3.1-70b-versatile"

        @JvmStatic
        val MODELS = listOf(
            "llama-3.1-405b-reasoning",
            "llama-3.1-70b-versatile",
            "llama-3.1-8b-instant",
            "llama3-70b-8192",
            "llama3-8b-8192",
            "mixtral-8x7b-32768",
            "gemma-7b-it",
            "gemma2-9b-it"
        )

        @JvmStatic
        fun getTokenLimit(modelName: String): Int {
            return when {
                modelName.endsWith("8192") -> 8192
                modelName.endsWith("32768") -> 32768
                modelName == "gemma-7b-it" -> 8192
                modelName == "gemma2-9b-it" -> 8192
                modelName.startsWith("llama-3.1") -> 128_000
                else -> -1
            }
        }
    }

    private val jsonDecoder = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(jsonDecoder) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    private val providerUrl = "https://api.groq.com/openai/v1/chat/completions"

    override suspend fun chatCompletion(request: ChatCompletionRequest, directive: VoqalDirective?): ChatCompletion {
        val log = project.getVoqalLogger(this::class)
        try {
            val requestJson = JsonObject()
                .put("model", request.model.id)
                .put("messages", JsonArray(request.messages.map { it.toJson() }))
            val response = client.post(providerUrl) {
                header("Content-Type", "application/json")
                header("Accept", "application/json")
                header("Authorization", "Bearer $providerKey")
                setBody(requestJson.encode())
            }
            val roundTripTime = response.responseTime.timestamp - response.requestTime.timestamp
            log.debug("Groq response status: ${response.status} in $roundTripTime ms")

            if (response.status.isSuccess()) {
                val completion = response.body<ChatCompletion>()
                log.debug("Completion: $completion")
                return completion
            } else if (response.status.value == 401) {
                throw AuthenticationException(
                    response.status.value,
                    OpenAIError(
                        OpenAIErrorDetails(
                            code = null,
                            message = "Unauthorized access to Groq. Please check your API key and try again.",
                            param = null,
                            type = null
                        )
                    ),
                    ClientRequestException(response, response.bodyAsText())
                )
            } else {
                val error = response.body<OpenAIError>()
                throw InvalidRequestException(
                    response.status.value,
                    OpenAIError(OpenAIErrorDetails(message = error.detail?.message)),
                    ClientRequestException(response, response.bodyAsText())
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            throw OpenAITimeoutException(e)
        }
    }

    override suspend fun streamChatCompletion(
        request: ChatCompletionRequest,
        directive: VoqalDirective?
    ): Flow<ChatCompletionChunk> = flow {
        try {
            val requestJson = JsonObject()
                .put("model", request.model.id)
                .put("messages", JsonArray(request.messages.map { it.toJson() }))
                .put("stream", true)

            val fullText = StringBuilder()
            client.preparePost(providerUrl) {
                header("Content-Type", "application/json")
                header("Accept", "application/json")
                header("Authorization", "Bearer $providerKey")
                setBody(requestJson.encode())
            }.execute { httpResponse: HttpResponse ->
                val channel: ByteReadChannel = httpResponse.body()

                var deltaRole: Role? = null
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line()?.takeUnless { it.isEmpty() } ?: continue

                    try {
                        val dataJson = line.substringAfter("data: ")
                        if (dataJson != "[DONE]") {
                            val partialChunk = jsonDecoder.decodeFromString<ChatCompletionChunk>(dataJson)
                            if (deltaRole == null) {
                                deltaRole = partialChunk.choices[0].delta?.role
                            }
                            fullText.append(partialChunk.choices[0].delta?.content ?: "")

                            emit(
                                partialChunk.copy(
                                    choices = partialChunk.choices.map {
                                        it.copy(
                                            delta = it.delta?.copy(
                                                role = deltaRole,
                                                content = fullText.toString()
                                            )
                                        )
                                    }
                                )
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            throw OpenAITimeoutException(e)
        }
    }

    override fun isStreamable() = true
    override fun getAvailableModelNames() = MODELS
    override fun dispose() = client.close()
    private fun ChatMessage.toJson() = JsonObject().put("role", "user").put("content", content)
}
