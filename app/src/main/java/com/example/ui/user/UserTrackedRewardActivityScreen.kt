package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.UserTrackedRewardActivityItem
import com.example.data.model.UserTrackedRewardActivityUiState
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.ui.theme.taskFlowColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ACTIVITY_STATUS_FILTERS = listOf(
  "ALL" to "All",
  "PENDING" to "Pending Verification",
  "APPROVED" to "Approved",
  "REJECTED" to "Rejected",
  "REVERSED" to "Reversed"
)

/**
 * Phase 3E.1 — Read-only User Tracked Reward Activity screen.
 *
 * Security & privacy invariants:
 * - Strictly read-only (users can never mutate conversion states).
 * - Displays only campaign title, conversion status, reward TF/INR (125 TF = ₹1),
 *   and relevant timestamps.
 * - Never displays click IDs, raw conversion IDs, external transaction IDs,
 *   partner parameters, signatures, secrets, or internal moderation notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserTrackedRewardActivityScreen(
  state: UserTrackedRewardActivityUiState,
  onSelectStatusFilter: (String) -> Unit,
  onRefresh: () -> Unit,
  onLoadMore: () -> Unit,
  onBack: () -> Unit
) {
  BackHandler(onBack = onBack)
  val tfColors = taskFlowColors()

  val dateFormatter = remember {
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = "Tracked Reward Activity",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Server-verified offer conversions • 125 TF = ₹1",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("tracked_activity_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back"
            )
          }
        },
        actions = {
          IconButton(
            onClick = onRefresh,
            enabled = !state.isLoading,
            modifier = Modifier.testTag("tracked_activity_refresh_button")
          ) {
            Icon(
              imageVector = Icons.Outlined.Refresh,
              contentDescription = "Refresh tracked reward activity"
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background
        )
      )
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .widthIn(max = 680.dp)
          .testTag("tracked_activity_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        item(key = "activity_summary_counts") {
          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              ActivityCountMetric("Pending", state.counts.pending, tfColors.goldAccent)
              ActivityCountMetric("Approved", state.counts.approved, tfColors.statusConfirmedFg)
              ActivityCountMetric("Rejected", state.counts.rejected, MaterialTheme.colorScheme.error)
              ActivityCountMetric("Reversed", state.counts.reversed, MaterialTheme.colorScheme.tertiary)
            }
          }
        }

        item(key = "activity_status_filters") {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            ACTIVITY_STATUS_FILTERS.forEach { (wire, label) ->
              FilterChip(
                selected = state.selectedStatusFilter == wire,
                onClick = { onSelectStatusFilter(wire) },
                label = { Text(label) },
                modifier = Modifier.testTag("tracked_activity_filter_${wire.lowercase(Locale.US)}")
              )
            }
          }
        }

        if (!state.error.isNullOrBlank()) {
          item(key = "activity_error_card") {
            Card(
              shape = RoundedCornerShape(14.dp),
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
                  text = state.error,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer
                )
                Button(
                  onClick = onRefresh,
                  modifier = Modifier.testTag("tracked_activity_retry_button")
                ) {
                  Text("Retry")
                }
              }
            }
          }
        }

        if (state.isLoading && state.items.isEmpty()) {
          item(key = "activity_loading") {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
              contentAlignment = Alignment.Center
            ) {
              CircularProgressIndicator()
            }
          }
        } else if (state.items.isEmpty() && state.error.isNullOrBlank()) {
          item(key = "activity_empty") {
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Outlined.History,
                  contentDescription = null,
                  modifier = Modifier.size(36.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                  text = "No Tracked Reward Activity Yet",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = "Start an eligible tracked offer from the Offers tab. Once verified by our server, your reward status will appear here.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        } else {
          items(state.items, key = { it.activityId.ifBlank { "${it.campaignId}_${it.createdAtMillis}" } }) { item ->
            UserTrackedActivityCard(item = item, dateFormatter = dateFormatter)
          }

          if (state.hasMore) {
            item(key = "activity_load_more") {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
              ) {
                OutlinedButton(
                  onClick = onLoadMore,
                  enabled = !state.isLoading,
                  modifier = Modifier.testTag("tracked_activity_load_more_button")
                ) {
                  if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                  } else {
                    Text("Load More")
                  }
                }
              }
            }
          }
        }

        item(key = "activity_footer_spacer") {
          Spacer(modifier = Modifier.height(16.dp))
        }
      }
    }
  }
}

@Composable
private fun ActivityCountMetric(
  label: String,
  count: Int,
  accent: Color
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = count.toString(),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = accent
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun UserTrackedActivityCard(
  item: UserTrackedRewardActivityItem,
  dateFormatter: SimpleDateFormat
) {
  val tfColors = taskFlowColors()
  val (statusColor, statusIcon) = when (item.status) {
    "APPROVED" -> tfColors.statusConfirmedFg to Icons.Outlined.CheckCircle
    "REJECTED" -> MaterialTheme.colorScheme.error to Icons.Outlined.ErrorOutline
    "REVERSED" -> MaterialTheme.colorScheme.tertiary to Icons.Outlined.Undo
    else -> tfColors.goldAccent to Icons.Outlined.Schedule
  }

  val statusTimestampLabel = remember(item) {
    when (item.status) {
      "APPROVED" -> if (item.verifiedAtMillis > 0L) "Approved: ${dateFormatter.format(Date(item.verifiedAtMillis))}" else null
      "REJECTED" -> if (item.rejectedAtMillis > 0L) "Rejected: ${dateFormatter.format(Date(item.rejectedAtMillis))}" else null
      "REVERSED" -> if (item.reversedAtMillis > 0L) "Reversed: ${dateFormatter.format(Date(item.reversedAtMillis))}" else null
      else -> null
    }
  }
  val createdLabel = remember(item.createdAtMillis) {
    if (item.createdAtMillis > 0L) "Tracked: ${dateFormatter.format(Date(item.createdAtMillis))}" else null
  }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("tracked_activity_item_${item.activityId}")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Text(
          text = item.campaignTitle,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = statusColor.copy(alpha = 0.14f)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = statusIcon,
              contentDescription = item.statusLabel,
              tint = statusColor,
              modifier = Modifier.size(14.dp)
            )
            Text(
              text = item.statusLabel,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = statusColor
            )
          }
        }
      }

      if (item.rewardTf > 0L) {
        val rewardPrefix = when (item.status) {
          "APPROVED" -> "Credited Reward"
          "REVERSED" -> "Reversed Reward"
          "REJECTED" -> "Offer Reward (Not Credited)"
          else -> "Expected Reward"
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = rewardPrefix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "${formatCoins(item.rewardTf)} (${formatRewardInr(item.rewardCents)})",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (item.status == "APPROVED") tfColors.statusConfirmedFg else MaterialTheme.colorScheme.onSurface
          )
        }
      }

      if (createdLabel != null || statusTimestampLabel != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          if (createdLabel != null) {
            Text(
              text = createdLabel,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          if (statusTimestampLabel != null) {
            Text(
              text = statusTimestampLabel,
              style = MaterialTheme.typography.labelSmall,
              color = statusColor
            )
          }
        }
      }
    }
  }
}
