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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.PromoRedemptionUiState
import com.example.data.model.TelegramJoinConfig
import com.example.data.model.TelegramUserRewardState
import com.example.data.model.formatCoins
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonGlassCard
import com.example.ui.components.TaskFlowBrandLogo
import com.example.ui.theme.taskFlowColors
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

internal fun computeIstMidnightCountdownMillis(nowMillis: Long): Long {
  val istTz = TimeZone.getTimeZone("Asia/Kolkata")
  val cal = Calendar.getInstance(istTz, Locale.US)
  cal.timeInMillis = nowMillis
  cal.set(Calendar.HOUR_OF_DAY, 0)
  cal.set(Calendar.MINUTE, 0)
  cal.set(Calendar.SECOND, 0)
  cal.set(Calendar.MILLISECOND, 0)
  cal.add(Calendar.DAY_OF_YEAR, 1)
  return (cal.timeInMillis - nowMillis).coerceAtLeast(0L)
}

internal fun formatCountdownHms(diffMillis: Long): String {
  val clamped = diffMillis.coerceAtLeast(0L)
  val hours = clamped / (1000L * 60L * 60L)
  val minutes = (clamped / (1000L * 60L)) % 60L
  val seconds = (clamped / 1000L) % 60L
  return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

/**
 * Dedicated Daily Check-in Screen with a compact 5 columns × 6 rows 30-day grid
 * of rounded square cards and a prominent separate Claim button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedicatedDailyCheckInScreen(
  dailyCheckInState: DailyCheckInState,
  approvedEarningsCents: Long = 0L,
  onClaimDay: (Int) -> Unit,
  onBack: () -> Unit,
  isBusy: Boolean = false,
  telegramConfig: TelegramJoinConfig = TelegramJoinConfig(),
  telegramRewardState: TelegramUserRewardState = TelegramUserRewardState(),
  onJoinTelegramChannel: ((String) -> Unit)? = null,
  onVerifyTelegramMembership: (String) -> Unit = {},
  onClaimTelegramReward: () -> Unit = {},
  onOpenPromoCodeScreen: () -> Unit = {}
) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val tfColors = taskFlowColors()

  var remainingCountdownMs by remember {
    mutableLongStateOf(computeIstMidnightCountdownMillis(System.currentTimeMillis()))
  }
  LaunchedEffect(dailyCheckInState.canCheckInToday) {
    remainingCountdownMs = computeIstMidnightCountdownMillis(System.currentTimeMillis())
    while (!dailyCheckInState.canCheckInToday) {
      delay(1_000L)
      remainingCountdownMs = computeIstMidnightCountdownMillis(System.currentTimeMillis())
    }
  }
  val timeRemainingToTomorrow = formatCountdownHms(remainingCountdownMs)

  val nextClaimDay = dailyCheckInState.nextClaimDay.coerceIn(1, 30)
  val todayRewardCoins = DailyCheckInConstants.getRewardCoinsForDay(nextClaimDay)

  NeonBackgroundBox {
    LazyColumn(
      modifier =
        Modifier.fillMaxSize().testTag("dedicated_daily_checkin_screen"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Top Navigation Bar
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          IconButton(
            onClick = onBack,
            modifier =
              Modifier.clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .testTag("daily_checkin_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to Home",
              tint = MaterialTheme.colorScheme.onSurface
            )
          }
          Text(
            text = "Daily Check-in",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
          )
          Surface(
            shape = RoundedCornerShape(50),
            color = tfColors.rewardBadgeBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, tfColors.rewardBadgeBorder)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = tfColors.goldAccent,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "${dailyCheckInState.currentStreak}d Streak",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tfColors.rewardBadgeText
              )
            }
          }
        }
      }

      // 1. Hero Banner + Separate Prominent Claim Button
      item {
        NeonGlassCard(
          shape = RoundedCornerShape(22.dp),
          borderColor = MaterialTheme.colorScheme.outlineVariant,
          backgroundColor = MaterialTheme.colorScheme.surface,
          modifier = Modifier.fillMaxWidth().testTag("daily_checkin_hero_card")
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
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "30-DAY COIN REWARD CYCLE",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text =
                    if (dailyCheckInState.canCheckInToday) {
                      "Day $nextClaimDay Ready: +${formatCoins(todayRewardCoins)}"
                    } else {
                      "Today's Check-in Claimed ✓"
                    },
                  style = MaterialTheme.typography.titleLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontWeight = FontWeight.SemiBold
                )
              }
              Surface(
                shape = RoundedCornerShape(50),
                color = tfColors.rewardBadgeBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, tfColors.rewardBadgeBorder)
              ) {
                Text(
                  text = "Day $nextClaimDay / 30",
                  style = MaterialTheme.typography.labelSmall,
                  color = tfColors.rewardBadgeText,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
              }
            }

            // Status / Timer Notice
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = tfColors.neutralSurfaceBg,
              border = androidx.compose.foundation.BorderStroke(1.dp, tfColors.neutralSurfaceBorder),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Schedule,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(16.dp)
                )
                Text(
                  text =
                    if (dailyCheckInState.canCheckInToday) {
                      "Claim today's +${formatCoins(todayRewardCoins)} streak reward below."
                    } else {
                      "Next daily check-in unlocks in $timeRemainingToTomorrow"
                    },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontWeight = FontWeight.Medium
                )
              }
            }

            // Separate Clearly Visible Claim Button
            Button(
              onClick = { onClaimDay(nextClaimDay) },
              enabled = dailyCheckInState.canCheckInToday && !isBusy,
              shape = RoundedCornerShape(14.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = tfColors.charcoalContainer,
                  contentColor = tfColors.charcoalContent,
                  disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                  disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
              modifier =
                Modifier.fillMaxWidth()
                  .height(50.dp)
                  .testTag("claim_daily_checkin_button")
            ) {
              if (isBusy) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.dp,
                  color = tfColors.charcoalContent
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = "Processing Check-in...",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold
                )
              } else if (dailyCheckInState.canCheckInToday) {
                Icon(
                  imageVector = Icons.Default.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Claim Day $nextClaimDay (+${formatCoins(todayRewardCoins)})",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold
                )
              } else {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Claimed for Today ✓",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }
        }
      }

      // 1B. Daily Promo Code Entry Card inside Daily Rewards
      item {
        DailyPromoCodeEntryCard(
          onOpenPromoCodeScreen = onOpenPromoCodeScreen
        )
      }

      // 2. Legend Row (Claimed / Today / Locked)
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "30-Day Schedule",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
          )
          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            GridLegendDot(color = tfColors.goldAccent, label = "Claimed")
            GridLegendDot(color = MaterialTheme.colorScheme.primary, label = "Today")
            GridLegendDot(color = MaterialTheme.colorScheme.onSurfaceVariant, label = "Locked")
          }
        }
      }

      // 3. Compact 5 Columns × 6 Rows Grid of Rounded Square Cards (Days 1..30)
      item {
        Column(
          modifier = Modifier.fillMaxWidth().testTag("daily_checkin_30day_grid"),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          for (row in 0 until 6) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              for (col in 0 until 5) {
                val day = row * 5 + col + 1
                val rewardCoins = DailyCheckInConstants.getRewardCoinsForDay(day)
                val isClaimed =
                  if (!dailyCheckInState.canCheckInToday) {
                    day <= dailyCheckInState.currentStreak
                  } else {
                    day < nextClaimDay
                  }
                val isCurrent = dailyCheckInState.canCheckInToday && day == nextClaimDay
                val isGrandBonus = day == 30 || day % 5 == 0

                CompactDayGridSquareCard(
                  day = day,
                  rewardCoins = rewardCoins,
                  isClaimed = isClaimed,
                  isCurrent = isCurrent,
                  isMilestone = isGrandBonus,
                  isBusy = isBusy,
                  onClick = {
                    if (isCurrent && !isBusy) {
                      onClaimDay(day)
                    }
                  },
                  modifier = Modifier.weight(1f)
                )
              }
            }
          }
        }
      }

      // 4. Concise Milestone Summary Card
      item {
        NeonGlassCard(
          shape = RoundedCornerShape(16.dp),
          borderColor = MaterialTheme.colorScheme.outlineVariant,
          backgroundColor = MaterialTheme.colorScheme.surface,
          modifier = Modifier.fillMaxWidth().testTag("daily_checkin_rules_card")
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Icon(
              imageVector = Icons.Default.EmojiEvents,
              contentDescription = null,
              tint = tfColors.goldAccent,
              modifier = Modifier.size(20.dp)
            )
            Text(
              text = "Milestone bonuses on Days 5, 10, 15, 20, 25 & ${formatCoins(1000L)} on Day 30.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }
  }
}

@Composable
private fun GridLegendDot(color: Color, label: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Box(
      modifier = Modifier.size(8.dp).clip(CircleShape).background(color)
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
      fontSize = 10.sp
    )
  }
}

@Composable
private fun CompactDayGridSquareCard(
  day: Int,
  rewardCoins: Long,
  isClaimed: Boolean,
  isCurrent: Boolean,
  isMilestone: Boolean,
  isBusy: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val tfColors = taskFlowColors()
  val borderColor =
    when {
      isCurrent -> MaterialTheme.colorScheme.primary
      isClaimed -> tfColors.statusConfirmedBorder
      isMilestone -> tfColors.rewardBadgeBorder
      else -> MaterialTheme.colorScheme.outlineVariant
    }

  val containerColor =
    when {
      isCurrent -> MaterialTheme.colorScheme.primaryContainer
      isClaimed -> tfColors.statusConfirmedBg
      isMilestone -> tfColors.rewardBadgeBg
      else -> MaterialTheme.colorScheme.surface
    }

  Box(
    modifier =
      modifier
        .aspectRatio(0.92f)
        .clip(RoundedCornerShape(14.dp))
        .background(containerColor)
        .border(
          width = if (isCurrent) 1.75.dp else 1.dp,
          color = borderColor,
          shape = RoundedCornerShape(14.dp)
        )
        .clickable(enabled = isCurrent && !isBusy, onClick = onClick)
        .padding(4.dp)
        .testTag("day_${day}_card"),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxSize().padding(vertical = 2.dp)
    ) {
      // Top Day Header
      Text(
        text = "D$day",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color =
          when {
            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
            isClaimed -> tfColors.statusConfirmedFg
            isMilestone -> tfColors.rewardBadgeText
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          }
      )

      // Center Status Icon or Coin Value
      Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center
      ) {
        when {
          isClaimed -> {
            Surface(
              shape = CircleShape,
              color = tfColors.goldAccent,
              modifier = Modifier.size(20.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = "Claimed",
                  tint = tfColors.claimContent,
                  modifier = Modifier.size(13.dp)
                )
              }
            }
          }
          isCurrent -> {
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.LocalFireDepartment,
                  contentDescription = "Claimable Today",
                  tint = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
          isMilestone -> {
            Icon(
              imageVector = Icons.Default.EmojiEvents,
              contentDescription = "Bonus Day",
              tint = tfColors.goldAccent,
              modifier = Modifier.size(16.dp)
            )
          }
          else -> {
            Icon(
              imageVector = Icons.Default.Lock,
              contentDescription = "Locked",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(13.dp)
            )
          }
        }
      }

      // Bottom Coin Amount
      Text(
        text = "+${formatCoins(rewardCoins)}",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color =
          when {
            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
            isClaimed -> tfColors.statusConfirmedFg
            isMilestone -> tfColors.rewardBadgeText
            else -> MaterialTheme.colorScheme.onSurface
          },
        textAlign = TextAlign.Center
      )
    }
  }
}

@Composable
fun DailyCheckInPendingDialog(
  streakDay: Int,
  rewardCents: Long,
  onDismiss: () -> Unit
) {
  val tfColors = taskFlowColors()
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Surface(
        shape = CircleShape,
        color = tfColors.rewardBadgeBg,
        modifier = Modifier.size(54.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.HourglassEmpty,
            contentDescription = null,
            tint = tfColors.goldAccent,
            modifier = Modifier.size(30.dp)
          )
        }
      }
    },
    title = {
      Text(
        text = "Daily Check-in Notice",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Day $streakDay Reward: +${formatCoins(rewardCents)}",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        shape = RoundedCornerShape(12.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = tfColors.claimContainer,
            contentColor = tfColors.claimContent
          ),
        modifier = Modifier.testTag("dismiss_pending_dialog_button")
      ) {
        Text("Understood", fontWeight = FontWeight.SemiBold)
      }
    }
  )
}

@Composable
fun TelegramJoinRewardDailyCard(
  config: TelegramJoinConfig,
  rewardState: TelegramUserRewardState,
  isBusy: Boolean,
  onOpenTelegramUrl: (String) -> Unit,
  onVerifyMembership: (String) -> Unit,
  onClaimReward: () -> Unit
) {
  val tfColors = taskFlowColors()
  var telegramUserIdInput by rememberSaveable(rewardState.telegramUserId) {
    mutableStateOf(rewardState.telegramUserId)
  }

  NeonGlassCard(
    shape = RoundedCornerShape(18.dp),
    borderColor = MaterialTheme.colorScheme.outlineVariant,
    backgroundColor = MaterialTheme.colorScheme.surface,
    modifier = Modifier.fillMaxWidth().testTag("daily_rewards_telegram_join_card")
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f)
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Telegram",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
              )
            }
          }
          Column {
            Text(
              text = "Telegram Join Reward",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "One-time channel membership bonus",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
        Surface(
          shape = RoundedCornerShape(50),
          color = tfColors.rewardBadgeBg,
          border = androidx.compose.foundation.BorderStroke(1.dp, tfColors.rewardBadgeBorder)
        ) {
          Text(
            text = "+${formatCoins(config.rewardCoins)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tfColors.rewardBadgeText,
            modifier =
              Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("daily_rewards_telegram_reward_badge")
          )
        }
      }

      // Step 1: Join Telegram button
      OutlinedButton(
        onClick = { onOpenTelegramUrl(config.channelUrl) },
        enabled = !isBusy,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(46.dp).testTag("daily_rewards_join_telegram_button")
      ) {
        Icon(
          imageVector = Icons.Default.OpenInNew,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Join Telegram",
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary
        )
      }

      // Step 2: Verify membership via Telegram Bot API
      if (!rewardState.isMembershipVerified) {
        OutlinedTextField(
          value = telegramUserIdInput,
          onValueChange = { telegramUserIdInput = it.filter { ch -> ch.isDigit() } },
          label = { Text("Telegram User ID") },
          placeholder = { Text("Enter your numeric Telegram ID") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("daily_rewards_telegram_user_id_input")
        )

        Button(
          onClick = { onVerifyMembership(telegramUserIdInput) },
          enabled = !isBusy && telegramUserIdInput.isNotBlank(),
          shape = RoundedCornerShape(12.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary
            ),
          modifier =
            Modifier.fillMaxWidth()
              .height(46.dp)
              .testTag("daily_rewards_verify_telegram_button")
        ) {
          Icon(
            imageVector = Icons.Default.VerifiedUser,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Verify Membership", fontWeight = FontWeight.SemiBold)
        }
      } else {
        // Step 3: Show Claim ONLY after successful membership verification
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = tfColors.statusConfirmedBg,
          border = androidx.compose.foundation.BorderStroke(1.dp, tfColors.statusConfirmedBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.CheckCircle,
              contentDescription = null,
              tint = tfColors.goldAccent,
              modifier = Modifier.size(18.dp)
            )
            Text(
              text = "Telegram channel membership verified!",
              style = MaterialTheme.typography.bodySmall,
              color = tfColors.statusConfirmedFg,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        Button(
          onClick = onClaimReward,
          enabled = !isBusy && !rewardState.isClaimed,
          shape = RoundedCornerShape(12.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = tfColors.claimContainer,
              contentColor = tfColors.claimContent
            ),
          modifier =
            Modifier.fillMaxWidth()
              .height(48.dp)
              .testTag("daily_rewards_claim_telegram_button")
        ) {
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Claim +${formatCoins(config.rewardCoins)}",
            fontWeight = FontWeight.SemiBold
          )
        }
      }

      if (!rewardState.errorMessage.isNullOrBlank()) {
        Text(
          text = rewardState.errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("daily_rewards_telegram_error_text")
        )
      }
    }
  }
}

@Composable
fun DailyPromoCodeEntryCard(
  onOpenPromoCodeScreen: () -> Unit
) {
  NeonGlassCard(
    shape = RoundedCornerShape(18.dp),
    borderColor = MaterialTheme.colorScheme.outlineVariant,
    backgroundColor = MaterialTheme.colorScheme.surface,
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onOpenPromoCodeScreen)
        .testTag("daily_rewards_promo_code_card")
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
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(40.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.ConfirmationNumber,
              contentDescription = "Daily Promo Code",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(20.dp)
            )
          }
        }
        Column {
          Text(
            text = "Daily Promo Code",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = "Redeem valid promo codes for instant wallet Coins",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      Button(
        onClick = onOpenPromoCodeScreen,
        shape = RoundedCornerShape(10.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
          ),
        modifier = Modifier.testTag("daily_rewards_open_promo_code_button")
      ) {
        Text("Redeem", fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

/**
 * Dedicated Promo Code Redemption Screen opened from Daily Rewards.
 */
@Composable
fun PromoCodeRedemptionScreen(
  uiState: PromoRedemptionUiState,
  onRedeemCode: (String) -> Unit,
  onClearFeedback: () -> Unit = {},
  onBack: () -> Unit
) {
  BackHandler(onBack = onBack)
  val tfColors = taskFlowColors()
  var codeText by rememberSaveable { mutableStateOf(uiState.codeInput) }

  LaunchedEffect(uiState.lastRedeemedCode) {
    if (uiState.lastRedeemedCode.isNotBlank() && uiState.codeInput.isEmpty()) {
      codeText = ""
    }
  }

  NeonBackgroundBox {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(horizontal = 16.dp, vertical = 14.dp)
          .testTag("promo_code_redemption_screen"),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        IconButton(
          onClick = onBack,
          modifier =
            Modifier.clip(CircleShape)
              .background(MaterialTheme.colorScheme.surface)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
              .testTag("promo_code_back_button")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to Daily Rewards",
            tint = MaterialTheme.colorScheme.onSurface
          )
        }
        TaskFlowBrandLogo(size = 30.dp)
        Text(
          text = "Daily Promo Code",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onBackground
        )
      }

      NeonGlassCard(
        shape = RoundedCornerShape(20.dp),
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        backgroundColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primaryContainer,
              modifier = Modifier.size(42.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.CardGiftcard,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.size(22.dp)
                )
              }
            }
            Column {
              Text(
                text = "Enter Promo Code",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Coins are credited directly to your TaskFlow Wallet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          OutlinedTextField(
            value = codeText,
            onValueChange = {
              codeText = it.uppercase(Locale.US)
              onClearFeedback()
            },
            enabled = !uiState.isRedeeming,
            label = { Text("Promo Code") },
            placeholder = { Text("Enter promo code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("promo_code_input_field")
          )

          Button(
            onClick = {
              if (!uiState.isRedeeming) {
                onRedeemCode(codeText.trim())
              }
            },
            enabled = !uiState.isRedeeming && codeText.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
              ),
            modifier =
              Modifier.fillMaxWidth()
                .height(48.dp)
                .testTag("promo_code_redeem_button")
          ) {
            if (uiState.isRedeeming) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("Redeeming...", fontWeight = FontWeight.SemiBold)
            } else {
              Text("Redeem", fontWeight = FontWeight.SemiBold)
            }
          }

          if (!uiState.successMessage.isNullOrBlank()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = tfColors.statusConfirmedBg,
              border = androidx.compose.foundation.BorderStroke(1.dp, tfColors.statusConfirmedBorder),
              modifier = Modifier.fillMaxWidth().testTag("promo_code_success_message")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = null,
                  tint = tfColors.goldAccent,
                  modifier = Modifier.size(20.dp)
                )
                Text(
                  text = uiState.successMessage,
                  style = MaterialTheme.typography.bodyMedium,
                  color = tfColors.statusConfirmedFg,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }

          if (!uiState.errorMessage.isNullOrBlank()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.errorContainer,
              border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
              modifier = Modifier.fillMaxWidth().testTag("promo_code_error_message")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.ErrorOutline,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(20.dp)
                )
                Text(
                  text = uiState.errorMessage,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }
        }
      }
    }
  }
}
