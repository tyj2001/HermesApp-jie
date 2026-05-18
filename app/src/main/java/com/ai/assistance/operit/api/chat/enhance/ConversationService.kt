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
import com.ai.assistance.operit.util.ComputerDesktopActionResultData
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
            // Use CHAT settings for summary instead of SUMMARY
            val modelParameters = multiServiceManager.getModelParametersForFunction(FunctionType.CHAT)

            // Get CHAT service instead of SUMMARY
            val summaryService = multiServiceManager.getServiceForFunction(FunctionType.CHAT)

            val result = summaryService.generateResponse(
                finalMessages,
                modelParameters
            ).first()

            return result.content
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error generating summary", e)
            return previousSummary ?: ""
        }
    }
}