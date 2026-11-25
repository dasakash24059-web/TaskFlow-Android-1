package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserTrackedCampaignItem
import com.example.data.model.UserTrackedCampaignsUiState
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.ui.components.AppIconThumbnail
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonCoinBadge
import com.example.ui.components.NeonGlassCard
import com.example.ui.components.resolveAppOrWebsiteLabel
import com.example.ui.theme.taskFlowColors

@Composable
fun OfferWallScreen(
  tasks: List<TaskItem> = emptyList(),
  onBack: () -> Unit,
  onSelectOffer: (String) -> Unit = {},
  trackedCampaignsState: UserTrackedCampaignsUiState = UserTrackedCampaignsUiState(),
  onRefreshTrackedCampaigns: () -> Unit = {},
  onLoadMoreTrackedCampaigns: () -> Unit = {},
  onLaunchTrackedCampaign: (UserTrackedCampaignItem) -> Unit = {},
  onDismissTrackedFeedback: () -> Unit = {},
  onOpenTrackedActivity: () -> Unit = {}
) {
  BackHandler(onBack = onBack)
  val tfColors = taskFlowColors()

  var selectedCategory by rememberSaveable { mutableStateOf("All") }
  val categories = listOf("All", "App Install", "Web Signups", "Surveys", "Quizzes")

  val availableOffers = remember(tasks, selectedCategory) {
    tasks.filter { task ->
      task.isActive &&
        task.status == TaskStatus.PENDING &&
        !task.isQuotaExhausted &&
        (selectedCategory == "All" || task.category.contains(selectedCategory, ignoreCase = true))
    }
  }

  NeonBackgroundBox {
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("offer_wall_screen"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Top Bar
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surface)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = MaterialTheme.colorScheme.onSurface
            )
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Campaign,
              contentDescription = null,
              tint = tfColors.goldAccent,
              modifier = Modifier.size(24.dp)
            )
            Text(
              text = "TaskFlow Offer Wall",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onBackground,
              fontWeight = FontWeight.SemiBold
            )
          }
          IconButton(
            onClick = onOpenTrackedActivity,
            modifier = Modifier
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surface)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
              .testTag("offer_wall_tracked_activity_button")
          ) {
            Icon(
              imageVector = Icons.Default.History,
              contentDescription = "Tracked Reward Activity",
              tint = MaterialTheme.colorScheme.primary
            )
          }
        }
      }

      // Hero Banner
      item {
        NeonGlassCard(
          modifier = Modifier.fillMaxWidth(),
          borderColor = MaterialTheme.colorScheme.outlineVariant,
          backgroundColor = MaterialTheme.colorScheme.surface
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
              text = "High-Paying Partner Offers",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Complete verified app installs, web signups, and surveys to earn bonus Coins.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      // Filter Chips
      item {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(vertical = 4.dp)
        ) {
          items(categories) { cat ->
            FilterChip(
              selected = selectedCategory == cat,
              onClick = { selectedCategory = cat },
              label = { Text(cat, fontWeight = FontWeight.SemiBold) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
              ),
              border = FilterChipDefaults.filterChipBorder(
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                selectedBorderColor = MaterialTheme.colorScheme.primary,
                enabled = true,
                selected = selectedCategory == cat
              )
            )
          }
        }
      }

      // Phase 3E.1 — Server-Authoritative Verified Tracked Offers
      if (
        trackedCampaignsState.isLoading ||
        trackedCampaignsState.items.isNotEmpty() ||
        !trackedCampaignsState.error.isNullOrBlank() ||
        !trackedCampaignsState.feedbackMessage.isNullOrBlank() ||
        !trackedCampaignsState.accountApproved
      ) {
        item(key = "tracked_offers_header") {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Verified Tracked Offers (${trackedCampaignsState.items.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "Server-tracked attribution • Rewards credited only after verification",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            IconButton(
              onClick = onRefreshTrackedCampaigns,
              enabled = !trackedCampaignsState.isLoading,
              modifier = Modifier.testTag("offer_wall_refresh_tracked_button")
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh tracked offers",
                tint = MaterialTheme.colorScheme.primary
              )
            }
          }
        }

        if (!trackedCampaignsState.accountApproved) {
          item(key = "tracked_offers_account_approval_notice") {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = tfColors.goldAccent.copy(alpha = 0.14f),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("offer_wall_account_not_approved_banner")
            ) {
              Text(
                text = "Your account is awaiting approval. You can preview offers now, and start tracked offers once approved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp)
              )
            }
          }
        }

        trackedCampaignsState.feedbackMessage?.let { msg ->
          item(key = "tracked_offers_feedback") {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (trackedCampaignsState.feedbackIsError) {
                MaterialTheme.colorScheme.errorContainer
              } else {
                tfColors.statusConfirmedBg
              },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("offer_wall_tracked_feedback_banner")
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Text(
                  text = msg,
                  style = MaterialTheme.typography.bodySmall,
                  color = if (trackedCampaignsState.feedbackIsError) {
                    MaterialTheme.colorScheme.onErrorContainer
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                  modifier = Modifier.weight(1f)
                )
                IconButton(
                  onClick = onDismissTrackedFeedback,
                  modifier = Modifier.size(24.dp)
                ) {
                  Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss message",
                    modifier = Modifier.size(16.dp)
                  )
                }
              }
            }
          }
        }

        if (!trackedCampaignsState.error.isNullOrBlank()) {
          item(key = "tracked_offers_error") {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = trackedCampaignsState.error,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onRefreshTrackedCampaigns) {
                  Text("Retry")
                }
              }
            }
          }
        }

        if (trackedCampaignsState.isLoading && trackedCampaignsState.items.isEmpty()) {
          item(key = "tracked_offers_loading") {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
              contentAlignment = Alignment.Center
            ) {
              CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
          }
        } else {
          items(trackedCampaignsState.items, key = { "tracked_camp_${it.campaignId}" }) { campaign ->
            TrackedCampaignOfferCard(
              campaign = campaign,
              accountApproved = trackedCampaignsState.accountApproved,
              isLaunching = trackedCampaignsState.launchingCampaignId == campaign.campaignId,
              anyLaunching = trackedCampaignsState.launchingCampaignId != null,
              onStartOffer = { onLaunchTrackedCampaign(campaign) }
            )
          }

          if (trackedCampaignsState.hasMore) {
            item(key = "tracked_offers_load_more") {
              Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
              ) {
                OutlinedButton(
                  onClick = onLoadMoreTrackedCampaigns,
                  enabled = !trackedCampaignsState.isLoading,
                  modifier = Modifier.testTag("offer_wall_tracked_load_more")
                ) {
                  Text("Load More Tracked Offers")
                }
              }
            }
          }
        }
      }

      // Offers List Header
      item {
        Text(
          text = "Available Offers (${availableOffers.size})",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onBackground,
          fontWeight = FontWeight.SemiBold
        )
      }

      if (availableOffers.isEmpty()) {
        item {
          NeonGlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            backgroundColor = MaterialTheme.colorScheme.surface
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text = "No offers found in this category",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Select another category above to view available offers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      } else {
        items(availableOffers, key = { "offer_${it.id}" }) { offer ->
          val coinReward = offer.getEffectiveRewardCoins()
          val appOrSiteLabel = resolveAppOrWebsiteLabel(offer)

          NeonGlassCard(
            modifier = Modifier.fillMaxWidth().testTag("offer_card_${offer.id}"),
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            backgroundColor = MaterialTheme.colorScheme.surface,
            onClick = { onSelectOffer(offer.id) }
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                AppIconThumbnail(
                  appIconUrl = offer.appIconUrl,
                  appName = offer.appName,
                  category = offer.category,
                  size = 48.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = offer.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = appOrSiteLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                  )
                }

                NeonCoinBadge(coins = coinReward)
              }

              Text(
                text = offer.description.ifBlank { "Complete requirements to receive instant coin rewards." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Surface(
                  shape = RoundedCornerShape(50),
                  color = tfColors.neutralSurfaceBg,
                  border = androidx.compose.foundation.BorderStroke(1.dp, tfColors.neutralSurfaceBorder)
                ) {
                  Text(
                    text = offer.category.ifBlank { "Verified Offer" },
                    style = MaterialTheme.typography.labelSmall,
                    color = tfColors.neutralSurfaceFg,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                  )
                }

                Button(
                  onClick = { onSelectOffer(offer.id) },
                  shape = RoundedCornerShape(12.dp),
                  colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                  )
                ) {
                  Text("Start Offer", fontWeight = FontWeight.SemiBold)
                  Spacer(modifier = Modifier.width(4.dp))
                  Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun TrackedCampaignOfferCard(
  campaign: UserTrackedCampaignItem,
  accountApproved: Boolean,
  isLaunching: Boolean,
  anyLaunching: Boolean,
  onStartOffer: () -> Unit
) {
  val tfColors = taskFlowColors()
  val statusLabel = when (campaign.userStatus) {
    "PENDING" -> "Pending Verification"
    "COMPLETED" -> "Completed"
    else -> "Available"
  }
  val statusColor = when (campaign.userStatus) {
    "PENDING" -> tfColors.goldAccent
    "COMPLETED" -> tfColors.statusConfirmedFg
    else -> MaterialTheme.colorScheme.primary
  }
  val canTapStart = accountApproved && campaign.canClick && !anyLaunching

  NeonGlassCard(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("tracked_campaign_card_${campaign.campaignId}"),
    borderColor = MaterialTheme.colorScheme.outlineVariant,
    backgroundColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = campaign.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = buildString {
              append(campaign.category.ifBlank { "Tracked Offer" })
              if (campaign.requiresAdminApproval) append(" • Manual Verification")
              campaign.remainingSubmissions?.let { rem ->
                append(" • $rem slots left")
              }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        NeonCoinBadge(coins = campaign.rewardTf)
      }

      if (campaign.description.isNotBlank()) {
        Text(
          text = campaign.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Surface(
            shape = RoundedCornerShape(50),
            color = statusColor.copy(alpha = 0.14f)
          ) {
            Text(
              text = statusLabel,
              style = MaterialTheme.typography.labelSmall,
              color = statusColor,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
          }
          Text(
            text = "${formatCoins(campaign.rewardTf)} (${formatRewardInr(campaign.rewardCents)})",
            style = MaterialTheme.typography.labelSmall,
            color = tfColors.neutralSurfaceFg
          )
        }

        Button(
          onClick = onStartOffer,
          enabled = canTapStart,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.testTag("tracked_campaign_start_${campaign.campaignId}")
        ) {
          if (isLaunching) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary
            )
          } else {
            Text(
              text = when {
                !accountApproved -> "Approval Required"
                campaign.userStatus == "COMPLETED" && !campaign.allowMultipleConversions -> "Completed"
                campaign.userStatus == "PENDING" -> "Pending"
                else -> "Start Offer"
              },
              fontWeight = FontWeight.SemiBold
            )
            if (canTapStart) {
              Spacer(modifier = Modifier.width(4.dp))
              Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }
      }
    }
  }
}
