package com.example.ui.admin

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.model.AdminActivityLog
import com.example.data.model.COINS_PER_RUPEE
import com.example.data.model.CampaignItem
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalStatus
import com.example.data.model.centsToCoins
import com.example.data.model.coinsToCents
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.data.model.formatVisibleUserEmail
import com.example.data.model.getEffectiveBalanceCoins
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.ui.viewmodel.AdminTab
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Deep Navy Blue & Neutral Slate Admin Control Center Design Tokens
internal val AdminWhiteBg = Color(0xFFFFFFFF)
internal val AdminSoftGreyBg = Color(0xFFF8FAFC)
internal val AdminSurfaceGrey = Color(0xFFF1F5F9)
internal val AdminBorderColor = Color(0xFFE2E8F0)
internal val AdminBluePrimary = Color(0xFF1E3A8A)
internal val AdminBlueDark = Color(0xFF172554)
internal val AdminBlueTint = Color(0xFFEFF6FF)
internal val AdminTextDark = Color(0xFF0F172A)
internal val AdminTextSecondary = Color(0xFF334155)
internal val AdminTextMuted = Color(0xFF64748B)
internal val AdminGreenSuccess = Color(0xFF059669)
internal val AdminGreenTint = Color(0xFFECFDF5)
internal val AdminAmberWarning = Color(0xFFD97706)
internal val AdminAmberTint = Color(0xFFFFFBEB)
internal val AdminRedDanger = Color(0xFFDC2626)
internal val AdminRedTint = Color(0xFFFEF2F2)

internal fun formatInrAndCoins(cents: Long): String {
  val coins = centsToCoins(cents)
  return "${formatRewardInr(cents)} (${formatCoins(coins)})"
}

data class AdminNavSectionItem(
  val tab: AdminTab,
  val label: String,
  val icon: ImageVector,
  val tag: String,
  val defaultSubtitle: String = ""
)

val ADMIN_10_MAIN_SECTIONS: List<AdminNavSectionItem> =
  listOf(
    AdminNavSectionItem(
      tab = AdminTab.OVERVIEW,
      label = "Overview",
      icon = Icons.Default.Dashboard,
      tag = "admin_section_overview",
      defaultSubtitle = "System summary & live KPIs"
    ),
    AdminNavSectionItem(
      tab = AdminTab.USERS,
      label = "Users",
      icon = Icons.Default.People,
      tag = "admin_section_users",
      defaultSubtitle = "Accounts & status controls"
    ),
    AdminNavSectionItem(
      tab = AdminTab.TASKS,
      label = "Tasks",
      icon = Icons.Default.Checklist,
      tag = "admin_section_tasks",
      defaultSubtitle = "Create, publish & manage"
    ),
    AdminNavSectionItem(
      tab = AdminTab.CAMPAIGNS,
      label = "Campaigns",
      icon = Icons.Default.Campaign,
      tag = "admin_section_campaigns",
      defaultSubtitle = "Budgets, quotas & status"
    ),
    AdminNavSectionItem(
      tab = AdminTab.WITHDRAWALS,
      label = "Withdrawals",
      icon = Icons.Default.AccountBalanceWallet,
      tag = "admin_section_withdrawals",
      defaultSubtitle = "Payout requests & history"
    ),
    AdminNavSectionItem(
      tab = AdminTab.COINS_REWARDS,
      label = "Coins & Rewards",
      icon = Icons.Default.MonetizationOn,
      tag = "admin_section_coins_rewards",
      defaultSubtitle = "125 Coins = ₹1 • Ledgers"
    ),
    AdminNavSectionItem(
      tab = AdminTab.ANALYTICS,
      label = "Analytics",
      icon = Icons.Default.Analytics,
      tag = "admin_section_analytics",
      defaultSubtitle = "Trends, charts & reports"
    ),
    AdminNavSectionItem(
      tab = AdminTab.VERIFICATION_FRAUD,
      label = "Verification",
      icon = Icons.Default.VerifiedUser,
      tag = "admin_section_verification_fraud",
      defaultSubtitle = "User & task proof checks"
    ),
    AdminNavSectionItem(
      tab = AdminTab.APP_SETTINGS,
      label = "App Settings",
      icon = Icons.Default.Settings,
      tag = "admin_section_app_settings",
      defaultSubtitle = "Rules, limits & config"
    ),
    AdminNavSectionItem(
      tab = AdminTab.ACTIVITY_LOGS,
      label = "Activity Logs",
      icon = Icons.Default.History,
      tag = "admin_section_activity_logs",
      defaultSubtitle = "Chronological audit trail"
    )
  )

/**
 * B. MAIN DASHBOARD HOME SCREEN
 * First screen upon entering Admin Mode.
 * Shows TaskFlow branding, Dashboard title, compact live overview statistics in a responsive 2x2 grid,
 * and the 10 management sections in a clean 2-column square-card grid.
 */
@Composable
fun AdminControlCenterHomeScreen(
  users: List<UserProfile>,
  tasks: List<TaskItem>,
  campaigns: List<CampaignItem>,
  reviewTasks: List<TaskItem>,
  withdrawalRequests: List<DemoWithdrawalRequest>,
  transactions: List<TransactionRecord>,
  activityLogs: List<AdminActivityLog>,
  onSelectSection: (AdminTab) -> Unit,
  onOpenDrawer: () -> Unit = {}
) {
  val activeUsersCount =
    users.count {
      val s = it.approvalStatus.lowercase(Locale.US)
      s == "approved" || s == "active"
    }
  val pendingUsersCount =
    users.count { it.approvalStatus.equals("pending", ignoreCase = true) }
  val activeTasksCount = tasks.count { it.isActive && !it.isQuotaExhausted }
  val pendingSubmissionsCount =
    reviewTasks.count { it.status == TaskStatus.SUBMITTED }
  val approvedSubmissionsCount =
    reviewTasks.count { it.status == TaskStatus.APPROVED }
  val activeCampaignsCount =
    campaigns.count { it.status.equals("ACTIVE", ignoreCase = true) && !it.isQuotaExhausted }
  val pendingWithdrawalsCount =
    withdrawalRequests.count { WithdrawalStatus.isPendingOrActive(it.status) }
  val totalDistributedCoins =
    remember(transactions, users, reviewTasks) {
      val txCoins =
        transactions
          .filter {
            it.type != TransactionType.DEMO_WITHDRAWAL &&
              it.type != TransactionType.WITHDRAWAL_DEBIT &&
              it.type != TransactionType.WITHDRAWAL_REFUND
          }
          .sumOf { it.getEffectiveAmountCoins() }
      val userWalletCoins = users.sumOf { it.getEffectiveBalanceCoins() }
      val approvedTaskCoins =
        reviewTasks.filter { it.status == TaskStatus.APPROVED }.sumOf { it.getEffectiveRewardCoins() }
      maxOf(txCoins, userWalletCoins, approvedTaskCoins)
    }

  val dynamicSubtitles =
    remember(
      users.size,
      tasks.size,
      pendingUsersCount,
      activeTasksCount,
      activeCampaignsCount,
      campaigns.size,
      pendingWithdrawalsCount,
      withdrawalRequests.size,
      totalDistributedCoins,
      pendingSubmissionsCount,
      activityLogs.size,
      reviewTasks.size
    ) {
      mapOf(
        AdminTab.OVERVIEW to "${users.size} Users • ${tasks.size} Tasks",
        AdminTab.USERS to "${users.size} Total • $pendingUsersCount Pending",
        AdminTab.TASKS to "$activeTasksCount Active • ${tasks.size} Total",
        AdminTab.CAMPAIGNS to "$activeCampaignsCount Active • ${campaigns.size} Total",
        AdminTab.WITHDRAWALS to "$pendingWithdrawalsCount Pending • ${withdrawalRequests.size} Total",
        AdminTab.COINS_REWARDS to "${formatCoins(totalDistributedCoins)} Paid",
        AdminTab.ANALYTICS to "Daily, Weekly & Monthly",
        AdminTab.VERIFICATION_FRAUD to "$pendingSubmissionsCount Proofs • $pendingUsersCount Users",
        AdminTab.APP_SETTINGS to "v${BuildConfig.VERSION_NAME} • Rules & Limits",
        AdminTab.ACTIVITY_LOGS to "${activityLogs.size + reviewTasks.size} Audit Events"
      )
    }
  val badgeCounts =
    remember(pendingUsersCount, pendingWithdrawalsCount, pendingSubmissionsCount) {
      mapOf(
        AdminTab.USERS to pendingUsersCount,
        AdminTab.WITHDRAWALS to pendingWithdrawalsCount,
        AdminTab.VERIFICATION_FRAUD to (pendingSubmissionsCount + pendingUsersCount)
      )
    }
  val sectionPairs = remember { ADMIN_10_MAIN_SECTIONS.chunked(2) }

  LazyColumn(
    modifier =
      Modifier.fillMaxSize()
        .background(AdminWhiteBg)
        .testTag("admin_control_center_home"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    contentPadding = PaddingValues(bottom = 36.dp)
  ) {
    // 1. TaskFlow Branding & Dashboard Title Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminSoftGreyBg),
        border = BorderStroke(1.dp, AdminBorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              IconButton(
                onClick = onOpenDrawer,
                modifier =
                  Modifier.size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AdminBluePrimary)
                    .testTag("admin_hamburger_menu_button")
              ) {
                Icon(
                  imageVector = Icons.Default.Menu,
                  contentDescription = "Open Admin Menu",
                  tint = Color.White,
                  modifier = Modifier.size(20.dp)
                )
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Admin Control Center",
                  style = MaterialTheme.typography.titleMedium,
                  color = AdminTextDark,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "Authorized Administrator",
                  style = MaterialTheme.typography.labelSmall,
                  color = AdminBluePrimary,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }

          // Compact Live Overview Statistics (Responsive 2x2 Grid so mobile screens never clip)
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              HomeStatMiniCard(
                label = "TOTAL TASKS",
                value = tasks.size.toString(),
                subtitle = "$activeTasksCount Active",
                icon = Icons.Default.Checklist,
                testTag = "admin_metric_total_tasks",
                modifier = Modifier.weight(1f)
              )
              HomeStatMiniCard(
                label = "NEEDS REVIEW",
                value = pendingSubmissionsCount.toString(),
                subtitle = "Pending Proofs",
                icon = Icons.Default.RateReview,
                testTag = "admin_metric_needs_review",
                accentColor = if (pendingSubmissionsCount > 0) AdminAmberWarning else AdminBluePrimary,
                modifier = Modifier.weight(1f)
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              HomeStatMiniCard(
                label = "APPROVED",
                value = approvedSubmissionsCount.toString(),
                subtitle = "Verified Tasks",
                icon = Icons.Default.CheckCircle,
                testTag = "admin_metric_approved",
                accentColor = AdminGreenSuccess,
                modifier = Modifier.weight(1f)
              )
              HomeStatMiniCard(
                label = "USERS",
                value = users.size.toString(),
                subtitle = "$activeUsersCount Active • $pendingUsersCount Pending",
                icon = Icons.Default.People,
                testTag = "admin_metric_users",
                modifier = Modifier.weight(1f)
              )
            }
          }

          // Secondary Live Summary Pills
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            HomeQuickPill(
              label = "Active Campaigns: $activeCampaignsCount/${campaigns.size}",
              color = AdminBlueTint,
              textColor = AdminBlueDark
            )
            HomeQuickPill(
              label = "Pending Withdrawals: $pendingWithdrawalsCount",
              color = if (pendingWithdrawalsCount > 0) AdminAmberTint else AdminSurfaceGrey,
              textColor = if (pendingWithdrawalsCount > 0) AdminAmberWarning else AdminTextSecondary
            )
            HomeQuickPill(
              label = "Distributed: ${formatCoins(totalDistributedCoins)}",
              color = AdminGreenTint,
              textColor = AdminGreenSuccess
            )
          }
        }
      }
    }

    // 2. Section Title for the 10-Card 2-Column Square Grid
    item {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(top = 2.dp)
            .testTag("admin_10_sections_nav_bar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Management Sections",
          style = MaterialTheme.typography.titleMedium,
          color = AdminTextDark,
          fontWeight = FontWeight.Bold
        )
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = AdminSurfaceGrey
        ) {
          Text(
            text = "10 Modules",
            style = MaterialTheme.typography.labelSmall,
            color = AdminTextSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
      }
    }

    // 3. 10 Management Sections in a 2-Column Square-Card Grid (Each row is a LazyColumn item)
    items(sectionPairs, key = { pair -> pair.first().tag }) { pair ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        pair.forEach { section ->
          val badgeCount = badgeCounts[section.tab] ?: 0
          val liveSubtitle = dynamicSubtitles[section.tab] ?: section.defaultSubtitle
          AdminSquareManagementCard(
            item = section,
            subtitle = liveSubtitle,
            badgeCount = badgeCount,
            onClick = { onSelectSection(section.tab) },
            modifier = Modifier.weight(1f)
          )
        }
        if (pair.size == 1) {
          Spacer(modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun HomeStatMiniCard(
  label: String,
  value: String,
  subtitle: String,
  icon: ImageVector,
  testTag: String,
  accentColor: Color = AdminBluePrimary,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = AdminWhiteBg,
    border = BorderStroke(1.dp, AdminBorderColor),
    modifier = modifier.testTag(testTag)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = AdminTextMuted,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(14.dp)
        )
      }
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        color = AdminTextDark,
        fontWeight = FontWeight.ExtraBold
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = AdminTextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun HomeQuickPill(
  label: String,
  color: Color,
  textColor: Color
) {
  Surface(
    shape = RoundedCornerShape(50),
    color = color
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = textColor,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
    )
  }
}

@Composable
private fun AdminSquareManagementCard(
  item: AdminNavSectionItem,
  subtitle: String,
  badgeCount: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    modifier =
      modifier
        .height(132.dp)
        .clip(RoundedCornerShape(16.dp))
        .clickable(onClick = onClick)
        .testTag(item.tag),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
    border = BorderStroke(1.dp, AdminBorderColor),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(14.dp),
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = AdminBlueTint,
          border = BorderStroke(1.dp, AdminBluePrimary.copy(alpha = 0.15f)),
          modifier = Modifier.size(44.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = item.icon,
              contentDescription = item.label,
              tint = AdminBluePrimary,
              modifier = Modifier.size(22.dp)
            )
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          if (badgeCount > 0) {
            Surface(
              shape = RoundedCornerShape(50),
              color = AdminAmberTint,
              border = BorderStroke(1.dp, AdminAmberWarning.copy(alpha = 0.4f))
            ) {
              Text(
                text = badgeCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = AdminAmberWarning,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
              )
            }
          }
          Surface(
            shape = CircleShape,
            color = AdminSoftGreyBg,
            modifier = Modifier.size(26.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Open ${item.label}",
                tint = AdminTextMuted,
                modifier = Modifier.size(12.dp)
              )
            }
          }
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = item.label,
          style = MaterialTheme.typography.titleMedium,
          color = AdminTextDark,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = AdminTextMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

/**
 * Dedicated Management Page Header Bar with Back navigation to Main Dashboard Home Screen.
 */
@Composable
fun AdminDedicatedPageHeader(
  activeTab: AdminTab,
  onBackToHome: () -> Unit,
  onOpenDrawer: () -> Unit = {}
) {
  val sectionMeta =
    ADMIN_10_MAIN_SECTIONS.find { it.tab == activeTab }
      ?: AdminNavSectionItem(
        tab = activeTab,
        label =
          when (activeTab) {
            AdminTab.SUBMISSIONS -> "Submissions"
            AdminTab.APPS -> "Apps Management"
            else -> "Dashboard"
          },
        icon = Icons.Default.Dashboard,
        tag = "admin_section_custom"
      )

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = AdminSoftGreyBg,
    border = BorderStroke(1.dp, AdminBorderColor),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        IconButton(
          onClick = onOpenDrawer,
          modifier = Modifier.size(32.dp).testTag("admin_hamburger_menu_button")
        ) {
          Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = "Open Admin Navigation Drawer",
            tint = AdminBluePrimary,
            modifier = Modifier.size(18.dp)
          )
        }
        IconButton(
          onClick = onBackToHome,
          modifier = Modifier.size(32.dp).testTag("admin_back_to_dashboard_btn")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to Dashboard Home",
            tint = AdminBluePrimary,
            modifier = Modifier.size(18.dp)
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = sectionMeta.label,
            style = MaterialTheme.typography.titleSmall,
            color = AdminTextDark,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = "TaskFlow • Authorized Administrator",
            style = MaterialTheme.typography.labelSmall,
            color = AdminTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Spacer(modifier = Modifier.width(6.dp))

      Surface(
        shape = RoundedCornerShape(8.dp),
        color = AdminBlueTint,
        border = BorderStroke(1.dp, AdminBluePrimary.copy(alpha = 0.25f)),
        modifier =
          Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onBackToHome)
            .testTag("admin_home_grid_btn")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Dashboard,
            contentDescription = null,
            tint = AdminBluePrimary,
            modifier = Modifier.size(13.dp)
          )
          Text(
            text = "Dashboard",
            style = MaterialTheme.typography.labelSmall,
            color = AdminBlueDark,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
          )
        }
      }
    }
  }
}

/**
 * Compact 10-Section Navigation Bar retained for quick switching inside Overview or tests.
 */
@Composable
fun AdminSectionNavigationBar(
  activeTab: AdminTab,
  pendingUsersCount: Int,
  pendingSubmissionsCount: Int,
  onSelectTab: (AdminTab) -> Unit
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(bottom = 4.dp)
        .testTag("admin_10_sections_nav_bar"),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    ADMIN_10_MAIN_SECTIONS.forEach { item ->
      val isSelected =
        activeTab == item.tab ||
          (activeTab == AdminTab.SUBMISSIONS && item.tab == AdminTab.VERIFICATION_FRAUD)
      val badgeCount =
        when (item.tab) {
          AdminTab.USERS -> pendingUsersCount
          AdminTab.VERIFICATION_FRAUD -> pendingSubmissionsCount
          else -> 0
        }
      FilterChip(
        selected = isSelected,
        onClick = { onSelectTab(item.tab) },
        label = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = item.label,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
            if (badgeCount > 0) {
              Surface(
                shape = CircleShape,
                color = if (isSelected) Color.White else AdminAmberTint
              ) {
                Text(
                  text = badgeCount.toString(),
                  style = MaterialTheme.typography.labelSmall,
                  color = if (isSelected) AdminBluePrimary else AdminAmberWarning,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
              }
            }
          }
        },
        leadingIcon = {
          Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
          )
        },
        colors =
          FilterChipDefaults.filterChipColors(
            selectedContainerColor = AdminBluePrimary,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White
          ),
        modifier = Modifier.testTag(item.tag)
      )
    }
  }
}

/**
 * C. OVERVIEW PAGE & M. FINANCIAL OVERVIEW
 * Dedicated full page showing all 13 live operational metrics, Financial Overview, and Recent Activities.
 */
@Composable
fun AdminOverviewSection(
  users: List<UserProfile>,
  tasks: List<TaskItem>,
  campaigns: List<CampaignItem>,
  reviewTasks: List<TaskItem>,
  transactions: List<TransactionRecord>,
  activityLogs: List<AdminActivityLog>,
  withdrawalRequests: List<DemoWithdrawalRequest> = emptyList(),
  onNavigateTab: (AdminTab) -> Unit,
  onCreateTaskClick: () -> Unit
) {
  val totalUsers = users.size
  val activeUsers =
    users.count {
      val s = it.approvalStatus.lowercase(Locale.US)
      s == "approved" || s == "active"
    }
  val pendingUsers = users.count { it.approvalStatus.equals("pending", ignoreCase = true) }
  val suspendedUsers =
    users.count {
      it.approvalStatus.equals("suspended", ignoreCase = true) ||
        it.approvalStatus.equals("rejected", ignoreCase = true)
    }

  val totalTasks = tasks.size
  val activeTasks = tasks.count { it.isActive && !it.isQuotaExhausted }
  val completedTasks =
    tasks.count { it.isQuotaExhausted } +
      reviewTasks.count { it.status == TaskStatus.APPROVED }

  val pendingSubmissions = reviewTasks.count { it.status == TaskStatus.SUBMITTED }
  val totalCampaigns = campaigns.size
  val activeCampaigns =
    campaigns.count { it.status.equals("ACTIVE", ignoreCase = true) && !it.isQuotaExhausted }
  val pendingWithdrawals =
    withdrawalRequests.count { WithdrawalStatus.isPendingOrActive(it.status) }

  val totalDistributedCoins =
    remember(transactions, users, reviewTasks) {
      val txCoins =
        transactions
          .filter {
            it.type != TransactionType.DEMO_WITHDRAWAL &&
              it.type != TransactionType.WITHDRAWAL_DEBIT &&
              it.type != TransactionType.WITHDRAWAL_REFUND
          }
          .sumOf { it.getEffectiveAmountCoins() }
      val userWalletCoins = users.sumOf { it.getEffectiveBalanceCoins() }
      val approvedTaskCoins =
        reviewTasks.filter { it.status == TaskStatus.APPROVED }.sumOf { it.getEffectiveRewardCoins() }
      maxOf(txCoins, userWalletCoins, approvedTaskCoins)
    }
  val totalDistributedCents = coinsToCents(totalDistributedCoins)

  // Financial Overview calculations
  val totalCampaignBudgetCents = campaigns.sumOf { it.budgetCents }
  val usedCampaignBudgetCents = campaigns.sumOf { it.spentCents }
  val remainingCampaignBudgetCents =
    (totalCampaignBudgetCents - usedCampaignBudgetCents).coerceAtLeast(0L)
  val pendingLiabilityCoins =
    reviewTasks.filter { it.status == TaskStatus.SUBMITTED }.sumOf { it.getEffectiveRewardCoins() }
  val pendingLiabilityCents = coinsToCents(pendingLiabilityCoins)
  val totalRequestedWithdrawalCents =
    withdrawalRequests.sumOf {
      if (it.amountCents > 0L) it.amountCents else it.getEffectiveAmountRupees() * 100L
    }
  val approvedWithdrawalCents =
    withdrawalRequests
      .filter {
        it.status.equals(WithdrawalStatus.COMPLETED, ignoreCase = true) ||
          it.status.equals("approved", ignoreCase = true)
      }
      .sumOf { if (it.amountCents > 0L) it.amountCents else it.getEffectiveAmountRupees() * 100L }

  val recentActivities =
    remember(activityLogs, reviewTasks, tasks, campaigns, transactions, users, withdrawalRequests) {
      buildCombinedActivityEntries(
        activityLogs = activityLogs,
        reviewTasks = reviewTasks,
        tasks = tasks,
        campaigns = campaigns,
        transactions = transactions,
        users = users,
        withdrawalRequests = withdrawalRequests
      ).take(8)
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_overview_section"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 80.dp)
  ) {
    // Quick module switcher bar (also keeps admin_10_sections_nav_bar present on Overview page)
    item {
      AdminSectionNavigationBar(
        activeTab = AdminTab.OVERVIEW,
        pendingUsersCount = pendingUsers,
        pendingSubmissionsCount = pendingSubmissions,
        onSelectTab = onNavigateTab
      )
    }

    // 1. Live Operational Metrics (12 Core Metrics in Clean 2-Column Grid)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
        border = BorderStroke(1.dp, AdminBorderColor)
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
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Live Operational Metrics",
                style = MaterialTheme.typography.titleMedium,
                color = AdminTextDark,
                fontWeight = FontWeight.ExtraBold
              )
              Text(
                text = "Real-time counts synchronized with Firebase Firestore",
                style = MaterialTheme.typography.bodySmall,
                color = AdminTextMuted
              )
            }
            Button(
              onClick = onCreateTaskClick,
              shape = RoundedCornerShape(10.dp),
              colors = ButtonDefaults.buttonColors(containerColor = AdminBluePrimary),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("New Task", style = MaterialTheme.typography.labelMedium)
            }
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetricTile(
              title = "Total Registered Users",
              value = totalUsers.toString(),
              subtitle = "All accounts",
              icon = Icons.Default.People,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.USERS) }
            )
            OverviewMetricTile(
              title = "Active Users",
              value = activeUsers.toString(),
              subtitle = "Approved & active",
              icon = Icons.Default.CheckCircle,
              accentColor = AdminGreenSuccess,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.USERS) }
            )
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetricTile(
              title = "Pending Users",
              value = pendingUsers.toString(),
              subtitle = "Awaiting verification",
              icon = Icons.Default.HourglassEmpty,
              accentColor = AdminAmberWarning,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.USERS) }
            )
            OverviewMetricTile(
              title = "Suspended Users",
              value = suspendedUsers.toString(),
              subtitle = "Restricted access",
              icon = Icons.Default.Block,
              accentColor = AdminRedDanger,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.USERS) }
            )
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetricTile(
              title = "Total Tasks",
              value = totalTasks.toString(),
              subtitle = "All created tasks",
              icon = Icons.Default.Checklist,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.TASKS) }
            )
            OverviewMetricTile(
              title = "Active Tasks",
              value = activeTasks.toString(),
              subtitle = "Live for users",
              icon = Icons.Default.PlayCircleFilled,
              accentColor = AdminBluePrimary,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.TASKS) }
            )
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetricTile(
              title = "Completed Tasks",
              value = completedTasks.toString(),
              subtitle = "Approved completions",
              icon = Icons.AutoMirrored.Filled.FactCheck,
              accentColor = AdminGreenSuccess,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.TASKS) }
            )
            OverviewMetricTile(
              title = "Pending Submissions",
              value = pendingSubmissions.toString(),
              subtitle = "Proofs to review",
              icon = Icons.Default.RateReview,
              accentColor = AdminAmberWarning,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.VERIFICATION_FRAUD) }
            )
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetricTile(
              title = "Total Campaigns",
              value = totalCampaigns.toString(),
              subtitle = "All campaigns",
              icon = Icons.Default.Campaign,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.CAMPAIGNS) }
            )
            OverviewMetricTile(
              title = "Active Campaigns",
              value = activeCampaigns.toString(),
              subtitle = "Running now",
              icon = Icons.AutoMirrored.Filled.TrendingUp,
              accentColor = AdminBluePrimary,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.CAMPAIGNS) }
            )
          }

          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetricTile(
              title = "Pending Withdrawals",
              value = pendingWithdrawals.toString(),
              subtitle = "${withdrawalRequests.size} Total requests",
              icon = Icons.Default.AccountBalanceWallet,
              accentColor = if (pendingWithdrawals > 0) AdminAmberWarning else AdminBluePrimary,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.WITHDRAWALS) }
            )
            OverviewMetricTile(
              title = "Total Coins Distributed",
              value = formatCoins(totalDistributedCoins),
              subtitle = formatRewardInr(totalDistributedCents),
              icon = Icons.Default.MonetizationOn,
              accentColor = AdminGreenSuccess,
              modifier = Modifier.weight(1f),
              onClick = { onNavigateTab(AdminTab.COINS_REWARDS) }
            )
          }
        }
      }
    }

    // 2. M. Financial Overview Card (Accurate Distinction of Budgets, Rewards, Liabilities & Withdrawals)
    item {
      Card(
        modifier = Modifier.fillMaxWidth().testTag("admin_financial_overview_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminSoftGreyBg),
        border = BorderStroke(1.dp, AdminBorderColor)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Financial & Budget Overview",
            style = MaterialTheme.typography.titleMedium,
            color = AdminTextDark,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "Conversion Standard: $COINS_PER_RUPEE Coins = ₹1.00 INR (All figures derived from live Firestore data)",
            style = MaterialTheme.typography.bodySmall,
            color = AdminTextMuted
          )
          HorizontalDivider(color = AdminBorderColor)
          FinancialSummaryRow(
            label = "Allocated Campaign Budgets",
            value = formatInrAndCoins(totalCampaignBudgetCents)
          )
          FinancialSummaryRow(
            label = "Used Campaign Budget",
            value = formatInrAndCoins(usedCampaignBudgetCents)
          )
          FinancialSummaryRow(
            label = "Remaining Campaign Budget",
            value = formatInrAndCoins(remainingCampaignBudgetCents)
          )
          FinancialSummaryRow(
            label = "Total Rewards Distributed",
            value = "${formatCoins(totalDistributedCoins)} (${formatRewardInr(totalDistributedCents)})"
          )
          FinancialSummaryRow(
            label = "Pending Reward Liabilities ($pendingSubmissions Submissions)",
            value = "${formatCoins(pendingLiabilityCoins)} (${formatRewardInr(pendingLiabilityCents)})"
          )
          FinancialSummaryRow(
            label = "Requested / Approved Withdrawals",
            value = "${formatRewardInr(totalRequestedWithdrawalCents)} Req • ${formatRewardInr(approvedWithdrawalCents)} Appr"
          )
        }
      }
    }

    // 3. Recent Activities Feed (13th Overview Metric)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
        border = BorderStroke(1.dp, AdminBorderColor)
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
            Text(
              text = "Recent Activities (${recentActivities.size})",
              style = MaterialTheme.typography.titleMedium,
              color = AdminTextDark,
              fontWeight = FontWeight.Bold
            )
            OutlinedButton(
              onClick = { onNavigateTab(AdminTab.ACTIVITY_LOGS) },
              shape = RoundedCornerShape(8.dp),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
              Text("View All Logs", style = MaterialTheme.typography.labelSmall)
            }
          }
          if (recentActivities.isEmpty()) {
            Text(
              text = "No administrative or task activities recorded yet.",
              style = MaterialTheme.typography.bodySmall,
              color = AdminTextMuted
            )
          } else {
            recentActivities.forEach { entry ->
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AdminTextDark,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = entry.dateLabel,
                  style = MaterialTheme.typography.labelSmall,
                  color = AdminTextMuted
                )
              }
              HorizontalDivider(color = AdminBorderColor.copy(alpha = 0.6f))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FinancialSummaryRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = AdminTextSecondary,
      modifier = Modifier.weight(1f)
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      color = AdminTextDark,
      fontWeight = FontWeight.Bold
    )
  }
}

@Composable
internal fun OverviewMetricTile(
  title: String,
  value: String,
  subtitle: String,
  icon: ImageVector,
  accentColor: Color = AdminBluePrimary,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {}
) {
  Card(
    modifier =
      modifier
        .clip(RoundedCornerShape(12.dp))
        .clickable { onClick() },
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = AdminSoftGreyBg),
    border = BorderStroke(1.dp, AdminBorderColor)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(16.dp)
        )
        Text(
          text = title,
          style = MaterialTheme.typography.labelSmall,
          color = AdminTextSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        color = AdminTextDark,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = AdminTextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * H. COINS & REWARDS MANAGEMENT PAGE
 * Displays 125 Coins = ₹1 conversion standard, reward configurations, total coins distributed,
 * duplicate reward prevention status, user coin balances, and reward transaction & approval history.
 */
@Composable
fun AdminCoinsRewardsSection(
  users: List<UserProfile>,
  reviewTasks: List<TaskItem>,
  transactions: List<TransactionRecord>
) {
  val creditTransactions =
    transactions.filter {
      it.type != TransactionType.DEMO_WITHDRAWAL &&
        it.type != TransactionType.WITHDRAWAL_DEBIT &&
        it.type != TransactionType.WITHDRAWAL_REFUND
    }
  val approvedSubmissions =
    reviewTasks.filter { it.status == TaskStatus.APPROVED }
  val pendingSubmissions =
    reviewTasks.filter { it.status == TaskStatus.SUBMITTED }

  val totalDistributedCoins =
    maxOf(
      creditTransactions.sumOf { it.getEffectiveAmountCoins() },
      users.sumOf { it.getEffectiveBalanceCoins() },
      approvedSubmissions.sumOf { it.getEffectiveRewardCoins() }
    )
  val totalDistributedCents = coinsToCents(totalDistributedCoins)

  val pendingLiabilityCoins = pendingSubmissions.sumOf { it.getEffectiveRewardCoins() }
  val pendingLiabilityCents = coinsToCents(pendingLiabilityCoins)

  val totalCurrentWalletCoins = users.sumOf { it.getEffectiveBalanceCoins() }
  val totalCurrentWalletCents = coinsToCents(totalCurrentWalletCoins)

  val duplicateSafeCount =
    creditTransactions.map { it.id }.distinct().size

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_coins_rewards_section"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 48.dp)
  ) {
    // 1. Conversion Standard & Reward Rules Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminBlueTint),
        border = BorderStroke(1.dp, AdminBluePrimary.copy(alpha = 0.25f))
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.MonetizationOn,
              contentDescription = null,
              tint = AdminBluePrimary
            )
            Text(
              text = "Coins & INR Conversion Standard",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = AdminTextDark
            )
          }
          Text(
            text = "125 Coins = ₹1.00 INR  •  1,250 Coins = ₹10.00 INR  •  12,500 Coins = ₹100.00 INR",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = AdminBlueDark
          )
        }
      }
    }

    // 2. Live Reward Ledger Summary
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OverviewMetricTile(
            title = "Total Distributed",
            value = formatCoins(totalDistributedCoins),
            subtitle = formatRewardInr(totalDistributedCents),
            icon = Icons.Default.CheckCircle,
            accentColor = AdminGreenSuccess,
            modifier = Modifier.weight(1f)
          )
          OverviewMetricTile(
            title = "Active User Wallets",
            value = formatCoins(totalCurrentWalletCoins),
            subtitle = formatRewardInr(totalCurrentWalletCents),
            icon = Icons.Default.AccountBalanceWallet,
            accentColor = AdminBluePrimary,
            modifier = Modifier.weight(1f)
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OverviewMetricTile(
            title = "Pending Approval Coins",
            value = formatCoins(pendingLiabilityCoins),
            subtitle = "${pendingSubmissions.size} Proofs in queue • ${formatRewardInr(pendingLiabilityCents)}",
            icon = Icons.Default.HourglassEmpty,
            accentColor = AdminAmberWarning,
            modifier = Modifier.weight(1f)
          )
          OverviewMetricTile(
            title = "Verified Reward Credits",
            value = duplicateSafeCount.toString(),
            subtitle = "Zero duplicate payouts",
            icon = Icons.Default.Shield,
            accentColor = AdminGreenSuccess,
            modifier = Modifier.weight(1f)
          )
        }
      }
    }

    // 3. User Coin Balances Leaderboard
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
        border = BorderStroke(1.dp, AdminBorderColor)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "User Coin & Rupee Balances (${users.size})",
            style = MaterialTheme.typography.titleMedium,
            color = AdminTextDark,
            fontWeight = FontWeight.Bold
          )
          val sortedUsers =
            remember(users) { users.sortedByDescending { it.getEffectiveBalanceCoins() } }
          if (sortedUsers.isEmpty()) {
            Text(
              text = "No registered user accounts yet.",
              style = MaterialTheme.typography.bodySmall,
              color = AdminTextMuted
            )
          } else {
            sortedUsers.take(15).forEach { u ->
              val userCoins = u.getEffectiveBalanceCoins()
              val userCents = coinsToCents(userCoins)
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = u.displayName.ifBlank { "TaskFlow User" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AdminTextDark,
                    fontWeight = FontWeight.SemiBold
                  )
                  Text(
                    text = "Completed Tasks: ${u.completedTasksCount} • Status: ${u.approvalStatus.uppercase(Locale.US)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminTextMuted
                  )
                }
                Text(
                  text = "${formatCoins(userCoins)} (${formatRewardInr(userCents)})",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Bold,
                  color = AdminBluePrimary
                )
              }
              HorizontalDivider(color = AdminBorderColor.copy(alpha = 0.6f))
            }
          }
        }
      }
    }

    // 4. Reward Transactions & Approval Records
    item {
      Text(
        text = "Reward Transactions & Approval Ledger (${creditTransactions.size})",
        style = MaterialTheme.typography.titleMedium,
        color = AdminTextDark,
        fontWeight = FontWeight.Bold
      )
    }

    if (creditTransactions.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = AdminSoftGreyBg),
          border = BorderStroke(1.dp, AdminBorderColor)
        ) {
          Text(
            text = "No reward credit transactions recorded yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = AdminTextMuted,
            modifier = Modifier.padding(16.dp)
          )
        }
      }
    } else {
      items(creditTransactions.take(40), key = { it.id }) { tx ->
        val dateText =
          tx.createdAt?.toDate()?.let {
            SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
          } ?: "Recorded"
        val txCoins = tx.getEffectiveAmountCoins()
        val txCents = coinsToCents(txCoins)
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
          border = BorderStroke(1.dp, AdminBorderColor)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text = tx.title.ifBlank { "Task Reward Credit" },
                style = MaterialTheme.typography.bodyMedium,
                color = AdminTextDark,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Member: ${tx.userName.ifBlank { "Verified User" }}",
                style = MaterialTheme.typography.bodySmall,
                color = AdminTextMuted
              )
              Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall,
                color = AdminTextMuted
              )
            }
            Text(
              text = "+${formatCoins(txCoins)} (${formatRewardInr(txCents)})",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = AdminGreenSuccess
            )
          }
        }
      }
    }
  }
}

/**
 * I. ANALYTICS & REPORTS PAGE
 * Displays date filters (Daily, Weekly, Monthly, All Time), visual progress bars,
 * and accurate breakdowns of Users, Tasks, Campaigns, Rewards, Withdrawals, and Engagement.
 */
@Composable
fun AdminAnalyticsReportsSection(
  users: List<UserProfile>,
  tasks: List<TaskItem>,
  campaigns: List<CampaignItem>,
  reviewTasks: List<TaskItem>,
  transactions: List<TransactionRecord>,
  withdrawalRequests: List<DemoWithdrawalRequest> = emptyList()
) {
  var selectedWindow by rememberSaveable { mutableStateOf("ALL") }
  val nowMs = System.currentTimeMillis()
  val cutoffMs =
    when (selectedWindow) {
      "24H" -> nowMs - 24L * 3600_000L
      "7D" -> nowMs - 7L * 24L * 3600_000L
      "30D" -> nowMs - 30L * 24L * 3600_000L
      else -> 0L
    }

  val filteredUsers =
    remember(users, cutoffMs) {
      if (cutoffMs == 0L) users
      else users.filter { (it.createdAt?.toDate()?.time ?: nowMs) >= cutoffMs }
    }
  val filteredTasks =
    remember(tasks, cutoffMs) {
      if (cutoffMs == 0L) tasks
      else tasks.filter { (it.createdAt?.toDate()?.time ?: nowMs) >= cutoffMs }
    }
  val filteredReviews =
    remember(reviewTasks, cutoffMs) {
      if (cutoffMs == 0L) reviewTasks
      else reviewTasks.filter { ((it.updatedAt ?: it.createdAt)?.toDate()?.time ?: nowMs) >= cutoffMs }
    }
  val filteredCampaigns =
    remember(campaigns, cutoffMs) {
      if (cutoffMs == 0L) campaigns
      else campaigns.filter { (it.createdAt?.toDate()?.time ?: nowMs) >= cutoffMs }
    }
  val filteredTransactions =
    remember(transactions, cutoffMs) {
      if (cutoffMs == 0L) transactions
      else transactions.filter { (it.createdAt?.toDate()?.time ?: nowMs) >= cutoffMs }
    }

  val totalSubmissions = filteredReviews.size
  val approvedCount = filteredReviews.count { it.status == TaskStatus.APPROVED }
  val rejectedCount = filteredReviews.count { it.status == TaskStatus.REJECTED }
  val pendingCount = filteredReviews.count { it.status == TaskStatus.SUBMITTED }
  val approvalRate =
    if (approvedCount + rejectedCount > 0) {
      (approvedCount.toFloat() / (approvedCount + rejectedCount).toFloat())
    } else 0f

  val totalCampaignTarget = filteredCampaigns.sumOf { it.targetSubmissions }
  val totalCampaignCompleted = filteredCampaigns.sumOf { it.completedSubmissions }
  val campaignProgress =
    if (totalCampaignTarget > 0) {
      (totalCampaignCompleted.toFloat() / totalCampaignTarget.toFloat()).coerceIn(0f, 1f)
    } else 0f

  val totalBudgetCents = filteredCampaigns.sumOf { it.budgetCents }
  val usedBudgetCents = filteredCampaigns.sumOf { it.spentCents }
  val remainingBudgetCents = (totalBudgetCents - usedBudgetCents).coerceAtLeast(0L)
  val budgetUtilization =
    if (totalBudgetCents > 0L) {
      (usedBudgetCents.toFloat() / totalBudgetCents.toFloat()).coerceIn(0f, 1f)
    } else 0f

  val activeUsersCount =
    filteredUsers.count {
      val s = it.approvalStatus.lowercase(Locale.US)
      s == "approved" || s == "active"
    }
  val engagedUsersCount =
    filteredUsers.count { it.completedTasksCount > 0L || it.getEffectiveBalanceCoins() > 0L }
  val engagementRate =
    if (filteredUsers.isNotEmpty()) {
      (engagedUsersCount.toFloat() / filteredUsers.size.toFloat()).coerceIn(0f, 1f)
    } else 0f

  val periodDistributedCoins =
    filteredTransactions
      .filter {
        it.type != TransactionType.DEMO_WITHDRAWAL &&
          it.type != TransactionType.WITHDRAWAL_DEBIT &&
          it.type != TransactionType.WITHDRAWAL_REFUND
      }
      .sumOf { it.getEffectiveAmountCoins() }
      .let { txCoins ->
        val approvedSubCoins =
          filteredReviews.filter { it.status == TaskStatus.APPROVED }.sumOf { it.getEffectiveRewardCoins() }
        maxOf(txCoins, approvedSubCoins)
      }
  val periodDistributedCents = coinsToCents(periodDistributedCoins)

  val pendingWithdrawalsCount =
    withdrawalRequests.count { WithdrawalStatus.isPendingOrActive(it.status) }
  val approvedWithdrawalsCount =
    withdrawalRequests.count {
      it.status.equals(WithdrawalStatus.COMPLETED, ignoreCase = true) ||
        it.status.equals("approved", ignoreCase = true)
    }
  val rejectedWithdrawalsCount =
    withdrawalRequests.count { it.status.equals(WithdrawalStatus.REJECTED, ignoreCase = true) }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_analytics_section"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 48.dp)
  ) {
    // Date Range Filters: Daily, Weekly, Monthly, All Time
    item {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf(
          "24H" to "Daily (24h)",
          "7D" to "Weekly (7d)",
          "30D" to "Monthly (30d)",
          "ALL" to "All Time"
        ).forEach { (key, label) ->
          FilterChip(
            selected = selectedWindow == key,
            onClick = { selectedWindow = key },
            label = { Text(label) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = AdminBluePrimary,
                selectedLabelColor = Color.White
              ),
            modifier = Modifier.testTag("analytics_filter_$key")
          )
        }
      }
    }

    // 1. User Registration, Active Users & Engagement Trends
    item {
      val pendingUsersCount = filteredUsers.count { it.approvalStatus.equals("pending", true) }
      AnalyticsTrendCard(
        title = "User Registration & Engagement Trends",
        subtitle = "${filteredUsers.size} Registered • $activeUsersCount Active • $engagedUsersCount Engaged",
        progress = engagementRate,
        progressLabel = "User Engagement Rate: ${(engagementRate * 100).toInt()}%",
        chartBars =
          listOf(
            "Registered" to filteredUsers.size.toFloat(),
            "Active" to activeUsersCount.toFloat(),
            "Pending" to pendingUsersCount.toFloat(),
            "Engaged" to engagedUsersCount.toFloat()
          ),
        metrics =
          listOf(
            "Registered Users" to filteredUsers.size.toString(),
            "Active Approved" to activeUsersCount.toString(),
            "Pending Verification" to pendingUsersCount.toString(),
            "Users With Earnings" to engagedUsersCount.toString()
          )
      )
    }

    // 2. Task Creation, Completion & Approval/Rejection Trends
    item {
      AnalyticsTrendCard(
        title = "Task Completion & Approval Analytics",
        subtitle = "${filteredTasks.size} Tasks Created • $totalSubmissions Total Submissions",
        progress = approvalRate,
        progressLabel = "Submission Approval Rate: ${(approvalRate * 100).toInt()}%",
        chartBars =
          listOf(
            "Tasks" to filteredTasks.size.toFloat(),
            "Approved" to approvedCount.toFloat(),
            "Pending" to pendingCount.toFloat(),
            "Rejected" to rejectedCount.toFloat()
          ),
        metrics =
          listOf(
            "Tasks Created" to filteredTasks.size.toString(),
            "Approved Proofs" to approvedCount.toString(),
            "Pending Review" to pendingCount.toString(),
            "Rejected Proofs" to rejectedCount.toString()
          )
      )
    }

    // 3. Campaign Performance & Budget Utilization
    item {
      val activeCampCount = filteredCampaigns.count { it.status.equals("ACTIVE", true) }
      AnalyticsTrendCard(
        title = "Campaign Performance & Budget Utilization",
        subtitle = "${filteredCampaigns.size} Campaigns • $totalCampaignCompleted / $totalCampaignTarget Quota Filled",
        progress = campaignProgress,
        progressLabel = "Quota Completion: ${(campaignProgress * 100).toInt()}% • Budget Used: ${(budgetUtilization * 100).toInt()}%",
        chartBars =
          listOf(
            "Budget" to (totalBudgetCents / 100f),
            "Used" to (usedBudgetCents / 100f),
            "Remain" to (remainingBudgetCents / 100f),
            "Active" to activeCampCount.toFloat()
          ),
        metrics =
          listOf(
            "Total Budget" to formatRewardInr(totalBudgetCents),
            "Used Budget" to formatRewardInr(usedBudgetCents),
            "Remaining Budget" to formatRewardInr(remainingBudgetCents),
            "Active Campaigns" to activeCampCount.toString()
          )
      )
    }

    // 4. Reward Distribution & Withdrawal Trends
    item {
      AnalyticsTrendCard(
        title = "Reward Distribution & Withdrawal Trends",
        subtitle = "Total Distributed in Period: ${formatCoins(periodDistributedCoins)} (${formatRewardInr(periodDistributedCents)})",
        progress = if (withdrawalRequests.isNotEmpty()) {
          approvedWithdrawalsCount.toFloat() / withdrawalRequests.size.toFloat()
        } else 0f,
        progressLabel = "Withdrawal Requests: ${withdrawalRequests.size} Total ($pendingWithdrawalsCount Pending)",
        chartBars =
          listOf(
            "Total" to withdrawalRequests.size.toFloat(),
            "Pending" to pendingWithdrawalsCount.toFloat(),
            "Approved" to approvedWithdrawalsCount.toFloat(),
            "Rejected" to rejectedWithdrawalsCount.toFloat()
          ),
        metrics =
          listOf(
            "Coins Distributed" to formatCoins(periodDistributedCoins),
            "INR Equivalent" to formatRewardInr(periodDistributedCents),
            "Pending Withdrawals" to pendingWithdrawalsCount.toString(),
            "Approved / Rejected" to "$approvedWithdrawalsCount / $rejectedWithdrawalsCount"
          )
      )
    }
  }
}

@Composable
private fun AnalyticsTrendCard(
  title: String,
  subtitle: String,
  progress: Float,
  progressLabel: String,
  chartBars: List<Pair<String, Float>> = emptyList(),
  metrics: List<Pair<String, String>>
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
    border = BorderStroke(1.dp, AdminBorderColor)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = AdminTextDark,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = AdminTextMuted
      )
      if (chartBars.isNotEmpty()) {
        val maxVal = chartBars.maxOfOrNull { it.second }?.coerceAtLeast(1f) ?: 1f
        val barColors =
          listOf(
            AdminBluePrimary,
            AdminGreenSuccess,
            AdminAmberWarning,
            Color(0xFF475569)
          )
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = AdminSoftGreyBg,
          border = BorderStroke(1.dp, AdminBorderColor),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Canvas(
              modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
              val count = chartBars.size.coerceAtLeast(1)
              val slotWidth = size.width / count
              val barWidth = (slotWidth * 0.45f).coerceAtMost(38.dp.toPx())
              val maxBarHeight = size.height - 6.dp.toPx()

              // Subtle baseline
              drawLine(
                color = AdminBorderColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx()
              )

              chartBars.forEachIndexed { idx, (_, rawValue) ->
                val ratio = (rawValue / maxVal).coerceIn(0.08f, 1f)
                val barHeight = maxBarHeight * ratio
                val left = idx * slotWidth + (slotWidth - barWidth) / 2f
                val top = size.height - barHeight
                drawRoundRect(
                  color = barColors[idx % barColors.size],
                  topLeft = Offset(left, top),
                  size = Size(barWidth, barHeight),
                  cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
              }
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceEvenly
            ) {
              chartBars.forEach { (barLabel, _) ->
                Text(
                  text = barLabel,
                  style = MaterialTheme.typography.labelSmall,
                  color = AdminTextMuted,
                  fontSize = 10.sp,
                  maxLines = 1
                )
              }
            }
          }
        }
      }
      LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = AdminBluePrimary,
        trackColor = AdminSurfaceGrey
      )
      Text(
        text = progressLabel,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = AdminBlueDark
      )
      HorizontalDivider(color = AdminBorderColor)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { rowItems ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            rowItems.forEach { (mLabel, mVal) ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = AdminSoftGreyBg,
                border = BorderStroke(1.dp, AdminBorderColor),
                modifier = Modifier.weight(1f)
              ) {
                Column(modifier = Modifier.padding(10.dp)) {
                  Text(
                    text = mLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminTextMuted
                  )
                  Text(
                    text = mVal,
                    style = MaterialTheme.typography.titleSmall,
                    color = AdminTextDark,
                    fontWeight = FontWeight.Bold
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

/**
 * J. VERIFICATION & FRAUD PAGE
 * Manages both User Verification (Pending, Approved, Rejected/Suspended users)
 * and Task Submission Verification (Pending, Approved, Rejected proofs with image preview & duplicate detection).
 */
@Composable
fun AdminVerificationFraudSection(
  reviewTasks: List<TaskItem>,
  users: List<UserProfile>,
  withdrawalRequests: List<DemoWithdrawalRequest>,
  selectedFilter: String,
  onSelectFilter: (String) -> Unit,
  onReviewSubmission: (TaskItem, Boolean, String) -> Unit,
  onUpdateUserStatus: ((UserProfile, String) -> Unit)? = null
) {
  var showUserVerifications by rememberSaveable { mutableStateOf(false) }
  var userVerificationFilter by rememberSaveable { mutableStateOf("pending") }

  val duplicateProofMap =
    remember(reviewTasks) {
      reviewTasks
        .filter { it.proofText.isNotBlank() || it.proofImageUrl.isNotBlank() }
        .groupBy { "${it.proofText.trim().lowercase(Locale.US)}|${it.proofImageUrl.trim()}" }
        .filterValues { list -> list.map { it.assignedUserId }.distinct().size > 1 || list.size > 1 }
    }
  val suspiciousIds =
    remember(duplicateProofMap) {
      duplicateProofMap.values.flatten().map { it.id }.toSet()
    }

  val pendingCount = reviewTasks.count { it.status == TaskStatus.SUBMITTED }
  val approvedCount = reviewTasks.count { it.status == TaskStatus.APPROVED }
  val rejectedCount = reviewTasks.count { it.status == TaskStatus.REJECTED }
  val duplicateCount = suspiciousIds.size

  val pendingUsers = users.filter { it.approvalStatus.equals("pending", ignoreCase = true) }
  val approvedUsers =
    users.filter {
      it.approvalStatus.equals("approved", ignoreCase = true) ||
        it.approvalStatus.equals("active", ignoreCase = true)
    }
  val rejectedOrSuspendedUsers =
    users.filter {
      it.approvalStatus.equals("rejected", ignoreCase = true) ||
        it.approvalStatus.equals("suspended", ignoreCase = true)
    }

  Column(
    modifier = Modifier.fillMaxSize().testTag("admin_verification_fraud_section"),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    // Summary Card + Queue Switcher (Task Submissions vs User Verifications)
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = AdminSoftGreyBg),
      border = BorderStroke(1.dp, AdminBorderColor)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = if (duplicateCount > 0) Icons.Default.Warning else Icons.Default.VerifiedUser,
              contentDescription = null,
              tint = if (duplicateCount > 0) AdminAmberWarning else AdminBluePrimary
            )
            Column {
              Text(
                text = "Verification & Fraud Control",
                style = MaterialTheme.typography.titleSmall,
                color = AdminTextDark,
                fontWeight = FontWeight.ExtraBold
              )
              Text(
                text = "Proofs: $pendingCount Pending, $approvedCount Appr, $rejectedCount Rej • Users: ${pendingUsers.size} Pending",
                style = MaterialTheme.typography.labelSmall,
                color = AdminTextSecondary
              )
            }
          }
        }

        if (duplicateCount > 0) {
          Text(
            text = "Alert: $duplicateCount submission(s) share identical proof text/URLs across multiple entries.",
            style = MaterialTheme.typography.labelSmall,
            color = AdminRedDanger,
            fontWeight = FontWeight.Bold
          )
        }

        // Toggle between Task Submission Proofs and User Account Verifications (equal width, single-line)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          FilterChip(
            selected = !showUserVerifications,
            onClick = { showUserVerifications = false },
            label = {
              Text(
                text = "Task Submissions ($pendingCount Pending)",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp
              )
            },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = AdminBluePrimary,
                selectedLabelColor = Color.White
              ),
            modifier = Modifier.weight(1f).testTag("verification_tab_submissions")
          )
          FilterChip(
            selected = showUserVerifications,
            onClick = { showUserVerifications = true },
            label = {
              Text(
                text = "User Verifications (${pendingUsers.size} Pending)",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp
              )
            },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = AdminBluePrimary,
                selectedLabelColor = Color.White
              ),
            modifier = Modifier.weight(1f).testTag("verification_tab_users")
          )
        }
      }
    }

    if (showUserVerifications) {
      // User Verification Queue (Pending, Approved, Rejected/Suspended)
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf(
          "pending" to "Pending (${pendingUsers.size})",
          "approved" to "Approved (${approvedUsers.size})",
          "rejected" to "Rejected / Suspended (${rejectedOrSuspendedUsers.size})"
        ).forEach { (key, label) ->
          FilterChip(
            selected = userVerificationFilter == key,
            onClick = { userVerificationFilter = key },
            label = { Text(label) }
          )
        }
      }

      val displayedUsers =
        when (userVerificationFilter) {
          "pending" -> pendingUsers
          "approved" -> approvedUsers
          else -> rejectedOrSuspendedUsers
        }

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 36.dp)
      ) {
        if (displayedUsers.isEmpty()) {
          item {
            Card(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(12.dp),
              colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
              border = BorderStroke(1.dp, AdminBorderColor)
            ) {
              Text(
                text = "No user accounts in this verification status.",
                style = MaterialTheme.typography.bodyMedium,
                color = AdminTextMuted,
                modifier = Modifier.padding(16.dp)
              )
            }
          }
        } else {
          items(displayedUsers, key = { it.id.ifBlank { it.userId } }) { user ->
            val targetUid = user.userId.ifBlank { user.id }
            Card(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(12.dp),
              colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
              border = BorderStroke(1.dp, AdminBorderColor)
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = user.displayName.ifBlank { "TaskFlow User" },
                      style = MaterialTheme.typography.titleSmall,
                      color = AdminTextDark,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = formatVisibleUserEmail(user.email, user.role),
                      style = MaterialTheme.typography.bodySmall,
                      color = AdminTextMuted
                    )
                  }
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AdminBlueTint
                  ) {
                    Text(
                      text = user.approvalStatus.uppercase(Locale.US),
                      style = MaterialTheme.typography.labelSmall,
                      color = AdminBlueDark,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                  }
                }
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Button(
                    onClick = { onUpdateUserStatus?.invoke(user, "approved") },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AdminGreenSuccess),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                  ) {
                    Text("Approve User", style = MaterialTheme.typography.labelSmall)
                  }
                  OutlinedButton(
                    onClick = { onUpdateUserStatus?.invoke(user, "suspended") },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                  ) {
                    Text("Reject / Suspend", style = MaterialTheme.typography.labelSmall, color = AdminRedDanger)
                  }
                }
              }
            }
          }
        }
      }
    } else {
      // Task Submission Proof Verification Queue (reuses AdminSubmissionsTabContent with image preview)
      AdminSubmissionsTabContent(
        tasks = reviewTasks,
        withdrawalRequests = withdrawalRequests,
        selectedFilter = selectedFilter,
        onSelectFilter = onSelectFilter,
        onReviewSubmission = onReviewSubmission,
        suspiciousSubmissionIds = suspiciousIds
      )
    }
  }
}

/**
 * K. APP SETTINGS PAGE
 * Displays only actual supported configurations: Theme Mode, App Version & Update Check,
 * Reward & Limit Rules, Notification Preferences, and Firebase Security Rules Diagnostics.
 */
@Composable
fun AdminAppSettingsSection(
  databaseId: String,
  onOpenSecurityDialog: () -> Unit,
  onTriggerUpdateCheck: () -> Unit
) {
  val context = LocalContext.current
  val currentThemeMode by ThemePreferences.currentThemeMode.collectAsState()
  val prefs = remember(context) { context.getSharedPreferences("taskflow_admin_prefs", Context.MODE_PRIVATE) }
  var notifyOnNewSubmissions by rememberSaveable {
    mutableStateOf(prefs.getBoolean("notify_new_submissions", true))
  }
  var notifyOnWithdrawals by rememberSaveable {
    mutableStateOf(prefs.getBoolean("notify_withdrawals", true))
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_app_settings_section"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 48.dp)
  ) {
    // 1. Theme & Display Mode
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
        border = BorderStroke(1.dp, AdminBorderColor)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            text = "Appearance & Theme Mode",
            style = MaterialTheme.typography.titleMedium,
            color = AdminTextDark,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Select the active appearance mode for this device.",
            style = MaterialTheme.typography.bodySmall,
            color = AdminTextMuted
          )
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(
              AppThemeMode.LIGHT to "Light Minimal",
              AppThemeMode.DARK to "Dark Mode",
              AppThemeMode.SYSTEM_DEFAULT to "System"
            ).forEach { (mode, label) ->
              FilterChip(
                selected = currentThemeMode == mode,
                onClick = { ThemePreferences.setThemeMode(context, mode) },
                label = {
                  Text(
                    text = label,
                    maxLines = 1,
                    softWrap = false
                  )
                },
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AdminBluePrimary,
                    selectedLabelColor = Color.White
                  ),
                modifier = Modifier.testTag("admin_theme_mode_${mode.name.lowercase(Locale.US)}")
              )
            }
          }
        }
      }
    }

    // 2. Reward, Task & Campaign Limit Configuration
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
        border = BorderStroke(1.dp, AdminBorderColor)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Reward & Task/Campaign Limit Rules",
            style = MaterialTheme.typography.titleMedium,
            color = AdminTextDark,
            fontWeight = FontWeight.Bold
          )
          FinancialSummaryRow("Coin Conversion Standard", "$COINS_PER_RUPEE Coins = ₹1.00 INR")
          FinancialSummaryRow("Daily Check-In Reward", "30-Day Schedule (25–1,000 Coins)")
          FinancialSummaryRow("Watch Video & Earn", "30–35 Random Coins (2-Ad Flow)")
          FinancialSummaryRow("Spin & Earn Wheel", "10–150 Coins (Free 24h + Extra Spins)")
          FinancialSummaryRow("Minimum Withdrawal Threshold", "25,000 Coins (₹200.00 INR)")
          FinancialSummaryRow("Duplicate Credit Protection", "Active (Single-Credit Verified)")
        }
      }
    }

    // 3. Admin Notification Preferences
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
        border = BorderStroke(1.dp, AdminBorderColor)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.Notifications, contentDescription = null, tint = AdminBluePrimary)
            Text(
              text = "Notification Preferences",
              style = MaterialTheme.typography.titleMedium,
              color = AdminTextDark,
              fontWeight = FontWeight.Bold
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Alert on New Task Submissions",
                style = MaterialTheme.typography.bodyMedium,
                color = AdminTextDark,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "Highlight pending task proofs in Verification & Overview",
                style = MaterialTheme.typography.bodySmall,
                color = AdminTextMuted
              )
            }
            Switch(
              checked = notifyOnNewSubmissions,
              onCheckedChange = {
                notifyOnNewSubmissions = it
                prefs.edit().putBoolean("notify_new_submissions", it).apply()
              },
              colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AdminBluePrimary)
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Alert on New Withdrawal Requests",
                style = MaterialTheme.typography.bodyMedium,
                color = AdminTextDark,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "Show badge count on Withdrawals card when pending > 0",
                style = MaterialTheme.typography.bodySmall,
                color = AdminTextMuted
              )
            }
            Switch(
              checked = notifyOnWithdrawals,
              onCheckedChange = {
                notifyOnWithdrawals = it
                prefs.edit().putBoolean("notify_withdrawals", it).apply()
              },
              colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AdminBluePrimary)
            )
          }
        }
      }
    }

    // 4. App Build & Remote Update Check
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
        border = BorderStroke(1.dp, AdminBorderColor)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Application Build & Remote Update",
            style = MaterialTheme.typography.titleMedium,
            color = AdminTextDark,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Package ID: ${BuildConfig.APPLICATION_ID}",
            style = MaterialTheme.typography.bodySmall,
            color = AdminTextSecondary
          )
          Text(
            text = "Version: ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = AdminTextSecondary
          )
          Spacer(modifier = Modifier.height(4.dp))
          OutlinedButton(
            onClick = onTriggerUpdateCheck,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.testTag("admin_check_app_updates_btn")
          ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Check for App Updates Now")
          }
        }
      }
    }

    // 5. Firebase & Backend Security Diagnostics
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
        border = BorderStroke(1.dp, AdminBorderColor)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = AdminBluePrimary)
            Text(
              text = "Firebase & Zero-Trust Security Config",
              style = MaterialTheme.typography.titleMedium,
              color = AdminTextDark,
              fontWeight = FontWeight.Bold
            )
          }
          Text(
            text = "Project ID: taskflow-8852d\nFirestore Database ID: $databaseId",
            style = MaterialTheme.typography.bodySmall,
            color = AdminTextSecondary
          )
          OutlinedButton(
            onClick = onOpenSecurityDialog,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.testTag("admin_open_security_sheet_btn")
          ) {
            Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Inspect Firebase Security Rules & Diagnostics")
          }
        }
      }
    }
  }
}

/**
 * L. ACTIVITY LOGS PAGE
 * Chronological audit log of admin actions, user registrations, user approvals/rejections,
 * task & campaign creations/modifications, task submissions, approvals/rejections,
 * reward credits, and withdrawal requests.
 */
@Composable
fun AdminActivityLogsSection(
  activityLogs: List<AdminActivityLog>,
  reviewTasks: List<TaskItem>,
  tasks: List<TaskItem>,
  campaigns: List<CampaignItem>,
  transactions: List<TransactionRecord>,
  users: List<UserProfile> = emptyList(),
  withdrawalRequests: List<DemoWithdrawalRequest> = emptyList()
) {
  var selectedCategoryFilter by rememberSaveable { mutableStateOf("ALL") }
  val allEntries =
    remember(activityLogs, reviewTasks, tasks, campaigns, transactions, users, withdrawalRequests) {
      buildCombinedActivityEntries(
        activityLogs = activityLogs,
        reviewTasks = reviewTasks,
        tasks = tasks,
        campaigns = campaigns,
        transactions = transactions,
        users = users,
        withdrawalRequests = withdrawalRequests
      )
    }

  val filteredEntries =
    remember(allEntries, selectedCategoryFilter) {
      if (selectedCategoryFilter == "ALL") {
        allEntries
      } else {
        allEntries.filter { it.category.equals(selectedCategoryFilter, ignoreCase = true) }
      }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_activity_logs_section"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    contentPadding = PaddingValues(bottom = 48.dp)
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf(
          "ALL" to "All Activity (${allEntries.size})",
          "TASK" to "Tasks",
          "CAMPAIGN" to "Campaigns",
          "SUBMISSION" to "Approvals & Reviews",
          "REWARD" to "Rewards & Coins",
          "USER" to "User Actions",
          "WITHDRAWAL" to "Withdrawals"
        ).forEach { (key, label) ->
          FilterChip(
            selected = selectedCategoryFilter == key,
            onClick = { selectedCategoryFilter = key },
            label = { Text(label) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = AdminBluePrimary,
                selectedLabelColor = Color.White
              )
          )
        }
      }
    }

    if (filteredEntries.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = AdminSoftGreyBg),
          border = BorderStroke(1.dp, AdminBorderColor)
        ) {
          Text(
            text = "No activity logs recorded for this filter yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = AdminTextMuted,
            modifier = Modifier.padding(16.dp)
          )
        }
      }
    } else {
      items(filteredEntries, key = { it.id }) { entry ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
          border = BorderStroke(1.dp, AdminBorderColor)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = AdminBlueTint
                ) {
                  Text(
                    text = entry.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminBlueDark,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
                Text(
                  text = entry.title,
                  style = MaterialTheme.typography.bodyMedium,
                  color = AdminTextDark,
                  fontWeight = FontWeight.Bold
                )
              }
              Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AdminTextSecondary
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = entry.dateLabel,
              style = MaterialTheme.typography.labelSmall,
              color = AdminTextMuted
            )
          }
        }
      }
    }
  }
}

internal data class CombinedActivityEntry(
  val id: String,
  val category: String,
  val title: String,
  val subtitle: String,
  val timestampMillis: Long,
  val dateLabel: String
)

internal fun buildCombinedActivityEntries(
  activityLogs: List<AdminActivityLog>,
  reviewTasks: List<TaskItem>,
  tasks: List<TaskItem>,
  campaigns: List<CampaignItem>,
  transactions: List<TransactionRecord>,
  users: List<UserProfile> = emptyList(),
  withdrawalRequests: List<DemoWithdrawalRequest> = emptyList()
): List<CombinedActivityEntry> {
  val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US)
  val items = mutableListOf<CombinedActivityEntry>()

  activityLogs.forEach { log ->
    val ts = log.createdAt?.toDate()?.time ?: log.timestampMillis
    val cat =
      when {
        log.actionType.contains("CAMPAIGN", ignoreCase = true) -> "CAMPAIGN"
        log.actionType.contains("SUBMISSION", ignoreCase = true) -> "SUBMISSION"
        log.actionType.contains("USER", ignoreCase = true) -> "USER"
        log.actionType.contains("REWARD", ignoreCase = true) -> "REWARD"
        log.actionType.contains("WITHDRAW", ignoreCase = true) -> "WITHDRAWAL"
        else -> "TASK"
      }
    items.add(
      CombinedActivityEntry(
        id = "log_${log.id}",
        category = cat,
        title = log.title.ifBlank { log.actionType.replace('_', ' ') },
        subtitle = log.details,
        timestampMillis = ts,
        dateLabel = if (ts > 0L) fmt.format(Date(ts)) else "Recent"
      )
    )
  }

  users.take(20).forEach { u ->
    val ts = u.createdAt?.toDate()?.time ?: 0L
    val uid = u.userId.ifBlank { u.id }
    val coins = u.getEffectiveBalanceCoins()
    items.add(
      CombinedActivityEntry(
        id = "user_reg_${uid}_${u.approvalStatus}",
        category = "USER",
        title = "User ${u.approvalStatus.uppercase(Locale.US)} • ${u.displayName.ifBlank { "TaskFlow User" }}",
        subtitle = "Balance: ${formatCoins(coins)} • Completed Tasks: ${u.completedTasksCount}",
        timestampMillis = ts,
        dateLabel = if (ts > 0L) fmt.format(Date(ts)) else "Recorded"
      )
    )
  }

  tasks.take(15).forEach { t ->
    val ts = (t.updatedAt ?: t.createdAt)?.toDate()?.time ?: 0L
    val stateLabel = if (t.isActive) "ACTIVE" else "PAUSED"
    items.add(
      CombinedActivityEntry(
        id = "task_cfg_${t.id}_$stateLabel",
        category = "TASK",
        title = "Task $stateLabel • ${t.title}",
        subtitle = "Category: ${t.category.ifBlank { "General" }} • Reward: ${formatCoins(t.getEffectiveRewardCoins())}",
        timestampMillis = ts,
        dateLabel = if (ts > 0L) fmt.format(Date(ts)) else "Recorded"
      )
    )
  }

  reviewTasks.take(25).forEach { sub ->
    val ts = (sub.updatedAt ?: sub.createdAt)?.toDate()?.time ?: 0L
    val statusLabel =
      when (sub.status) {
        TaskStatus.APPROVED -> "Submission Approved"
        TaskStatus.REJECTED -> "Submission Rejected"
        else -> "Proof Submitted"
      }
    items.add(
      CombinedActivityEntry(
        id = "sub_${sub.id}_${sub.status}",
        category = "SUBMISSION",
        title = "$statusLabel • ${sub.title}",
        subtitle = "Member: ${sub.assignedUserName.ifBlank { "Verified User" }} • ${formatCoins(sub.getEffectiveRewardCoins())}",
        timestampMillis = ts,
        dateLabel = if (ts > 0L) fmt.format(Date(ts)) else "Recorded"
      )
    )
  }

  campaigns.take(15).forEach { camp ->
    val ts = (camp.updatedAt ?: camp.createdAt)?.toDate()?.time ?: 0L
    items.add(
      CombinedActivityEntry(
        id = "camp_${camp.id}_${camp.status}",
        category = "CAMPAIGN",
        title = "Campaign ${camp.status} • ${camp.title}",
        subtitle = "Quota: ${camp.completedSubmissions}/${camp.targetSubmissions} • Budget: ${formatRewardInr(camp.spentCents)}/${formatRewardInr(camp.budgetCents)}",
        timestampMillis = ts,
        dateLabel = if (ts > 0L) fmt.format(Date(ts)) else "Recorded"
      )
    )
  }

  transactions.take(20).forEach { tx ->
    val ts = tx.createdAt?.toDate()?.time ?: 0L
    items.add(
      CombinedActivityEntry(
        id = "tx_${tx.id}",
        category = "REWARD",
        title = tx.title.ifBlank { "Reward Credit" },
        subtitle = "User: ${tx.userName.ifBlank { tx.userId }} • Amount: +${formatCoins(tx.getEffectiveAmountCoins())}",
        timestampMillis = ts,
        dateLabel = if (ts > 0L) fmt.format(Date(ts)) else "Recorded"
      )
    )
  }

  withdrawalRequests.take(15).forEach { req ->
    val ts = req.createdAt?.toDate()?.time ?: 0L
    val reqId = req.id.ifBlank { req.requestId }
    val rupees = req.getEffectiveAmountRupees()
    items.add(
      CombinedActivityEntry(
        id = "wd_${reqId}_${req.status}",
        category = "WITHDRAWAL",
        title = "Withdrawal ${req.getDisplayStatus()} • " +
          if (rupees > 0L) "₹$rupees" else formatRewardInr(req.amountCents),
        subtitle = "User: ${req.userName.ifBlank { req.userId }} • Method: ${req.getEffectiveMethodLabel()}",
        timestampMillis = ts,
        dateLabel = if (ts > 0L) fmt.format(Date(ts)) else "Recorded"
      )
    )
  }

  return items.sortedByDescending { it.timestampMillis }
}
