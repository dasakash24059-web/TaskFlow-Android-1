# Implementation Plan: TaskFlow APK Release, Dark Mode, Admin User Management & FAQ/Chat Support

Implement the four requested features for the native Android TaskFlow application under a strict **Zero-Billing / Zero-Paid Services** constraint, preserving all existing architecture, Firebase configuration, Google Credential Manager authentication, INR wallet, and task lifecycle functionality.

---

## 1. Zero-Billing / Zero-Paid Services Invariant Check

- **Google Cloud / Firebase Plan:** Remains 100% on the free Firebase Spark plan. No Blaze plan upgrade or billing account required.
- **AI / Chat Services:** Built completely with a locally embedded, zero-cost, privacy-safe FAQ and keyword-matching support assistant. Zero external AI APIs, zero OpenAI/Gemini Cloud API charges, zero API key requirements.
- **Storage / Theming:** Theme selection (Light / Dark / System Default) persisted locally via `SharedPreferences` / DataStore. Zero cloud dependencies.
- **CI / CD:** Built with standard GitHub Actions within GitHub's free runner quota and standard GitHub Releases for distributing the APK.

---

## 2. GitHub Actions: Automatic APK Build and Release

### File: `.github/workflows/main.yml`
- **Triggers:**
  - Push on version tags matching `v*` (e.g., `v1.0.0`, `v1.1.0`, `v2.0.0`)
  - `workflow_dispatch` (manual execution with optional release creation)
- **Workflow Steps:**
  1. `actions/checkout@v4` with `fetch-depth: 0`
  2. `actions/setup-java@v4` with Temurin JDK 17
  3. Secret extraction & validation: safely decode `TASKFLOW_DEBUG_KEYSTORE_BASE64` and verify SHA-1 (`a7aacdf1fa3e6bda90b72480c73200f8a6c2ea09`) without exposing passwords or keys
  4. Run verification tests: `gradle testDebugUnitTest`
  5. Build Debug APK: `gradle assembleDebug`
  6. Verify signing certificate: run `apksigner` / `keytool` check to ensure the output APK matches the registered Firebase SHA-1
  7. Clear keystore from runner disk immediately
  8. Copy/rename APK to a clear versioned filename: `TaskFlow-${TAG_NAME}.apk` (or `TaskFlow-debug-${GITHUB_SHA::7}.apk` for manual runs)
  9. Automatic GitHub Release: Use `softprops/action-gh-release@v2` triggered only on tags to publish the release with release notes and attached APK asset.

---

## 3. Complete Dark Mode Support

### Files: `Theme.kt`, `ThemePreferences.kt`, `MainActivity.kt`, `UserWalletAndProfileScreens.kt`
- **Theme Options:**
  - `LIGHT`: Forces Material 3 Light Color Scheme
  - `DARK`: Forces Material 3 Dark Color Scheme (deep purple `0xFF0F0B1E` background, surface `0xFF1A1433`, high contrast text)
  - `SYSTEM_DEFAULT`: Dynamically follows Android OS system dark mode (`isSystemInDarkTheme()`)
- **Persistence:**
  - Create a lightweight `ThemePreferences` helper using Android `SharedPreferences` to persist the mode across app restarts
- **UI Integration:**
  - Add theme selector in **Settings** (`UserSettingsSubScreen`) with clear radio/segmented selection: *System Default*, *Light Mode*, *Dark Mode*
  - Expose current theme StateFlow in `TaskFlowViewModel` or local state in `MainActivity`
  - Update all screens (Dashboards, Tasks, Task Details, Forms, Modals, Wallet, History, Support) to use semantic `MaterialTheme.colorScheme` tokens, avoiding hardcoded white or unreadable dark text.

---

## 4. Admin Dashboard: User Database Management

### Files: `AdminScreens.kt`, `TaskFlowViewModel.kt`, `TaskFlowRepository.kt`
- **Tab Layout & Filtering:**
  - In the Admin Dashboard **Users** tab:
    - Search bar: search users by name or email in real-time
    - Filter chips: **Pending**, **Approved**, and **Suspended**
    - User stats: Display count for total users, pending approval, approved, and suspended
- **User Cards & Details Modal:**
  - Clean card for each registered user displaying:
    - Display Name, Avatar initial
    - Masked email (or full email for regular users, admin email protected)
    - User UID
    - Current Role (`admin` / `user`)
    - Approval Status badge (`pending`, `approved`, `suspended`)
    - Account Creation Date
    - Total Balance (INR ₹) & Completed Tasks Count
- **Admin Actions with Confirmation:**
  - **Approve User:** Transitions status to `approved`
  - **Reject / Suspend User:** Transitions status to `suspended`
  - **Activate User:** Reactivates a suspended user
  - **Assign Task directly:** Pre-selects this user in the task creation dialog
  - **Confirmation Dialog:** Shows clear confirmation modal before changing any user's approval status
- **Zero-Trust Security Enforcement:**
  - Retains existing `/admins/{uid}` gate so only authorized admins can view or modify `/users`
  - Normal users cannot query or list `/users` (protected by Firestore rules)

---

## 5. Custom TaskFlow FAQ and In-App Support Assistant

### Files: `TaskFlowFaqData.kt`, `UserSupportScreen.kt`, `UserWalletAndProfileScreens.kt`, `TaskFlowViewModel.kt`
- **FAQ Section (15 Core Topics):**
  - Detailed questions & answers tailored strictly to actual TaskFlow rules:
    1. Account creation & Google Sign-In
    2. Admin vs. User accounts
    3. Finding Available Tasks
    4. Task Details and step-by-step instructions
    5. Submitting mandatory text proof and Gallery screenshot
    6. Why both text and photo proof are mandatory
    7. Admin approval and rejection workflow
    8. Reward crediting in INR (₹)
    9. Checking Wallet balance
    10. Minimum withdrawal requirement of ₹200
    11. Bank Account vs. UPI ID withdrawal options (demo flow)
    12. Demo withdrawal request status
    13. Viewing History and Activity feeds
    14. Switching between Light Mode, Dark Mode, and System Default
    15. Security, zero-trust rules, and data privacy
- **Interactive Chat Support Interface:**
  - Local, zero-cost, zero-billing conversational assistant
  - Predefined suggested question chips ("How do I submit proof?", "What is minimum withdrawal?", "How to change theme?", etc.)
  - Text input to ask any custom question: matches keywords against the FAQ database and provides the exact relevant explanation
  - Quick action buttons (e.g., "View Available Tasks", "Open Wallet", "Go to Settings")
  - Friendly fallback when an answer is not found, without pretending to be a human or paid AI
  - Accessible directly under the User **Profile** screen as a prominent "Help & Support" menu card, plus direct link from Settings

---

## 6. Verification and Automated Testing

- Test Robolectric suite for:
  - Theme switching and persistence (Light, Dark, System Default)
  - Admin User Management search and filtering by Pending, Approved, and Suspended
  - Support FAQ retrieval and interactive question matching
  - Regression check for existing authentication, task submission, and wallet flows
- Verify compilation with `compile_applet`
- Inspect GitHub Actions workflow YAML syntax
