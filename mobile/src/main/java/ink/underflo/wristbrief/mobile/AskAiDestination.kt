package ink.underflo.wristbrief.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.underflo.wristbrief.mobile.ui.AppIcon
import ink.underflo.wristbrief.mobile.ui.AppIconKind
import ink.underflo.wristbrief.mobile.ui.glass.GlassSurface
import ink.underflo.wristbrief.mobile.ui.glass.GlassTokens
import ink.underflo.wristbrief.mobile.ui.glass.NeutralFilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AskAiScope { All, Today, Unread, Saved }

@Composable
private fun askAiScopeLabel(scope: AskAiScope): String = when (scope) {
    AskAiScope.All -> stringResource(R.string.ai_scope_all)
    AskAiScope.Today -> stringResource(R.string.ai_scope_brief)
    AskAiScope.Unread -> stringResource(R.string.ai_scope_unread)
    AskAiScope.Saved -> stringResource(R.string.ai_scope_saved)
}

/**
 * Ask AI Destination (Screen 09) following uidocs/stitch_wristbrief_android_design_system/09_ask_ai.
 *
 * Dedicated AI reading synthesis center grounded in user's library, saved feeds, and podcast transcripts.
 */
@Composable
fun AskAiDestination(
    padding: PaddingValues,
    inboxRepository: MobileInboxRepository,
    initialTitle: String = "",
    initialContent: String = "",
    onOpenArticle: ((MobileFeedItem) -> Unit)? = null,
    onOpenAccountSettings: () -> Unit = {},
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(context) { AccountSessionPreferences(context) }.read()
    val summaryClient = remember { PhoneLongSummaryClient() }

    val allItems = remember(inboxRepository) { inboxRepository.items() }
    val unreadItems = remember(allItems) { allItems.filterNot { inboxRepository.isRead(it.id) } }
    val savedItems = remember(allItems) { allItems.filter { inboxRepository.isSaved(it.id) } }

    var selectedContextScope by rememberSaveable { mutableStateOf(AskAiScope.All) }
    var promptQuery by rememberSaveable { mutableStateOf(initialTitle.ifBlank { "" }) }
    var activePrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var promptTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    var aiResponseState by remember { mutableStateOf<PhoneLongSummaryUiState?>(null) }
    var requiresSignIn by remember { mutableStateOf(false) }
    var copiedToClipboard by remember { mutableStateOf(false) }

    val contextScopes = listOf(AskAiScope.All, AskAiScope.Today, AskAiScope.Unread, AskAiScope.Saved)

    fun executeQuery(query: String) {
        if (query.isBlank()) return
        activePrompt = query
        promptTimestamp = System.currentTimeMillis()
        aiResponseState = PhoneLongSummaryUiState.Loading
        requiresSignIn = false
        copiedToClipboard = false

        // Determine context source items
        val sourceItems = when (selectedContextScope) {
            AskAiScope.Unread -> unreadItems
            AskAiScope.Saved -> savedItems
            else -> allItems
        }

        val aggregatedContent = if (initialContent.isNotBlank()) {
            initialContent
        } else if (sourceItems.isNotEmpty()) {
            sourceItems.take(5).joinToString("\n\n") { "${it.title}: ${it.description.orEmpty()}" }
        } else {
            query
        }

        val aggregatedTitle = query

        scope.launch {
            if (session != null && BuildConfig.GATEWAY_BASE_URL.startsWith("https://")) {
                try {
                    val summary = withContext(Dispatchers.IO) {
                        summaryClient.summarize(
                            gatewayUrl = BuildConfig.GATEWAY_BASE_URL,
                            gatewayToken = session.sessionToken,
                            title = aggregatedTitle,
                            content = aggregatedContent,
                        )
                    }
                    aiResponseState = phoneLongSummaryReadyState(summary)
                } catch (error: PhoneSummaryRequestException) {
                    requiresSignIn = error.failure == PhoneSummaryFailure.Unauthorized
                    val msg = when (error.failure) {
                        PhoneSummaryFailure.Quota -> context.getString(R.string.ai_quota_exceeded)
                        PhoneSummaryFailure.ProviderUnavailable -> context.getString(R.string.ai_provider_unavailable)
                        PhoneSummaryFailure.Unauthorized -> context.getString(R.string.ai_sign_in_body)
                        else -> context.getString(R.string.ai_error)
                    }
                    aiResponseState = PhoneLongSummaryUiState.Error(msg)
                } catch (_: Exception) {
                    aiResponseState = PhoneLongSummaryUiState.Error(context.getString(R.string.ai_error))
                }
            } else if (!BuildConfig.GATEWAY_BASE_URL.startsWith("https://", ignoreCase = true)) {
                aiResponseState = PhoneLongSummaryUiState.Error(context.getString(R.string.ai_service_unavailable_body))
            } else {
                requiresSignIn = true
                aiResponseState = PhoneLongSummaryUiState.Error(context.getString(R.string.ai_sign_in_body))
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.nav_ai),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = GlassTokens.textPrimary(darkTheme),
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(GlassTokens.surfaceContainerHigh(darkTheme))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AppIcon(
                            kind = AppIconKind.Bullet,
                            modifier = Modifier.size(12.dp),
                            tint = GlassTokens.accentTeal(darkTheme),
                        )
                        Text(
                            text = stringResource(R.string.ai_grounded_sources, allItems.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassTokens.textPrimary(darkTheme),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.ai_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTokens.textSecondary(darkTheme),
                    lineHeight = 22.sp,
                )
            }
        }

        // Context Source Filter Chips
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(contextScopes) { scope ->
                    val isSelected = selectedContextScope == scope
                    val label = when (scope) {
                        AskAiScope.All -> "${stringResource(R.string.ai_scope_all)} (${allItems.size})"
                        AskAiScope.Unread -> "${stringResource(R.string.ai_scope_unread)} (${unreadItems.size})"
                        AskAiScope.Saved -> "${stringResource(R.string.ai_scope_saved)} (${savedItems.size})"
                        AskAiScope.Today -> stringResource(R.string.ai_scope_brief)
                    }
                    NeutralFilterChip(
                        label = label,
                        selected = isSelected,
                        onClick = { selectedContextScope = scope },
                        darkTheme = darkTheme,
                    )
                }
            }
        }

        // Active Prompt View if user submitted
        if (activePrompt != null) {
            item {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    strong = false,
                    cornerRadius = GlassTokens.CardRadius,
                    darkTheme = darkTheme,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(GlassTokens.accentTeal(darkTheme)),
                                )
                                Text(
                                    text = stringResource(R.string.ai_prompt_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTokens.accentTeal(darkTheme),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            promptTimestamp?.let { ts ->
                                Text(
                                    text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }
                        }

                        Text(
                            text = activePrompt!!,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = GlassTokens.textPrimary(darkTheme),
                        )

                        Text(
                            text = stringResource(R.string.ai_context_prefix, askAiScopeLabel(selectedContextScope)),
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassTokens.textSecondary(darkTheme),
                        )
                    }
                }
            }
        }

        // AI Answer / Executive Brief Card
        when (val current = aiResponseState) {
            null -> {
                // Empty state: Suggested Prompt Queries
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.ai_suggested_queries),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = GlassTokens.textPrimary(darkTheme),
                        )
                        val suggestions = listOf(
                            stringResource(R.string.ai_suggested_query_1),
                            stringResource(R.string.ai_suggested_query_2),
                            stringResource(R.string.ai_suggested_query_3),
                            stringResource(R.string.ai_suggested_query_4),
                        )
                        suggestions.forEach { query ->
                            GlassSurface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        promptQuery = query
                                        executeQuery(query)
                                    },
                                strong = false,
                                cornerRadius = GlassTokens.RowRadius,
                                darkTheme = darkTheme,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = query,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = GlassTokens.textPrimary(darkTheme),
                                        modifier = Modifier.weight(1f),
                                    )
                                    AppIcon(
                                        kind = AppIconKind.ChevronRight,
                                        modifier = Modifier.size(16.dp),
                                        tint = GlassTokens.accentTeal(darkTheme),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            PhoneLongSummaryUiState.Loading -> {
                item {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        strong = true,
                        cornerRadius = GlassTokens.HeroRadius,
                        darkTheme = darkTheme,
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = GlassTokens.accentTeal(darkTheme),
                            )
                            Text(
                                text = stringResource(R.string.ai_generating),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                        }
                    }
                }
            }

            is PhoneLongSummaryUiState.Error -> {
                item {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = GlassTokens.CardRadius,
                        darkTheme = darkTheme,
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(if (requiresSignIn) R.string.ai_sign_in_title else R.string.ai_error),
                                style = MaterialTheme.typography.titleMedium,
                                color = GlassTokens.semanticError(darkTheme),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = current.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                            )
                            if (requiresSignIn) {
                                Button(
                                    onClick = onOpenAccountSettings,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.membership_sign_in_google))
                                }
                            }
                        }
                    }
                }
            }

            is PhoneLongSummaryUiState.Ready -> {
                item {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        strong = true,
                        cornerRadius = GlassTokens.HeroRadius,
                        darkTheme = darkTheme,
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Top Tag row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(GlassTokens.surfaceContainerHighest(darkTheme)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AppIcon(
                                            kind = AppIconKind.Bullet,
                                            modifier = Modifier.size(14.dp),
                                            tint = GlassTokens.accentTeal(darkTheme),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.ai_executive_brief),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = GlassTokens.accentTeal(darkTheme),
                                    )
                                }

                                Text(
                                    text = stringResource(R.string.ai_sources_synthesized, allItems.take(3).size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GlassTokens.textSecondary(darkTheme),
                                )
                            }

                            // Headline
                            Text(
                                text = activePrompt?.ifBlank { null } ?: stringResource(R.string.ai_default_synthesis_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = GlassTokens.textPrimary(darkTheme),
                            )

                            // Paragraph
                            Text(
                                text = current.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = GlassTokens.textSecondary(darkTheme),
                                lineHeight = 24.sp,
                            )

                            // Takeaway cards
                            if (current.bullets.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    current.bullets.forEachIndexed { idx, bullet ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp),
                                            color = GlassTokens.surfaceContainer(darkTheme),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            when (idx % 3) {
                                                                0 -> GlassTokens.accentTeal(darkTheme)
                                                                1 -> MaterialTheme.colorScheme.primary
                                                                else -> MaterialTheme.colorScheme.secondary
                                                            },
                                                        )
                                                        .padding(top = 6.dp),
                                                )
                                                Text(
                                                    text = bullet,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = GlassTokens.textPrimary(darkTheme),
                                                    lineHeight = 20.sp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Citations row
                            if (allItems.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = stringResource(R.string.ai_citations_grounding),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GlassTokens.textSecondary(darkTheme),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(allItems.take(4)) { sourceItem ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(GlassTokens.surfaceContainer(darkTheme))
                                                    .clickable { onOpenArticle?.invoke(sourceItem) }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                            ) {
                                                Text(
                                                    text = sourceItem.feedTitle,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = GlassTokens.textPrimary(darkTheme),
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(GlassTokens.surfaceContainerHigh(darkTheme))
                                            .clickable {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("AI Brief", "${activePrompt.orEmpty()}\n\n${current.text}"))
                                                copiedToClipboard = true
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = if (copiedToClipboard) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                            contentDescription = stringResource(if (copiedToClipboard) R.string.ai_copied else R.string.action_save),
                                            modifier = Modifier.size(16.dp),
                                            tint = GlassTokens.textPrimary(darkTheme),
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(GlassTokens.surfaceContainerHigh(darkTheme))
                                            .clickable {
                                                activePrompt?.let { executeQuery(it) }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = stringResource(R.string.today_refresh),
                                            modifier = Modifier.size(16.dp),
                                            tint = GlassTokens.textPrimary(darkTheme),
                                        )
                                    }
                                }

                                if (copiedToClipboard) {
                                    Text(
                                        text = stringResource(R.string.ai_copied),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GlassTokens.accentTeal(darkTheme),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            PhoneLongSummaryUiState.AwaitingAuthenticatedGateway -> Unit
        }

        // Bottom Fixed Query Input Row
        item {
            OutlinedTextField(
                value = promptQuery,
                onValueChange = { promptQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.ai_placeholder),
                        color = GlassTokens.textSecondary(darkTheme),
                    )
                },
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (promptQuery.isNotBlank()) GlassTokens.primaryContainer(darkTheme)
                                else GlassTokens.surfaceContainerHigh(darkTheme),
                            )
                            .clickable(enabled = promptQuery.isNotBlank()) {
                                executeQuery(promptQuery)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.ai_generate),
                            modifier = Modifier.size(18.dp),
                            tint = if (promptQuery.isNotBlank()) GlassTokens.onPrimaryContainer(darkTheme) else GlassTokens.textSecondary(darkTheme),
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = GlassTokens.surfaceContainerHigh(darkTheme),
                    unfocusedContainerColor = GlassTokens.surfaceContainer(darkTheme),
                    focusedBorderColor = GlassTokens.accentTeal(darkTheme),
                    unfocusedBorderColor = GlassTokens.hairline(darkTheme),
                    focusedTextColor = GlassTokens.textPrimary(darkTheme),
                    unfocusedTextColor = GlassTokens.textPrimary(darkTheme),
                ),
                singleLine = true,
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}
