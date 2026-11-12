package com.example.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.model.AdminActivityLog
import com.example.data.model.COINS_PER_RUPEE
import com.example.data.model.CampaignItem
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.TaskCategories
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.UserProfile
import com.example.data.model.centsToCoins
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.data.model.formatVisibleUserEmail
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.ui.viewmodel.AdminTab
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AdminSectionNavItem(
  val tab: AdminTab,
  val label: String,
  val icon: ImageVector,
  val testTag: String,
  val badgeCount: Int = 0
)

@Composable
fun AdminSectionNavigationBar(
  activeTab: AdminTab,
  pendingUsersCount: Int,
  pendingSubmissionsCount: Int,
  onSelectTab: (AdminTab) -> Unit
) {
  val sections =
    remember(pendingUsersCount, pendingSubmissionsCount) {
      listOf(
        AdminSectionNavItem(AdminTab.OVERVIEW, "1. Overview", Icons.Default.Dashboard, "admin_section_overview"),
        AdminSectionNavItem(AdminTab.USERS, "2. Users", Icons.Default.People, "admin_section_users", pendingUsersCount),
        AdminSectionNavItem(AdminTab.TASKS, "3. Tasks", Icons.Default.Task, "admin_section_tasks"),
        AdminSectionNavItem(AdminTab.CAMPAIGNS, "4. Campaigns", Icons.Default.Campaign, "admin_section_campaigns"),
        AdminSectionNavItem(AdminTab.WITHDRAWALS, "5. Withdrawals", Icons.Default.Payment, "admin_section_withdrawals"),
        AdminSectionNavItem(AdminTab.COINS_REWARDS, "6. Coins & Rewards", Icons.Default.MonetizationOn, "admin_section_coins_rewards"),
        AdminSectionNavItem(AdminTab.ANALYTICS, "7. Analytics & Reports", Icons.Default.Analytics, "admin_section_analytics"),
        AdminSectionNavItem(
          AdminTab.VERIFICATION_FRAUD,
          "8. Verification & Fraud",
          Icons.Default.VerifiedUser,
          "admin_section_verification_fraud",
          pendingSubmissionsCount
        ),
        AdminSectionNavItem(AdminTab.APP_SETTINGS, "9. App Settings", Icons.Default.Settings, "admin_section_app_settings"),
        AdminSectionNavItem(AdminTab.ACTIVITY_LOGS, "10. Activity Logs", Icons.Default.History, "admin_section_activity_logs")
      )
    }

  val normalizedActiveTab =
    if (activeTab == AdminTab.SUBMISSIONS) AdminTab.VERIFICATION_FRAUD else activeTab

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(bottom = 8.dp)
        .testTag("admin_10_sections_nav_bar"),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    sections.forEach { item ->
      val isSelected = normalizedActiveTab == item.tab
      FilterChip(
        selected = isSelected,
        onClick = { onSelectTab(item.tab) },
        leadingIcon = {
          Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
          )
        },
        label = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = item.label,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
            if (item.badgeCount > 0) {
              Surface(
                shape = CircleShape,
                color =
                  if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.errorContainer
              ) {
                Text(
                  text = item.badgeCount.toString(),
                  style = MaterialTheme.typography.labelSmall,
                  color =
                    if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onErrorContainer,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
              }
            }
          }
        },
        modifier = Modifier.testTag(item.testTag)
      )
    }
  }
}

/**
 * SECTION 1: OVERVIEW
 * Displays real Firebase-backed information:
 * - Total users
 * - Active users
 * - Pending user approvals
 * - Active campaigns
 * - Active tasks
 * - Pending submissions
 * - Completed tasks
 * - Total coins distributed
 * - Recent activity
 */
@Composable
fun AdminOverviewSection(
  users: List<UserProfile>,
  tasks: List<TaskItem>,
  campaigns: List<CampaignItem>,
  reviewTasks: List<TaskItem>,
  transactions: List<TransactionRecord>,
  activityLogs: List<AdminActivityLog>,
  onNavigateTab: (AdminTab) -> Unit,
  onCreateTaskClick: () -> Unit
) {
  val totalUsers = users.size
  val activeUsers = users.count { it.approvalStatus.equals("approved", ignoreCase = true) }
  val pendingUserApprovals = users.count { it.approvalStatus.equals("pending", ignoreCase = true) }
  val activeCampaigns =
    campaigns.count { it.status.equals("ACTIVE", ignoreCase = true) && !it.isQuotaExhausted }
  val activeTasks = tasks.count { it.isActive && !it.isQuotaExhausted }
  val pendingSubmissions = reviewTasks.count { it.status == TaskStatus.SUBMITTED }
  val completedTasks = reviewTasks.count { it.status == TaskStatus.APPROVED }

  val totalCoinsDistributed =
    remember(users, transactions, reviewTasks) {
      val fromTx =
        transactions
          .filter { it.status.equals("CREDITED", ignoreCase = true) || it.amountCents > 0L }
          .sumOf { it.amountCents.coerceAtLeast(0L) }
      val fromBalances = users.sumOf { it.balanceCents.coerceAtLeast(0L) }
      val fromApprovedTasks =
        reviewTasks
          .filter { it.status == TaskStatus.APPROVED }
          .sumOf { it.rewardCents.coerceAtLeast(0L) }
      maxOf(fromTx, fromBalances, fromApprovedTasks)
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_overview_section"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 84.dp)
  ) {
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Admin Control Center",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Real-time Firebase metrics across users, tasks, campaigns & rewards.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Button(
            onClick = onCreateTaskClick,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.testTag("overview_create_task_btn")
          ) {
            Text("+ New Task / Campaign")
          }
        }
      }
    }

    // 8 Core Metrics Grid (2 columns x 4 rows)
    item {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OverviewMetricTile(
            title = "Total Users",
            value = "$totalUsers",
            subtitle = "$activeUsers active accounts",
            icon = Icons.Default.People,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateTab(AdminTab.USERS) }
          )
          OverviewMetricTile(
            title = "Active Users",
            value = "$activeUsers",
            subtitle = "Approved & verified",
            icon = Icons.Default.CheckCircle,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateTab(AdminTab.USERS) }
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OverviewMetricTile(
            title = "Pending Approvals",
            value = "$pendingUserApprovals",
            subtitle = "User accounts awaiting review",
            icon = Icons.Default.PendingActions,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateTab(AdminTab.USERS) }
          )
          OverviewMetricTile(
            title = "Active Campaigns",
            value = "$activeCampaigns",
            subtitle = "${campaigns.size} total campaigns",
            icon = Icons.Default.Campaign,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateTab(AdminTab.CAMPAIGNS) }
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OverviewMetricTile(
            title = "Active Tasks",
            value = "$activeTasks",
            subtitle = "${tasks.size} total tasks",
            icon = Icons.Default.Task,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateTab(AdminTab.TASKS) }
          )
          OverviewMetricTile(
            title = "Pending Submissions",
            value = "$pendingSubmissions",
            subtitle = "Proofs awaiting review",
            icon = Icons.Default.RateReview,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateTab(AdminTab.VERIFICATION_FRAUD) }
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OverviewMetricTile(
            title = "Completed Tasks",
            value = "$completedTasks",
            subtitle = "Approved & rewarded",
            icon = Icons.Default.VerifiedUser,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateTab(AdminTab.VERIFICATION_FRAUD) }
          )
          OverviewMetricTile(
            title = "Coins Distributed",
            value = formatCoins(centsToCoins(totalCoinsDistributed)),
            subtitle = "≈ ${formatRewardInr(totalCoinsDistributed)}",
            icon = Icons.Default.MonetizationOn,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateTab(AdminTab.COINS_REWARDS) }
          )
        }
      }
    }

    // Recent Activity Feed
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
              text = "Recent System & Admin Activity",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "View All Logs →",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.clickable { onNavigateTab(AdminTab.ACTIVITY_LOGS) }
            )
          }

          val recentEntries =
            remember(activityLogs, reviewTasks, tasks) {
              buildCombinedActivityEntries(activityLogs, reviewTasks, tasks, campaigns, transactions)
                .take(6)
            }

          if (recentEntries.isEmpty()) {
            Text(
              text = "No recent activity recorded yet.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          } else {
            recentEntries.forEachIndexed { idx, entry ->
              if (idx > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
              }
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                  )
                  Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = entry.dateLabel,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OverviewMetricTile(
  title: String,
  value: String,
  subtitle: String,
  icon: ImageVector,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(16.dp)
        )
        Text(
          text = title,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.ExtraBold,
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

/**
 * SECTION 6: COINS & REWARDS
 * - Manage reward configurations (125 Coins = ₹1)
 * - View coin transactions and reward history
 * - Calculate user balances accurately
 * - Prevent duplicate reward credits
 * - Maintain an auditable reward ledger
 */
@Composable
fun AdminCoinsRewardsSection(
  users: List<UserProfile>,
  reviewTasks: List<TaskItem>,
  transactions: List<TransactionRecord>
) {
  var searchQuery by rememberSaveable { mutableStateOf("") }
  val totalUserBalancesCoins = remember(users) { users.sumOf { it.balanceCents.coerceAtLeast(0L) } }
  val approvedTaskRewardsCoins =
    remember(reviewTasks) {
      reviewTasks
        .filter { it.status == TaskStatus.APPROVED }
        .sumOf { it.rewardCents.coerceAtLeast(0L) }
    }
  val filteredTransactions =
    remember(transactions, searchQuery) {
      if (searchQuery.isBlank()) {
        transactions
      } else {
        val q = searchQuery.trim().lowercase(Locale.US)
        transactions.filter { tx ->
          tx.title.lowercase(Locale.US).contains(q) ||
            tx.userId.lowercase(Locale.US).contains(q) ||
            tx.referenceId.lowercase(Locale.US).contains(q)
        }
      }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_coins_rewards_section"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 48.dp)
  ) {
    // 1. Reward Configuration & Conversion Standard Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Coins & Reward Configuration",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Surface(
              shape = RoundedCornerShape(50),
              color = MaterialTheme.colorScheme.primaryContainer
            ) {
              Text(
                text = "$COINS_PER_RUPEE Coins = ₹1.00",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
              )
            }
          }
          Text(
            text = "All task rewards, daily check-in streaks (Days 1–7: 10–50 Coins), Spin & Earn outcomes, and Watch & Earn video credits are atomically recorded in Firestore with idempotent transaction keys (`tx_approval_{submissionId}`) to prevent duplicate reward credits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column {
              Text("Total User Wallet Balances", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(formatRewardInr(totalUserBalancesCoins), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
              Text("Approved Task Rewards", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(formatRewardInr(approvedTaskRewardsCoins), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }

    // 2. Search Ledger
    item {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search reward ledger by title, user UID or task ID...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Default.Close, contentDescription = "Clear")
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("admin_ledger_search_input")
      )
    }

    // 3. Auditable Reward Ledger
    item {
      Text(
        text = "Auditable Coin & Reward Ledger (${filteredTransactions.size})",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    }

    if (filteredTransactions.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
          Text(
            text = "No reward ledger transactions match the current filter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
          )
        }
      }
    } else {
      items(filteredTransactions, key = { it.id.ifBlank { "${it.userId}_${it.referenceId}_${it.amountCents}" } }) { tx ->
        val dateStr =
          tx.createdAt?.toDate()?.let {
            SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
          } ?: "Recorded"
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = tx.title.ifBlank { "Reward Credit" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "User UID: ${tx.userId.ifBlank { "N/A" }} • $dateStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              if (tx.referenceId.isNotBlank()) {
                Text(
                  text = "Ref ID: ${tx.referenceId}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
            Text(
              text = "+${formatRewardInr(tx.amountCents)}",
              style = MaterialTheme.typography.titleSmall,
              color = Color(0xFF16A34A),
              fontWeight = FontWeight.ExtraBold
            )
          }
        }
      }
    }
  }
}

/**
 * SECTION 7: ANALYTICS & REPORTS
 * Displays genuine data for users, tasks, campaigns, submissions, approvals, and rewards
 * with category and date-range filters.
 */
@Composable
fun AdminAnalyticsReportsSection(
  users: List<UserProfile>,
  tasks: List<TaskItem>,
  campaigns: List<CampaignItem>,
  reviewTasks: List<TaskItem>,
  transactions: List<TransactionRecord>
) {
  var selectedRange by rememberSaveable { mutableStateOf("ALL") }
  var selectedCategory by rememberSaveable { mutableStateOf("ALL") }

  val nowMillis = System.currentTimeMillis()
  val cutoffMillis =
    when (selectedRange) {
      "TODAY" -> nowMillis - 24L * 60 * 60 * 1000
      "7D" -> nowMillis - 7L * 24 * 60 * 60 * 1000
      "30D" -> nowMillis - 30L * 24 * 60 * 60 * 1000
      else -> 0L
    }

  val filteredTasks =
    remember(tasks, selectedRange, selectedCategory, cutoffMillis) {
      tasks.filter { task ->
        val tsMillis = (task.updatedAt ?: task.createdAt)?.toDate()?.time ?: Long.MAX_VALUE
        val inTime = cutoffMillis == 0L || tsMillis >= cutoffMillis
        val inCat = selectedCategory == "ALL" || task.category.equals(selectedCategory, ignoreCase = true)
        inTime && inCat
      }
    }

  val filteredSubmissions =
    remember(reviewTasks, selectedRange, selectedCategory, cutoffMillis) {
      reviewTasks.filter { sub ->
        val tsMillis = (sub.updatedAt ?: sub.createdAt)?.toDate()?.time ?: Long.MAX_VALUE
        val inTime = cutoffMillis == 0L || tsMillis >= cutoffMillis
        val inCat = selectedCategory == "ALL" || sub.category.equals(selectedCategory, ignoreCase = true)
        inTime && inCat
      }
    }

  val submittedCount = filteredSubmissions.count { it.status == TaskStatus.SUBMITTED }
  val approvedCount = filteredSubmissions.count { it.status == TaskStatus.APPROVED }
  val rejectedCount = filteredSubmissions.count { it.status == TaskStatus.REJECTED }
  val totalReviewed = approvedCount + rejectedCount
  val approvalRatePercent =
    if (totalReviewed > 0) (approvedCount * 100) / totalReviewed else 100
  val approvedCoinsInPeriod =
    filteredSubmissions
      .filter { it.status == TaskStatus.APPROVED }
      .sumOf { it.rewardCents.coerceAtLeast(0L) }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_analytics_reports_section"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 48.dp)
  ) {
    // Date Range Filters
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Date Range & Category Filters",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold
        )
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          listOf(
            "ALL" to "All Time",
            "TODAY" to "Last 24 Hours",
            "7D" to "Last 7 Days",
            "30D" to "Last 30 Days"
          ).forEach { (key, label) ->
            FilterChip(
              selected = selectedRange == key,
              onClick = { selectedRange = key },
              label = { Text(label) }
            )
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          FilterChip(
            selected = selectedCategory == "ALL",
            onClick = { selectedCategory = "ALL" },
            label = { Text("All Categories") }
          )
          TaskCategories.ALL.forEach { cat ->
            FilterChip(
              selected = selectedCategory == cat,
              onClick = { selectedCategory = cat },
              label = { Text(cat) }
            )
          }
        }
      }
    }

    // Submission & Approval Funnel
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            text = "Submission & Verification Funnel",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
              Text("Tasks in Scope", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text("${filteredTasks.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column {
              Text("Pending Review", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text("$submittedCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column {
              Text("Approved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text("$approvedCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
            }
            Column {
              Text("Rejected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text("$rejectedCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
          }
          LinearProgressIndicator(
            progress = { (approvalRatePercent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              text = "Approval Rate: $approvalRatePercent%",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Rewards Credited: ${formatRewardInr(approvedCoinsInPeriod)}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }

    // Category Breakdown
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            text = "Performance by Task Category",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          TaskCategories.ALL.forEach { cat ->
            val catTasks = tasks.count { it.category.equals(cat, ignoreCase = true) }
            val catApproved =
              reviewTasks.count {
                it.category.equals(cat, ignoreCase = true) && it.status == TaskStatus.APPROVED
              }
            val catPending =
              reviewTasks.count {
                it.category.equals(cat, ignoreCase = true) && it.status == TaskStatus.SUBMITTED
              }
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(text = cat, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
              Text(
                text = "$catTasks tasks • $catApproved approved • $catPending pending",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }

    // Campaign Quota Utilization Report
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Campaign Quota & Completion Report (${campaigns.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          if (campaigns.isEmpty()) {
            Text(
              text = "No campaigns created yet.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          } else {
            campaigns.forEach { camp ->
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(camp.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                  Text(
                    text = "${camp.status} • Remaining Quota: ${camp.remainingQuota}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Text(
                  text = "${camp.completedSubmissions}/${camp.targetSubmissions}",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary
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
 * SECTION 8: VERIFICATION & FRAUD
 * - Review submitted proof
 * - Detect duplicate submissions where possible (duplicate proof text, duplicate screenshot URL/hash, suspended user attempts)
 * - Flag suspicious activity
 * - Prevent repeated reward claims
 * - Ensure reward approval is securely validated
 */
@Composable
fun AdminVerificationFraudSection(
  reviewTasks: List<TaskItem>,
  users: List<UserProfile>,
  withdrawalRequests: List<DemoWithdrawalRequest>,
  selectedFilter: String,
  onSelectFilter: (String) -> Unit,
  onReviewSubmission: (TaskItem, Boolean, String) -> Unit
) {
  // Compute fraud / duplicate heuristics across all submissions
  val proofTextCounts =
    remember(reviewTasks) {
      reviewTasks
        .map { it.proofText.trim().lowercase(Locale.US) }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
    }
  val proofImageCounts =
    remember(reviewTasks) {
      reviewTasks
        .map { it.proofImageUrl.trim() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
    }
  val userStatusById =
    remember(users) {
      users.associateBy({ it.userId.ifBlank { it.id } }, { it.approvalStatus })
    }

  val suspiciousSubmissionIds =
    remember(reviewTasks, proofTextCounts, proofImageCounts, userStatusById) {
      reviewTasks
        .filter { sub ->
          val normText = sub.proofText.trim().lowercase(Locale.US)
          val img = sub.proofImageUrl.trim()
          val dupText = normText.isNotBlank() && (proofTextCounts[normText] ?: 0) > 1
          val dupImg = img.isNotBlank() && (proofImageCounts[img] ?: 0) > 1
          val shortText = normText.length in 1..5
          val userSuspended =
            userStatusById[sub.assignedUserId]?.equals("suspended", ignoreCase = true) == true
          dupText || dupImg || shortText || userSuspended
        }
        .map { it.id }
        .toSet()
    }

  Column(modifier = Modifier.fillMaxSize().testTag("admin_verification_fraud_section")) {
    Card(
      modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
      shape = RoundedCornerShape(14.dp),
      colors =
        CardDefaults.cardColors(
          containerColor =
            if (suspiciousSubmissionIds.isNotEmpty())
              MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surface
        ),
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(
          imageVector =
            if (suspiciousSubmissionIds.isNotEmpty()) Icons.Default.WarningAmber
            else Icons.Default.Shield,
          contentDescription = null,
          tint =
            if (suspiciousSubmissionIds.isNotEmpty()) MaterialTheme.colorScheme.error
            else Color(0xFF16A34A),
          modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text =
              if (suspiciousSubmissionIds.isNotEmpty())
                "${suspiciousSubmissionIds.size} Submission(s) Flagged for Duplicate / Suspicious Proof"
              else "Anti-Fraud & Duplicate Guard Active",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Deterministic submission IDs (`{taskId}_{userId}`) and ledger keys (`tx_approval_{submissionId}`) block duplicate reward claims.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }

    AdminSubmissionsTabContent(
      tasks = reviewTasks,
      withdrawalRequests = withdrawalRequests,
      selectedFilter = selectedFilter,
      onSelectFilter = onSelectFilter,
      onReviewSubmission = onReviewSubmission,
      suspiciousSubmissionIds = suspiciousSubmissionIds
    )
  }
}

/**
 * SECTION 9: APP SETTINGS
 * Shows ONLY settings that are actually implemented and safely configurable:
 * 1. Theme Mode (Light Minimal Default / Dark Mode / Follow System) via ThemePreferences
 * 2. App Version & Remote Update Check via AppUpdateManager
 * 3. Notification Channels Verification
 * 4. Firebase & Security Rules Configuration Inspector
 */
@Composable
fun AdminAppSettingsSection(
  databaseId: String,
  onOpenSecurityDialog: () -> Unit,
  onTriggerUpdateCheck: () -> Unit
) {
  val context = LocalContext.current
  val currentThemeMode by ThemePreferences.currentThemeMode.collectAsState()

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_app_settings_section"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 48.dp)
  ) {
    // 1. UI Appearance & Theme Mode
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            text = "Appearance & Theme Mode",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Configure the active theme for TaskFlow on this device (persisted in SharedPreferences).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
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
                label = { Text(label) },
                modifier = Modifier.testTag("admin_theme_mode_${mode.name.lowercase(Locale.US)}")
              )
            }
          }
        }
      }
    }

    // 2. App Build & Remote Update Check
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Application Build & Remote Update",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Package ID: ${BuildConfig.APPLICATION_ID}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "Version: ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "Reward Conversion Rate: $COINS_PER_RUPEE Coins = ₹1.00 INR",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

    // 3. Firebase & Backend Security Diagnostics
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
              text = "Firebase & Zero-Trust Security Config",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
          }
          Text(
            text = "Project ID: taskflow-8852d\nFirestore Database ID: $databaseId",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
 * SECTION 10: ACTIVITY LOGS
 * Records and displays important admin actions, campaign changes, task changes,
 * approvals, rejections, and reward operations.
 */
@Composable
fun AdminActivityLogsSection(
  activityLogs: List<AdminActivityLog>,
  reviewTasks: List<TaskItem>,
  tasks: List<TaskItem>,
  campaigns: List<CampaignItem>,
  transactions: List<TransactionRecord>
) {
  var selectedCategoryFilter by rememberSaveable { mutableStateOf("ALL") }
  val allEntries =
    remember(activityLogs, reviewTasks, tasks, campaigns, transactions) {
      buildCombinedActivityEntries(activityLogs, reviewTasks, tasks, campaigns, transactions)
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
          "USER" to "User Actions"
        ).forEach { (key, label) ->
          FilterChip(
            selected = selectedCategoryFilter == key,
            onClick = { selectedCategoryFilter = key },
            label = { Text(label) }
          )
        }
      }
    }

    if (filteredEntries.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
          Text(
            text = "No activity logs recorded for this filter yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
          )
        }
      }
    } else {
      items(filteredEntries, key = { it.id }) { entry ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.primaryContainer
                ) {
                  Text(
                    text = entry.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                  )
                }
                Text(
                  text = entry.title,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Bold
                )
              }
              Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = entry.dateLabel,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
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
  transactions: List<TransactionRecord>
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
        subtitle = "User: ${sub.assignedUserName.ifBlank { sub.assignedUserId }} • ${formatRewardInr(sub.rewardCents)}",
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
        subtitle = "Progress: ${camp.completedSubmissions}/${camp.targetSubmissions} • Reward: ${formatRewardInr(camp.rewardPerTaskCents)}",
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
        title = tx.title.ifBlank { "Reward Operation" },
        subtitle = "User UID: ${tx.userId} • Amount: +${formatRewardInr(tx.amountCents)}",
        timestampMillis = ts,
        dateLabel = if (ts > 0L) fmt.format(Date(ts)) else "Recorded"
      )
    )
  }

  return items.sortedByDescending { it.timestampMillis }
}
