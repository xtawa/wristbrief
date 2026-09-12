package ink.underflo.wristbrief.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.underflo.wristbrief.mobile.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = TouchTargetTokens.minTouchTarget)
            .semantics {
                role = Role.Button
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = TouchTargetTokens.minTouchTarget)
            .semantics {
                role = Role.Button
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun BackIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.action_back),
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minWidth = TouchTargetTokens.minTouchTarget, minHeight = TouchTargetTokens.minTouchTarget)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MobileSpacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetTokens.minTouchTarget),
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun ContentRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    timestamp: String? = null,
    isUnread: Boolean = false,
    isSaved: Boolean = false,
    isPodcast: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTargetTokens.minTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MobileSpacing.medium, vertical = MobileSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "Unread" },
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                }
                Spacer(Modifier.width(MobileSpacing.small))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(MobileSpacing.xsmall))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MobileSpacing.small),
                ) {
                    if (isPodcast) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.library_filter_podcasts),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (timestamp != null) {
                        Text(
                            text = timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            if (isSaved) {
                Spacer(Modifier.width(MobileSpacing.small))
                AppIcon(
                    kind = AppIconKind.BookmarkFilled,
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = "Saved" },
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    canRetry: Boolean = true,
    onRetry: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MobileSpacing.cardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (canRetry && onRetry != null) {
                Spacer(Modifier.width(MobileSpacing.small))
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
fun OfflineBadge(
    modifier: Modifier = Modifier,
    cachedEpochMs: Long? = null,
) {
    val timeLabel = cachedEpochMs?.let {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = if (timeLabel != null) {
                "${stringResource(R.string.today_offline_fallback)} ($timeLabel)"
            } else {
                stringResource(R.string.today_offline_fallback)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun <T> StateView(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            is UiState.Idle -> Unit
            is UiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            is UiState.Refreshing -> content(state.content)
            is UiState.Content -> content(state.content)
            is UiState.OfflineCached -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    OfflineBadge(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MobileSpacing.medium, vertical = MobileSpacing.small),
                        cachedEpochMs = state.cachedEpochMs,
                    )
                    content(state.content)
                }
            }
            is UiState.PartialFailure -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    ErrorBanner(
                        message = "${state.failedSourcesCount}/${state.totalSourcesCount} feeds failed to refresh",
                        canRetry = onRetry != null,
                        onRetry = onRetry,
                        modifier = Modifier.padding(MobileSpacing.medium),
                    )
                    content(state.content)
                }
            }
            is UiState.Empty -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MobileSpacing.cardPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MobileSpacing.medium),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.actionLabel != null && onAction != null) {
                        PrimaryAction(text = state.actionLabel, onClick = onAction)
                    }
                }
            }
            is UiState.Error -> {
                ErrorBanner(
                    message = state.message,
                    canRetry = state.canRetry && onRetry != null,
                    onRetry = onRetry,
                    modifier = Modifier.padding(MobileSpacing.cardPadding),
                )
            }
            is UiState.AuthRequired -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MobileSpacing.cardPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MobileSpacing.medium),
                ) {
                    Text(
                        text = stringResource(R.string.membership_account_sign_in_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (onAction != null) {
                        PrimaryAction(
                            text = stringResource(R.string.membership_sign_in_google),
                            onClick = onAction,
                        )
                    }
                }
            }
            is UiState.QuotaExhausted -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MobileSpacing.cardPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MobileSpacing.medium),
                ) {
                    Text(
                        text = stringResource(R.string.ai_quota_exceeded),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}
