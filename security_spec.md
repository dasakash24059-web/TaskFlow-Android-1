# Security Specification — TaskFlow

## 1. Data Invariants

1. **Authentication & Identity**: Every read and write operation across `/users/{userId}`, `/tasks/{taskId}`, and `/admins/{adminId}` requires an authenticated session (`request.auth != null`). All document IDs must match `^[a-zA-Z0-9_\-]+$` with length `<= 128`.
2. **Role, Approval & Balance Immutability for Users**: Regular users (`request.auth.uid == userId`) can only create their own `/users/{userId}` profile with `role == 'user'`, `approvalStatus == 'approved'`, `balanceCents == 0`, and `completedTasksCount == 0`. Regular users can never modify `role`, `approvalStatus`, `balanceCents`, `completedTasksCount`, `email`, `userId`, or `createdAt`.
3. **PII Isolation**: User profiles (`/users/{userId}`) contain `email` and financial balance data (`balanceCents`). Only the profile owner (`request.auth.uid == userId`) or a verified admin (`isAdmin()`) may read a user profile.
4. **Task Lifecycle & Terminal State Locking**:
   - Only admins (`isAdmin()`) can create or delete `/tasks/{taskId}` documents.
   - Regular users may only update a task assigned to them (`existing().assignedUserId == request.auth.uid`) when `existing().status == 'pending'`, transitioning `status` to `'submitted'` and providing a non-empty `proofText` (`1..4000` chars). Once a task leaves `'pending'` (`'submitted'`, `'approved'`, `'rejected'`), non-admin updates are locked.
   - Only admins (`isAdmin()`) can transition tasks to `'approved'` or `'rejected'`, edit task details, or credit user balances.
5. **Temporal Integrity**: All `createdAt` and `updatedAt` fields must be valid Firestore `timestamp` objects `<= request.time`. `createdAt` is strictly immutable across all updates.

## 2. The "Dirty Dozen" Adversarial Payloads

1. **Unauthenticated Scrape**: Unauthenticated client attempts `get` or `list` on `/tasks` or `/users`.
2. **Privilege Escalation on Registration**: User creates `/users/{uid}` with `{"role": "admin", "balanceCents": 999999}`.
3. **Self-Balance Inflation on Update**: User updates `/users/{uid}` with `{"balanceCents": 50000}`.
4. **Role Mutation on Update**: User updates `/users/{uid}` with `{"role": "admin"}` or `{"approvalStatus": "approved"}` after suspension.
5. **Cross-User PII Leak**: Authenticated User A attempts `get` on `/users/{userB}`.
6. **Unfiltered Collection List (Query Enforcer Bypass)**: Regular user runs `db.collection("tasks").get()` without `.where("assignedUserId", "==", uid)`.
7. **Unverified Admin Email Spoof**: Attacker signs in with an unverified token (`email: "dasakash24059@gmail.com"`, `email_verified: false`) and attempts admin operations.
8. **Task Self-Approval**: Assigned user attempts to update `/tasks/{taskId}` directly from `'pending'` or `'submitted'` to `{"status": "approved"}`.
9. **Terminal State Re-Write**: Assigned user attempts to overwrite `proofText` on a task whose status is already `'approved'` or `'rejected'`.
10. **Task Reward Tampering during Proof Submission**: Assigned user submits proof while also modifying `{"rewardCents": 1000000}` on `/tasks/{taskId}`.
11. **Shadow / Ghost Field Injection**: User updates `/users/{uid}` or `/tasks/{taskId}` with an undeclared field `{"isVerified": true}`.
12. **Denial-of-Wallet Oversized String Poisoning**: Client attempts to write a 10,000-character `displayName` or `title`.

## 3. Red Team Conflict & Audit Report

- **Collection `/users/{userId}`**:
  - Identity Spoofing: Blocked (`request.auth.uid == userId && incoming().userId == userId`).
  - Privilege/Balance Escalation: Blocked (`role == 'user' && balanceCents == 0` on create; `affectedKeys().hasOnly(['displayName', 'updatedAt'])` for non-admin updates).
  - PII Blanket Read: Blocked (`allow get` restricted to owner or `isAdmin()`, `allow list` restricted to `resource.data.userId == request.auth.uid || isVerifiedAdminEmail()`).
- **Collection `/tasks/{taskId}`**:
  - State Shortcutting: Blocked (user update requires `existing().status == 'pending' && incoming().status == 'submitted'`).
  - Value Poisoning: Blocked (`isValidTask(incoming())` wraps the entire `allow update` block).
  - Query Enforcer: Enforced (`allow list` requires `resource.data.assignedUserId == request.auth.uid || resource.data.createdBy == request.auth.uid || isVerifiedAdminEmail()`).
- **Collection `/admins/{adminId}`**:
  - Unauthorized Admin Creation: Blocked (`allow create, update, delete: if isAdmin()`).
