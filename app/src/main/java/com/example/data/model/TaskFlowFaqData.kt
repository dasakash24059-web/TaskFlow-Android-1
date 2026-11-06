package com.example.data.model

data class TaskFlowFaqItem(
  val id: String,
  val question: String,
  val answer: String,
  val category: String,
  val actionLabel: String? = null,
  val targetScreen: String? = null
)

object TaskFlowFaqData {
  val ITEMS: List<TaskFlowFaqItem> =
    listOf(
      TaskFlowFaqItem(
        id = "faq_account_signin",
        question = "How do I create an account and sign in?",
        answer =
          "TaskFlow uses secure Google Sign-In powered by Android Credential Manager. Tap 'Continue with Google', choose your verified Google account from the system bottom sheet, and you are immediately signed in with zero passwords required.",
        category = "Authentication"
      ),
      TaskFlowFaqItem(
        id = "faq_admin_vs_user",
        question = "What is the difference between Admin and Normal User accounts?",
        answer =
          "Admin accounts are authorized through our secure server-verified /admins registry to publish tasks, review proof submissions, approve or suspend users, and manage rewards. Normal User accounts can browse available tasks, complete instructions, submit text + photo proof, and track INR wallet rewards.",
        category = "Account"
      ),
      TaskFlowFaqItem(
        id = "faq_available_tasks",
        question = "How do I find Available Tasks?",
        answer =
          "Tap the 'Tasks' tab in the bottom navigation bar or click 'Browse Available Tasks' from the Home screen hero banner. Active tasks published by administrators appear instantly with their title, reward amount in INR (₹), and category.",
        category = "Tasks",
        actionLabel = "Browse Available Tasks",
        targetScreen = "tracking"
      ),
      TaskFlowFaqItem(
        id = "faq_task_instructions",
        question = "How do I open Task Details and follow instructions?",
        answer =
          "Tap 'View Task' on any task card. The Task Details screen provides step-by-step instructions, the destination link (e.g., Google Play Store or website), the required proof guidelines, and the 'Start Task / Open Link' button to launch the target app or browser directly.",
        category = "Tasks"
      ),
      TaskFlowFaqItem(
        id = "faq_submit_proof",
        question = "How do I submit written proof and Gallery photos?",
        answer =
          "On the Task Details screen, tap 'Submit Proof' to open the submission dialog. Enter your written completion notes describing what you did, and tap the photo picker to select at least one completion screenshot from your phone Gallery.",
        category = "Submissions"
      ),
      TaskFlowFaqItem(
        id = "faq_mandatory_both_proofs",
        question = "Why are BOTH written text proof and Gallery screenshot mandatory?",
        answer =
          "To guarantee high-quality task verification and prevent spam, TaskFlow's zero-trust security rules strictly require both written verification steps and visual screenshot proof. If either is missing, the Submit button remains disabled.",
        category = "Submissions"
      ),
      TaskFlowFaqItem(
        id = "faq_approval_workflow",
        question = "How does task review, approval, and rejection work?",
        answer =
          "Once you submit proof, the task enters 'Pending Review' status. The Administrator inspects your written description and full-size screenshot in the Admin Dashboard. The admin can either Approve & Credit the task or Reject it with custom feedback explaining what needs revision.",
        category = "Submissions"
      ),
      TaskFlowFaqItem(
        id = "faq_rewards_crediting",
        question = "How are rewards credited after task approval?",
        answer =
          "When the Administrator clicks 'Approve & Credit', an atomic Firestore batch updates the task status to Approved, records the submission, and credits the exact reward amount in Indian Rupees (INR ₹) to your Wallet balance immediately.",
        category = "Wallet",
        actionLabel = "Open Wallet",
        targetScreen = "wallet"
      ),
      TaskFlowFaqItem(
        id = "faq_wallet_balance",
        question = "How do I check my Wallet balance?",
        answer =
          "Tap the 'Wallet' tab in the bottom navigation or view your Available Balance card on the Home screen. Your balance is always displayed in Indian Rupees (₹) with real-time updates.",
        category = "Wallet",
        actionLabel = "View Wallet",
        targetScreen = "wallet"
      ),
      TaskFlowFaqItem(
        id = "faq_min_withdrawal",
        question = "What is the minimum withdrawal requirement?",
        answer =
          "The minimum withdrawal requirement is ₹200.00 (20,000 paise). The Withdraw button opens a method selector where you can request a demo withdrawal whenever your balance meets or exceeds ₹200.",
        category = "Wallet",
        actionLabel = "Open Wallet",
        targetScreen = "wallet"
      ),
      TaskFlowFaqItem(
        id = "faq_withdrawal_methods",
        question = "What is the difference between Bank Account and UPI ID withdrawal methods?",
        answer =
          "TaskFlow offers two withdrawal request options: Bank Account (Account Holder Name, Bank Name, Account Number, IFSC Code) and UPI ID (e.g. name@upi). This is currently a simulated demo/request-only workflow; real financial transactions are intentionally not processed and sensitive bank numbers are never stored in Firestore.",
        category = "Wallet"
      ),
      TaskFlowFaqItem(
        id = "faq_demo_status",
        question = "What does 'Demo / Pending' withdrawal status mean?",
        answer =
          "It indicates that your demo withdrawal request has been registered in the system for tracking. Your wallet balance is preserved and real banking transactions are not executed in this demo version.",
        category = "Wallet"
      ),
      TaskFlowFaqItem(
        id = "faq_history_activity",
        question = "How do I view my History and Activity feeds?",
        answer =
          "Navigate to the Profile tab. Under your account card, tap 'Activity' to view chronological milestones (account creation, task submissions, approvals) or tap 'History' to filter your past submissions by Approved, Pending, or Rejected with full detail dialogs.",
        category = "Account",
        actionLabel = "Go to Profile",
        targetScreen = "profile"
      ),
      TaskFlowFaqItem(
        id = "faq_dark_mode",
        question = "How do I switch between Light Mode, Dark Mode, and System Default?",
        answer =
          "Go to Profile -> Settings. Under 'App Theme', you can select Light Mode, Dark Mode, or System Default. Your choice is saved locally on your phone and automatically restored whenever the app opens.",
        category = "Appearance",
        actionLabel = "Open Settings",
        targetScreen = "settings"
      ),
      TaskFlowFaqItem(
        id = "faq_contact_support",
        question = "How do I contact TaskFlow support?",
        answer =
          "You can ask any question in this Support Assistant. Since this is an offline-capable local assistant, your queries are matched locally against our comprehensive knowledge base with zero third-party data tracking.",
        category = "Support"
      )
    )

  val COMMON_QUESTIONS =
    listOf(
      "How do I submit proof?",
      "Why are text and photo both mandatory?",
      "What is the minimum withdrawal?",
      "How do I change between Light and Dark mode?",
      "How do rewards get credited?"
    )

  fun findAnswer(userQuery: String): TaskFlowFaqItem? {
    val clean = userQuery.trim().lowercase()
    if (clean.isBlank()) return null

    // Exact or substring question match
    val directMatch = ITEMS.firstOrNull { it.question.lowercase().contains(clean) || clean.contains(it.question.lowercase()) }
    if (directMatch != null) return directMatch

    // Keyword heuristic matching
    val keywords = clean.split(Regex("[\\s,?.!]+")).filter { it.length > 2 }
    var bestMatch: TaskFlowFaqItem? = null
    var bestScore = 0

    ITEMS.forEach { item ->
      val combined = (item.question + " " + item.answer + " " + item.category).lowercase()
      val score = keywords.count { combined.contains(it) }
      if (score > bestScore) {
        bestScore = score
        bestMatch = item
      }
    }

    return if (bestScore >= 1) bestMatch else null
  }
}
