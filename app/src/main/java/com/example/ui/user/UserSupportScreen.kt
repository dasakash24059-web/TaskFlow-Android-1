package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.data.model.SupportTicket
import com.example.data.model.SupportTicketStatus
import com.example.data.model.TaskFlowFaqData
import com.example.data.model.TaskFlowFaqItem
import com.example.ui.theme.taskFlowColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

data class ChatMessage(
  val id: String,
  val text: String,
  val isUser: Boolean,
  val relatedFaq: TaskFlowFaqItem? = null
)

@Composable
fun UserSupportScreen(
  onBack: () -> Unit,
  onNavigateScreen: (String) -> Unit,
  onSubmitTicket: ((String, String, String, (Boolean) -> Unit) -> Unit)? = null,
  supportTickets: List<SupportTicket> = emptyList(),
  onReplyToTicket: ((String, String, (Boolean) -> Unit) -> Unit)? = null
) {
  BackHandler(onBack = onBack)
  var selectedTab by rememberSaveable { mutableStateOf(0) } // 0: FAQ, 1: Assistant, 2: Support Requests

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .testTag("user_support_screen")
  ) {
    // Clean Header
    Surface(
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("support_back_button")) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Help & Support",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = "Submit support requests & view admin replies",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }

    TabRow(
      selectedTabIndex = selectedTab,
      containerColor = MaterialTheme.colorScheme.surface
    ) {
      Tab(
        selected = selectedTab == 0,
        onClick = { selectedTab = 0 },
        text = { Text("FAQ", fontWeight = FontWeight.SemiBold) },
        icon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.testTag("tab_faq_browse")
      )
      Tab(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
        text = { Text("Assistant", fontWeight = FontWeight.SemiBold) },
        icon = { Icon(Icons.Default.QuestionAnswer, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.testTag("tab_assistant_chat")
      )
      Tab(
        selected = selectedTab == 2,
        onClick = { selectedTab = 2 },
        text = {
          val unreadReplies = supportTickets.count { it.userUnread }
          Text(
            text = if (unreadReplies > 0) "Support ($unreadReplies)" else "Support",
            fontWeight = FontWeight.SemiBold
          )
        },
        icon = { Icon(Icons.Default.SupportAgent, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.testTag("tab_contact_support")
      )
    }

    when (selectedTab) {
      0 -> FaqBrowseView(onNavigateScreen = onNavigateScreen)
      1 -> SupportAssistantChatView(onNavigateScreen = onNavigateScreen)
      else ->
        ContactSupportTicketView(
          onSubmitTicket = onSubmitTicket,
          supportTickets = supportTickets,
          onReplyToTicket = onReplyToTicket
        )
    }
  }
}

@Composable
private fun ContactSupportTicketView(
  onSubmitTicket: ((String, String, String, (Boolean) -> Unit) -> Unit)?,
  supportTickets: List<SupportTicket>,
  onReplyToTicket: ((String, String, (Boolean) -> Unit) -> Unit)?
) {
  val tfColors = taskFlowColors()
  var subject by rememberSaveable { mutableStateOf("") }
  var category by rememberSaveable { mutableStateOf("Task Issue") }
  var message by rememberSaveable { mutableStateOf("") }
  var isSubmitting by rememberSaveable { mutableStateOf(false) }
  var isSubmittedBannerVisible by rememberSaveable { mutableStateOf(false) }
  var submitError by rememberSaveable { mutableStateOf<String?>(null) }
  var selectedTicketId by rememberSaveable { mutableStateOf<String?>(null) }
  var replyText by rememberSaveable { mutableStateOf("") }
  var isSendingReply by rememberSaveable { mutableStateOf(false) }
  var replyFeedback by rememberSaveable { mutableStateOf<String?>(null) }

  val categories = listOf("Task Issue", "Reward / Wallet", "Account Approval", "App Bug", "Other")
  val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US) }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // 1. Create Support Request Section
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "New Support Request",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = "Describe your issue clearly and our support team will respond here.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
          text = "Category",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          categories.forEach { cat ->
            FilterChip(
              selected = category == cat,
              onClick = { category = cat },
              label = { Text(cat) }
            )
          }
        }

        OutlinedTextField(
          value = subject,
          onValueChange = {
            subject = it
            isSubmittedBannerVisible = false
          },
          label = { Text("Subject") },
          placeholder = { Text("Brief summary of your issue") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("support_subject_input"),
          shape = RoundedCornerShape(10.dp)
        )

        OutlinedTextField(
          value = message,
          onValueChange = {
            message = it
            isSubmittedBannerVisible = false
          },
          label = { Text("Detailed Description") },
          placeholder = { Text("Explain what happened, task title, or transaction details...") },
          modifier = Modifier.fillMaxWidth().testTag("support_message_input"),
          minLines = 3,
          shape = RoundedCornerShape(10.dp)
        )

        if (!submitError.isNullOrBlank()) {
          Text(
            text = submitError.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
          )
        }

        if (isSubmittedBannerVisible) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
              Text(
                text = "Inquiry Submitted!",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "Your support request has been submitted. You can track its status and replies below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium
              )
            }
          }
        }

        Button(
          onClick = {
            val handler = onSubmitTicket
            if (handler == null) {
              submitError = "Support service is temporarily unavailable."
            } else {
              submitError = null
              isSubmitting = true
              handler.invoke(subject.trim(), message.trim(), category) { success ->
                isSubmitting = false
                if (success) {
                  subject = ""
                  message = ""
                  isSubmittedBannerVisible = true
                } else {
                  submitError = "Could not submit your inquiry. Please try again."
                }
              }
            }
          },
          enabled = !isSubmitting && subject.trim().isNotBlank() && message.trim().isNotBlank(),
          modifier =
            Modifier.fillMaxWidth()
              .height(48.dp)
              .testTag("submit_support_ticket_button"),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text(
            text = if (isSubmitting) "Submitting..." else "Submit Support Request",
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    }

    // 2. My Submitted Support Requests & Conversation History
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
      modifier = Modifier.fillMaxWidth().testTag("user_support_tickets_section")
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
          Text(
            text = "My Support Requests (${supportTickets.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
        }

        if (supportTickets.isEmpty()) {
          Text(
            text = "You haven't submitted any support requests yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
          )
        } else {
          supportTickets.forEach { ticket ->
            val isExpanded = selectedTicketId == ticket.id
            val normalizedStatus = SupportTicketStatus.normalize(ticket.status)
            val statusColor =
              when (normalizedStatus) {
                SupportTicketStatus.RESOLVED -> tfColors.goldText
                SupportTicketStatus.IN_PROGRESS -> tfColors.goldAccent
                else -> MaterialTheme.colorScheme.primary
              }
            val createdLabel =
              ticket.createdAt?.toDate()?.let { dateFormat.format(it) } ?: "Recently"

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
              border =
                BorderStroke(
                  1.dp,
                  if (ticket.userUnread) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                  } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                  }
                ),
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .clickable {
                    selectedTicketId = if (isExpanded) null else ticket.id
                    replyFeedback = null
                  }
                  .testTag("support_ticket_item_${ticket.id}")
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
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                      Text(
                        text = ticket.subject.ifBlank { "Support Request" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                      )
                      if (ticket.userUnread) {
                        Surface(
                          shape = RoundedCornerShape(50),
                          color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                          Text(
                            text = "New Reply",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                          )
                        }
                      }
                    }
                    Text(
                      text = "${ticket.category.ifBlank { "General" }} • $createdLabel",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }

                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f))
                  ) {
                    Text(
                      text = normalizedStatus.replace('_', ' '),
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.SemiBold,
                      color = statusColor,
                      modifier =
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                          .testTag("support_ticket_status_${ticket.id}")
                    )
                  }
                }

                if (!isExpanded) {
                  Text(
                    text = ticket.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                  )
                  if (ticket.adminReply.isNotBlank()) {
                    Text(
                      text = "Support Reply: ${ticket.adminReply}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.Medium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                } else {
                  HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                  )
                  // Conversation History
                  if (ticket.messages.isNotEmpty()) {
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      ticket.messages.forEach { msg ->
                        val isAdminMsg = msg.senderRole.equals("admin", ignoreCase = true)
                        Surface(
                          shape = RoundedCornerShape(10.dp),
                          color =
                            if (isAdminMsg) {
                              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                              MaterialTheme.colorScheme.surface
                            },
                          border =
                            BorderStroke(
                              1.dp,
                              MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                          modifier = Modifier.fillMaxWidth()
                        ) {
                          Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                          ) {
                            Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.SpaceBetween,
                              verticalAlignment = Alignment.CenterVertically
                            ) {
                              Text(
                                text = if (isAdminMsg) "Support Team" else msg.senderName.ifBlank { "You" },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color =
                                  if (isAdminMsg) {
                                    MaterialTheme.colorScheme.primary
                                  } else {
                                    MaterialTheme.colorScheme.onSurface
                                  }
                              )
                              if (msg.createdAtMillis > 0L) {
                                Text(
                                  text = dateFormat.format(Date(msg.createdAtMillis)),
                                  style = MaterialTheme.typography.labelSmall,
                                  color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                              }
                            }
                            Text(
                              text = msg.message,
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurface
                            )
                          }
                        }
                      }
                    }
                  } else {
                    Text(
                      text = ticket.message,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                    if (ticket.adminReply.isNotBlank()) {
                      Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        border =
                          BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                          ),
                        modifier = Modifier.fillMaxWidth()
                      ) {
                        Column(
                          modifier = Modifier.fillMaxWidth().padding(10.dp),
                          verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                          Text(
                            text = "Support Team",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                          )
                          Text(
                            text = ticket.adminReply,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                          )
                        }
                      }
                    }
                  }

                  // Follow-up reply input
                  if (onReplyToTicket != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                      value = replyText,
                      onValueChange = { replyText = it },
                      label = { Text("Add a follow-up reply") },
                      placeholder = { Text("Write a response to support...") },
                      modifier =
                        Modifier.fillMaxWidth()
                          .testTag("support_ticket_reply_input_${ticket.id}"),
                      minLines = 2,
                      shape = RoundedCornerShape(10.dp)
                    )
                    if (!replyFeedback.isNullOrBlank()) {
                      Text(
                        text = replyFeedback.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                      )
                    }
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.End
                    ) {
                      OutlinedButton(
                        onClick = {
                          val cleanReply = replyText.trim()
                          if (cleanReply.isNotEmpty()) {
                            isSendingReply = true
                            onReplyToTicket(ticket.id, cleanReply) { ok ->
                              isSendingReply = false
                              if (ok) {
                                replyText = ""
                                replyFeedback = "Reply sent."
                              } else {
                                replyFeedback = "Could not send reply. Please try again."
                              }
                            }
                          }
                        },
                        enabled = !isSendingReply && replyText.trim().isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("support_ticket_reply_btn_${ticket.id}")
                      ) {
                        Text(if (isSendingReply) "Sending..." else "Send Reply")
                      }
                    }
                  }
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
private fun FaqBrowseView(onNavigateScreen: (String) -> Unit) {
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var selectedCategory by rememberSaveable { mutableStateOf("All") }
  val expandedItems = remember { mutableStateListOf<String>() }

  val categories = remember {
    listOf("All") + TaskFlowFaqData.ITEMS.map { it.category }.distinct()
  }

  val filteredFaqs =
    remember(searchQuery, selectedCategory) {
      TaskFlowFaqData.ITEMS.filter { item ->
        val matchesCategory = selectedCategory == "All" || item.category == selectedCategory
        val matchesSearch =
          searchQuery.isBlank() ||
            item.question.contains(searchQuery, ignoreCase = true) ||
            item.answer.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesSearch
      }
    }

  Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      label = { Text("Search questions, topics...") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("faq_search_input"),
      shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(10.dp))

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      categories.forEach { cat ->
        FilterChip(
          selected = selectedCategory == cat,
          onClick = { selectedCategory = cat },
          label = { Text(cat) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
          modifier = Modifier.testTag("faq_chip_$cat")
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    if (filteredFaqs.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "No matching questions found.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Switch to the 'Support' tab to send a request directly to our team.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
      ) {
        items(filteredFaqs, key = { it.id }) { item ->
          val isExpanded = expandedItems.contains(item.id)
          Card(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                  if (isExpanded) expandedItems.remove(item.id) else expandedItems.add(item.id)
                }
                .testTag("faq_card_${item.id}"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
          ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = item.question,
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.weight(1f)
                )
                Icon(
                  imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                  contentDescription = if (isExpanded) "Collapse" else "Expand",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }

              if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                  text = item.answer,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.actionLabel != null && item.targetScreen != null) {
                  Spacer(modifier = Modifier.height(10.dp))
                  Button(
                    onClick = { onNavigateScreen(item.targetScreen) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("faq_action_${item.id}")
                  ) {
                    Text(item.actionLabel)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                  }
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
private fun SupportAssistantChatView(onNavigateScreen: (String) -> Unit) {
  val messages = remember {
    mutableStateListOf(
      ChatMessage(
        id = "welcome_1",
        text =
          "Welcome to TaskFlow Support! Select a topic below or use the Support tab to open a direct ticket with our team.",
        isUser = false
      )
    )
  }

  var inputText by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  fun sendQuery(query: String) {
    val clean = query.trim()
    if (clean.isBlank()) return
    messages.add(ChatMessage(id = "user_${System.currentTimeMillis()}", text = clean, isUser = true))
    inputText = ""

    val answerItem = TaskFlowFaqData.findAnswer(clean)
    if (answerItem != null) {
      messages.add(
        ChatMessage(
          id = "bot_${System.currentTimeMillis()}",
          text = "${answerItem.question}\n\n${answerItem.answer}",
          isUser = false,
          relatedFaq = answerItem
        )
      )
    } else {
      messages.add(
        ChatMessage(
          id = "bot_${System.currentTimeMillis()}",
          text =
            "I couldn't find a direct match in the FAQ. Please open the 'Support' tab to submit a ticket to our team.",
          isUser = false
        )
      )
    }

    scope.launch {
      listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
    }
  }

  Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
    Text(
      text = "Common Topics:",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      TaskFlowFaqData.COMMON_QUESTIONS.forEach { q ->
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
          modifier =
            Modifier.clip(RoundedCornerShape(20.dp))
              .clickable { sendQuery(q) }
              .testTag("suggested_q_${q.take(15)}")
        ) {
          Text(
            text = q,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = PaddingValues(vertical = 8.dp)
    ) {
      items(messages, key = { it.id }) { msg ->
        val bubbleColor =
          if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val textColor =
          if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        val alignment = if (msg.isUser) Alignment.End else Alignment.Start

        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = alignment
        ) {
          Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth(0.92f)
          ) {
            if (!msg.isUser) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(32.dp).padding(end = 4.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    Icons.Default.SupportAgent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                  )
                }
              }
            }

            Surface(
              shape =
                RoundedCornerShape(
                  topStart = 14.dp,
                  topEnd = 14.dp,
                  bottomStart = if (msg.isUser) 14.dp else 4.dp,
                  bottomEnd = if (msg.isUser) 4.dp else 14.dp
                ),
              color = bubbleColor,
              modifier = Modifier.padding(horizontal = 4.dp).testTag(if (msg.isUser) "chat_bubble_user" else "chat_bubble_bot")
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(text = msg.text, style = MaterialTheme.typography.bodyMedium, color = textColor)
                if (msg.relatedFaq?.actionLabel != null && msg.relatedFaq.targetScreen != null) {
                  Spacer(modifier = Modifier.height(8.dp))
                  Button(
                    onClick = { onNavigateScreen(msg.relatedFaq.targetScreen) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("chat_action_btn")
                  ) {
                    Text(msg.relatedFaq.actionLabel)
                  }
                }
              }
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = inputText,
        onValueChange = { inputText = it },
        placeholder = { Text("Ask a question...") },
        singleLine = true,
        modifier = Modifier.weight(1f).testTag("chat_input_field"),
        shape = RoundedCornerShape(24.dp)
      )
      IconButton(
        onClick = { sendQuery(inputText) },
        enabled = inputText.isNotBlank(),
        modifier =
          Modifier.size(48.dp)
            .clip(CircleShape)
            .background(if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .testTag("chat_send_button")
      ) {
        Icon(
          Icons.AutoMirrored.Filled.Send,
          contentDescription = "Send",
          tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}
