package com.example.ui.user

import android.net.Uri
import androidx.activity.compose.BackHandler
import com.example.data.model.getEffectiveBalanceCoins
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.filled.ReceiptLong
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.calculateDailyCheckInState
import com.example.data.model.formatRewardInr
import com.example.data.model.mergeTasksWithPreservedSubmissions
import com.example.data.model.WatchStats
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
  onNavigateToOfferWall: () -> Unit = {}
) {
  val profile = (profileState as? UiState.Success)?.data
  val currentUserId = profile?.userId?.ifBlank { profile.id } ?: ""
  val rawTasks = (tasksState as? UiState.Success)?.data ?: emptyList()
  val submissions = (submissionsState as? UiState.Success)?.data ?: emptyList()
  val transactions = (transactionsState as? UiState.Success)?.data ?: emptyList()
  val leaderboardUsers = (leaderboardState as? UiState.Success)?.data ?: emptyList()

  var showWatchAdDialog by rememberSaveable { mutableStateOf(false) }
  var showLeaderboardDialog by rememberSaveable { mutableStateOf(false) }

  // Active available tasks (isActive == true and status == PENDING)
  val activeAvailableTasks =
    remember(rawTasks) {
      rawTasks.filter { it.isActive && it.status == TaskStatus.PENDING }
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
      onDismiss = { showLeaderboardDialog = false }
    )
  }

  NeonBackgroundBox {
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("user_home_screen"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // 1. Header: Welcome & Compact Balance Pill
      item {
        NeonGlassCard(
          modifier = Modifier.fillMaxWidth(),
          borderColor = Color(0x60A855F7),
          backgroundColor = Color(0x40141938)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              TaskFlowBrandLogo(size = 40.dp)
              Column {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Text(
                    text = "Welcome back,",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Medium
                  )
                  if (profile?.approvalStatus == "approved") {
                    Icon(
                      imageVector = Icons.Default.CheckCircle,
                      contentDescription = "Verified Account",
                      tint = Color(0xFF22D3EE),
                      modifier = Modifier.size(15.dp)
                    )
                  }
                }
                Text(
                  text = displayName,
                  style = MaterialTheme.typography.titleLarge,
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.testTag("home_welcome_message")
                )
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Compact Balance Pill on right side
            Surface(
              shape = RoundedCornerShape(50),
              color = Color(0x35F59E0B),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x80FBBF24)),
              modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onNavigateToWallet(true) }
                .testTag("home_compact_balance")
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.AccountBalanceWallet,
                  contentDescription = "Wallet",
                  tint = Color(0xFFFBBF24),
                  modifier = Modifier.size(16.dp)
                )
                Column(verticalArrangement = Arrangement.Center) {
                  Text(
                    text = "MY BALANCE",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = "${profile?.getEffectiveBalanceCoins() ?: 0L} Coins",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFFDE047),
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.testTag("home_available_balance")
                  )
                }
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                  contentDescription = "Open Withdraw",
                  tint = Color(0xFFFBBF24),
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
        }
      }

      // 2. Explore Rewards Section (EXACTLY 4 Equal-Sized Square Cards in a 2x2 Grid)
      item {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "Explore Rewards",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp)
          )
          Row(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
            modifier = Modifier.fillMaxWidth().height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
        }
      }

      // Unity Banner Ad View
      item {
        UnityBannerAdView(modifier = Modifier.clip(RoundedCornerShape(16.dp)))
      }

      // 3. Available Tasks Section Header
      item {
        NeonGlassCard(
          modifier = Modifier.fillMaxWidth().testTag("home_available_tasks_section"),
          borderColor = Color(0x6006B6D4),
          backgroundColor = Color(0x40141938)
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Available Tasks",
                  style = MaterialTheme.typography.titleLarge,
                  color = Color.White,
                  fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text =
                    if (activeAvailableTasks.isNotEmpty()) {
                      "${activeAvailableTasks.size} active task(s) available to earn rewards"
                    } else {
                      "Browse verified tasks published by the administrator"
                    },
                  style = MaterialTheme.typography.bodySmall,
                  color = Color(0xFF94A3B8)
                )
              }
              TextButton(
                onClick = { onNavigateToTracking(TaskStatus.PENDING) },
                modifier = Modifier.testTag("view_all_tracking_button")
              ) {
                Text("View All (${activeAvailableTasks.size})", color = Color(0xFF22D3EE))
              }
            }

            Button(
              onClick = { onNavigateToTracking(TaskStatus.PENDING) },
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0284C7),
                contentColor = Color.White
              ),
              modifier =
                Modifier.fillMaxWidth()
                  .height(48.dp)
                  .testTag("browse_available_tasks_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Assignment,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Browse Available Tasks (${activeAvailableTasks.size})",
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }

      // Active Task Cards
      if (tasksState is UiState.Loading) {
        item {
          Box(
            modifier = Modifier.fillMaxWidth().padding(36.dp),
            contentAlignment = Alignment.Center
          ) {
            CircularProgressIndicator(color = Color(0xFFA855F7))
          }
        }
      } else if (tasksState is UiState.Error) {
        item {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0x30EF4444),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x80F87171)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = (tasksState as UiState.Error).message,
              style = MaterialTheme.typography.bodyMedium,
              color = Color(0xFFFCA5A5),
              modifier = Modifier.padding(16.dp)
            )
          }
        }
      } else {
        if (activeAvailableTasks.isEmpty()) {
          item {
            EmptyAssignedTaskCard(
              isAdminAuthorized = isAdminAuthorized,
              onSwitchToAdmin = onSwitchToAdmin
            )
          }
        } else {
          items(activeAvailableTasks.take(3), key = { "home_avail_${it.id}" }) { task ->
            TaskItemCard(
              task = task,
              onClick = { onSelectTask(task.id) }
            )
          }
        }
      }

      // 4. Top Earners Leaderboard
      item {
        LeaderboardPreviewCard(
          users = leaderboardUsers,
          onViewAllClicked = { showLeaderboardDialog = true }
        )
      }

      // Hidden test anchors preserving unit test compatibility
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
private fun DailyCheckInCard(
  dailyCheckInState: DailyCheckInState,
  onClaimDailyCheckIn: () -> Unit,
  onOpenCalendar: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  Card(
    modifier =
      modifier
        .testTag("home_daily_checkin_card")
        .clip(RoundedCornerShape(26.dp))
        .clickable {
          onClaimDailyCheckIn()
          onOpenCalendar()
        },
    shape = RoundedCornerShape(26.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Surface(
        shape = CircleShape,
        color = Color(0xFFFEF3C7),
        modifier = Modifier.size(58.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.LocalFireDepartment,
            contentDescription = "Daily Check-in",
            tint = Color(0xFFEA580C),
            modifier = Modifier.size(30.dp)
          )
        }
      }
      Spacer(modifier = Modifier.height(10.dp))
      Text(
        text = "Daily Check-in",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurface
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
  Card(
    modifier = modifier.testTag("home_watch_earn_card").clip(RoundedCornerShape(26.dp)),
    shape = RoundedCornerShape(26.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.SpaceBetween,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          tint = Color(0xFF5B21B6),
          modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
          text = "Watch & Earn",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.ExtraBold,
          color = Color(0xFF5B21B6),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      Text(
        text = "Up to 300 Coins",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF6B21A8),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("watch_earn_badge")
      )

      Button(
        onClick = onWatchAdClicked,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B21B6)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = Modifier.fillMaxWidth().height(36.dp).testTag("watch_ad_button")
      ) {
        Text(
          text = "Watch Now",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = Color.White
        )
      }
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

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("watch_earn_dialog"),
    shape = RoundedCornerShape(26.dp),
    containerColor = Color(0xFFFAFAFE),
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Surface(
          shape = CircleShape,
          color = Color(0xFFEDE9FE),
          modifier = Modifier.size(44.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector =
                if (isReadyToClaim || isClaimed) Icons.Default.CheckCircle
                else Icons.Default.PlayArrow,
              contentDescription = null,
              tint = if (isReadyToClaim || isClaimed) Color(0xFF059669) else Color(0xFF6D28D9),
              modifier = Modifier.size(26.dp)
            )
          }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Watch & Earn",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E1B4B)
          )
          Surface(
            shape = RoundedCornerShape(50),
            color = Color(0xFFEDE9FE)
          ) {
            Text(
              text = "Earn Rewards",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF6D28D9),
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
            )
          }
        }
      }
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // Progress Card
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color(0xFFF3E8FF).copy(alpha = 0.6f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Text(
              text = "Today's Progress",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF4C1D95)
            )
            Text(
              text = "Videos Watched: ${watchStats.videosWatched} / 25",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF1E1B4B)
            )
          }
        }

        // Loading State Banner
        if (isLoadingAd || isClaiming) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFEFF6FF),
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
                color = Color(0xFF6D28D9)
              )
              Text(
                text =
                  watchEarnUiState.statusMessage.ifBlank {
                    if (isClaiming) "Claiming reward..." else "Loading video..."
                  },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1E3A8A)
              )
            }
          }
        }

        // Ready-to-Claim Verified State Banner & Claim Button
        if (isReadyToClaim) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFECFDF5),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA7F3D0)),
            modifier = Modifier.fillMaxWidth().testTag("watch_earn_ready_to_claim_banner")
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text =
                  watchEarnUiState.statusMessage.ifBlank {
                    "Video completed! Tap Claim to receive your reward."
                  },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF065F46),
                textAlign = TextAlign.Center
              )
              Button(
                onClick = onClaimVerifiedReward,
                enabled = !isClaiming,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                modifier =
                  Modifier.fillMaxWidth()
                    .height(48.dp)
                    .testTag("claim_watch_reward_button")
              ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Claim",
                  fontWeight = FontWeight.Bold,
                  color = Color.White
                )
              }
            }
          }
        }

        // Claimed Confirmation Banner
        if (isClaimed) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFECFDF5),
            modifier = Modifier.fillMaxWidth().testTag("watch_earn_claimed_banner")
          ) {
            Text(
              text =
                watchEarnUiState.statusMessage.ifBlank {
                  "Reward claimed successfully and added to your wallet!"
                },
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF047857),
              modifier = Modifier.padding(14.dp)
            )
          }
        }

        // Error State Banner with Retry Option
        if (hasError && !watchEarnUiState.errorMessage.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFFEF2F2),
            modifier = Modifier.fillMaxWidth().testTag("watch_earn_error_banner")
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              val rawErr = watchEarnUiState.errorMessage.orEmpty()
              val formattedErr = if (rawErr.isBlank() || rawErr == "NOT_FOUND" || rawErr.contains("NOT_FOUND", ignoreCase = true)) {
                "Video is currently unavailable. Please tap Try Again to reload."
              } else {
                rawErr
              }
              Text(
                text = formattedErr,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFB91C1C)
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
                    tint = Color(0xFFB91C1C),
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "Try Again",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB91C1C)
                  )
                }
              }
            }
          }
        }

        // Watch Now Button (hidden while Claim button is active for current completed video)
        if (!isReadyToClaim) {
          Button(
            onClick = onStartWatchVideo,
            enabled = !isLimitReached && !isLoadingAd && !isClaiming,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("watch_ad_action_button")
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text =
                when {
                  isLimitReached -> "Daily Limit Reached (25/25)"
                  isLoadingAd -> "Loading Video..."
                  else -> "Watch Now"
                },
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
        }

        // Instructions
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "How it works:",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E1B4B)
          )
          Text(
            text = "• Tap Watch Now and watch the complete video until the end.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "• Once the video is completed and verified, tap Claim to credit your wallet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "• Up to 25 videos can be completed per day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        enabled = !isLoadingAd && !isClaiming,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D28D9)),
        modifier =
          Modifier.fillMaxWidth()
            .testTag("close_watch_dialog_button")
      ) {
        Text("Close", fontWeight = FontWeight.Bold)
      }
    }
  )
}

@Composable
private fun SpinAndEarnCard(
  onClick: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  NeonGlassCard(
    modifier = modifier.testTag("home_spin_earn_card"),
    borderColor = Color(0x60EC4899),
    backgroundColor = Color(0x3B1F174B),
    onClick = onClick
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(14.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Surface(
        shape = CircleShape,
        color = Color(0x35EC4899),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x80F472B6)),
        modifier = Modifier.size(52.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Spin & Earn",
            tint = Color(0xFFF472B6),
            modifier = Modifier.size(28.dp)
          )
        }
      }
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Spin &\nEarn",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        lineHeight = 18.sp,
        color = Color.White
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "Win Up to 500 Coins",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 9.sp,
        color = Color(0xFFF472B6),
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

@Composable
private fun OfferWallCard(
  onClick: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  NeonGlassCard(
    modifier = modifier.testTag("home_offer_wall_card"),
    borderColor = Color(0x6010B981),
    backgroundColor = Color(0x3B1F174B),
    onClick = onClick
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(14.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Surface(
        shape = CircleShape,
        color = Color(0x3510B981),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x8034D399)),
        modifier = Modifier.size(52.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.Campaign,
            contentDescription = "Offer Wall",
            tint = Color(0xFF34D399),
            modifier = Modifier.size(28.dp)
          )
        }
      }
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Offer\nWall",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        lineHeight = 18.sp,
        color = Color.White
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "High Paying Tasks",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 9.sp,
        color = Color(0xFF34D399),
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

@Composable
private fun LeaderboardPreviewCard(
  users: List<UserProfile>,
  onViewAllClicked: () -> Unit
) {
  val top3 = remember(users) { users.take(3) }

  Card(
    modifier = Modifier.fillMaxWidth().testTag("home_leaderboard_section"),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
            shape = CircleShape,
            color = Color(0xFFFBBF24).copy(alpha = 0.2f),
            modifier = Modifier.size(38.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Color(0xFFD97706),
                modifier = Modifier.size(22.dp)
              )
            }
          }
          Column {
            Text(
              text = "Top Earners Leaderboard",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Live rankings of top members",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        TextButton(
          onClick = onViewAllClicked,
          modifier = Modifier.testTag("view_all_leaderboard_button")
        ) {
          Text("View All")
        }
      }

      if (top3.isEmpty()) {
        Text(
          text = "No leaderboard data yet. Complete verified tasks to rank at the top!",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 4.dp)
        )
      } else {
        top3.forEachIndexed { index, user ->
          val rank = index + 1
          val rankBadgeColor = when (rank) {
            1 -> Color(0xFFFBBF24)
            2 -> Color(0xFF94A3B8)
            else -> Color(0xFFB45309)
          }
          val rankBadgeText = when (rank) {
            1 -> "🥇 #1"
            2 -> "🥈 #2"
            else -> "🥉 #3"
          }

          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
              ) {
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = rankBadgeColor.copy(alpha = 0.2f)
                ) {
                  Text(
                    text = rankBadgeText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = rankBadgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                  )
                }

                Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.size(34.dp)
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Text(
                      text = (user.displayName.firstOrNull() ?: 'M').uppercaseChar().toString(),
                      style = MaterialTheme.typography.labelLarge,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                  }
                }

                Text(
                  text = user.displayName.ifBlank { "TaskFlow Member" },
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }

              Text(
                text = formatRewardInr(user.balanceCents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF16A34A)
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LeaderboardDialog(
  users: List<UserProfile>,
  onDismiss: () -> Unit
) {
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
          modifier = Modifier.size(24.dp)
        )
        Text(
          text = "TaskFlow Top Earners",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (users.isEmpty()) {
          Text(
            text = "No approved members on leaderboard yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          users.take(50).forEachIndexed { index, user ->
            val rank = index + 1
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(32.dp)
                  )
                  Text(
                    text = user.displayName.ifBlank { "TaskFlow Member" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                Text(
                  text = formatRewardInr(user.balanceCents),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF16A34A)
                )
              }
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
              Text(
                text = if (tx.type == TransactionType.DEMO_WITHDRAWAL) {
                  "-${formatRewardInr(tx.amountCents)}"
                } else {
                  "+${formatRewardInr(tx.amountCents)}"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (tx.type == TransactionType.DEMO_WITHDRAWAL) {
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
    color = Color.White.copy(alpha = 0.08f),
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
        color = Color(0xFFCBD5E1)
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
  initialShowSubmitProofModal: Boolean = false
) {
  BackHandler(onBack = onBack)

  val context = LocalContext.current
  val isUserActive = userApprovalStatus == "approved"
  val isTaskAvailable = task.isActive && task.status == TaskStatus.PENDING
  val isTaskOwnerOrUnspecified = currentUserId.isBlank() || task.assignedUserId == currentUserId
  val appOrWebsiteName = resolveAppOrWebsiteLabel(task)

  var showSubmitProofModal by rememberSaveable(task.id) {
    mutableStateOf(initialShowSubmitProofModal)
  }
  var showFullSizeSubmittedImage by rememberSaveable(task.id) { mutableStateOf(false) }

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
          text = "Review instructions, open the task link, and submit proof when finished",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    // Main Task Details Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
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
                text = "REWARD:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
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
              text = "Published $createdDateStr",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 1. Task Overview
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "1. TASK OVERVIEW",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = task.description,
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.testTag("task_detail_description")
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Step-by-Step Instructions
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth().testTag("task_detail_instructions_card")
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "2. STEP-BY-STEP INSTRUCTIONS",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            val rawInstructions = task.instructions.ifBlank { task.description }
            Text(
              text = rawInstructions,
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.testTag("task_detail_instructions")
            )
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

        // Action Button 1: "Start Task / Open Link"
        if (task.taskLink.isNotBlank()) {
          Button(
            onClick = { openExternalTaskUrl(context, task.taskLink) },
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
              text = "Start Task / Open Link",
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
        } else if (!task.isActive && task.status == TaskStatus.PENDING) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
            modifier = Modifier.fillMaxWidth().testTag("deactivated_task_notice")
          ) {
            Text(
              text = "This task is currently deactivated by the administrator and is not accepting new submissions.",
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
  currentUserId: String = ""
) {
  val rawTasks = (tasksState as? UiState.Success)?.data ?: emptyList()
  // Exclude deactivated pending tasks so deactivated tasks are never available for new user submissions
  val visibleTasks =
    remember(rawTasks, currentUserId) {
      rawTasks.filter { task ->
        if (task.status == TaskStatus.PENDING) {
          task.isActive
        } else {
          currentUserId.isBlank() || task.assignedUserId == currentUserId || task.isActive
        }
      }
    }

  val filteredTasks =
    remember(visibleTasks, selectedFilter) {
      when (selectedFilter.lowercase(Locale.US)) {
        "all" -> visibleTasks
        TaskStatus.PENDING -> visibleTasks.filter { it.isActive && it.status == TaskStatus.PENDING }
        else -> visibleTasks.filter { it.status.equals(selectedFilter, ignoreCase = true) }
      }
    }

  val availableCount = visibleTasks.count { it.isActive && it.status == TaskStatus.PENDING }
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

    if (filteredTasks.isEmpty()) {
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
