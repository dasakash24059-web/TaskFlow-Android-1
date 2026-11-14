const crypto = require("crypto");
const assert = require("assert");
const {
  _verifyUnityS2SSignature,
  _getDailyCheckInRewardCents,
  _calculateServerDailyCheckInState,
  _resolveUnityS2SSecret
} = require("./index.js");

// Unit / Mock Verification of Phase 1 Security Hardening Invariants
(async () => {
  const secret = "unity_prod_secret_key";
  const sessionId = crypto.randomBytes(16).toString("hex");

  // 1. Official Unity S2S HMAC-MD5 verification with sid = sessionId
  const queryParams = {
    adid: "800430673",
    oid: "unity_order_1001",
    sid: sessionId
  };
  const paramString = Object.keys(queryParams)
    .sort()
    .map((k) => `${k}=${queryParams[k]}`)
    .join(",");
  const hmac = crypto.createHmac("md5", secret).update(paramString).digest("hex");

  // Valid HMAC with valid secret passes
  assert.strictEqual(_verifyUnityS2SSignature({ ...queryParams, hmac }, secret), true);
  // Invalid HMAC fails
  assert.strictEqual(_verifyUnityS2SSignature({ ...queryParams, hmac: "0".repeat(32) }, secret), false);
  // Missing / blank UNITY_S2S_SECRET fails (mandatory in production)
  assert.strictEqual(_verifyUnityS2SSignature({ ...queryParams, hmac }, ""), false);
  assert.strictEqual(_verifyUnityS2SSignature({ ...queryParams, hmac }, undefined), false);

  // _resolveUnityS2SSecret returns empty string when unset and resolves env var when set
  delete process.env.UNITY_S2S_SECRET;
  delete process.env.UNITY_ADS_SECRET;
  assert.strictEqual(_resolveUnityS2SSecret(), "");
  process.env.UNITY_S2S_SECRET = "prod_secret_abc";
  assert.strictEqual(_resolveUnityS2SSecret(), "prod_secret_abc");
  delete process.env.UNITY_S2S_SECRET;

  // 2. Server-side 30-Day Daily Check-in Reward & Eligibility Validation (_getDailyCheckInRewardCoins & _calculateServerDailyCheckInState)
  const expected30DayRewards = [
    25, 35, 50, 60, 75, 85, 100, 110, 125, 150,
    160, 175, 185, 200, 210, 225, 250, 260, 275, 285,
    300, 325, 350, 375, 400, 425, 450, 500, 750, 1000
  ];
  for (let day = 1; day <= 30; day++) {
    assert.strictEqual(
      _getDailyCheckInRewardCents(day),
      expected30DayRewards[day - 1],
      `Day ${day} must equal ${expected30DayRewards[day - 1]} Coins`
    );
  }
  assert.throws(() => _getDailyCheckInRewardCents(0), /Invalid streakDay/);
  assert.throws(() => _getDailyCheckInRewardCents(31), /Invalid streakDay/);

  // Consecutive yesterday -> today streak progression (Days 1..30)
  for (let prevStreak = 1; prevStreak <= 29; prevStreak++) {
    const state = _calculateServerDailyCheckInState("2026-11-10", prevStreak, "2026-11-11", "2026-11-10");
    assert.strictEqual(state.canCheckInToday, true);
    assert.strictEqual(state.expectedStreakDay, prevStreak + 1);
    assert.strictEqual(state.rewardCents, expected30DayRewards[prevStreak]);
  }

  // Day 30 consecutive wraps back to Day 1 (25 Coins)
  const wrapState = _calculateServerDailyCheckInState("2026-11-10", 30, "2026-11-11", "2026-11-10");
  assert.strictEqual(wrapState.canCheckInToday, true);
  assert.strictEqual(wrapState.expectedStreakDay, 1);
  assert.strictEqual(wrapState.rewardCents, 25);

  // Same-date claim attempt (lastCheckInDate === today) -> canCheckInToday is false
  const duplicateTodayState = _calculateServerDailyCheckInState("2026-11-11", 3, "2026-11-11", "2026-11-10");
  assert.strictEqual(duplicateTodayState.canCheckInToday, false);

  // Missed day resets streak to Day 1 (25 Coins)
  const resetState = _calculateServerDailyCheckInState("2026-11-08", 15, "2026-11-11", "2026-11-10");
  assert.strictEqual(resetState.canCheckInToday, true);
  assert.strictEqual(resetState.expectedStreakDay, 1);
  assert.strictEqual(resetState.rewardCents, 25);

  // 3. Simulate Atomic Session Lifecycle: PENDING -> VERIFIED -> CLAIMED (single-use)
  const sessions = {
    [sessionId]: {
      sessionId,
      userId: "user_1",
      rewardType: "WATCH_AND_EARN",
      rewardCents: 100,
      status: "PENDING",
      s2sVerified: false,
      used: false,
      claimed: false,
      expiresAtMillis: Date.now() + 600000
    }
  };
  const processedOids = new Set();
  const watchStats = {
    "user_1_today": { videosWatched: 24, totalEarnedCents: 2400 }
  };

  function processS2SCallback(oid, sid, configuredSecret) {
    if (!configuredSecret || !configuredSecret.trim()) {
      throw new Error("Server misconfiguration: UNITY_S2S_SECRET is missing");
    }
    if (processedOids.has(oid)) throw new Error("Duplicate transaction");
    const session = sessions[sid];
    if (!session || session.used || session.claimed || session.expiresAtMillis < Date.now()) {
      throw new Error("Invalid or expired session");
    }
    processedOids.add(oid);
    session.s2sVerified = true;
    session.status = "VERIFIED";
  }

  function claimSession(authUid, userApprovalStatus, sid) {
    if (userApprovalStatus !== "approved") {
      throw new Error("Account is not approved");
    }
    const session = sessions[sid];
    if (!session) throw new Error("Session not found");
    if (session.userId !== authUid) throw new Error("Mismatched user");
    if (session.used || session.claimed || session.status === "CLAIMED") throw new Error("Already claimed");
    if (session.expiresAtMillis < Date.now()) throw new Error("Expired session");
    if (session.s2sVerified !== true || session.status !== "VERIFIED") {
      throw new Error("Reward video not verified");
    }
    const stats = watchStats[`${authUid}_today`];
    if (stats.videosWatched >= 25 || stats.totalEarnedCents + session.rewardCents > 2500) {
      throw new Error("Daily limit exceeded");
    }
    session.used = true;
    session.claimed = true;
    session.status = "CLAIMED";
    stats.videosWatched += 1;
    stats.totalEarnedCents += session.rewardCents;
    return stats;
  }

  // Missing S2S secret on server must reject callback
  assert.throws(() => processS2SCallback("unity_order_1001", sessionId, ""), /UNITY_S2S_SECRET is missing/);

  // Attempting to claim BEFORE S2S verification must fail
  assert.throws(() => claimSession("user_1", "approved", sessionId), /Reward video not verified/);

  // S2S Callback marks session VERIFIED
  processS2SCallback("unity_order_1001", sessionId, secret);
  assert.strictEqual(sessions[sessionId].status, "VERIFIED");
  assert.strictEqual(sessions[sessionId].s2sVerified, true);

  // Duplicate OID callback must be rejected
  assert.throws(() => processS2SCallback("unity_order_1001", sessionId, secret), /Duplicate transaction/);

  // Pending user attempting to claim must be rejected
  assert.throws(() => claimSession("user_1", "pending", sessionId), /Account is not approved/);

  // Wrong user attempting to claim must be rejected
  assert.throws(() => claimSession("user_2", "approved", sessionId), /Mismatched user/);

  // Valid approved user claims the 25th video -> succeeds
  const updatedStats = claimSession("user_1", "approved", sessionId);
  assert.strictEqual(updatedStats.videosWatched, 25);
  assert.strictEqual(sessions[sessionId].claimed, true);

  // Re-claiming the same session must be rejected
  assert.throws(() => claimSession("user_1", "approved", sessionId), /Already claimed/);

  // Attempting 26th video on the same day must be rejected
  const session26 = "session_26";
  sessions[session26] = {
    sessionId: session26,
    userId: "user_1",
    rewardType: "WATCH_AND_EARN",
    rewardCents: 100,
    status: "VERIFIED",
    s2sVerified: true,
    used: false,
    claimed: false,
    expiresAtMillis: Date.now() + 600000
  };
  assert.throws(() => claimSession("user_1", "approved", session26), /Daily limit exceeded/);

  console.log("All Phase 1 security unit/mock tests passed!");
})();
