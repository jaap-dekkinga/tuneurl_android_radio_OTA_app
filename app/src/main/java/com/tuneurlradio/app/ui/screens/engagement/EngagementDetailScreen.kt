package com.tuneurlradio.app.ui.screens.engagement

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.tuneurlradio.app.domain.model.Engagement
import com.tuneurlradio.app.domain.model.EngagementType
import com.tuneurlradio.app.tuneurl.EngagementReporter
import com.tuneurlradio.app.tuneurl.TimeUtils
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Detail screen reached from the Saved list and the History list. Mirrors
 * the iOS `ViewEngagementScreen` per the platform-neutral spec.
 *
 * Reporting:
 *  - `acted`  → fires on primary-action or content-preview tap, per the
 *               spec's "acted reports the action, not the screen view" rule.
 *  - `shared` → fires when Share is tapped, regardless of share-sheet outcome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngagementDetailScreen(
    onBack: () -> Unit,
    viewModel: EngagementDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val reporter = rememberEngagementReporter()

    // Local state for the coupon overlay. Kept here rather than in the VM
    // because it's pure presentation — the `acted` report fires when the
    // ShowCoupon effect is handled, not when the overlay actually appears.
    var couponUrl by remember { mutableStateOf<String?>(null) }

    val engagement = state.engagement

    LaunchedEffect(Unit) {
        viewModel.effect.collect { effect ->
            when (effect) {
                EngagementDetailEffect.Dismiss -> onBack()

                is EngagementDetailEffect.OpenWebsite -> {
                    engagement?.let { reporter.acted(context, it.id, isoHeardAt(it)) }
                    safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)))
                }

                is EngagementDetailEffect.PlaceCall -> {
                    engagement?.let { reporter.acted(context, it.id, isoHeardAt(it)) }
                    safeStart(
                        context,
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${effect.phoneNumber}"))
                    )
                }

                is EngagementDetailEffect.SendSms -> {
                    engagement?.let { reporter.acted(context, it.id, isoHeardAt(it)) }
                    safeStart(
                        context,
                        Intent(Intent.ACTION_VIEW, Uri.parse("sms:${effect.phoneNumber}"))
                    )
                }

                is EngagementDetailEffect.ShowCoupon -> {
                    engagement?.let { reporter.acted(context, it.id, isoHeardAt(it)) }
                    couponUrl = effect.imageUrl
                }

                is EngagementDetailEffect.Share -> {
                    engagement?.let { reporter.shared(context, it.id, isoHeardAt(it)) }
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, effect.text)
                    }
                    safeStart(context, Intent.createChooser(shareIntent, null))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                state.isLoading -> {
                    // Intentionally empty — the screen loads from Room
                    // in microseconds; a spinner adds visual noise.
                }
                state.notFound || engagement == null -> {
                    Text(
                        text = "This item is no longer available.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    EngagementTitle(engagement)
                    Box(modifier = Modifier.weight(1f, fill = true)) {
                        EngagementPreview(
                            engagement = engagement,
                            onPreviewTap = {
                                viewModel.handleIntent(EngagementDetailIntent.PrimaryAction)
                            }
                        )
                    }
                    PrimaryActionButton(
                        engagement = engagement,
                        onClick = {
                            viewModel.handleIntent(EngagementDetailIntent.PrimaryAction)
                        }
                    )
                    SecondaryActions(
                        canDelete = state.canDelete,
                        onShare = { viewModel.handleIntent(EngagementDetailIntent.ShareTapped) },
                        onDelete = { viewModel.handleIntent(EngagementDetailIntent.DeleteTapped) },
                        onClose = { viewModel.handleIntent(EngagementDetailIntent.CloseTapped) }
                    )
                }
            }
        }
    }

    if (state.showDeleteConfirmation) {
        DeleteConfirmDialog(
            onConfirm = { viewModel.handleIntent(EngagementDetailIntent.DeleteConfirmed) },
            onDismiss = { viewModel.handleIntent(EngagementDetailIntent.DeleteCancelled) }
        )
    }

    couponUrl?.let { url ->
        CouponViewerOverlay(
            imageUrl = url,
            onDone = { couponUrl = null }
        )
    }
}

// ---------------------------------------------------------------------------
// Hilt EntryPoint to fetch the singleton EngagementReporter inside a
// Composable without making the screen take a constructor dependency.
// This is the idiomatic escape hatch for non-ViewModel singletons; it
// keeps the screen's signature lean and unit-testable in isolation.
// ---------------------------------------------------------------------------

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReporterEntryPoint {
    fun engagementReporter(): EngagementReporter
}

@Composable
private fun rememberEngagementReporter(): EngagementReporter {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context, ReporterEntryPoint::class.java)
            .engagementReporter()
    }
}

private fun isoHeardAt(e: Engagement): String = TimeUtils.formatAsIso(e.heardAt.time)

private fun safeStart(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun EngagementTitle(engagement: Engagement) {
    Text(
        text = engagement.description ?: engagement.name ?: "",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EngagementPreview(
    engagement: Engagement,
    onPreviewTap: () -> Unit
) {
    val info = engagement.info
    when (engagement.type) {
        EngagementType.OPEN_PAGE, EngagementType.SAVE_PAGE -> {
            if (info.isNullOrBlank()) FailedToLoadPreview()
            else LinkPreviewCard(url = info, onClick = onPreviewTap)
        }
        EngagementType.COUPON -> {
            if (info.isNullOrBlank()) FailedToLoadPreview()
            else CouponPreviewImage(imageUrl = info, onClick = onPreviewTap)
        }
        EngagementType.PHONE, EngagementType.SMS -> {
            if (info.isNullOrBlank()) FailedToLoadPreview()
            else PhoneNumberPreview(number = info, onClick = onPreviewTap)
        }
        EngagementType.POLL, EngagementType.API_CALL, EngagementType.UNKNOWN ->
            FailedToLoadPreview()
    }
}

@Composable
private fun LinkPreviewCard(url: String, onClick: () -> Unit) {
    // Lightweight link card. Android has no built-in equivalent to iOS's
    // LPLinkMetadata; pulling in a full OpenGraph fetcher is out of scope
    // for this pass. We render a clickable card with the URL and its host,
    // matching the visual vocabulary already used by EngagementListCard.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = Uri.parse(url).host ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CouponPreviewImage(imageUrl: String, onClick: () -> Unit) {
    AsyncImage(
        model = imageUrl,
        contentDescription = "Coupon",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun PhoneNumberPreview(number: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Phone Number",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = number,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable(onClick = onClick)
        )
    }
}

@Composable
private fun FailedToLoadPreview() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Failed to load content.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrimaryActionButton(engagement: Engagement, onClick: () -> Unit) {
    val label = when (engagement.type) {
        EngagementType.OPEN_PAGE, EngagementType.SAVE_PAGE -> "Open Website"
        EngagementType.COUPON -> "View Coupon"
        EngagementType.PHONE -> "Call"
        EngagementType.SMS -> "Send Message"
        EngagementType.POLL, EngagementType.API_CALL, EngagementType.UNKNOWN -> null
    } ?: return

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SecondaryActions(
    canDelete: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SecondaryButton(
            label = "Share",
            color = MaterialTheme.colorScheme.outline,
            onClick = onShare,
            modifier = Modifier.weight(1f)
        )
        if (canDelete) {
            SecondaryButton(
                label = "Delete",
                color = MaterialTheme.colorScheme.error,
                onClick = onDelete,
                modifier = Modifier.weight(1f)
            )
        }
        SecondaryButton(
            label = "Close",
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            onClick = onClose,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this saved item?") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Full-screen coupon viewer. Matches the iOS contract: black background,
 * image aspect-fit, Done button top-right to dismiss; image tap also
 * dismisses. Rendered as an in-place overlay rather than a separate
 * navigation route to keep AppNavigation lean and avoid an extra
 * back-stack entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CouponViewerOverlay(imageUrl: String, onDone: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Coupon (full screen)",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .clickable(onClick = onDone)
            )
            TextButton(
                onClick = onDone,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text(text = "Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
