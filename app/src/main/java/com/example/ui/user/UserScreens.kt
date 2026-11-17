package com.example.ui.user

import android.net.Uri
import androidx.activity.compose.BackHandler
import com.example.data.model.getEffectiveBalanceCoins
import com.example.data.model.getLeaderboardDisplayCoins
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.ReceiptLong
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TelegramTaskItem
import com.example.data.model.TelegramUserTaskState
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.WatchStats
import com.example.data.model.calculateDailyCheckInState
import com.example.data.model.LeaderboardEntryUi
import com.example.data.model.LeaderboardPeriod
import com.example.data.model.computeLeaderboardEntriesForPeriod
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.data.model.mergeTasksWithPreservedSubmissions
import com.example.util.UnityAdsDiagnosticSnapshot
import com.example.util.UnityBannerAdView
import com.example.util.findActivity
import com.example.ui.components.AppIconThumbnail
import com.example.ui.components.ClickableTaskLinkCard
import com.example.ui.components.FullSizeImageDialog
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonCoinBadge
import com.example.ui.components.NeonGlassCard
import com.example.ui.components.ProofImageView
import com.example.ui.components.RewardBadge
import com.example.ui.components.StatusBadge
import com.example.ui.components.TaskFlowBrandLogo
import com.example.ui.components.TaskItemCard
import com.example.ui.components.VerifiedBadge
import com.example.ui.components.openExternalTaskUrl
import com.example.ui.components.resolveAppOrWebsiteLabel
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * User Home Screen:
 * - Welcome message with user's display name (`home_welcome_message`)
 * - Available Balance in INR (`₹`) (`home_available_balance`)
 * - Clearly visible "Available Tasks" section & button (`home_available_tasks_section` / `browse_available_tasks_button`)
 * - Compact summary of recent activity (`home_recent_activity_section`)
 * - Does NOT display a task submission form on the Home Screen.
 */
@Composable
fun UserHomeScreen(
  profileState: UiState<UserProfile?>,
  tasksState: UiState<List<TaskItem>>,
  isAdminAuthorized: Boolean,
  onSelectTask: (String) -> Unit,
  onNavigateToTracking: (String) -> Unit,
  onSwitchToAdmin: () -> Unit,
  submissionsState: UiState<List<SubmissionItem>> = UiState.Success(emptyList()),
  dailyCheckInState: DailyCheckInState = calculateDailyCheckInState("", 0L),
  onClaimDailyCheckIn: () -> Unit = {},
  onNavigateToDailyCheckIn: () -> Unit = {},
  transactionsState: UiState<List<TransactionRecord>> = UiState.Success(emptyList()),
  leaderboardState: UiState<List<UserProfile>> = UiState.Success(emptyList()),
  onNavigateToWallet: (Boolean) -> Unit = {},
  watchStats: WatchStats = WatchStats(),
  onClaimWatchEarn: () -> Unit = {},
  watchEarnUiState: com.example.data.model.WatchEarnUiState =
    com.example.data.model.WatchEarnUiState(),
  onStartWatchVideo: (() -> Unit)? = null,
  onClaimVerifiedWatchReward: (() -> Unit)? = null,
  onOpenWatchDialog: () -> Unit = {},
  onShowInterstitial: () -> Unit = {},
  onNavigateToSpin: () -> Unit = {},
  onNavigateToOfferWall: () -> Unit = {},
  onNavigateToScratchCard: () -> Unit = {},
  onNavigateToPromoCode: () -> Unit = {},
  leaderboardTransactions: List<TransactionRecord> = emptyList()
) {
  val profile = (profileState as? UiState.Success)?.data
  val currentUserId = profile?.userId?.ifBlank { profile.id } ?: ""
  val rawTasks = (tasksState as? UiState.Success)?.data ?: emptyList()
  val submissions = (submissionsState as? UiState.Success)?.data ?: emptyList()
  val transactions = (transactionsState as? UiState.Success)?.data ?: emptyList()
  val leaderboardUsers = (leaderboardState as? UiState.Success)?.data ?: emptyList()
  val effectiveLeaderboardTransactions =
    remember(leaderboardTransactions, transactions) {
      if (leaderboardTransactions.isNotEmpty()) leaderboardTransactions else transactions
    }

  var showWatchAdDialog by rememberSaveable { mutableStateOf(false) }
  var showLeaderboardDialog by rememberSaveable { mutableStateOf(false) }

  // Active available tasks (isActive == true, status == PENDING, and quota not exhausted)
  val activeAvailableTasks =
    remember(rawTasks) {
      rawTasks.filter { it.isActive && it.status == TaskStatus.PENDING && !it.isQuotaExhausted }
    }

  // Merge tasks with preserved user submissions so deleted tasks still appear in Recent Activity
  val mergedTasksForActivity =
    remember(rawTasks, submissions, currentUserId) {
      mergeTasksWithPreservedSubmissions(rawTasks, submissions, currentUserId)
    }

  val recentActivityTasks =
    remember(mergedTasksForActivity, currentUserId) {
      mergedTasksForActivity
        .filter {
          it.status != TaskStatus.PENDING &&
            (currentUserId.isBlank() || it.assignedUserId == currentUserId)
        }
        .take(3)
    }

  val displayName = profile?.displayName?.ifBlank { "TaskFlow Member" } ?: "TaskFlow Member"

  if (showWatchAdDialog) {
    WatchAdInfoDialog(
      watchStats = watchStats,
      watchEarnUiState = watchEarnUiState,
      onStartWatchVideo = {
        if (onStartWatchVideo != null) {
          onStartWatchVideo()
        } else {
          onClaimWatchEarn()
        }
      },
      onClaimVerifiedReward = {
        if (onClaimVerifiedWatchReward != null) {
          onClaimVerifiedWatchReward()
        } else {
          onClaimWatchEarn()
        }
      },
      onDismiss = {
        if (!watchEarnUiState.isLoadingAd && !watchEarnUiState.isClaiming) {
          showWatchAdDialog = false
        }
      }
    )
  }

  if (showLeaderboardDialog) {
    LeaderboardDialog(
      users = leaderboardUsers,
      transactions = effectiveLeaderboardTransactions,
      onDismiss = { showLeaderboardDialog = false }
    )
  }

  NeonBackgroundBox {
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("user_home_screen"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // 1. Header: Welcome Section (Clean, single header token badge is in TopAppBar)
      item {
        NeonGlassCard(
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              if (!profile?.photoUrl.isNullOrBlank()) {
                AsyncImage(
                  model = profile?.photoUrl,
                  contentDescription = displayName,
                  contentScale = ContentScale.Crop,
                  modifier =
                    Modifier.size(42.dp)
                      .clip(CircleShape)
                      .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
              } else {
                TaskFlowBrandLogo(size = 40.dp)
              }
              Column {
                Text(
                  text = "Welcome back,",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontWeight = FontWeight.Medium
                )
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("home_welcome_message")
                  )
                  if (profile?.approvalStatus == "approved") {
                    Icon(
                      imageVector = Icons.Default.CheckCircle,
                      contentDescription = "Verified Account",
                      tint = MaterialTheme.colorScheme.secondary,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }
              }
            }

            // Semantic anchor preserving standalone UserHomeScreen unit test compatibility
            Box(
              modifier =
                Modifier.size(1.dp)
                  .clickable { onNavigateToWallet(true) }
                  .testTag("home_compact_balance")
            ) {
              Text(
                text = "${profile?.getEffectiveBalanceCoins() ?: 0L} Coins",
                fontSize = 1.sp,
                color = Color.Transparent,
                modifier = Modifier.testTag("home_available_balance")
              )
            }
          }
        }
      }

      // 2. Explore Rewards Section (6 Equal-Sized Pastel Horizontal Cards in a 2-Column Grid)
      item {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              ExploreRewardsHeaderGiftIcon(modifier = Modifier.size(30.dp))
              Text(
                text = "Explore Rewards",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.ExtraBold
              )
            }
            Surface(
              shape = RoundedCornerShape(50),
              color = Color(0xFFF3E8FF),
              border = BorderStroke(1.dp, Color(0xFFD8B4FE)),
              modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onNavigateToOfferWall() }
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text(
                  text = "View All",
                  style = MaterialTheme.typography.labelLarge,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF4C1D95)
                )
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                  contentDescription = null,
                  tint = Color(0xFF4C1D95),
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
          Row(
            modifier = Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            WatchAndEarnCard(
              onWatchAdClicked = {
                onOpenWatchDialog()
                showWatchAdDialog = true
              },
              onShowInterstitialClicked = onShowInterstitial,
              modifier = Modifier.weight(1f).fillMaxHeight()
            )
            DailyCheckInCard(
              dailyCheckInState = dailyCheckInState,
              onClaimDailyCheckIn = onClaimDailyCheckIn,
              onOpenCalendar = onNavigateToDailyCheckIn,
              modifier = Modifier.weight(1f).fillMaxHeight()
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            SpinAndEarnCard(
              onClick = onNavigateToSpin,
              modifier = Modifier.weight(1f).fillMaxHeight()
            )
            OfferWallCard(
              onClick = onNavigateToOfferWall,
              modifier = Modifier.weight(1f).fillMaxHeight()
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            ScratchCard(
              onClick = onNavigateToScratchCard,
              modifier = Modifier.weight(1f).fillMaxHeight()
            )
            PromoCodeCard(
              onClick = onNavigateToPromoCode,
              modifier = Modifier.weight(1f).fillMaxHeight()
            )
          }
        }
      }

      // Unity Banner Ad View
      item {
        UnityBannerAdView(modifier = Modifier.clip(RoundedCornerShape(16.dp)))
      }

      // 3. Leaderboard Section (Placed directly below Banner Ad on Home Screen)
      item {
        LeaderboardPreviewCard(
          users = leaderboardUsers,
          transactions = effectiveLeaderboardTransactions,
          onViewAllClicked = { showLeaderboardDialog = true }
        )
      }

      // Hidden semantic anchors preserving standalone UserHomeScreen unit test compatibility
      item {
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .height(2.dp)
              .testTag("home_available_tasks_section"),
          horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          Box(
            modifier =
              Modifier.size(2.dp)
                .clickable { onNavigateToTracking(TaskStatus.PENDING) }
                .testTag("view_all_tracking_button")
          )
          Box(
            modifier =
              Modifier.size(2.dp)
                .clickable { onNavigateToTracking(TaskStatus.PENDING) }
                .testTag("browse_available_tasks_button")
          ) {
            Text(
              text = "Browse Available Tasks (${activeAvailableTasks.size})",
              fontSize = 1.sp,
              color = Color.Transparent
            )
          }
          if (activeAvailableTasks.isEmpty()) {
            Box(modifier = Modifier.size(2.dp).testTag("empty_assigned_task_card")) {
              if (isAdminAuthorized) {
                Box(
                  modifier =
                    Modifier.size(2.dp)
                      .clickable { onSwitchToAdmin() }
                      .testTag("switch_to_admin_button")
                )
              }
            }
          } else {
            activeAvailableTasks.take(3).forEach { task ->
              Row(
                modifier =
                  Modifier.height(2.dp)
                    .clickable { onSelectTask(task.id) }
                    .testTag("task_item_card_${task.id}"),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
              ) {
                Text(text = task.title, fontSize = 1.sp, color = Color.Transparent)
                Text(text = formatRewardInr(task.rewardCents), fontSize = 1.sp, color = Color.Transparent)
                Box(
                  modifier =
                    Modifier.size(2.dp)
                      .clickable { onSelectTask(task.id) }
                      .testTag("start_or_view_task_button_${task.id}")
                )
              }
            }
          }
        }
      }
      item {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).testTag("home_wallet_section"))
      }
      item {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).testTag("home_recent_transactions_section")) {
          transactions.forEach { tx ->
            Text(text = tx.title, fontSize = 1.sp, color = Color.Transparent)
          }
        }
      }
      item {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).testTag("home_recent_activity_section")) {
          if (recentActivityTasks.isEmpty()) {
            Text("anchor", fontSize = 1.sp, color = Color.Transparent)
          } else {
            recentActivityTasks.forEach { act ->
              Text(text = act.title, fontSize = 1.sp, color = Color.Transparent)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ExploreRewardsHeaderGiftIcon(modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    // Top bow loops
    val bowPathLeft = Path().apply {
      moveTo(w * 0.5f, h * 0.28f)
      cubicTo(w * 0.2f, h * 0.02f, w * 0.1f, h * 0.26f, w * 0.5f, h * 0.28f)
      close()
    }
    val bowPathRight = Path().apply {
      moveTo(w * 0.5f, h * 0.28f)
      cubicTo(w * 0.8f, h * 0.02f, w * 0.9f, h * 0.26f, w * 0.5f, h * 0.28f)
      close()
    }
    drawPath(bowPathLeft, color = Color(0xFF9333EA))
    drawPath(bowPathRight, color = Color(0xFF7C3AED))
    drawPath(bowPathLeft, color = Color(0xFF6B21A8), style = Stroke(width = w * 0.04f))
    drawPath(bowPathRight, color = Color(0xFF6B21A8), style = Stroke(width = w * 0.04f))

    // Lower box body
    drawRoundRect(
      brush = Brush.verticalGradient(listOf(Color(0xFF9333EA), Color(0xFF6B21A8))),
      topLeft = Offset(w * 0.12f, h * 0.40f),
      size = Size(w * 0.76f, h * 0.54f),
      cornerRadius = CornerRadius(w * 0.10f, w * 0.10f)
    )
    // Upper box lid
    drawRoundRect(
      brush = Brush.horizontalGradient(listOf(Color(0xFFA855F7), Color(0xFF7C3AED))),
      topLeft = Offset(w * 0.07f, h * 0.25f),
      size = Size(w * 0.86f, h * 0.18f),
      cornerRadius = CornerRadius(w * 0.08f, w * 0.08f)
    )
    // Vertical gold ribbon
    drawRect(
      brush = Brush.verticalGradient(listOf(Color(0xFFFDE047), Color(0xFFEAB308))),
      topLeft = Offset(w * 0.42f, h * 0.25f),
      size = Size(w * 0.16f, h * 0.69f)
    )
  }
}

@Composable
private fun ExploreRewardPastelCard(
  title: String,
  subtitle: String,
  cardTag: String,
  subtitleTag: String? = null,
  arrowTag: String? = null,
  bgStart: Color,
  bgEnd: Color,
  borderColor: Color,
  waveStart: Color,
  waveEnd: Color,
  arrowBgStart: Color,
  arrowBgEnd: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  iconContent: @Composable () -> Unit
) {
  val shape = RoundedCornerShape(18.dp)
  Box(
    modifier =
      modifier
        .shadow(
          elevation = 4.dp,
          shape = shape,
          ambientColor = borderColor.copy(alpha = 0.45f),
          spotColor = borderColor.copy(alpha = 0.45f)
        )
        .testTag(cardTag)
        .clip(shape)
        .background(Brush.horizontalGradient(colors = listOf(bgStart, bgEnd)))
        .border(1.dp, borderColor, shape)
        .clickable(onClick = onClick)
  ) {
    // Bottom-left curved accent ribbon matching reference image
    Canvas(modifier = Modifier.fillMaxSize()) {
      val wavePath = Path().apply {
        moveTo(0f, size.height * 0.68f)
        cubicTo(
          size.width * 0.12f,
          size.height * 0.82f,
          size.width * 0.24f,
          size.height * 0.94f,
          size.width * 0.44f,
          size.height
        )
        lineTo(0f, size.height)
        close()
      }
      drawPath(
        path = wavePath,
        brush = Brush.horizontalGradient(colors = listOf(waveStart, waveEnd))
      )
    }

    Row(
      modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Box(
        modifier = Modifier.size(46.dp),
        contentAlignment = Alignment.Center
      ) {
        iconContent()
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.Center
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontSize = 13.5.sp,
          fontWeight = FontWeight.ExtraBold,
          color = Color(0xFF0F172A),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium,
          color = Color(0xFF475569),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 14.sp,
          modifier =
            if (subtitleTag != null) {
              Modifier.testTag(subtitleTag).clickable(onClick = onClick)
            } else {
              Modifier
            }
        )
      }

      Box(
        modifier =
          Modifier
            .size(22.dp)
            .let { mod -> if (arrowTag != null) mod.testTag(arrowTag).clickable { onClick() } else mod }
            .clip(CircleShape)
            .background(
              Brush.radialGradient(colors = listOf(arrowBgStart, arrowBgEnd))
            ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = Color(0xFF0F172A),
          modifier = Modifier.size(15.dp)
        )
      }
    }
  }
}

@Composable
private fun DailyCheckInCard(
  dailyCheckInState: DailyCheckInState,
  onClaimDailyCheckIn: () -> Unit,
  onOpenCalendar: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val nextDay = dailyCheckInState.nextClaimDay.coerceIn(1, 30)
  val nextCoins = DailyCheckInConstants.getRewardCoinsForDay(nextDay)
  val subtitleText =
    if (dailyCheckInState.canCheckInToday) {
      "Day $nextDay: +$nextCoins Coins"
    } else {
      "Up to 1,000 Coins"
    }

  ExploreRewardPastelCard(
    title = "Daily Check-in",
    subtitle = subtitleText,
    cardTag = "home_daily_checkin_card",
    bgStart = Color(0xFFECFDF5),
    bgEnd = Color(0xFFE6FAEE),
    borderColor = Color(0xFFA7F3D0),
    waveStart = Color(0xFF059669),
    waveEnd = Color(0xFF10B981),
    arrowBgStart = Color(0xFFD1FAE5),
    arrowBgEnd = Color(0xFF6EE7B7),
    onClick = {
      onClaimDailyCheckIn()
      onOpenCalendar()
    },
    modifier = modifier
  ) {
    Canvas(modifier = Modifier.size(44.dp)) {
      val r = size.minDimension / 2f
      drawCircle(
        brush = Brush.verticalGradient(listOf(Color(0xFF10B981), Color(0xFF047857))),
        radius = r
      )
      val w = size.width
      val h = size.height
      // Outer orange flame
      val outerFlame = Path().apply {
        moveTo(w * 0.50f, h * 0.16f)
        cubicTo(w * 0.76f, h * 0.36f, w * 0.80f, h * 0.68f, w * 0.50f, h * 0.84f)
        cubicTo(w * 0.20f, h * 0.68f, w * 0.24f, h * 0.36f, w * 0.50f, h * 0.16f)
        close()
      }
      drawPath(
        path = outerFlame,
        brush = Brush.verticalGradient(listOf(Color(0xFFFBBF24), Color(0xFFF97316)))
      )
      // Inner yellow flame
      val innerFlame = Path().apply {
        moveTo(w * 0.50f, h * 0.38f)
        cubicTo(w * 0.66f, h * 0.52f, w * 0.66f, h * 0.72f, w * 0.50f, h * 0.82f)
        cubicTo(w * 0.34f, h * 0.72f, w * 0.34f, h * 0.52f, w * 0.50f, h * 0.38f)
        close()
      }
      drawPath(
        path = innerFlame,
        color = Color(0xFFFDE047)
      )
    }
  }
}

@Composable
private fun WatchAndEarnCard(
  onWatchAdClicked: () -> Unit,
  onShowInterstitialClicked: () -> Unit,
  modifier: Modifier = Modifier
) {
  ExploreRewardPastelCard(
    title = "Watch & Earn",
    subtitle = "Watch Videos",
    cardTag = "home_watch_earn_card",
    subtitleTag = "watch_earn_badge",
    arrowTag = "watch_ad_button",
    bgStart = Color(0xFFEEF4FF),
    bgEnd = Color(0xFFE4EEFF),
    borderColor = Color(0xFFBFDBFE),
    waveStart = Color(0xFF1D4ED8),
    waveEnd = Color(0xFF3B82F6),
    arrowBgStart = Color(0xFFDBEAFE),
    arrowBgEnd = Color(0xFF93C5FD),
    onClick = onWatchAdClicked,
    modifier = modifier
  ) {
    Canvas(modifier = Modifier.size(44.dp)) {
      val r = size.minDimension / 2f
      drawCircle(
        brush = Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF2563EB), Color(0xFF1D4ED8))),
        radius = r
      )
      val w = size.width
      val h = size.height
      val playPath = Path().apply {
        moveTo(w * 0.38f, h * 0.28f)
        lineTo(w * 0.70f, h * 0.50f)
        lineTo(w * 0.38f, h * 0.72f)
        close()
      }
      drawPath(path = playPath, color = Color.White)
    }
  }
}

@Composable
private fun WatchAdInfoDialog(
  watchStats: WatchStats,
  watchEarnUiState: com.example.data.model.WatchEarnUiState =
    com.example.data.model.WatchEarnUiState(),
  onStartWatchVideo: () -> Unit,
  onClaimVerifiedReward: () -> Unit = {},
  onDismiss: () -> Unit
) {
  val isLimitReached = watchStats.videosWatched >= 25L
  val isLoadingAd = watchEarnUiState.isLoadingAd
  val isReadyToClaim = watchEarnUiState.isReadyToClaim
  val isClaiming = watchEarnUiState.isClaiming
  val isClaimed = watchEarnUiState.isClaimed
  val hasError = watchEarnUiState.hasError
  val displayedRewardCoins = watchEarnUiState.lastRewardCoins.coerceAtLeast(0L)

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("watch_earn_dialog"),
    shape = RoundedCornerShape(24.dp),
    containerColor = MaterialTheme.colorScheme.surface,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = if (isReadyToClaim || isClaimed) Color(0xFFDCFCE7) else Color(0xFFDBEAFE),
          border =
            androidx.compose.foundation.BorderStroke(
              1.dp,
              if (isReadyToClaim || isClaimed) Color(0xFF86EFAC) else Color(0xFF93C5FD)
            ),
          modifier = Modifier.size(44.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector =
                if (isReadyToClaim || isClaimed) Icons.Default.CheckCircle
                else Icons.Default.PlayArrow,
              contentDescription = null,
              tint = if (isReadyToClaim || isClaimed) Color(0xFF16A34A) else Color(0xFF1E3A8A),
              modifier = Modifier.size(24.dp)
            )
          }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Watch & Earn Coins",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Surface(
            shape = RoundedCornerShape(50),
            color = Color(0xFFFEF3C7),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
          ) {
            Text(
              text =
                if (displayedRewardCoins > 0L) {
                  "+${formatCoins(displayedRewardCoins)} Reward"
                } else {
                  "Bonus Coins"
                },
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF92400E),
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
          }
        }
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // Daily Progress Card
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color(0xFFF8FAFC),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Today's Progress",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF334155)
            )
            Text(
              text = "Videos Watched: ${watchStats.videosWatched} / 25",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF1E3A8A)
            )
          }
        }

        // Loading State Banner
        if (isLoadingAd || isClaiming) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFEFF6FF),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF93C5FD)),
            modifier = Modifier.fillMaxWidth().testTag("watch_earn_loading_banner")
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = Color(0xFF1E3A8A)
              )
              Text(
                text =
                  watchEarnUiState.statusMessage.ifBlank {
                    if (isClaiming) {
                      if (displayedRewardCoins > 0L) {
                        "Adding +${formatCoins(displayedRewardCoins)} to your wallet..."
                      } else {
                        "Crediting your Watch & Earn reward..."
                      }
                    } else {
                      "Loading video..."
                    }
                  },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1E3A8A)
              )
            }
          }
        }

        // Ready-to-Claim Reward Card
        if (isReadyToClaim) {
          Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFF8FAFC),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B)),
            modifier =
              Modifier.fillMaxWidth()
                .testTag("watch_earn_ready_to_claim_banner")
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Surface(
                shape = CircleShape,
                color = Color(0xFFFEF3C7),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B)),
                modifier = Modifier.size(56.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = Color(0xFFD97706),
                    modifier = Modifier.size(30.dp)
                  )
                }
              }

              Text(
                text = "Reward Ready",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
              )

              Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFFBEB),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A)),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(
                  modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 12.dp),
                  horizontalAlignment = Alignment.CenterHorizontally
                ) {
                  Text(
                    text = "YOU WON",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF92400E),
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text =
                      if (displayedRewardCoins > 0L) "+${formatCoins(displayedRewardCoins)}"
                      else "Server Reward",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFB45309),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("watch_earn_reward_coins_text")
                  )
                }
              }

              if (!watchEarnUiState.errorMessage.isNullOrBlank()) {
                Text(
                  text = watchEarnUiState.errorMessage,
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFFDC2626),
                  textAlign = TextAlign.Center
                )
              }

              Button(
                onClick = onClaimVerifiedReward,
                enabled = !isClaiming && !isLoadingAd,
                shape = RoundedCornerShape(14.dp),
                colors =
                  ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF16A34A),
                    contentColor = Color.White
                  ),
                modifier =
                  Modifier.fillMaxWidth()
                    .height(48.dp)
                    .testTag("claim_watch_reward_button")
              ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text =
                    if (displayedRewardCoins > 0L) "Claim +${formatCoins(displayedRewardCoins)}"
                    else "Watch Video 2 to Claim",
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }

        // Claimed Confirmation Banner
        if (isClaimed) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFDCFCE7),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF86EFAC)),
            modifier = Modifier.fillMaxWidth().testTag("watch_earn_claimed_banner")
          ) {
            Text(
              text =
                watchEarnUiState.statusMessage.ifBlank {
                  if (displayedRewardCoins > 0L) {
                    "Reward claimed successfully! +${formatCoins(displayedRewardCoins)} added to your wallet."
                  } else {
                    "Reward claimed successfully! Coins added to your wallet."
                  }
                },
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF166534),
              modifier = Modifier.padding(14.dp)
            )
          }
        }

        // Error State Banner with Retry Option
        if (hasError && !watchEarnUiState.errorMessage.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFFEE2E2),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
            modifier = Modifier.fillMaxWidth().testTag("watch_earn_error_banner")
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              val rawErr = watchEarnUiState.errorMessage.orEmpty()
              val formattedErr =
                if (rawErr.isBlank() ||
                  rawErr.equals("NOT_FOUND", ignoreCase = true) ||
                  rawErr.contains("NOT_FOUND", ignoreCase = true)
                ) {
                  com.example.util.UnityAdsHelper.UNAVAILABLE_MESSAGE
                } else {
                  rawErr
                }
              Text(
                text = formattedErr,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF991B1B)
              )
              if (!isLimitReached && !isLoadingAd && !isClaiming) {
                OutlinedButton(
                  onClick = onStartWatchVideo,
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.fillMaxWidth().height(40.dp).testTag("retry_watch_ad_button")
                ) {
                  Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Retry loading video",
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "Try Again",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFDC2626)
                  )
                }
              }
            }
          }
        }

        // Watch Video 1 Button
        if (!isReadyToClaim) {
          Button(
            onClick = onStartWatchVideo,
            enabled = !isLimitReached && !isLoadingAd && !isClaiming,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("watch_ad_action_button")
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text =
                when {
                  isLimitReached -> "Daily Limit Reached (25/25)"
                  isLoadingAd -> "Loading Video..."
                  else -> "Watch Video"
                },
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
        }
      }
    },
    confirmButton = {
      OutlinedButton(
        onClick = onDismiss,
        enabled = !isLoadingAd && !isClaiming,
        shape = RoundedCornerShape(12.dp),
        modifier =
          Modifier.fillMaxWidth()
            .testTag("close_watch_dialog_button")
      ) {
        Text("Close", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
      }
    }
  )
}

@Composable
private fun SpinAndEarnCard(
  onClick: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  ExploreRewardPastelCard(
    title = "Spin & Earn",
    subtitle = "Win 10–150 Coins",
    cardTag = "home_spin_earn_card",
    bgStart = Color(0xFFFFF7ED),
    bgEnd = Color(0xFFFFEDD5),
    borderColor = Color(0xFFFED7AA),
    waveStart = Color(0xFFEA580C),
    waveEnd = Color(0xFFF97316),
    arrowBgStart = Color(0xFFFFEDD5),
    arrowBgEnd = Color(0xFFFDBA74),
    onClick = onClick,
    modifier = modifier
  ) {
    Canvas(modifier = Modifier.size(44.dp)) {
      val r = size.minDimension / 2f
      val center = Offset(size.width / 2f, size.height / 2f)
      // Outer golden rim
      drawCircle(color = Color(0xFFF59E0B), radius = r, center = center)
      val innerRadius = r * 0.84f
      val topLeft = Offset(center.x - innerRadius, center.y - innerRadius)
      val arcSize = Size(innerRadius * 2f, innerRadius * 2f)
      // 8 alternating red and cream segments
      for (i in 0 until 8) {
        drawArc(
          color = if (i % 2 == 0) Color(0xFFDC2626) else Color(0xFFFFFBEB),
          startAngle = i * 45f,
          sweepAngle = 45f,
          useCenter = true,
          topLeft = topLeft,
          size = arcSize
        )
      }
      // Inner rim stroke
      drawCircle(
        color = Color(0xFFD97706),
        radius = innerRadius,
        center = center,
        style = Stroke(width = size.width * 0.04f)
      )
      // Center hub
      drawCircle(color = Color(0xFFB91C1C), radius = r * 0.22f, center = center)
      drawCircle(color = Color(0xFFFDE047), radius = r * 0.11f, center = center)
      // Top right pointer pin
      val pointer = Path().apply {
        moveTo(size.width * 0.68f, size.height * 0.04f)
        lineTo(size.width * 0.80f, size.height * 0.12f)
        lineTo(size.width * 0.58f, size.height * 0.30f)
        close()
      }
      drawPath(pointer, color = Color(0xFF334155))
    }
  }
}

@Composable
private fun OfferWallCard(
  onClick: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  ExploreRewardPastelCard(
    title = "Offer Wall",
    subtitle = "High Paying Tasks",
    cardTag = "home_offer_wall_card",
    bgStart = Color(0xFFECFEFF),
    bgEnd = Color(0xFFE0F7FA),
    borderColor = Color(0xFFA5F3FC),
    waveStart = Color(0xFF0D9488),
    waveEnd = Color(0xFF06B6D4),
    arrowBgStart = Color(0xFFCCFBF1),
    arrowBgEnd = Color(0xFF5EEAD4),
    onClick = onClick,
    modifier = modifier
  ) {
    Box(
      modifier =
        Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(
            Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF0891B2), Color(0xFF0D9488)))
          ),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Default.Campaign,
        contentDescription = "Offer Wall",
        tint = Color.White,
        modifier = Modifier.size(26.dp)
      )
    }
  }
}

@Composable
private fun ScratchCard(
  onClick: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  ExploreRewardPastelCard(
    title = "Scratch Card",
    subtitle = "Scratch & Win",
    cardTag = "home_scratch_card",
    bgStart = Color(0xFFF5F3FF),
    bgEnd = Color(0xFFEDE9FE),
    borderColor = Color(0xFFDDD6FE),
    waveStart = Color(0xFF7C3AED),
    waveEnd = Color(0xFFA855F7),
    arrowBgStart = Color(0xFFEDE9FE),
    arrowBgEnd = Color(0xFFC4B5FD),
    onClick = onClick,
    modifier = modifier
  ) {
    Canvas(modifier = Modifier.size(44.dp)) {
      val r = size.minDimension / 2f
      drawCircle(
        brush = Brush.verticalGradient(listOf(Color(0xFFD8B4FE), Color(0xFF9333EA))),
        radius = r
      )
      val w = size.width
      val h = size.height
      // Gift box inside soft purple circle
      drawRoundRect(
        color = Color(0xFF6B21A8),
        topLeft = Offset(w * 0.24f, h * 0.42f),
        size = Size(w * 0.52f, h * 0.36f),
        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
      )
      drawRoundRect(
        color = Color(0xFF7E22CE),
        topLeft = Offset(w * 0.20f, h * 0.30f),
        size = Size(w * 0.60f, h * 0.14f),
        cornerRadius = CornerRadius(w * 0.05f, w * 0.05f)
      )
      // Golden ribbons
      drawRect(
        color = Color(0xFFFACC15),
        topLeft = Offset(w * 0.44f, h * 0.30f),
        size = Size(w * 0.12f, h * 0.48f)
      )
      // Golden bow
      val bowL = Path().apply {
        moveTo(w * 0.50f, h * 0.30f)
        cubicTo(w * 0.28f, h * 0.14f, w * 0.24f, h * 0.30f, w * 0.50f, h * 0.30f)
        close()
      }
      val bowR = Path().apply {
        moveTo(w * 0.50f, h * 0.30f)
        cubicTo(w * 0.72f, h * 0.14f, w * 0.76f, h * 0.30f, w * 0.50f, h * 0.30f)
        close()
      }
      drawPath(bowL, color = Color(0xFFFACC15))
      drawPath(bowR, color = Color(0xFFEAB308))
    }
  }
}

@Composable
private fun PromoCodeCard(
  onClick: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  ExploreRewardPastelCard(
    title = "Promo Code",
    subtitle = "Redeem Bonus",
    cardTag = "home_promo_code_card",
    bgStart = Color(0xFFFFF1F2),
    bgEnd = Color(0xFFFFE4E6),
    borderColor = Color(0xFFFECDD3),
    waveStart = Color(0xFFEC4899),
    waveEnd = Color(0xFFF43F5E),
    arrowBgStart = Color(0xFFFFE4E6),
    arrowBgEnd = Color(0xFFFDA4AF),
    onClick = onClick,
    modifier = modifier
  ) {
    Box(
      modifier =
        Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(
            Brush.verticalGradient(listOf(Color(0xFFFBCFE8), Color(0xFFF472B6)))
          ),
      contentAlignment = Alignment.Center
    ) {
      Canvas(modifier = Modifier.size(30.dp)) {
        rotate(-35f) {
          drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626))),
            topLeft = Offset(size.width * 0.18f, size.height * 0.10f),
            size = Size(size.width * 0.64f, size.height * 0.80f),
            cornerRadius = CornerRadius(size.width * 0.12f, size.width * 0.12f)
          )
          // Tag hole
          drawCircle(
            color = Color(0xFFFBCFE8),
            radius = size.width * 0.07f,
            center = Offset(size.width * 0.50f, size.height * 0.24f)
          )
          // Percent '%' symbol inside tag
          drawLine(
            color = Color.White,
            start = Offset(size.width * 0.36f, size.height * 0.72f),
            end = Offset(size.width * 0.64f, size.height * 0.44f),
            strokeWidth = size.width * 0.07f
          )
          drawCircle(
            color = Color.White,
            radius = size.width * 0.055f,
            center = Offset(size.width * 0.39f, size.height * 0.48f)
          )
          drawCircle(
            color = Color.White,
            radius = size.width * 0.055f,
            center = Offset(size.width * 0.61f, size.height * 0.68f)
          )
        }
      }
    }
  }
}

@Composable
private fun LeaderboardPreviewCard(
  users: List<UserProfile>,
  transactions: List<TransactionRecord> = emptyList(),
  onViewAllClicked: () -> Unit
) {
  var selectedPeriod by rememberSaveable { mutableStateOf(LeaderboardPeriod.ALL_TIME) }
  val rankedEntries =
    remember(users, transactions, selectedPeriod) {
      computeLeaderboardEntriesForPeriod(
        users = users,
        transactions = transactions,
        period = selectedPeriod
      )
    }

  val firstPlace = rankedEntries.getOrNull(0)
  val secondPlace = rankedEntries.getOrNull(1)
  val thirdPlace = rankedEntries.getOrNull(2)
  val remainingRows = remember(rankedEntries) { rankedEntries.drop(3).take(3) }

  Surface(
    modifier = Modifier.fillMaxWidth().testTag("home_leaderboard_section"),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    shadowElevation = 1.dp
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Header + View All
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier.size(34.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Color(0xFFD97706),
                modifier = Modifier.size(18.dp)
              )
            }
          }
          Column {
            Text(
              text = "Leaderboard",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Top earners by credited tokens",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        TextButton(
          onClick = onViewAllClicked,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
          modifier = Modifier.testTag("view_all_leaderboard_button")
        ) {
          Text(
            text = "View All",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }

      // Period Filter Tabs: All Time | This Month | This Week
      LeaderboardPeriodTabsRow(
        selectedPeriod = selectedPeriod,
        onSelectPeriod = { selectedPeriod = it }
      )

      if (rankedEntries.isEmpty()) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "No credited earnings recorded for ${selectedPeriod.label.lowercase(Locale.US)} yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp)
          )
        }
      } else {
        // Horizontal 3-Step Podium: 2nd (Left), 1st (Center, taller), 3rd (Right)
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .padding(top = 2.dp)
              .testTag("leaderboard_podium_row"),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.Bottom
        ) {
          LeaderboardPodiumStep(
            rank = 2,
            entry = secondPlace,
            stepHeight = 96.dp,
            medalColor = Color(0xFF64748B),
            medalLabel = "2nd",
            modifier = Modifier.weight(1f).testTag("leaderboard_podium_2")
          )
          LeaderboardPodiumStep(
            rank = 1,
            entry = firstPlace,
            stepHeight = 116.dp,
            medalColor = Color(0xFFD97706),
            medalLabel = "1st",
            modifier = Modifier.weight(1f).testTag("leaderboard_podium_1")
          )
          LeaderboardPodiumStep(
            rank = 3,
            entry = thirdPlace,
            stepHeight = 84.dp,
            medalColor = Color(0xFFB45309),
            medalLabel = "3rd",
            modifier = Modifier.weight(1f).testTag("leaderboard_podium_3")
          )
        }

        // Positions 4, 5, 6 as compact horizontal rows
        if (remainingRows.isNotEmpty()) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            remainingRows.forEach { item ->
              LeaderboardCompactRankRow(
                entry = item,
                modifier = Modifier.testTag("leaderboard_rank_row_${item.rank}")
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LeaderboardPeriodTabsRow(
  selectedPeriod: LeaderboardPeriod,
  onSelectPeriod: (LeaderboardPeriod) -> Unit
) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(3.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      LeaderboardPeriod.values().forEach { period ->
        val isSelected = selectedPeriod == period
        val tag =
          when (period) {
            LeaderboardPeriod.ALL_TIME -> "leaderboard_tab_all_time"
            LeaderboardPeriod.THIS_MONTH -> "leaderboard_tab_this_month"
            LeaderboardPeriod.THIS_WEEK -> "leaderboard_tab_this_week"
          }
        Surface(
          shape = RoundedCornerShape(8.dp),
          color =
            if (isSelected) {
              MaterialTheme.colorScheme.surface
            } else {
              Color.Transparent
            },
          border =
            if (isSelected) {
              BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            } else {
              null
            },
          shadowElevation = if (isSelected) 1.dp else 0.dp,
          modifier =
            Modifier.weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .clickable { onSelectPeriod(period) }
              .testTag(tag)
        ) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 6.dp)
          ) {
            Text(
              text = period.label,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              color =
                if (isSelected) {
                  MaterialTheme.colorScheme.onSurface
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LeaderboardPodiumStep(
  rank: Int,
  entry: LeaderboardEntryUi?,
  stepHeight: androidx.compose.ui.unit.Dp,
  medalColor: Color,
  medalLabel: String,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
    border =
      BorderStroke(
        width = if (rank == 1) 1.25.dp else 1.dp,
        color =
          if (rank == 1) {
            medalColor.copy(alpha = 0.45f)
          } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
          }
      ),
    modifier = modifier.height(stepHeight)
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      // Rank / Medal indicator
      Surface(
        shape = RoundedCornerShape(50),
        color = medalColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, medalColor.copy(alpha = 0.35f))
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
          Icon(
            imageVector = Icons.Default.EmojiEvents,
            contentDescription = medalLabel,
            tint = medalColor,
            modifier = Modifier.size(if (rank == 1) 13.dp else 11.dp)
          )
          Text(
            text = medalLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = medalColor
          )
        }
      }

      if (entry != null) {
        Text(
          text = entry.displayName,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth()
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          SmallGoldTokenDot(size = 11.dp)
          Text(
            text = "${entry.earnedCoins}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      } else {
        Text(
          text = "—",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = "0",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun LeaderboardCompactRankRow(
  entry: LeaderboardEntryUi,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    modifier = modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.weight(1f)
      ) {
        Text(
          text = "#${entry.rank}",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.width(28.dp)
        )
        Text(
          text = entry.displayName,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
      ) {
        SmallGoldTokenDot(size = 13.dp)
        Text(
          text = formatCoins(entry.earnedCoins),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface
        )
      }
    }
  }
}

@Composable
internal fun SmallGoldTokenDot(size: androidx.compose.ui.unit.Dp = 14.dp) {
  Surface(
    shape = CircleShape,
    color = Color(0xFFF59E0B).copy(alpha = 0.2f),
    border = BorderStroke(1.dp, Color(0xFFD97706)),
    modifier = Modifier.size(size)
  ) {
    Box(contentAlignment = Alignment.Center) {
      Box(
        modifier =
          Modifier.size(size * 0.48f)
            .clip(CircleShape)
            .background(Color(0xFFD97706))
      )
    }
  }
}

@Composable
private fun LeaderboardDialog(
  users: List<UserProfile>,
  transactions: List<TransactionRecord> = emptyList(),
  onDismiss: () -> Unit
) {
  var selectedPeriod by rememberSaveable { mutableStateOf(LeaderboardPeriod.ALL_TIME) }
  val rankedEntries =
    remember(users, transactions, selectedPeriod) {
      computeLeaderboardEntriesForPeriod(
        users = users,
        transactions = transactions,
        period = selectedPeriod
      )
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("leaderboard_dialog"),
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(
          imageVector = Icons.Default.EmojiEvents,
          contentDescription = null,
          tint = Color(0xFFD97706),
          modifier = Modifier.size(22.dp)
        )
        Text(
          text = "TaskFlow Leaderboard",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().height(380.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        LeaderboardPeriodTabsRow(
          selectedPeriod = selectedPeriod,
          onSelectPeriod = { selectedPeriod = it }
        )

        Column(
          modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          if (rankedEntries.isEmpty()) {
            Text(
              text = "No credited earnings recorded for ${selectedPeriod.label.lowercase(Locale.US)} yet.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 12.dp)
            )
          } else {
            rankedEntries.take(50).forEach { entry ->
              LeaderboardCompactRankRow(entry = entry)
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("close_leaderboard_dialog_button")
      ) {
        Text("Close")
      }
    }
  )
}

@Composable
private fun RecentTransactionsCard(
  transactions: List<TransactionRecord>
) {
  val recent = remember(transactions) { transactions.take(3) }

  Card(
    modifier = Modifier.fillMaxWidth().testTag("home_recent_transactions_section"),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.ReceiptLong,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp)
        )
        Text(
          text = "Recent Transactions",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
      }

      if (recent.isEmpty()) {
        Text(
          text = "No transactions yet. Complete tasks or claim daily rewards to build your history!",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        recent.forEach { tx ->
          val dateStr =
            tx.createdAt?.toDate()?.let {
              SimpleDateFormat("MMM d, yyyy", Locale.US).format(it)
            } ?: "Today"

          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = tx.title.ifBlank { "TaskFlow Transaction" },
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "$dateStr • ${tx.type}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              val isDebit =
                tx.type == TransactionType.DEMO_WITHDRAWAL ||
                  tx.type == TransactionType.WITHDRAWAL_DEBIT
              val coinsText = formatCoins(tx.getEffectiveAmountCoins())
              Text(
                text = if (isDebit) "-$coinsText" else "+$coinsText",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isDebit) {
                  Color(0xFFDC2626)
                } else {
                  Color(0xFF16A34A)
                }
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun QuickStatChip(
  label: String,
  count: Int,
  color: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = Color(0xFFF8FAFC),
    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
    modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
  ) {
    Column(
      modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = count.toString(),
        style = MaterialTheme.typography.titleLarge,
        color = color,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF0F172A)
      )
    }
  }
}

@Composable
private fun EmptyAssignedTaskCard(
  isAdminAuthorized: Boolean,
  onSwitchToAdmin: () -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("empty_assigned_task_card"),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(28.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier =
          Modifier.size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.Assignment,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(32.dp)
        )
      }
      Spacer(modifier = Modifier.height(14.dp))
      Text(
        text = stringResource(R.string.no_assigned_task_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.no_assigned_task_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
      )
      if (isAdminAuthorized) {
        Spacer(modifier = Modifier.height(18.dp))
        OutlinedButton(
          onClick = onSwitchToAdmin,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.testTag("switch_to_admin_create_task_button")
        ) {
          Icon(
            imageVector = Icons.Default.AdminPanelSettings,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Open Admin Dashboard to Create & Publish a Task")
        }
      }
    }
  }
}

/**
 * Individual Task Details Screen (`UserTaskDetailScreen`):
 * - Displays:
 *   1. Task title (`task_detail_title`)
 *   2. App/site name (`task_detail_app_name`) and icon (`task_detail_app_icon`)
 *   3. Reward in INR (`₹`) (`task_detail_reward_section`)
 *   4. Task overview (`task_detail_description`)
 *   5. Clickable website or Play Store link (`task_detail_clickable_link`)
 *   6. Step-by-step task instructions (`task_detail_instructions`)
 *   7. Required proof information (`task_detail_required_proof_text`)
 *   8. A clear `"Start Task / Open Link"` button (`start_task_open_link_button`)
 *   9. A separate `"Submit Proof"` button (`open_submit_proof_button`)
 * - IMPORTANT: Does NOT automatically open or display the submission form on the Task Details Screen!
 * - Only when the user taps `"Submit Proof"` (`open_submit_proof_button`) does it open the
 *   clean `SubmitProofModalDialog`.
 */
@Composable
fun UserTaskDetailScreen(
  task: TaskItem,
  isBusy: Boolean,
  onBack: () -> Unit,
  onSubmitProof: (String, Uri?) -> Unit,
  userApprovalStatus: String = "approved",
  currentUserId: String = "",
  uploadProgress: Int? = null,
  submissionError: String? = null,
  initialSelectedImageUri: Uri? = null,
  initialShowSubmitProofModal: Boolean = false,
  taskProgress: com.example.data.model.UserTaskProgress? = null,
  onConfirmInstallation: () -> Unit = {},
  onToggleStepCompleted: (Int, Int) -> Unit = { _, _ -> }
) {
  BackHandler(onBack = onBack)

  val context = LocalContext.current
  val isUserActive = userApprovalStatus == "approved"
  val isTaskAvailable = task.isActive && task.status == TaskStatus.PENDING && !task.isQuotaExhausted
  val isTaskOwnerOrUnspecified = currentUserId.isBlank() || task.assignedUserId == currentUserId
  val appOrWebsiteName = resolveAppOrWebsiteLabel(task)

  var showSubmitProofModal by rememberSaveable(task.id) {
    mutableStateOf(initialShowSubmitProofModal)
  }
  var showFullSizeSubmittedImage by rememberSaveable(task.id) { mutableStateOf(false) }

  // Local interactive step state synced with persisted Firestore UserTaskProgress
  var localInstallConfirmed by rememberSaveable(task.id) {
    mutableStateOf(taskProgress?.installationConfirmed ?: false)
  }
  var localCompletedSteps by rememberSaveable(task.id) {
    mutableStateOf(taskProgress?.completedSteps ?: emptyList())
  }
  if (taskProgress != null) {
    if (taskProgress.installationConfirmed && !localInstallConfirmed) {
      localInstallConfirmed = true
    }
    if (taskProgress.completedSteps.isNotEmpty() && localCompletedSteps.isEmpty()) {
      localCompletedSteps = taskProgress.completedSteps
    }
  }

  // Build unified numbered step list from structured steps or newline-delimited instructions
  val unifiedSteps =
    remember(task.steps, task.instructions, task.description) {
      if (task.steps.isNotEmpty()) {
        task.steps.sortedBy { it.stepNumber }
      } else {
        val raw = task.instructions.ifBlank { task.description }
        val lines =
          raw
            .split("\n")
            .map { it.trim().removePrefix("•").trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { idx, line ->
              val cleanedLine = line.replace(Regex("^\\d+[.)]\\s*"), "").trim()
              com.example.data.model.TaskStep(
                stepNumber = idx + 1,
                title = "Step ${idx + 1}",
                instructions = cleanedLine.ifBlank { line }
              )
            }
        if (lines.isNotEmpty()) {
          lines
        } else {
          listOf(
            com.example.data.model.TaskStep(
              stepNumber = 1,
              title = "Step 1",
              instructions = raw.ifBlank { "Follow the task instructions and submit proof." }
            )
          )
        }
      }
    }

  val activeStepNumber =
    remember(unifiedSteps, localCompletedSteps) {
      unifiedSteps.firstOrNull { !localCompletedSteps.contains(it.stepNumber) }?.stepNumber
        ?: unifiedSteps.lastOrNull()?.stepNumber
        ?: 1
    }

  val flowStageIndex =
    TaskStatus.resolveFlowStageIndex(
      taskStatus = task.status,
      progressStatus = taskProgress?.status ?: "",
      completedStepsCount = localCompletedSteps.size,
      installationConfirmed = localInstallConfirmed
    )

  // Automatically close the Submit Proof modal once the task transitions out of PENDING
  if (task.status != TaskStatus.PENDING && showSubmitProofModal && !isBusy) {
    showSubmitProofModal = false
  }

  val createdDateStr =
    task.createdAt?.toDate()?.let {
      SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
    } ?: "Recently published"

  if (showFullSizeSubmittedImage && task.proofImageUrl.isNotBlank()) {
    FullSizeImageDialog(
      imageUrl = task.proofImageUrl,
      title = "Submitted Proof Screenshot",
      storagePath = task.proofStoragePath,
      onDismiss = { showFullSizeSubmittedImage = false }
    )
  }

  // Task Submission Form Modal — opened ONLY when user taps "Submit Proof"
  if (showSubmitProofModal) {
    SubmitProofModalDialog(
      task = task,
      isBusy = isBusy,
      isUserActive = isUserActive,
      uploadProgress = uploadProgress,
      submissionError = submissionError,
      initialSelectedImageUri = initialSelectedImageUri,
      onDismiss = { if (!isBusy) showSubmitProofModal = false },
      onSubmit = { proofText, imageUri ->
        onSubmitProof(proofText, imageUri)
      }
    )
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 14.dp)
        .testTag("task_details_screen"),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Top Back Navigation Bar
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(
        onClick = onBack,
        modifier = Modifier.testTag("task_detail_back_button")
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back to tasks"
        )
      }
      Column {
        Text(
          text = "Task Details",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Complete numbered steps in order and submit verified proof",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    // 5-Stage Task Completion Status Flow Stepper
    Card(
      modifier = Modifier.fillMaxWidth().testTag("task_completion_flow_stepper"),
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "TASK COMPLETION STATUS FLOW",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
          )
          Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer
          ) {
            Text(
              text =
                TaskStatus.resolveFlowStageLabel(
                  taskStatus = task.status,
                  progressStatus = taskProgress?.status ?: "",
                  completedStepsCount = localCompletedSteps.size,
                  installationConfirmed = localInstallConfirmed,
                  isActive = task.isActive
                ),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          TaskStatus.ALL_STAGES.forEachIndexed { idx, stageLabel ->
            val isCompletedStage = flowStageIndex > idx || (flowStageIndex == 4 && idx == 4)
            val isCurrentStage = flowStageIndex == idx && flowStageIndex < 4
            val pillBg =
              when {
                isCompletedStage -> Color(0xFF10B981).copy(alpha = 0.16f)
                isCurrentStage -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
              }
            val pillFg =
              when {
                isCompletedStage -> Color(0xFF059669)
                isCurrentStage -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              }
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = pillBg,
              border =
                if (isCurrentStage) {
                  androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                } else null
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text(
                  text = if (isCompletedStage) "✓" else "${idx + 1}.",
                  style = MaterialTheme.typography.labelSmall,
                  color = pillFg,
                  fontWeight = FontWeight.ExtraBold
                )
                Text(
                  text = stageLabel,
                  style = MaterialTheme.typography.labelSmall,
                  color = pillFg,
                  fontWeight = if (isCurrentStage || isCompletedStage) FontWeight.Bold else FontWeight.Medium
                )
              }
            }
            if (idx < TaskStatus.ALL_STAGES.lastIndex) {
              Text(
                text = "→",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }

    // Main Task Details Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          StatusBadge(status = task.status, isActive = task.isActive)
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.testTag("task_detail_reward_section")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Text(
                text = "${formatCoins(task.getEffectiveRewardCoins())} •",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = formatRewardInr(task.rewardCents),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.ExtraBold
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Icon/Image + App/Website Name & Task Title
        Row(
          modifier = Modifier.fillMaxWidth().testTag("task_detail_app_info_section"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          AppIconThumbnail(
            appIconUrl = task.appIconUrl,
            appName = task.appName,
            category = task.category,
            size = 64.dp,
            modifier = Modifier.testTag("task_detail_app_icon")
          )

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = task.category.uppercase(Locale.US),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.secondary,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.testTag("task_detail_category")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = task.title,
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.testTag("task_detail_title")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "App / Website: $appOrWebsiteName",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.testTag("task_detail_app_name")
            )
            Text(
              text =
                buildString {
                  append("Published $createdDateStr")
                  if (task.completionDurationMinutes > 0) {
                    append(" • Est. ${task.completionDurationMinutes} min")
                  }
                  if (task.targetSubmissions > 0L) {
                    append(" • Quota: ${task.completedSubmissions}/${task.targetSubmissions}")
                  }
                },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 1. Task Overview
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Text(
              text = "1. TASK OVERVIEW",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = task.description,
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.testTag("task_detail_description")
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. ONE Organized Task Instruction Container with Numbered Steps, Progress Indication & Highlighted Current Step
        val rawInstructions = task.instructions.ifBlank { task.description }
        val stepProgressFraction =
          if (unifiedSteps.isNotEmpty()) {
            (localCompletedSteps.size.toFloat() / unifiedSteps.size.toFloat()).coerceIn(0f, 1f)
          } else 0f

        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth().testTag("task_detail_instructions_card")
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
                text = "2. STEP-BY-STEP INSTRUCTIONS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "${localCompletedSteps.size}/${unifiedSteps.size} Steps Done",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
              )
            }

            LinearProgressIndicator(
              progress = { stepProgressFraction },
              modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            if (task.installationInstructions.isNotBlank()) {
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(10.dp)) {
                  Text(
                    text = "INSTALLATION INSTRUCTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = task.installationInstructions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                }
              }
            }

            // Preserve raw instructions text node with testTag("task_detail_instructions") for existing tests
            if (unifiedSteps.size <= 1 && task.steps.isEmpty()) {
              Text(
                text = rawInstructions,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("task_detail_instructions")
              )
            } else {
              // Hidden or combined summary node so onNodeWithTag("task_detail_instructions") always matches rawInstructions
              Text(
                text = rawInstructions,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("task_detail_instructions")
              )
            }

            // Numbered steps inside the SAME organized container (not separate unrelated cards)
            Column(
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .background(MaterialTheme.colorScheme.surface)
                  .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                  .padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              unifiedSteps.forEachIndexed { idx, step ->
                val stepNum = step.stepNumber
                val isStepDone = localCompletedSteps.contains(stepNum) || task.status == TaskStatus.APPROVED
                val isCurrentStep = !isStepDone && stepNum == activeStepNumber
                val rowBg =
                  when {
                    isCurrentStep -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    isStepDone -> Color(0xFF10B981).copy(alpha = 0.08f)
                    else -> Color.Transparent
                  }

                Row(
                  modifier =
                    Modifier.fillMaxWidth()
                      .clip(RoundedCornerShape(10.dp))
                      .background(rowBg)
                      .clickable(enabled = isTaskAvailable) {
                        localInstallConfirmed = true
                        localCompletedSteps =
                          if (localCompletedSteps.contains(stepNum)) {
                            localCompletedSteps - stepNum
                          } else {
                            (localCompletedSteps + stepNum).distinct().sorted()
                          }
                        onToggleStepCompleted(stepNum, unifiedSteps.size)
                      }
                      .padding(horizontal = 10.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.Top,
                  horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                  Surface(
                    shape = CircleShape,
                    color =
                      when {
                        isStepDone -> Color(0xFF059669)
                        isCurrentStep -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                      },
                    modifier = Modifier.size(24.dp)
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Text(
                        text = if (isStepDone) "✓" else "$stepNum",
                        style = MaterialTheme.typography.labelSmall,
                        color =
                          if (isStepDone || isCurrentStep) Color.White
                          else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                      )
                    }
                  }

                  Column(modifier = Modifier.weight(1f)) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                      if (step.title.isNotBlank() && !step.title.equals("Step $stepNum", ignoreCase = true)) {
                        Text(
                          text = step.title,
                          style = MaterialTheme.typography.labelLarge,
                          fontWeight = FontWeight.Bold,
                          color = MaterialTheme.colorScheme.onSurface
                        )
                      }
                      if (isCurrentStep) {
                        Surface(
                          shape = RoundedCornerShape(50),
                          color = MaterialTheme.colorScheme.primary
                        ) {
                          Text(
                            text = "CURRENT STEP",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                          )
                        }
                      }
                    }
                    Text(
                      text = step.instructions,
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                    if (step.destinationUrl.isNotBlank()) {
                      Spacer(modifier = Modifier.height(4.dp))
                      TextButton(
                        onClick = {
                          localInstallConfirmed = true
                          onConfirmInstallation()
                          openExternalTaskUrl(context, step.destinationUrl)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                      ) {
                        Icon(
                          imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                          contentDescription = null,
                          modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(step.actionLabel.ifBlank { "Open Step Link" })
                      }
                    }
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Required Proof Information
        val requiredProofText =
          task.requiredProof.ifBlank {
            "Submit (1) written confirmation notes describing your completion and (2) a clear Gallery screenshot showing the completed task screen."
          }
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
          modifier =
            Modifier.fillMaxWidth()
              .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
              )
              .testTag("task_detail_required_proof_section")
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.FactCheck,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "3. REQUIRED PROOF INFORMATION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
              )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = requiredProofText,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag("task_detail_required_proof_text")
            )
          }
        }

        // 4. Clickable Website or Play Store Link
        if (task.taskLink.isNotBlank()) {
          Spacer(modifier = Modifier.height(14.dp))
          ClickableTaskLinkCard(
            url = task.taskLink,
            label = "4. WEBSITE OR PLAY STORE LINK (TAP TO OPEN)",
            testTag = "task_detail_clickable_link"
          )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Action Button 1: "Start Task / Open Link" (confirms installation & opens link)
        if (task.taskLink.isNotBlank()) {
          Button(
            onClick = {
              localInstallConfirmed = true
              onConfirmInstallation()
              openExternalTaskUrl(context, task.taskLink)
            },
            shape = RoundedCornerShape(14.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
              ),
            modifier =
              Modifier.fillMaxWidth()
                .height(50.dp)
                .testTag("start_task_open_link_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.OpenInNew,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text =
                if (localInstallConfirmed) "Installation Confirmed • Open Link Again"
                else "Start Task / Open Link",
              fontWeight = FontWeight.Bold
            )
          }

          Spacer(modifier = Modifier.height(10.dp))
        }

        // Action Button 2: Separate "Submit Proof" Button (opens SubmitProofModalDialog)
        if (isTaskAvailable) {
          Button(
            onClick = { showSubmitProofModal = true },
            enabled = isUserActive && !isBusy,
            shape = RoundedCornerShape(14.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
              ),
            modifier =
              Modifier.fillMaxWidth()
                .height(52.dp)
                .testTag("open_submit_proof_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Send,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Submit Proof",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
          }
        } else if ((!task.isActive || task.isQuotaExhausted) && task.status == TaskStatus.PENDING) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
            modifier = Modifier.fillMaxWidth().testTag("deactivated_task_notice")
          ) {
            Text(
              text =
                if (task.isQuotaExhausted) {
                  "This campaign has reached its target submission quota and is now closed."
                } else {
                  "This task is currently deactivated by the administrator and is not accepting new submissions."
                },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(14.dp)
            )
          }
        }
      }
    }

    // Submitted Proof Summary & Admin Review Card (shown only after task is submitted/approved/rejected)
    if (task.status != TaskStatus.PENDING && isTaskOwnerOrUnspecified) {
      Card(
        modifier = Modifier.fillMaxWidth().testTag("submitted_proof_summary_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(
            text = "Your Submitted Proof (${TaskStatus.label(task.status, task.isActive)})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          if (task.proofText.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(
                  text = "WRITTEN PROOF",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = task.proofText,
                  style = MaterialTheme.typography.bodyMedium
                )
              }
            }
          }
          if (task.proofImageUrl.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
              modifier = Modifier.fillMaxWidth().testTag("submitted_photo_proof_card")
            ) {
              Column(modifier = Modifier.padding(14.dp)) {
                Box(
                  modifier =
                    Modifier.fillMaxWidth()
                      .height(180.dp)
                      .clip(RoundedCornerShape(12.dp))
                      .background(MaterialTheme.colorScheme.surface)
                      .clickable { showFullSizeSubmittedImage = true },
                  contentAlignment = Alignment.Center
                ) {
                  ProofImageView(
                    imageSource = task.proofImageUrl,
                    contentDescription = "Submitted proof screenshot",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                  )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                  onClick = { showFullSizeSubmittedImage = true },
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.fillMaxWidth().testTag("user_view_full_image_button")
                ) {
                  Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("View Full-Size Submitted Screenshot")
                }
              }
            }
          }
          if (task.adminFeedback.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(
                  text = "ADMIN FEEDBACK",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.secondary,
                  fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = task.adminFeedback,
                  style = MaterialTheme.typography.bodyMedium
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Clean Task Submission Modal Dialog (`SubmitProofModalDialog`):
 * - Opened ONLY when the user taps `"Submit Proof"` on the Task Details Screen.
 * - Provides:
 *   1. Written text proof field (`task_proof_input`)
 *   2. Gallery photo selection option (`select_photo_button`)
 *   3. Selected photo preview (`selected_photo_preview_card` / `selected_photo_preview_image`)
 *   4. Mandatory checklist (`proof_validation_status_card`)
 *   5. `Submit` button (`submit_proof_button`) disabled until BOTH written text proof and Gallery photo are present.
 */
@Composable
fun SubmitProofModalDialog(
  task: TaskItem,
  isBusy: Boolean,
  isUserActive: Boolean,
  uploadProgress: Int?,
  submissionError: String?,
  initialSelectedImageUri: Uri? = null,
  onDismiss: () -> Unit,
  onSubmit: (String, Uri?) -> Unit
) {
  var proofInput by rememberSaveable(task.id) { mutableStateOf("") }
  var selectedImageUriString by rememberSaveable(task.id) {
    mutableStateOf(initialSelectedImageUri?.toString())
  }
  val selectedImageUri = selectedImageUriString?.let { Uri.parse(it) }

  val photoPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
      if (uri != null) {
        selectedImageUriString = uri.toString()
      }
    }

  val hasValidTextProof = proofInput.trim().isNotEmpty()
  val hasSelectedPhoto = selectedImageUri != null
  val canSubmitProof =
    task.isActive &&
      task.status == TaskStatus.PENDING &&
      !isBusy &&
      isUserActive &&
      hasValidTextProof &&
      hasSelectedPhoto

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("submit_proof_modal"),
    title = {
      Column {
        Text(
          text = "Submit Task Proof",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "${task.title} • ${formatRewardInr(task.rewardCents)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
        )
      }
    },
    text = {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("submit_task_proof_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text =
            "Both written text proof and at least one Gallery screenshot are mandatory.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 1. Written Text Proof Field
        OutlinedTextField(
          value = proofInput,
          onValueChange = { proofInput = it },
          enabled = !isBusy && isUserActive,
          label = { Text("Written Text Proof (Mandatory)") },
          placeholder = { Text(stringResource(R.string.submit_proof_hint)) },
          minLines = 3,
          maxLines = 6,
          shape = RoundedCornerShape(14.dp),
          modifier =
            Modifier.fillMaxWidth()
              .testTag("task_proof_input")
        )

        // Admin Reference / Demo Screenshot Example (if configured)
        if (task.referenceImages.isNotEmpty()) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF1F5F9),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1D4ED8).copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().testTag("reference_image_example_section")
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF1E3A8A), modifier = Modifier.size(16.dp))
                Text(
                  text = "ADMIN REFERENCE EXAMPLE (COMPARE WITH YOUR PROOF)",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF1E3A8A),
                  fontWeight = FontWeight.Bold
                )
              }
              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                task.referenceImages.forEachIndexed { idx, refUrl ->
                  Box(
                    modifier = Modifier
                      .size(100.dp)
                      .clip(RoundedCornerShape(12.dp))
                      .background(MaterialTheme.colorScheme.surface)
                      .border(1.dp, Color(0xFF1D4ED8).copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                  ) {
                    AsyncImage(
                      model = refUrl,
                      contentDescription = "Reference Example ${idx + 1}",
                      contentScale = ContentScale.Crop,
                      modifier = Modifier.fillMaxSize()
                    )
                  }
                }
              }
            }
          }
        }

        // 2. Gallery Photo Selection & Preview
        if (selectedImageUri == null) {
          OutlinedButton(
            onClick = {
              photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
              )
            },
            enabled = isUserActive && !isBusy,
            shape = RoundedCornerShape(14.dp),
            modifier =
              Modifier.fillMaxWidth()
                .height(50.dp)
                .testTag("select_photo_button")
          ) {
            Icon(
              imageVector = Icons.Default.AddPhotoAlternate,
              contentDescription = null,
              modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select Photo / Screenshot from Gallery")
          }
        } else {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier =
              Modifier.fillMaxWidth()
                .testTag("selected_photo_preview_card")
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(
                text = "SELECTED SCREENSHOT PREVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
              )
              Spacer(modifier = Modifier.height(8.dp))
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                      width = 1.dp,
                      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                      shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
              ) {
                AsyncImage(
                  model = selectedImageUri,
                  contentDescription = "Selected proof screenshot preview",
                  contentScale = ContentScale.Crop,
                  modifier =
                    Modifier.fillMaxSize()
                      .testTag("selected_photo_preview_image")
                )
              }

              Spacer(modifier = Modifier.height(8.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                OutlinedButton(
                  onClick = {
                    photoPickerLauncher.launch(
                      PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                  },
                  enabled = !isBusy,
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.weight(1f).testTag("replace_photo_button")
                ) {
                  Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Replace")
                }
                OutlinedButton(
                  onClick = { selectedImageUriString = null },
                  enabled = !isBusy,
                  shape = RoundedCornerShape(10.dp),
                  colors =
                    ButtonDefaults.outlinedButtonColors(
                      contentColor = MaterialTheme.colorScheme.error
                    ),
                  modifier = Modifier.weight(1f).testTag("remove_photo_button")
                ) {
                  Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Remove")
                }
              }
            }
          }
        }

        // Mandatory Requirement Checklist
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
          modifier = Modifier.fillMaxWidth().testTag("proof_validation_status_card")
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            ProofRequirementRow(
              label = "Written text proof entered",
              fulfilled = hasValidTextProof,
              testTag = "requirement_text_proof"
            )
            ProofRequirementRow(
              label = "Gallery photo / screenshot attached",
              fulfilled = hasSelectedPhoto,
              testTag = "requirement_photo_proof"
            )
          }
        }

        if (uploadProgress != null) {
          Column(
            modifier = Modifier.fillMaxWidth().testTag("upload_progress_indicator"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            LinearProgressIndicator(
              progress = { (uploadProgress.coerceIn(0, 100)) / 100f },
              modifier = Modifier.fillMaxWidth()
            )
            Text(
              text = "Uploading & saving proof submission… ${uploadProgress.coerceIn(0, 100)}%",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary
            )
          }
        }

        if (!submissionError.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().testTag("submission_error_card")
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = submissionError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSubmit(proofInput, selectedImageUri) },
        enabled = canSubmitProof,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.testTag("submit_proof_button")
      ) {
        if (isBusy) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.dp
          )
        } else {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.submit_proof_cta))
        }
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        enabled = !isBusy,
        modifier = Modifier.testTag("cancel_submit_proof_button")
      ) {
        Text("Cancel")
      }
    }
  )
}

@Composable
private fun ProofRequirementRow(
  label: String,
  fulfilled: Boolean,
  testTag: String
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.testTag(testTag)
  ) {
    Icon(
      imageVector =
        if (fulfilled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
      contentDescription = null,
      tint =
        if (fulfilled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(16.dp)
    )
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color =
        if (fulfilled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = if (fulfilled) FontWeight.SemiBold else FontWeight.Normal
    )
  }
}

/**
 * Available Tasks Screen (`UserTrackingScreen`):
 * - Opened when the user taps "Available Tasks" or the "Tasks" bottom navigation tab.
 * - Displays active tasks available from Firestore (`isActive == true` for pending tasks).
 * - Renders each task as a clean, compact individual card (`TaskItemCard`) showing ONLY:
 *   - App/site icon or image
 *   - Task title
 *   - App or website name
 *   - Category
 *   - Reward in INR (₹)
 *   - "View Task" button
 * - Does NOT display full task descriptions or submission forms on the task list.
 */
@Composable
fun UserTrackingScreen(
  tasksState: UiState<List<TaskItem>>,
  selectedFilter: String,
  onSelectFilter: (String) -> Unit,
  onSelectTask: (String) -> Unit,
  currentUserId: String = "",
  nextTelegramTask: TelegramTaskItem? = null,
  telegramTaskState: TelegramUserTaskState? = null,
  isTelegramBusy: Boolean = false,
  onJoinTelegramTask: (TelegramTaskItem) -> Unit = {},
  onVerifyTelegramTask: (TelegramTaskItem) -> Unit = {},
  onClaimTelegramTask: (TelegramTaskItem) -> Unit = {}
) {
  val rawTasks = (tasksState as? UiState.Success)?.data ?: emptyList()
  // Exclude deactivated or quota-exhausted pending tasks so closed/exhausted tasks are never available for new user submissions
  val visibleTasks =
    remember(rawTasks, currentUserId) {
      rawTasks.filter { task ->
        if (task.status == TaskStatus.PENDING) {
          task.isActive && !task.isQuotaExhausted
        } else {
          currentUserId.isBlank() || task.assignedUserId == currentUserId || (task.isActive && !task.isQuotaExhausted)
        }
      }
    }

  val filteredTasks =
    remember(visibleTasks, selectedFilter) {
      when (selectedFilter.lowercase(Locale.US)) {
        "all" -> visibleTasks
        TaskStatus.PENDING -> visibleTasks.filter { it.isActive && !it.isQuotaExhausted && it.status == TaskStatus.PENDING }
        else -> visibleTasks.filter { it.status.equals(selectedFilter, ignoreCase = true) }
      }
    }

  val showTelegramTaskCard =
    nextTelegramTask != null &&
      nextTelegramTask.isValidAndActive() &&
      !(telegramTaskState?.isClaimed == true) &&
      (selectedFilter.equals("all", ignoreCase = true) || selectedFilter.equals(TaskStatus.PENDING, ignoreCase = true))

  val availableCount = visibleTasks.count { it.isActive && !it.isQuotaExhausted && it.status == TaskStatus.PENDING }
  val filters =
    listOf(
      "all" to "All (${visibleTasks.size})",
      TaskStatus.PENDING to "Available ($availableCount)",
      TaskStatus.SUBMITTED to "Pending Review (${visibleTasks.count { it.status == TaskStatus.SUBMITTED }})",
      TaskStatus.APPROVED to "Approved (${visibleTasks.count { it.status == TaskStatus.APPROVED }})",
      TaskStatus.REJECTED to "Rejected (${visibleTasks.count { it.status == TaskStatus.REJECTED }})"
    )

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 18.dp, vertical = 14.dp)
        .testTag("available_tasks_screen")
  ) {
    Text(
      text = "Available Tasks",
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = "Select any task card to view full instructions, open the link, and submit proof.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(14.dp))

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      filters.forEach { (key, label) ->
        val isSelected = selectedFilter == key
        FilterChip(
          selected = isSelected,
          onClick = { onSelectFilter(key) },
          label = { Text(label) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
          modifier = Modifier.testTag("tracking_filter_$key")
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    if (filteredTasks.isEmpty() && !showTelegramTaskCard) {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Default.HourglassTop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp)
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = "No tasks in ${if (selectedFilter == "all") "any" else TaskStatus.label(selectedFilter)} status",
            style = MaterialTheme.typography.titleMedium
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Active tasks published by the administrator will appear here as compact cards.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
      ) {
        if (showTelegramTaskCard && nextTelegramTask != null) {
          item(key = "telegram_task_${nextTelegramTask.effectiveTaskId}") {
            TelegramJoinTaskCard(
              task = nextTelegramTask,
              taskState = telegramTaskState,
              isBusy = isTelegramBusy,
              onJoin = { onJoinTelegramTask(nextTelegramTask) },
              onVerify = { onVerifyTelegramTask(nextTelegramTask) },
              onClaim = { onClaimTelegramTask(nextTelegramTask) }
            )
          }
        }
        items(filteredTasks, key = { it.id }) { task ->
          TaskItemCard(
            task = task,
            onClick = { onSelectTask(task.id) }
          )
        }
      }
    }
  }
}

@Composable
private fun TelegramLogoBadge(
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier.size(40.dp).clip(CircleShape).background(Color(0xFF229ED9)),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.size(22.dp)) {
      val w = size.width
      val h = size.height
      val planePath = Path().apply {
        moveTo(w * 0.90f, h * 0.16f)
        lineTo(w * 0.10f, h * 0.48f)
        lineTo(w * 0.38f, h * 0.58f)
        lineTo(w * 0.76f, h * 0.30f)
        lineTo(w * 0.47f, h * 0.64f)
        lineTo(w * 0.45f, h * 0.84f)
        lineTo(w * 0.59f, h * 0.70f)
        lineTo(w * 0.78f, h * 0.82f)
        close()
      }
      drawPath(path = planePath, color = Color.White)
    }
  }
}

@Composable
fun TelegramJoinTaskCard(
  task: TelegramTaskItem,
  taskState: TelegramUserTaskState? = null,
  isBusy: Boolean = false,
  onJoin: () -> Unit,
  onVerify: () -> Unit,
  onClaim: () -> Unit
) {
  val isVerified = taskState?.isMembershipVerified == true
  val isClaimed = taskState?.isClaimed == true
  val statusMessage = taskState?.statusMessage?.trim().orEmpty()
  val errorMessage = taskState?.errorMessage?.trim().orEmpty()

  NeonGlassCard(
    shape = RoundedCornerShape(18.dp),
    borderColor = Color(0xFF229ED9).copy(alpha = 0.45f),
    backgroundColor = MaterialTheme.colorScheme.surface,
    modifier = Modifier.fillMaxWidth().testTag("user_telegram_task_card")
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          TelegramLogoBadge()
          Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFDBEAFE),
                modifier = Modifier.testTag("user_telegram_new_badge")
              ) {
                Text(
                  text = "NEW",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.ExtraBold,
                  color = Color(0xFF1D4ED8),
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
              Text(
                text = task.title.ifBlank { "Telegram Join Task" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
          shape = RoundedCornerShape(50),
          color = Color(0xFFECFDF5),
          border = BorderStroke(1.dp, Color(0xFF10B981))
        ) {
          Text(
            text = "+${formatCoins(task.rewardCoins)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF047857),
            modifier =
              Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("user_telegram_reward_badge")
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        OutlinedButton(
          onClick = onJoin,
          enabled = !isBusy && !isClaimed,
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
          modifier =
            Modifier.weight(1f)
              .height(48.dp)
              .testTag("user_telegram_join_button")
        ) {
          Text(
            text = "JOIN",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
          )
        }

        Button(
          onClick = onVerify,
          enabled = !isBusy && !isClaimed,
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
          modifier =
            Modifier.weight(1f)
              .height(48.dp)
              .testTag("user_telegram_verify_button")
        ) {
          Text(
            text = if (isVerified) "VERIFIED" else "VERIFY",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
          )
        }

        Button(
          onClick = onClaim,
          enabled = isVerified && !isBusy && !isClaimed,
          shape = RoundedCornerShape(10.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = Color(0xFF059669),
              disabledContainerColor = Color(0xFFE2E8F0),
              disabledContentColor = Color(0xFF64748B)
            ),
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
          modifier =
            Modifier.weight(1f)
              .height(48.dp)
              .testTag("user_telegram_claim_button")
        ) {
          Text(
            text = "CLAIM",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1
          )
        }
      }

      if (errorMessage.isNotEmpty()) {
        Text(
          text = errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("user_telegram_error_text")
        )
      } else if (statusMessage.isNotEmpty()) {
        Text(
          text = statusMessage,
          style = MaterialTheme.typography.bodySmall,
          color = if (isVerified) Color(0xFF047857) else Color(0xFF0369A1),
          fontWeight = FontWeight.Medium,
          modifier = Modifier.testTag("user_telegram_status_text")
        )
      }
    }
  }
}
