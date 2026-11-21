/**
 * Universal Campaign Tracking Engine — Phase 3E.1 User-Facing Campaign Discovery & Activity Backend.
 *
 * All handlers require strict Firebase Authentication and derive `userId` exclusively from
 * `request.auth.uid` (never trusting client-submitted `userId`, rewards, eligibility, or country).
 *
 * Protected Firestore collections (`campaign_tracking_configs`, `campaign_networks`,
 * `campaign_clicks`, `campaign_conversions`, `campaign_click_guards`, `campaign_conversion_locks`)
 * remain strictly server-only (`allow read, write: if false`).
 *
 * Strict sanitization guarantee:
 * - Never returns `trackingBaseUrl`, `clickId`, `conversionId` (which embeds `clickId`),
 *   `externalTransactionId`, `idempotencyKey`, partner parameters, signatures, secrets,
 *   or internal moderation notes to end users.
 */

const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_CONVERSION_STATES,
  buildUserActivityId,
  tfToBalanceCents,
  toMillisValue,
  resolveCountryTrustContext,
  resolveDeviceTrustContext,
  validateDestinationUrl
} = require("./campaign_tracking");

const MAX_USER_PAGE_SIZE = 50;
const DEFAULT_USER_PAGE_SIZE = 20;
const MAX_USER_CAMPAIGNS_SCAN = 500;
const MAX_USER_NETWORKS_SCAN = 200;
const MAX_USER_ACTIVITY_SCAN = 500;
const DEFAULT_BATCH_SIZE = 250;

const ALLOWED_USER_ACTIVITY_FILTERS = new Set([
  "ALL",
  CAMPAIGN_CONVERSION_STATES.PENDING,
  CAMPAIGN_CONVERSION_STATES.APPROVED,
  CAMPAIGN_CONVERSION_STATES.REJECTED,
  CAMPAIGN_CONVERSION_STATES.REVERSED
]);

function clampUserPageSize(raw, fallback = DEFAULT_USER_PAGE_SIZE) {
  const num = Number(raw);
  if (!Number.isFinite(num) || num <= 0) return fallback;
  return Math.min(MAX_USER_PAGE_SIZE, Math.max(1, Math.floor(num)));
}

function paginateItems(items, pageSize, cursor, getId) {
  const safeSize = clampUserPageSize(pageSize);
  const cleanCursor = String(cursor || "").trim();
  let startIndex = 0;
  if (cleanCursor) {
    const idx = items.findIndex((item) => getId(item) === cleanCursor);
    if (idx >= 0) {
      startIndex = idx + 1;
    }
  }
  const slice = items.slice(startIndex, startIndex + safeSize);
  const hasMore = startIndex + safeSize < items.length;
  const nextCursor = hasMore && slice.length > 0 ? getId(slice[slice.length - 1]) : "";
  return {
    items: slice,
    nextCursor,
    hasMore,
    totalCount: items.length
  };
}

function mapDocs(snap) {
  return (snap?.docs || []).map((doc) => ({
    _docId: doc.id,
    ...(typeof doc.data === "function" ? doc.data() || {} : {})
  }));
}

async function readBounded(baseQuery, maxLimit, batchSize = DEFAULT_BATCH_SIZE) {
  const safeMax = Math.max(1, Math.floor(Number(maxLimit || 250)));
  const safeBatch = Math.min(safeMax + 1, Math.max(1, Math.floor(Number(batchSize || DEFAULT_BATCH_SIZE))));
  const canCursor = typeof baseQuery.startAfter === "function" && typeof baseQuery.limit === "function";

  if (!canCursor) {
    const q = typeof baseQuery.limit === "function" ? baseQuery.limit(safeMax + 1) : baseQuery;
    const snap = await q.get();
    const mapped = mapDocs(snap);
    const truncated = mapped.length > safeMax;
    return { docs: truncated ? mapped.slice(0, safeMax) : mapped, truncated };
  }

  const collected = [];
  let lastDocSnap = null;
  let truncated = false;
  while (collected.length <= safeMax) {
    const fetchSize = Math.min(safeBatch, safeMax + 1 - collected.length);
    let q = baseQuery;
    if (lastDocSnap) q = q.startAfter(lastDocSnap);
    q = q.limit(fetchSize);
    const snap = await q.get();
    const rawDocs = snap?.docs || [];
    if (rawDocs.length === 0) break;
    for (const d of rawDocs) {
      collected.push({
        _docId: d.id,
        ...(typeof d.data === "function" ? d.data() || {} : {})
      });
    }
    lastDocSnap = rawDocs[rawDocs.length - 1];
    if (collected.length > safeMax) {
      truncated = true;
      break;
    }
    if (rawDocs.length < fetchSize) break;
  }
  return {
    docs: truncated ? collected.slice(0, safeMax) : collected,
    truncated
  };
}

function getUserStatusLabel(state) {
  switch (String(state || "").trim().toUpperCase()) {
    case CAMPAIGN_CONVERSION_STATES.PENDING:
      return "Pending Verification";
    case CAMPAIGN_CONVERSION_STATES.APPROVED:
      return "Approved";
    case CAMPAIGN_CONVERSION_STATES.REJECTED:
      return "Rejected";
    case CAMPAIGN_CONVERSION_STATES.REVERSED:
      return "Reversed";
    default:
      return "Pending Verification";
  }
}

/**
 * Projects a conversion document into a strictly sanitized end-user activity item.
 * NEVER exposes clickId, raw conversionId (`conv_<clickId>`), externalTransactionId,
 * idempotencyKey, partner parameters, signatures, secrets, or internal moderation metadata.
 */
function toSafeUserActivityDto(convDoc = {}, campaignTitleMap = new Map()) {
  const rawConvId = String(convDoc.conversionId || convDoc._docId || "").trim();
  const campaignId = String(convDoc.campaignId || "").trim();
  const state = String(convDoc.state || CAMPAIGN_CONVERSION_STATES.PENDING)
    .trim()
    .toUpperCase();
  const safeState = Object.values(CAMPAIGN_CONVERSION_STATES).includes(state)
    ? state
    : CAMPAIGN_CONVERSION_STATES.PENDING;
  const rewardTf = Math.max(0, Math.floor(Number(convDoc.rewardTf || 0)));
  const createdAtMillis = toMillisValue(convDoc.createdAt);
  const verifiedAtMillis = toMillisValue(convDoc.verifiedAt);
  const rejectedAtMillis = toMillisValue(convDoc.rejectedAt);
  const reversedAtMillis = toMillisValue(convDoc.reversedAt);
  const updatedAtMillis = Math.max(
    toMillisValue(convDoc.updatedAt),
    reversedAtMillis,
    verifiedAtMillis,
    rejectedAtMillis,
    createdAtMillis
  );

  return {
    activityId: buildUserActivityId(rawConvId),
    campaignId,
    campaignTitle: campaignTitleMap.get(campaignId) || campaignId || "Tracked Campaign",
    status: safeState,
    statusLabel: getUserStatusLabel(safeState),
    rewardTf,
    rewardCents: tfToBalanceCents(rewardTf),
    rewardCredited: convDoc.rewardCredited === true && safeState === CAMPAIGN_CONVERSION_STATES.APPROVED,
    createdAtMillis,
    verifiedAtMillis,
    rejectedAtMillis,
    reversedAtMillis,
    updatedAtMillis
  };
}

function createCampaignUserHandlers({ getDefaultDb, HttpsError }) {
  /**
   * Lists eligible active tracked campaigns for the authenticated user.
   * All eligibility, budget, schedule, geo trust, and platform filters run strictly on the server.
   */
  async function handleListUserEligibleTrackedCampaigns(request, deps = {}) {
    const authUid = request.auth?.uid;
    if (!authUid) {
      throw new HttpsError("unauthenticated", "Authentication required to browse tracked campaigns.");
    }

    const firestoreDb = deps.db || getDefaultDb();
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
    const data = request.data || {};

    // Reject explicit cross-user spoofing attempts if client ever attempts to pass another userId
    if (data.userId !== undefined && String(data.userId || "").trim() !== "" && String(data.userId).trim() !== authUid) {
      throw new HttpsError("permission-denied", "Cannot query eligible campaigns on behalf of another user.");
    }

    const deviceContext = resolveDeviceTrustContext(data, request.app || deps.appCheckToken || null);
    if (!deviceContext.platform) {
      throw new HttpsError("invalid-argument", "Unsupported device platform.");
    }
    const countryContext = resolveCountryTrustContext(
      deps.rawHeaders || request.rawRequest?.headers || {},
      { trustCloudflareHeaders: deps.trustCloudflareHeaders }
    );

    const pageSize = clampUserPageSize(data.pageSize);
    const cursor = String(data.cursor || "").trim();
    const categoryFilter = String(data.category || "").trim().toLowerCase();

    const userSnap = await firestoreDb.collection("users").doc(authUid).get();
    if (!userSnap.exists) {
      throw new HttpsError("failed-precondition", "User profile not found.");
    }
    const userData = userSnap.data() || {};
    const accountApproved = userData.approvalStatus === "approved";

    const configsCol = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS);
    const networksCol = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS);
    const guardsCol = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICK_GUARDS);
    const publicCampaignsCol = firestoreDb.collection("campaigns");

    const activeConfigsQuery =
      typeof configsCol.where === "function" ? configsCol.where("isActive", "==", true) : configsCol;
    const activeNetworksQuery =
      typeof networksCol.where === "function" ? networksCol.where("isActive", "==", true) : networksCol;
    const userGuardsQuery =
      typeof guardsCol.where === "function" ? guardsCol.where("userId", "==", authUid) : guardsCol;

    const [configsRes, networksRes, guardsRes] = await Promise.all([
      readBounded(activeConfigsQuery, deps.maxCampaignsScan || MAX_USER_CAMPAIGNS_SCAN),
      readBounded(activeNetworksQuery, deps.maxNetworksScan || MAX_USER_NETWORKS_SCAN),
      readBounded(userGuardsQuery, deps.maxCampaignsScan || MAX_USER_CAMPAIGNS_SCAN)
    ]);

    const networkById = new Map();
    for (const net of networksRes.docs) {
      const nId = String(net.networkId || net._docId || "").trim();
      if (nId && net.isActive === true) {
        networkById.set(nId, { ...net, networkId: nId });
      }
    }

    const guardByCampaignId = new Map();
    for (const guard of guardsRes.docs) {
      if (String(guard.userId || "").trim() !== authUid) continue;
      const cId = String(guard.campaignId || "").trim();
      if (cId) guardByCampaignId.set(cId, guard);
    }

    // Fetch public campaign metadata only for candidate active configs
    const candidateConfigs = configsRes.docs.filter((cfg) => cfg.isActive === true);
    const publicMetaMap = new Map();
    await Promise.all(
      candidateConfigs.map(async (cfg) => {
        const cId = String(cfg.campaignId || cfg.configId || cfg._docId || "").trim();
        if (!cId) return;
        try {
          const pubSnap = await publicCampaignsCol.doc(cId).get();
          if (pubSnap.exists) {
            publicMetaMap.set(cId, pubSnap.data() || {});
          }
        } catch (_) {
          // Non-fatal fallback to config title/metadata
        }
      })
    );

    const eligibleItems = [];
    for (const cfg of candidateConfigs) {
      const campaignId = String(cfg.campaignId || cfg.configId || cfg._docId || "").trim();
      if (!campaignId) continue;

      const networkId = String(cfg.networkId || "").trim();
      const network = networkById.get(networkId);
      if (!network || network.isActive !== true) continue;
      if (network.capabilities?.supportsIncentivizedTraffic !== true) continue;

      const publicMeta = publicMetaMap.get(campaignId) || null;
      if (publicMeta) {
        const pubStatus = String(publicMeta.status || "ACTIVE").trim().toUpperCase();
        if (pubStatus !== "ACTIVE") continue;
      }

      // Schedule enforcement (authoritative server clock)
      const startAtMillis = Math.max(Number(cfg.startAtMillis || 0), toMillisValue(cfg.startAt));
      const expiresAtMillis = Math.max(Number(cfg.expiresAtMillis || 0), toMillisValue(cfg.expiresAt));
      if (startAtMillis > 0 && nowMillis < startAtMillis) continue;
      if (expiresAtMillis > 0 && nowMillis > expiresAtMillis) continue;

      // Reward & budget enforcement
      const rewardTf = Math.floor(Number(cfg.rewardTf || 0));
      if (!Number.isInteger(rewardTf) || rewardTf <= 0) continue;
      const submissionBudget = Math.max(0, Math.floor(Number(cfg.submissionBudget || 0)));
      const usedSubmissions = Math.max(0, Math.floor(Number(cfg.usedSubmissions || 0)));
      if (submissionBudget > 0 && usedSubmissions >= submissionBudget) continue;

      const rewardBudgetTf = Math.max(0, Math.floor(Number(cfg.rewardBudgetTf || 0)));
      const spentBudgetTf = Math.max(0, Math.floor(Number(cfg.spentBudgetTf || 0)));
      if (rewardBudgetTf > 0 && spentBudgetTf + rewardTf > rewardBudgetTf) continue;

      // Destination URL must be valid server-side HTTPS URL (never returned to user here!)
      const rawBaseUrl = String(cfg.trackingBaseUrl || publicMeta?.taskLink || "").trim();
      const combinedAllowedHosts = [
        ...(Array.isArray(network.allowedDestinationHosts) ? network.allowedDestinationHosts : []),
        ...(Array.isArray(cfg.allowedDestinationHosts) ? cfg.allowedDestinationHosts : [])
      ].filter(Boolean);
      if (!rawBaseUrl || !validateDestinationUrl(rawBaseUrl, combinedAllowedHosts).valid) {
        continue;
      }

      // Platform eligibility
      const allowedPlatforms = Array.isArray(cfg.allowedPlatforms)
        ? cfg.allowedPlatforms.map((p) => String(p || "").trim().toUpperCase())
        : ["ANDROID"];
      if (
        allowedPlatforms.length > 0 &&
        !allowedPlatforms.includes("ALL") &&
        !allowedPlatforms.includes(deviceContext.platform)
      ) {
        continue;
      }

      // Fail-closed trusted country verification (never trusts client country)
      const allowedCountries = Array.isArray(cfg.allowedCountries)
        ? cfg.allowedCountries.map((c) => String(c || "").trim().toUpperCase())
        : ["ALL"];
      const allowsAllCountries = allowedCountries.length === 0 || allowedCountries.includes("ALL");
      const requireVerifiedCountry = cfg.conversionRequirements?.requireVerifiedCountry === true;
      if (!allowsAllCountries || requireVerifiedCountry) {
        if (!countryContext.countryCode || countryContext.trustLevel === "UNVERIFIED") {
          continue;
        }
        if (!allowsAllCountries && !allowedCountries.includes(countryContext.countryCode)) {
          continue;
        }
      }

      // Per-user completion / pending state from server guard
      const guard = guardByCampaignId.get(campaignId) || {};
      const allowMultiple = cfg.conversionRequirements?.allowMultipleConversions === true;
      let userStatus = "AVAILABLE";
      if (guard.hasCompletedConversion === true && !allowMultiple) {
        userStatus = "COMPLETED";
      } else if (guard.hasPendingConversion === true) {
        userStatus = "PENDING";
      }

      const category = String(publicMeta?.category || "Partner Offer").trim() || "Partner Offer";
      if (categoryFilter && categoryFilter !== "all" && !category.toLowerCase().includes(categoryFilter)) {
        continue;
      }

      const convReqs = cfg.conversionRequirements || {};
      const requiresAdminApproval =
        convReqs.requireAdminApproval === true ||
        convReqs.autoApproveVerifiedPostback === false ||
        network.capabilities?.requiresManualReview === true;
      const requireAppCheck =
        cfg.requireDeviceAttestation === true || convReqs.requireAppCheck === true;

      eligibleItems.push({
        campaignId,
        title: String(cfg.title || publicMeta?.title || campaignId).trim(),
        description: String(
          publicMeta?.description ||
            "Open this verified partner offer to complete the action and earn TF rewards."
        ).trim(),
        category,
        clientName: String(publicMeta?.clientName || network.name || "Verified Partner").trim(),
        appIconUrl: String(publicMeta?.appIconUrl || "").trim(),
        rewardTf,
        rewardCents: tfToBalanceCents(rewardTf),
        verificationMode: String(cfg.verificationMode || "").trim(),
        requiresAdminApproval,
        requireAppCheck,
        attributionWindowHours: Math.max(1, Math.floor(Number(convReqs.attributionWindowHours || 72))),
        clickTtlMinutes: Math.max(1, Math.floor(Number(cfg.clickTtlMinutes || 60))),
        startDate: String(cfg.startDate || "").trim(),
        endDate: String(cfg.endDate || "").trim(),
        userStatus
      });
    }

    eligibleItems.sort((a, b) => {
      // Available first, then Pending, then Completed; tie-break by highest TF reward then title
      const rank = (s) => (s === "AVAILABLE" ? 0 : s === "PENDING" ? 1 : 2);
      const diff = rank(a.userStatus) - rank(b.userStatus);
      if (diff !== 0) return diff;
      if (b.rewardTf !== a.rewardTf) return b.rewardTf - a.rewardTf;
      return a.title.localeCompare(b.title);
    });

    const page = paginateItems(eligibleItems, pageSize, cursor, (item) => item.campaignId);
    return {
      success: true,
      accountApproved,
      ...page,
      truncated: Boolean(configsRes.truncated || networksRes.truncated)
    };
  }

  /**
   * Read-only endpoint returning ONLY the authenticated user's own tracked reward activity.
   * Strictly derives `userId` from `request.auth.uid` and strips all sensitive attribution
   * identifiers (`clickId`, raw `conversionId`, `externalTransactionId`, secrets, moderation notes).
   */
  async function handleListUserTrackedRewardActivity(request, deps = {}) {
    const authUid = request.auth?.uid;
    if (!authUid) {
      throw new HttpsError("unauthenticated", "Authentication required to view tracked reward activity.");
    }

    const firestoreDb = deps.db || getDefaultDb();
    const data = request.data || {};

    // Never accept arbitrary userId from the client; fail closed if caller attempts cross-user access
    if (data.userId !== undefined && String(data.userId || "").trim() !== "" && String(data.userId).trim() !== authUid) {
      throw new HttpsError("permission-denied", "Cross-user activity access is prohibited.");
    }

    const statusFilter = String(data.status || "ALL").trim().toUpperCase();
    if (!ALLOWED_USER_ACTIVITY_FILTERS.has(statusFilter)) {
      throw new HttpsError(
        "invalid-argument",
        "status filter must be ALL, PENDING, APPROVED, REJECTED, or REVERSED."
      );
    }

    const pageSize = clampUserPageSize(data.pageSize);
    const cursor = String(data.cursor || "").trim();

    const conversionsCol = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS);
    const userConversionsQuery =
      typeof conversionsCol.where === "function"
        ? conversionsCol.where("userId", "==", authUid)
        : conversionsCol;

    const { docs: rawDocs, truncated } = await readBounded(
      userConversionsQuery,
      deps.maxUserActivityScan || MAX_USER_ACTIVITY_SCAN
    );

    // Defensive double-check ensuring strictly authUid's own records even on mock DBs
    const ownDocs = rawDocs.filter((doc) => String(doc.userId || "").trim() === authUid);

    // Resolve human-readable campaign titles cleanly for unique campaignIds
    const uniqueCampaignIds = Array.from(
      new Set(ownDocs.map((d) => String(d.campaignId || "").trim()).filter(Boolean))
    );
    const campaignTitleMap = new Map();
    const configsCol = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS);
    const publicCampaignsCol = firestoreDb.collection("campaigns");

    await Promise.all(
      uniqueCampaignIds.map(async (cId) => {
        try {
          const [cfgSnap, pubSnap] = await Promise.all([
            configsCol.doc(cId).get(),
            publicCampaignsCol.doc(cId).get()
          ]);
          const title = String(
            (cfgSnap.exists ? cfgSnap.data()?.title : "") ||
              (pubSnap.exists ? pubSnap.data()?.title : "") ||
              cId
          ).trim();
          campaignTitleMap.set(cId, title || cId);
        } catch (_) {
          campaignTitleMap.set(cId, cId);
        }
      })
    );

    const allDtos = ownDocs.map((doc) => toSafeUserActivityDto(doc, campaignTitleMap));

    const counts = {
      total: allDtos.length,
      pending: allDtos.filter((i) => i.status === CAMPAIGN_CONVERSION_STATES.PENDING).length,
      approved: allDtos.filter((i) => i.status === CAMPAIGN_CONVERSION_STATES.APPROVED).length,
      rejected: allDtos.filter((i) => i.status === CAMPAIGN_CONVERSION_STATES.REJECTED).length,
      reversed: allDtos.filter((i) => i.status === CAMPAIGN_CONVERSION_STATES.REVERSED).length
    };

    const filtered = allDtos
      .filter((item) => statusFilter === "ALL" || item.status === statusFilter)
      .sort((a, b) => {
        if (b.updatedAtMillis !== a.updatedAtMillis) {
          return b.updatedAtMillis - a.updatedAtMillis;
        }
        return a.activityId.localeCompare(b.activityId);
      });

    const page = paginateItems(filtered, pageSize, cursor, (item) => item.activityId);
    return {
      success: true,
      statusFilter,
      counts,
      ...page,
      truncated: Boolean(truncated)
    };
  }

  return {
    handleListUserEligibleTrackedCampaigns,
    handleListUserTrackedRewardActivity
  };
}

module.exports = {
  toSafeUserActivityDto,
  createCampaignUserHandlers
};
