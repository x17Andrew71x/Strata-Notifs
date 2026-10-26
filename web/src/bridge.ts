import type { DevFeedbackItem } from "./dev-feedback/model";

export const BRIDGE_VERSION = 2 as const;
const DEV_FEEDBACK_BUILD = import.meta.env.VITE_DEV_FEEDBACK_ENABLED === "true";

const CORE_NO_PAYLOAD_ACTIONS = [
  "capabilities.get",
  "state.get",
  "notificationAccess.openSettings",
  "notificationAccess.openAppDetails",
  "onboarding.complete",
  "formation.reveal",
  "exhibits.refresh",
  "worlds.reset",
] as const;
const DEV_NO_PAYLOAD_ACTIONS = ["devFeedback.items", "devFeedback.refresh"] as const;
const CORE_PAYLOAD_ACTIONS = [
  "catalog.update",
  "preferences.update",
  "museum.lock",
  "museum.share",
  "museum.combine",
  "excavation.dig",
  "exhibits.contribute",
  "worlds.select",
  "worlds.own",
] as const;
const DEV_PAYLOAD_ACTIONS = [
  "devFeedback.submit",
  "devFeedback.amend",
  "devFeedback.accept",
  "devFeedback.close",
] as const;
const NO_PAYLOAD_ACTIONS: readonly string[] = DEV_FEEDBACK_BUILD
  ? [...CORE_NO_PAYLOAD_ACTIONS, ...DEV_NO_PAYLOAD_ACTIONS]
  : CORE_NO_PAYLOAD_ACTIONS;
const PAYLOAD_ACTIONS: readonly string[] = DEV_FEEDBACK_BUILD
  ? [...CORE_PAYLOAD_ACTIONS, ...DEV_PAYLOAD_ACTIONS]
  : CORE_PAYLOAD_ACTIONS;
const FOSSIL_FAMILIES: ReadonlySet<string> = new Set([
  "AMMONITE",
  "BELEMNITE",
  "BRACHIOPOD",
  "BIVALVE",
  "CRINOID",
  "CORAL",
  "TRILOBITE",
  "FERN_IMPRINT",
  "SHARK_TOOTH",
  "STROMATOLITE",
  "ECHINOID",
  "FISH",
  "STARFISH",
  "CRAB",
  "AMBER",
  "DINOSAUR_EMBRYO",
  "ARCHAEOPTERYX",
  "TRACKWAY",
  "GEODE",
  "METEORITE_FRAGMENT",
  "TRACE_PLATE",
  "COPROLITE",
]);

export type NativeAction =
  | (typeof CORE_NO_PAYLOAD_ACTIONS)[number]
  | (typeof DEV_NO_PAYLOAD_ACTIONS)[number]
  | (typeof CORE_PAYLOAD_ACTIONS)[number]
  | (typeof DEV_PAYLOAD_ACTIONS)[number];
export type PreferenceKey =
  | "onlineFeaturesEnabled"
  | "productAnalyticsEnabled"
  | "notificationAggregateSharingEnabled"
  | "reduceMotionEnabled"
  | "highContrastEnabled"
  | "hapticsEnabled";
export type World = "PRIMEVAL_STRATA" | "DEEP_SPACE" | "BOTANICAL_ARCHIVE" | "THE_ABYSS";
export type Tier = "COMMON" | "UNCOMMON" | "RARE" | "EXCEPTIONAL" | "SINGULAR";
export type CollectibleState = "ORDINARY" | "RESTORED" | "CENTRE_PIECE";
export type DevFeedbackOperation = "list" | "submit" | "amend" | "accept" | "close";

export type NativeRequest = {
  version: 2;
  id: string;
  type: NativeAction;
  payload?: Record<string, unknown>;
};

export type ShellPreferences = {
  onboardingComplete: boolean;
  onlineFeaturesEnabled: boolean;
  productAnalyticsEnabled: boolean;
  notificationAggregateSharingEnabled: boolean;
  reduceMotionEnabled: boolean;
  highContrastEnabled: boolean;
  hapticsEnabled: boolean;
};

export type ShellSpecimen = {
  id: string;
  anchoredLocalDate: string | null;
  generatorVersion: number;
  createdAtEpochMillis: number;
  revealedAtEpochMillis: number | null;
  isLocked: boolean;
  collectibleState: CollectibleState;
  provenanceCount: number;
  family: string;
  tier: Tier;
  catalogItemId?: string | null;
  visual: {
    hueDegrees: number;
    strataCount: number;
    inclusionDensityPercent: number;
    reliefPercent: number;
    rotationDegrees: number;
  };
};

export type ShellExhibit = {
  id: string;
  title: string;
  seasonLabel: string;
  imagePath: string;
  progressBasisPoints: number;
  personalContribution: number;
  personalMinimum: number;
  daysRemaining: number | null;
  communityComplete: boolean;
  qualified: boolean;
  unlocked: boolean;
  closed: boolean;
};

export type ShellPastExhibit = {
  id: string;
  title: string;
  seasonLabel: string;
  imagePath: string;
  unlocked: boolean;
  lockedReason: string | null;
};

export type ShellExhibits = {
  status: "DISABLED" | "LOADING" | "READY" | "OFFLINE";
  bankedEnergy: number;
  contributionInFlight: boolean;
  featured: ShellExhibit | null;
  archive: ShellExhibit | null;
  past: ShellPastExhibit[];
};

export type ShellState = {
  version: 2;
  id: string;
  type: "shell.state";
  notificationAccess: boolean;
  preferences: ShellPreferences;
  today: {
    localDate: string;
    observation:
      | "AwaitingAccess"
      | "Active"
      | "Disconnected"
      | "Revoked"
      | "SealedObserved"
      | "SealedUnobserved";
    layers: Array<{ localHour: number; category: string; sourceColourRgb: number }>;
    specimen: ShellSpecimen | null;
    excavation?: {
      artifactId: string;
      capturedNotificationCount: number;
      eligibleNotificationCount: number;
      energyPerNotification: number;
      energyEarned: number;
      energySpent: number;
      energyAvailable: number;
      tileEnergyCost: number;
      gridColumns: number;
      gridRows: number;
      dugTiles: number[];
      completedAtEpochMillis: number | null;
    } | null;
    revealInFlight: boolean;
    digInFlight?: boolean;
    primaryAction: "ENABLE_ACCESS" | "REVEAL" | "NONE";
  };
  museum: { specimens: ShellSpecimen[] };
  exhibits?: ShellExhibits;
  worlds: {
    selected: World;
    owned: World[];
    available: World[];
    developmentControlsEnabled: boolean;
  };
};

export type NativeResponse =
  | ShellState
  | {
      version: 2;
      id: string;
      type: "capabilities.state";
      bridgeVersion: 2;
      notificationAccess: boolean;
      appDetailsAction: boolean;
      catalogUpdates?: boolean;
      devFeedback?: boolean;
      appVersion?: string;
    }
  | {
      version: 2;
      id: string;
      type: "action.result";
      action: NativeAction;
      ok: boolean;
    }
  | {
      version: 2;
      id: string;
      type: "devFeedback.state";
      operation: DevFeedbackOperation;
      ok: boolean;
      error: string | null;
      item: DevFeedbackItem | null;
      items: DevFeedbackItem[];
    };

declare global {
  interface Window {
    AfterchimeBridge?: {
      postMessage(message: string): void;
      onmessage: ((event: MessageEvent<string>) => void) | null;
    };
  }
}

export function parseNativeRequest(value: unknown): NativeRequest | null {
  if (!isRecord(value) || value.version !== BRIDGE_VERSION || !isValidId(value.id)) return null;
  if (typeof value.type !== "string" || !isNativeAction(value.type)) return null;
  if ((NO_PAYLOAD_ACTIONS as readonly string[]).includes(value.type)) {
    return hasExactKeys(value, ["version", "id", "type"])
      ? { version: 2, id: value.id, type: value.type as NativeAction }
      : null;
  }
  if (!hasExactKeys(value, ["version", "id", "type", "payload"]) || !isRecord(value.payload)) {
    return null;
  }
  if (!validPayload(value.type, value.payload)) return null;
  return { version: 2, id: value.id, type: value.type, payload: value.payload };
}

export function sendNativeRequest(
  type: NativeAction,
  payload?: Record<string, unknown>,
): string | null {
  const bridge = window.AfterchimeBridge;
  if (!bridge) return null;
  const id = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
  const request = payload
    ? { version: BRIDGE_VERSION, id, type, payload }
    : { version: BRIDGE_VERSION, id, type };
  if (!parseNativeRequest(request)) return null;
  bridge.postMessage(JSON.stringify(request));
  return id;
}

export function installResponseListener(
  onResponse: (response: NativeResponse) => void,
): () => void {
  const listener = (event: MessageEvent<string>) => {
    if (typeof event.data !== "string" || event.data.length > 2 * 1024 * 1024) return;
    try {
      const parsed: unknown = JSON.parse(event.data);
      const response = parseNativeResponse(parsed);
      if (response) onResponse(response);
    } catch {
      // Malformed native messages never become shell state.
    }
  };
  const bridge = window.AfterchimeBridge;
  const previousBridgeListener = bridge?.onmessage ?? null;
  if (bridge) bridge.onmessage = listener;
  window.addEventListener("message", listener);
  return () => {
    window.removeEventListener("message", listener);
    if (bridge?.onmessage === listener) bridge.onmessage = previousBridgeListener;
  };
}

function parseNativeResponse(value: unknown): NativeResponse | null {
  if (!isRecord(value) || value.version !== BRIDGE_VERSION || !isValidId(value.id)) return null;
  if (value.type === "capabilities.state") {
    const legacyKeys = [
      "version",
      "id",
      "type",
      "bridgeVersion",
      "notificationAccess",
      "appDetailsAction",
    ];
    const catalogKeys = [...legacyKeys, "catalogUpdates"];
    if (
      (hasExactKeys(value, legacyKeys) ||
        hasExactKeys(value, catalogKeys) ||
        (DEV_FEEDBACK_BUILD && isDevFeedbackCapabilities(value, catalogKeys))) &&
      value.bridgeVersion === BRIDGE_VERSION &&
      typeof value.notificationAccess === "boolean" &&
      typeof value.appDetailsAction === "boolean" &&
      (value.catalogUpdates === undefined || typeof value.catalogUpdates === "boolean")
    ) {
      return value as NativeResponse;
    }
  }
  if (DEV_FEEDBACK_BUILD && value.type === "devFeedback.state" && isDevFeedbackState(value)) {
    return value;
  }
  if (
    value.type === "action.result" &&
    hasExactKeys(value, ["version", "id", "type", "action", "ok"]) &&
    typeof value.action === "string" &&
    isNativeAction(value.action) &&
    typeof value.ok === "boolean"
  ) {
    return value as NativeResponse;
  }
  return isShellState(value) ? value : null;
}

function isDevFeedbackCapabilities(
  value: Record<string, unknown>,
  catalogKeys: readonly string[],
): boolean {
  return (
    hasExactKeys(value, [...catalogKeys, "devFeedback", "appVersion"]) &&
    typeof value.devFeedback === "boolean" &&
    validBoundedText(value.appVersion, 1, 64)
  );
}

function isShellState(value: Record<string, unknown>): value is ShellState {
  const baseKeys = [
    "version",
    "id",
    "type",
    "notificationAccess",
    "preferences",
    "today",
    "museum",
    "worlds",
  ];
  const exhibitKeys = [...baseKeys, "exhibits"];
  if (
    value.type !== "shell.state" ||
    (!hasExactKeys(value, baseKeys) && !hasExactKeys(value, exhibitKeys)) ||
    typeof value.notificationAccess !== "boolean" ||
    !isPreferences(value.preferences) ||
    !isToday(value.today) ||
    !isRecord(value.museum) ||
    !hasExactKeys(value.museum, ["specimens"]) ||
    !Array.isArray(value.museum.specimens) ||
    !value.museum.specimens.every(isSpecimen) ||
    (value.exhibits !== undefined && !isExhibits(value.exhibits)) ||
    !isWorldState(value.worlds)
  ) {
    return false;
  }
  return true;
}

function isExhibits(value: unknown): value is ShellExhibits {
  return (
    isRecord(value) &&
    hasExactKeys(value, [
      "status",
      "bankedEnergy",
      "contributionInFlight",
      "featured",
      "archive",
      "past",
    ]) &&
    typeof value.status === "string" &&
    ["DISABLED", "LOADING", "READY", "OFFLINE"].includes(value.status) &&
    isInteger(value.bankedEnergy, 0, 100_000) &&
    typeof value.contributionInFlight === "boolean" &&
    (value.featured === null || isExhibit(value.featured)) &&
    (value.archive === null || isExhibit(value.archive)) &&
    Array.isArray(value.past) &&
    value.past.length <= 100 &&
    value.past.every(isPastExhibit)
  );
}

function isExhibit(value: unknown): value is ShellExhibit {
  return (
    isRecord(value) &&
    hasExactKeys(value, [
      "id",
      "title",
      "seasonLabel",
      "imagePath",
      "progressBasisPoints",
      "personalContribution",
      "personalMinimum",
      "daysRemaining",
      "communityComplete",
      "qualified",
      "unlocked",
      "closed",
    ]) &&
    validExhibitId(value.id) &&
    validBoundedText(value.title, 1, 80) &&
    validBoundedText(value.seasonLabel, 1, 48) &&
    validExhibitImagePath(value.imagePath) &&
    isInteger(value.progressBasisPoints, 0, 10_000) &&
    isInteger(value.personalContribution, 0, 10_000_000) &&
    isInteger(value.personalMinimum, 1, 10_000_000) &&
    (value.daysRemaining === null || isInteger(value.daysRemaining, 0, 3_650)) &&
    typeof value.communityComplete === "boolean" &&
    typeof value.qualified === "boolean" &&
    typeof value.unlocked === "boolean" &&
    typeof value.closed === "boolean"
  );
}

function isPastExhibit(value: unknown): value is ShellPastExhibit {
  return (
    isRecord(value) &&
    hasExactKeys(value, ["id", "title", "seasonLabel", "imagePath", "unlocked", "lockedReason"]) &&
    validExhibitId(value.id) &&
    validBoundedText(value.title, 1, 80) &&
    validBoundedText(value.seasonLabel, 1, 48) &&
    validExhibitImagePath(value.imagePath) &&
    typeof value.unlocked === "boolean" &&
    (value.lockedReason === null || validBoundedText(value.lockedReason, 1, 80))
  );
}

function isPreferences(value: unknown): value is ShellPreferences {
  if (!isRecord(value)) return false;
  const keys = [
    "onboardingComplete",
    "onlineFeaturesEnabled",
    "productAnalyticsEnabled",
    "notificationAggregateSharingEnabled",
    "reduceMotionEnabled",
    "highContrastEnabled",
    "hapticsEnabled",
  ];
  return hasExactKeys(value, keys) && keys.every((key) => typeof value[key] === "boolean");
}

function isToday(value: unknown): value is ShellState["today"] {
  const legacyKeys = [
    "localDate",
    "observation",
    "layers",
    "specimen",
    "revealInFlight",
    "primaryAction",
  ];
  const excavationKeys = [...legacyKeys, "excavation", "digInFlight"];
  if (
    !isRecord(value) ||
    (!hasExactKeys(value, legacyKeys) && !hasExactKeys(value, excavationKeys))
  )
    return false;
  const observations = [
    "AwaitingAccess",
    "Active",
    "Disconnected",
    "Revoked",
    "SealedObserved",
    "SealedUnobserved",
  ];
  const actions = ["ENABLE_ACCESS", "REVEAL", "NONE"];
  return (
    typeof value.localDate === "string" &&
    /^\d{4}-\d{2}-\d{2}$/.test(value.localDate) &&
    typeof value.observation === "string" &&
    observations.includes(value.observation) &&
    Array.isArray(value.layers) &&
    value.layers.every(isLayer) &&
    (value.specimen === null || isSpecimen(value.specimen)) &&
    (value.excavation === undefined ||
      value.excavation === null ||
      isExcavation(value.excavation)) &&
    typeof value.revealInFlight === "boolean" &&
    (value.digInFlight === undefined || typeof value.digInFlight === "boolean") &&
    typeof value.primaryAction === "string" &&
    actions.includes(value.primaryAction)
  );
}

function isLayer(value: unknown): boolean {
  return (
    isRecord(value) &&
    hasExactKeys(value, ["localHour", "category", "sourceColourRgb"]) &&
    isInteger(value.localHour, 0, 23) &&
    typeof value.category === "string" &&
    /^[A-Z_]{1,40}$/.test(value.category) &&
    isInteger(value.sourceColourRgb, -2147483648, 2147483647)
  );
}

function isSpecimen(value: unknown): value is ShellSpecimen {
  const legacyKeys = [
    "id",
    "anchoredLocalDate",
    "generatorVersion",
    "createdAtEpochMillis",
    "revealedAtEpochMillis",
    "isLocked",
    "collectibleState",
    "provenanceCount",
    "family",
    "tier",
    "visual",
  ];
  const catalogKeys = [...legacyKeys, "catalogItemId"];
  if (!isRecord(value) || (!hasExactKeys(value, legacyKeys) && !hasExactKeys(value, catalogKeys)))
    return false;
  const states = ["ORDINARY", "RESTORED", "CENTRE_PIECE"];
  const tiers = ["COMMON", "UNCOMMON", "RARE", "EXCEPTIONAL", "SINGULAR"];
  return (
    typeof value.id === "string" &&
    /^[A-Za-z0-9_-]{1,128}$/.test(value.id) &&
    (value.anchoredLocalDate === null ||
      (typeof value.anchoredLocalDate === "string" &&
        /^\d{4}-\d{2}-\d{2}$/.test(value.anchoredLocalDate))) &&
    isInteger(value.generatorVersion, 1) &&
    isInteger(value.createdAtEpochMillis, 0) &&
    (value.revealedAtEpochMillis === null || isInteger(value.revealedAtEpochMillis, 0)) &&
    typeof value.isLocked === "boolean" &&
    typeof value.collectibleState === "string" &&
    states.includes(value.collectibleState) &&
    isInteger(value.provenanceCount, 1) &&
    typeof value.family === "string" &&
    /^[A-Z_]{1,40}$/.test(value.family) &&
    typeof value.tier === "string" &&
    tiers.includes(value.tier) &&
    (value.catalogItemId === undefined ||
      value.catalogItemId === null ||
      (typeof value.catalogItemId === "string" && /^[a-z0-9-]{1,64}$/.test(value.catalogItemId))) &&
    isVisual(value.visual)
  );
}

function isVisual(value: unknown): boolean {
  return (
    isRecord(value) &&
    hasExactKeys(value, [
      "hueDegrees",
      "strataCount",
      "inclusionDensityPercent",
      "reliefPercent",
      "rotationDegrees",
    ]) &&
    isInteger(value.hueDegrees, 0, 359) &&
    isInteger(value.strataCount, 4, 16) &&
    isInteger(value.inclusionDensityPercent, 0, 100) &&
    isInteger(value.reliefPercent, 30, 100) &&
    isInteger(value.rotationDegrees, 0, 359)
  );
}

function isExcavation(value: unknown): boolean {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, [
      "artifactId",
      "capturedNotificationCount",
      "eligibleNotificationCount",
      "energyPerNotification",
      "energyEarned",
      "energySpent",
      "energyAvailable",
      "tileEnergyCost",
      "gridColumns",
      "gridRows",
      "dugTiles",
      "completedAtEpochMillis",
    ])
  )
    return false;
  const columns = value.gridColumns;
  const rows = value.gridRows;
  if (!isInteger(columns, 1, 10) || !isInteger(rows, 1, 10)) return false;
  const tileCount = (columns as number) * (rows as number);
  return (
    typeof value.artifactId === "string" &&
    /^[a-z0-9-]{1,64}$/.test(value.artifactId) &&
    isInteger(value.capturedNotificationCount, 0) &&
    isInteger(value.eligibleNotificationCount, 0, value.capturedNotificationCount as number) &&
    isInteger(value.energyPerNotification, 1) &&
    isInteger(value.energyEarned, 0) &&
    isInteger(value.energySpent, 0) &&
    isInteger(value.energyAvailable, 0) &&
    isInteger(value.tileEnergyCost, 1) &&
    Array.isArray(value.dugTiles) &&
    value.dugTiles.every((tile) => isInteger(tile, 0, tileCount - 1)) &&
    new Set(value.dugTiles).size === value.dugTiles.length &&
    (value.completedAtEpochMillis === null || isInteger(value.completedAtEpochMillis, 0))
  );
}

function isWorldState(value: unknown): value is ShellState["worlds"] {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, ["selected", "owned", "available", "developmentControlsEnabled"])
  )
    return false;
  return (
    isWorld(value.selected) &&
    Array.isArray(value.owned) &&
    value.owned.every(isWorld) &&
    Array.isArray(value.available) &&
    value.available.every(isWorld) &&
    typeof value.developmentControlsEnabled === "boolean" &&
    value.owned.includes(value.selected)
  );
}

function isDevFeedbackState(
  value: Record<string, unknown>,
): value is Extract<NativeResponse, { type: "devFeedback.state" }> {
  return (
    hasExactKeys(value, ["version", "id", "type", "operation", "ok", "error", "item", "items"]) &&
    typeof value.operation === "string" &&
    ["list", "submit", "amend", "accept", "close"].includes(value.operation) &&
    typeof value.ok === "boolean" &&
    (value.error === null || validBoundedText(value.error, 1, 80)) &&
    (value.item === null || isDevFeedbackItem(value.item)) &&
    Array.isArray(value.items) &&
    value.items.length <= 200 &&
    value.items.every(isDevFeedbackItem)
  );
}

function isDevFeedbackItem(value: unknown): value is DevFeedbackItem {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, [
      "id",
      "queuePosition",
      "revision",
      "title",
      "description",
      "status",
      "note",
      "createdAt",
      "updatedAt",
    ])
  ) {
    return false;
  }
  return (
    isUuid(value.id) &&
    isInteger(value.queuePosition, 1) &&
    isInteger(value.revision, 1, 10_000) &&
    validBoundedText(value.title, 1, 120) &&
    validBoundedText(value.description, 1, 4_000) &&
    typeof value.status === "string" &&
    ["queued", "in_progress", "blocked", "failed", "ready_for_qa", "accepted", "closed"].includes(
      value.status,
    ) &&
    (value.note === "" || validBoundedText(value.note, 1, 600)) &&
    isIsoTimestamp(value.createdAt) &&
    isIsoTimestamp(value.updatedAt)
  );
}

function validPayload(type: string, payload: Record<string, unknown>): boolean {
  if (type === "catalog.update") return isCatalogUpdate(payload);
  if (type === "preferences.update") {
    return (
      hasExactKeys(payload, ["key", "value"]) &&
      typeof payload.key === "string" &&
      [
        "onlineFeaturesEnabled",
        "productAnalyticsEnabled",
        "notificationAggregateSharingEnabled",
        "reduceMotionEnabled",
        "highContrastEnabled",
        "hapticsEnabled",
      ].includes(payload.key) &&
      typeof payload.value === "boolean"
    );
  }
  if (type === "museum.lock") {
    return (
      hasExactKeys(payload, ["specimenId", "locked"]) &&
      validSpecimenId(payload.specimenId) &&
      typeof payload.locked === "boolean"
    );
  }
  if (type === "museum.share") {
    return hasExactKeys(payload, ["specimenId"]) && validSpecimenId(payload.specimenId);
  }
  if (type === "museum.combine") {
    return (
      hasExactKeys(payload, ["specimenIds"]) &&
      Array.isArray(payload.specimenIds) &&
      payload.specimenIds.length === 3 &&
      payload.specimenIds.every(validSpecimenId) &&
      new Set(payload.specimenIds).size === 3
    );
  }
  if (type === "excavation.dig") {
    return hasExactKeys(payload, ["tileIndex"]) && isInteger(payload.tileIndex, 0, 99);
  }
  if (type === "exhibits.contribute") {
    return (
      hasExactKeys(payload, ["exhibitId", "localDate", "units"]) &&
      validExhibitId(payload.exhibitId) &&
      typeof payload.localDate === "string" &&
      /^\d{4}-\d{2}-\d{2}$/.test(payload.localDate) &&
      isInteger(payload.units, 1, 100_000)
    );
  }
  if (type === "worlds.select" || type === "worlds.own") {
    return hasExactKeys(payload, ["world"]) && isWorld(payload.world);
  }
  if (DEV_FEEDBACK_BUILD && (type === "devFeedback.accept" || type === "devFeedback.close")) {
    return hasExactKeys(payload, ["itemId"]) && isUuid(payload.itemId);
  }
  if (DEV_FEEDBACK_BUILD && type === "devFeedback.submit") {
    return isDevFeedbackDraft(payload, false);
  }
  if (DEV_FEEDBACK_BUILD && type === "devFeedback.amend") {
    return isDevFeedbackDraft(payload, true);
  }
  return false;
}

function isDevFeedbackDraft(payload: Record<string, unknown>, amendment: boolean): boolean {
  const keys = [
    ...(amendment ? ["itemId"] : []),
    "idempotencyKey",
    "title",
    "description",
    "route",
    "shellRevision",
    "appVersion",
    "selection",
  ];
  return (
    hasExactKeys(payload, keys) &&
    (!amendment || isUuid(payload.itemId)) &&
    isUuid(payload.idempotencyKey) &&
    validBoundedText(payload.title, 1, 120) &&
    validBoundedText(payload.description, 1, 4_000) &&
    validBoundedText(payload.route, 1, 160) &&
    typeof payload.shellRevision === "string" &&
    /^[0-9a-f]{64}$/.test(payload.shellRevision) &&
    validBoundedText(payload.appVersion, 1, 64) &&
    isDevFeedbackSelection(payload.selection)
  );
}

function isDevFeedbackSelection(value: unknown): boolean {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, [
      "kind",
      "rect",
      "viewportWidth",
      "viewportHeight",
      "devId",
      "tag",
      "role",
      "accessibleLabel",
      "visibleLabel",
      "domPath",
    ]) ||
    (value.kind !== "element" && value.kind !== "region") ||
    !isInteger(value.viewportWidth, 1, 16_384) ||
    !isInteger(value.viewportHeight, 1, 16_384) ||
    !isSelectionRect(value.rect) ||
    !validOptionalText(value.devId, 96) ||
    !validOptionalText(value.tag, 32) ||
    !validOptionalText(value.role, 64) ||
    !validOptionalText(value.accessibleLabel, 160) ||
    !validOptionalText(value.visibleLabel, 160) ||
    !validOptionalText(value.domPath, 512)
  ) {
    return false;
  }
  if (value.kind === "region") {
    return [
      value.devId,
      value.tag,
      value.role,
      value.accessibleLabel,
      value.visibleLabel,
      value.domPath,
    ].every((field) => field === "");
  }
  return value.tag !== "" && value.domPath !== "";
}

function isSelectionRect(value: unknown): boolean {
  return (
    isRecord(value) &&
    hasExactKeys(value, ["x", "y", "width", "height"]) &&
    isInteger(value.x, 0, 16_384) &&
    isInteger(value.y, 0, 16_384) &&
    isInteger(value.width, 1, 16_384) &&
    isInteger(value.height, 1, 16_384)
  );
}

function isCatalogUpdate(payload: Record<string, unknown>): boolean {
  if (
    !hasExactKeys(payload, ["schemaVersion", "revision", "items"]) ||
    payload.schemaVersion !== 1 ||
    !isInteger(payload.revision, 1, 1_000_000) ||
    !Array.isArray(payload.items) ||
    payload.items.length < 1 ||
    payload.items.length > 128
  ) {
    return false;
  }
  let totalWeight = 0;
  const ids = new Set<string>();
  for (const item of payload.items) {
    if (
      !isRecord(item) ||
      !hasExactKeys(item, ["id", "tier", "selectionWeight", "family", "visual"]) ||
      typeof item.id !== "string" ||
      !/^[a-z0-9-]{1,64}$/.test(item.id) ||
      typeof item.tier !== "string" ||
      !["COMMON", "UNCOMMON", "RARE", "EXCEPTIONAL", "SINGULAR"].includes(item.tier) ||
      !isInteger(item.selectionWeight, 1, 1_000_000) ||
      typeof item.family !== "string" ||
      !FOSSIL_FAMILIES.has(item.family) ||
      !isVisual(item.visual) ||
      ids.has(item.id)
    ) {
      return false;
    }
    ids.add(item.id);
    totalWeight += item.selectionWeight as number;
    if (totalWeight > 1_000_000) return false;
  }
  return true;
}

function isNativeAction(value: string): value is NativeAction {
  return ([...NO_PAYLOAD_ACTIONS, ...PAYLOAD_ACTIONS] as readonly string[]).includes(value);
}

function validSpecimenId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value);
}

function validExhibitId(value: unknown): value is string {
  return typeof value === "string" && /^[a-z0-9-]{1,80}$/.test(value);
}

function validExhibitImagePath(value: unknown): value is string {
  return (
    typeof value === "string" && /^\/exhibits\/[a-z0-9-]{1,128}-[a-f0-9]{12}\.webp$/.test(value)
  );
}

function isWorld(value: unknown): value is World {
  return (
    typeof value === "string" &&
    ["PRIMEVAL_STRATA", "DEEP_SPACE", "BOTANICAL_ARCHIVE", "THE_ABYSS"].includes(value)
  );
}

function validBoundedText(value: unknown, minimum: number, maximum: number): value is string {
  return (
    typeof value === "string" &&
    value === value.trim() &&
    value.length >= minimum &&
    value.length <= maximum &&
    !hasControlCharacter(value)
  );
}

function hasControlCharacter(value: string): boolean {
  return Array.from(value).some((character) => {
    const code = character.charCodeAt(0);
    return code < 32 || code === 127;
  });
}

function validOptionalText(value: unknown, maximum: number): value is string {
  return value === "" || validBoundedText(value, 1, maximum);
}

function isUuid(value: unknown): value is string {
  return (
    typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)
  );
}

function isIsoTimestamp(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length >= 20 &&
    value.length <= 40 &&
    Number.isFinite(Date.parse(value))
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isValidId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
}

function isInteger(value: unknown, minimum: number, maximum = Number.MAX_SAFE_INTEGER): boolean {
  return (
    Number.isSafeInteger(value) && (value as number) >= minimum && (value as number) <= maximum
  );
}
