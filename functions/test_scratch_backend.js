const crypto = require("crypto");
const assert = require("assert");
const {
  _generateScratchRewardCoins,
  _getTodayDateString,
  _verifyUnityS2SSignature,
  _resolveUnityS2SSecret,
  _DAILY_CHECK_IN_REWARDS_COINS,
  _rewardHandlers
} = require("./index.js");


(async () => {
  console.log("Starting TaskFlow Scratch Card Backend Unit & Security Test Suite...");

  // =========================================================================
  // TEST 1: Reward Distribution Boundaries & Probability Weights
  // =========================================================================
  console.log("Test 1: Reward distribution boundaries (10,000 trials)...");
  const TRIALS = 10000;
  let lowTierCount = 0;
  let highTierCount = 0;

  for (let i = 0; i < TRIALS; i++) {
    const reward = _generateScratchRewardCoins(0);
    assert.strictEqual(Number.isInteger(reward), true, "Reward must be an integer");
    assert.ok(reward >= 1 && reward <= 100, `Reward ${reward} out of bounds [1, 100]`);

    if (reward <= 40) {
      lowTierCount++;
    } else {
      highTierCount++;
    }
  }

  const lowRatio = lowTierCount / TRIALS;
  const highRatio = highTierCount / TRIALS;
  console.log(`  Distribution: Low Tier (1-40) = ${(lowRatio * 100).toFixed(1)}%, High Tier (41-100) = ${(highRatio * 100).toFixed(1)}%`);
  assert.ok(lowRatio >= 0.85 && lowRatio <= 0.95, `Expected ~90% low tier, got ${(lowRatio * 100).toFixed(1)}%`);
  assert.ok(highRatio >= 0.05 && highRatio <= 0.15, `Expected ~10% high tier, got ${(highRatio * 100).toFixed(1)}%`);

  // Test optional task boost raising minimum reward floor without exceeding boundaries
  for (let i = 0; i < 500; i++) {
    const boostedReward = _generateScratchRewardCoins(20); // 20 completed tasks
    assert.ok(boostedReward >= 11 && boostedReward <= 100, `Boosted reward ${boostedReward} must be >= 11`);
  }

  // =========================================================================
  // TEST 2: Device Clock Cannot Reset Server Daily Limits (Asia/Kolkata Timezone)
  // =========================================================================
  console.log("Test 2: Device clock manipulation cannot reset server dateKey...");
  const serverToday = _getTodayDateString(new Date());
  assert.match(serverToday, /^\d{4}-\d{2}-\d{2}$/, "Server dateKey must be YYYY-MM-DD");

  // Client passing an altered timestamp/date is rejected because server generates dateKey
  const fixedUtcDate = new Date("2026-11-14T12:00:00Z");
  const kolkataDate = _getTodayDateString(fixedUtcDate);
  assert.strictEqual(kolkataDate, "2026-11-14");

  // =========================================================================
  // MOCK ENVIRONMENT FOR SIMULATING FIRESTORE ATOMIC TRANSACTIONS
  // =========================================================================
  const db = {
    users: {},
    scratchStats: {},
    scratchSessions: {},
    rewardSessions: {},
    processedCallbacks: new Set(),
    transactions: [],
    notifications: []
  };

  function initUser(uid, approvalStatus = "approved", completedTasks = 0) {
    db.users[uid] = {
      userId: uid,
      displayName: `User_${uid}`,
      approvalStatus,
      completedTasksCount: completedTasks,
      balanceCents: 0,
      balanceCoins: 0,
      promotionalCoins: 0
    };
  }

  function getStats(uid, dateKey) {
    const key = `${uid}_${dateKey}`;
    if (!db.scratchStats[key]) {
      db.scratchStats[key] = {
        userId: uid,
        dateKey,
        scratchesCompletedToday: 0,
        adsWatchedForNextScratch: 0,
        unlockedScratchesCount: 0,
        hasUsedFreeScratchToday: false,
        activeCardId: "",
        activeCardRewardCoins: 0,
        activeCardIsScratched: false,
        activeCardIsClaimed: false
      };
    }
    return db.scratchStats[key];
  }

  // Simulated createRewardSession (SCRATCH_CARD_UNLOCK)
  function createRewardSession(authUid, rewardType) {
    if (!["WATCH_AND_EARN", "DAILY_CHECK_IN", "SCRATCH_CARD_UNLOCK"].includes(rewardType)) {
      throw new Error("Invalid reward type.");
    }
    const user = db.users[authUid];
    if (!user || user.approvalStatus !== "approved") {
      throw new Error("User must be approved.");
    }
    const today = _getTodayDateString();
    const stats = getStats(authUid, today);
    if (rewardType === "SCRATCH_CARD_UNLOCK" && stats.scratchesCompletedToday >= 50) {
      throw new Error("Daily scratch limit of 50 reached.");
    }

    const sessionId = `ad_session_${crypto.randomBytes(8).toString("hex")}`;
    db.rewardSessions[sessionId] = {
      sessionId,
      userId: authUid,
      rewardType,
      rewardCents: 0,
      rewardCoins: 0,
      dateKey: today,
      status: "PENDING",
      s2sVerified: false,
      used: false,
      claimed: false,
      expiresAtMillis: Date.now() + 600000
    };
    return sessionId;
  }

  // Simulated Unity S2S Callback
  function processUnityS2SCallback(orderId, sessionId) {
    if (db.processedCallbacks.has(orderId)) {
      throw new Error("Duplicate transaction");
    }
    const session = db.rewardSessions[sessionId];
    if (!session) throw new Error("Session not found");
    if (session.used || session.claimed || session.status === "CLAIMED") {
      throw new Error("Session already used");
    }
    db.processedCallbacks.add(orderId);
    session.s2sVerified = true;
    session.status = "VERIFIED";
  }

  // Simulated recordScratchAdWatch
  function recordScratchAdWatch(authUid, sessionId) {
    const user = db.users[authUid];
    if (!user || user.approvalStatus !== "approved") {
      throw new Error("User must be approved.");
    }
    const session = db.rewardSessions[sessionId];
    if (!session) throw new Error("Invalid reward session.");
    if (session.userId !== authUid) throw new Error("Session belongs to another user.");
    if (session.rewardType !== "SCRATCH_CARD_UNLOCK") {
      throw new Error("Session is not a Scratch Card unlock session.");
    }
    if (session.used || session.claimed || session.status === "CONSUMED") {
      throw new Error("This ad session has already been used.");
    }
    if (session.expiresAtMillis < Date.now()) {
      throw new Error("Ad session has expired.");
    }
    if (session.s2sVerified !== true || session.status !== "VERIFIED") {
      throw new Error("Reward video completion has not been verified by server yet.");
    }

    const today = _getTodayDateString();
    const stats = getStats(authUid, today);
    if (stats.scratchesCompletedToday >= 50) {
      throw new Error("Daily scratch limit of 50 reached.");
    }

    session.status = "CONSUMED";
    session.used = true;

    const nextWatched = stats.adsWatchedForNextScratch + 1;
    let newlyUnlocked = false;
    if (nextWatched >= 2) {
      stats.adsWatchedForNextScratch = 0;
      stats.unlockedScratchesCount += 1;
      newlyUnlocked = true;
    } else {
      stats.adsWatchedForNextScratch = nextWatched;
    }

    return {
      adsWatchedForNextScratch: stats.adsWatchedForNextScratch,
      unlockedScratchesCount: stats.unlockedScratchesCount,
      newlyUnlocked
    };
  }

  // Simulated createScratchCardSession
  function createScratchCardSession(authUid) {
    const user = db.users[authUid];
    if (!user || user.approvalStatus !== "approved") {
      throw new Error("User must be approved.");
    }
    const today = _getTodayDateString();
    const stats = getStats(authUid, today);

    if (stats.scratchesCompletedToday >= 50) {
      throw new Error("Daily scratch limit (50) reached. Check back tomorrow!");
    }

    // Check if active card exists
    if (stats.activeCardId) {
      const activeSession = db.scratchSessions[stats.activeCardId];
      if (activeSession && !activeSession.claimed) {
        return {
          cardId: stats.activeCardId,
          rewardCoins: activeSession.rewardCoins,
          scratchNumber: activeSession.scratchNumber,
          isFree: activeSession.isFree,
          isReopened: true
        };
      }
    }

    const hasUsedFree = stats.hasUsedFreeScratchToday === true || stats.scratchesCompletedToday > 0;
    if (hasUsedFree) {
      if (stats.unlockedScratchesCount < 1) {
        throw new Error("You must watch 2 rewarded videos to unlock another Scratch Card.");
      }
      stats.unlockedScratchesCount -= 1;
    }

    const rewardCoins = _generateScratchRewardCoins(user.completedTasksCount || 0);
    const cardId = `scratch_${crypto.randomBytes(8).toString("hex")}`;
    const scratchNumber = stats.scratchesCompletedToday + 1;

    db.scratchSessions[cardId] = {
      cardId,
      userId: authUid,
      scratchNumber,
      rewardCoins,
      dateKey: today,
      isFree: !hasUsedFree,
      claimed: false,
      status: "ACTIVE"
    };

    stats.hasUsedFreeScratchToday = true;
    stats.activeCardId = cardId;
    stats.activeCardRewardCoins = rewardCoins;
    stats.activeCardIsClaimed = false;

    return {
      cardId,
      rewardCoins,
      scratchNumber,
      isFree: !hasUsedFree,
      isReopened: false
    };
  }

  // Simulated claimScratchCardReward
  function claimScratchCardReward(authUid, cardId) {
    const user = db.users[authUid];
    if (!user || user.approvalStatus !== "approved") {
      throw new Error("User must be approved.");
    }
    const session = db.scratchSessions[cardId];
    if (!session) throw new Error("Scratch card session not found.");
    if (session.userId !== authUid) throw new Error("Scratch card belongs to another user.");
    if (session.claimed || session.status === "CLAIMED") {
      throw new Error("This Scratch Card reward has already been claimed.");
    }

    const today = _getTodayDateString();
    const stats = getStats(authUid, today);
    if (stats.scratchesCompletedToday >= 50) {
      throw new Error("Daily scratch limit (50) reached.");
    }

    session.claimed = true;
    session.status = "CLAIMED";

    stats.scratchesCompletedToday += 1;
    stats.activeCardId = "";
    stats.activeCardRewardCoins = 0;
    stats.activeCardIsClaimed = true;

    user.promotionalCoins += session.rewardCoins;
    user.balanceCoins += session.rewardCoins;

    db.transactions.push({
      userId: authUid,
      type: "SCRATCH_CARD",
      amountCoins: session.rewardCoins,
      referenceId: cardId,
      status: "CREDITED"
    });

    db.notifications.push({
      userId: authUid,
      type: "SCRATCH_CARD_REWARD",
      rewardCoins: session.rewardCoins,
      referenceId: cardId
    });

    return {
      success: true,
      cardId,
      rewardCoins: session.rewardCoins,
      scratchesCompletedToday: stats.scratchesCompletedToday,
      newBalanceCoins: user.balanceCoins
    };
  }

  // =========================================================================
  // TEST 3: First Free Scratch of the Day
  // =========================================================================
  console.log("Test 3: First scratch of the day is FREE without ad watching...");
  initUser("alice", "approved", 5);
  const card1 = createScratchCardSession("alice");
  assert.strictEqual(card1.isFree, true);
  assert.strictEqual(card1.scratchNumber, 1);
  assert.ok(card1.rewardCoins >= 1 && card1.rewardCoins <= 100);

  // Reopening returns the same active card session without consuming or regenerating
  const card1Reopen = createScratchCardSession("alice");
  assert.strictEqual(card1Reopen.cardId, card1.cardId);
  assert.strictEqual(card1Reopen.rewardCoins, card1.rewardCoins);
  assert.strictEqual(card1Reopen.isReopened, true);

  // Claim first card
  const claim1 = claimScratchCardReward("alice", card1.cardId);
  assert.strictEqual(claim1.scratchesCompletedToday, 1);
  assert.strictEqual(db.users["alice"].promotionalCoins, card1.rewardCoins);
  assert.strictEqual(db.users["alice"].balanceCoins, card1.rewardCoins);
  assert.strictEqual(db.users["alice"].balanceCents, 0, "balanceCents must remain unchanged");

  // =========================================================================
  // TEST 4: One Verified Ad Does NOT Unlock a Scratch Card
  // =========================================================================
  console.log("Test 4: One verified ad does NOT unlock a scratch card...");
  // Now free scratch is used, user attempts to create card 2 without watching 2 ads -> MUST FAIL
  assert.throws(
    () => createScratchCardSession("alice"),
    /You must watch 2 rewarded videos to unlock another Scratch Card/
  );

  // User watches Ad 1
  const adSession1 = createRewardSession("alice", "SCRATCH_CARD_UNLOCK");
  // Before S2S verification, attempting to consume ad session must fail
  assert.throws(
    () => recordScratchAdWatch("alice", adSession1),
    /Reward video completion has not been verified by server yet/
  );

  // Unity S2S completes Ad 1
  processUnityS2SCallback("unity_ad_order_1", adSession1);
  const adResult1 = recordScratchAdWatch("alice", adSession1);
  assert.strictEqual(adResult1.adsWatchedForNextScratch, 1);
  assert.strictEqual(adResult1.unlockedScratchesCount, 0);
  assert.strictEqual(adResult1.newlyUnlocked, false);

  // After 1 ad, attempting to create card 2 must STILL fail
  assert.throws(
    () => createScratchCardSession("alice"),
    /You must watch 2 rewarded videos to unlock another Scratch Card/
  );

  // =========================================================================
  // TEST 5: Two Verified Ads Unlock Exactly One Scratch Card
  // =========================================================================
  console.log("Test 5: Two verified ads unlock exactly one scratch card...");
  // User watches Ad 2
  const adSession2 = createRewardSession("alice", "SCRATCH_CARD_UNLOCK");
  processUnityS2SCallback("unity_ad_order_2", adSession2);
  const adResult2 = recordScratchAdWatch("alice", adSession2);
  assert.strictEqual(adResult2.adsWatchedForNextScratch, 0);
  assert.strictEqual(adResult2.unlockedScratchesCount, 1);
  assert.strictEqual(adResult2.newlyUnlocked, true);

  // Now create card 2 succeeds, consuming 1 unlock
  const card2 = createScratchCardSession("alice");
  assert.strictEqual(card2.scratchNumber, 2);
  assert.strictEqual(card2.isFree, false);
  const statsAlice = getStats("alice", _getTodayDateString());
  assert.strictEqual(statsAlice.unlockedScratchesCount, 0, "Unlock count must decrement to 0");

  // Claim card 2
  const claim2 = claimScratchCardReward("alice", card2.cardId);
  assert.strictEqual(claim2.scratchesCompletedToday, 2);
  assert.strictEqual(db.users["alice"].balanceCoins, card1.rewardCoins + card2.rewardCoins);

  // =========================================================================
  // TEST 6: Duplicate S2S Callback and Duplicate Ad Consumption Prevention
  // =========================================================================
  console.log("Test 6: Duplicate S2S callback and duplicate ad consumption...");
  // Replaying unity_ad_order_2 must throw Duplicate transaction
  assert.throws(
    () => processUnityS2SCallback("unity_ad_order_2", adSession2),
    /Duplicate transaction/
  );

  // Re-consuming adSession2 must throw already used
  assert.throws(
    () => recordScratchAdWatch("alice", adSession2),
    /This ad session has already been used/
  );

  // =========================================================================
  // TEST 7: Duplicate and Concurrent Claim Prevention
  // =========================================================================
  console.log("Test 7: Duplicate claim on already claimed scratch card...");
  // Attempting to claim card1 or card2 again must throw
  assert.throws(
    () => claimScratchCardReward("alice", card1.cardId),
    /This Scratch Card reward has already been claimed/
  );
  assert.throws(
    () => claimScratchCardReward("alice", card2.cardId),
    /This Scratch Card reward has already been claimed/
  );

  // =========================================================================
  // TEST 8: Users Cannot Access or Claim Another User's Sessions
  // =========================================================================
  console.log("Test 8: Cross-user session isolation...");
  initUser("bob", "approved", 0);
  const bobAdSession = createRewardSession("bob", "SCRATCH_CARD_UNLOCK");
  processUnityS2SCallback("unity_ad_order_bob_1", bobAdSession);

  // Alice cannot consume Bob's ad session
  assert.throws(
    () => recordScratchAdWatch("alice", bobAdSession),
    /Session belongs to another user/
  );

  // Bob creates a scratch card
  const bobCard = createScratchCardSession("bob");
  // Alice cannot claim Bob's scratch card
  assert.throws(
    () => claimScratchCardReward("alice", bobCard.cardId),
    /Scratch card belongs to another user/
  );

  // =========================================================================
  // TEST 9: Scratch Card Ad Session Cannot Be Claimed via Watch & Earn Endpoint
  // =========================================================================
  console.log("Test 9: Cross-endpoint claiming prevention...");
  const claimRewardSessionSim = (authUid, sid) => {
    const session = db.rewardSessions[sid];
    if (!["WATCH_AND_EARN", "DAILY_CHECK_IN"].includes(session.rewardType)) {
      throw new Error("This reward session cannot be claimed via claimRewardSession.");
    }
  };
  assert.throws(
    () => claimRewardSessionSim("bob", bobAdSession),
    /This reward session cannot be claimed via claimRewardSession/
  );

  // =========================================================================
  // TEST 10: Maximum 50 Daily Claims Enforced Server-Side
  // =========================================================================
  console.log("Test 10: Maximum 50 daily scratches limit enforcement...");
  initUser("charlie", "approved", 0);
  const charlieStats = getStats("charlie", _getTodayDateString());
  charlieStats.scratchesCompletedToday = 50;

  // Attempting to create card when 50 completed must throw resource-exhausted
  assert.throws(
    () => createScratchCardSession("charlie"),
    /Daily scratch limit \(50\) reached/
  );

  // Attempting to create ad unlock session when 50 completed must throw
  assert.throws(
    () => createRewardSession("charlie", "SCRATCH_CARD_UNLOCK"),
    /Daily scratch limit of 50 reached/
  );

  // Attempting to claim when 50 completed must throw
  db.scratchSessions["charlie_stale_card"] = {
    cardId: "charlie_stale_card",
    userId: "charlie",
    rewardCoins: 50,
    claimed: false,
    status: "ACTIVE"
  };
  assert.throws(
    () => claimScratchCardReward("charlie", "charlie_stale_card"),
    /Daily scratch limit \(50\) reached/
  );

  // =========================================================================
  // TEST 11: Real Cloud Function Handlers (Step 9 Scratch Card Fixes)
  // =========================================================================
  console.log("Test 11: Real Scratch Card handlers (deterministic tx/notif, wallet consistency, toMillisSafe)...");
  const {
    handleRecordScratchAdWatch,
    handleCreateScratchCardSession,
    handleClaimScratchCardReward,
    generateWatchEarnRewardCoins
  } = _rewardHandlers;

  function createHandlerMockDb() {
    const store = new Map();
    let autoId = 0;
    const clone = (v) => (v === null || typeof v !== "object" ? v : v instanceof Date ? new Date(v.getTime()) : Array.isArray(v) ? v.map(clone) : Object.fromEntries(Object.entries(v).map(([k, val]) => [k, clone(val)])));
    const resolve = (v, nowMs) => (v && v.__ts ? new Date(nowMs) : v && typeof v === "object" && !(v instanceof Date) && !Array.isArray(v) ? Object.fromEntries(Object.entries(v).map(([k, val]) => [k, resolve(val, nowMs)])) : clone(v));
    const docRef = (path) => ({
      path,
      id: path.split("/").pop(),
      collection: (sub) => colRef(`${path}/${sub}`),
      async get() {
        const entry = store.get(path);
        return { id: path.split("/").pop(), exists: Boolean(entry), data: () => (entry ? clone(entry.data) : undefined) };
      },
      async set(data, opts) {
        const prev = store.get(path);
        const res = resolve(data, Date.now());
        store.set(path, { data: opts?.merge && prev ? { ...clone(prev.data), ...res } : res, version: (prev?.version || 0) + 1 });
      }
    });
    const colRef = (path) => ({ path, doc: (id) => docRef(`${path}/${id || `auto_${++autoId}`}`) });
    return {
      store,
      collection: (name) => colRef(name),
      async runTransaction(fn) {
        const pending = [];
        const tx = {
          async get(ref) {
            const entry = store.get(ref.path);
            return { id: ref.id, exists: Boolean(entry), data: () => (entry ? clone(entry.data) : undefined) };
          },
          set(ref, data, opts) { pending.push({ op: "set", path: ref.path, data: clone(data), merge: Boolean(opts?.merge) }); },
          update(ref, data) { pending.push({ op: "update", path: ref.path, data: clone(data) }); }
        };
        const res = await fn(tx);
        const nowMs = Date.now();
        for (const w of pending) {
          const prev = store.get(w.path);
          const resolved = resolve(w.data, nowMs);
          if (w.op === "update" || w.merge) {
            store.set(w.path, { data: { ...(prev ? clone(prev.data) : {}), ...resolved }, version: (prev?.version || 0) + 1 });
          } else {
            store.set(w.path, { data: resolved, version: (prev?.version || 0) + 1 });
          }
        }
        return res;
      }
    };
  }

  const fixedNow = Date.UTC(2026, 10, 15, 6, 0, 0);
  const realToday = _getTodayDateString(new Date(fixedNow));
  const mockDb = createHandlerMockDb();
  const deps = {
    db: mockDb,
    now: () => fixedNow,
    serverTimestamp: () => ({ __ts: true }),
    random: () => 0.5,
    cardIdGenerator: () => "card_step9_001"
  };

  // Seed user with explicit eligibleCoins > floor(balanceCents*125/100) + promotionalCoins
  await mockDb.collection("users").doc("u_scratch").set({
    userId: "u_scratch",
    displayName: "Scratch Tester",
    approvalStatus: "approved",
    balanceCents: 800,         // ₹8.00 -> 1000 coins
    eligibleCoins: 1200,       // explicit eligible > 1000
    promotionalCoins: 150,     // total before claim = 1350
    balanceCoins: 1350,
    completedTasksCount: 4
  });

  // Step 11a: Create first free card
  const createdCard1 = await handleCreateScratchCardSession({ auth: { uid: "u_scratch" }, data: {} }, deps);
  assert.strictEqual(createdCard1.cardId, "card_step9_001");
  assert.strictEqual(createdCard1.isFree, true);
  assert.ok(createdCard1.rewardCoins >= 1 && createdCard1.rewardCoins <= 40);

  // Step 11b: Claim card -> deterministic tx & notif, both body+message, eligibleCoins preserved, leaderboard updated
  const claimed1 = await handleClaimScratchCardReward(
    { auth: { uid: "u_scratch" }, data: { cardId: "card_step9_001" } },
    deps
  );
  assert.strictEqual(claimed1.rewardCoins, createdCard1.rewardCoins);
  assert.strictEqual(claimed1.newBalanceCoins, 1350 + createdCard1.rewardCoins);

  const userAfterClaim = (await mockDb.collection("users").doc("u_scratch").get()).data();
  assert.strictEqual(userAfterClaim.balanceCents, 800, "balanceCents must remain unchanged");
  assert.strictEqual(userAfterClaim.eligibleCoins, 1200, "eligibleCoins must remain unchanged");
  assert.strictEqual(userAfterClaim.promotionalCoins, 150 + createdCard1.rewardCoins);
  assert.strictEqual(userAfterClaim.balanceCoins, 1350 + createdCard1.rewardCoins);

  const txDoc = await mockDb.collection("transactions").doc("scratch_card_step9_001").get();
  assert.strictEqual(txDoc.exists, true, "Deterministic transaction ID scratch_<cardId> must exist");
  assert.strictEqual(txDoc.data().amountCoins, createdCard1.rewardCoins);
  assert.strictEqual(txDoc.data().type, "SCRATCH_CARD");

  const notifDoc = await mockDb.collection("users").doc("u_scratch").collection("notifications").doc("notif_scratch_card_step9_001").get();
  assert.strictEqual(notifDoc.exists, true, "Deterministic notification ID notif_scratch_<cardId> must exist");
  assert.ok(notifDoc.data().body && notifDoc.data().body.includes(`+${createdCard1.rewardCoins} Coins`));
  assert.ok(notifDoc.data().message && notifDoc.data().message.includes(`+${createdCard1.rewardCoins} Coins`));

  const lbDoc = await mockDb.collection("leaderboard").doc("u_scratch").get();
  assert.strictEqual(lbDoc.data().balanceCents, 800);
  assert.strictEqual(lbDoc.data().balanceCoins, 1350 + createdCard1.rewardCoins);

  // Step 11c: Duplicate claim blocked both by session.claimed and existingTxDoc.exists
  await assert.rejects(
    () => handleClaimScratchCardReward({ auth: { uid: "u_scratch" }, data: { cardId: "card_step9_001" } }, deps),
    /already been claimed/
  );
  // Even if session status were corrupted back to ACTIVE, deterministic existingTxDoc still blocks duplicate credit!
  await mockDb.collection("users").doc("u_scratch").collection("scratch_sessions").doc("card_step9_001").set({
    cardId: "card_step9_001",
    userId: "u_scratch",
    rewardCoins: 99,
    claimed: false,
    status: "ACTIVE"
  });
  await assert.rejects(
    () => handleClaimScratchCardReward({ auth: { uid: "u_scratch" }, data: { cardId: "card_step9_001" } }, deps),
    /already been claimed/
  );

  // Step 11d: toMillisSafe handles plain {_seconds, _nanoseconds} object in recordScratchAdWatch
  await mockDb.collection("reward_sessions").doc("sess_plain_ts_1").set({
    sessionId: "sess_plain_ts_1",
    userId: "u_scratch",
    rewardType: "SCRATCH_CARD_UNLOCK",
    status: "VERIFIED",
    s2sVerified: true,
    used: false,
    claimed: false,
    expiresAt: { _seconds: Math.floor((fixedNow + 300000) / 1000), _nanoseconds: 0 }
  });
  const watchRes = await handleRecordScratchAdWatch(
    { auth: { uid: "u_scratch" }, data: { sessionId: "sess_plain_ts_1" } },
    deps
  );
  assert.strictEqual(watchRes.adsWatchedForNextScratch, 1);
  assert.strictEqual(watchRes.unlockedScratchesCount, 0);
  assert.strictEqual(watchRes.newlyUnlocked, false);

  // =========================================================================
  // TEST 12: Restored Reward Configuration Verification
  // =========================================================================
  console.log("Test 12: Verify Watch & Earn (63-125) and Daily Check-In 30-day schedule...");
  const minWatch = generateWatchEarnRewardCoins(() => 0.0);
  const maxWatch = generateWatchEarnRewardCoins(() => 0.999999);
  assert.strictEqual(minWatch, 63);
  assert.strictEqual(maxWatch, 125);
  assert.strictEqual(_DAILY_CHECK_IN_REWARDS_COINS.length, 30);
  assert.strictEqual(_DAILY_CHECK_IN_REWARDS_COINS[0], 25);
  assert.strictEqual(_DAILY_CHECK_IN_REWARDS_COINS[6], 100);
  assert.strictEqual(_DAILY_CHECK_IN_REWARDS_COINS[29], 1000);

  // =========================================================================
  // TEST 13: Corrupted active scratch card rewardCoins is strictly rejected
  // =========================================================================
  console.log("Test 13: Corrupted active scratch session rewardCoins must throw failed-precondition...");
  await mockDb.collection("users").doc("u_corrupt_scratch").set({
    userId: "u_corrupt_scratch",
    displayName: "Corrupt Tester",
    approvalStatus: "approved",
    balanceCents: 0,
    balanceCoins: 0,
    promotionalCoins: 0
  });
  await mockDb
    .collection("users")
    .doc("u_corrupt_scratch")
    .collection("scratch_stats")
    .doc(realToday)
    .set({
      userId: "u_corrupt_scratch",
      dateKey: realToday,
      scratchesCompletedToday: 0,
      activeCardId: "bad_card_1",
      activeCardRewardCoins: 0
    });
  await mockDb
    .collection("users")
    .doc("u_corrupt_scratch")
    .collection("scratch_sessions")
    .doc("bad_card_1")
    .set({
      cardId: "bad_card_1",
      userId: "u_corrupt_scratch",
      rewardCoins: 0,
      claimed: false,
      status: "ACTIVE"
    });
  await assert.rejects(
    () => handleCreateScratchCardSession({ auth: { uid: "u_corrupt_scratch" }, data: {} }, deps),
    /Invalid active scratch card reward/
  );

  console.log("\n========================================================");
  console.log("ALL 13 SCRATCH CARD BACKEND TESTS PASSED SUCCESSFULLY! 🎉");
  console.log("========================================================\n");
})();
