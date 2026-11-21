package com.example.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AdminCampaignConversionItem
import com.example.data.model.AdminCampaignTrackingUiState
import com.example.data.model.AdminConversionReviewAction
import com.example.data.model.AdminConversionStatusFilter
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.ui.theme.taskFlowColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 3E.2 — Admin Conversion Moderation Queue Tab.
 *
 * Security & financial invariants:
 * - Uses strictly server-sanitized `AdminCampaignConversionItem` (`moderationId`, `displayRef`).
 * - Never displays `clickId`, raw `conv_<clickId>`, `externalTransactionId`, raw signatures, or secrets.
 * - Never computes or mutates wallet balances locally — displays server-computed TF & INR (125 TF = ₹1)
 *   financial impact previews and delegates all transitions atomically to `reviewCampaignConversion`.
 * - Enforces mandatory admin reason/notes on APPROVE, REJECT, and REVERSE.
 * - Clearly warns on REVERSE that recovery may result in partial recovery or shortfall.
 */
@Composable
fun CampaignTrackingConversionsQueueTab(
  state: AdminCampaignTrackingUiState,
  onSelectStatusFilter: (AdminConversionStatusFilter) -> Unit,
  onApplyFilters: (campaignId: String, networkId: String) -> Unit,
  onRefresh: () -> Unit,
  onLoadMore: () -> Unit,
  onReviewConversion: (
    item: AdminCampaignConversionItem,
    action: AdminConversionReviewAction,
    reason: String,
    onSuccess: () -> Unit
  ) -> Unit
) {
  var campaignIdInput by remember(state.conversionsFilterCampaignId) {
    mutableStateOf(state.conversionsFilterCampaignId)
  }
  var networkIdInput by remember(state.conversionsFilterNetworkId) {
    mutableStateOf(state.conversionsFilterNetworkId)
  }
  var pendingModerationTarget by remember {
    mutableStateOf<Pair<AdminCampaignConversionItem, AdminConversionReviewAction>?>(null)
  }

  val tfColors = taskFlowColors()
  val dateFormatter = remember {
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_conversions_queue_tab"),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Status Filter Chips + Refresh
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      AdminConversionStatusFilter.entries.forEach { filter ->
        FilterChip(
          selected = state.conversionsFilterStatus == filter,
          onClick = { onSelectStatusFilter(filter) },
          label = { Text(filter.label) },
          modifier = Modifier.testTag("admin_conversions_filter_${filter.wireValue.lowercase(Locale.US)}")
        )
      }
      OutlinedButton(
        onClick = onRefresh,
        enabled = !state.isLoadingConversions,
        modifier = Modifier.testTag("admin_conversions_refresh_button")
      ) {
        Icon(
          imageVector = Icons.Outlined.Refresh,
          contentDescription = "Refresh conversion queue",
          modifier = Modifier.size(16.dp)
        )
      }
    }

    // Campaign & Network ID Filters
    Card(
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.FilterList,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
          )
          Text(
            text = "Filter Queue (${state.conversions.totalCount} matched)",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = campaignIdInput,
            onValueChange = { campaignIdInput = it },
            label = { Text("Campaign ID") },
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("admin_conversions_campaign_filter_input")
          )
          OutlinedTextField(
            value = networkIdInput,
            onValueChange = { networkIdInput = it },
            label = { Text("Network ID") },
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("admin_conversions_network_filter_input")
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (state.conversionsFilterCampaignId.isNotBlank() || state.conversionsFilterNetworkId.isNotBlank()) {
            TextButton(
              onClick = {
                campaignIdInput = ""
                networkIdInput = ""
                onApplyFilters("", "")
              },
              modifier = Modifier.testTag("admin_conversions_clear_filters_button")
            ) {
              Text("Clear")
            }
          }
          Button(
            onClick = { onApplyFilters(campaignIdInput.trim(), networkIdInput.trim()) },
            enabled = !state.isLoadingConversions,
            modifier = Modifier.testTag("admin_conversions_apply_filters_button")
          ) {
            Text("Apply Filters")
          }
        }
      }
    }

    // Truncation warning if bounded queue scan hit limit
    if (state.conversions.truncated) {
      Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = tfColors.goldAccent.copy(alpha = 0.14f)),
        border = BorderStroke(1.dp, tfColors.goldAccent.copy(alpha = 0.4f)),
        modifier = Modifier.testTag("admin_conversions_truncation_banner")
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = "Queue truncated warning",
            tint = tfColors.goldAccent,
            modifier = Modifier.size(18.dp)
          )
          Text(
            text = "Queue scan reached its safe limit of ${state.conversionsScanLimit} records. Filter by status, campaign ID, or network ID to narrow results.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
          )
        }
      }
    }

    // Error state
    if (!state.conversionsError.isNullOrBlank()) {
      Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        )
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = state.conversionsError,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
          )
          Button(
            onClick = onRefresh,
            modifier = Modifier.testTag("admin_conversions_retry_button")
          ) {
            Text("Retry")
          }
        }
      }
    }

    // Loading / Empty / List states
    if (state.isLoadingConversions && state.conversions.items.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 36.dp),
        contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator()
      }
    } else if (state.conversions.items.isEmpty() && state.conversionsError.isNullOrBlank()) {
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = "No Conversions in Queue",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
          )
          Text(
            text = "No ${state.conversionsFilterStatus.label.lowercase(Locale.US)} campaign conversions match the current filters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    } else {
      state.conversions.items.forEach { item ->
        AdminConversionQueueCard(
          item = item,
          isActionInFlight = state.reviewingModerationId != null,
          dateFormatter = dateFormatter,
          onRequestAction = { action -> pendingModerationTarget = item to action }
        )
      }

      if (state.conversions.hasMore) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
          contentAlignment = Alignment.Center
        ) {
          OutlinedButton(
            onClick = onLoadMore,
            enabled = !state.isLoadingConversions,
            modifier = Modifier.testTag("admin_conversions_load_more_button")
          ) {
            if (state.isLoadingConversions) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Text("Load More Conversions")
            }
          }
        }
      }
    }
  }

  pendingModerationTarget?.let { (item, action) ->
    CampaignConversionModerationDialog(
      item = item,
      action = action,
      isSubmitting = state.reviewingModerationId == item.moderationId,
      onDismiss = {
        if (state.reviewingModerationId == null) {
          pendingModerationTarget = null
        }
      },
      onConfirm = { reason ->
        onReviewConversion(item, action, reason) {
          pendingModerationTarget = null
        }
      }
    )
  }
}

@Composable
private fun AdminConversionQueueCard(
  item: AdminCampaignConversionItem,
  isActionInFlight: Boolean,
  dateFormatter: SimpleDateFormat,
  onRequestAction: (AdminConversionReviewAction) -> Unit
) {
  val tfColors = taskFlowColors()
  val stateColor: Color = when (item.state) {
    "APPROVED" -> tfColors.statusConfirmedFg
    "REJECTED" -> MaterialTheme.colorScheme.error
    "REVERSED" -> MaterialTheme.colorScheme.tertiary
    else -> tfColors.goldAccent
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("admin_conversion_card_${item.moderationId}")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      // Header row: Campaign title + State badge
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = item.campaignTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = "${item.displayRef} • ${item.networkName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = stateColor.copy(alpha = 0.14f)
        ) {
          Text(
            text = item.state,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = stateColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
          )
        }
      }

      // User + Verification metadata (zero sensitive attribution IDs)
      Text(
        text = "User: ${item.userDisplayName} (${item.userId.take(10)}) • Mode: ${item.verificationMode.ifBlank { "S2S" }}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      // Financial summary block (125 TF = ₹1)
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              text = "Authoritative Reward",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              text = "${formatCoins(item.rewardTf)} (${formatRewardInr(item.rewardCents)})",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
          }

          if (item.state == "REVERSED" && item.rewardCredited) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "Recovered / Shortfall",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                text = "${formatCoins(item.recoveredTf)} (${formatRewardInr(item.recoveredCents)}) • Shortfall: ${formatCoins(item.shortfallTf)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (item.shortfallTf > 0L) tfColors.goldAccent else tfColors.statusConfirmedFg
              )
            }
          } else if (item.state == "APPROVED" && item.rewardCredited) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "Est. Recoverable if Reversed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                text = "${formatCoins(item.expectedRecoverableTf)} (${formatRewardInr(item.expectedRecoverableCents)})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (item.estimatedShortfallTf > 0L) tfColors.goldAccent else MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
      }

      if (item.statusReason.isNotBlank()) {
        Text(
          text = "Status Note: ${item.statusReason}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      val eventTime = when (item.state) {
        "APPROVED" -> item.verifiedAtMillis.takeIf { it > 0L }?.let { "Verified ${dateFormatter.format(Date(it))}" }
        "REJECTED" -> item.rejectedAtMillis.takeIf { it > 0L }?.let { "Rejected ${dateFormatter.format(Date(it))}" }
        "REVERSED" -> item.reversedAtMillis.takeIf { it > 0L }?.let { "Reversed ${dateFormatter.format(Date(it))}" }
        else -> item.createdAtMillis.takeIf { it > 0L }?.let { "Created ${dateFormatter.format(Date(it))}" }
      }
      if (eventTime != null) {
        Text(
          text = eventTime,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      // Action buttons strictly matching allowed state machine transitions
      when (item.state) {
        "PENDING" -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = { onRequestAction(AdminConversionReviewAction.APPROVE) },
              enabled = !isActionInFlight,
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              modifier = Modifier
                .weight(1f)
                .testTag("admin_conversion_approve_${item.moderationId}")
            ) {
              Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.size(4.dp))
              Text("Approve")
            }
            OutlinedButton(
              onClick = { onRequestAction(AdminConversionReviewAction.REJECT) },
              enabled = !isActionInFlight,
              modifier = Modifier
                .weight(1f)
                .testTag("admin_conversion_reject_${item.moderationId}")
            ) {
              Icon(
                Icons.Outlined.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.size(4.dp))
              Text("Reject", color = MaterialTheme.colorScheme.error)
            }
          }
        }
        "APPROVED" -> {
          OutlinedButton(
            onClick = { onRequestAction(AdminConversionReviewAction.REVERSE) },
            enabled = !isActionInFlight,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("admin_conversion_reverse_${item.moderationId}")
          ) {
            Icon(
              Icons.Outlined.Undo,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text("Reverse Credited Reward", color = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }
}

/**
 * Confirmation dialog for Admin Conversion Moderation (APPROVE / REJECT / REVERSE).
 *
 * Displays server-authoritative TF & INR financial impact preview (`125 TF = ₹1`),
 * explicit partial-recovery/shortfall warning on reversal, and requires a non-empty
 * admin reason before submitting to `reviewCampaignConversion`.
 */
@Composable
fun CampaignConversionModerationDialog(
  item: AdminCampaignConversionItem,
  action: AdminConversionReviewAction,
  isSubmitting: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (reason: String) -> Unit
) {
  val tfColors = taskFlowColors()
  var reasonText by remember(item.moderationId, action) { mutableStateOf("") }
  var validationError by remember(item.moderationId, action) { mutableStateOf<String?>(null) }

  val title = when (action) {
    AdminConversionReviewAction.APPROVE -> "Approve Conversion"
    AdminConversionReviewAction.REJECT -> "Reject Conversion"
    AdminConversionReviewAction.REVERSE -> "Reverse Approved Conversion"
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = "${item.campaignTitle} (${item.displayRef})",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold
        )
        Text(
          text = "User: ${item.userDisplayName} • Network: ${item.networkName}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        // Server-Authoritative Financial Impact Preview (125 TF = ₹1)
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_conversion_financial_preview")
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "Financial Impact Preview (125 TF = ₹1)",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold
            )

            when (action) {
              AdminConversionReviewAction.APPROVE -> {
                PreviewMetricRow(
                  label = "Server Credit Amount",
                  value = "+${formatCoins(item.rewardTf)} (${formatRewardInr(item.rewardCents)})",
                  accent = tfColors.statusConfirmedFg
                )
                Text(
                  text = "Approving atomically credits the user's withdrawable wallet balance and updates campaign spend on the server.",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              AdminConversionReviewAction.REJECT -> {
                PreviewMetricRow(
                  label = "Wallet Impact",
                  value = "0 TF (₹0.00) — No credit issued",
                  accent = MaterialTheme.colorScheme.onSurface
                )
                PreviewMetricRow(
                  label = "Forfeited Pending Reward",
                  value = "${formatCoins(item.rewardTf)} (${formatRewardInr(item.rewardCents)})",
                  accent = MaterialTheme.colorScheme.error
                )
              }
              AdminConversionReviewAction.REVERSE -> {
                PreviewMetricRow(
                  label = "Original Credited Amount",
                  value = "${formatCoins(item.rewardTf)} (${formatRewardInr(item.rewardCents)})",
                  accent = MaterialTheme.colorScheme.onSurface
                )
                PreviewMetricRow(
                  label = "Expected Recoverable Now",
                  value = "${formatCoins(item.expectedRecoverableTf)} (${formatRewardInr(item.expectedRecoverableCents)})",
                  accent = tfColors.statusConfirmedFg
                )
                PreviewMetricRow(
                  label = "Estimated Shortfall",
                  value = "${formatCoins(item.estimatedShortfallTf)} (${formatRewardInr(item.estimatedShortfallCents)})",
                  accent = if (item.estimatedShortfallTf > 0L) tfColors.goldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.recoveredTf > 0L || item.shortfallTf > 0L) {
                  PreviewMetricRow(
                    label = "Recorded Recovered / Shortfall",
                    value = "${formatCoins(item.recoveredTf)} / ${formatCoins(item.shortfallTf)}",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = tfColors.goldAccent.copy(alpha = 0.16f),
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_conversion_reversal_warning")
                ) {
                  Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                  ) {
                    Icon(
                      imageVector = Icons.Outlined.WarningAmber,
                      contentDescription = "Partial recovery warning",
                      tint = tfColors.goldAccent,
                      modifier = Modifier.size(16.dp)
                    )
                    Text(
                      text = "Warning: Full recovery is not guaranteed. If the user has already spent or withdrawn funds, server reversal will recover only available balance without creating negative wallets, recording the remainder as a partial-recovery shortfall.",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                  }
                }
              }
            }
          }
        }

        // Mandatory Admin Reason Field
        OutlinedTextField(
          value = reasonText,
          onValueChange = {
            reasonText = it.take(500)
            validationError = null
          },
          label = { Text("Mandatory Admin Reason / Notes *") },
          placeholder = {
            Text(
              when (action) {
                AdminConversionReviewAction.APPROVE -> "e.g., Verified against advertiser conversion report"
                AdminConversionReviewAction.REJECT -> "e.g., Advertiser rejected duplicate install"
                AdminConversionReviewAction.REVERSE -> "e.g., Partner chargeback notification received"
              }
            )
          },
          minLines = 2,
          maxLines = 4,
          isError = validationError != null,
          supportingText = {
            if (validationError != null) {
              Text(validationError!!, color = MaterialTheme.colorScheme.error)
            } else {
              Text("Required for audit logs (max 500 characters).")
            }
          },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_conversion_reason_input")
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val clean = reasonText.trim()
          if (clean.isEmpty()) {
            validationError = "Please enter a clear reason before confirming."
          } else {
            onConfirm(clean)
          }
        },
        enabled = !isSubmitting,
        colors = when (action) {
          AdminConversionReviewAction.APPROVE -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
          AdminConversionReviewAction.REJECT,
          AdminConversionReviewAction.REVERSE -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
          )
        },
        modifier = Modifier.testTag("admin_conversion_confirm_button")
      ) {
        if (isSubmitting) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = Color.White
          )
        } else {
          Text("Confirm ${action.label}")
        }
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        enabled = !isSubmitting,
        modifier = Modifier.testTag("admin_conversion_cancel_button")
      ) {
        Text("Cancel")
      }
    }
  )
}

@Composable
private fun PreviewMetricRow(
  label: String,
  value: String,
  accent: Color
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.Bold,
      color = accent
    )
  }
}
