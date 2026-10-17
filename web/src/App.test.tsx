import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App, {
  brandMarkPath,
  catalogAssetsAreCached,
  ENERGY_GAIN_PULSE_MS,
  EXCAVATION_CLEAR_MS,
  IMAGE_RETRY_DELAYS_MS,
  launchSafeDisplayState,
  loadDevelopmentFeedbackRuntime,
  registerShellWorker,
  reloadOnShellWorkerChange,
  SHELL_STATE_REFRESH_MS,
  TOAST_DURATION_MS,
  TOAST_FADE_START_MS,
} from "./App";
import type { ShellState } from "./bridge";
import { RELIC_VAULT_CATALOG_REVISION, WORLD_ASSET_PATHS, WORLD_DEFINITIONS } from "./worlds";

const native = {
  postMessage: vi.fn(),
  onmessage: null as ((event: MessageEvent<string>) => void) | null,
};

function catalogCacheStorage(
  paths: readonly string[] = WORLD_ASSET_PATHS,
  revision = RELIC_VAULT_CATALOG_REVISION,
): CacheStorage {
  const state = {
    catalogRevision: revision,
    assets: paths.map((pathname) => ({ pathname, digest: "a".repeat(64) })),
  };
  return {
    keys: async () => ["afterchime-shell-v29"],
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
  vi.unstubAllGlobals();
});

describe("service worker registration", () => {
  it("maps the selected brand mark onto hosted and bundled-shell origins", () => {
    expect(brandMarkPath("afterchime-api-dev-development.up.railway.app")).toBe(
      "/afterchime-mark.webp",
    );
    expect(brandMarkPath("appassets.androidplatform.net")).toBe("/assets/afterchime-mark.webp");
  });

  it("registers only when the hosted production shell asks for it", async () => {
    const register = vi.fn().mockResolvedValue(undefined);
    await registerShellWorker(false, { register });
    expect(register).not.toHaveBeenCalled();
    await registerShellWorker(true, { register });
    expect(register).toHaveBeenCalledWith("/service-worker.js", { scope: "/" });
  });

  it("reloads once when an existing shell worker is replaced", () => {
    let listener: (() => void) | null = null;
    const reload = vi.fn();
    const registrar = {
      controller: {},
      register: vi.fn().mockResolvedValue(undefined),
      addEventListener: vi.fn((_type: "controllerchange", next: () => void) => {
        listener = next;
      }),
      removeEventListener: vi.fn(),
    };

    const stop = reloadOnShellWorkerChange(registrar, reload);
    expect(listener).toBeTypeOf("function");
    (listener as unknown as () => void)();
    (listener as unknown as () => void)();
    expect(reload).toHaveBeenCalledTimes(1);
    stop();
    expect(registrar.removeEventListener).toHaveBeenCalledWith("controllerchange", listener);

    const firstInstallListener = vi.fn();
    reloadOnShellWorkerChange(
      {
        controller: null,
        register: vi.fn().mockResolvedValue(undefined),
        addEventListener: firstInstallListener,
      },
      reload,
    );
    expect(firstInstallListener).not.toHaveBeenCalled();
  });

  it("authorizes a native catalogue only after the matching complete artwork set is cached", async () => {
    expect(await catalogAssetsAreCached(RELIC_VAULT_CATALOG_REVISION, catalogCacheStorage())).toBe(
      true,
    );
    expect(
      await catalogAssetsAreCached(RELIC_VAULT_CATALOG_REVISION + 1, catalogCacheStorage()),
    ).toBe(false);
    expect(
      await catalogAssetsAreCached(
        RELIC_VAULT_CATALOG_REVISION,
        catalogCacheStorage(WORLD_ASSET_PATHS.slice(1)),
      ),
    ).toBe(false);
  });
});

describe("full shell application", () => {
  it("contains an optional development-tool load failure", async () => {
    const runtime = await loadDevelopmentFeedbackRuntime(async () => {
      throw new Error("development chunk unavailable");
    });

    expect(
      runtime.default({
        route: "today",
        shellRevision: "a".repeat(64),
        appVersion: "0.1.10-dev-debug",
        registerResponseSink: vi.fn(),
      }),
    ).toBeTruthy();
  });

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
    expect(update.payload).toMatchObject({
      schemaVersion: 1,
      revision: RELIC_VAULT_CATALOG_REVISION,
    });
    expect(update.payload.items).toHaveLength(17);
  });

  it("mounts development feedback only after native capability and hosted revision proof", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              shellVersion: 2,
              bridgeVersion: 2,
              revision: "a".repeat(64),
              entry: "/",
            }),
            { status: 200, headers: { "content-type": "application/json" } },
          ),
      ),
    );
    render(<App />);
    sendState(baseState);
    expect(screen.queryByRole("button", { name: "Open development feedback" })).toBeNull();

    act(() => {
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
            devFeedback: true,
            appVersion: "0.1.10-dev-debug",
          }),
        }),
      );
    });

    expect(await screen.findByRole("button", { name: "Open development feedback" })).toBeTruthy();
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

  it("keeps the notification-access state free of the removed subtitle and Start collecting button", () => {
    renderWithState({
      ...baseState,
      notificationAccess: false,
      today: {
        ...baseState.today,
        observation: "AwaitingAccess",
        primaryAction: "ENABLE_ACCESS",
      },
    });
    expect(screen.getByRole("heading", { name: "Begin your collection" })).toBeTruthy();
    expect(
      screen.queryByText(
        "Choose which alerting apps may contribute. Content never enters the collection.",
      ),
    ).toBeNull();
    expect(screen.queryByRole("button", { name: "Start collecting" })).toBeNull();
  });

  it("retains notification-access recovery for a disconnected collector", () => {
    renderWithState({
      ...baseState,
      notificationAccess: false,
      today: {
        ...baseState.today,
        observation: "Disconnected",
        primaryAction: "ENABLE_ACCESS",
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Open notification settings" }));
    expect(lastRequest()).toMatchObject({ type: "notificationAccess.openSettings" });
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
    expect(container.querySelector(".brand-mark")).toHaveAttribute("src", "/afterchime-mark.webp");
    expect(container.querySelector(".brand strong")).toHaveTextContent("Afterchime");
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

  it("renders the actual daily fossil beneath a concise spendable excavation bed", () => {
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
    expect(screen.queryByText("6 energy earned · 3 per tile", { exact: true })).toBeNull();
    expect(screen.queryByText(/captured/i)).toBeNull();
    expect(container.querySelector(".excavation-artifact")).toHaveAttribute(
      "src",
      "/worlds/relic-dactylioceras-ammonite-excavation-50dc0ab0359a.webp",
    );
    const excavationBed = container.querySelector(".excavation-bed");
    expect(excavationBed).toContainElement(container.querySelector(".excavation-grid"));
    expect(excavationBed).not.toContainElement(container.querySelector(".excavation-hud"));
    expect(container.querySelectorAll(".excavation-tile")).toHaveLength(25);
    expect(container.querySelectorAll(".excavation-tile.dug")).toHaveLength(1);
    expect(container.querySelector('[data-tile-index="0"]')).toHaveClass(
      "excavation-tile-corner-top-left",
    );
    expect(container.querySelector('[data-tile-index="4"]')).toHaveClass(
      "excavation-tile-corner-top-right",
    );
    expect(container.querySelector('[data-tile-index="20"]')).toHaveClass(
      "excavation-tile-corner-bottom-left",
    );
    expect(container.querySelector('[data-tile-index="24"]')).toHaveClass(
      "excavation-tile-corner-bottom-right",
    );

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
      "/worlds/relic-dactylioceras-ammonite-excavation-50dc0ab0359a.webp",
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
    expect(container.querySelector(".specimen-reveal figcaption")).toHaveTextContent(
      "Dinosaur Embryo in Egg",
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
    const improve = screen.getByRole("checkbox", { name: /Help improve Afterchime/i });
    const dailyTotals = screen.getByRole("checkbox", { name: /Share daily activity totals/i });
    expect(online).not.toBeChecked();
    expect(improve).toBeDisabled();
    expect(dailyTotals).toBeDisabled();
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

    expect(screen.getByText("Relic Vault · Included for launch", { exact: true })).toBeTruthy();
    for (const detail of [
      /Choose which apps may add layers/i,
      /your collection still works offline/i,
      /which parts of Afterchime are used and basic device details/i,
      /one day’s counts by broad type and hour/i,
      /Turn off excavation and reveal animations/i,
      /Increase separation between text, controls, and surfaces/i,
    ]) {
      expect(screen.getByText(detail)).toBeTruthy();
    }
    expect(
      screen.getAllByText(
        /never includes notification text, app names, senders, contacts, or media/i,
      ),
    ).toHaveLength(1);
    expect(
      screen.getByText(
        "Send which parts of Afterchime are used and basic device details such as app version, Android version, language, and phone or tablet.",
        { exact: true },
      ),
    ).toBeTruthy();
    expect(screen.queryByText(/pseudonymous|aggregate sharing|product analytics/i)).toBeNull();
    expect(screen.queryByRole("checkbox", { name: /Haptics/i })).toBeNull();

    fireEvent.click(screen.getByRole("checkbox", { name: /Reduce motion/i }));
    expect(lastRequest()).toMatchObject({
      version: 2,
      type: "preferences.update",
      payload: { key: "reduceMotionEnabled", value: true },
    });
  });

  it("fails hostile stale Series state closed to the launch Relic Vault experience", () => {
    const hostileState: ShellState = {
      ...baseState,
      museum: { specimens: [{ ...specimen, revealedAtEpochMillis: 2 }] },
      worlds: {
        ...baseState.worlds,
        selected: "THE_ABYSS",
        owned: ["PRIMEVAL_STRATA", "DEEP_SPACE", "BOTANICAL_ARCHIVE", "THE_ABYSS"],
        developmentControlsEnabled: true,
      },
    };
    const projected = launchSafeDisplayState(hostileState);
    expect(projected).not.toBe(hostileState);
    expect(projected.worlds).toEqual({
      ...hostileState.worlds,
      selected: "PRIMEVAL_STRATA",
      owned: ["PRIMEVAL_STRATA"],
      developmentControlsEnabled: false,
    });
    expect(hostileState.worlds).toMatchObject({
      selected: "THE_ABYSS",
      owned: ["PRIMEVAL_STRATA", "DEEP_SPACE", "BOTANICAL_ARCHIVE", "THE_ABYSS"],
      developmentControlsEnabled: true,
    });

    const { container } = renderWithState(hostileState);
    expect(container.querySelector(".app-shell")).toHaveAttribute("data-world", "Relic Vault");
    expect(container.querySelector(".app-shell")).toHaveClass("world-relic");
    expect(container.querySelector(".app-shell")).not.toHaveClass("world-atlas");
    expect(screen.getByText(/new relief is being pressed/i)).toBeTruthy();
    expect(screen.queryByText(/terrain is being fixed in charcoal/i)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /Collection$/ }));
    expect(screen.getByRole("heading", { name: "Vault" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /artifact from Relic Vault/i }));
    expect(screen.getByText("Relic Vault", { selector: ".eyebrow", exact: true })).toBeTruthy();

    act(() => {
      window.location.hash = "#more";
      window.dispatchEvent(new Event("hashchange"));
    });
    expect(screen.getByRole("heading", { name: "Settings" })).toBeTruthy();
    expect(screen.getByText("Relic Vault · Included for launch", { exact: true })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /^Series/i }));

    expect(screen.getByRole("heading", { level: 1, name: "Series" })).toBeTruthy();
    expect(screen.getByText("Two authored series", { exact: true })).toBeTruthy();
    expect(
      screen.getByText("Relic Vault is available now. Signal Ruins is under development.", {
        exact: true,
      }),
    ).toBeTruthy();
    for (const world of WORLD_DEFINITIONS) {
      const heading = screen.getByRole("heading", { name: world.name });
      const card = heading.closest("article") as HTMLElement;
      expect(card).toBeTruthy();
      expect(within(card).getByText(world.eyebrow, { exact: true })).toBeTruthy();
      expect(within(card).getByText(world.description, { exact: true })).toBeTruthy();
      if (world.baseline) {
        expect(card.querySelector(".world-preview .specimen-visual")).toBeTruthy();
      } else {
        const image = card.querySelector<HTMLImageElement>(".world-preview .world-card-image");
        expect(image).toHaveAttribute("src", world.cardImage);
        expect(image).toHaveAttribute("alt", "");
      }
    }
    expect(container.querySelectorAll(".world-card")).toHaveLength(2);
    expect(container.querySelectorAll(".world-card.selected")).toHaveLength(1);
    const baseCard = screen.getByRole("heading", { name: "Relic Vault" }).closest("article");
    expect(baseCard).toHaveClass("selected");
    expect(baseCard).not.toHaveClass("locked");
    expect(baseCard).not.toHaveAttribute("aria-disabled");
    expect(
      within(baseCard as HTMLElement).getByText("Active series", { exact: true }),
    ).toBeTruthy();

    const lockedCards = Array.from(container.querySelectorAll<HTMLElement>(".world-card.locked"));
    expect(lockedCards).toHaveLength(1);
    expect(screen.getByText("Under development", { exact: true })).toBeTruthy();
    expect(screen.queryByText("Locked for launch", { exact: true })).toBeNull();
    for (const card of lockedCards) {
      expect(card).toHaveAttribute("aria-disabled", "true");
      const preview = card.querySelector(".world-preview");
      expect(preview).toBeTruthy();
      expect(preview?.querySelectorAll('.world-lock-overlay[aria-hidden="true"]')).toHaveLength(1);
      expect(preview?.querySelectorAll("svg.world-lock-icon")).toHaveLength(1);
      expect(card.querySelector(".world-status .world-lock-icon")).toBeNull();
      expect(card.querySelector(".world-copy")).toBeTruthy();
      expect(within(card).queryByRole("button")).toBeNull();
    }
    expect(container.querySelectorAll("svg.world-lock-icon")).toHaveLength(1);
    expect(WORLD_ASSET_PATHS).toContain("/worlds/signal-ruins-artifact-67f7d4054fb5.webp");
    for (const removedPrefix of ["/worlds/field-", "/worlds/control-", "/worlds/atlas-"]) {
      expect(WORLD_ASSET_PATHS.some((asset) => asset.startsWith(removedPrefix))).toBe(false);
    }
    for (const removedName of ["Living Field Station", "Expedition Control", "Charcoal Atlas"]) {
      expect(screen.queryByText(removedName, { exact: true })).toBeNull();
    }
    for (const control of ["Apply", "Unlock for testing", "Reset test unlocks"]) {
      expect(screen.queryByRole("button", { name: control })).toBeNull();
    }
    expect(
      requests().some(({ type }) =>
        ["worlds.select", "worlds.own", "worlds.reset"].includes(type as string),
      ),
    ).toBe(false);
    for (const artifact of WORLD_DEFINITIONS.flatMap((world) => world.artifacts)) {
      expect(screen.queryByText(artifact.name, { exact: true })).toBeNull();
    }
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
