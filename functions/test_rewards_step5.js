const crypto = require("crypto");
const assert = require("assert");
const {
  _rewardHandlers,
  _DAILY_CHECK_IN_REWARDS_COINS,
  _SPIN_SEGMENTS_COINS,
  _getTodayDateString,
  _getYesterdayDateString
} = require("./index.js");

const {
  handleCreateRewardSession,
  handleUnityAdsS2SCallback,
  handleClaimRewardSession,
  handleClaimDailyCheckIn,
  handleCreateSpinSession,
  handleRecordExtraSpinAdWatch,
  handleClaimSpinReward
} = _rewardHandlers;

/**
 * In-memory Firestore simulator with subcollections, timestamp support,
 * atomic rollback, and optimistic-concurrency transaction retries.
 */
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
        const prev = store.get(path);
        const resolved = resolveValue(data, Date.now());
        const nextVer = (prev ? prev.version : 0) + 1;
        if (options && options.merge && prev) {
          store.set(path, { data: { ...clone(prev.data), ...resolved }, version: nextVer });
        } else {
          store.set(path, { data: resolved, version: nextVer });
        }
      }
    };
  }

  function makeCollectionRef(path) {
    return {
      path,
      doc(docId) {
        const resolvedId = docId || `auto_${++autoIdCounter}`;
        return makeDocRef(`${path}/${resolvedId}`);
      }
    };
  }

  async function runTransaction(fn) {
    const MAX_ATTEMPTS = 6;
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
        const prev = store.get(w.path);
        const nextVersion = (prev ? prev.version : 0) + 1;
        const resolved = resolveValue(w.data, serverNow);
        if (w.op === "update") {
          if (!prev) {
            throw new Error(`NOT_FOUND: Cannot update missing doc ${w.path}`);
          }
          store.set(w.path, {
            data: { ...clone(prev.data), ...resolved },
            version: nextVersion
          });
        } else if (w.op === "set" && w.merge && prev) {
          store.set(w.path, {
            data: { ...clone(prev.data), ...resolved },
            version: nextVersion
          });
        } else {
          store.set(w.path, {
            data: resolved,
            version: nextVersion
          });
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

function makeDeps(mockDb, nowRef, overrides = {}) {
  let seq = 0;
  return {
    db: mockDb,
    now: () => nowRef.value,
    secret: "unity_step5_test_secret",
    serverTimestamp: () => new Date(nowRef.value),
    timestampFromMillis: (ms) => new Date(ms),
    sessionIdGenerator: () => `sess_${++seq}_${crypto.randomBytes(4).toString("hex")}`,
    ...overrides
  };
}

function makeSignedUnityReq(orderId, sessionId, secret = "unity_step5_test_secret") {
  const query = {
    adid: "800430673",
    oid: orderId,
    sid: sessionId
  };
  const paramStr = Object.keys(query)
    .sort()
    .map((k) => `${k}=${query[k]}`)
    .join(",");
  const hmac = crypto.createHmac("md5", secret).update(paramStr, "utf8").digest("hex");
  return { method: "GET", query: { ...query, hmac } };
}

function makeMockRes() {
  const res = {
    statusCode: 0,
    body: null,
    status(code) {
      this.statusCode = code;
      return this;
    },
    send(payload) {
      this.body = payload;
      return this;
    }
  };
  return res;
}

async function expectReject(promise, expectedCode, messageSubstring) {
  let failed = false;
  try {
    await promise;
  } catch (err) {
    failed = true;
    if (expectedCode) {
      assert.strictEqual(
        err.code,
        expectedCode,
        `Expected error code '${expectedCode}', got '${err.code}' (${err.message})`
      );
    }
    if (messageSubstring) {
      assert.ok(
        String(err.message).toLowerCase().includes(messageSubstring.toLowerCase()),
        `Expected '${err.message}' to include '${messageSubstring}'`
      );
    }
  }
  assert.ok(failed, `Expected operation to reject with ${expectedCode || "error"}`);
}

(async () => {
  console.log("Starting TaskFlow Step 5 Reward Backend Security & Consistency Test Suite...");

  const FIXED_START = Date.UTC(2026, 10, 14, 10, 0, 0); // 2026-11-14 15:30 IST
  const aliceAuth = { uid: "alice", token: { email: "alice@example.com" } };
  const bobAuth = { uid: "bob", token: { email: "bob@example.com" } };
  const pendingAuth = { uid: "pending_u", token: { email: "pending@example.com" } };

  // =========================================================================
  // TEST 1: Spin Wheel — Cooldown, Stored Reward, Idempotent Claim, Wallet & Leaderboard
  // =========================================================================
  console.log("Test 1: Spin Wheel cooldown, stored segment/reward, idempotent claim & leaderboard...");
  {
    const nowRef = { value: FIXED_START };
    const db = createMockFirestore();
    // random = 0.99 picks last segment index 7 -> 150 Coins
    const deps = makeDeps(db, nowRef, { random: () => 0.99 });

    db.seed("users/alice", {
      userId: "alice",
      displayName: "Alice",
      approvalStatus: "approved",
      completedTasksCount: 4,
      balanceCents: 200, // ₹2.00 = 250 eligible Coins
      eligibleCoins: 250,
      promotionalCoins: 50,
      balanceCoins: 300
    });
    db.seed("users/pending_u", {
      userId: "pending_u",
      displayName: "Pending",
      approvalStatus: "pending",
      balanceCents: 0,
      promotionalCoins: 0,
      balanceCoins: 0
    });

    // Unauthenticated and pending rejected
    await expectReject(handleCreateSpinSession({ auth: null }, deps), "unauthenticated");
    await expectReject(handleCreateSpinSession({ auth: pendingAuth }, deps), "permission-denied");

    // 1. Free spin succeeds and stores segmentIndex & rewardCoins on server
    const spin1 = await handleCreateSpinSession({ auth: aliceAuth }, deps);
    assert.strictEqual(spin1.usedFreeSpin, true);
    assert.strictEqual(spin1.spinNumber, 1);
    assert.strictEqual(spin1.segmentIndex, 7);
    assert.strictEqual(spin1.rewardCoins, _SPIN_SEGMENTS_COINS[7]); // 150
    assert.strictEqual(spin1.rewardCoins, 150);

    const spinDoc = db.read(`users/alice/spin_sessions/${spin1.sessionId}`);
    assert.ok(spinDoc);
    assert.strictEqual(spinDoc.segmentIndex, 7);
    assert.strictEqual(spinDoc.rewardCoins, 150);
    assert.strictEqual(spinDoc.claimed, false);
    assert.strictEqual(spinDoc.status, "SPUN");

    // 2. Immediate 2nd spin blocked by 24-hour free spin cooldown
    await expectReject(
      handleCreateSpinSession({ auth: aliceAuth }, deps),
      "failed-precondition",
      "24-hour cooldown"
    );

    // 3. Even if RNG changes before claim, claim uses STORED reward (150), not a fresh random roll!
    deps.random = () => 0.0; // Would pick segment 0 (10 Coins) if wrongly rolled at claim time!

    // Cross-user claim rejected
    db.seed("users/bob", {
      userId: "bob",
      displayName: "Bob",
      approvalStatus: "approved",
      balanceCents: 0,
      promotionalCoins: 0,
      balanceCoins: 0
    });
    await expectReject(
      handleClaimSpinReward({ auth: bobAuth, data: { sessionId: spin1.sessionId } }, deps),
      "not-found"
    );

    // Concurrent double claim: exactly 1 succeeds, 1 rejects with already-exists
    const claimRace = await Promise.allSettled([
      handleClaimSpinReward({ auth: aliceAuth, data: { sessionId: spin1.sessionId, rewardCoins: 99999 } }, deps),
      handleClaimSpinReward({ auth: aliceAuth, data: { sessionId: spin1.sessionId } }, deps)
    ]);
    const winners = claimRace.filter((r) => r.status === "fulfilled");
    const losers = claimRace.filter((r) => r.status === "rejected");
    assert.strictEqual(winners.length, 1);
    assert.strictEqual(losers.length, 1);
    assert.strictEqual(losers[0].reason.code, "already-exists");

    const claimRes = winners[0].value;
    assert.strictEqual(claimRes.segmentIndex, 7);
    assert.strictEqual(claimRes.rewardCoins, 150);
    assert.strictEqual(claimRes.newPromotionalCoins, 200); // 50 + 150
    assert.strictEqual(claimRes.newBalanceCoins, 450);     // 300 + 150

    const aliceAfter = db.read("users/alice");
    assert.strictEqual(aliceAfter.balanceCents, 200, "balanceCents must be preserved");
    assert.strictEqual(aliceAfter.eligibleCoins, 250, "eligibleCoins must be preserved");
    assert.strictEqual(aliceAfter.promotionalCoins, 200);
    assert.strictEqual(aliceAfter.balanceCoins, 450);

    const lbAlice = db.read("leaderboard/alice");
    assert.strictEqual(lbAlice.balanceCents, 200);
    assert.strictEqual(lbAlice.balanceCoins, 450);
    assert.strictEqual(lbAlice.completedTasksCount, 4);
    assert.strictEqual(lbAlice.approvalStatus, "approved");
    assert.strictEqual(lbAlice.email, undefined);

    const spinTx = db.read(`transactions/spin_${spin1.sessionId}`);
    assert.strictEqual(spinTx.type, "SPIN_EARN");
    assert.strictEqual(spinTx.amountCoins, 150);
    assert.strictEqual(spinTx.referenceId, spin1.sessionId);

    const spinNotif = db.read(`users/alice/notifications/notif_spin_${spin1.sessionId}`);
    assert.strictEqual(spinNotif.type, "SPIN_EARN_REWARD");
    assert.strictEqual(spinNotif.rewardCoins, 150);

    // Sequential duplicate claim fails
    await expectReject(
      handleClaimSpinReward({ auth: aliceAuth, data: { sessionId: spin1.sessionId } }, deps),
      "already-exists"
    );

    // 4. Advance server time 24 hours + 1 second -> Free Spin available again
    nowRef.value += 24 * 60 * 60 * 1000 + 1000;
    const spin2 = await handleCreateSpinSession({ auth: aliceAuth }, deps);
    assert.strictEqual(spin2.usedFreeSpin, true);
    assert.strictEqual(spin2.segmentIndex, 0);
    assert.strictEqual(spin2.rewardCoins, 10);

    // 5. Daily 50-spin limit enforced
    const todayKey = _getTodayDateString(new Date(nowRef.value));
    db.seed(`users/alice/spin_stats/${todayKey}`, { spinsUsed: 50, dateKey: todayKey });
    await expectReject(
      handleCreateSpinSession({ auth: aliceAuth }, deps),
      "resource-exhausted",
      "50"
    );
  }

  // =========================================================================
  // TEST 2: Extra-Spin S2S Ad Verification (2 independent ads, replay blocked)
  // =========================================================================
  console.log("Test 2: Extra-Spin S2S verification, replay/tampering rejection & consumption...");
  {
    const nowRef = { value: FIXED_START };
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef, { random: () => 0.3 });

    db.seed("users/alice", {
      userId: "alice",
      displayName: "Alice",
      approvalStatus: "approved",
      completedTasksCount: 2,
      balanceCents: 100,
      eligibleCoins: 125,
      promotionalCoins: 0,
      balanceCoins: 125,
      lastFreeSpinAt: new Date(nowRef.value - 60_000) // On 24h cooldown
    });

    // Cannot spin while on cooldown
    await expectReject(
      handleCreateSpinSession({ auth: aliceAuth }, deps),
      "failed-precondition"
    );

    // Create EXTRA_SPIN_UNLOCK session 1
    const ad1 = await handleCreateRewardSession(
      { auth: aliceAuth, data: { rewardType: "EXTRA_SPIN_UNLOCK" } },
      deps
    );
    assert.strictEqual(ad1.rewardType, "EXTRA_SPIN_UNLOCK");

    // Must reject BEFORE Unity S2S verification
    await expectReject(
      handleRecordExtraSpinAdWatch(
        {
          auth: aliceAuth,
          data: { sessionId: ad1.sessionId, extraSpinVideosWatched: 2, extraSpinAvailable: true }
        },
        deps
      ),
      "failed-precondition",
      "not been verified"
    );

    // Verify Ad 1 via real handleUnityAdsS2SCallback
    const res1 = makeMockRes();
    await handleUnityAdsS2SCallback(makeSignedUnityReq("oid_extra_1", ad1.sessionId), res1, deps);
    assert.strictEqual(res1.statusCode, 200);

    // Wrong endpoint / cross-type rejection: SCRATCH_CARD_UNLOCK rejected by recordExtraSpinAdWatch
    const scratchAd = await handleCreateRewardSession(
      { auth: aliceAuth, data: { rewardType: "SCRATCH_CARD_UNLOCK" } },
      deps
    );
    await handleUnityAdsS2SCallback(makeSignedUnityReq("oid_scratch_wrong", scratchAd.sessionId), makeMockRes(), deps);
    await expectReject(
      handleRecordExtraSpinAdWatch({ auth: aliceAuth, data: { sessionId: scratchAd.sessionId } }, deps),
      "invalid-argument",
      "Extra Spin unlock session"
    );

    // Record Ad 1 -> progress = 1/2, extraSpinAvailable = false
    const watch1 = await handleRecordExtraSpinAdWatch(
      { auth: aliceAuth, data: { sessionId: ad1.sessionId, extraSpinAvailable: true, count: 99 } },
      deps
    );
    assert.strictEqual(watch1.extraSpinVideosWatched, 1);
    assert.strictEqual(watch1.extraSpinAvailable, false);
    assert.strictEqual(watch1.newlyUnlocked, false);

    // Replaying Ad 1 session must fail
    await expectReject(
      handleRecordExtraSpinAdWatch({ auth: aliceAuth, data: { sessionId: ad1.sessionId } }, deps),
      "already-exists"
    );
    // Still cannot spin after 1 ad!
    await expectReject(handleCreateSpinSession({ auth: aliceAuth }, deps), "failed-precondition");

    // Watch Ad 2 with its own unique session & Unity order
    const ad2 = await handleCreateRewardSession(
      { auth: aliceAuth, data: { rewardType: "EXTRA_SPIN_UNLOCK" } },
      deps
    );
    // Replaying oid_extra_1 for Ad 2 fails duplicate OID guard cleanly without verifying ad2!
    const replayOidRes = makeMockRes();
    await handleUnityAdsS2SCallback(makeSignedUnityReq("oid_extra_1", ad2.sessionId), replayOidRes, deps);
    assert.strictEqual(db.read(`reward_sessions/${ad2.sessionId}`).s2sVerified, false);

    // Legitimate S2S callback oid_extra_2 verifies ad2
    await handleUnityAdsS2SCallback(makeSignedUnityReq("oid_extra_2", ad2.sessionId), makeMockRes(), deps);
    const watch2 = await handleRecordExtraSpinAdWatch(
      { auth: aliceAuth, data: { sessionId: ad2.sessionId } },
      deps
    );
    assert.strictEqual(watch2.extraSpinVideosWatched, 2);
    assert.strictEqual(watch2.extraSpinAvailable, true);
    assert.strictEqual(watch2.newlyUnlocked, true);

    // Now Extra Spin succeeds, consumes extraSpinAvailable -> false & extraSpinVideosWatched -> 0
    const extraSpin = await handleCreateSpinSession({ auth: aliceAuth }, deps);
    assert.strictEqual(extraSpin.usedFreeSpin, false);
    const alicePostSpin = db.read("users/alice");
    assert.strictEqual(alicePostSpin.extraSpinAvailable, false);
    assert.strictEqual(alicePostSpin.extraSpinVideosWatched, 0);

    // Claiming Extra Spin credits promotionalCoins & balanceCoins, preserves balanceCents
    const extraClaim = await handleClaimSpinReward(
      { auth: aliceAuth, data: { sessionId: extraSpin.sessionId } },
      deps
    );
    assert.strictEqual(extraClaim.rewardCoins, extraSpin.rewardCoins);
    assert.strictEqual(db.read("users/alice").balanceCents, 100);
    assert.strictEqual(db.read("users/alice").promotionalCoins, extraSpin.rewardCoins);
    assert.strictEqual(db.read("users/alice").balanceCoins, 125 + extraSpin.rewardCoins);
  }

  // =========================================================================
  // TEST 3: Watch & Earn — Independent 2-Ad S2S Verification, Replay & Wallet Math
  // =========================================================================
  console.log("Test 3: Watch & Earn independent 2-ad S2S verification, wallet & leaderboard...");
  {
    const nowRef = { value: FIXED_START };
    const db = createMockFirestore();
    // generateWatchEarnRewardCoins(0.5) => floor(0.5 * 63) + 63 = 94 Coins
    const deps = makeDeps(db, nowRef, { random: () => 0.5 });

    db.seed("users/alice", {
      userId: "alice",
      displayName: "Alice",
      approvalStatus: "approved",
      completedTasksCount: 7,
      balanceCents: 400, // ₹4.00 = 500 eligibleCoins
      eligibleCoins: 500,
      promotionalCoins: 100,
      balanceCoins: 600
    });
    db.seed("users/bob", {
      userId: "bob",
      displayName: "Bob",
      approvalStatus: "approved",
      balanceCents: 0,
      promotionalCoins: 0,
      balanceCoins: 0
    });

    const w1 = await handleCreateRewardSession(
      { auth: aliceAuth, data: { rewardType: "WATCH_AND_EARN" } },
      deps
    );
    assert.strictEqual(w1.rewardCoins, 94);

    // Verify Ad 1 via S2S
    await handleUnityAdsS2SCallback(makeSignedUnityReq("oid_watch_1", w1.sessionId), makeMockRes(), deps);

    // 1. Missing secondSessionId MUST FAIL
    await expectReject(
      handleClaimRewardSession({ auth: aliceAuth, data: { sessionId: w1.sessionId } }, deps),
      "failed-precondition",
      "second"
    );

    // 2. Reusing Ad 1 verification for Ad 2 MUST FAIL
    await expectReject(
      handleClaimRewardSession(
        { auth: aliceAuth, data: { sessionId: w1.sessionId, secondSessionId: w1.sessionId } },
        deps
      ),
      "invalid-argument",
      "reuse"
    );

    // 3. Create Ad 2 (WATCH_AND_EARN_CLAIM) — cannot claim before Ad 2 S2S finishes
    const w2 = await handleCreateRewardSession(
      { auth: aliceAuth, data: { rewardType: "WATCH_AND_EARN_CLAIM" } },
      deps
    );
    await expectReject(
      handleClaimRewardSession(
        { auth: aliceAuth, data: { sessionId: w1.sessionId, secondSessionId: w2.sessionId } },
        deps
      ),
      "failed-precondition",
      "Second reward video"
    );

    // Bob's verified session cannot be paired by Alice
    const bobAd = await handleCreateRewardSession(
      { auth: bobAuth, data: { rewardType: "WATCH_AND_EARN_CLAIM" } },
      deps
    );
    await handleUnityAdsS2SCallback(makeSignedUnityReq("oid_bob_watch", bobAd.sessionId), makeMockRes(), deps);
    await expectReject(
      handleClaimRewardSession(
        { auth: aliceAuth, data: { sessionId: w1.sessionId, secondSessionId: bobAd.sessionId } },
        deps
      ),
      "permission-denied"
    );

    // Verify Ad 2 via S2S
    await handleUnityAdsS2SCallback(makeSignedUnityReq("oid_watch_2", w2.sessionId), makeMockRes(), deps);

    // 4. Concurrent double claim with client reward tampering -> only 1 succeeds with exact 94 Coins
    const race = await Promise.allSettled([
      handleClaimRewardSession(
        { auth: aliceAuth, data: { sessionId: w1.sessionId, secondSessionId: w2.sessionId, rewardCoins: 99999 } },
        deps
      ),
      handleClaimRewardSession(
        { auth: aliceAuth, data: { sessionId: w1.sessionId, secondSessionId: w2.sessionId } },
        deps
      )
    ]);
    const ok = race.filter((r) => r.status === "fulfilled");
    const err = race.filter((r) => r.status === "rejected");
    assert.strictEqual(ok.length, 1);
    assert.strictEqual(err.length, 1);
    assert.strictEqual(err[0].reason.code, "already-exists");

    const res = ok[0].value;
    assert.strictEqual(res.rewardCoins, 94);
    assert.strictEqual(res.videosWatched, 1);
    assert.strictEqual(res.newPromotionalCoins, 194); // 100 + 94
    assert.strictEqual(res.newBalanceCoins, 694);     // 600 + 94

    const aliceAfter = db.read("users/alice");
    assert.strictEqual(aliceAfter.balanceCents, 400, "balanceCents MUST be preserved");
    assert.strictEqual(aliceAfter.eligibleCoins, 500, "eligibleCoins MUST be preserved");
    assert.strictEqual(aliceAfter.promotionalCoins, 194);
    assert.strictEqual(aliceAfter.balanceCoins, 694);

    const lb = db.read("leaderboard/alice");
    assert.strictEqual(lb.balanceCents, 400);
    assert.strictEqual(lb.balanceCoins, 694);
    assert.strictEqual(lb.completedTasksCount, 7);
    assert.strictEqual(lb.approvalStatus, "approved");

    const todayKey = _getTodayDateString(new Date(nowRef.value));
    assert.strictEqual(db.read(`watch_stats/alice_${todayKey}`).videosWatched, 1);
    assert.strictEqual(db.read(`users/alice/watch_stats/${todayKey}`).totalEarnedCoins, 94);

    // Neither w1 nor w2 can be reused later
    assert.strictEqual(db.read(`reward_sessions/${w1.sessionId}`).claimed, true);
    assert.strictEqual(db.read(`reward_sessions/${w2.sessionId}`).claimed, true);
    const w3 = await handleCreateRewardSession(
      { auth: aliceAuth, data: { rewardType: "WATCH_AND_EARN" } },
      deps
    );
    await handleUnityAdsS2SCallback(makeSignedUnityReq("oid_watch_3", w3.sessionId), makeMockRes(), deps);
    await expectReject(
      handleClaimRewardSession(
        { auth: aliceAuth, data: { sessionId: w3.sessionId, secondSessionId: w2.sessionId } },
        deps
      ),
      "already-exists"
    );

    // Daily 25-video limit enforced
    db.seed(`watch_stats/alice_${todayKey}`, { videosWatched: 25, totalEarnedCoins: 2000 });
    await expectReject(
      handleCreateRewardSession({ auth: aliceAuth, data: { rewardType: "WATCH_AND_EARN" } }, deps),
      "resource-exhausted"
    );
  }

  // =========================================================================
  // TEST 4: Daily Check-In 30-Day Schedule, Same-Day Blocking, Reset, Wallet & Leaderboard
  // =========================================================================
  console.log("Test 4: Daily Check-in 30-day progression, duplicate blocking, reset & wallet...");
  {
    const nowRef = { value: Date.UTC(2026, 10, 1, 10, 0, 0) }; // 2026-11-01 15:30 IST
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);

    db.seed("users/alice", {
      userId: "alice",
      displayName: "Alice",
      approvalStatus: "approved",
      completedTasksCount: 1,
      balanceCents: 100, // ₹1 = 125 eligibleCoins
      eligibleCoins: 125,
      promotionalCoins: 0,
      balanceCoins: 125
    });

    let expectedPromo = 0;
    let expectedBalance = 125;

    // Simulate full 30-day consecutive streak!
    for (let day = 1; day <= 30; day++) {
      const claimRes = await handleClaimDailyCheckIn(
        {
          auth: aliceAuth,
          data: { rewardCoins: 99999, rewardCents: 99999, streakDay: 30 } // Ignored tampering!
        },
        deps
      );
      const expectedDayCoins = _DAILY_CHECK_IN_REWARDS_COINS[day - 1];
      expectedPromo += expectedDayCoins;
      expectedBalance += expectedDayCoins;

      assert.strictEqual(claimRes.streakDay, day);
      assert.strictEqual(claimRes.rewardCoins, expectedDayCoins);
      assert.strictEqual(claimRes.newPromotionalCoins, expectedPromo);
      assert.strictEqual(claimRes.newBalanceCoins, expectedBalance);

      // Duplicate claim on the same Asia/Kolkata date MUST fail
      await expectReject(
        handleClaimDailyCheckIn({ auth: aliceAuth }, deps),
        "already-exists"
      );
      await expectReject(
        handleCreateRewardSession({ auth: aliceAuth, data: { rewardType: "DAILY_CHECK_IN" } }, deps),
        "already-exists"
      );

      // Advance exactly 24h for next IST calendar date
      nowRef.value += 24 * 60 * 60 * 1000;
    }

    const alice30 = db.read("users/alice");
    assert.strictEqual(alice30.balanceCents, 100, "balanceCents preserved across all 30 check-ins");
    assert.strictEqual(alice30.promotionalCoins, expectedPromo);
    assert.strictEqual(alice30.balanceCoins, expectedBalance);
    assert.strictEqual(alice30.checkInStreak, 30);

    const lb = db.read("leaderboard/alice");
    assert.strictEqual(lb.balanceCents, 100);
    assert.strictEqual(lb.balanceCoins, expectedBalance);

    // Day 31 wraps back to Day 1 (25 Coins), tested via createRewardSession + claimRewardSession!
    const sDay1 = await handleCreateRewardSession(
      { auth: aliceAuth, data: { rewardType: "DAILY_CHECK_IN" } },
      deps
    );
    assert.strictEqual(sDay1.streakDay, 1);
    assert.strictEqual(sDay1.rewardCoins, 25);

    const raceCheckIn = await Promise.allSettled([
      handleClaimRewardSession({ auth: aliceAuth, data: { sessionId: sDay1.sessionId } }, deps),
      handleClaimDailyCheckIn({ auth: aliceAuth }, deps)
    ]);
    assert.strictEqual(raceCheckIn.filter((r) => r.status === "fulfilled").length, 1);
    assert.strictEqual(raceCheckIn.filter((r) => r.status === "rejected").length, 1);

    // Skip 2 days -> streak resets cleanly to Day 1 (25 Coins)
    nowRef.value += 2 * 24 * 60 * 60 * 1000;
    const resetClaim = await handleClaimDailyCheckIn({ auth: aliceAuth }, deps);
    assert.strictEqual(resetClaim.streakDay, 1);
    assert.strictEqual(resetClaim.rewardCoins, 25);

    // IST midnight boundary test: claiming at 18:20 UTC (= 23:50 IST on 2026-12-03) -> Day 1,
    // retrying at 18:25 UTC (same IST day) -> rejected, advancing to 18:35 UTC (= 00:05 IST on 2026-12-04) -> Day 2!
    const bobIstStart = Date.UTC(2026, 11, 3, 18, 20, 0);
    nowRef.value = bobIstStart;
    db.seed("users/bob", {
      userId: "bob",
      displayName: "Bob",
      approvalStatus: "approved",
      completedTasksCount: 0,
      balanceCents: 0,
      promotionalCoins: 0,
      balanceCoins: 0
    });
    assert.strictEqual(_getTodayDateString(new Date(bobIstStart)), "2026-12-03");
    assert.strictEqual(_getYesterdayDateString(new Date(bobIstStart)), "2026-12-02");

    const istDay1 = await handleClaimDailyCheckIn({ auth: bobAuth }, deps);
    assert.strictEqual(istDay1.dateKey, "2026-12-03");
    assert.strictEqual(istDay1.streakDay, 1);
    assert.strictEqual(istDay1.rewardCoins, 25);

    nowRef.value += 5 * 60 * 1000; // 23:55 IST (still 2026-12-03)
    await expectReject(handleClaimDailyCheckIn({ auth: bobAuth }, deps), "already-exists");

    nowRef.value += 10 * 60 * 1000; // 00:05 IST on 2026-12-04
    const istDay2 = await handleClaimDailyCheckIn({ auth: bobAuth }, deps);
    assert.strictEqual(istDay2.dateKey, "2026-12-04");
    assert.strictEqual(istDay2.streakDay, 2);
    assert.strictEqual(istDay2.rewardCoins, 35);
  }

  console.log("========================================================");
  console.log("ALL STEP 5 REWARD BACKEND SECURITY & CONSISTENCY TESTS PASSED! 🎉");
  console.log("========================================================");
})();
