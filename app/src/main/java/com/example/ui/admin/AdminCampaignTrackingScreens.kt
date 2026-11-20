package com.example.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AdminCampaignNetworkItem
import com.example.data.model.AdminCampaignTrackingConfigItem
import com.example.data.model.AdminCampaignTrackingUiState
import com.example.data.model.CampaignAnalyticsDateRange
import com.example.data.model.CampaignPerformanceRow
import com.example.data.model.CampaignTrackingSummaryMetrics
import com.example.data.model.NetworkPerformanceRow
import com.example.data.model.coinsToCents
import com.example.data.model.formatApprovalRateDisplay
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.ui.theme.taskFlowColors
import java.util.Locale

private enum class CampaignTrackingSubTab(val label: String, val tag: String) {
  OVERVIEW("Overview", "campaign_tracking_tab_overview"),
  NETWORKS("Networks", "campaign_tracking_tab_networks"),
  CAMPAIGNS("Campaigns", "campaign_tracking_tab_campaigns"),
  ANALYTICS("Analytics", "campaign_tracking_tab_analytics")
}

private data class PendingStatusConfirmation(
  val title: String,
  val message: String,
  val confirmLabel: String,
  val isDestructive: Boolean,
  val onConfirm: () -> Unit
)

@Composable
fun AdminCampaignTrackingSection(
  state: AdminCampaignTrackingUiState,
  onRefreshAll: () -> Unit,
  onSelectDateRange: (CampaignAnalyticsDateRange) -> Unit,
  onLoadMoreCampaignAnalytics: () -> Unit,
  onLoadMoreNetworkAnalytics: () -> Unit,
  onLoadMoreNetworks: () -> Unit,
  onLoadMoreConfigs: () -> Unit,
  onSaveNetwork: (AdminCampaignNetworkItem, Boolean, () -> Unit) -> Unit,
  onToggleNetworkStatus: (String, Boolean) -> Unit,
  onSaveCampaignConfig: (AdminCampaignTrackingConfigItem, Boolean, () -> Unit) -> Unit,
  onToggleCampaignConfigStatus: (String, Boolean) -> Unit,
  onClearFeedback: () -> Unit
) {
  val tfColors = taskFlowColors()
  var activeSubTab by rememberSaveable { mutableStateOf(CampaignTrackingSubTab.OVERVIEW) }
  var editingNetwork by remember { mutableStateOf<AdminCampaignNetworkItem?>(null) }
  var showNetworkDialog by remember { mutableStateOf(false) }
  var editingConfig by remember { mutableStateOf<AdminCampaignTrackingConfigItem?>(null) }
  var showConfigDialog by remember { mutableStateOf(false) }
  var pendingStatusConfirm by remember { mutableStateOf<PendingStatusConfirmation?>(null) }

  if (showNetworkDialog) {
    CampaignNetworkEditorDialog(
      initial = editingNetwork,
      isSaving = state.isSaving,
      onDismiss = { showNetworkDialog = false },
      onSave = { item, isEdit ->
        onSaveNetwork(item, isEdit) { showNetworkDialog = false }
      }
    )
  }

  if (showConfigDialog) {
    CampaignTrackingConfigEditorDialog(
      initial = editingConfig,
      networks = state.networks.items,
      isSaving = state.isSaving,
      onDismiss = { showConfigDialog = false },
      onSave = { item, isEdit ->
        onSaveCampaignConfig(item, isEdit) { showConfigDialog = false }
      }
    )
  }

  pendingStatusConfirm?.let { confirm ->
    CampaignStatusConfirmationDialog(
      title = confirm.title,
      message = confirm.message,
      confirmLabel = confirm.confirmLabel,
      isDestructive = confirm.isDestructive,
      onDismiss = { pendingStatusConfirm = null },
      onConfirm = {
        val action = confirm.onConfirm
        pendingStatusConfirm = null
        action()
      }
    )
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .testTag("admin_campaign_tracking_root")
  ) {
    // Compact Header & Sub-tab navigation
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = 1.dp,
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Universal Campaign Tracking",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "Server-authoritative S2S networks, configs & net TF analytics (125 TF = ₹1)",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }
          IconButton(
            onClick = onRefreshAll,
            modifier = Modifier.testTag("campaign_tracking_refresh_button")
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "Refresh Campaign Tracking Data",
              tint = MaterialTheme.colorScheme.primary
            )
          }
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          CampaignTrackingSubTab.entries.forEach { tab ->
            FilterChip(
              selected = activeSubTab == tab,
              onClick = { activeSubTab = tab },
              label = { Text(tab.label) },
              modifier = Modifier.testTag(tab.tag)
            )
          }
        }
      }
    }

    // Feedback Banner
    state.feedbackMessage?.let { msg ->
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (state.feedbackIsError) {
          MaterialTheme.colorScheme.errorContainer
        } else {
          tfColors.statusConfirmedBg
        },
        border = BorderStroke(
          1.dp,
          if (state.feedbackIsError) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
          else tfColors.statusConfirmedBorder
        ),
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .testTag("campaign_tracking_feedback_banner")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = if (state.feedbackIsError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (state.feedbackIsError) MaterialTheme.colorScheme.error else tfColors.statusConfirmedFg,
            modifier = Modifier.size(18.dp)
          )
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.feedbackIsError) MaterialTheme.colorScheme.onErrorContainer else tfColors.statusConfirmedFg,
            modifier = Modifier.weight(1f)
          )
          IconButton(
            onClick = onClearFeedback,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Dismiss feedback",
              modifier = Modifier.size(16.dp)
            )
          }
        }
      }
    }

    when (activeSubTab) {
      CampaignTrackingSubTab.OVERVIEW -> {
        CampaignTrackingOverviewTab(
          state = state,
          onRetry = onRefreshAll,
          onNavigateToSubTab = { activeSubTab = it }
        )
      }
      CampaignTrackingSubTab.NETWORKS -> {
        CampaignTrackingNetworksTab(
          state = state,
          onRetry = onRefreshAll,
          onAddNetwork = {
            editingNetwork = null
            showNetworkDialog = true
          },
          onEditNetwork = { net ->
            editingNetwork = net
            showNetworkDialog = true
          },
          onRequestToggleStatus = { net ->
            val nextActive = !net.isActive
            pendingStatusConfirm = PendingStatusConfirmation(
              title = if (nextActive) "Enable Network?" else "Disable Network?",
              message = if (nextActive) {
                "Enable '${net.name}' (${net.networkId}) for campaign tracking and postback verification?"
              } else {
                "Disable '${net.name}' (${net.networkId})? New click issuance and activation of linked campaigns will be blocked."
              },
              confirmLabel = if (nextActive) "Enable" else "Disable",
              isDestructive = !nextActive,
              onConfirm = { onToggleNetworkStatus(net.networkId, nextActive) }
            )
          },
          onLoadMore = onLoadMoreNetworks
        )
      }
      CampaignTrackingSubTab.CAMPAIGNS -> {
        CampaignTrackingConfigsTab(
          state = state,
          onRetry = onRefreshAll,
          onCreateConfig = {
            editingConfig = null
            showConfigDialog = true
          },
          onEditConfig = { cfg ->
            editingConfig = cfg
            showConfigDialog = true
          },
          onRequestToggleStatus = { cfg ->
            val nextActive = !cfg.isActive
            pendingStatusConfirm = PendingStatusConfirmation(
              title = if (nextActive) "Activate Campaign?" else "Pause Campaign?",
              message = if (nextActive) {
                "Activate '${cfg.title}' (${cfg.campaignId}) so server-issued tracking clicks can be generated?"
              } else {
                "Pause '${cfg.title}' (${cfg.campaignId})? New click issuance will stop immediately while preserving existing attribution logs."
              },
              confirmLabel = if (nextActive) "Activate" else "Pause",
              isDestructive = !nextActive,
              onConfirm = { onToggleCampaignConfigStatus(cfg.campaignId, nextActive) }
            )
          },
          onLoadMore = onLoadMoreConfigs
        )
      }
      CampaignTrackingSubTab.ANALYTICS -> {
        CampaignTrackingAnalyticsTab(
          state = state,
          onSelectDateRange = onSelectDateRange,
          onRetry = { onSelectDateRange(state.selectedDateRange) },
          onLoadMoreCampaigns = onLoadMoreCampaignAnalytics,
          onLoadMoreNetworks = onLoadMoreNetworkAnalytics
        )
      }
    }
  }
}

@Composable
private fun CampaignTrackingOverviewTab(
  state: AdminCampaignTrackingUiState,
  onRetry: () -> Unit,
  onNavigateToSubTab: (CampaignTrackingSubTab) -> Unit
) {
  if (state.isLoadingDashboard && !state.hasLoadedDashboard) {
    TrackingLoadingBlock(
      label = "Loading server-authoritative tracking metrics...",
      tag = "campaign_tracking_overview_loading"
    )
    return
  }

  if (state.dashboardError != null && !state.hasLoadedDashboard) {
    TrackingErrorBlock(
      message = state.dashboardError,
      onRetry = onRetry,
      errorTag = "campaign_tracking_overview_error",
      retryTag = "campaign_tracking_retry_button"
    )
    return
  }

  val summary = state.summary
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("overview_lazy_column"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      GrossVsNetFinancialCard(
        summary = summary,
        dateRangeLabel = state.selectedDateRange.label,
        tag = "overview_gross_vs_net_card"
      )
    }

    item {
      Text(
        text = "Campaign & Conversion Summary",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
      )
    }

    item {
      MetricCardRow(
        leftTitle = "Total Campaigns",
        leftValue = summary.totalCampaigns.toString(),
        leftSub = "${summary.totalNetworks} registered networks",
        leftTag = "metric_total_campaigns",
        rightTitle = "Active Campaigns",
        rightValue = summary.activeCampaigns.toString(),
        rightSub = "${summary.activeNetworks} active networks",
        rightTag = "metric_active_campaigns"
      )
    }

    item {
      MetricCardRow(
        leftTitle = "Total Clicks",
        leftValue = summary.totalClicks.toString(),
        leftSub = "Window: ${state.selectedDateRange.label}",
        leftTag = "metric_total_clicks",
        rightTitle = "Approval Rate",
        rightValue = formatApprovalRateDisplay(summary.approvalRatePercent, summary.totalConversions),
        rightSub = "${summary.totalConversions} total conversions",
        rightTag = "metric_approval_rate"
      )
    }

    item {
      MetricCardRow(
        leftTitle = "Pending Conversions",
        leftValue = summary.pendingConversions.toString(),
        leftSub = "Awaiting verification",
        leftTag = "metric_pending_conversions",
        rightTitle = "Approved Conversions",
        rightValue = summary.approvedConversions.toString(),
        rightSub = "Verified & credited",
        rightTag = "metric_approved_conversions"
      )
    }

    item {
      MetricCardRow(
        leftTitle = "Rejected Conversions",
        leftValue = summary.rejectedConversions.toString(),
        leftSub = "Denied or invalid",
        leftTag = "metric_rejected_conversions",
        rightTitle = "Reversed Conversions",
        rightValue = summary.reversedConversions.toString(),
        rightSub = "Clawed back (${formatCoins(summary.reversedTf)})",
        rightTag = "metric_reversed_conversions"
      )
    }

    item {
      Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Quick Management",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = { onNavigateToSubTab(CampaignTrackingSubTab.NETWORKS) },
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Networks")
            }
            OutlinedButton(
              onClick = { onNavigateToSubTab(CampaignTrackingSubTab.CAMPAIGNS) },
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Campaigns")
            }
          }
          Button(
            onClick = { onNavigateToSubTab(CampaignTrackingSubTab.ANALYTICS) },
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open Detailed Analytics")
          }
        }
      }
    }
  }
}

@Composable
private fun GrossVsNetFinancialCard(
  summary: CampaignTrackingSummaryMetrics,
  dateRangeLabel: String,
  tag: String
) {
  val tfColors = taskFlowColors()
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, tfColors.rewardBadgeBorder),
    modifier = Modifier
      .fillMaxWidth()
      .testTag(tag)
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "TF Reward Accounting",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )
        StatusPill(
          text = dateRangeLabel,
          bg = tfColors.rewardBadgeBg,
          fg = tfColors.rewardBadgeText,
          border = tfColors.rewardBadgeBorder
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Column(
          modifier = Modifier
            .weight(1f)
            .semantics(mergeDescendants = true) {}
            .testTag("metric_gross_tf_rewards"),
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          Text(
            text = "Total TF Credited (Gross)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = formatCoins(summary.grossCreditedTf),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = formatRewardInr(summary.grossCreditedCents),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        Column(
          modifier = Modifier
            .weight(1f)
            .semantics(mergeDescendants = true) {}
            .testTag("metric_net_tf_rewards"),
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          Text(
            text = "Net TF After Reversals",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = formatCoins(summary.netCreditedTf),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tfColors.goldText
          )
          Text(
            text = formatRewardInr(summary.netCreditedCents),
            style = MaterialTheme.typography.labelSmall,
            color = tfColors.goldText
          )
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "Reversed TF Deducted",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = "-${formatCoins(summary.reversedTf)} (${formatRewardInr(coinsToCents(summary.reversedTf))})",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = tfColors.debitText
        )
      }
    }
  }
}

@Composable
private fun CampaignTrackingNetworksTab(
  state: AdminCampaignTrackingUiState,
  onRetry: () -> Unit,
  onAddNetwork: () -> Unit,
  onEditNetwork: (AdminCampaignNetworkItem) -> Unit,
  onRequestToggleStatus: (AdminCampaignNetworkItem) -> Unit,
  onLoadMore: () -> Unit
) {
  val tfColors = taskFlowColors()
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("networks_lazy_column"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Registered Networks (${state.networks.totalCount})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "S2S adapters, signature schemes & Secret Manager bindings",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Button(
          onClick = onAddNetwork,
          modifier = Modifier.testTag("add_campaign_network_button")
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(4.dp))
          Text("Add")
        }
      }
    }

    if (state.isLoadingNetworks && state.networks.items.isEmpty()) {
      item {
        TrackingLoadingBlock(
          label = "Loading campaign networks...",
          tag = "campaign_networks_loading"
        )
      }
    } else if (state.networksError != null && state.networks.items.isEmpty()) {
      item {
        TrackingErrorBlock(
          message = state.networksError,
          onRetry = onRetry,
          errorTag = "campaign_networks_error",
          retryTag = "campaign_networks_retry_button"
        )
      }
    } else if (state.networks.items.isEmpty()) {
      item {
        TrackingEmptyBlock(
          icon = Icons.Default.Hub,
          title = "No Campaign Networks Registered",
          subtitle = "Add an affiliate postback, S2S API, Telegram, or manual review network to begin configuring tracked campaigns.",
          tag = "campaign_networks_empty_state"
        )
      }
    } else {
      items(state.networks.items, key = { it.networkId }) { net ->
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("network_card_${net.networkId}")
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = net.name,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "ID: ${net.networkId} • ${net.adapterType.replace('_', ' ')}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              StatusPill(
                text = if (net.isActive) "ACTIVE" else "DISABLED",
                bg = if (net.isActive) tfColors.statusConfirmedBg else MaterialTheme.colorScheme.errorContainer,
                fg = if (net.isActive) tfColors.statusConfirmedFg else MaterialTheme.colorScheme.onErrorContainer,
                border = if (net.isActive) tfColors.statusConfirmedBorder else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
              )
            }

            Text(
              text = "Signature: ${net.signatureConfig.algorithm} (${net.signatureConfig.signingMode}/${net.signatureConfig.encoding})",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface
            )

            if (net.signatureConfig.secretEnvKey.isNotBlank()) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Lock,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(14.dp)
                )
                Text(
                  text = "Secret Key: ${net.signatureConfig.secretEnvKey} (Server-Only)",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary
                )
              }
            }

            if (net.allowedDestinationHosts.isNotEmpty()) {
              Text(
                text = "Hosts: ${net.allowedDestinationHosts.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = { onEditNetwork(net) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("edit_network_${net.networkId}")
              ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit")
              }
              OutlinedButton(
                onClick = { onRequestToggleStatus(net) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("toggle_network_${net.networkId}")
              ) {
                Icon(
                  imageVector = if (net.isActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (net.isActive) "Disable" else "Enable")
              }
            }
          }
        }
      }

      if (state.networks.hasMore) {
        item {
          OutlinedButton(
            onClick = onLoadMore,
            enabled = !state.isLoadingNetworks,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("load_more_networks_button")
          ) {
            Text(if (state.isLoadingNetworks) "Loading..." else "Load More Networks")
          }
        }
      }
    }
  }
}

@Composable
private fun CampaignTrackingConfigsTab(
  state: AdminCampaignTrackingUiState,
  onRetry: () -> Unit,
  onCreateConfig: () -> Unit,
  onEditConfig: (AdminCampaignTrackingConfigItem) -> Unit,
  onRequestToggleStatus: (AdminCampaignTrackingConfigItem) -> Unit,
  onLoadMore: () -> Unit
) {
  val tfColors = taskFlowColors()
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("configs_lazy_column"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Tracked Campaigns (${state.configs.totalCount})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Server-authoritative rewards, budgets & eligibility rules",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Button(
          onClick = onCreateConfig,
          modifier = Modifier.testTag("add_campaign_config_button")
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(4.dp))
          Text("Create")
        }
      }
    }

    if (state.isLoadingConfigs && state.configs.items.isEmpty()) {
      item {
        TrackingLoadingBlock(
          label = "Loading campaign configurations...",
          tag = "campaign_configs_loading"
        )
      }
    } else if (state.configsError != null && state.configs.items.isEmpty()) {
      item {
        TrackingErrorBlock(
          message = state.configsError,
          onRetry = onRetry,
          errorTag = "campaign_configs_error",
          retryTag = "campaign_configs_retry_button"
        )
      }
    } else if (state.configs.items.isEmpty()) {
      item {
        TrackingEmptyBlock(
          icon = Icons.Default.TrackChanges,
          title = "No Tracked Campaigns Configured",
          subtitle = "Create a tracked campaign linked to a registered network with server-authoritative TF rewards and budget caps.",
          tag = "campaign_configs_empty_state"
        )
      }
    } else {
      items(state.configs.items, key = { it.campaignId }) { cfg ->
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("campaign_config_card_${cfg.campaignId}")
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = cfg.title,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "${cfg.campaignId} • Net: ${cfg.networkId} • ${cfg.verificationMode.replace('_', ' ')}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              StatusPill(
                text = if (cfg.isActive) "ACTIVE" else "PAUSED",
                bg = if (cfg.isActive) tfColors.statusConfirmedBg else tfColors.neutralSurfaceBg,
                fg = if (cfg.isActive) tfColors.statusConfirmedFg else tfColors.neutralSurfaceFg,
                border = if (cfg.isActive) tfColors.statusConfirmedBorder else tfColors.neutralSurfaceBorder
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "Reward: ${formatCoins(cfg.rewardTf)} (${formatRewardInr(cfg.rewardCents)})",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = tfColors.goldText
              )
              Text(
                text = if (cfg.conversionRequirements.requireAdminApproval) "Manual Review" else "Auto-Verify",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
              )
            }

            Text(
              text = "Targeting: ${cfg.allowedCountries.joinToString(", ")} • ${cfg.allowedPlatforms.joinToString(", ")}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (cfg.startDate.isNotBlank() || cfg.endDate.isNotBlank()) {
              Text(
                text = "Schedule: ${cfg.startDate.ifBlank { "Now" }} → ${cfg.endDate.ifBlank { "Open" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }

            val budgetProgress = when {
              cfg.rewardBudgetTf > 0L -> (cfg.spentBudgetTf.toFloat() / cfg.rewardBudgetTf.toFloat()).coerceIn(0f, 1f)
              cfg.submissionBudget > 0L -> (cfg.usedSubmissions.toFloat() / cfg.submissionBudget.toFloat()).coerceIn(0f, 1f)
              else -> null
            }
            if (budgetProgress != null) {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = "Budget Usage: ${cfg.usedSubmissions}/${if (cfg.submissionBudget > 0) cfg.submissionBudget else "∞"} conv • ${formatCoins(cfg.spentBudgetTf)}/${if (cfg.rewardBudgetTf > 0) formatCoins(cfg.rewardBudgetTf) else "∞"}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                  progress = { budgetProgress },
                  modifier = Modifier.fillMaxWidth()
                )
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(
                onClick = { onEditConfig(cfg) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("edit_campaign_config_${cfg.campaignId}")
              ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit")
              }
              OutlinedButton(
                onClick = { onRequestToggleStatus(cfg) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("toggle_campaign_config_${cfg.campaignId}")
              ) {
                Icon(
                  imageVector = if (cfg.isActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (cfg.isActive) "Pause" else "Activate")
              }
            }
          }
        }
      }

      if (state.configs.hasMore) {
        item {
          OutlinedButton(
            onClick = onLoadMore,
            enabled = !state.isLoadingConfigs,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("load_more_configs_button")
          ) {
            Text(if (state.isLoadingConfigs) "Loading..." else "Load More Campaigns")
          }
        }
      }
    }
  }
}

@Composable
private fun CampaignTrackingAnalyticsTab(
  state: AdminCampaignTrackingUiState,
  onSelectDateRange: (CampaignAnalyticsDateRange) -> Unit,
  onRetry: () -> Unit,
  onLoadMoreCampaigns: () -> Unit,
  onLoadMoreNetworks: () -> Unit
) {
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("analytics_lazy_column"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = "Date Range Filter",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          CampaignAnalyticsDateRange.entries.forEach { range ->
            FilterChip(
              selected = state.selectedDateRange == range,
              onClick = { onSelectDateRange(range) },
              label = { Text(range.label) },
              modifier = Modifier.testTag("analytics_range_${range.wireValue}")
            )
          }
        }
      }
    }

    if (state.isLoadingDashboard && !state.hasLoadedDashboard) {
      item {
        TrackingLoadingBlock(
          label = "Computing server-authoritative analytics...",
          tag = "campaign_analytics_loading"
        )
      }
      return@LazyColumn
    }

    if (state.dashboardError != null) {
      item {
        TrackingErrorBlock(
          message = state.dashboardError,
          onRetry = onRetry,
          errorTag = "campaign_analytics_error",
          retryTag = "campaign_analytics_retry_button"
        )
      }
      return@LazyColumn
    }

    item {
      GrossVsNetFinancialCard(
        summary = state.summary,
        dateRangeLabel = state.selectedDateRange.label,
        tag = "analytics_gross_vs_net_card"
      )
    }

    val hasAnyRows =
      state.campaignPerformance.items.isNotEmpty() || state.networkPerformance.items.isNotEmpty()

    if (!hasAnyRows) {
      item {
        TrackingEmptyBlock(
          icon = Icons.Default.Analytics,
          title = "No Analytics Data For ${state.selectedDateRange.label}",
          subtitle = "No campaign clicks or conversions were recorded in this period.",
          tag = "campaign_analytics_empty_state"
        )
      }
      return@LazyColumn
    }

    item {
      Text(
        text = "Campaign-wise Performance (${state.campaignPerformance.totalCount})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
      )
    }

    items(state.campaignPerformance.items, key = { "camp_${it.campaignId}" }) { row ->
      CampaignPerformanceCard(row)
    }

    if (state.campaignPerformance.hasMore) {
      item {
        OutlinedButton(
          onClick = onLoadMoreCampaigns,
          enabled = !state.isLoadingDashboard,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("load_more_campaign_analytics_button")
        ) {
          Text(if (state.isLoadingDashboard) "Loading..." else "Load More Campaigns")
        }
      }
    }

    item {
      Spacer(Modifier.height(4.dp))
      Text(
        text = "Network-wise Performance (${state.networkPerformance.totalCount})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
      )
    }

    items(state.networkPerformance.items, key = { "net_${it.networkId}" }) { row ->
      NetworkPerformanceCard(row)
    }

    if (state.networkPerformance.hasMore) {
      item {
        OutlinedButton(
          onClick = onLoadMoreNetworks,
          enabled = !state.isLoadingDashboard,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("load_more_network_analytics_button")
        ) {
          Text(if (state.isLoadingDashboard) "Loading..." else "Load More Networks")
        }
      }
    }
  }
}

@Composable
private fun CampaignPerformanceCard(row: CampaignPerformanceRow) {
  val tfColors = taskFlowColors()
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = true) {}
      .testTag("analytics_campaign_row_${row.campaignId}")
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = row.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "${row.campaignId} • Net: ${row.networkId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        StatusPill(
          text = "Rate: ${formatApprovalRateDisplay(row.approvalRatePercent, row.totalConversions)}",
          bg = tfColors.neutralSurfaceBg,
          fg = tfColors.neutralSurfaceFg,
          border = tfColors.neutralSurfaceBorder
        )
      }

      Text(
        text = "Clicks: ${row.clicksCount} • Pending: ${row.pendingConversions} • Approved: ${row.approvedConversions} • Rejected: ${row.rejectedConversions} • Reversed: ${row.reversedConversions}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "Gross: ${formatCoins(row.grossCreditedTf)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = "Net: ${formatCoins(row.netCreditedTf)} (${formatRewardInr(row.netCreditedCents)})",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = tfColors.goldText
        )
      }

      row.budgetUtilizationPercent?.let { util ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Budget Utilization: ${String.format(Locale.US, "%.1f%%", util)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          LinearProgressIndicator(
            progress = { (util / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
          )
        }
      }
    }
  }
}

@Composable
private fun NetworkPerformanceCard(row: NetworkPerformanceRow) {
  val tfColors = taskFlowColors()
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = true) {}
      .testTag("analytics_network_row_${row.networkId}")
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = row.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "${row.networkId} • ${row.campaignsCount} campaigns • ${row.adapterType.replace('_', ' ')}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        StatusPill(
          text = "Rate: ${formatApprovalRateDisplay(row.approvalRatePercent, row.totalConversions)}",
          bg = tfColors.neutralSurfaceBg,
          fg = tfColors.neutralSurfaceFg,
          border = tfColors.neutralSurfaceBorder
        )
      }

      Text(
        text = "Clicks: ${row.clicksCount} • Pending: ${row.pendingConversions} • Approved: ${row.approvedConversions} • Rejected: ${row.rejectedConversions} • Reversed: ${row.reversedConversions}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "Gross: ${formatCoins(row.grossCreditedTf)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = "Net: ${formatCoins(row.netCreditedTf)} (${formatRewardInr(row.netCreditedCents)})",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = tfColors.goldText
        )
      }
    }
  }
}

@Composable
private fun MetricCardRow(
  leftTitle: String,
  leftValue: String,
  leftSub: String,
  leftTag: String,
  rightTitle: String,
  rightValue: String,
  rightSub: String,
  rightTag: String
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    SummaryMetricCard(
      title = leftTitle,
      value = leftValue,
      subtitle = leftSub,
      modifier = Modifier
        .weight(1f)
        .testTag(leftTag)
    )
    SummaryMetricCard(
      title = rightTitle,
      value = rightValue,
      subtitle = rightSub,
      modifier = Modifier
        .weight(1f)
        .testTag(rightTag)
    )
  }
}

@Composable
private fun SummaryMetricCard(
  title: String,
  value: String,
  subtitle: String,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier.semantics(mergeDescendants = true) {}
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun StatusPill(
  text: String,
  bg: Color,
  fg: Color,
  border: Color
) {
  Surface(
    shape = RoundedCornerShape(50),
    color = bg,
    border = BorderStroke(1.dp, border)
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      color = fg,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )
  }
}

@Composable
private fun TrackingLoadingBlock(label: String, tag: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(32.dp)
      .testTag(tag),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      CircularProgressIndicator()
      Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun TrackingErrorBlock(
  message: String,
  onRetry: () -> Unit,
  errorTag: String,
  retryTag: String
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp)
      .testTag(errorTag)
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Icon(
        imageVector = Icons.Default.ErrorOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(28.dp)
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer
      )
      TextButton(
        onClick = onRetry,
        modifier = Modifier.testTag(retryTag)
      ) {
        Text("Retry")
      }
    }
  }
}

@Composable
private fun TrackingEmptyBlock(
  icon: ImageVector,
  title: String,
  subtitle: String,
  tag: String
) {
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier
      .fillMaxWidth()
      .testTag(tag)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(32.dp)
      )
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
