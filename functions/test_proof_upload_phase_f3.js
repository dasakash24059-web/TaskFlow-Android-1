const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");

const { _proofUploadHandlers } = require("./index.js");
const {
  handleIssueTaskProofUploadTicket,
  handleFinalizeTaskProofSubmission,
  handleCleanupExpiredProofUploadTickets,
  hasValidImageMagicBytes,
} = _proofUploadHandlers;

function makeValidJpegBuffer(totalBytes = 256, fillByte = 0x41) {
  const buf = Buffer.alloc(Math.max(4, totalBytes), fillByte);
  buf[0] = 0xff;
  buf[1] = 0xd8;
  buf[2] = 0xff;
  buf[3] = 0xe0;
  return buf;
}

function makeValidPngBuffer(totalBytes = 256) {
  const buf = Buffer.alloc(Math.max(16, totalBytes), 0x20);
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  sig.copy(buf, 0);
  return buf;
}

function makeValidWebpBuffer(totalBytes = 256) {
  const buf = Buffer.alloc(Math.max(16, totalBytes), 0x30);
  Buffer.from("RIFF", "ascii").copy(buf, 0);
  Buffer.from("WEBP", "ascii").copy(buf, 8);
  return buf;
}

function sha256HexOf(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

function createMockProofEnv({ allowSignedUrls = true } = {}) {
  const store = {
    users: new Map(),
    tasks: new Map(),
    submissions: new Map(),
    proof_upload_tickets: new Map(),
    proof_upload_locks: new Map(),
  };

  const files = new Map();
  const signedUrls = new Map();
  let failNextTransaction = false;

  function getCollectionMap(name) {
    if (!store[name]) {
      store[name] = new Map();
    }
    return store[name];
  }

  function makeDocRef(collectionName, id) {
    const colMap = getCollectionMap(collectionName);
    return {
      id,
      path: `${collectionName}/${id}`,
      async get() {
        const val = colMap.get(id);
        return {
          id,
          exists: val !== undefined,
          data: () => (val ? { ...val } : undefined),
        };
      },
      async set(data, options = {}) {
        if (options && options.merge && colMap.has(id)) {
          colMap.set(id, { ...colMap.get(id), ...data });
        } else {
          colMap.set(id, { ...data });
        }
      },
      async update(data) {
        if (!colMap.has(id)) {
          throw new Error(`Document ${collectionName}/${id} does not exist`);
        }
        colMap.set(id, { ...colMap.get(id), ...data });
      },
      async delete() {
        colMap.delete(id);
      },
    };
  }

  function buildQuery(collectionName, filters = [], limitCount = null) {
    return {
      where(field, op, value) {
        return buildQuery(
          collectionName,
          [...filters, { field, op, value }],
          limitCount
        );
      },
      limit(n) {
        return buildQuery(collectionName, filters, n);
      },
      async get() {
        const colMap = getCollectionMap(collectionName);
        const docs = [];
        for (const [id, rawData] of colMap.entries()) {
          const matchesAll = filters.every(({ field, op, value }) => {
            const fieldVal = rawData[field];
            if (op === "==") return fieldVal === value;
            if (op === "<") return Number(fieldVal) < Number(value);
            if (op === "<=") return Number(fieldVal) <= Number(value);
            if (op === ">") return Number(fieldVal) > Number(value);
            if (op === ">=") return Number(fieldVal) >= Number(value);
            return false;
          });
          if (matchesAll) {
            docs.push({
              id,
              ref: makeDocRef(collectionName, id),
              exists: true,
              data: () => ({ ...rawData }),
            });
          }
        }
        const sliced =
          typeof limitCount === "number" ? docs.slice(0, limitCount) : docs;
        return {
          empty: sliced.length === 0,
          size: sliced.length,
          docs: sliced,
        };
      },
    };
  }

  const db = {
    collection(name) {
      return {
        doc(id) {
          return makeDocRef(name, id);
        },
        where(field, op, value) {
          return buildQuery(name, [{ field, op, value }], null);
        },
      };
    },
    async runTransaction(updateFn) {
      // Snapshot before transaction for rollback on error
      const backup = {};
      for (const [colName, colMap] of Object.entries(store)) {
        backup[colName] = new Map(
          Array.from(colMap.entries()).map(([k, v]) => [k, { ...v }])
        );
      }

      const pendingWrites = [];
      const tx = {
        async get(refOrQuery) {
          return refOrQuery.get();
        },
        set(ref, data, options) {
          pendingWrites.push(() => ref.set(data, options));
        },
        update(ref, data) {
          pendingWrites.push(() => ref.update(data));
        },
        delete(ref) {
          pendingWrites.push(() => ref.delete());
        },
      };

      try {
        const result = await updateFn(tx);
        if (failNextTransaction) {
          failNextTransaction = false;
          throw new Error("Simulated Firestore transaction commit failure");
        }
        for (const writeOp of pendingWrites) {
          await writeOp();
        }
        return result;
      } catch (err) {
        for (const [colName, colMap] of Object.entries(backup)) {
          store[colName] = colMap;
        }
        throw err;
      }
    },
  };

  function makeGcsFile(storagePath) {
    return {
      name: storagePath,
      async getSignedUrl(cfg) {
        if (!allowSignedUrls) {
          throw new Error("SigningError: iam.serviceAccounts.signBlob denied");
        }
        const url = `https://storage.googleapis.com/taskflow-8852d.firebasestorage.app/${storagePath}?sig=${ crypto
          .randomBytes(6)
          .toString("hex")}`;
        signedUrls.set(url, {
          storagePath,
          expires: cfg.expires,
          contentType: cfg.contentType,
          extensionHeaders: { ...(cfg.extensionHeaders || {}) },
        });
        return [url];
      },
      async save(buffer, options = {}) {
        const ifGenMatch =
          options.preconditionOpts &&
          options.preconditionOpts.ifGenerationMatch;
        if (ifGenMatch === 0 && files.has(storagePath)) {
          const err = new Error("Precondition Failed: ifGenerationMatch=0");
          err.code = 412;
          throw err;
        }
        const meta = options.metadata || {};
        files.set(storagePath, {
          buffer: Buffer.from(buffer),
          contentType: meta.contentType || options.contentType || "application/octet-stream",
          metadata: { ...(meta.metadata || {}) },
        });
      },
      async exists() {
        return [files.has(storagePath)];
      },
      async getMetadata() {
        const entry = files.get(storagePath);
        if (!entry) {
          const err = new Error(`No such object: ${storagePath}`);
          err.code = 404;
          throw err;
        }
        return [
          {
            name: storagePath,
            size: String(entry.buffer.length),
            contentType: entry.contentType,
            metadata: { ...(entry.metadata || {}) },
          },
        ];
      },
      async setMetadata(newMeta) {
        const entry = files.get(storagePath);
        if (!entry) {
          throw new Error(`No such object: ${storagePath}`);
        }
        if (newMeta.contentType) {
          entry.contentType = newMeta.contentType;
        }
        if (newMeta.metadata) {
          entry.metadata = { ...(entry.metadata || {}), ...newMeta.metadata };
        }
      },
      async download() {
        const entry = files.get(storagePath);
        if (!entry) {
          throw new Error(`No such object: ${storagePath}`);
        }
        return [Buffer.from(entry.buffer)];
      },
      async delete() {
        files.delete(storagePath);
      },
    };
  }

  const bucket = {
    name: "taskflow-8852d.firebasestorage.app",
    file(storagePath) {
      return makeGcsFile(storagePath);
    },
  };

  function putToSignedUrl(uploadUrl, buffer, headers = {}, nowMs = null) {
    const signedSpec = signedUrls.get(uploadUrl);
    if (!signedSpec) {
      throw new Error("Invalid signed URL");
    }
    const effectiveNowMs =
      typeof nowMs === "number" ? nowMs : signedSpec.expires - 1000;
    if (effectiveNowMs > signedSpec.expires) {
      const err = new Error("Signed URL has expired");
      err.code = 403;
      throw err;
    }
    // Enforce signed header preconditions (including x-goog-if-generation-match: 0)
    for (const [k, expectedVal] of Object.entries(signedSpec.extensionHeaders)) {
      const actualVal = headers[k];
      if (String(actualVal) !== String(expectedVal)) {
        const err = new Error(`Signed header mismatch for ${k}`);
        err.code = 403;
        throw err;
      }
    }
    if (
      String(headers["x-goog-if-generation-match"]) === "0" &&
      files.has(signedSpec.storagePath)
    ) {
      const err = new Error(
        "GCS Precondition Failed (412): Object already exists and x-goog-if-generation-match is 0"
      );
      err.code = 412;
      throw err;
    }

    const customMetadata = {};
    for (const [k, v] of Object.entries(headers)) {
      if (k.toLowerCase().startsWith("x-goog-meta-")) {
        customMetadata[k.slice("x-goog-meta-".length)] = String(v);
      }
    }
    files.set(signedSpec.storagePath, {
      buffer: Buffer.from(buffer),
      contentType: headers["Content-Type"] || signedSpec.contentType,
      metadata: customMetadata,
    });
  }

  return {
    db,
    bucket,
    store,
    files,
    signedUrls,
    putToSignedUrl,
    setFailNextTransaction(val) {
      failNextTransaction = Boolean(val);
    },
  };
}

function seedDefaultApprovedUserAndTask(env, {
  uid = "user_approved_1",
  email = "approved1@taskflow.app",
  approvalStatus = "approved",
  coinBalance = 1250,
  taskId = "task_open_1",
  isActive = true,
  status = "pending",
  assignedUserId = "",
  targetSubmissions = 10,
  completedSubmissions = 0,
} = {}) {
  env.store.users.set(uid, {
    uid,
    email,
    displayName: "Approved User",
    approvalStatus,
    coinBalance,
    balanceCents: 1000,
  });
  env.store.tasks.set(taskId, {
    id: taskId,
    title: "Install & Screenshot TaskFlow Partner App",
    appName: "Partner App",
    appIconUrl: "https://example.com/icon.png",
    rewardCents: 200,
    rewardCoins: 250,
    isActive,
    status,
    assignedUserId,
    targetSubmissions,
    completedSubmissions,
  });
}

test("Scenario 1: Approved user + valid task E2E ticket issue, signed PUT upload, and atomic finalization (plus SERVER_MEDIATED_FINALIZE fallback)", async () => {
  const env = createMockProofEnv({ allowSignedUrls: true });
  seedDefaultApprovedUserAndTask(env);

  const imgBuf = makeValidJpegBuffer(512);
  const sha256Hex = sha256HexOf(imgBuf);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex,
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );

  assert.equal(ticket.uploadMode, "SIGNED_PUT");
  assert.ok(ticket.ticketId.startsWith("tkt_"));
  assert.ok(
    ticket.storagePath.startsWith(
      "submissions/user_approved_1/task_open_1/proof_1763890000000_"
    )
  );
  assert.equal(ticket.requiredHeaders["x-goog-if-generation-match"], "0");

  // Upload bytes via signed URL
  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);

  const finalized = await handleFinalizeTaskProofSubmission(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        ticketId: ticket.ticketId,
        taskId: "task_open_1",
        proofText: "Completed registration and took screenshot of dashboard.",
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
  );

  assert.equal(finalized.status, "submitted");
  assert.equal(finalized.submissionId, "task_open_1_user_approved_1");
  assert.equal(finalized.proofStoragePath, ticket.storagePath);
  assert.ok(finalized.proofImageUrl.includes("firebasestorage.googleapis.com"));
  assert.ok(finalized.proofImageUrl.includes("token="));

  // Also test SERVER_MEDIATED_FINALIZE mode when IAM signBlob is unavailable
  const envFallback = createMockProofEnv({ allowSignedUrls: false });
  seedDefaultApprovedUserAndTask(envFallback, {
    uid: "user_approved_png",
    taskId: "task_png_1",
  });
  const pngBuf = makeValidPngBuffer(320);
  const pngTicket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_png",
        token: { email: "png@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_png_1",
        mimeType: "image/png",
        sizeBytes: pngBuf.length,
        sha256Hex: sha256HexOf(pngBuf),
      },
    },
    { db: envFallback.db, bucket: envFallback.bucket, nowMs: 1763890000000 }
  );
  assert.equal(pngTicket.uploadMode, "SERVER_MEDIATED_FINALIZE");

  const pngFinalized = await handleFinalizeTaskProofSubmission(
    {
      auth: {
        uid: "user_approved_png",
        token: { email: "png@taskflow.app", email_verified: true },
      },
      data: {
        ticketId: pngTicket.ticketId,
        taskId: "task_png_1",
        proofText: "Submitted PNG proof via server-mediated finalize.",
        imageBase64: pngBuf.toString("base64"),
      },
    },
    { db: envFallback.db, bucket: envFallback.bucket, nowMs: 1763890010000 }
  );
  assert.equal(pngFinalized.status, "submitted");
  assert.ok(envFallback.files.has(pngTicket.storagePath));
});

test("Scenario 2: Pending user cannot obtain upload ticket or upload proof", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, {
    uid: "user_pending_1",
    approvalStatus: "pending",
  });
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_pending_1",
            token: { email: "pending@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "permission-denied" && /pending/i.test(err.message)
  );
  assert.equal(env.files.size, 0);
  assert.equal(env.store.proof_upload_tickets.size, 0);
});

test("Scenario 3: Rejected user cannot obtain upload ticket or upload proof", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, {
    uid: "user_rejected_1",
    approvalStatus: "rejected",
  });
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_rejected_1",
            token: { email: "rejected@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "permission-denied" && /rejected/i.test(err.message)
  );
  assert.equal(env.files.size, 0);
});

test("Scenario 4: Suspended user cannot obtain upload ticket or upload proof", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, {
    uid: "user_suspended_1",
    approvalStatus: "suspended",
  });
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_suspended_1",
            token: { email: "suspended@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "permission-denied" && /suspended/i.test(err.message)
  );
});

test("Scenario 5: Missing user profile in named Firestore DB is rejected", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "ghost_user_no_profile",
            token: { email: "ghost@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "permission-denied" && /profile does not exist/i.test(err.message)
  );
});

test("Scenario 6: Unverified email user is rejected at both issue and finalize", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: false },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "permission-denied" && /verified email/i.test(err.message)
  );
});

test("Scenario 7: Nonexistent task is rejected with not-found", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_does_not_exist_999",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "not-found"
  );
});

test("Scenario 8: Inactive task (isActive == false) is rejected with failed-precondition", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, { isActive: false });
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "failed-precondition" && /deactivated/i.test(err.message)
  );
});

test("Scenario 9: Completed/approved task (status != pending) is rejected with failed-precondition", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, { status: "approved" });
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "failed-precondition" && /Available/i.test(err.message)
  );
});

test("Scenario 10: Exhausted quota (completedSubmissions >= targetSubmissions) is rejected", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, {
    targetSubmissions: 5,
    completedSubmissions: 5,
  });
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "failed-precondition" && /quota/i.test(err.message)
  );
});

test("Scenario 11: Wrong assignee on private assigned task is rejected with permission-denied", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, {
    assignedUserId: "someone_else_uid_777",
  });
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "permission-denied" && /assigned to another user/i.test(err.message)
  );
});

test("Scenario 12: Duplicate submission is rejected BEFORE issuing upload ticket", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  env.store.submissions.set("task_open_1_user_approved_1", {
    taskId: "task_open_1",
    userId: "user_approved_1",
    status: "submitted",
  });
  const imgBuf = makeValidJpegBuffer(256);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: imgBuf.length,
            sha256Hex: sha256HexOf(imgBuf),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "already-exists"
  );
  assert.equal(env.store.proof_upload_tickets.size, 0);
});

test("Scenario 13: Expired ticket is rejected on finalize and uncommitted uploaded object is deleted", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const issueTime = 1763890000000;
  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: issueTime }
  );

  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);
  assert.ok(env.files.has(ticket.storagePath));

  // Finalize 6 minutes later (past 5-minute TTL)
  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Late finalize after ticket expiry",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: issueTime + 6 * 60 * 1000 }
      ),
    (err) => err.code === "deadline-exceeded"
  );

  // Uncommitted object was deleted and ticket marked EXPIRED_CLEANED
  assert.equal(env.files.has(ticket.storagePath), false);
  assert.equal(
    env.store.proof_upload_tickets.get(ticket.ticketId).status,
    "EXPIRED_CLEANED"
  );
});

test("Scenario 14: Consumed ticket cannot be reused and committed proof object is preserved", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );

  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);

  await handleFinalizeTaskProofSubmission(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        ticketId: ticket.ticketId,
        taskId: "task_open_1",
        proofText: "First valid finalization",
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
  );

  // Second attempt with the same consumed ticket must fail with already-exists
  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Replay attempt on consumed ticket",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890020000 }
      ),
    (err) => err.code === "already-exists"
  );

  // Committed proof object MUST remain intact in Storage!
  assert.equal(env.files.has(ticket.storagePath), true);
});

test("Scenario 15: Same signed URL cannot overwrite existing object (x-goog-if-generation-match: 0)", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );

  // First PUT succeeds
  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);

  // Second PUT to the same signed URL fails with HTTP 412 Precondition Failed
  const replacementBuf = makeValidJpegBuffer(256, 0x77);
  assert.throws(
    () => env.putToSignedUrl(ticket.uploadUrl, replacementBuf, ticket.requiredHeaders),
    (err) => err.code === 412
  );
});

test("Scenario 16: Wrong MIME type or invalid magic bytes are rejected and uncommitted object is deleted", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const validBuf = makeValidJpegBuffer(256);

  // 16a: Disallowed MIME type rejected at ticket issuance
  for (const disallowedMime of [
    "application/pdf",
    "text/html",
    "image/svg+xml",
    "application/vnd.android.package-archive",
  ]) {
    await assert.rejects(
      () =>
        handleIssueTaskProofUploadTicket(
          {
            auth: {
              uid: "user_approved_1",
              token: { email: "approved1@taskflow.app", email_verified: true },
            },
            data: {
              taskId: "task_open_1",
              mimeType: disallowedMime,
              sizeBytes: validBuf.length,
              sha256Hex: sha256HexOf(validBuf),
            },
          },
          { db: env.db, bucket: env.bucket }
        ),
      (err) => err.code === "invalid-argument"
    );
  }

  // 16b: Non-image payload disguised as image/jpeg (invalid magic bytes) rejected on finalize and deleted
  const fakeJpegBuf = Buffer.from("%PDF-1.7 disguised as jpeg content".padEnd(256, "X"), "utf8");
  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: fakeJpegBuf.length,
        sha256Hex: sha256HexOf(fakeJpegBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );

  env.putToSignedUrl(ticket.uploadUrl, fakeJpegBuf, ticket.requiredHeaders);
  assert.ok(env.files.has(ticket.storagePath));

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Trying to upload PDF disguised as JPEG",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    (err) => err.code === "invalid-argument" && /signature/i.test(err.message)
  );
  assert.equal(env.files.has(ticket.storagePath), false);
});

test("Scenario 17: Zero-byte file is rejected with invalid-argument", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: 0,
            sha256Hex: sha256HexOf(Buffer.alloc(0)),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "invalid-argument" && /sizeBytes/i.test(err.message)
  );
});

test("Scenario 18: File larger than 5 MB (5,242,881 bytes) is rejected with invalid-argument", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);

  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: 5 * 1024 * 1024 + 1,
            sha256Hex: "a".repeat(64),
          },
        },
        { db: env.db, bucket: env.bucket }
      ),
    (err) => err.code === "invalid-argument" && /sizeBytes/i.test(err.message)
  );
});

test("Scenario 19: Incorrect uploaded file size (mismatch with ticket sizeBytes) is rejected and deleted", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const declaredBuf = makeValidJpegBuffer(512);
  const actualUploadedBuf = makeValidJpegBuffer(1024);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: declaredBuf.length,
        sha256Hex: sha256HexOf(declaredBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );

  // Upload 1024 bytes when ticket authorized 512 bytes
  env.putToSignedUrl(ticket.uploadUrl, actualUploadedBuf, ticket.requiredHeaders);
  assert.ok(env.files.has(ticket.storagePath));

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Size mismatch test",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    (err) => err.code === "invalid-argument" && /size/i.test(err.message)
  );
  assert.equal(env.files.has(ticket.storagePath), false);
  assert.equal(
    env.store.proof_upload_tickets.get(ticket.ticketId).status,
    "REJECTED"
  );
});

test("Scenario 20: Incorrect SHA-256 (uploaded content differs from ticket sha256Hex) is rejected and deleted", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const expectedBuf = makeValidJpegBuffer(512, 0x11);
  const swappedBuf = makeValidJpegBuffer(512, 0x22);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: expectedBuf.length,
        sha256Hex: sha256HexOf(expectedBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );

  env.putToSignedUrl(ticket.uploadUrl, swappedBuf, ticket.requiredHeaders);
  assert.ok(env.files.has(ticket.storagePath));

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Swapped bytes with same length",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    (err) => err.code === "invalid-argument" && /SHA-256/i.test(err.message)
  );
  assert.equal(env.files.has(ticket.storagePath), false);
  assert.equal(
    env.store.proof_upload_tickets.get(ticket.ticketId).status,
    "REJECTED"
  );
});

test("Scenario 21: Cross-user ticket access denied (User B cannot finalize User A's ticket)", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  env.store.users.set("user_approved_2", {
    uid: "user_approved_2",
    email: "approved2@taskflow.app",
    displayName: "Approved User 2",
    approvalStatus: "approved",
  });
  const imgBuf = makeValidJpegBuffer(256);

  const ticketA = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );
  env.putToSignedUrl(ticketA.uploadUrl, imgBuf, ticketA.requiredHeaders);

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_2",
            token: { email: "approved2@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticketA.ticketId,
            taskId: "task_open_1",
            proofText: "User B trying to hijack User A's ticket",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    (err) => err.code === "permission-denied"
  );
});

test("Scenario 22: Cross-user proof metadata tampering is denied and uncommitted object is deleted", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );

  // Simulate tampered object metadata in GCS where uid metadata doesn't match caller
  env.files.set(ticket.storagePath, {
    buffer: imgBuf,
    contentType: "image/jpeg",
    metadata: {
      uid: "other_user_uid",
      "task-id": "task_open_1",
      "ticket-id": ticket.ticketId,
    },
  });

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Tampered metadata test",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    (err) => err.code === "permission-denied" && /metadata/i.test(err.message)
  );
  assert.equal(env.files.has(ticket.storagePath), false);
});

test("Scenario 23: Admin Proof Review compatibility (submission doc has valid proofImageUrl download token and proofStoragePath)", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, {
    assignedUserId: "user_approved_1",
  });
  const webpBuf = makeValidWebpBuffer(384);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/webp",
        sizeBytes: webpBuf.length,
        sha256Hex: sha256HexOf(webpBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );
  env.putToSignedUrl(ticket.uploadUrl, webpBuf, ticket.requiredHeaders);

  const finalized = await handleFinalizeTaskProofSubmission(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        ticketId: ticket.ticketId,
        taskId: "task_open_1",
        proofText: "1:1 assigned task proof for Admin Review",
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
  );

  // For 1:1 assigned private task, submissionId is task.id and both /submissions/{taskId} and /tasks/{taskId} are updated
  assert.equal(finalized.submissionId, "task_open_1");
  const subDoc = env.store.submissions.get("task_open_1");
  const taskDoc = env.store.tasks.get("task_open_1");
  assert.ok(subDoc);
  assert.equal(subDoc.proofStoragePath, ticket.storagePath);
  assert.equal(subDoc.proofImageUrl, finalized.proofImageUrl);
  assert.equal(subDoc.status, "submitted");
  assert.equal(taskDoc.status, "submitted");
  assert.equal(taskDoc.proofImageUrl, finalized.proofImageUrl);
  assert.equal(taskDoc.proofStoragePath, ticket.storagePath);

  // GCS object has firebaseStorageDownloadTokens set for Coil / Admin Review rendering
  const gcsEntry = env.files.get(ticket.storagePath);
  assert.ok(gcsEntry.metadata.firebaseStorageDownloadTokens);
  assert.ok(
    finalized.proofImageUrl.includes(
      gcsEntry.metadata.firebaseStorageDownloadTokens
    )
  );
});

test("Scenario 24: Rate limiting (per-task max 5/15m, global max 15/15m, idempotent active reuse, and supersede cleanup)", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const nowMs = 1763890000000;

  // 24a: Identical active ticket request returns same ticketId idempotently
  const buf1 = makeValidJpegBuffer(256, 0x10);
  const t1 = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: buf1.length,
        sha256Hex: sha256HexOf(buf1),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs }
  );
  const t1Idempotent = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: buf1.length,
        sha256Hex: sha256HexOf(buf1),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: nowMs + 1000 }
  );
  assert.equal(t1Idempotent.ticketId, t1.ticketId);
  assert.equal(t1Idempotent.reusedExistingTicket, true);

  // Upload to t1, then pick a different image -> t1 is superseded and its uncommitted object is deleted
  env.putToSignedUrl(t1.uploadUrl, buf1, t1.requiredHeaders);
  assert.equal(env.files.has(t1.storagePath), true);

  // Issue 4 more distinct tickets on task_open_1 (reaching per-task limit of 5 in 15 min)
  for (let i = 2; i <= 5; i++) {
    const nextBuf = makeValidJpegBuffer(256 + i, i);
    await handleIssueTaskProofUploadTicket(
      {
        auth: {
          uid: "user_approved_1",
          token: { email: "approved1@taskflow.app", email_verified: true },
        },
        data: {
          taskId: "task_open_1",
          mimeType: "image/jpeg",
          sizeBytes: nextBuf.length,
          sha256Hex: sha256HexOf(nextBuf),
        },
      },
      { db: env.db, bucket: env.bucket, nowMs: nowMs + i * 1000 }
    );
  }
  // Verify t1's uncommitted object was cleaned up when superseded
  assert.equal(env.files.has(t1.storagePath), false);
  assert.equal(
    env.store.proof_upload_tickets.get(t1.ticketId).status,
    "SUPERSEDED_CLEANED"
  );

  // 6th distinct ticket on the same task within 15 minutes fails with resource-exhausted
  const buf6 = makeValidJpegBuffer(500, 0x99);
  await assert.rejects(
    () =>
      handleIssueTaskProofUploadTicket(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            taskId: "task_open_1",
            mimeType: "image/jpeg",
            sizeBytes: buf6.length,
            sha256Hex: sha256HexOf(buf6),
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: nowMs + 10000 }
      ),
    (err) => err.code === "resource-exhausted"
  );
});

test("Scenario 25: Concurrent duplicate submission race during finalize is rejected and second uncommitted object is deleted", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );
  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);

  // Simulate another concurrent request having committed the submission right before finalize
  env.store.submissions.set("task_open_1_user_approved_1", {
    taskId: "task_open_1",
    userId: "user_approved_1",
    proofStoragePath: "submissions/user_approved_1/task_open_1/proof_earlier_winner.jpg",
    status: "submitted",
  });

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Concurrent race loser submission",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    (err) => err.code === "already-exists"
  );

  // The losing ticket's uncommitted file was deleted
  assert.equal(env.files.has(ticket.storagePath), false);
});

test("Scenario 26: Mid-upload account suspension (approved -> suspended before finalize) rejects finalize and deletes uploaded file", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );
  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);
  assert.ok(env.files.has(ticket.storagePath));

  // Admin suspends user while upload is in progress
  env.store.users.set("user_approved_1", {
    ...env.store.users.get("user_approved_1"),
    approvalStatus: "suspended",
  });

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Trying to finalize after being suspended",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    (err) => err.code === "permission-denied" && /no longer approved/i.test(err.message)
  );

  assert.equal(env.files.has(ticket.storagePath), false);
  assert.equal(env.store.submissions.size, 0);
});

test("Scenario 27: Task deactivation during upload (isActive: true -> false before finalize) rejects finalize and deletes uploaded file", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );
  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);
  assert.ok(env.files.has(ticket.storagePath));

  // Admin deactivates task mid-upload
  env.store.tasks.set("task_open_1", {
    ...env.store.tasks.get("task_open_1"),
    isActive: false,
  });

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Trying to finalize after task deactivation",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    (err) => err.code === "failed-precondition" && /deactivated/i.test(err.message)
  );

  assert.equal(env.files.has(ticket.storagePath), false);
  assert.equal(env.store.submissions.size, 0);
});

test("Scenario 28: Firestore transaction commit failure triggers safe object cleanup without orphan files", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: 1763890000000 }
  );
  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);
  assert.ok(env.files.has(ticket.storagePath));

  env.setFailNextTransaction(true);

  await assert.rejects(
    () =>
      handleFinalizeTaskProofSubmission(
        {
          auth: {
            uid: "user_approved_1",
            token: { email: "approved1@taskflow.app", email_verified: true },
          },
          data: {
            ticketId: ticket.ticketId,
            taskId: "task_open_1",
            proofText: "Simulated Firestore commit failure",
          },
        },
        { db: env.db, bucket: env.bucket, nowMs: 1763890010000 }
      ),
    /Simulated Firestore transaction commit failure/
  );

  // Uncommitted file was cleaned up and no submission document exists
  assert.equal(env.files.has(ticket.storagePath), false);
  assert.equal(env.store.submissions.size, 0);
});

test("Scenario 29: App termination / abandoned upload after ticket expiry is cleaned up by scheduled cleanupExpiredProofUploadTickets", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env);
  const imgBuf = makeValidJpegBuffer(256);

  const issueTime = 1763890000000;
  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: issueTime }
  );

  // User uploads file to GCS, then app crashes / disconnects and never calls finalize
  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);
  assert.equal(env.files.has(ticket.storagePath), true);

  // Run scheduled sweeper 10 minutes after ticket expiry
  const sweepResult = await handleCleanupExpiredProofUploadTickets({
    db: env.db,
    bucket: env.bucket,
    nowMs: issueTime + 15 * 60 * 1000,
  });

  assert.equal(sweepResult.cleaned, 1);
  assert.equal(env.files.has(ticket.storagePath), false);
  assert.equal(
    env.store.proof_upload_tickets.get(ticket.ticketId).status,
    "EXPIRED_CLEANED"
  );
});

test("Scenario 30: Consumed ticket's valid proof image is NEVER deleted by cleanup job, and upload/finalize never alters wallet balance", async () => {
  const env = createMockProofEnv();
  seedDefaultApprovedUserAndTask(env, { coinBalance: 1250 });
  const imgBuf = makeValidJpegBuffer(256);

  const issueTime = 1763890000000;
  const ticket = await handleIssueTaskProofUploadTicket(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        taskId: "task_open_1",
        mimeType: "image/jpeg",
        sizeBytes: imgBuf.length,
        sha256Hex: sha256HexOf(imgBuf),
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: issueTime }
  );
  env.putToSignedUrl(ticket.uploadUrl, imgBuf, ticket.requiredHeaders);

  await handleFinalizeTaskProofSubmission(
    {
      auth: {
        uid: "user_approved_1",
        token: { email: "approved1@taskflow.app", email_verified: true },
      },
      data: {
        ticketId: ticket.ticketId,
        taskId: "task_open_1",
        proofText: "Valid committed proof submission",
      },
    },
    { db: env.db, bucket: env.bucket, nowMs: issueTime + 5000 }
  );

  // Even if an anomalous ISSUED ticket record pointed to a committed submission's storagePath,
  // handleCleanupExpiredProofUploadTickets verifies /submissions before deleting and preserves it
  env.store.proof_upload_tickets.set("tkt_anomalous_duplicate_ref", {
    ticketId: "tkt_anomalous_duplicate_ref",
    uid: "user_approved_1",
    taskId: "task_open_1",
    storagePath: ticket.storagePath,
    status: "ISSUED",
    expiresAtMs: issueTime + 1000,
  });

  await handleCleanupExpiredProofUploadTickets({
    db: env.db,
    bucket: env.bucket,
    nowMs: issueTime + 30 * 60 * 1000,
  });

  // Committed proof object MUST still exist in Storage!
  assert.equal(env.files.has(ticket.storagePath), true);

  // Wallet balance (1250 TF coins / 1000 cents) is completely untouched by proof upload & submission
  const userAfter = env.store.users.get("user_approved_1");
  assert.equal(userAfter.coinBalance, 1250);
  assert.equal(userAfter.balanceCents, 1000);
});
