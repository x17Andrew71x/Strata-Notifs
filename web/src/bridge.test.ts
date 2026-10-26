import { describe, expect, it } from "vitest";
import { installResponseListener, parseNativeRequest, type ShellState } from "./bridge";
import { relicVaultCatalogUpdate } from "./worlds";

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
    const stop = installResponseListener((response) => responses.push(response));
    const feedback = {
      version: 2,
      id: "feedback_list",
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
    window.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(feedback) }));
    window.dispatchEvent(
      new MessageEvent("message", {
        data: JSON.stringify({
          version: 2,
          id: "caps",
          type: "capabilities.state",
          bridgeVersion: 2,
          notificationAccess: true,
          appDetailsAction: true,
          catalogUpdates: true,
          devFeedback: true,
          appVersion: "0.1.10-dev-debug",
        }),
      }),
    );
    window.dispatchEvent(
      new MessageEvent("message", {
        data: JSON.stringify({ ...feedback, leaseId: "forbidden" }),
      }),
    );
    stop();

    expect(responses).toHaveLength(2);
    expect(responses[0]).toEqual(feedback);
    expect(responses[1]).toMatchObject({ devFeedback: true, appVersion: "0.1.10-dev-debug" });
  });

  it("accepts failed development-feedback items while rejecting unknown statuses", () => {
    const responses: unknown[] = [];
    const stop = installResponseListener((response) => responses.push(response));
    const feedback = {
      version: 2,
      id: "feedback_failed",
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
    window.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(feedback) }));
    window.dispatchEvent(
      new MessageEvent("message", {
        data: JSON.stringify({
          ...feedback,
          id: "feedback_unknown",
          items: [{ ...feedback.items[0], status: "automation_failed" }],
        }),
      }),
    );
    stop();

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

  it("accepts a strict privacy-reduced state and rejects added fields", () => {
    const responses: unknown[] = [];
    const stop = installResponseListener((response) => responses.push(response));
    window.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(state) }));
    window.dispatchEvent(
      new MessageEvent("message", {
        data: JSON.stringify({ ...state, notificationText: "private" }),
      }),
    );
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
