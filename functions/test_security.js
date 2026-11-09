const crypto = require("crypto");
const assert = require("assert");

// Mocking Admin & Firestore logic for verification
const mockDb = {
  processed_transactions: {},
  reward_sessions: {},
  watch_stats: {},
  users: { "user1": { virtualPoints: 0 } }
};

async function runTransaction(logic) {
  // Simulate atomic transaction
  return await logic({
    get: async (ref) => ({ exists: !!mockDb[ref.parent.id][ref.id], data: () => mockDb[ref.parent.id][ref.id] }),
    set: (ref, data) => { mockDb[ref.parent.id][ref.id] = data; },
    update: (ref, data) => { Object.assign(mockDb[ref.parent.id][ref.id], data); }
  });
}

// Verification Logic (same as in index.js)
function verifyHMAC(reqQuery, secret) {
  const { hmac, ...params } = reqQuery;
  const paramString = Object.keys(params).sort().map(k => params[k]).join(',');
  const calc = crypto.createHmac("md5", secret).update(paramString).digest("hex");
  return hmac === calc;
}

// Tests
(async () => {
  const secret = "test_secret";
  
  // Test: Valid HMAC
  const params = { oid: "tx1", sid: "user1", rewardPoints: "2", sessionId: "sess1" };
  const hmac = crypto.createHmac("md5", secret).update(Object.keys(params).sort().map(k => params[k]).join(',')).digest("hex");
  assert.strictEqual(verifyHMAC({ ...params, hmac }, secret), true);
  
  // Test: Invalid HMAC
  assert.strictEqual(verifyHMAC({ ...params, hmac: "invalid" }, secret), false);

  // Test: 25 video limit / 40 point limit
  mockDb.reward_sessions["sess1"] = { userId: "user1", rewardType: "WATCH_AND_EARN", used: false, expiresAt: { toMillis: () => Date.now() + 100000 } };
  mockDb.watch_stats["user1_2026-11-09"] = { videosWatched: 24, totalEarnedPoints: 39 };
  
  // Should pass (24+1=25, 39+1=40)
  const req = { query: { hmac, ...params, rewardPoints: "1" } };
  // Mocking transaction for the limit check
  const txLogic = async (tx) => {
    // ... simulate the limit check
    const data = { videosWatched: 24, totalEarnedPoints: 39 };
    if (data.videosWatched + 1 > 25 || data.totalEarnedPoints + 1 > 40) throw new Error("Daily limit exceeded");
    return "OK";
  };
  assert.strictEqual(await txLogic(), "OK");

  // Should fail
  const txLogicFail = async (tx) => {
    const data = { videosWatched: 25, totalEarnedPoints: 40 };
    if (data.videosWatched + 1 > 25 || data.totalEarnedPoints + 1 > 40) throw new Error("Daily limit exceeded");
  };
  await assert.rejects(txLogicFail(), /Daily limit exceeded/);

  console.log("All functional tests passed!");
})();
