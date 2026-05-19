package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.chat.hooks.PromptHookContext
import com.ai.assistance.operit.core.chat.hooks.PromptHookRegistry
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.chat.hooks.toPromptTurns
import com.ai.assistance.operit.core.config.SystemPromptConfig
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.PreferenceProfile
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.core.tools.SimplifiedUINode
import com.ai.assistance.operit.core.config.FunctionalPrompts
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.WaifuPreferences
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.core.avatar.impl.factory.AvatarModelFactoryImpl
import com.ai.assistance.operit.data.repository.AvatarRepository
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.core.tools.ToolProgressBus
import com.ai.assistance.operit.util.streamnative.NativeXmlSplitter
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.ai.assistance.operit.core.tools.ComputerDesktopActionResultData
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.data.repository.CustomEmojiRepository
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkBuilder
import com.ai.assistance.operit.data.repository.getCustomMoodDefinitions
import com.ai.assistance.operit.data.repository.getMoodAnimationMapping

/**
 * Handles conversation-related功能的服务类，包括：摘要生成、偏好处理和对话分割等预备
 */
class ConversationService(
    private val context: Context,
    private val customEmojiRepository: CustomEmojiRepository
    ) {

    companion object {
        private const val TAG = "ConversationService"
        private const val APPLY_FILE_TOOL_NAME = "apply_file"
        private val fileRequestContentRegex = Regex(
            """<file-request-content\b[^>]*><!\[CDATA\[(.*?)\]\]></file-request-content>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }

    private val apiPreferences = ApiPreferences.getInstance(context)
    private val displayPreferencesManager = DisplayPreferencesManager.getInstance(context)
    private val waifuPreferences = WaifuPreferences.getInstance(context)
    private val characterCardManager = CharacterCardManager.getInstance(context)
    private val characterCardToolAccessResolver = CharacterCardToolAccessResolver.getInstance(context)
    private val activePromptManager = ActivePromptManager.getInstance(context)
    private val userPreferencesManager = preferencesManager
    private val avatarRepository by lazy {
        AvatarRepository.getInstance(context, AvatarModelFactoryImpl())
    }
    private val conversationMutex = Mutex()

    /**
     * 生成对话摘要
     * @param messages 要摘要的消息列表
     * @return 生成的摘要文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            multiServiceManager: MultiServiceManager
    ): String {
        return generateSummaryFromPromptTurns(messages.toPromptTurns(), null, multiServiceManager)
    }

    /**
     * 生成对话摘要，并且包含上一次摘要的内容
     * @param messages 要摘要的消息列表
     * @param previousSummary 上一次摘要的内容，可以为null
     * @return 生成的摘要文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            previousSummary: String?,
            multiServiceManager: MultiServiceManager
    ): String {
        return generateSummaryFromPromptTurns(messages.toPromptTurns(), previousSummary, multiServiceManager)
    }

    suspend fun generateSummaryFromPromptTurns(
            messages: List<PromptTurn>,
            previousSummary: String?,
            multiServiceManager: MultiServiceManager
    ): String {
        try {
            val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
            val systemPrompt = FunctionalPrompts.buildSummarySystemPrompt(previousSummary, useEnglish)
            val sanitizedMessages = ChatUtils.stripGeminiThoughtSignatureMetaTurns(messages)

            val finalMessages =
                listOf(PromptTurn(kind = PromptTurnKind.SYSTEM, content = systemPrompt)) +
                    sanitizedMessages

            // Get all model parameters from preferences (with enabled state)
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.SUMMARY)

            // 获取SUMMARY功能类型的AIService实例
            val summaryService = multiServiceManager.getServiceForFunction(FunctionType.SUMMARY)

            // 使用summaryService发送请求，收集完整响应
            val contentBuilder = StringBuilder()

            ToolProgressBus.update(
                ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                0.05f,
                context.getString(R.string.conversation_summary_preparing)
            )

            data class Stage(
                val matchers: List<(String) -> Boolean>,
                val progress: Float,
                val message: String
            )

            val stages = listOf(
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_MARKER_EN) || it.contains(FunctionalPrompts.SUMMARY_MARKER_CN) }),
                    progress = 0.20f,
                    message = context.getString(R.string.conversation_summary_writing_title)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_CORE_TASK_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_CORE_TASK_CN) }),
                    progress = 0.40f,
                    message = context.getString(R.string.conversation_summary_core_task)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_INTERACTION_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_INTERACTION_CN) }),
                    progress = 0.55f,
                    message = context.getString(R.string.conversation_summary_interaction)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_PROGRESS_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_PROGRESS_CN) }),
                    progress = 0.70f,
                    message = context.getString(R.string.conversation_summary_progress)
                ),
                Stage(
                    matchers = listOf({ it.contains(FunctionalPrompts.SUMMARY_SECTION_KEY_INFO_EN) || it.contains(FunctionalPrompts.SUMMARY_SECTION_KEY_INFO_CN) }),
                    progress = 0.85f,
                    message = context.getString(R.string.conversation_summary_key_info)
                ),
                Stage(
                    matchers = listOf({ it.contains("=======================================") || it.contains("============================") }),
                    progress = 0.95f,
                    message = context.getString(R.string.conversation_summary_finishing)
                )
            )

            var lastStageIndex = -1
            fun updateStageIfNeeded() {
                if (lastStageIndex + 1 >= stages.size) return
                val snapshot = contentBuilder.toString()
                while (lastStageIndex + 1 < stages.size) {
                    val next = stages[lastStageIndex + 1]
                    val matched = next.matchers.any { it(snapshot) }
                    if (!matched) break
                    lastStageIndex += 1
                    ToolProgressBus.update(
                        ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                        next.progress,
                        next.message
                    )
                }
            }

            // 使用新的Stream API
            val stream =
                    summaryService.sendMessage(
                            context = context,
                            chatHistory =
                                finalMessages + PromptTurn(
                                    kind = PromptTurnKind.USER,
                                    content = FunctionalPrompts.summaryUserMessage(useEnglish)
                                ),
                            modelParameters = modelParameters
                    )

            // 收集流中的所有内容
            stream.collect { content ->
                contentBuilder.append(content)
                updateStageIfNeeded()
            }

            ToolProgressBus.update(
                ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
                1f,
                context.getString(R.string.conversation_summary_completed)
            )

            // 获取完整的总结内容
            val summaryContent = ChatUtils.removeThinkingContent(contentBuilder.toString().trim())

            // 如果内容为空，返回默认消息
            if (summaryContent.isBlank()) {
                return "Conversation Summary: Unable to generate valid summary."
            }

            // 获取本次总结生成的token统计
            val inputTokens = summaryService.inputTokenCount
            val cachedInputTokens = summaryService.cachedInputTokenCount
            val outputTokens = summaryService.outputTokenCount

            // 将总结token计数添加到用户偏好分析的token统计中
            try {
                AppLogger.d(TAG, "总结生成使用了输入token: $inputTokens, 缓存token: $cachedInputTokens, 输出token: $outputTokens")
                apiPreferences.updateTokensForProviderModel(summaryService.providerModel, inputTokens, outputTokens, cachedInputTokens)
                
                // Update request count for summary generation
                apiPreferences.incrementRequestCountForProviderModel(summaryService.providerModel)
                
                AppLogger.d(TAG, "已将总结token统计添加到用户偏好分析token计数中")
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新token统计失败", e)
            }

            return summaryContent
        } catch (e: Exception) {
            AppLogger.e(TAG, "生成总结时出错", e)
            // return "对话摘要：生成摘要时出错，但对话仍在继续。"
            throw e
        }
    }
}