import { afterEach, describe, expect, it, vi } from "vitest";
import {
  installResponseListener,
  MAX_PENDING_NATIVE_REQUESTS,
  NATIVE_REQUEST_TIMEOUT_MS,
  parseNativeRequest,
  type ShellState,
  sendNativeRequest,
} from "./bridge";
import { relicVaultCatalogUpdate } from "./worlds";

afterEach(() => {
  vi.useRealTimers();
  delete window.AfterchimeBridge;
});

function installTestBridge() {
  const messages: string[] = [];
  const bridge = {
    messages,
    postMessage: (message: string) => messages.push(message),
    onmessage: null as ((event: MessageEvent<string>) => void) | null,
  };
  window.AfterchimeBridge = bridge;
  return bridge;
}

function emitBridge(
  bridge: ReturnType<typeof installTestBridge>,
  value: Record<string, unknown>,
): void {
  bridge.onmessage?.(new MessageEvent("message", { data: JSON.stringify(value) }));
}

function emitTrustedWindow(value: Record<string, unknown>): void {
  window.dispatchEvent(
    new MessageEvent("message", {
      data: JSON.stringify(value),
      origin: window.location.origin,
      source: window,
    }),
  );
}

describe("versioned native bridge requests", () => {
  it("accepts only exact bounded v2 actions", () => {
    expect(parseNativeRequest({ version: 2, id: "req_1", type: "capabilities.get" })).toEqual({
      version: 2,
      id: "req_1",
      type: "capabilities.get",
    });
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_2",
        type: "preferences.update",
        payload: { key: "reduceMotionEnabled", value: true },
      }),
    ).toEqual({
      version: 2,
      id: "req_2",
      type: "preferences.update",
      payload: { key: "reduceMotionEnabled", value: true },
    });
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_dig",
        type: "excavation.dig",
        payload: { tileIndex: 24 },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_bad_dig",
        type: "excavation.dig",
        payload: { tileIndex: -1 },
      }),
    ).toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_exhibit_contribution",
        type: "exhibits.contribute",
        payload: {
          exhibitId: "returning-light-2027",
          localDate: "2026-10-26",
          units: 18,
        },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_exhibit_download",
        type: "exhibits.download",
        payload: {
          imagePath: "/exhibits/fall-seasonal-special-2026-4k-eb20514a5829.webp",
        },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_bad_exhibit_download",
        type: "exhibits.download",
        payload: { imagePath: "https://example.invalid/private.webp" },
      }),
    ).toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_catalog",
        type: "catalog.update",
        payload: relicVaultCatalogUpdate(),
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_3",
        type: "museum.combine",
        payload: { specimenIds: ["one", "two", "three"] },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_lock",
        type: "museum.lock",
        payload: { specimenId: "specimen_1", locked: true },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "req_share",
        type: "museum.share",
        payload: { specimenId: "specimen_1" },
      }),
    ).not.toBeNull();
  });

  it("accepts a future append-only catalogue revision without changing the bridge version", () => {
    const baseline = relicVaultCatalogUpdate() as { items: Array<Record<string, unknown>> };
    const future = {
      ...baseline,
      revision: 2,
      items: [...baseline.items, { ...baseline.items[0], id: "relic-future-trace-fossil" }],
    };

    expect(
      parseNativeRequest({
        version: 2,
        id: "req_future_catalog",
        type: "catalog.update",
        payload: future,
      }),
    ).not.toBeNull();
  });

  it("accepts only strict development-feedback requests", () => {
    const selection = {
      kind: "element",
      rect: { x: 10, y: 20, width: 120, height: 44 },
      viewportWidth: 360,
      viewportHeight: 800,
      devId: "excavation-action",
      tag: "button",
      role: "button",
      accessibleLabel: "Excavate tile 1",
      visibleLabel: "Excavate",
      domPath: '[data-dev-id="excavation-action"]',
    };
    expect(
      parseNativeRequest({
        version: 2,
        id: "feedback_submit",
        type: "devFeedback.submit",
        payload: {
          idempotencyKey: "22222222-2222-4222-8222-222222222222",
          title: "Tighten the action",
          description: "Reduce the empty space above it.",
          route: "today",
          shellRevision: "a".repeat(64),
          appVersion: "0.1.10-dev-debug",
          selection,
        },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "feedback_amend",
        type: "devFeedback.amend",
        payload: {
          idempotencyKey: "33333333-3333-4333-8333-333333333333",
          itemId: "11111111-1111-4111-8111-111111111111",
          title: "Tighten the action",
          description: "Move the label lower now.",
          route: "today",
          shellRevision: "a".repeat(64),
          appVersion: "0.1.10-dev-debug",
          selection,
        },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "feedback_bad",
        type: "devFeedback.submit",
        payload: {
          idempotencyKey: "22222222-2222-4222-8222-222222222222",
          title: "Tighten the action",
          description: "Reduce the empty space above it.",
          route: "today",
          shellRevision: "a".repeat(64),
          appVersion: "0.1.10-dev-debug",
          selection,
          arbitraryState: { token: "forbidden" },
        },
      }),
    ).toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "feedback_accept",
        type: "devFeedback.accept",
        payload: { itemId: "11111111-1111-4111-8111-111111111111" },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({
        version: 2,
        id: "feedback_close",
        type: "devFeedback.close",
        payload: { itemId: "11111111-1111-4111-8111-111111111111" },
      }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({ version: 2, id: "feedback_list", type: "devFeedback.items" }),
    ).not.toBeNull();
    expect(
      parseNativeRequest({ version: 2, id: "feedback_refresh", type: "devFeedback.refresh" }),
    ).not.toBeNull();
  });

  it("accepts strict development-feedback state responses and an additive capability", () => {
    const responses: unknown[] = [];
    const bridge = installTestBridge();
    const stop = installResponseListener((response) => responses.push(response));
    const feedbackId = sendNativeRequest("devFeedback.items");
    const capabilityId = sendNativeRequest("capabilities.get");
    expect(feedbackId).not.toBeNull();
    expect(capabilityId).not.toBeNull();
    const feedback = {
      version: 2,
      id: feedbackId as string,
      type: "devFeedback.state",
      operation: "list",
      ok: true,
      error: null,
      item: null,
      items: [
        {
          id: "11111111-1111-4111-8111-111111111111",
          queuePosition: 1,
          revision: 1,
          title: "Tighten the action",
          description: "Reduce the empty space above it.",
          status: "queued",
          note: "Queued in submission order.",
          createdAt: "2026-10-16T00:00:00.000Z",
          updatedAt: "2026-10-16T00:00:00.000Z",
        },
      ],
    };
    emitBridge(bridge, feedback);
    emitBridge(bridge, {
      version: 2,
      id: capabilityId as string,
      type: "capabilities.state",
      bridgeVersion: 2,
      notificationAccess: true,
      appDetailsAction: true,
      catalogUpdates: true,
      exhibitDownloads: true,
      devFeedback: true,
      appVersion: "0.1.10-dev-debug",
    });
    emitBridge(bridge, { ...feedback, leaseId: "forbidden" });
    stop();
    delete window.AfterchimeBridge;

    expect(responses).toHaveLength(2);
    expect(responses[0]).toEqual(feedback);
    expect(responses[1]).toMatchObject({
      devFeedback: true,
      exhibitDownloads: true,
      appVersion: "0.1.10-dev-debug",
    });
  });

  it("accepts failed development-feedback items while rejecting unknown statuses", () => {
    const responses: unknown[] = [];
    const bridge = installTestBridge();
    const stop = installResponseListener((response) => responses.push(response));
    const feedbackId = sendNativeRequest("devFeedback.items");
    expect(feedbackId).not.toBeNull();
    const feedback = {
      version: 2,
      id: feedbackId as string,
      type: "devFeedback.state",
      operation: "list",
      ok: true,
      error: null,
      item: null,
      items: [
        {
          id: "11111111-1111-4111-8111-111111111111",
          queuePosition: 1,
          revision: 1,
          title: "Tighten the action",
          description: "Reduce the empty space above it.",
          status: "failed",
          note: "Provider retries were exhausted.",
          createdAt: "2026-10-16T00:00:00.000Z",
          updatedAt: "2026-10-16T00:00:00.000Z",
        },
      ],
    };
    emitBridge(bridge, feedback);
    emitBridge(bridge, {
      ...feedback,
      id: "feedback_unknown",
      items: [{ ...feedback.items[0], status: "automation_failed" }],
    });
    stop();
    delete window.AfterchimeBridge;

    expect(responses).toEqual([feedback]);
  });

  it.each([
    null,
    [],
    { version: 1, id: "req", type: "state.get" },
    { version: 2, id: "!", type: "state.get" },
    { version: 2, id: "req", type: "notification.read" },
    { version: 2, id: "req", type: "state.get", payload: { private: "text" } },
    {
      version: 2,
      id: "req",
      type: "preferences.update",
      payload: { key: "unknown", value: true },
    },
    {
      version: 2,
      id: "req",
      type: "museum.combine",
      payload: { specimenIds: ["same", "same", "other"] },
    },
  ])("rejects malformed, unsupported, or expanded request %#", (message) => {
    expect(parseNativeRequest(message)).toBeNull();
  });

  it("rejects cross-origin, wrong-source, unknown, mismatched, and duplicate responses", () => {
    const responses: unknown[] = [];
    const bridge = installTestBridge();
    const stop = installResponseListener((response) => responses.push(response));
    const id = sendNativeRequest("preferences.update", {
      key: "reduceMotionEnabled",
      value: true,
    });
    expect(id).not.toBeNull();

    window.dispatchEvent(
      new MessageEvent("message", {
        data: JSON.stringify(state),
        origin: "https://attacker.example",
        source: window,
      }),
    );
    window.dispatchEvent(
      new MessageEvent("message", {
        data: JSON.stringify(state),
        origin: window.location.origin,
        source: null,
      }),
    );
    emitBridge(bridge, {
      version: 2,
      id: id as string,
      type: "action.result",
      action: "museum.combine",
      ok: true,
    });
    emitBridge(bridge, {
      version: 2,
      id: "unknown",
      type: "action.result",
      action: "preferences.update",
      ok: true,
    });
    const accepted = {
      version: 2,
      id: id as string,
      type: "action.result",
      action: "preferences.update",
      ok: true,
    };
    emitBridge(bridge, accepted);
    emitBridge(bridge, accepted);
    stop();
    delete window.AfterchimeBridge;

    expect(responses).toEqual([accepted]);
  });

  it("expires a lost native request and rejects its late response", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-03T00:00:00.000Z"));
    const responses: unknown[] = [];
    const bridge = installTestBridge();
    const stop = installResponseListener((response) => responses.push(response));
    const id = sendNativeRequest("preferences.update", {
      key: "reduceMotionEnabled",
      value: true,
    });
    expect(id).not.toBeNull();

    vi.advanceTimersByTime(NATIVE_REQUEST_TIMEOUT_MS + 1);
    emitBridge(bridge, {
      version: 2,
      id: id as string,
      type: "action.result",
      action: "preferences.update",
      ok: true,
    });

    expect(responses).toEqual([]);
    stop();
    vi.useRealTimers();
  });

  it("coalesces recurring state reads and clears correlation state on teardown", () => {
    const bridge = installTestBridge();
    const stop = installResponseListener(() => undefined);

    const first = sendNativeRequest("state.get");
    const repeated = sendNativeRequest("state.get");
    expect(repeated).toBe(first);
    expect(bridge.messages).toHaveLength(1);

    stop();
    const stopAgain = installResponseListener(() => undefined);
    const afterTeardown = sendNativeRequest("state.get");
    expect(afterTeardown).not.toBe(first);
    expect(bridge.messages).toHaveLength(2);
    stopAgain();
    delete window.AfterchimeBridge;
  });

  it("rejects new requests when the bounded correlation map is full", () => {
    const bridge = installTestBridge();
    const stop = installResponseListener(() => undefined);
    for (let index = 0; index < MAX_PENDING_NATIVE_REQUESTS; index += 1) {
      expect(
        sendNativeRequest("preferences.update", {
          key: "reduceMotionEnabled",
          value: index % 2 === 0,
        }),
      ).not.toBeNull();
    }

    expect(
      sendNativeRequest("preferences.update", {
        key: "reduceMotionEnabled",
        value: true,
      }),
    ).toBeNull();
    expect(bridge.messages).toHaveLength(MAX_PENDING_NATIVE_REQUESTS);
    stop();
    delete window.AfterchimeBridge;
  });

  it("accepts a strict privacy-reduced state and rejects added fields", () => {
    const responses: unknown[] = [];
    const stop = installResponseListener((response) => responses.push(response));
    emitTrustedWindow(state);
    emitTrustedWindow({ ...state, notificationText: "private" });
    stop();

    expect(responses).toEqual([state]);
  });
});

const state: ShellState = {
  version: 2,
  id: "state",
  type: "shell.state",
  notificationAccess: true,
  preferences: {
    onboardingComplete: true,
    onlineFeaturesEnabled: false,
    productAnalyticsEnabled: false,
    notificationAggregateSharingEnabled: false,
    reduceMotionEnabled: false,
    highContrastEnabled: false,
    hapticsEnabled: true,
  },
  today: {
    localDate: "2026-10-07",
    observation: "Active",
    layers: [{ localHour: 10, category: "SOCIAL", sourceColourRgb: 123 }],
    specimen: null,
    revealInFlight: false,
    primaryAction: "NONE",
  },
  museum: { specimens: [] },
  worlds: {
    selected: "PRIMEVAL_STRATA",
    owned: ["PRIMEVAL_STRATA"],
    available: ["PRIMEVAL_STRATA", "DEEP_SPACE", "BOTANICAL_ARCHIVE", "THE_ABYSS"],
    developmentControlsEnabled: true,
  },
};
