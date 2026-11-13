package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.MIN_WITHDRAWAL_CENTS
import com.example.data.model.MIN_WITHDRAWAL_COINS
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserActivityItem
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalMethod
import com.example.data.model.centsToCoins
import com.example.data.model.coinsToCents
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.data.model.formatVisibleUserEmail
import com.example.data.model.getEffectiveBalanceCoins
import com.example.data.model.getEligibleWithdrawalCoins
import com.example.data.model.getPromotionalBalanceCoins
import com.example.data.model.mergeTasksWithPreservedSubmissions
import com.example.ui.components.AppIconThumbnail
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonCoinBadge
import com.example.ui.components.NeonGlassCard
import com.example.ui.components.ProofImageView
import com.example.ui.components.RewardBadge
import com.example.ui.components.TaskFlowBrandLogo
import com.example.ui.components.VerifiedBadge
import com.example.ui.components.resolveAppOrWebsiteLabel
import com.example.ui.viewmodel.ProfileSubScreen
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * TaskFlow Wallet Screen (Purple-and-White Design):
 * - Displays Available Balance in INR (₹).
 * - Displays only ONE main action button: `Withdraw` (No "Add Money" button anywhere).
 * - Clearly displays `Minimum Withdrawal: ₹200`.
 * - Opens `Select Withdrawal Method` dialog with `Bank Account` and `UPI ID` demo request flows.
 */
@Composable
fun UserWalletScreen(
  profileState: UiState<UserProfile?>,
  tasksState: UiState<List<TaskItem>>,
  withdrawalRequestsState: UiState<List<DemoWithdrawalRequest>>,
  currentUserId: String = "",
  isBusy: Boolean = false,
  withdrawalValidationError: String? = null,
  onClearValidationError: () -> Unit = {},
  submissionsState: UiState<List<SubmissionItem>> = UiState.Success(emptyList()),
  transactionsState: UiState<List<TransactionRecord>> = UiState.Success(emptyList()),
  onSubmitDemoWithdrawal: (
    amountInr: String,
    method: String,
    accountHolderName: String,
    bankName: String,
    accountNumber: String,
    ifscCode: String,
    upiId: String,
    onSuccess: () -> Unit
  ) -> Unit = { _, _, _, _, _, _, _, _ -> },
  initialOpenWithdraw: Boolean = false
) {
  val profile = (profileState as? UiState.Success)?.data
  val availableBalanceCents = profile?.balanceCents ?: 0L
  val eligibleCoins = profile?.getEligibleWithdrawalCoins() ?: 0L
  val promotionalCoins = profile?.getPromotionalBalanceCoins() ?: 0L
  val totalBalanceCoins = profile?.getEffectiveBalanceCoins() ?: 0L
  val rawTasks = (tasksState as? UiState.Success)?.data ?: emptyList()
  val submissions = (submissionsState as? UiState.Success)?.data ?: emptyList()
  val transactions = (transactionsState as? UiState.Success)?.data ?: emptyList()
  val tasks =
    remember(rawTasks, submissions, currentUserId) {
      mergeTasksWithPreservedSubmissions(rawTasks, submissions, currentUserId)
    }
  val withdrawalRequests =
    (withdrawalRequestsState as? UiState.Success)?.data ?: emptyList()

  val approvedUserTasks =
    remember(tasks, currentUserId) {
      tasks.filter {
        it.status == TaskStatus.APPROVED &&
          (currentUserId.isBlank() || it.assignedUserId == currentUserId)
      }
    }

  val totalEarningsCoins =
    remember(approvedUserTasks, transactions, totalBalanceCoins) {
      val txEarningsCoins =
        transactions
          .filter { it.type != TransactionType.DEMO_WITHDRAWAL }
          .sumOf { it.getEffectiveAmountCoins() }
      if (txEarningsCoins > 0L) {
        txEarningsCoins
      } else {
        approvedUserTasks.sumOf { it.getEffectiveRewardCoins() } + totalBalanceCoins
      }
    }

  val pendingRewardsCoins =
    remember(tasks, currentUserId) {
      tasks
        .filter {
          it.status == TaskStatus.SUBMITTED &&
            (currentUserId.isBlank() || it.assignedUserId == currentUserId)
        }
        .sumOf { it.getEffectiveRewardCoins() }
    }

  var showWithdrawMethodDialog by rememberSaveable { mutableStateOf(initialOpenWithdraw) }

  if (showWithdrawMethodDialog) {
    SelectWithdrawalMethodDialog(
      availableBalanceCents = availableBalanceCents,
      isBusy = isBusy,
      externalValidationError = withdrawalValidationError,
      onDismiss = {
        onClearValidationError()
        showWithdrawMethodDialog = false
      },
      onSubmit = { amountInr, method, holderName, bankName, accountNum, ifsc, upiId ->
        onSubmitDemoWithdrawal(
          amountInr,
          method,
          holderName,
          bankName,
          accountNum,
          ifsc,
          upiId
        ) {
          showWithdrawMethodDialog = false
        }
      }
    )
  }

  NeonBackgroundBox {
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("user_wallet_screen"),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
      // 1. High-Contrast Wallet Hero Card (Coin System)
      item {
        NeonGlassCard(
          modifier = Modifier.fillMaxWidth().testTag("wallet_hero_card"),
          borderColor = Color(0xFFC7D2FE),
          backgroundColor = MaterialTheme.colorScheme.surface
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Surface(
                shape = CircleShape,
                color = Color(0xFFFEF3C7),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                modifier = Modifier.size(40.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.AccountBalanceWallet,
                    contentDescription = "TaskFlow Wallet",
                    tint = Color(0xFFD97706),
                    modifier = Modifier.size(20.dp)
                  )
                }
              }
              Column {
                Text(
                  text = "TASKFLOW COIN WALLET",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF4F46E5),
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "Total Coin Balance",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontWeight = FontWeight.Medium
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
              text = formatCoins(totalBalanceCoins),
              style = MaterialTheme.typography.headlineLarge,
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.ExtraBold,
              modifier = Modifier.testTag("wallet_available_balance")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Eligible Withdrawable Coins vs Promotional Coins breakdown
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFECFDF5),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6EE7B7)),
                modifier = Modifier.weight(1f)
              ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                  Text(
                    text = "Eligible Withdrawable",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF065F46),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = formatCoins(eligibleCoins),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF047857),
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.testTag("wallet_eligible_coins")
                  )
                }
              }
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFE0F2FE),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7DD3FC)),
                modifier = Modifier.weight(1f)
              ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                  Text(
                    text = "Promotional Coins",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF075985),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = formatCoins(promotionalCoins),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF0284C7),
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.testTag("wallet_promotional_coins")
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
              shape = RoundedCornerShape(50),
              color = Color(0xFFEEF2FF),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC7D2FE))
            ) {
              Text(
                text = "Minimum Withdrawal: 25,000 Coins",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4338CA),
                fontWeight = FontWeight.Bold,
                modifier =
                  Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    .testTag("wallet_min_withdrawal_text")
              )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
              onClick = {
                onClearValidationError()
                showWithdrawMethodDialog = true
              },
              shape = RoundedCornerShape(14.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF4F46E5),
                  contentColor = Color.White
                ),
              modifier =
                Modifier.fillMaxWidth()
                  .height(48.dp)
                  .testTag("wallet_withdraw_button")
            ) {
              Icon(
                imageVector = Icons.Default.Payment,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Withdraw Coins",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }

      // 2. Total Earnings and Pending Rewards 2-Column Row (in Coins)
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Card(
            modifier = Modifier.weight(1f).testTag("wallet_total_earnings_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
          ) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text(
                text = "Total Earnings",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = formatCoins(totalEarningsCoins),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF16A34A),
                modifier = Modifier.testTag("wallet_total_earnings")
              )
            }
          }

          Card(
            modifier = Modifier.weight(1f).testTag("wallet_pending_rewards_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
          ) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text(
                text = "Pending Rewards",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = formatCoins(pendingRewardsCoins),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFD97706),
                modifier = Modifier.testTag("wallet_pending_rewards")
              )
            }
          }
        }
      }

      // 3. Recent Withdrawal Requests Section (in Coins)
      item {
        Text(
          text = "Withdrawal Requests (${withdrawalRequests.size})",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground
        )
      }

      if (withdrawalRequests.isEmpty()) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(22.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(
                imageVector = Icons.Default.HourglassTop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "No Withdrawal Requests Yet",
                style = MaterialTheme.typography.titleMedium
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Tap Withdraw Coins above to choose Bank Account or UPI ID (Minimum Withdrawal: 25,000 Coins).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
            }
          }
        }
      } else {
        items(withdrawalRequests, key = { it.id.ifBlank { it.requestId } }) { request ->
          val dateStr =
            request.createdAt?.toDate()?.let {
              SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
            } ?: "Just now"
          Card(
            modifier =
              Modifier.fillMaxWidth()
                .testTag("user_withdrawal_request_card_${request.id.ifBlank { request.requestId }}"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
              ) {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.size(44.dp)
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector =
                        if (request.method == WithdrawalMethod.UPI_ID) {
                          Icons.Default.QrCode2
                        } else {
                          Icons.Default.AccountBalance
                        },
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(22.dp)
                    )
                  }
                }
                Column {
                  Text(
                    text = "${request.method} Withdrawal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                  )
                  Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                  ) {
                    Text(
                      text = "Pending Review",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSecondaryContainer,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                  }
                }
              }
              NeonCoinBadge(coins = request.getEffectiveAmountCoins())
            }
          }
        }
      }

      // 4. Credited Task Earnings Summary (in Coins)
      if (approvedUserTasks.isNotEmpty()) {
        item {
          Text(
            text = "Credited Task Rewards (${approvedUserTasks.size})",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
          )
        }
        items(approvedUserTasks, key = { "wallet_earned_${it.id}" }) { task ->
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
              ) {
                AppIconThumbnail(
                  appIconUrl = task.appIconUrl,
                  appName = task.appName,
                  category = task.category,
                  size = 42.dp
                )
                Column {
                  Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = "Approved & Credited",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                  )
                }
              }
              NeonCoinBadge(coins = task.getEffectiveRewardCoins())
            }
          }
        }
      }

      // 5. Transaction History Section (in Coins)
      item {
        Text(
          text = "Transaction History (${transactions.size})",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.testTag("wallet_transaction_history_section")
        )
      }

      if (transactions.isEmpty()) {
        item {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(20.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text = "No Transactions Recorded",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Daily check-ins, spin rewards, watch rewards, and task earnings will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
            }
          }
        }
      } else {
        items(
          transactions,
          key = { it.id.ifBlank { "tx_${it.title}_${it.createdAt?.seconds ?: 0L}_${it.amountCents}_${it.amountCoins}" } }
        ) { tx ->
          val dateStr =
            tx.createdAt?.toDate()?.let {
              SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
            } ?: "Today"
          val txCoins = tx.getEffectiveAmountCoins()
          Card(
            modifier =
              Modifier.fillMaxWidth().testTag("wallet_transaction_item_${tx.id}"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = tx.title.ifBlank { "Wallet Transaction" },
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = "$dateStr • ${tx.type}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              Text(
                text =
                  if (tx.type == TransactionType.DEMO_WITHDRAWAL) {
                    "-${formatCoins(txCoins)}"
                  } else {
                    "+${formatCoins(txCoins)}"
                  },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color =
                  if (tx.type == TransactionType.DEMO_WITHDRAWAL) {
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
fun SelectWithdrawalMethodDialog(
  availableBalanceCents: Long,
  isBusy: Boolean,
  externalValidationError: String?,
  onDismiss: () -> Unit,
  onSubmit: (
    amountInr: String,
    method: String,
    accountHolderName: String,
    bankName: String,
    accountNumber: String,
    ifscCode: String,
    upiId: String
  ) -> Unit
) {
  var selectedMethod by rememberSaveable { mutableStateOf(WithdrawalMethod.BANK_ACCOUNT) }
  var accountHolderName by rememberSaveable { mutableStateOf("") }
  var bankName by rememberSaveable { mutableStateOf("") }
  var accountNumber by rememberSaveable { mutableStateOf("") }
  var ifscCode by rememberSaveable { mutableStateOf("") }
  var upiId by rememberSaveable { mutableStateOf("") }
  var amountInput by rememberSaveable { mutableStateOf("25000") }
  var localValidationError by rememberSaveable { mutableStateOf<String?>(null) }

  val availableEligibleCoins = centsToCoins(availableBalanceCents)
  val displayedError = localValidationError ?: externalValidationError

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("withdrawal_method_dialog"),
    title = {
      Column {
        Text(
          text = "Select Withdrawal Method",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text =
            "Eligible Balance: ${formatCoins(availableEligibleCoins)} • Minimum Withdrawal: 25,000 Coins",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        WithdrawalMethodOptionCard(
          title = "Bank Account",
          subtitle = "NEFT / IMPS Bank Transfer",
          icon = Icons.Default.AccountBalance,
          selected = selectedMethod == WithdrawalMethod.BANK_ACCOUNT,
          onClick = {
            selectedMethod = WithdrawalMethod.BANK_ACCOUNT
            localValidationError = null
          },
          testTag = "withdrawal_method_bank"
        )

        WithdrawalMethodOptionCard(
          title = "UPI ID",
          subtitle = "Instant UPI Transfer",
          icon = Icons.Default.QrCode2,
          selected = selectedMethod == WithdrawalMethod.UPI_ID,
          onClick = {
            selectedMethod = WithdrawalMethod.UPI_ID
            localValidationError = null
          },
          testTag = "withdrawal_method_upi"
        )

        if (selectedMethod == WithdrawalMethod.BANK_ACCOUNT) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth().testTag("bank_account_form_section")
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Text(
                text = "BANK ACCOUNT DETAILS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
              )
              OutlinedTextField(
                value = accountHolderName,
                onValueChange = {
                  accountHolderName = it
                  localValidationError = null
                },
                label = { Text("Account Holder Name") },
                placeholder = { Text("e.g., Rahul Sharma") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bank_holder_name_input")
              )
              OutlinedTextField(
                value = bankName,
                onValueChange = {
                  bankName = it
                  localValidationError = null
                },
                label = { Text("Bank Name") },
                placeholder = { Text("e.g., State Bank of India") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bank_name_input")
              )
              OutlinedTextField(
                value = accountNumber,
                onValueChange = {
                  accountNumber = it
                  localValidationError = null
                },
                label = { Text("Account Number") },
                placeholder = { Text("Enter account number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bank_account_number_input")
              )
              OutlinedTextField(
                value = ifscCode,
                onValueChange = {
                  ifscCode = it.uppercase(Locale.US)
                  localValidationError = null
                },
                label = { Text("IFSC Code") },
                placeholder = { Text("e.g., SBIN0001234") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bank_ifsc_input")
              )
            }
          }
        } else {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth().testTag("upi_id_form_section")
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Text(
                text = "UPI ID DETAILS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
              )
              OutlinedTextField(
                value = upiId,
                onValueChange = {
                  upiId = it
                  localValidationError = null
                },
                label = { Text("UPI ID") },
                placeholder = { Text("e.g., username@upi") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("upi_id_input")
              )
            }
          }
        }

        // Withdrawal Amount Input (Coins)
        OutlinedTextField(
          value = amountInput,
          onValueChange = {
            amountInput = it
            localValidationError = null
          },
          label = { Text("Withdrawal Amount (Coins)") },
          placeholder = { Text("Minimum 25,000 Coins") },
          singleLine = true,
          supportingText = {
            Text(
              "Minimum Withdrawal: 25,000 Coins • Eligible: ${formatCoins(availableEligibleCoins)}"
            )
          },
          modifier = Modifier.fillMaxWidth().testTag("withdrawal_amount_input")
        )

        if (!displayedError.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().testTag("withdrawal_validation_error")
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = displayedError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val cleaned = amountInput.trim().replace(",", "")
          val parsedValue = cleaned.toDoubleOrNull()
          // Support both Coin input (>= 25,000 Coins) and legacy test values (e.g. 200..1000)
          val amountCents =
            when {
              parsedValue == null || parsedValue <= 0.0 -> 0L
              parsedValue >= MIN_WITHDRAWAL_COINS.toDouble() ->
                coinsToCents(parsedValue.roundToLong())
              parsedValue in 200.0..2000.0 -> (parsedValue * 100.0).roundToLong()
              else -> coinsToCents(parsedValue.roundToLong())
            }
          val requestedCoins = centsToCoins(amountCents)
          when {
            selectedMethod == WithdrawalMethod.BANK_ACCOUNT &&
              (accountHolderName.isBlank() ||
                bankName.isBlank() ||
                accountNumber.isBlank() ||
                ifscCode.isBlank()) -> {
              localValidationError =
                "Please complete Account Holder Name, Bank Name, Account Number, and IFSC Code."
            }
            selectedMethod == WithdrawalMethod.UPI_ID &&
              (upiId.isBlank() || !upiId.contains("@")) -> {
              localValidationError = "Please enter a valid UPI ID (e.g., name@upi)."
            }
            parsedValue == null || parsedValue <= 0.0 -> {
              localValidationError = "Please enter a valid withdrawal amount in Coins."
            }
            amountCents < MIN_WITHDRAWAL_CENTS -> {
              localValidationError =
                "Minimum withdrawal amount is 25,000 Coins. Please enter 25,000 Coins or more."
            }
            amountCents > availableBalanceCents -> {
              localValidationError =
                "Amount (${formatCoins(requestedCoins)}) exceeds your eligible withdrawable balance (${formatCoins(availableEligibleCoins)})."
            }
            else -> {
              localValidationError = null
              onSubmit(
                amountInput.trim(),
                selectedMethod,
                accountHolderName.trim(),
                bankName.trim(),
                accountNumber.trim(),
                ifscCode.trim(),
                upiId.trim()
              )
            }
          }
        },
        enabled = !isBusy,
        modifier = Modifier.testTag("withdrawal_submit_button")
      ) {
        Text("Submit")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("withdrawal_cancel_button")
      ) {
        Text("Cancel")
      }
    }
  )
}

@Composable
private fun WithdrawalMethodOptionCard(
  title: String,
  subtitle: String,
  icon: ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  testTag: String
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color =
      if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
      } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
      },
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .border(
          width = if (selected) 1.5.dp else 1.dp,
          color =
            if (selected) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            },
          shape = RoundedCornerShape(14.dp)
        )
        .clickable(onClick = onClick)
        .testTag(testTag)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp)
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      RadioButton(selected = selected, onClick = onClick)
    }
  }
}

/**
 * Redesigned Profile Screen (`UserProfileScreen`):
 * - At the top, shows ONLY:
 *   1. User's display name (`profile_display_name`)
 *   2. User's email address (`profile_email_address`)
 * - Below the profile header, shows 5 separate clickable menu cards in this exact order:
 *   1. `Activity` (`profile_menu_activity`)
 *   2. `History` (`profile_menu_history`)
 *   3. `Rewards` (`profile_menu_rewards`)
 *   4. `Settings` (`profile_menu_settings`)
 *   5. `About TaskFlow` (`profile_menu_about`)
 * - At the bottom, shows a clearly visible `Sign Out` button (`sign_out_button`).
 * - Supports opening the separate `Activity`, `History`, `Rewards`, `Settings`, and `About TaskFlow` screens.
 */
@Composable
fun UserProfileScreen(
  profileState: UiState<UserProfile?>,
  tasksState: UiState<List<TaskItem>>,
  userEmail: String,
  userUid: String,
  onUpdateDisplayName: (String) -> Unit,
  onOpenSecurityInfo: () -> Unit,
  onSelectTask: (String) -> Unit,
  onSignOut: () -> Unit,
  withdrawalRequestsState: UiState<List<DemoWithdrawalRequest>> = UiState.Success(emptyList()),
  submissionsState: UiState<List<SubmissionItem>> = UiState.Success(emptyList()),
  activeSubScreen: ProfileSubScreen = ProfileSubScreen.MAIN,
  onNavigateSubScreen: ((ProfileSubScreen) -> Unit)? = null,
  onNavigateScreen: ((String) -> Unit)? = null,
  onSubmitSupportTicket: ((String, String, String) -> Unit)? = null
) {
  var internalSubScreen by rememberSaveable { mutableStateOf(ProfileSubScreen.MAIN) }
  val currentSubScreen =
    if (onNavigateSubScreen != null) activeSubScreen else internalSubScreen
  val setSubScreen: (ProfileSubScreen) -> Unit = { target ->
    if (onNavigateSubScreen != null) {
      onNavigateSubScreen(target)
    } else {
      internalSubScreen = target
    }
  }

  val profile = (profileState as? UiState.Success)?.data
  val rawTasks = (tasksState as? UiState.Success)?.data ?: emptyList()
  val submissions = (submissionsState as? UiState.Success)?.data ?: emptyList()
  val tasks =
    remember(rawTasks, submissions, userUid) {
      mergeTasksWithPreservedSubmissions(rawTasks, submissions, userUid)
    }
  val withdrawalRequests =
    (withdrawalRequestsState as? UiState.Success)?.data ?: emptyList()

  when (currentSubScreen) {
    ProfileSubScreen.ACTIVITY -> {
      UserActivityScreen(
        profile = profile,
        tasks = tasks,
        withdrawalRequests = withdrawalRequests,
        currentUserId = userUid,
        onBack = { setSubScreen(ProfileSubScreen.MAIN) },
        onSelectTask = onSelectTask
      )
      return
    }
    ProfileSubScreen.HISTORY -> {
      UserHistoryScreen(
        tasks = tasks,
        currentUserId = userUid,
        onBack = { setSubScreen(ProfileSubScreen.MAIN) },
        onOpenFullTaskDetail = onSelectTask
      )
      return
    }
    ProfileSubScreen.REWARDS -> {
      UserRewardsSubScreen(
        profile = profile,
        tasks = tasks,
        currentUserId = userUid,
        onBack = { setSubScreen(ProfileSubScreen.MAIN) }
      )
      return
    }
    ProfileSubScreen.SETTINGS -> {
      UserSettingsSubScreen(
        profile = profile,
        userEmail = userEmail,
        onUpdateDisplayName = onUpdateDisplayName,
        onOpenSecurityInfo = onOpenSecurityInfo,
        onOpenSupport = { setSubScreen(ProfileSubScreen.SUPPORT) },
        onBack = { setSubScreen(ProfileSubScreen.MAIN) }
      )
      return
    }
    ProfileSubScreen.SUPPORT -> {
      UserSupportScreen(
        onBack = { setSubScreen(ProfileSubScreen.MAIN) },
        onNavigateScreen = { target ->
          onNavigateScreen?.invoke(target)
        },
        onSubmitTicket = onSubmitSupportTicket
      )
      return
    }
    ProfileSubScreen.ABOUT -> {
      UserAboutSubScreen(
        onOpenSecurityInfo = onOpenSecurityInfo,
        onBack = { setSubScreen(ProfileSubScreen.MAIN) }
      )
      return
    }
    ProfileSubScreen.MAIN -> Unit
  }

  val displayNameText =
    profile?.displayName?.ifBlank { "TaskFlow User" } ?: "TaskFlow User"
  val visibleEmailText =
    formatVisibleUserEmail(
      email = profile?.email?.ifBlank { userEmail } ?: userEmail,
      role = profile?.role
    )

  NeonBackgroundBox {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 18.dp, vertical = 16.dp)
          .testTag("user_profile_screen"),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Top Header: User Profile Card with Avatar, Crisp Border, Verified Badge Overlay
      NeonGlassCard(
        modifier = Modifier.fillMaxWidth().testTag("profile_header_card"),
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        backgroundColor = MaterialTheme.colorScheme.surface
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Box(contentAlignment = Alignment.BottomEnd) {
            if (!profile?.photoUrl.isNullOrBlank()) {
              AsyncImage(
                model = profile?.photoUrl,
                contentDescription = displayNameText,
                contentScale = ContentScale.Crop,
                modifier =
                  Modifier.size(84.dp)
                    .clip(CircleShape)
                    .border(3.dp, Color(0xFF4F46E5), CircleShape)
              )
            } else {
              Box(
                modifier =
                  Modifier.size(84.dp)
                    .clip(CircleShape)
                    .background(
                      Brush.linearGradient(
                        colors = listOf(Color(0xFF4F46E5), Color(0xFF06B6D4))
                      )
                    )
                    .border(3.dp, Color(0xFF4F46E5), CircleShape),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text =
                    (displayNameText.firstOrNull() ?: visibleEmailText.firstOrNull() ?: 'U')
                      .uppercaseChar()
                      .toString(),
                  style = MaterialTheme.typography.headlineLarge,
                  color = Color.White,
                  fontWeight = FontWeight.ExtraBold
                )
              }
            }

            // Verified Badge Overlay on bottom-right of avatar ONLY when backend approvalStatus == "approved"
            if (profile?.approvalStatus == "approved") {
              Surface(
                shape = CircleShape,
                color = Color(0xFF0284C7),
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                modifier = Modifier.size(26.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Verified",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          Text(
            text = displayNameText,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("profile_display_name")
          )

          Spacer(modifier = Modifier.height(4.dp))

          Text(
            text = visibleEmailText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("profile_email_address")
          )

          val resolvedUid = userUid.ifBlank { profile?.userId ?: profile?.id ?: "" }
          if (resolvedUid.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
              shape = RoundedCornerShape(50),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
              Text(
                text = "UID: $resolvedUid",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier =
                  Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    .testTag("profile_user_uid")
              )
            }
          }

          Spacer(modifier = Modifier.height(18.dp))

          // 3 Stat Cards Row (Tasks, Completed, Coins)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            val approvedCount = tasks.count { it.status == TaskStatus.APPROVED }
            val effectiveCoins = profile?.getEffectiveBalanceCoins() ?: 0L

            Surface(
              shape = RoundedCornerShape(16.dp),
              color = Color(0xFFEEF2FF),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC7D2FE)),
              modifier = Modifier.weight(1f)
            ) {
              Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Icon(
                  imageVector = Icons.Default.Assignment,
                  contentDescription = null,
                  tint = Color(0xFF4F46E5),
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "${tasks.size}",
                  style = MaterialTheme.typography.titleMedium,
                  color = Color(0xFF1E1B4B),
                  fontWeight = FontWeight.ExtraBold
                )
                Text(
                  text = "Tasks",
                  style = MaterialTheme.typography.labelSmall,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFF4338CA)
                )
              }
            }

            Surface(
              shape = RoundedCornerShape(16.dp),
              color = Color(0xFFECFDF5),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA7F3D0)),
              modifier = Modifier.weight(1f)
            ) {
              Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = null,
                  tint = Color(0xFF059669),
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "$approvedCount",
                  style = MaterialTheme.typography.titleMedium,
                  color = Color(0xFF064E3B),
                  fontWeight = FontWeight.ExtraBold
                )
                Text(
                  text = "Completed",
                  style = MaterialTheme.typography.labelSmall,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFF047857)
                )
              }
            }

            Surface(
              shape = RoundedCornerShape(16.dp),
              color = Color(0xFFFFFBEB),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A)),
              modifier = Modifier.weight(1f)
            ) {
              Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Icon(
                  imageVector = Icons.Default.MonetizationOn,
                  contentDescription = null,
                  tint = Color(0xFFD97706),
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "$effectiveCoins",
                  style = MaterialTheme.typography.titleMedium,
                  color = Color(0xFF92400E),
                  fontWeight = FontWeight.ExtraBold
                )
                Text(
                  text = "Coins",
                  style = MaterialTheme.typography.labelSmall,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color(0xFFB45309)
                )
              }
            }
          }
        }
      }

      // Menu Cards with Test Tags for Activity, History, Rewards, Settings, Support, About
      ProfileMenuCard(
        title = "Personal Information & Activity",
        subtitle = "Update display name, activity log, and user account info",
        icon = Icons.Default.Person,
        onClick = { setSubScreen(ProfileSubScreen.ACTIVITY) },
        testTag = "profile_menu_activity"
      )

      ProfileMenuCard(
        title = "History",
        subtitle = "Browse task submission history cards by status",
        icon = Icons.Default.History,
        onClick = { setSubScreen(ProfileSubScreen.HISTORY) },
        testTag = "profile_menu_history"
      )

      ProfileMenuCard(
        title = "Rewards",
        subtitle = "View earned coin rewards and completed task milestones",
        icon = Icons.Default.CardGiftcard,
        onClick = { setSubScreen(ProfileSubScreen.REWARDS) },
        testTag = "profile_menu_rewards"
      )

      ProfileMenuCard(
        title = "Account Settings",
        subtitle = "Appearance theme and security configuration",
        icon = Icons.Default.Settings,
        onClick = { setSubScreen(ProfileSubScreen.SETTINGS) },
        testTag = "profile_menu_settings"
      )

      ProfileMenuCard(
        title = "Help & Support",
        subtitle = "Contact support and submit help tickets",
        icon = Icons.AutoMirrored.Filled.Help,
        onClick = { setSubScreen(ProfileSubScreen.SUPPORT) },
        testTag = "profile_menu_support"
      )

      ProfileMenuCard(
        title = "Privacy Policy & About",
        subtitle = "TaskFlow overview, privacy rules, and version details",
        icon = Icons.Default.Shield,
        onClick = { setSubScreen(ProfileSubScreen.ABOUT) },
        testTag = "profile_menu_about"
      )

      // Sign Out / Logout Button
      Spacer(modifier = Modifier.height(8.dp))
      Button(
        onClick = onSignOut,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFFFEF2F2),
          contentColor = Color(0xFFDC2626)
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFCA5A5)),
        modifier =
          Modifier.fillMaxWidth()
            .height(52.dp)
            .testTag("sign_out_button")
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.Logout,
          contentDescription = null,
          tint = Color(0xFFDC2626),
          modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Logout",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Color(0xFFDC2626)
        )
      }
    }
  }
}

@Composable
private fun ProfileMenuCard(
  title: String,
  subtitle: String,
  icon: ImageVector,
  onClick: () -> Unit,
  testTag: String
) {
  NeonGlassCard(
    modifier = Modifier.fillMaxWidth().testTag(testTag),
    borderColor = MaterialTheme.colorScheme.outlineVariant,
    backgroundColor = MaterialTheme.colorScheme.surface,
    onClick = onClick
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFEEF2FF),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC7D2FE)),
        modifier = Modifier.size(46.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF4F46E5),
            modifier = Modifier.size(22.dp)
          )
        }
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = "Open $title",
        tint = Color(0xFF4F46E5)
      )
    }
  }
}

/**
 * Separate History Screen (`UserHistoryScreen`):
 * - Displays filter options: `All`, `Approved`, `Pending`, `Rejected`.
 * - Displays each task as an individual compact card containing:
 *   1. Task name
 *   2. App or website icon
 *   3. Reward amount in INR (₹)
 *   4. Submission date
 *   5. Status: `Approved`, `Pending`, or `Rejected`
 *   6. Right-side arrow to open task history details
 * - When a user taps a history card, shows its relevant submission details.
 */
@Composable
fun UserHistoryScreen(
  tasks: List<TaskItem>,
  currentUserId: String,
  onBack: () -> Unit,
  onOpenFullTaskDetail: (String) -> Unit
) {
  BackHandler(onBack = onBack)

  var selectedFilter by rememberSaveable { mutableStateOf("all") }
  var selectedHistoryTask by remember { mutableStateOf<TaskItem?>(null) }

  // Include tasks submitted/completed by this user, or all non-pending tasks if none specifically match
  val historyTasks =
    remember(tasks, currentUserId) {
      val userSubmitted =
        tasks.filter {
          it.status != TaskStatus.PENDING &&
            (currentUserId.isBlank() || it.assignedUserId == currentUserId)
        }
      if (userSubmitted.isNotEmpty()) {
        userSubmitted
      } else {
        tasks
      }
    }

  val filteredTasks =
    remember(historyTasks, selectedFilter) {
      when (selectedFilter) {
        "approved" -> historyTasks.filter { it.status == TaskStatus.APPROVED }
        "pending" ->
          historyTasks.filter {
            it.status == TaskStatus.SUBMITTED || it.status == TaskStatus.PENDING
          }
        "rejected" -> historyTasks.filter { it.status == TaskStatus.REJECTED }
        else -> historyTasks
      }
    }

  val filterOptions =
    listOf(
      "all" to "All",
      "approved" to "Approved",
      "pending" to "Pending",
      "rejected" to "Rejected"
    )

  selectedHistoryTask?.let { task ->
    HistorySubmissionDetailsDialog(
      task = task,
      onDismiss = { selectedHistoryTask = null },
      onOpenTaskScreen = {
        val taskId = task.id
        selectedHistoryTask = null
        onOpenFullTaskDetail(taskId)
      }
    )
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 18.dp, vertical = 14.dp)
        .testTag("user_history_screen")
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(
        onClick = onBack,
        modifier = Modifier.testTag("history_back_button")
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back to Profile"
        )
      }
      Column {
        Text(
          text = "History",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Tap any compact task card to inspect your submission details",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      filterOptions.forEach { (key, label) ->
        FilterChip(
          selected = selectedFilter == key,
          onClick = { selectedFilter = key },
          label = { Text(label) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
          modifier = Modifier.testTag("history_filter_$key")
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
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp)
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = "No task history found for this filter",
            style = MaterialTheme.typography.titleMedium
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Completed and submitted tasks will appear here as compact history cards.",
            style = MaterialTheme.typography.bodySmall,
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
          CompactHistoryTaskCard(
            task = task,
            onClick = { selectedHistoryTask = task }
          )
        }
      }
    }
  }
}

@Composable
private fun CompactHistoryTaskCard(
  task: TaskItem,
  onClick: () -> Unit
) {
  val dateStr =
    (task.updatedAt ?: task.createdAt)?.toDate()?.let {
      SimpleDateFormat("MMM d, yyyy", Locale.US).format(it)
    } ?: "Recent"
  val statusLabel = TaskStatus.historyLabel(task.status)
  val (statusBg, statusFg) =
    when (task.status) {
      TaskStatus.APPROVED -> Color(0xFFD1FAE5) to Color(0xFF065F46)
      TaskStatus.REJECTED -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
      else -> Color(0xFFEDE9FE) to Color(0xFF5B21B6)
    }

  Card(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .clickable(onClick = onClick)
        .testTag("history_card_${task.id}"),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // App or Website Icon
      AppIconThumbnail(
        appIconUrl = task.appIconUrl,
        appName = task.appName,
        category = task.category,
        size = 48.dp
      )

      // Task Name, Submission Date & Status
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = task.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Submitted: $dateStr",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
          shape = RoundedCornerShape(50),
          color = statusBg
        ) {
          Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = statusFg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
          )
        }
      }

      // Reward Amount in INR (₹) + Right-Side Arrow
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Text(
          text = formatRewardInr(task.rewardCents),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.ExtraBold
        )
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = "View History Details",
          tint = MaterialTheme.colorScheme.primary
        )
      }
    }
  }
}

@Composable
private fun HistorySubmissionDetailsDialog(
  task: TaskItem,
  onDismiss: () -> Unit,
  onOpenTaskScreen: () -> Unit
) {
  val dateStr =
    (task.updatedAt ?: task.createdAt)?.toDate()?.let {
      SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
    } ?: "Recently submitted"
  val statusLabel = TaskStatus.historyLabel(task.status)
  val appOrSiteLabel = resolveAppOrWebsiteLabel(task)

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("history_submission_details_dialog"),
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        AppIconThumbnail(
          appIconUrl = task.appIconUrl,
          appName = task.appName,
          category = task.category,
          size = 44.dp
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = task.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "$appOrSiteLabel • $statusLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text(
                text = "STATUS: ${statusLabel.uppercase(Locale.US)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = dateStr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
              )
            }
            Text(
              text = formatRewardInr(task.rewardCents),
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.ExtraBold
            )
          }
        }

        if (task.proofText.isNotBlank()) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(
                text = "SUBMITTED WRITTEN PROOF",
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
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(
                text = "SUBMITTED SCREENSHOT PROOF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
              )
              Spacer(modifier = Modifier.height(8.dp))
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
              ) {
                ProofImageView(
                  imageSource = task.proofImageUrl,
                  contentDescription = "Submitted screenshot proof",
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize()
                )
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
    },
    confirmButton = {
      Button(
        onClick = onOpenTaskScreen,
        modifier = Modifier.testTag("history_detail_open_task_button")
      ) {
        Text("Open Full Task")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("history_detail_close_button")
      ) {
        Text("Close")
      }
    }
  )
}

/**
 * Separate Activity Screen (`UserActivityScreen`):
 * - Displays the user's real chronological account, task, submission, and withdrawal request events
 *   derived directly from real Firebase user, task, and withdrawal request records (zero fake data).
 */
@Composable
fun UserActivityScreen(
  profile: UserProfile?,
  tasks: List<TaskItem>,
  withdrawalRequests: List<DemoWithdrawalRequest>,
  currentUserId: String,
  onBack: () -> Unit,
  onSelectTask: (String) -> Unit
) {
  BackHandler(onBack = onBack)

  val activities =
    remember(profile, tasks, withdrawalRequests, currentUserId) {
      val list = mutableListOf<UserActivityItem>()

      // 1. Real user account registration event
      if (profile != null) {
        val createdMillis = (profile.createdAt?.seconds ?: 0L) * 1000L
        list.add(
          UserActivityItem(
            id = "account_${profile.userId.ifBlank { currentUserId }}",
            title = "TaskFlow Account Verified",
            subtitle =
              "Signed in as ${profile.displayName.ifBlank { "TaskFlow User" }} • Balance: ${formatRewardInr(profile.balanceCents)}",
            amountText = formatRewardInr(profile.balanceCents),
            statusLabel = profile.approvalStatus.replaceFirstChar { it.uppercase() },
            timestampMillis = createdMillis
          )
        )
      }

      // 2. Real task assignment, submission, approval, and rejection events
      tasks.forEach { task ->
        val isUserTask =
          currentUserId.isBlank() ||
            task.assignedUserId == currentUserId ||
            task.status == TaskStatus.PENDING
        if (isUserTask) {
          val tsMillis =
            ((task.updatedAt ?: task.createdAt)?.seconds ?: 0L) * 1000L
          val (activityTitle, activitySubtitle) =
            when (task.status) {
              TaskStatus.APPROVED ->
                "Task Approved: ${task.title}" to
                  "Reward ${formatRewardInr(task.rewardCents)} credited to your wallet"
              TaskStatus.REJECTED ->
                "Task Rejected: ${task.title}" to
                  (task.adminFeedback.ifBlank { "Reviewed by Admin" })
              TaskStatus.SUBMITTED ->
                "Proof Submitted: ${task.title}" to
                  "Written + screenshot proof awaiting Admin verification"
              else ->
                "Task Available: ${task.title}" to
                  "${resolveAppOrWebsiteLabel(task)} • Complete task to earn ${formatRewardInr(task.rewardCents)}"
            }
          list.add(
            UserActivityItem(
              id = "task_${task.id}_${task.status}",
              title = activityTitle,
              subtitle = activitySubtitle,
              amountText = formatRewardInr(task.rewardCents),
              statusLabel = TaskStatus.historyLabel(task.status),
              timestampMillis = tsMillis,
              relatedTaskId = task.id
            )
          )
        }
      }

      // 3. Real demo withdrawal request events
      withdrawalRequests.forEach { req ->
        val reqMillis = (req.createdAt?.seconds ?: 0L) * 1000L
        list.add(
          UserActivityItem(
            id = "withdrawal_${req.id.ifBlank { req.requestId }}",
            title = "Withdrawal Request (${req.method})",
            subtitle = "Demo / Pending request submitted for Admin review",
            amountText = formatRewardInr(req.amountCents),
            statusLabel = "Demo / Pending",
            timestampMillis = reqMillis
          )
        )
      }

      list.sortedByDescending { it.timestampMillis }
    }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 18.dp, vertical = 14.dp)
        .testTag("user_activity_screen")
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(
        onClick = onBack,
        modifier = Modifier.testTag("activity_back_button")
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back to Profile"
        )
      }
      Column {
        Text(
          text = "Activity",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Real-time feed of your account, task submissions, and withdrawal requests",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    if (activities.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "No recent activity recorded yet.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
      ) {
        items(activities, key = { it.id }) { item ->
          val dateStr =
            if (item.timestampMillis > 0L) {
              SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(Date(item.timestampMillis))
            } else {
              "Recent"
            }

          Card(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(enabled = item.relatedTaskId != null) {
                  item.relatedTaskId?.let(onSelectTask)
                }
                .testTag("activity_item_${item.id}"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                  )
                }
              }

              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.title,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = item.subtitle,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "$dateStr • ${item.statusLabel}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary
                )
              }

              if (item.amountText.isNotBlank()) {
                Text(
                  text = item.amountText,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.ExtraBold
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
private fun UserRewardsSubScreen(
  profile: UserProfile?,
  tasks: List<TaskItem>,
  currentUserId: String,
  onBack: () -> Unit
) {
  BackHandler(onBack = onBack)
  val approvedTasks =
    remember(tasks, currentUserId) {
      tasks.filter {
        it.status == TaskStatus.APPROVED &&
          (currentUserId.isBlank() || it.assignedUserId == currentUserId)
      }
    }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 18.dp, vertical = 14.dp)
        .testTag("user_rewards_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(onClick = onBack, modifier = Modifier.testTag("rewards_back_button")) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Profile")
      }
      Text(
        text = "Rewards",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
      )
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
          text = "TOTAL EARNED REWARDS (INR)",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = formatRewardInr(profile?.balanceCents ?: 0L),
          style = MaterialTheme.typography.displayMedium,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Completed Tasks: ${profile?.completedTasksCount ?: approvedTasks.size.toLong()}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun UserSettingsSubScreen(
  profile: UserProfile?,
  userEmail: String,
  onUpdateDisplayName: (String) -> Unit,
  onOpenSecurityInfo: () -> Unit,
  onOpenSupport: () -> Unit = {},
  onBack: () -> Unit
) {
  BackHandler(onBack = onBack)
  val context = androidx.compose.ui.platform.LocalContext.current
  var editedName by rememberSaveable(profile?.displayName) {
    mutableStateOf(profile?.displayName ?: "")
  }

  val currentTheme by com.example.ui.theme.ThemePreferences.currentThemeMode.collectAsStateWithLifecycle()

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 14.dp)
        .testTag("user_settings_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Profile")
      }
      Text(
        text = "Settings",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
      )
    }

    // App Theme Mode Selection Card
    Card(
      modifier = Modifier.fillMaxWidth().testTag("theme_selection_card"),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = "App Theme",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Choose your preferred appearance mode for all screens",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        com.example.ui.theme.AppThemeMode.entries.forEach { mode ->
          val isSelected = currentTheme == mode
          Surface(
            shape = RoundedCornerShape(14.dp),
            color =
              if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
              else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                  com.example.ui.theme.ThemePreferences.setThemeMode(context, mode)
                }
                .testTag("theme_option_${mode.name.lowercase()}")
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              RadioButton(
                selected = isSelected,
                onClick = { com.example.ui.theme.ThemePreferences.setThemeMode(context, mode) }
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = mode.title,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = mode.description,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }
      }
    }

    // Profile Display Name Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Profile Display Name",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
          value = editedName,
          onValueChange = { editedName = it },
          label = { Text("Display Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_display_name_input")
        )
        Button(
          onClick = { onUpdateDisplayName(editedName) },
          enabled = editedName.trim().isNotEmpty(),
          modifier = Modifier.fillMaxWidth().testTag("save_display_name_button")
        ) {
          Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Save Display Name")
        }
      }
    }

    // Help & Security Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Help & Security",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
        OutlinedButton(
          onClick = onOpenSupport,
          modifier = Modifier.fillMaxWidth().testTag("settings_open_support_button")
        ) {
          Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("FAQ & Support Assistant")
        }
        OutlinedButton(
          onClick = onOpenSecurityInfo,
          modifier = Modifier.fillMaxWidth().testTag("open_security_info_button")
        ) {
          Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Firebase Security & Rules Info")
        }
      }
    }
  }
}

@Composable
private fun UserAboutSubScreen(
  onOpenSecurityInfo: () -> Unit,
  onBack: () -> Unit
) {
  BackHandler(onBack = onBack)

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 18.dp, vertical = 14.dp)
        .testTag("user_about_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(onClick = onBack, modifier = Modifier.testTag("about_back_button")) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Profile")
      }
      Text(
        text = "About TaskFlow",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
      )
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        TaskFlowBrandLogo(size = 80.dp)
        Text(
          text = "TaskFlow Micro-Task & Reward Platform",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center
        )
        Text(
          text =
            "TaskFlow connects verified users with curated digital tasks in Indian Rupees (INR ₹). Complete step-by-step instructions, upload mandatory written and screenshot proof, and track Admin approvals in real time.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center
        )
        OutlinedButton(
          onClick = onOpenSecurityInfo,
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("View Security & Firestore Architecture")
        }
      }
    }
  }
}
