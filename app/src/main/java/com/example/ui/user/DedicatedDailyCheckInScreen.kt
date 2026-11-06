package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.formatRewardInr
import java.util.Calendar

/**
 * Dedicated Daily Check-in Screen:
 * - 7 individual day cards:
 *   Day 1 — ₹1
 *   Day 2 — ₹1
 *   Day 3 — ₹1
 *   Day 4 — ₹1
 *   Day 5 — ₹1 (unlocked when user completes approved tasks worth at least ₹20)
 *   Day 6 — ₹1 (unlocked when user completes approved tasks worth at least ₹20)
 *   Day 7 — ₹7 bonus (total reward for 7 consecutive days = ₹13)
 * - Clear status on each card: Available, Claimed, or Locked
 * - Individual working Claim buttons
 * - Next eligible claim countdown and streak rules
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedicatedDailyCheckInScreen(
  dailyCheckInState: DailyCheckInState,
  approvedEarningsCents: Long,
  onClaimDay: (Int) -> Unit,
  onBack: () -> Unit,
  isBusy: Boolean = false
) {
  BackHandler(onBack = onBack)

  val requiredTaskCents = 2000L // ₹20.00
  val isTaskRequirementMet = approvedEarningsCents >= requiredTaskCents
  val taskProgress = (approvedEarningsCents.toFloat() / requiredTaskCents.toFloat()).coerceIn(0f, 1f)

  val timeRemainingToTomorrow = remember {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 24)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val diff = (cal.timeInMillis - now).coerceAtLeast(0L)
    val hours = diff / (1000 * 60 * 60)
    val minutes = (diff / (1000 * 60)) % 60
    "${hours}h ${minutes}m"
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "Daily Check-in",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
          )
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("daily_checkin_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to Home"
            )
          }
        },
        actions = {
          Surface(
            shape = RoundedCornerShape(50),
            color = Color(0xFFFEF3C7),
            modifier = Modifier.padding(end = 12.dp)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = Color(0xFFD97706),
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "${dailyCheckInState.currentStreak}d Streak",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB45309)
              )
            }
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
          )
      )
    }
  ) { innerPadding ->
    LazyColumn(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .testTag("dedicated_daily_checkin_screen"),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      val currentStageDay = dailyCheckInState.nextClaimDay
      val showWeeklyBanner = currentStageDay >= 5 || dailyCheckInState.currentStreak >= 4

      // 1. Hero Banner: Streak Progress & Weekly Reward Overview (Visible only when reaching weekly reward stage: Day 5+)
      if (showWeeklyBanner) {
        item {
          Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth().testTag("daily_checkin_hero_card")
          ) {
            Box(
              modifier =
                Modifier.fillMaxWidth()
                  .background(
                    Brush.linearGradient(
                      colors =
                        listOf(
                          Color(0xFF1E3A8A),
                          Color(0xFF2563EB),
                          Color(0xFF7C3AED)
                        )
                    )
                  )
                  .padding(22.dp)
            ) {
              Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(
                  color = Color.White.copy(alpha = 0.08f),
                  radius = size.height * 0.75f,
                  center = Offset(size.width * 0.88f, size.height * 0.2f)
                )
              }
              Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column {
                    Text(
                      text = "7-Day Streak Rewards",
                      style = MaterialTheme.typography.labelMedium,
                      color = Color(0xFFDBEAFE),
                      fontWeight = FontWeight.SemiBold
                    )
                    Text(
                      text = "Earn ₹13.00 Every Week",
                      style = MaterialTheme.typography.titleLarge,
                      color = Color.White,
                      fontWeight = FontWeight.ExtraBold
                    )
                  }
                  Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFFFDE047),
                    modifier = Modifier.padding(start = 8.dp)
                  ) {
                    Text(
                      text = "₹13 / 7d",
                      style = MaterialTheme.typography.labelSmall,
                      color = Color(0xFF78350F),
                      fontWeight = FontWeight.ExtraBold,
                      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                  }
                }

                // 7-step horizontal visual tracker
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  for (day in 1..7) {
                    val isClaimed =
                      if (!dailyCheckInState.canCheckInToday) {
                        day <= dailyCheckInState.currentStreak
                      } else {
                        day < dailyCheckInState.nextClaimDay
                      }
                    val isCurrent = dailyCheckInState.canCheckInToday && day == dailyCheckInState.nextClaimDay

                    Column(
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                      Surface(
                        shape = CircleShape,
                        color =
                          when {
                            isClaimed -> Color(0xFF10B981)
                            isCurrent -> Color(0xFFFDE047)
                            else -> Color.White.copy(alpha = 0.22f)
                          },
                        modifier = Modifier.size(32.dp)
                      ) {
                        Box(contentAlignment = Alignment.Center) {
                          if (isClaimed) {
                            Icon(
                              imageVector = Icons.Default.Check,
                              contentDescription = "Claimed",
                              tint = Color.White,
                              modifier = Modifier.size(16.dp)
                            )
                          } else if (day == 7) {
                            Icon(
                              imageVector = Icons.Default.EmojiEvents,
                              contentDescription = "Bonus",
                              tint = if (isCurrent) Color(0xFF78350F) else Color.White,
                              modifier = Modifier.size(16.dp)
                            )
                          } else {
                            Text(
                              text = "$day",
                              style = MaterialTheme.typography.labelMedium,
                              fontWeight = FontWeight.Bold,
                              color = if (isCurrent) Color(0xFF78350F) else Color.White
                            )
                          }
                        }
                      }
                      Text(
                        text = if (day == 7) "₹7" else "₹1",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 10.sp,
                        fontWeight = if (isCurrent || isClaimed) FontWeight.Bold else FontWeight.Normal
                      )
                    }
                  }
                }

                // Next claim timing notice
                Surface(
                  shape = RoundedCornerShape(14.dp),
                  color = Color.White.copy(alpha = 0.16f),
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
                      tint = Color(0xFFFDE047),
                      modifier = Modifier.size(16.dp)
                    )
                    Text(
                      text =
                        if (dailyCheckInState.canCheckInToday) {
                          "Day ${dailyCheckInState.nextClaimDay} (${formatRewardInr(dailyCheckInState.nextRewardCents)}) is ready to claim today!"
                        } else {
                          "Today's reward claimed ✓ Next claim unlocks in: $timeRemainingToTomorrow (Tomorrow)"
                        },
                      style = MaterialTheme.typography.bodySmall,
                      color = Color.White,
                      fontWeight = FontWeight.Medium
                    )
                  }
                }
              }
            }
          }
        }
      } else {
        // Compact Status Header for Days 1–4
        item {
          Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth().testTag("daily_checkin_hero_card")
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text(
                  text = "Daily Rewards",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text =
                    if (dailyCheckInState.canCheckInToday) {
                      "Day ${dailyCheckInState.nextClaimDay} Available"
                    } else {
                      "Day ${dailyCheckInState.currentStreak} Claimed ✓"
                    },
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
              }
              Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFFFEF3C7)
              ) {
                Text(
                  text = "🔥 Streak: ${dailyCheckInState.currentStreak}d",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFB45309),
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
              }
            }
          }
        }
      }

      // 2. Day 5 and Day 6 Task Requirement Card (Hidden during Days 1–4, shown specifically when reaching Day 5 or Day 6)
      if (currentStageDay == 5 || currentStageDay == 6) {
        item {
          val stageTitle = if (currentStageDay == 5) "Day 5 Task Requirement" else "Day 6 Task Requirement"
          val stageMessage =
            if (currentStageDay == 5) {
              "To unlock Day 5 check-in reward (₹1.00), you must complete approved tasks worth at least ₹20.00. Only verified approved tasks are counted."
            } else {
              "To unlock Day 6 check-in reward (₹1.00), you must complete approved tasks worth at least ₹20.00. Only verified approved tasks are counted."
            }

          Card(
            shape = RoundedCornerShape(20.dp),
            colors =
              CardDefaults.cardColors(
                containerColor =
                  if (isTaskRequirementMet) {
                    Color(0xFFECFDF5)
                  } else {
                    Color(0xFFFEF2F2)
                  }
              ),
            border =
              androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isTaskRequirementMet) Color(0xFFA7F3D0) else Color(0xFFFECACA)
              ),
            modifier = Modifier.fillMaxWidth().testTag("day_5_6_task_requirement_card")
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = if (isTaskRequirementMet) Icons.Default.CheckCircle else Icons.Default.Info,
                  contentDescription = null,
                  tint = if (isTaskRequirementMet) Color(0xFF059669) else Color(0xFFDC2626),
                  modifier = Modifier.size(20.dp)
                )
                Text(
                  text = stageTitle,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = if (isTaskRequirementMet) Color(0xFF065F46) else Color(0xFF991B1B)
                )
              }

              Text(
                text = stageMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (isTaskRequirementMet) Color(0xFF047857) else Color(0xFF7F1D1D)
              )

              LinearProgressIndicator(
                progress = { taskProgress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (isTaskRequirementMet) Color(0xFF10B981) else Color(0xFFEF4444),
                trackColor = if (isTaskRequirementMet) Color(0xFFD1FAE5) else Color(0xFFFEE2E2),
                strokeCap = StrokeCap.Round
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Completed: ${formatRewardInr(approvedEarningsCents)} / ₹20.00",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = if (isTaskRequirementMet) Color(0xFF065F46) else Color(0xFF991B1B)
                )
                Text(
                  text = if (isTaskRequirementMet) "Requirement Met ✓" else "Locked",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = if (isTaskRequirementMet) Color(0xFF059669) else Color(0xFFDC2626)
                )
              }
            }
          }
        }
      }

      // 3. Section Title
      item {
        Text(
          text = "7-Day Daily Claim Cards",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )
      }

      // 4. Individual 7 Day Cards
      for (day in 1..7) {
        val rewardCents = DailyCheckInConstants.getRewardForDay(day)
        val isDay7Bonus = day == 7
        val isDay5or6 = day == 5 || day == 6

        val isClaimed =
          if (!dailyCheckInState.canCheckInToday) {
            day <= dailyCheckInState.currentStreak
          } else {
            day < dailyCheckInState.nextClaimDay
          }
        val isCurrent = dailyCheckInState.canCheckInToday && day == dailyCheckInState.nextClaimDay
        val isDayTaskLocked = isDay5or6 && !isTaskRequirementMet
        val isAvailable = isCurrent && !isDayTaskLocked

        item(key = "day_card_$day") {
          IndividualDayCard(
            day = day,
            rewardCents = rewardCents,
            isClaimed = isClaimed,
            isAvailable = isAvailable,
            isDayTaskLocked = isDayTaskLocked,
            isDay7Bonus = isDay7Bonus,
            canCheckInToday = dailyCheckInState.canCheckInToday,
            nextClaimDay = dailyCheckInState.nextClaimDay,
            isBusy = isBusy,
            onClaimClicked = { onClaimDay(day) }
          )
        }
      }

      // 5. Rules & Streak Explanation Card
      item {
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          modifier = Modifier.fillMaxWidth().testTag("daily_checkin_rules_card")
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = "Daily Check-in Rules",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
              )
            }
            Text(
              text = "• Days 1–6 reward: ₹1.00 each.\n• Day 7 bonus reward: ₹7.00 after 7 consecutive days (Total ₹13.00).\n• Days 5 & 6 require completing approved tasks worth at least ₹20.00.\n• Next claim opens every calendar day (24-hour interval).\n• Missing a day resets your streak to Day 1.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              lineHeight = 20.sp
            )
          }
        }
      }
    }
  }
}

/**
 * Individual Day Card with dedicated Day label, Reward amount, clear status badge, and working Claim button.
 */
@Composable
private fun IndividualDayCard(
  day: Int,
  rewardCents: Long,
  isClaimed: Boolean,
  isAvailable: Boolean,
  isDayTaskLocked: Boolean,
  isDay7Bonus: Boolean,
  canCheckInToday: Boolean,
  nextClaimDay: Int,
  isBusy: Boolean,
  onClaimClicked: () -> Unit
) {
  val cardBorderColor =
    when {
      isAvailable -> Color(0xFF2563EB)
      isClaimed -> Color(0xFF10B981).copy(alpha = 0.5f)
      isDay7Bonus -> Color(0xFFF59E0B).copy(alpha = 0.6f)
      else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

  val cardContainerColor =
    when {
      isAvailable -> Color(0xFFEFF6FF)
      isClaimed -> Color(0xFFF0FDF4)
      isDay7Bonus -> Color(0xFFFFFBEB)
      else -> MaterialTheme.colorScheme.surface
    }

  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = cardContainerColor),
    border = androidx.compose.foundation.BorderStroke(if (isAvailable) 2.dp else 1.dp, cardBorderColor),
    elevation = CardDefaults.cardElevation(defaultElevation = if (isAvailable) 4.dp else 1.dp),
    modifier = Modifier.fillMaxWidth().testTag("day_${day}_card")
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.weight(1f)
      ) {
        Surface(
          shape = CircleShape,
          color =
            when {
              isClaimed -> Color(0xFF10B981)
              isAvailable -> Color(0xFF2563EB)
              isDay7Bonus -> Color(0xFFF59E0B)
              else -> MaterialTheme.colorScheme.surfaceVariant
            },
          modifier = Modifier.size(44.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (isClaimed) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Claimed",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
              )
            } else if (isDay7Bonus) {
              Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = "Bonus",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
              )
            } else if (isDayTaskLocked || day > nextClaimDay) {
              Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
              )
            } else {
              Text(
                text = "$day",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
              )
            }
          }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "Day $day",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            if (isDay7Bonus) {
              Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFFFEF3C7)
              ) {
                Text(
                  text = "⭐ Bonus",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFB45309),
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
            }
          }

          Text(
            text = formatRewardInr(rewardCents),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color =
              when {
                isDay7Bonus -> Color(0xFFD97706)
                isClaimed -> Color(0xFF059669)
                isAvailable -> Color(0xFF1D4ED8)
                else -> MaterialTheme.colorScheme.onSurface
              }
          )

          // Subtitle / Lock Explanation
          val explanation =
            when {
              isClaimed -> "Reward credited to wallet"
              isAvailable -> "Ready to claim today!"
              isDayTaskLocked -> "Requires ₹20 in approved tasks"
              day == nextClaimDay && !canCheckInToday -> "Unlocks tomorrow"
              else -> "Locked (Complete previous days)"
            }
          Text(
            text = explanation,
            style = MaterialTheme.typography.labelSmall,
            color =
              if (isDayTaskLocked) {
                Color(0xFFDC2626)
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              }
          )
        }
      }

      // Individual Claim Button
      Button(
        onClick = onClaimClicked,
        enabled = isAvailable && !isBusy,
        shape = RoundedCornerShape(12.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = if (isDay7Bonus) Color(0xFFD97706) else Color(0xFF2563EB),
            disabledContainerColor =
              if (isClaimed) Color(0xFFD1FAE5) else MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor =
              if (isClaimed) Color(0xFF065F46) else MaterialTheme.colorScheme.onSurfaceVariant
          ),
        modifier =
          Modifier.padding(start = 8.dp)
            .testTag(if (day == 1) "claim_daily_checkin_button" else "claim_day_${day}_button")
      ) {
        if (isBusy && isAvailable) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = Color.White
          )
        } else {
          Text(
            text =
              when {
                isClaimed -> "Claimed ✓"
                isAvailable -> "Claim"
                isDayTaskLocked -> "Locked"
                else -> "Locked"
              },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}
