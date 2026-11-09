const crypto = require("crypto");
const assert = require("assert");
const { _verifyUnityS2SSignature } = require("./index.js");

// Unit / Mock Verification of S2S Callback + 2-Step Claim Security Invariants
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

  assert.strictEqual(_verifyUnityS2SSignature({ ...queryParams, hmac }, secret), true);
  assert.strictEqual(_verifyUnityS2SSignature({ ...queryParams, hmac: "0".repeat(32) }, secret), false);

  // 2. Simulate Atomic Session Lifecycle: PENDING -> VERIFIED -> CLAIMED (single-use)
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
    "user_1_today": { videosWatched: 24, totalEarnedCents: 2400, totalEarnedPoints: 24 }
  };

  function processS2SCallback(oid, sid) {
    if (processedOids.has(oid)) throw new Error("Duplicate transaction");
    const session = sessions[sid];
    if (!session || session.used || session.claimed || session.expiresAtMillis < Date.now()) {
      throw new Error("Invalid or expired session");
    }
    processedOids.add(oid);
    session.s2sVerified = true;
    session.status = "VERIFIED";
  }

  function claimSession(authUid, sid) {
    const session = sessions[sid];
    if (!session) throw new Error("Session not found");
    if (session.userId !== authUid) throw new Error("Mismatched user");
    if (session.used || session.claimed) throw new Error("Already claimed");
    if (session.expiresAtMillis < Date.now()) throw new Error("Expired session");
    if (!session.s2sVerified && session.status !== "VERIFIED") {
      throw new Error("Reward video not verified");
    }
    const stats = watchStats[`${authUid}_today`];
    if (stats.videosWatched >= 25 || stats.totalEarnedPoints + 1 > 40) {
      throw new Error("Daily limit exceeded");
    }
    session.used = true;
    session.claimed = true;
    session.status = "CLAIMED";
    stats.videosWatched += 1;
    stats.totalEarnedCents += session.rewardCents;
    stats.totalEarnedPoints += 1;
    return stats;
  }

  // Attempting to claim BEFORE video completion / verification must fail
  assert.throws(() => claimSession("user_1", sessionId), /Reward video not verified/);

  // S2S Callback marks session VERIFIED
  processS2SCallback("unity_order_1001", sessionId);
  assert.strictEqual(sessions[sessionId].status, "VERIFIED");

  // Duplicate OID callback must be rejected
  assert.throws(() => processS2SCallback("unity_order_1001", sessionId), /Duplicate transaction/);

  // Wrong user attempting to claim must be rejected
  assert.throws(() => claimSession("user_2", sessionId), /Mismatched user/);

  // Valid user claims the 25th video -> succeeds
  const updatedStats = claimSession("user_1", sessionId);
  assert.strictEqual(updatedStats.videosWatched, 25);
  assert.strictEqual(sessions[sessionId].claimed, true);

  // Re-claiming the same session must be rejected
  assert.throws(() => claimSession("user_1", sessionId), /Already claimed/);

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
  assert.throws(() => claimSession("user_1", session26), /Daily limit exceeded/);

  console.log("All security unit/mock tests passed!");
})();
