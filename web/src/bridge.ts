export const BRIDGE_VERSION = 2 as const;

const NO_PAYLOAD_ACTIONS = [
  "capabilities.get",
  "state.get",
  "notificationAccess.openSettings",
  "notificationAccess.openAppDetails",
  "onboarding.complete",
  "formation.reveal",
  "worlds.reset",
] as const;
const PAYLOAD_ACTIONS = [
  "catalog.update",
  "preferences.update",
  "museum.lock",
  "museum.share",
  "museum.combine",
  "excavation.dig",
  "worlds.select",
  "worlds.own",
] as const;
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

export type NativeAction = (typeof NO_PAYLOAD_ACTIONS)[number] | (typeof PAYLOAD_ACTIONS)[number];
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
    }
  | {
      version: 2;
      id: string;
      type: "action.result";
      action: NativeAction;
      ok: boolean;
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
      (hasExactKeys(value, legacyKeys) || hasExactKeys(value, catalogKeys)) &&
      value.bridgeVersion === BRIDGE_VERSION &&
      typeof value.notificationAccess === "boolean" &&
      typeof value.appDetailsAction === "boolean" &&
      (value.catalogUpdates === undefined || typeof value.catalogUpdates === "boolean")
    ) {
      return value as NativeResponse;
    }
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

function isShellState(value: Record<string, unknown>): value is ShellState {
  if (
    value.type !== "shell.state" ||
    !hasExactKeys(value, [
      "version",
      "id",
      "type",
      "notificationAccess",
      "preferences",
      "today",
      "museum",
      "worlds",
    ]) ||
    typeof value.notificationAccess !== "boolean" ||
    !isPreferences(value.preferences) ||
    !isToday(value.today) ||
    !isRecord(value.museum) ||
    !hasExactKeys(value.museum, ["specimens"]) ||
    !Array.isArray(value.museum.specimens) ||
    !value.museum.specimens.every(isSpecimen) ||
    !isWorldState(value.worlds)
  ) {
    return false;
  }
  return true;
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
  if (type === "worlds.select" || type === "worlds.own") {
    return hasExactKeys(payload, ["world"]) && isWorld(payload.world);
  }
  return false;
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

function isWorld(value: unknown): value is World {
  return (
    typeof value === "string" &&
    ["PRIMEVAL_STRATA", "DEEP_SPACE", "BOTANICAL_ARCHIVE", "THE_ABYSS"].includes(value)
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
