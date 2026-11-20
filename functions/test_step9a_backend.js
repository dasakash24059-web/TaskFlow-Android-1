const test = require("node:test");
const assert = require("node:assert/strict");
const { _manualBonusHandlers, _telegramHandlers } = require("./index.js");

const { handleCreditManualBonus } = _manualBonusHandlers;
const {
  handleTelegramBotWebhook,
  handleCreateTelegramTask,
  handleSetTelegramTaskActiveStatus,
  handleVerifyTelegramTaskMembership,
  handleClaimTelegramTaskReward
} = _telegramHandlers;

function createMockFirestore() {
  const store = new Map();
  let autoIdCounter = 0;

  function clone(val) {
    if (val === null || val === undefined || typeof val !== "object") return val;
    if (val instanceof Date) return new Date(val.getTime());
    if (Array.isArray(val)) return val.map(clone);
    const out = {};
    for (const [k, v] of Object.entries(val)) {
      out[k] = clone(v);
    }
    return out;
  }

  function resolveValue(val, serverNow) {
    if (val && val.__isServerTimestamp) {
      return new Date(serverNow);
    }
    if (val && typeof val === "object" && !(val instanceof Date) && !Array.isArray(val)) {
      const out = {};
      for (const [k, v] of Object.entries(val)) {
        out[k] = resolveValue(v, serverNow);
      }
      return out;
    }
    return clone(val);
  }

  function applySet(path, data, options, serverNow) {
    const prev = store.get(path);
    const nextVersion = (prev ? prev.version : 0) + 1;
    const resolved = resolveValue(data, serverNow);
    if (options && options.merge && prev) {
      store.set(path, {
        data: { ...clone(prev.data), ...resolved },
        version: nextVersion
      });
    } else {
      store.set(path, {
        data: resolved,
        version: nextVersion
      });
    }
  }

  function applyUpdate(path, data, serverNow) {
    const prev = store.get(path);
    if (!prev) {
      throw new Error(`NOT_FOUND: Cannot update missing doc ${path}`);
    }
    const nextVersion = prev.version + 1;
    const resolved = resolveValue(data, serverNow);
    store.set(path, {
      data: { ...clone(prev.data), ...resolved },
      version: nextVersion
    });
  }

  function makeQueryRef(colPath, filters = []) {
    return {
      where(field, op, value) {
        return makeQueryRef(colPath, [...filters, { field, op, value }]);
      },
      async get() {
        const docs = [];
        for (const [docPath, entry] of store.entries()) {
          if (!docPath.startsWith(`${colPath}/`)) continue;
          const rest = docPath.slice(colPath.length + 1);
          if (rest.includes("/")) continue;
          const data = entry.data || {};
          const matches = filters.every((f) => {
            if (f.op === "==") return data[f.field] === f.value;
            return false;
          });
          if (matches) {
            docs.push({
              id: rest,
              exists: true,
              data: () => clone(data)
            });
          }
        }
        return { docs, empty: docs.length === 0, size: docs.length };
      }
    };
  }

  function makeDocRef(path) {
    return {
      path,
      id: path.split("/").pop(),
      collection(subName) {
        return makeCollectionRef(`${path}/${subName}`);
      },
      async get() {
        const entry = store.get(path);
        return {
          id: path.split("/").pop(),
          exists: Boolean(entry),
          data: () => (entry ? clone(entry.data) : undefined)
        };
      },
      async set(data, options) {
        applySet(path, data, options, Date.now());
      },
      async update(data) {
        applyUpdate(path, data, Date.now());
      }
    };
  }

  function makeCollectionRef(path) {
    return {
      path,
      doc(docId) {
        const resolvedId = docId || `auto_${++autoIdCounter}`;
        return makeDocRef(`${path}/${resolvedId}`);
      },
      where(field, op, value) {
        return makeQueryRef(path, [{ field, op, value }]);
      },
      async get() {
        return makeQueryRef(path, []).get();
      }
    };
  }

  async function runTransaction(fn) {
    const MAX_ATTEMPTS = 5;
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      const readVersions = new Map();
      const pendingWrites = [];

      const transaction = {
        async get(docRef) {
          await Promise.resolve();
          const entry = store.get(docRef.path);
          readVersions.set(docRef.path, entry ? entry.version : 0);
          return {
            id: docRef.id,
            exists: Boolean(entry),
            data: () => (entry ? clone(entry.data) : undefined)
          };
        },
        set(docRef, data, options) {
          pendingWrites.push({
            op: "set",
            path: docRef.path,
            data: clone(data),
            merge: Boolean(options && options.merge)
          });
        },
        update(docRef, data) {
          pendingWrites.push({
            op: "update",
            path: docRef.path,
            data: clone(data)
          });
        }
      };

      const result = await fn(transaction);

      let conflict = false;
      for (const [docPath, expectedVersion] of readVersions.entries()) {
        const currentEntry = store.get(docPath);
        const currentVersion = currentEntry ? currentEntry.version : 0;
        if (currentVersion !== expectedVersion) {
          conflict = true;
          break;
        }
      }
      if (conflict) {
        if (attempt === MAX_ATTEMPTS) {
          throw new Error("ABORTED: Transaction contention max retries exceeded");
        }
        continue;
      }

      const serverNow = Date.now();
      for (const w of pendingWrites) {
        if (w.op === "update") {
          applyUpdate(w.path, w.data, serverNow);
        } else {
          applySet(w.path, w.data, { merge: w.merge }, serverNow);
        }
      }
      return result;
    }
  }

  return {
    _store: store,
    collection(name) {
      return makeCollectionRef(name);
    },
    runTransaction,
    seed(path, data) {
      const prev = store.get(path);
      store.set(path, { data: clone(data), version: (prev ? prev.version : 0) + 1 });
    },
    read(path) {
      const entry = store.get(path);
      return entry ? clone(entry.data) : null;
    }
  };
}

function makeDeps(mockDb, extra = {}) {
  return {
    db: mockDb,
    now: () => extra.nowMillis || 1_760_000_000_000,
    serverTimestamp: () => ({ __isServerTimestamp: true }),
    ...extra
  };
}

async function expectHttpsError(promise, expectedCode, msgPart) {
  await assert.rejects(promise, (err) => {
    assert.equal(err.code, expectedCode, `Expected code '${expectedCode}', got '${err.code}' (${err.message})`);
    if (msgPart) {
      assert.ok(
        String(err.message).toLowerCase().includes(msgPart.toLowerCase()),
        `Expected message to include '${msgPart}', got '${err.message}'`
      );
    }
    return true;
  });
}

// =========================================================================
// PART A: MANUAL BONUS TESTS
// =========================================================================

test("Part A1: Non-admin user cannot invoke creditManualBonus", async () => {
  const db = createMockFirestore();
  db.seed("users/user_1", {
    displayName: "Regular User",
    email: "user@example.com",
    approvalStatus: "approved",
    balanceCents: 2000,
    eligibleCoins: 2500,
    promotionalCoins: 100,
    balanceCoins: 2600,
    completedTasksCount: 1
  });

  await expectHttpsError(
    handleCreditManualBonus(
      {
        auth: { uid: "user_1", token: { email: "user@example.com", admin: false } },
        data: { targetUserId: "user_1", amountCoins: 500, idempotencyKey: "bonus_key_1" }
      },
      makeDeps(db)
    ),
    "permission-denied",
    "authorized administrators"
  );

  assert.equal(db.read("users/user_1").balanceCoins, 2600);
});

test("Part A2: Authorized admin credits custom manual bonus atomically with canonical schema & preserves balanceCents/eligibleCoins", async () => {
  const db = createMockFirestore();
  db.seed("admins/admin_uid_1", { userId: "admin_uid_1", email: "mod@taskflow.app" });
  db.seed("users/target_u1", {
    displayName: "Aarav",
    email: "aarav@example.com",
    approvalStatus: "approved",
    balanceCents: 2000,
    eligibleCoins: 2500,
    promotionalCoins: 300,
    balanceCoins: 2800,
    completedTasksCount: 4
  });

  const res = await handleCreditManualBonus(
    {
      auth: { uid: "admin_uid_1", token: { email: "mod@taskflow.app", email_verified: true } },
      data: {
        targetUserId: "target_u1",
        amountCoins: 450,
        reason: "Leaderboard Winner",
        idempotencyKey: "bonus_aarav_001",
        // Ensure spoofed creditedByAdminId cannot override server request.auth.uid
        creditedByAdminId: "forged_admin"
      }
    },
    makeDeps(db)
  );

  assert.equal(res.success, true);
  assert.equal(res.targetUserId, "target_u1");
  assert.equal(res.adminId, "admin_uid_1");
  assert.equal(res.amountCoins, 450);
  assert.equal(res.newPromotionalCoins, 750);
  assert.equal(res.newBalanceCoins, 3250);

  const updatedUser = db.read("users/target_u1");
  assert.equal(updatedUser.promotionalCoins, 750);
  assert.equal(updatedUser.balanceCoins, 3250);
  assert.equal(updatedUser.balanceCents, 2000);
  assert.equal(updatedUser.eligibleCoins, 2500);

  const bonusDoc = db.read("manual_bonuses/bonus_aarav_001");
  assert.equal(bonusDoc.targetUserId, "target_u1");
  assert.equal(bonusDoc.adminId, "admin_uid_1");
  assert.equal(bonusDoc.creditedByAdminId, undefined);
  assert.equal(bonusDoc.amountCoins, 450);

  const txDoc = db.read("transactions/bonus_bonus_aarav_001");
  assert.equal(txDoc.userId, "target_u1");
  assert.equal(txDoc.type, "ADMIN_MANUAL_BONUS");
  assert.equal(txDoc.amountCoins, 450);

  const notifDoc = db.read("users/target_u1/notifications/notif_bonus_bonus_aarav_001");
  assert.equal(notifDoc.userId, "target_u1");
  assert.equal(notifDoc.type, "ADMIN_BONUS");
  assert.equal(notifDoc.rewardCoins, 450);
  assert.ok(notifDoc.body && notifDoc.message && notifDoc.body === notifDoc.message);

  const leaderboardDoc = db.read("leaderboard/target_u1");
  assert.equal(leaderboardDoc.balanceCents, 2000);
  assert.equal(leaderboardDoc.balanceCoins, 3250);

  // Duplicate credit with same idempotencyKey MUST fail
  await expectHttpsError(
    handleCreditManualBonus(
      {
        auth: { uid: "admin_uid_1", token: { email: "mod@taskflow.app", email_verified: true } },
        data: {
          targetUserId: "target_u1",
          amountCoins: 450,
          reason: "Leaderboard Winner",
          idempotencyKey: "bonus_aarav_001"
        }
      },
      makeDeps(db)
    ),
    "already-exists",
    "already been credited"
  );
});

// =========================================================================
// PART B: TELEGRAM JOIN TASK SYSTEM TESTS
// =========================================================================

test("Part B1: Admin creates multiple Telegram tasks & non-admin is blocked", async () => {
  const db = createMockFirestore();
  db.seed("admins/admin_1", { userId: "admin_1", email: "admin@taskflow.app" });

  await expectHttpsError(
    handleCreateTelegramTask(
      {
        auth: { uid: "regular_u1", token: { email: "u1@example.com" } },
        data: { title: "Hack TG", channelUrl: "https://t.me/chan1", rewardCoins: 50 }
      },
      makeDeps(db)
    ),
    "permission-denied"
  );

  let seq = 0;
  const task1 = await handleCreateTelegramTask(
    {
      auth: { uid: "admin_1", token: { email: "admin@taskflow.app" } },
      data: {
        title: "Join Official Channel",
        channelUrl: "https://t.me/taskflow_official",
        rewardCoins: 100,
        isActive: true
      }
    },
    makeDeps(db, { taskIdGenerator: () => `tg_${++seq}` })
  );

  const task2 = await handleCreateTelegramTask(
    {
      auth: { uid: "admin_1", token: { email: "admin@taskflow.app" } },
      data: {
        title: "Join VIP Deals",
        channelUrl: "https://t.me/taskflow_vip",
        channelChatId: "-1001234567890",
        rewardCoins: 200,
        isActive: true
      }
    },
    makeDeps(db, { taskIdGenerator: () => `tg_${++seq}` })
  );

  assert.equal(task1.taskId, "tg_1");
  assert.equal(task1.channelChatId, "@taskflow_official");
  assert.equal(task1.rewardCoins, 100);
  assert.equal(task2.taskId, "tg_2");
  assert.equal(task2.channelChatId, "-1001234567890");
  assert.equal(task2.rewardCoins, 200);

  const toggled = await handleSetTelegramTaskActiveStatus(
    {
      auth: { uid: "admin_1", token: { email: "admin@taskflow.app" } },
      data: { taskId: "tg_2", isActive: false }
    },
    makeDeps(db)
  );
  assert.equal(toggled.isActive, false);
  assert.equal(db.read("telegram_tasks/tg_2").isActive, false);
});

test("Part B2: Full secure Telegram verification -> webhook linking -> getChatMember -> claim -> next task isolation & duplicate block", async () => {
  const db = createMockFirestore();
  db.seed("users/u_alice", {
    displayName: "Alice",
    email: "alice@example.com",
    approvalStatus: "approved",
    balanceCents: 1000,
    eligibleCoins: 1250,
    promotionalCoins: 50,
    balanceCoins: 1300,
    completedTasksCount: 2
  });
  db.seed("users/u_bob", {
    displayName: "Bob",
    email: "bob@example.com",
    approvalStatus: "approved",
    balanceCents: 0,
    eligibleCoins: 0,
    promotionalCoins: 0,
    balanceCoins: 0,
    completedTasksCount: 0
  });
  db.seed("telegram_tasks/tg_task_1", {
    taskId: "tg_task_1",
    title: "Join Updates",
    channelUrl: "https://t.me/TaskFlowUpdates",
    channelChatId: "@TaskFlowUpdates",
    rewardCoins: 150,
    isActive: true,
    claimsCount: 0
  });

  // Missing TELEGRAM_BOT_TOKEN gives actionable failed-precondition
  await expectHttpsError(
    handleVerifyTelegramTaskMembership(
      { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
      makeDeps(db, { botToken: "" })
    ),
    "failed-precondition",
    "TELEGRAM_BOT_TOKEN"
  );

  // Step 1: Unlinked user requests verify -> gets cryptographic bot deep link
  const firstVerify = await handleVerifyTelegramTaskMembership(
    { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
    makeDeps(db, {
      botToken: "test-bot-token",
      linkTokenGenerator: () => "tok_alice_secret_1234",
      getMe: async () => ({ ok: true, result: { id: 999, is_bot: true, username: "TaskFlowVerifyBot" } })
    })
  );
  assert.equal(firstVerify.verified, false);
  assert.equal(firstVerify.needsAccountLink, true);
  assert.equal(firstVerify.botLinkUrl, "https://t.me/TaskFlowVerifyBot?start=tok_alice_secret_1234");

  // Cannot claim before verified
  await expectHttpsError(
    handleClaimTelegramTaskReward(
      { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
      makeDeps(db, { botToken: "test-bot-token" })
    ),
    "failed-precondition"
  );

  // Step 2: Simulate Telegram webhook receiving /start tok_alice_secret_1234 from Alice's Telegram user ID 555001
  const mockRes = {
    statusCode: 200,
    body: null,
    status(c) {
      this.statusCode = c;
      return this;
    },
    json(b) {
      this.body = b;
      return this;
    },
    send(b) {
      this.body = b;
      return this;
    }
  };
  await handleTelegramBotWebhook(
    {
      method: "POST",
      headers: { "x-telegram-bot-api-secret-token": "wh_secret_1" },
      body: {
        update_id: 1,
        message: {
          text: "/start tok_alice_secret_1234",
          from: { id: 555001, is_bot: false, username: "alice_tg" }
        }
      }
    },
    mockRes,
    makeDeps(db, { webhookSecret: "wh_secret_1" })
  );
  assert.equal(mockRes.statusCode, 200);
  assert.equal(db.read("telegram_accounts/u_alice").telegramUserId, "555001");
  assert.equal(db.read("users/u_alice/telegram_account/status").isLinked, true);

  // Step 3: Verify while user hasn't joined channel yet -> permission-denied
  await expectHttpsError(
    handleVerifyTelegramTaskMembership(
      { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
      makeDeps(db, {
        botToken: "test-bot-token",
        getChatMember: async () => ({ ok: true, result: { status: "left" } })
      })
    ),
    "permission-denied",
    "not a member"
  );

  // Step 4: User joins channel -> VERIFY succeeds via Telegram Bot API getChatMember
  const secondVerify = await handleVerifyTelegramTaskMembership(
    { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
    makeDeps(db, {
      botToken: "test-bot-token",
      getChatMember: async (token, chatId, tgUid) => {
        assert.equal(token, "test-bot-token");
        assert.equal(chatId, "@TaskFlowUpdates");
        assert.equal(tgUid, "555001");
        return { ok: true, result: { status: "member" } };
      }
    })
  );
  assert.equal(secondVerify.verified, true);
  assert.equal(db.read("users/u_alice/telegram_task_states/tg_task_1").isMembershipVerified, true);

  // Step 5: If user leaves before clicking CLAIM, live re-verification blocks claim AND resets isMembershipVerified = false!
  await expectHttpsError(
    handleClaimTelegramTaskReward(
      { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
      makeDeps(db, {
        botToken: "test-bot-token",
        getChatMember: async () => ({ ok: true, result: { status: "left" } })
      })
    ),
    "permission-denied"
  );
  assert.equal(db.read("users/u_alice/telegram_task_states/tg_task_1").isMembershipVerified, false);

  // Attempting to claim again without re-verifying MUST fail with failed-precondition
  await expectHttpsError(
    handleClaimTelegramTaskReward(
      { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
      makeDeps(db, {
        botToken: "test-bot-token",
        getChatMember: async () => ({ ok: true, result: { status: "member" } })
      })
    ),
    "failed-precondition",
    "VERIFY before claiming"
  );

  // User re-joins and re-verifies -> state transitions back to verified
  const reVerify = await handleVerifyTelegramTaskMembership(
    { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
    makeDeps(db, {
      botToken: "test-bot-token",
      getChatMember: async () => ({ ok: true, result: { status: "member" } })
    })
  );
  assert.equal(reVerify.verified, true);
  assert.equal(db.read("users/u_alice/telegram_task_states/tg_task_1").isMembershipVerified, true);

  // Step 6: User stays member and calls CLAIM -> atomic wallet credit + ledger + notification + claim history + leaderboard sync
  const claimRes = await handleClaimTelegramTaskReward(
    { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
    makeDeps(db, {
      botToken: "test-bot-token",
      getChatMember: async () => ({ ok: true, result: { status: "member" } })
    })
  );
  assert.equal(claimRes.success, true);
  assert.equal(claimRes.rewardCoins, 150);
  assert.equal(claimRes.newPromotionalCoins, 200);
  assert.equal(claimRes.newBalanceCoins, 1450);

  const aliceUser = db.read("users/u_alice");
  assert.equal(aliceUser.promotionalCoins, 200);
  assert.equal(aliceUser.balanceCoins, 1450);
  assert.equal(aliceUser.balanceCents, 1000);
  assert.equal(aliceUser.eligibleCoins, 1250);

  assert.equal(db.read("telegram_tasks/tg_task_1").claimsCount, 1);
  assert.equal(db.read("telegram_claims/tg_task_1_u_alice").rewardCoins, 150);
  assert.equal(db.read("transactions/tg_task_tg_task_1_u_alice").type, "TELEGRAM_JOIN");
  const notif = db.read("users/u_alice/notifications/notif_tg_task_tg_task_1_u_alice");
  assert.equal(notif.type, "TELEGRAM_REWARD");
  assert.ok(notif.body && notif.message);
  assert.equal(db.read("leaderboard/u_alice").balanceCoins, 1450);

  // Duplicate claim MUST be blocked (even when still member)
  await expectHttpsError(
    handleClaimTelegramTaskReward(
      { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
      makeDeps(db, {
        botToken: "test-bot-token",
        getChatMember: async () => ({ ok: true, result: { status: "member" } })
      })
    ),
    "already-exists"
  );

  // Even if user leaves AFTER claiming, a late claim call must NOT corrupt isClaimed or overwrite verified state of claimed doc
  await expectHttpsError(
    handleClaimTelegramTaskReward(
      { auth: { uid: "u_alice" }, data: { taskId: "tg_task_1" } },
      makeDeps(db, {
        botToken: "test-bot-token",
        getChatMember: async () => ({ ok: true, result: { status: "left" } })
      })
    ),
    "permission-denied"
  );
  assert.equal(db.read("users/u_alice/telegram_task_states/tg_task_1").isClaimed, true);
  assert.equal(db.read("users/u_alice/telegram_task_states/tg_task_1").isMembershipVerified, true);

  // Bob trying to link the same Telegram account 555001 via getUpdates fallback MUST fail with TELEGRAM_ALREADY_BOUND
  const bobLinkPrompt = await handleVerifyTelegramTaskMembership(
    { auth: { uid: "u_bob" }, data: { taskId: "tg_task_1" } },
    makeDeps(db, {
      botToken: "test-bot-token",
      linkTokenGenerator: () => "tok_bob_secret_5678",
      getMe: async () => ({ ok: true, result: { id: 999, is_bot: true, username: "TaskFlowVerifyBot" } })
    })
  );
  assert.equal(bobLinkPrompt.needsAccountLink, true);

  await expectHttpsError(
    handleVerifyTelegramTaskMembership(
      { auth: { uid: "u_bob" }, data: { taskId: "tg_task_1" } },
      makeDeps(db, {
        botToken: "test-bot-token",
        getUpdates: async () => ({
          ok: true,
          result: [
            {
              update_id: 2,
              message: {
                text: "/start tok_bob_secret_5678",
                from: { id: 555001, is_bot: false, username: "alice_tg" }
              }
            }
          ]
        })
      })
    ),
    "already-exists",
    "already linked to another TaskFlow user"
  );
});

test("Part B3: Telegram webhook fails safely when TELEGRAM_WEBHOOK_SECRET is missing or mismatched, and accepts valid secret", async () => {
  const db = createMockFirestore();
  const prevEnvSecret = process.env.TELEGRAM_WEBHOOK_SECRET;
  delete process.env.TELEGRAM_WEBHOOK_SECRET;

  try {
    db.seed("telegram_link_tokens/tok_webhook_test_1", {
      token: "tok_webhook_test_1",
      userId: "u_webhook_user",
      taskId: "tg_task_1",
      status: "PENDING",
      expiresAtMillis: 1_760_000_900_000
    });

    const createMockRes = () => ({
      statusCode: 200,
      body: null,
      status(c) {
        this.statusCode = c;
        return this;
      },
      json(b) {
        this.body = b;
        return this;
      },
      send(b) {
        this.body = b;
        return this;
      }
    });

    const payload = {
      update_id: 101,
      message: {
        text: "/start tok_webhook_test_1",
        from: { id: 777001, is_bot: false, username: "wh_user" }
      }
    };

    // 1. Missing configured TELEGRAM_WEBHOOK_SECRET -> fails safely with HTTP 500, does NOT link account
    const missingSecretRes = createMockRes();
    await handleTelegramBotWebhook(
      { method: "POST", headers: {}, body: payload },
      missingSecretRes,
      makeDeps(db, { webhookSecret: "" })
    );
    assert.equal(missingSecretRes.statusCode, 500);
    assert.equal(missingSecretRes.body.ok, false);
    assert.equal(db.read("telegram_accounts/u_webhook_user"), null);
    assert.equal(db.read("telegram_link_tokens/tok_webhook_test_1").status, "PENDING");

    // 2. Configured secret present, but header missing -> HTTP 401
    const missingHeaderRes = createMockRes();
    await handleTelegramBotWebhook(
      { method: "POST", headers: {}, body: payload },
      missingHeaderRes,
      makeDeps(db, { webhookSecret: "prod_webhook_secret_xyz" })
    );
    assert.equal(missingHeaderRes.statusCode, 401);
    assert.equal(db.read("telegram_accounts/u_webhook_user"), null);

    // 3. Configured secret present, wrong header -> HTTP 401
    const wrongHeaderRes = createMockRes();
    await handleTelegramBotWebhook(
      {
        method: "POST",
        headers: { "x-telegram-bot-api-secret-token": "wrong_secret" },
        body: payload
      },
      wrongHeaderRes,
      makeDeps(db, { webhookSecret: "prod_webhook_secret_xyz" })
    );
    assert.equal(wrongHeaderRes.statusCode, 401);
    assert.equal(db.read("telegram_accounts/u_webhook_user"), null);

    // 4. Configured secret present, valid header -> HTTP 200 and account is linked
    const validRes = createMockRes();
    await handleTelegramBotWebhook(
      {
        method: "POST",
        headers: { "x-telegram-bot-api-secret-token": "prod_webhook_secret_xyz" },
        body: payload
      },
      validRes,
      makeDeps(db, { webhookSecret: "prod_webhook_secret_xyz" })
    );
    assert.equal(validRes.statusCode, 200);
    assert.equal(validRes.body.ok, true);
    assert.equal(db.read("telegram_accounts/u_webhook_user").telegramUserId, "777001");
    assert.equal(db.read("telegram_link_tokens/tok_webhook_test_1").status, "USED");
  } finally {
    if (prevEnvSecret !== undefined) {
      process.env.TELEGRAM_WEBHOOK_SECRET = prevEnvSecret;
    }
  }
});
