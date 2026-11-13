package com.example.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AdminNotificationItem
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.WithdrawalAvailabilityConfig
import com.example.data.model.WithdrawalCatalog
import com.example.data.model.WithdrawalMethodType
import com.example.data.model.WithdrawalStatus
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AdminWithdrawalRequestsSection(
  withdrawalRequests: List<DemoWithdrawalRequest>,
  availabilityConfig: WithdrawalAvailabilityConfig = WithdrawalAvailabilityConfig(),
  adminNotifications: List<AdminNotificationItem> = emptyList(),
  transactions: List<TransactionRecord> = emptyList(),
  isBusy: Boolean = false,
  onAcceptRequest: (requestId: String, targetStatus: String, statusNote: String) -> Unit = { _, _, _ -> },
  onSendGiftCardCode: (
    requestId: String,
    giftCode: String,
    statusNote: String,
    onSuccess: () -> Unit
  ) -> Unit = { _, _, _, _ -> },
  onCompleteUpi: (
    requestId: String,
    paymentReference: String,
    statusNote: String,
    onSuccess: () -> Unit
  ) -> Unit = { _, _, _, _ -> },
  onRejectAndRefund: (
    requestId: String,
    reason: String,
    onSuccess: () -> Unit
  ) -> Unit = { _, _, _ -> },
  onUpdateAvailability: (WithdrawalAvailabilityConfig, onSuccess: () -> Unit) -> Unit = { _, _ -> },
  onMarkAdminNotificationRead: (String) -> Unit = {}
) {
  var methodFilter by rememberSaveable { mutableStateOf("ALL") }
  var statusFilter by rememberSaveable { mutableStateOf("ALL") }
  var selectedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
  var showAvailabilityDialog by rememberSaveable { mutableStateOf(false) }

  val selectedRequest =
    remember(withdrawalRequests, selectedRequestId) {
      val id = selectedRequestId ?: return@remember null
      withdrawalRequests.firstOrNull { (it.id.ifBlank { it.requestId }) == id }
    }

  val unreadAdminNotifications =
    remember(adminNotifications) { adminNotifications.filter { !it.isRead } }

  val activeCount =
    withdrawalRequests.count { WithdrawalStatus.isPendingOrActive(it.status) }
  val delayedCount =
    withdrawalRequests.count { it.isDelayedAt() }
  val completedCount =
    withdrawalRequests.count {
      it.status.equals(WithdrawalStatus.COMPLETED, true) ||
        it.status.equals("approved", true)
    }
  val rejectedCount =
    withdrawalRequests.count { it.status.equals(WithdrawalStatus.REJECTED, true) }

  val totalRequestedCents =
    withdrawalRequests.sumOf {
      if (it.amountCents > 0L) it.amountCents else it.getEffectiveAmountRupees() * 100L
    }
  val totalCompletedCents =
    withdrawalRequests
      .filter {
        it.status.equals(WithdrawalStatus.COMPLETED, true) ||
          it.status.equals("approved", true)
      }
      .sumOf { if (it.amountCents > 0L) it.amountCents else it.getEffectiveAmountRupees() * 100L }

  val filteredRequests =
    remember(withdrawalRequests, methodFilter, statusFilter) {
      withdrawalRequests.filter { req ->
        val matchesMethod =
          when (methodFilter) {
            "UPI" -> WithdrawalMethodType.isUpi(req.method)
            WithdrawalMethodType.AMAZON_GIFT_CARD ->
              req.method.equals(WithdrawalMethodType.AMAZON_GIFT_CARD, true)
            WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD ->
              req.method.equals(WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD, true)
            WithdrawalMethodType.FLIPKART_GIFT_CARD ->
              req.method.equals(WithdrawalMethodType.FLIPKART_GIFT_CARD, true)
            else -> true
          }
        val matchesStatus =
          when (statusFilter) {
            WithdrawalStatus.SUBMITTED ->
              req.status.equals(WithdrawalStatus.SUBMITTED, true) ||
                req.status.contains("pending", true)
            WithdrawalStatus.UNDER_REVIEW ->
              req.status.equals(WithdrawalStatus.UNDER_REVIEW, true)
            WithdrawalStatus.PROCESSING ->
              req.status.equals(WithdrawalStatus.PROCESSING, true)
            WithdrawalStatus.DELAYED -> req.isDelayedAt()
            WithdrawalStatus.COMPLETED ->
              req.status.equals(WithdrawalStatus.COMPLETED, true) ||
                req.status.equals("approved", true)
            WithdrawalStatus.REJECTED ->
              req.status.equals(WithdrawalStatus.REJECTED, true)
            else -> true
          }
        matchesMethod && matchesStatus
      }
    }

  if (showAvailabilityDialog) {
    WithdrawalAvailabilityDialog(
      currentConfig = availabilityConfig,
      isBusy = isBusy,
      onDismiss = { showAvailabilityDialog = false },
      onSave = { updated ->
        onUpdateAvailability(updated) {
          showAvailabilityDialog = false
        }
      }
    )
  }

  selectedRequest?.let { req ->
    val reqId = req.id.ifBlank { req.requestId }
    val relatedTransactions =
      remember(transactions, reqId) {
        transactions.filter {
          it.referenceId == reqId &&
            (it.type == TransactionType.WITHDRAWAL_DEBIT ||
              it.type == TransactionType.WITHDRAWAL_REFUND)
        }
      }
    AdminWithdrawalDetailDialog(
      request = req,
      relatedTransactions = relatedTransactions,
      isBusy = isBusy,
      onDismiss = { selectedRequestId = null },
      onAcceptRequest = { targetStatus, note ->
        onAcceptRequest(reqId, targetStatus, note)
      },
      onSendGiftCardCode = { code, note ->
        onSendGiftCardCode(reqId, code, note) {
          selectedRequestId = null
        }
      },
      onCompleteUpi = { ref, note ->
        onCompleteUpi(reqId, ref, note) {
          selectedRequestId = null
        }
      },
      onRejectAndRefund = { reason ->
        onRejectAndRefund(reqId, reason) {
          selectedRequestId = null
        }
      }
    )
  }

  Card(
    modifier = Modifier.fillMaxWidth().testTag("admin_withdrawal_requests_section"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = AdminWhiteBg),
    border = BorderStroke(1.dp, AdminBorderColor),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Unread Admin Alerts Banner
      if (unreadAdminNotifications.isNotEmpty()) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = AdminAmberTint,
          border = BorderStroke(1.dp, AdminAmberWarning.copy(alpha = 0.45f)),
          modifier = Modifier.fillMaxWidth().testTag("admin_wd_notifications_banner")
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.NotificationsActive,
                contentDescription = null,
                tint = AdminAmberWarning,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = "${unreadAdminNotifications.size} New Withdrawal Alert(s)",
                style = MaterialTheme.typography.titleSmall,
                color = AdminTextDark,
                fontWeight = FontWeight.Bold
              )
            }
            unreadAdminNotifications.take(3).forEach { notif ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = notif.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = AdminTextDark,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = notif.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                TextButton(
                  onClick = { onMarkAdminNotificationRead(notif.id) },
                  modifier = Modifier.testTag("admin_notif_mark_read_${notif.id}")
                ) {
                  Text("Dismiss", fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }
      }

      // Header Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Withdrawals & Gift Cards (${withdrawalRequests.size})",
            style = MaterialTheme.typography.titleMedium,
            color = AdminTextDark,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "Tap any request to review, fulfill manually, or reject & refund",
            style = MaterialTheme.typography.labelSmall,
            color = AdminTextMuted
          )
        }
        OutlinedButton(
          onClick = { showAvailabilityDialog = true },
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.dp, AdminBluePrimary),
          modifier = Modifier.testTag("admin_wd_availability_btn")
        ) {
          Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Configure Availability",
            tint = AdminBluePrimary,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("Availability", color = AdminBluePrimary, fontWeight = FontWeight.Bold)
        }
      }

      // Summary KPI Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = AdminSoftGreyBg,
          border = BorderStroke(1.dp, AdminBorderColor),
          modifier = Modifier.weight(1f)
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text("Total Requested", style = MaterialTheme.typography.labelSmall, color = AdminTextMuted)
            Text(
              text = formatRewardInr(totalRequestedCents),
              style = MaterialTheme.typography.titleSmall,
              color = AdminTextDark,
              fontWeight = FontWeight.ExtraBold
            )
            Text(
              text = "$activeCount Active • $delayedCount Delayed",
              style = MaterialTheme.typography.labelSmall,
              color = if (delayedCount > 0) AdminAmberWarning else AdminTextSecondary
            )
          }
        }
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = AdminSoftGreyBg,
          border = BorderStroke(1.dp, AdminBorderColor),
          modifier = Modifier.weight(1f)
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text("Completed Payouts", style = MaterialTheme.typography.labelSmall, color = AdminTextMuted)
            Text(
              text = formatRewardInr(totalCompletedCents),
              style = MaterialTheme.typography.titleSmall,
              color = AdminGreenSuccess,
              fontWeight = FontWeight.ExtraBold
            )
            Text(
              text = "$completedCount Completed • $rejectedCount Rejected",
              style = MaterialTheme.typography.labelSmall,
              color = AdminTextSecondary
            )
          }
        }
      }

      // Method Filters
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        listOf(
          "ALL" to "All Methods",
          "UPI" to "UPI",
          WithdrawalMethodType.AMAZON_GIFT_CARD to "Amazon",
          WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD to "Google Play",
          WithdrawalMethodType.FLIPKART_GIFT_CARD to "Flipkart"
        ).forEach { (key, label) ->
          FilterChip(
            selected = methodFilter == key,
            onClick = { methodFilter = key },
            label = { Text(label) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = AdminBluePrimary,
                selectedLabelColor = Color.White
              ),
            modifier = Modifier.testTag("admin_wd_method_filter_$key")
          )
        }
      }

      // Status Filters
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        listOf(
          "ALL" to "All (${withdrawalRequests.size})",
          WithdrawalStatus.SUBMITTED to "Submitted",
          WithdrawalStatus.UNDER_REVIEW to "Under Review",
          WithdrawalStatus.PROCESSING to "Processing",
          WithdrawalStatus.DELAYED to "Delayed ($delayedCount)",
          WithdrawalStatus.COMPLETED to "Completed ($completedCount)",
          WithdrawalStatus.REJECTED to "Rejected ($rejectedCount)"
        ).forEach { (key, label) ->
          FilterChip(
            selected = statusFilter == key,
            onClick = { statusFilter = key },
            label = { Text(label) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = AdminBlueDark,
                selectedLabelColor = Color.White
              ),
            modifier = Modifier.testTag("admin_wd_status_filter_$key")
          )
        }
      }

      if (filteredRequests.isEmpty()) {
        Text(
          text = "No withdrawal requests match the selected filters.",
          style = MaterialTheme.typography.bodyMedium,
          color = AdminTextMuted,
          modifier = Modifier.padding(vertical = 8.dp)
        )
      } else {
        filteredRequests.forEach { req ->
          val reqId = req.id.ifBlank { req.requestId }
          val dateStr =
            req.createdAt?.toDate()?.let {
              SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
            } ?: "Recently submitted"
          val coinsVal = req.getEffectiveAmountCoins()
          val displayStatus =
            if (req.status.equals("pending_demo", true)) "Demo / Pending" else req.getDisplayStatus()
          val (statusBg, statusFg) =
            when {
              req.status.equals(WithdrawalStatus.COMPLETED, true) ||
                req.status.equals("approved", true) -> AdminGreenTint to AdminGreenSuccess
              req.status.equals(WithdrawalStatus.REJECTED, true) ->
                AdminRedTint to AdminRedDanger
              req.isDelayedAt() -> AdminAmberTint to AdminAmberWarning
              req.status.equals(WithdrawalStatus.UNDER_REVIEW, true) ||
                req.status.equals(WithdrawalStatus.PROCESSING, true) ->
                AdminBlueTint to AdminBlueDark
              else -> AdminAmberTint to AdminAmberWarning
            }
          val amountDisplay =
            if (req.amountCents > 0L) formatRewardInr(req.amountCents)
            else "₹${req.getEffectiveAmountRupees()}"

          Surface(
            shape = RoundedCornerShape(14.dp),
            color = AdminSoftGreyBg,
            border = BorderStroke(1.dp, AdminBorderColor),
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { selectedRequestId = reqId }
                .testTag("admin_withdrawal_card_$reqId")
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
              ) {
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = AdminBlueTint,
                  modifier = Modifier.size(40.dp)
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector =
                        when {
                          WithdrawalMethodType.isUpi(req.method) -> Icons.Default.QrCode2
                          WithdrawalMethodType.isGiftCard(req.method) -> Icons.Default.CardGiftcard
                          else -> Icons.Default.AccountBalance
                        },
                      contentDescription = null,
                      tint = AdminBluePrimary,
                      modifier = Modifier.size(20.dp)
                    )
                  }
                }
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = req.userName.ifBlank { "TaskFlow User" },
                    style = MaterialTheme.typography.titleMedium,
                    color = AdminTextDark,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("admin_withdrawal_user_$reqId")
                  )
                  Text(
                    text = "${req.getEffectiveMethodLabel()} • $dateStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminTextSecondary,
                    modifier = Modifier.testTag("admin_withdrawal_method_$reqId")
                  )
                  Text(
                    text = "ID: $reqId • ${formatCoins(coinsVal)}" +
                      if (req.upiId.isNotBlank()) " • UPI: ${req.upiId}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = AdminTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Surface(
                      shape = RoundedCornerShape(50),
                      color = statusBg,
                      modifier = Modifier.testTag("admin_withdrawal_status_$reqId")
                    ) {
                      Text(
                        text = displayStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusFg,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                      )
                    }
                    if (req.coinsRefunded) {
                      Surface(
                        shape = RoundedCornerShape(50),
                        color = AdminBlueTint
                      ) {
                        Text(
                          text = "Refunded",
                          style = MaterialTheme.typography.labelSmall,
                          color = AdminBlueDark,
                          fontWeight = FontWeight.Bold,
                          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                      }
                    }
                  }
                }
              }
              Text(
                text = amountDisplay,
                style = MaterialTheme.typography.titleLarge,
                color = AdminBluePrimary,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.testTag("admin_withdrawal_amount_$reqId")
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AdminWithdrawalDetailDialog(
  request: DemoWithdrawalRequest,
  relatedTransactions: List<TransactionRecord>,
  isBusy: Boolean,
  onDismiss: () -> Unit,
  onAcceptRequest: (targetStatus: String, statusNote: String) -> Unit,
  onSendGiftCardCode: (giftCode: String, statusNote: String) -> Unit,
  onCompleteUpi: (paymentReference: String, statusNote: String) -> Unit,
  onRejectAndRefund: (reason: String) -> Unit
) {
  val reqId = request.id.ifBlank { request.requestId }
  val isTerminal = WithdrawalStatus.isTerminal(request.status)
  val isGiftCard = WithdrawalMethodType.isGiftCard(request.method)
  val isUpi = WithdrawalMethodType.isUpi(request.method)

  var statusNote by rememberSaveable(reqId) { mutableStateOf(request.statusNote) }
  var giftCodeInput by rememberSaveable(reqId) { mutableStateOf("") }
  var upiPaymentRef by rememberSaveable(reqId) { mutableStateOf(request.paymentReference) }
  var upiTransferConfirmed by rememberSaveable(reqId) { mutableStateOf(false) }
  var rejectionReason by rememberSaveable(reqId) { mutableStateOf("") }

  val dateStr =
    request.createdAt?.toDate()?.let {
      SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
    } ?: "Recently submitted"

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("admin_withdrawal_detail_dialog"),
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "${request.getEffectiveMethodLabel()} • ₹${request.getEffectiveAmountRupees()}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "Status: ${request.getDisplayStatus()}",
            style = MaterialTheme.typography.labelMedium,
            color = AdminBluePrimary,
            fontWeight = FontWeight.Bold
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = "Close")
        }
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = AdminSoftGreyBg,
          border = BorderStroke(1.dp, AdminBorderColor),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            DetailKvRow("User", "${request.userName} (${request.userId})")
            DetailKvRow("Request ID", reqId, monospace = true)
            DetailKvRow("Submitted", dateStr)
            DetailKvRow("Coins Deducted", formatCoins(request.getEffectiveAmountCoins()))
            if (request.upiId.isNotBlank()) {
              DetailKvRow("UPI ID", request.upiId, monospace = true)
            }
            if (request.paymentReference.isNotBlank()) {
              DetailKvRow("Payment Ref / UTR", request.paymentReference, monospace = true)
            }
            if (request.giftCode.isNotBlank()) {
              DetailKvRow("Delivered Code", request.giftCode, monospace = true)
            }
            DetailKvRow("Refunded", if (request.coinsRefunded) "Yes" else "No")
            if (request.adminActionBy.isNotBlank()) {
              DetailKvRow("Action By", request.adminActionBy)
            }
            if (request.statusNote.isNotBlank()) {
              DetailKvRow("Note", request.statusNote)
            }
          }
        }

        if (relatedTransactions.isNotEmpty()) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = AdminBlueTint,
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Text(
                text = "COIN LEDGER AUDIT (${relatedTransactions.size})",
                style = MaterialTheme.typography.labelSmall,
                color = AdminBlueDark,
                fontWeight = FontWeight.Bold
              )
              relatedTransactions.forEach { tx ->
                Text(
                  text = "• ${tx.type}: ${formatCoins(tx.getEffectiveAmountCoins())} (${tx.id})",
                  style = MaterialTheme.typography.labelSmall,
                  color = AdminTextDark
                )
              }
            }
          }
        }

        if (isTerminal) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = AdminSurfaceGrey,
            border = BorderStroke(1.dp, AdminBorderColor),
            modifier = Modifier.fillMaxWidth().testTag("admin_wd_terminal_notice")
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = AdminTextSecondary,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = "Finalized — Terminal State (${request.getDisplayStatus()}). Further transitions and refunds are locked.",
                style = MaterialTheme.typography.bodySmall,
                color = AdminTextSecondary,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        } else {
          OutlinedTextField(
            value = statusNote,
            onValueChange = { statusNote = it },
            label = { Text("Status / Admin Note (Optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("admin_wd_status_note_input")
          )

          val isAlreadyUnderReview =
            request.status.equals(WithdrawalStatus.UNDER_REVIEW, ignoreCase = true)
          val isAlreadyProcessing =
            request.status.equals(WithdrawalStatus.PROCESSING, ignoreCase = true)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = { onAcceptRequest(WithdrawalStatus.UNDER_REVIEW, statusNote.trim()) },
              enabled = !isBusy && !isAlreadyUnderReview && !isAlreadyProcessing,
              modifier = Modifier.weight(1f).testTag("admin_wd_accept_btn")
            ) {
              Text("Accept (Review)", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
              onClick = { onAcceptRequest(WithdrawalStatus.PROCESSING, statusNote.trim()) },
              enabled = !isBusy && !isAlreadyProcessing,
              modifier = Modifier.weight(1f).testTag("admin_wd_processing_btn")
            ) {
              Text("Processing", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }

          HorizontalDivider()

          if (isGiftCard) {
            Text(
              text = "Manual Gift Card Code Delivery",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = AdminTextDark
            )
            OutlinedTextField(
              value = giftCodeInput,
              onValueChange = { giftCodeInput = it },
              label = { Text("Enter Gift Code") },
              placeholder = { Text("Paste valid voucher / gift code") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("admin_wd_gift_code_input")
            )
            Button(
              onClick = { onSendGiftCardCode(giftCodeInput.trim(), statusNote.trim()) },
              enabled = !isBusy && giftCodeInput.trim().isNotEmpty(),
              colors = ButtonDefaults.buttonColors(containerColor = AdminBluePrimary),
              modifier = Modifier.fillMaxWidth().testTag("admin_wd_send_code_btn")
            ) {
              Icon(Icons.Default.CardGiftcard, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Send Gift Code", fontWeight = FontWeight.Bold)
            }
          } else if (isUpi) {
            Text(
              text = "Manual UPI Payout Confirmation",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = AdminTextDark
            )
            OutlinedTextField(
              value = upiPaymentRef,
              onValueChange = { upiPaymentRef = it },
              label = { Text("UPI UTR / Payment Reference (Optional)") },
              placeholder = { Text("e.g., UTR1234567890") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("admin_wd_upi_ref_input")
            )
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .clickable { upiTransferConfirmed = !upiTransferConfirmed },
              verticalAlignment = Alignment.CenterVertically
            ) {
              Checkbox(
                checked = upiTransferConfirmed,
                onCheckedChange = { upiTransferConfirmed = it },
                modifier = Modifier.testTag("admin_wd_upi_confirm_checkbox")
              )
              Text(
                text = "I confirm manual UPI transfer is completed",
                style = MaterialTheme.typography.bodySmall,
                color = AdminTextDark
              )
            }
            Button(
              onClick = { onCompleteUpi(upiPaymentRef.trim(), statusNote.trim()) },
              enabled = !isBusy && upiTransferConfirmed,
              colors = ButtonDefaults.buttonColors(containerColor = AdminGreenSuccess),
              modifier = Modifier.fillMaxWidth().testTag("admin_wd_complete_upi_btn")
            ) {
              Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Mark Payment Completed", fontWeight = FontWeight.Bold)
            }
          }

          HorizontalDivider()

          Text(
            text = "Reject & Atomic Single Refund",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AdminRedDanger
          )
          OutlinedTextField(
            value = rejectionReason,
            onValueChange = { rejectionReason = it },
            label = { Text("Mandatory Rejection Reason") },
            placeholder = { Text("Explain why request is rejected") },
            modifier = Modifier.fillMaxWidth().testTag("admin_wd_reject_reason_input")
          )
          Button(
            onClick = { onRejectAndRefund(rejectionReason.trim()) },
            enabled = !isBusy && rejectionReason.trim().isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = AdminRedDanger),
            modifier = Modifier.fillMaxWidth().testTag("admin_wd_reject_refund_btn")
          ) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reject & Refund Coins", fontWeight = FontWeight.Bold)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close")
      }
    }
  )
}

@Composable
private fun DetailKvRow(
  label: String,
  value: String,
  monospace: Boolean = false
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = AdminTextMuted,
      modifier = Modifier.padding(end = 12.dp)
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
      fontWeight = FontWeight.SemiBold,
      color = AdminTextDark
    )
  }
}

@Composable
private fun WithdrawalAvailabilityDialog(
  currentConfig: WithdrawalAvailabilityConfig,
  isBusy: Boolean,
  onDismiss: () -> Unit,
  onSave: (WithdrawalAvailabilityConfig) -> Unit
) {
  var upiEnabled by rememberSaveable { mutableStateOf(currentConfig.upiEnabled) }
  var amazonEnabled by rememberSaveable { mutableStateOf(currentConfig.amazonEnabled) }
  var googlePlayEnabled by rememberSaveable { mutableStateOf(currentConfig.googlePlayEnabled) }
  var flipkartEnabled by rememberSaveable { mutableStateOf(currentConfig.flipkartEnabled) }

  var upiDenoms by remember { mutableStateOf(currentConfig.upiDenominationsEnabled) }
  var amazonDenoms by remember { mutableStateOf(currentConfig.amazonDenominationsEnabled) }
  var googlePlayDenoms by remember { mutableStateOf(currentConfig.googlePlayDenominationsEnabled) }
  var flipkartDenoms by remember { mutableStateOf(currentConfig.flipkartDenominationsEnabled) }

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("admin_wd_availability_dialog"),
    title = {
      Text(
        text = "Withdrawal Availability Controls",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        AvailabilityMethodGroup(
          title = "UPI",
          enabled = upiEnabled,
          onToggleMethod = { upiEnabled = it },
          denominations = WithdrawalCatalog.UPI_DENOMINATIONS.map { it.amountRupees.toString() },
          denomMap = upiDenoms,
          onToggleDenom = { key, v -> upiDenoms = upiDenoms.toMutableMap().apply { put(key, v) } },
          tagPrefix = "upi"
        )
        AvailabilityMethodGroup(
          title = "Amazon Gift Card",
          enabled = amazonEnabled,
          onToggleMethod = { amazonEnabled = it },
          denominations = WithdrawalCatalog.GIFT_CARD_DENOMINATIONS.map { it.amountRupees.toString() },
          denomMap = amazonDenoms,
          onToggleDenom = { key, v -> amazonDenoms = amazonDenoms.toMutableMap().apply { put(key, v) } },
          tagPrefix = "amazon"
        )
        AvailabilityMethodGroup(
          title = "Google Play Gift Code",
          enabled = googlePlayEnabled,
          onToggleMethod = { googlePlayEnabled = it },
          denominations = WithdrawalCatalog.GIFT_CARD_DENOMINATIONS.map { it.amountRupees.toString() },
          denomMap = googlePlayDenoms,
          onToggleDenom = { key, v -> googlePlayDenoms = googlePlayDenoms.toMutableMap().apply { put(key, v) } },
          tagPrefix = "google_play"
        )
        AvailabilityMethodGroup(
          title = "Flipkart Gift Card",
          enabled = flipkartEnabled,
          onToggleMethod = { flipkartEnabled = it },
          denominations = WithdrawalCatalog.GIFT_CARD_DENOMINATIONS.map { it.amountRupees.toString() },
          denomMap = flipkartDenoms,
          onToggleDenom = { key, v -> flipkartDenoms = flipkartDenoms.toMutableMap().apply { put(key, v) } },
          tagPrefix = "flipkart"
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            WithdrawalAvailabilityConfig(
              upiEnabled = upiEnabled,
              amazonEnabled = amazonEnabled,
              googlePlayEnabled = googlePlayEnabled,
              flipkartEnabled = flipkartEnabled,
              upiDenominationsEnabled = upiDenoms,
              amazonDenominationsEnabled = amazonDenoms,
              googlePlayDenominationsEnabled = googlePlayDenoms,
              flipkartDenominationsEnabled = flipkartDenoms
            )
          )
        },
        enabled = !isBusy,
        modifier = Modifier.testTag("admin_wd_save_availability_btn")
      ) {
        Text("Save Settings")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

@Composable
private fun AvailabilityMethodGroup(
  title: String,
  enabled: Boolean,
  onToggleMethod: (Boolean) -> Unit,
  denominations: List<String>,
  denomMap: Map<String, Boolean>,
  onToggleDenom: (String, Boolean) -> Unit,
  tagPrefix: String
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = AdminSoftGreyBg,
    border = BorderStroke(1.dp, AdminBorderColor),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Switch(
          checked = enabled,
          onCheckedChange = onToggleMethod,
          colors = SwitchDefaults.colors(checkedTrackColor = AdminBluePrimary),
          modifier = Modifier.testTag("avail_switch_$tagPrefix")
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        denominations.forEach { key ->
          val isDenomEnabled = denomMap[key] ?: true
          FilterChip(
            selected = isDenomEnabled,
            onClick = { onToggleDenom(key, !isDenomEnabled) },
            enabled = enabled,
            label = { Text("₹$key") },
            modifier = Modifier.testTag("avail_denom_${tagPrefix}_$key")
          )
        }
      }
    }
  }
}
