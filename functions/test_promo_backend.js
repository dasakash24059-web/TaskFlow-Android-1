const assert = require("assert");
const { _promoHandlers } = require("./index.js");

const {
  normalizePromoCode,
  parseStrictPromoExpiryMillis,
  handleCreatePromoCode,
  handleSetPromoCodeActiveStatus,
  handleRedeemPromoCode
} = _promoHandlers;

/**
 * In-memory Firestore simulator supporting subcollections, atomic rollback,
 * and optimistic-concurrency transaction retries (mirroring Firestore Admin SDK).
 */
function createMockFirestore() {
  const store = new Map(); // path -> { data: object, version: number }
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
    const MAX_ATTEMPTS = 5;
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      const readVersions = new Map();
      const pendingWrites = [];

      const transaction = {
        async get(docRef) {
          // Yield a microtask so concurrent transactions genuinely interleave
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

      // Atomic optimistic-concurrency validation at commit time
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

      // Apply all writes atomically
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

function makeDeps(mockDb, fixedNowRef) {
  return {
    db: mockDb,
    now: () => fixedNowRef.value,
    serverTimestamp: () => ({ __isServerTimestamp: true })
  };
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
        `Expected error message to include '${messageSubstring}', got '${err.message}'`
      );
    }
  }
  assert.ok(failed, `Expected operation to reject with ${expectedCode || "error"}`);
}

(async () => {
  console.log("Starting TaskFlow Promo Code Backend Security & Concurrency Test Suite...");

  const nowRef = { value: 1_763_100_000_000 }; // Fixed server reference time
  const FUTURE_EXPIRY = nowRef.value + 24 * 60 * 60 * 1000;

  const adminAuth = {
    uid: "admin_uid_1",
    token: { email: "dasakash24059@gmail.com", email_verified: true }
  };
  const claimAdminAuth = {
    uid: "admin_claim_uid",
    token: { admin: true, email: "ops@taskflow.app" }
  };
  const aliceAuth = {
    uid: "user_alice",
    token: { email: "alice@example.com", email_verified: true }
  };
  const bobAuth = {
    uid: "user_bob",
    token: { email: "bob@example.com", email_verified: true }
  };

  // =========================================================================
  // TEST 1: Admin-only code creation & code format normalization
  // =========================================================================
  console.log("Test 1: Admin-only code creation and spoofed role rejection...");
  {
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);

    // Normal user with spoofed role: "admin" in /users/{uid} must still be DENIED
    db.seed("users/user_alice", {
      userId: "user_alice",
      displayName: "Alice",
      role: "admin",
      approvalStatus: "approved",
      balanceCents: 0,
      balanceCoins: 0,
      promotionalCoins: 0
    });

    await expectReject(
      handleCreatePromoCode(
        { auth: null, data: { code: "UNAUTH10", rewardCoins: 50, maxSlots: 5, expiresAtMillis: FUTURE_EXPIRY } },
        deps
      ),
      "unauthenticated"
    );

    await expectReject(
      handleCreatePromoCode(
        { auth: aliceAuth, data: { code: "HACK100", rewardCoins: 100, maxSlots: 5, expiresAtMillis: FUTURE_EXPIRY } },
        deps
      ),
      "permission-denied"
    );

    // Code longer than 32 chars must fail
    await expectReject(
      handleCreatePromoCode(
        {
          auth: adminAuth,
          data: {
            code: "THIS_PROMO_CODE_IS_OVER_32_CHARACTERS_LONG",
            rewardCoins: 100,
            maxSlots: 5,
            expiresAtMillis: FUTURE_EXPIRY
          }
        },
        deps
      ),
      "invalid-argument",
      "3–32"
    );

    // Admin creates valid lowercase/spaced code -> normalized to uppercase doc ID
    const created = await handleCreatePromoCode(
      {
        auth: adminAuth,
        data: {
          code: " welcome250 ",
          rewardCoins: 250,
          maxSlots: 10,
          expiresAtMillis: FUTURE_EXPIRY,
          expiresAtLabel: "2026-11-15 12:00",
          isActive: true
        }
      },
      deps
    );
    assert.strictEqual(created.code, "WELCOME250");
    assert.strictEqual(created.status, "ACTIVE");
    assert.strictEqual(db.read("promo_codes/WELCOME250").rewardCoins, 250);
    assert.strictEqual(db.read("promo_codes/WELCOME250").usedSlots, 0);

    // Custom claim admin can also create
    const created2 = await handleCreatePromoCode(
      {
        auth: claimAdminAuth,
        data: { code: "BONUS_50", rewardCoins: 50, maxSlots: 2, expiresAtMillis: FUTURE_EXPIRY }
      },
      deps
    );
    assert.strictEqual(created2.code, "BONUS_50");
  }

  // =========================================================================
  // TEST 2: Duplicate code rejection
  // =========================================================================
  console.log("Test 2: Duplicate promo code rejection...");
  {
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);
    await handleCreatePromoCode(
      { auth: adminAuth, data: { code: "FEST2026", rewardCoins: 100, maxSlots: 5, expiresAtMillis: FUTURE_EXPIRY } },
      deps
    );

    await expectReject(
      handleCreatePromoCode(
        { auth: adminAuth, data: { code: " fest2026 ", rewardCoins: 500, maxSlots: 20, expiresAtMillis: FUTURE_EXPIRY } },
        deps
      ),
      "already-exists"
    );
    // Original must remain untouched
    assert.strictEqual(db.read("promo_codes/FEST2026").rewardCoins, 100);
  }

  // =========================================================================
  // TEST 3: Invalid reward, slots, and strict expiry date rejection (no silent fallback)
  // =========================================================================
  console.log("Test 3: Invalid reward, slots, and strict expiry date rejection...");
  {
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);

    for (const badReward of [0, -50, 12.5, "100", 10_000_001, NaN]) {
      await expectReject(
        handleCreatePromoCode(
          { auth: adminAuth, data: { code: "BADCOIN", rewardCoins: badReward, maxSlots: 5, expiresAtMillis: FUTURE_EXPIRY } },
          deps
        ),
        "invalid-argument"
      );
    }

    for (const badSlots of [0, -1, 1.5, "5", 1_000_001]) {
      await expectReject(
        handleCreatePromoCode(
          { auth: adminAuth, data: { code: "BADSLOT", rewardCoins: 50, maxSlots: badSlots, expiresAtMillis: FUTURE_EXPIRY } },
          deps
        ),
        "invalid-argument"
      );
    }

    // Past timestamp rejected
    await expectReject(
      handleCreatePromoCode(
        { auth: adminAuth, data: { code: "PAST1", rewardCoins: 50, maxSlots: 5, expiresAtMillis: nowRef.value - 1000 } },
        deps
      ),
      "invalid-argument",
      "future"
    );

    // Missing / zero / non-integer expiry rejected (NO silent fallback!)
    for (const badExpiry of [undefined, null, 0, -100, "invalid", NaN]) {
      await expectReject(
        handleCreatePromoCode(
          { auth: adminAuth, data: { code: "NOEXP", rewardCoins: 50, maxSlots: 5, expiresAtMillis: badExpiry } },
          deps
        ),
        "invalid-argument"
      );
    }

    // Invalid date strings & calendar rollover dates rejected strictly
    for (const badDateStr of ["tomorrow", "2026/11/20 12:00", "2026-13-01 10:00", "2026-02-31 12:00", "2026-04-31 09:00"]) {
      assert.throws(
        () => parseStrictPromoExpiryMillis({ expiresAtDateTime: badDateStr }, nowRef.value),
        (err) => err.code === "invalid-argument"
      );
    }
  }

  // =========================================================================
  // TEST 4: Valid redemption, balanceCents preservation, leaderboard & notification consistency
  // =========================================================================
  console.log("Test 4: Valid redemption, balanceCents preservation, ledger, leaderboard & notifications...");
  {
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);

    db.seed("users/user_alice", {
      userId: "user_alice",
      displayName: "Alice",
      approvalStatus: "approved",
      completedTasksCount: 3,
      balanceCents: 400,     // ₹4.00 = 500 eligible coins
      eligibleCoins: 500,
      promotionalCoins: 100,
      balanceCoins: 600
    });

    await handleCreatePromoCode(
      { auth: adminAuth, data: { code: "SAVE250", rewardCoins: 250, maxSlots: 5, expiresAtMillis: FUTURE_EXPIRY } },
      deps
    );

    const res = await handleRedeemPromoCode(
      { auth: aliceAuth, data: { code: "  save250  " } },
      deps
    );

    assert.strictEqual(res.success, true);
    assert.strictEqual(res.code, "SAVE250");
    assert.strictEqual(res.rewardCoins, 250);
    assert.strictEqual(res.newPromotionalCoins, 350);
    assert.strictEqual(res.newBalanceCoins, 850);

    const alice = db.read("users/user_alice");
    assert.strictEqual(alice.balanceCents, 400, "balanceCents MUST remain untouched");
    assert.strictEqual(alice.eligibleCoins, 500, "eligibleCoins MUST remain untouched");
    assert.strictEqual(alice.promotionalCoins, 350);
    assert.strictEqual(alice.balanceCoins, 850);

    const promo = db.read("promo_codes/SAVE250");
    assert.strictEqual(promo.usedSlots, 1);
    assert.strictEqual(promo.status, "ACTIVE");

    const redemption = db.read("promo_redemptions/SAVE250_user_alice");
    const subRedemption = db.read("promo_codes/SAVE250/redemptions/user_alice");
    assert.ok(redemption && subRedemption);
    assert.strictEqual(redemption.rewardCoins, 250);

    const tx = db.read("transactions/promo_SAVE250_user_alice");
    assert.ok(tx);
    assert.strictEqual(tx.type, "PROMO_CODE");
    assert.strictEqual(tx.amountCoins, 250);
    assert.strictEqual(tx.status, "CREDITED");
    assert.strictEqual(tx.referenceId, "SAVE250");

    const leaderboard = db.read("leaderboard/user_alice");
    assert.ok(leaderboard);
    assert.strictEqual(leaderboard.balanceCoins, 850);
    assert.strictEqual(leaderboard.completedTasksCount, 3);

    const notif = db.read("users/user_alice/notifications/notif_promo_SAVE250_user_alice");
    assert.ok(notif);
    assert.strictEqual(notif.type, "PROMO_CODE_REWARD");
    assert.strictEqual(notif.rewardCoins, 250);
    assert.ok(notif.body.includes("SAVE250"));
  }

  // =========================================================================
  // TEST 5: Duplicate user redemption (sequential AND concurrent)
  // =========================================================================
  console.log("Test 5: Duplicate user redemption prevention (sequential & concurrent)...");
  {
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);
    db.seed("users/user_alice", {
      userId: "user_alice",
      displayName: "Alice",
      approvalStatus: "approved",
      balanceCents: 0,
      balanceCoins: 0,
      promotionalCoins: 0
    });
    await handleCreatePromoCode(
      { auth: adminAuth, data: { code: "ONCE100", rewardCoins: 100, maxSlots: 10, expiresAtMillis: FUTURE_EXPIRY } },
      deps
    );

    // Concurrent double-submit by Alice
    const results = await Promise.allSettled([
      handleRedeemPromoCode({ auth: aliceAuth, data: { code: "ONCE100" } }, deps),
      handleRedeemPromoCode({ auth: aliceAuth, data: { code: "once100" } }, deps)
    ]);

    const fulfilled = results.filter((r) => r.status === "fulfilled");
    const rejected = results.filter((r) => r.status === "rejected");
    assert.strictEqual(fulfilled.length, 1, "Exactly 1 concurrent claim by same user must succeed");
    assert.strictEqual(rejected.length, 1, "Concurrent duplicate claim must fail");
    assert.strictEqual(rejected[0].reason.code, "already-exists");

    // Subsequent sequential retry also fails
    await expectReject(
      handleRedeemPromoCode({ auth: aliceAuth, data: { code: "ONCE100" } }, deps),
      "already-exists"
    );

    assert.strictEqual(db.read("users/user_alice").balanceCoins, 100);
    assert.strictEqual(db.read("promo_codes/ONCE100").usedSlots, 1);
  }

  // =========================================================================
  // TEST 6: Expired, inactive, exhausted, and unapproved rejection + setPromoCodeActiveStatus guards
  // =========================================================================
  console.log("Test 6: Expired, inactive, exhausted, unapproved, and reactivation guards...");
  {
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);

    db.seed("users/user_alice", {
      userId: "user_alice",
      displayName: "Alice",
      approvalStatus: "approved",
      balanceCents: 0,
      balanceCoins: 0,
      promotionalCoins: 0
    });
    db.seed("users/user_pending", {
      userId: "user_pending",
      displayName: "Pending",
      approvalStatus: "pending",
      balanceCents: 0,
      balanceCoins: 0,
      promotionalCoins: 0
    });

    // Inactive code
    await handleCreatePromoCode(
      { auth: adminAuth, data: { code: "PAUSED10", rewardCoins: 100, maxSlots: 5, expiresAtMillis: FUTURE_EXPIRY, isActive: false } },
      deps
    );
    await expectReject(
      handleRedeemPromoCode({ auth: aliceAuth, data: { code: "PAUSED10" } }, deps),
      "failed-precondition",
      "inactive"
    );

    // Non-admin cannot activate promo code
    await expectReject(
      handleSetPromoCodeActiveStatus({ auth: aliceAuth, data: { promoId: "PAUSED10", isActive: true } }, deps),
      "permission-denied"
    );

    // Admin activates valid code -> now redeemable
    const activated = await handleSetPromoCodeActiveStatus(
      { auth: adminAuth, data: { promoId: "PAUSED10", isActive: true } },
      deps
    );
    assert.strictEqual(activated.status, "ACTIVE");
    await handleRedeemPromoCode({ auth: aliceAuth, data: { code: "PAUSED10" } }, deps);
    assert.strictEqual(db.read("users/user_alice").balanceCoins, 100);

    // Unapproved user rejected
    await expectReject(
      handleRedeemPromoCode(
        { auth: { uid: "user_pending", token: {} }, data: { code: "PAUSED10" } },
        deps
      ),
      "permission-denied"
    );

    // Time advances past expiry -> server rejects expired code AND rejects admin reactivation
    await handleCreatePromoCode(
      { auth: adminAuth, data: { code: "FLASH60", rewardCoins: 60, maxSlots: 5, expiresAtMillis: nowRef.value + 5000 } },
      deps
    );
    nowRef.value += 10_000; // Server time moves past expiry
    await expectReject(
      handleRedeemPromoCode({ auth: aliceAuth, data: { code: "FLASH60" } }, deps),
      "failed-precondition",
      "expired"
    );
    await expectReject(
      handleSetPromoCodeActiveStatus({ auth: adminAuth, data: { promoId: "FLASH60", isActive: true } }, deps),
      "failed-precondition",
      "expired"
    );

    // Exhausted code cannot be redeemed or reactivated
    await handleCreatePromoCode(
      { auth: adminAuth, data: { code: "ONE_SLOT", rewardCoins: 75, maxSlots: 1, expiresAtMillis: nowRef.value + 60_000 } },
      deps
    );
    await handleRedeemPromoCode({ auth: aliceAuth, data: { code: "ONE_SLOT" } }, deps);
    assert.strictEqual(db.read("promo_codes/ONE_SLOT").status, "EXHAUSTED");

    db.seed("users/user_bob", {
      userId: "user_bob",
      displayName: "Bob",
      approvalStatus: "approved",
      balanceCents: 0,
      balanceCoins: 0,
      promotionalCoins: 0
    });
    await expectReject(
      handleRedeemPromoCode({ auth: bobAuth, data: { code: "ONE_SLOT" } }, deps),
      "resource-exhausted",
      "limit"
    );
    await expectReject(
      handleSetPromoCodeActiveStatus({ auth: adminAuth, data: { promoId: "ONE_SLOT", isActive: true } }, deps),
      "failed-precondition",
      "exhausted"
    );
  }

  // =========================================================================
  // TEST 7: Concurrent final-slot redemption across multiple users
  // =========================================================================
  console.log("Test 7: Concurrent final-slot redemption across 5 users (maxSlots = 2)...");
  {
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);
    const future = nowRef.value + 100_000;

    await handleCreatePromoCode(
      { auth: adminAuth, data: { code: "RACE2", rewardCoins: 200, maxSlots: 2, expiresAtMillis: future } },
      deps
    );

    const users = ["u1", "u2", "u3", "u4", "u5"];
    for (const u of users) {
      db.seed(`users/${u}`, {
        userId: u,
        displayName: `User_${u}`,
        approvalStatus: "approved",
        balanceCents: 100,
        balanceCoins: 125,
        promotionalCoins: 0
      });
    }

    const raceResults = await Promise.allSettled(
      users.map((u) =>
        handleRedeemPromoCode({ auth: { uid: u, token: {} }, data: { code: "RACE2" } }, deps)
      )
    );

    const winners = raceResults.filter((r) => r.status === "fulfilled");
    const losers = raceResults.filter((r) => r.status === "rejected");

    assert.strictEqual(winners.length, 2, `Expected exactly 2 winners, got ${winners.length}`);
    assert.strictEqual(losers.length, 3, `Expected 3 rejected users, got ${losers.length}`);
    for (const l of losers) {
      assert.strictEqual(l.reason.code, "resource-exhausted");
    }

    const promo = db.read("promo_codes/RACE2");
    assert.strictEqual(promo.usedSlots, 2);
    assert.strictEqual(promo.status, "EXHAUSTED");

    // Verify exact coin distribution across all 5 users (only 2 credited)
    const creditedUsers = users.filter((u) => db.read(`users/${u}`).promotionalCoins === 200);
    const uncreditedUsers = users.filter((u) => db.read(`users/${u}`).promotionalCoins === 0);
    assert.strictEqual(creditedUsers.length, 2);
    assert.strictEqual(uncreditedUsers.length, 3);
  }

  // =========================================================================
  // TEST 8: Client-supplied reward/balance/slot tampering & atomic rollback
  // =========================================================================
  console.log("Test 8: Client payload tampering ignored & atomic rollback on error...");
  {
    const db = createMockFirestore();
    const deps = makeDeps(db, nowRef);
    const future = nowRef.value + 100_000;

    db.seed("users/user_alice", {
      userId: "user_alice",
      displayName: "Alice",
      approvalStatus: "approved",
      balanceCents: 200,
      eligibleCoins: 250,
      promotionalCoins: 50,
      balanceCoins: 300
    });

    await handleCreatePromoCode(
      { auth: adminAuth, data: { code: "LEGIT25", rewardCoins: 25, maxSlots: 3, expiresAtMillis: future } },
      deps
    );

    // Client attempts to inject rewardCoins, balanceCoins, maxSlots, usedSlots, userId
    const res = await handleRedeemPromoCode(
      {
        auth: aliceAuth,
        data: {
          code: "LEGIT25",
          userId: "admin_uid_1",
          rewardCoins: 999999,
          balanceCoins: 9999999,
          promotionalCoins: 9999999,
          usedSlots: 0,
          maxSlots: 999999,
          expiresAtMillis: future + 999999999
        }
      },
      deps
    );

    assert.strictEqual(res.rewardCoins, 25);
    assert.strictEqual(res.newPromotionalCoins, 75);
    assert.strictEqual(res.newBalanceCoins, 325);
    assert.strictEqual(db.read("users/user_alice").balanceCents, 200);
    assert.strictEqual(db.read("transactions/promo_LEGIT25_user_alice").amountCoins, 25);

    // Atomic rollback check: missing promo leaves user, transactions, notifications, leaderboard unchanged
    const beforeStoreSize = db._store.size;
    await expectReject(
      handleRedeemPromoCode({ auth: aliceAuth, data: { code: "NO_SUCH_CODE" } }, deps),
      "not-found"
    );
    assert.strictEqual(db._store.size, beforeStoreSize);
    assert.strictEqual(db.read("users/user_alice").balanceCoins, 325);
  }

  console.log("========================================================");
  console.log("ALL 8 PROMO CODE BACKEND TESTS PASSED SUCCESSFULLY! 🎉");
  console.log("========================================================");
})();
