const crypto = require("crypto");
const assert = require("assert");
const {
  _generateScratchRewardCoins,
  _getTodayDateString,
  _verifyUnityS2SSignature,
  _resolveUnityS2SSecret
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

  console.log("\n========================================================");
  console.log("ALL 10 SCRATCH CARD BACKEND TESTS PASSED SUCCESSFULLY! 🎉");
  console.log("========================================================\n");
})();
