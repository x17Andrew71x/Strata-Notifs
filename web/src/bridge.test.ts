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
