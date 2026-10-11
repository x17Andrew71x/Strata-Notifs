import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App, {
  catalogAssetsAreCached,
  ENERGY_GAIN_PULSE_MS,
  EXCAVATION_CLEAR_MS,
  IMAGE_RETRY_DELAYS_MS,
  registerShellWorker,
  SHELL_STATE_REFRESH_MS,
  TOAST_DURATION_MS,
  TOAST_FADE_START_MS,
} from "./App";
import type { ShellState } from "./bridge";
import { WORLD_ASSET_PATHS } from "./worlds";

const native = {
  postMessage: vi.fn(),
  onmessage: null as ((event: MessageEvent<string>) => void) | null,
};

function catalogCacheStorage(
  paths: readonly string[] = WORLD_ASSET_PATHS,
  revision = 1,
): CacheStorage {
  const state = {
    catalogRevision: revision,
    assets: paths.map((pathname) => ({ pathname, digest: "a".repeat(64) })),
  };
  return {
    keys: async () => ["afterchime-shell-v24"],
    open: async () => ({
      match: async () =>
        new Response(JSON.stringify(state), { headers: { "content-type": "application/json" } }),
    }),
  } as unknown as CacheStorage;
}

beforeEach(() => {
  cleanup();
  window.location.hash = "";
  Object.defineProperty(window, "AfterchimeBridge", { configurable: true, value: native });
  Object.defineProperty(globalThis, "caches", {
    configurable: true,
    value: catalogCacheStorage(),
  });
  native.postMessage.mockClear();
  native.onmessage = null;
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("service worker registration", () => {
  it("registers only when the hosted production shell asks for it", async () => {
    const register = vi.fn().mockResolvedValue(undefined);
    await registerShellWorker(false, { register });
    expect(register).not.toHaveBeenCalled();
    await registerShellWorker(true, { register });
    expect(register).toHaveBeenCalledWith("/service-worker.js", { scope: "/" });
  });

  it("authorizes a native catalogue only after the matching complete artwork set is cached", async () => {
    expect(await catalogAssetsAreCached(1, catalogCacheStorage())).toBe(true);
    expect(await catalogAssetsAreCached(2, catalogCacheStorage())).toBe(false);
    expect(await catalogAssetsAreCached(1, catalogCacheStorage(WORLD_ASSET_PATHS.slice(1)))).toBe(
      false,
    );
  });
});

describe("full shell application", () => {
  it("receives replies on the injected WebView bridge channel", () => {
    render(<App />);
    expect(native.onmessage).toBeTypeOf("function");
    act(() => {
      native.onmessage?.(new MessageEvent("message", { data: JSON.stringify(baseState) }));
    });
    expect(screen.getByRole("heading", { name: "Today is still forming" })).toBeTruthy();
  });

  it("offers the cached hosted catalogue to capable native shells without user-visible prompting", async () => {
    render(<App />);
    await act(async () => {
      native.onmessage?.(
        new MessageEvent("message", {
          data: JSON.stringify({
            version: 2,
            id: "capabilities",
            type: "capabilities.state",
            bridgeVersion: 2,
            notificationAccess: true,
            appDetailsAction: true,
            catalogUpdates: true,
          }),
        }),
      );
      await Promise.resolve();
    });

    await waitFor(() =>
      expect(requests().some((request) => request.type === "catalog.update")).toBe(true),
    );
    const update = requests().find((request) => request.type === "catalog.update") as {
      type: string;
      payload: { schemaVersion: number; revision: number; items: unknown[] };
    };
    expect(update.type).toBe("catalog.update");
    expect(update.payload).toMatchObject({ schemaVersion: 1, revision: 1 });
    expect(update.payload.items).toHaveLength(17);
  });

  it("requests bridge state and renders private onboarding from native state", () => {
    render(<App />);
    expect(native.postMessage).toHaveBeenCalledTimes(2);
    expect(native.postMessage.mock.calls.map((call) => JSON.parse(call[0]).type)).toEqual([
      "capabilities.get",
      "state.get",
    ]);

    sendState({ preferences: { ...baseState.preferences, onboardingComplete: false } });
    expect(screen.getByRole("heading", { name: /a quiet collection, kept here/i })).toBeTruthy();
    expect(
      screen.getByText(
        /message text, titles, senders, contacts, actions, and media are never stored/i,
      ),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(lastRequest()).toMatchObject({ version: 2, type: "onboarding.complete" });
  });

  it("makes Excavate the first full-shell tab and explains next-day readiness", () => {
    renderWithState(baseState);
    expect(screen.getByRole("heading", { name: "Today is still forming" })).toBeTruthy();
    expect(screen.getByText(/ready tomorrow/i)).toBeTruthy();
    expect(screen.getByText(/new relief is being pressed/i)).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "Main navigation" })).toBeTruthy();
  });

  it("uses task-based bottom navigation labels with authored icons", () => {
    const { container } = renderWithState(baseState);
    for (const label of ["Excavate", "Collection", "Exhibits", "Settings"]) {
      const button = screen.getByRole("button", { name: label });
      expect(button.querySelector("svg")).toBeTruthy();
    }
    for (const oldLabel of ["Today", "Museum", "Community", "More"]) {
      expect(screen.queryByRole("button", { name: oldLabel })).toBeNull();
    }
    expect(container.querySelectorAll(".bottom-nav-icon")).toHaveLength(4);

    fireEvent.click(screen.getByRole("button", { name: "Settings" }));
    expect(screen.getByRole("heading", { name: "Settings" })).toBeTruthy();
  });

  it("shows only active fossil rarities in the Collection filter", () => {
    renderWithState(baseState);
    fireEvent.click(screen.getByRole("button", { name: "Collection" }));
    const filters = screen.getByRole("group", { name: "Filter by rarity" });
    expect(
      within(filters)
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["All", "Common", "Uncommon", "Rare"]);
    expect(within(filters).queryByRole("button", { name: "Exceptional" })).toBeNull();
    expect(within(filters).queryByRole("button", { name: "Singular" })).toBeNull();
  });

  it("keeps the notification-access state free of the removed subtitle", () => {
    renderWithState({
      ...baseState,
      notificationAccess: false,
      today: { ...baseState.today, observation: "AwaitingAccess" },
    });
    expect(screen.getByRole("heading", { name: "Begin your collection" })).toBeTruthy();
    expect(
      screen.queryByText(
        "Choose which alerting apps may contribute. Content never enters the collection.",
      ),
    ).toBeNull();
  });

  it("builds the primary formation from bounded sediment layers and a fossil imprint", () => {
    const layers = Array.from({ length: 22 }, (_, index) => ({
      localHour: index,
      category: `CATEGORY_${String.fromCharCode(65 + index)}`,
      sourceColourRgb: 0x5f4528 + index,
    }));
    const { container } = render(<App />);
    sendState({ ...baseState, today: { ...baseState.today, layers } });

    expect(container.querySelector(".app-shell")).toHaveClass("route-today", "world-relic");
    expect(container.querySelector(".brand-mark-fossil")).toBeTruthy();
    expect(
      screen.getByRole("img", {
        name: "Today's notifications settling into a forming specimen",
      }),
    ).toHaveClass("formation-relic");
    const sediment = container.querySelectorAll(".formation-layer");
    expect(sediment).toHaveLength(18);
    expect(sediment[0]).toHaveAttribute("data-category", "CATEGORY_E");
    expect(sediment[17]).toHaveClass("is-newest");
    expect(sediment[17]).toHaveAttribute("data-newest", "true");
    expect(sediment[16]).not.toHaveClass("is-newest");
    expect(container.querySelector(".imprint-relic")).toBeTruthy();
  });

  it("adds exactly one new sediment course for each incoming notification", () => {
    const layers = Array.from({ length: 3 }, (_, index) => ({
      localHour: index,
      category: `CATEGORY_${String.fromCharCode(65 + index)}`,
      sourceColourRgb: 0x5f4528 + index,
    }));
    const { container } = render(<App />);
    sendState({ ...baseState, today: { ...baseState.today, layers } });
    const previousNewest = container.querySelectorAll(".formation-layer")[2];

    sendState({
      ...baseState,
      today: {
        ...baseState.today,
        layers: [...layers, { localHour: 4, category: "CATEGORY_NEW", sourceColourRgb: 0x9b7241 }],
      },
    });

    const sediment = container.querySelectorAll(".formation-layer");
    expect(sediment).toHaveLength(4);
    expect(sediment[2]).toBe(previousNewest);
    expect(sediment[2]).not.toHaveClass("is-newest");
    expect(sediment[3]).toHaveClass("is-newest");
    expect(sediment[3]).toHaveAttribute("data-category", "CATEGORY_NEW");
  });

  it("renders the actual daily fossil beneath a spendable excavation bed", () => {
    const { container } = render(<App />);
    sendState({
      ...baseState,
      today: {
        ...baseState.today,
        excavation: {
          artifactId: "relic-dactylioceras-ammonite",
          capturedNotificationCount: 8,
          eligibleNotificationCount: 6,
          energyPerNotification: 1,
          energyEarned: 6,
          energySpent: 3,
          energyAvailable: 3,
          tileEnergyCost: 3,
          gridColumns: 5,
          gridRows: 5,
          dugTiles: [12],
          completedAtEpochMillis: null,
        },
        digInFlight: false,
      },
    });

    expect(screen.getByRole("heading", { name: "Excavate today’s fossil" })).toBeTruthy();
    expect(screen.queryByText(/notifications captured;.*counted after pacing/i)).toBeNull();
    expect(screen.getByText(/8 captured · 6 energy earned · 3 per tile/i)).toBeTruthy();
    expect(container.querySelector(".excavation-artifact")).toHaveAttribute(
      "src",
      "/worlds/relic-dactylioceras-ammonite-excavation-e191e90964c1.webp",
    );
    expect(container.querySelectorAll(".excavation-tile")).toHaveLength(25);
    expect(container.querySelectorAll(".excavation-tile.dug")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "Excavate tile 1 for 3 energy" }));
    expect(lastRequest()).toMatchObject({
      version: 2,
      type: "excavation.dig",
      payload: { tileIndex: 0 },
    });
  });

  it("retries a failed first-load excavation image without showing a broken image", () => {
    vi.useFakeTimers();
    const { container } = renderWithState(excavationState());
    const firstImage = container.querySelector<HTMLImageElement>(".excavation-artifact");
    expect(firstImage).not.toBeNull();
    fireEvent.error(firstImage as HTMLImageElement);
    expect(firstImage).not.toHaveClass("is-loaded");

    act(() => vi.advanceTimersByTime(IMAGE_RETRY_DELAYS_MS[0]));
    const retryImage = container.querySelector<HTMLImageElement>(".excavation-artifact");
    expect(retryImage).not.toBe(firstImage);
    expect(retryImage).toHaveAttribute(
      "src",
      "/worlds/relic-dactylioceras-ammonite-excavation-e191e90964c1.webp",
    );
    fireEvent.load(retryImage as HTMLImageElement);
    expect(retryImage).toHaveClass("is-loaded");
  });

  it("brushes away only a newly cleared excavation tile", () => {
    vi.useFakeTimers();
    const { container } = renderWithState(excavationState());
    expect(container.querySelector('[data-tile-index="12"]')).not.toHaveClass("is-clearing");

    sendState(
      excavationState({
        energySpent: 6,
        energyAvailable: 0,
        dugTiles: [12, 13],
      }),
    );
    const clearedTile = container.querySelector('[data-tile-index="13"]');
    expect(clearedTile).toHaveClass("dug", "is-clearing");

    act(() => vi.advanceTimersByTime(EXCAVATION_CLEAR_MS));
    expect(clearedTile).toHaveClass("dug");
    expect(clearedTile).not.toHaveClass("is-clearing");
  });

  it("updates dig energy from a native push and requests a visible-state fallback refresh", () => {
    vi.useFakeTimers();
    renderWithState(excavationState());
    expect(screen.getByText("3", { selector: ".excavation-hud strong" })).toBeTruthy();

    sendState(
      excavationState({
        capturedNotificationCount: 9,
        eligibleNotificationCount: 7,
        energyEarned: 7,
        energyAvailable: 4,
      }),
    );
    const gainedEnergy = screen.getByText("4", { selector: ".excavation-hud strong" });
    expect(gainedEnergy).toHaveClass("energy-gained");

    sendState(excavationState({ energySpent: 6, energyAvailable: 1 }));
    expect(screen.getByText("1", { selector: ".excavation-hud strong" })).not.toHaveClass(
      "energy-gained",
    );
    sendState(excavationState({ energySpent: 6, energyAvailable: 2 }));
    const secondGain = screen.getByText("2", { selector: ".excavation-hud strong" });
    expect(secondGain).toHaveClass("energy-gained");

    act(() => vi.advanceTimersByTime(ENERGY_GAIN_PULSE_MS));
    expect(secondGain).not.toHaveClass("energy-gained");

    native.postMessage.mockClear();
    act(() => vi.advanceTimersByTime(SHELL_STATE_REFRESH_MS));
    expect(lastRequest()).toMatchObject({ version: 2, type: "state.get" });
  });

  it("moves a completed catalog fossil to its matching rarity-treated Museum image", () => {
    const { container } = renderWithState({
      ...baseState,
      today: {
        ...baseState.today,
        specimen: {
          ...specimen,
          catalogItemId: "relic-dinosaur-embryo-egg",
          tier: "RARE",
          revealedAtEpochMillis: 25,
        },
        excavation: {
          artifactId: "relic-dinosaur-embryo-egg",
          capturedNotificationCount: 80,
          eligibleNotificationCount: 75,
          energyPerNotification: 1,
          energyEarned: 75,
          energySpent: 75,
          energyAvailable: 0,
          tileEnergyCost: 3,
          gridColumns: 5,
          gridRows: 5,
          dugTiles: Array.from({ length: 25 }, (_, index) => index),
          completedAtEpochMillis: 25,
        },
        digInFlight: false,
      },
      museum: {
        specimens: [
          {
            ...specimen,
            catalogItemId: "relic-dinosaur-embryo-egg",
            tier: "RARE",
            revealedAtEpochMillis: 25,
          },
        ],
      },
    });

    expect(screen.getByRole("heading", { name: "Today’s fossil is secured" })).toBeTruthy();
    expect(container.querySelector(".specimen-visual")).toHaveClass(
      "rarity-rare",
      "specimen-reveal",
    );
    expect(container.querySelector(".specimen-visual img")).toHaveAttribute(
      "src",
      "/worlds/relic-dinosaur-embryo-egg-museum-db0ceecfe047.webp",
    );
  });

  it("surfaces a prior-day specimen and sends the bounded reveal action", () => {
    renderWithState({
      ...baseState,
      today: {
        ...baseState.today,
        primaryAction: "REVEAL",
        specimen: specimen,
      },
    });
    expect(screen.getByRole("heading", { name: "Yesterday’s specimen is ready" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Reveal specimen" }));
    expect(lastRequest()).toMatchObject({ version: 2, type: "formation.reveal" });
  });

  it("keeps one fossil name and only rarity and sealed facts on the Museum detail", () => {
    const { container } = renderWithState({
      ...baseState,
      museum: {
        specimens: [{ ...specimen, revealedAtEpochMillis: 2, isLocked: true }],
      },
    });
    fireEvent.click(screen.getByRole("button", { name: /Collection$/ }));
    expect(screen.getByRole("heading", { name: "Vault" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Ribbed Jurassic Ammonite/i }));

    expect(screen.getByRole("heading", { name: "Ribbed Jurassic Ammonite" })).toBeTruthy();
    expect(screen.getAllByText("Ribbed Jurassic Ammonite", { exact: true })).toHaveLength(1);
    const detail = container.querySelector(".detail-card");
    expect(detail).toBeTruthy();
    expect(detail?.querySelector(".specimen-visual figcaption")).toBeNull();
    expect(
      Array.from(detail?.querySelectorAll("dt") ?? []).map((term) => term.textContent),
    ).toEqual(["Rarity", "Sealed"]);
    expect(screen.queryByRole("button", { name: "Share image" })).toBeNull();
    expect(screen.queryByRole("button", { name: /protection/i })).toBeNull();
    expect(screen.queryByRole("button", { name: "Combine three" })).toBeNull();
  });

  it("maps Museum card borders to bronze, silver, and gold rarity treatments", () => {
    const specimens = [
      ["common", "relic-dactylioceras-ammonite"],
      ["uncommon", "relic-articulated-trilobite"],
      ["rare", "relic-dinosaur-embryo-egg"],
    ].map(([id, catalogItemId], index) => ({
      ...specimen,
      id,
      catalogItemId,
      createdAtEpochMillis: index + 1,
      revealedAtEpochMillis: index + 2,
    }));
    renderWithState({ ...baseState, museum: { specimens } });
    fireEvent.click(screen.getByRole("button", { name: /Collection$/ }));

    expect(screen.getByRole("button", { name: /Ribbed Jurassic Ammonite/i })).toHaveClass(
      "rarity-common",
    );
    expect(screen.getByRole("button", { name: /Articulated Trilobite/i })).toHaveClass(
      "rarity-uncommon",
    );
    expect(screen.getByRole("button", { name: /Dinosaur Embryo in Egg/i })).toHaveClass(
      "rarity-rare",
    );
  });

  it("stacks matching Museum artifacts and shows the owned count with one canonical rarity", () => {
    const duplicates = ["old-common", "new-uncommon", "third-copy"].map((id, index) => ({
      ...specimen,
      id,
      catalogItemId: "relic-dactylioceras-ammonite",
      tier: index === 0 ? ("COMMON" as const) : ("UNCOMMON" as const),
      createdAtEpochMillis: index + 1,
      revealedAtEpochMillis: index + 2,
    }));
    renderWithState({ ...baseState, museum: { specimens: duplicates } });
    fireEvent.click(screen.getByRole("button", { name: /Collection$/ }));

    const cards = screen.getAllByRole("button", { name: /artifact from Relic Vault/i });
    expect(cards).toHaveLength(1);
    expect(within(cards[0]).getByText("3 owned")).toBeTruthy();
    expect(within(cards[0]).getByText("Common")).toBeTruthy();
    expect(within(cards[0]).queryByText("Uncommon")).toBeNull();
  });

  it("unlocks fossil reference information from a three-specimen stack", () => {
    const duplicates = ["a", "b", "c"].map((id, index) => ({
      ...specimen,
      id,
      catalogItemId: "relic-dactylioceras-ammonite",
      createdAtEpochMillis: index + 1,
      revealedAtEpochMillis: index + 2,
    }));
    renderWithState({ ...baseState, museum: { specimens: duplicates } });
    fireEvent.click(screen.getByRole("button", { name: /Collection$/ }));
    fireEvent.click(screen.getByRole("button", { name: /artifact from Relic Vault/i }));

    expect(screen.getByRole("heading", { name: "Reference series" })).toBeTruthy();
    expect(screen.getByText("3 owned")).toBeTruthy();
    expect(screen.getByText(/extinct coiled shell form/i)).toBeTruthy();
    expect(screen.getByText("6 specimens")).toBeTruthy();
    expect(screen.getByText("10 specimens")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Combine three" })).toBeNull();
  });

  it("unlocks the complete reference series without consuming ten duplicates", () => {
    const duplicates = Array.from({ length: 10 }, (_, index) => ({
      ...specimen,
      id: `ammonite-${index}`,
      catalogItemId: "relic-dactylioceras-ammonite",
      createdAtEpochMillis: index + 1,
      revealedAtEpochMillis: index + 2,
    }));
    renderWithState({ ...baseState, museum: { specimens: duplicates } });
    fireEvent.click(screen.getByRole("button", { name: /Collection$/ }));
    fireEvent.click(screen.getByRole("button", { name: /artifact from Relic Vault/i }));

    expect(screen.getByText("10 owned")).toBeTruthy();
    expect(screen.getByText(/extinct coiled shell form/i)).toBeTruthy();
    expect(screen.getByText(/occur worldwide/i)).toBeTruthy();
    expect(screen.getByText(/durable shell only/i)).toBeTruthy();
    expect(screen.queryByText("Locked")).toBeNull();
    expect(requests().some(({ type }) => type === "museum.combine")).toBe(false);
  });

  it("keeps optional online settings off and sends one explicit preference mutation", () => {
    renderWithState(baseState);
    fireEvent.click(screen.getByRole("button", { name: /Settings$/ }));
    const online = screen.getByRole("checkbox", { name: /Online features/i });
    const analytics = screen.getByRole("checkbox", { name: /Product analytics/i });
    expect(online).not.toBeChecked();
    expect(analytics).toBeDisabled();
    fireEvent.click(online);
    expect(lastRequest()).toMatchObject({
      version: 2,
      type: "preferences.update",
      payload: { key: "onlineFeaturesEnabled", value: true },
    });
  });

  it("explains what every Settings control does", () => {
    renderWithState(baseState);
    fireEvent.click(screen.getByRole("button", { name: /Settings$/ }));

    for (const detail of [
      /Change the collection’s look and language/i,
      /Choose which apps may add layers/i,
      /your collection still works offline/i,
      /pseudonymous app-use events/i,
      /daily counts only—never notification content or app identity/i,
      /Turn off excavation and reveal animations/i,
      /Increase separation between text, controls, and surfaces/i,
    ]) {
      expect(screen.getByText(detail)).toBeTruthy();
    }
    expect(screen.queryByRole("checkbox", { name: /Haptics/i })).toBeNull();

    fireEvent.click(screen.getByRole("checkbox", { name: /Reduce motion/i }));
    expect(lastRequest()).toMatchObject({
      version: 2,
      type: "preferences.update",
      payload: { key: "reduceMotionEnabled", value: true },
    });
  });

  it("shows only the four approved public worlds and keeps compatibility ids out of the UI", () => {
    renderWithState(baseState);
    fireEvent.click(screen.getByRole("button", { name: /Settings$/ }));
    fireEvent.click(screen.getByRole("button", { name: /Worlds/i }));

    for (const name of [
      "Relic Vault",
      "Living Field Station",
      "Expedition Control",
      "Charcoal Atlas",
    ]) {
      expect(screen.getByRole("heading", { name })).toBeTruthy();
    }
    expect(
      screen.queryByText(/Primeval Strata|Deep Space|Botanical Archive|The Abyss/i),
    ).toBeNull();
    expect(screen.getAllByRole("button", { name: "Unlock for testing" })).toHaveLength(3);
    expect(screen.getByText("Ribbed Jurassic Ammonite")).toBeTruthy();
    expect(screen.getByText("Tidal Archive")).toBeTruthy();
    expect(screen.getByText("Pelagic Channel")).toBeTruthy();
    expect(screen.getByText("Hollow Range")).toBeTruthy();

    fireEvent.click(screen.getAllByRole("button", { name: "Unlock for testing" })[0]);
    expect(lastRequest()).toMatchObject({
      version: 2,
      type: "worlds.own",
      payload: { world: "BOTANICAL_ARCHIVE" },
    });
  });

  it("applies the selected authored world to page language and material theme", () => {
    const { container } = render(<App />);
    sendState({
      ...baseState,
      worlds: {
        ...baseState.worlds,
        selected: "THE_ABYSS",
        owned: ["PRIMEVAL_STRATA", "THE_ABYSS"],
      },
    });
    expect(container.querySelector(".app-shell")).toHaveAttribute("data-world", "Charcoal Atlas");
    expect(container.querySelector(".app-shell")).toHaveClass("world-atlas");
    expect(screen.getByText(/terrain is being fixed in charcoal/i)).toBeTruthy();
  });

  it("dismisses action feedback after the visible toast period", () => {
    vi.useFakeTimers();
    renderWithState(baseState);
    act(() => {
      window.dispatchEvent(
        new MessageEvent("message", {
          data: JSON.stringify({
            version: 2,
            id: "save_result",
            type: "action.result",
            action: "preferences.update",
            ok: true,
          }),
        }),
      );
    });
    expect(screen.getByRole("status")).toHaveTextContent("Saved on this device.");

    act(() => vi.advanceTimersByTime(TOAST_FADE_START_MS - 1));
    expect(screen.getByRole("status")).not.toHaveClass("exiting");
    act(() => vi.advanceTimersByTime(1));
    expect(screen.getByRole("status")).toHaveClass("exiting");
    act(() => vi.advanceTimersByTime(TOAST_DURATION_MS - TOAST_FADE_START_MS));
    expect(screen.queryByRole("status")).toBeNull();
  });
});

function renderWithState(state: ShellState) {
  const rendered = render(<App />);
  sendState(state);
  return rendered;
}

function sendState(state: ShellState | { preferences: ShellState["preferences"] }) {
  const message = "type" in state ? state : { ...baseState, ...state };
  act(() => {
    window.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(message) }));
  });
}

function requests(): Array<Record<string, unknown>> {
  return native.postMessage.mock.calls.map(
    ([raw]) => JSON.parse(raw as string) as Record<string, unknown>,
  );
}

function lastRequest(): Record<string, unknown> {
  return requests().at(-1) ?? {};
}

function excavationState(
  excavation: Partial<NonNullable<ShellState["today"]["excavation"]>> = {},
): ShellState {
  return {
    ...baseState,
    today: {
      ...baseState.today,
      excavation: {
        artifactId: "relic-dactylioceras-ammonite",
        capturedNotificationCount: 8,
        eligibleNotificationCount: 6,
        energyPerNotification: 1,
        energyEarned: 6,
        energySpent: 3,
        energyAvailable: 3,
        tileEnergyCost: 3,
        gridColumns: 5,
        gridRows: 5,
        dugTiles: [12],
        completedAtEpochMillis: null,
        ...excavation,
      },
      digInFlight: false,
    },
  };
}

const specimen = {
  id: "specimen_1",
  catalogItemId: "relic-dactylioceras-ammonite",
  anchoredLocalDate: "2026-10-06",
  generatorVersion: 1,
  createdAtEpochMillis: 1,
  revealedAtEpochMillis: null,
  isLocked: false,
  collectibleState: "ORDINARY" as const,
  provenanceCount: 1,
  family: "AMMONITE",
  tier: "COMMON" as const,
  visual: {
    hueDegrees: 42,
    strataCount: 7,
    inclusionDensityPercent: 30,
    reliefPercent: 45,
    rotationDegrees: 14,
  },
};

const baseState: ShellState = {
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
    layers: [{ localHour: 11, category: "SOCIAL", sourceColourRgb: 0x668877 }],
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
