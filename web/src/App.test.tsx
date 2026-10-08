import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App, { registerShellWorker } from "./App";
import type { ShellState } from "./bridge";

const native = {
  postMessage: vi.fn(),
  onmessage: null as ((event: MessageEvent<string>) => void) | null,
};

beforeEach(() => {
  cleanup();
  window.location.hash = "";
  Object.defineProperty(window, "AfterchimeBridge", { configurable: true, value: native });
  native.postMessage.mockClear();
  native.onmessage = null;
});

afterEach(() => cleanup());

describe("service worker registration", () => {
  it("registers only when the hosted production shell asks for it", async () => {
    const register = vi.fn().mockResolvedValue(undefined);
    await registerShellWorker(false, { register });
    expect(register).not.toHaveBeenCalled();
    await registerShellWorker(true, { register });
    expect(register).toHaveBeenCalledWith("/service-worker.js", { scope: "/" });
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

  it("makes Today the first full-shell tab and explains next-day readiness", () => {
    renderWithState(baseState);
    expect(screen.getByRole("heading", { name: "Today is still forming" })).toBeTruthy();
    expect(screen.getByText(/ready tomorrow/i)).toBeTruthy();
    expect(screen.getByText(/quiet day can still become/i)).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "Main navigation" })).toBeTruthy();
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

  it("navigates the Museum and controls a specimen through bridge actions", () => {
    renderWithState({
      ...baseState,
      museum: { specimens: [{ ...specimen, revealedAtEpochMillis: 2 }] },
    });
    fireEvent.click(screen.getByRole("button", { name: /Museum$/ }));
    expect(screen.getByRole("heading", { name: "Museum" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Uncommon Ammonite specimen/i }));
    expect(screen.getByRole("heading", { name: "Specimen" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Protect specimen" }));
    expect(lastRequest()).toMatchObject({
      version: 2,
      type: "museum.lock",
      payload: { specimenId: specimen.id, locked: true },
    });
  });

  it("uses an in-shell confirmation for irreversible combining", () => {
    const specimens = ["a", "b", "c"].map((id, index) => ({
      ...specimen,
      id,
      createdAtEpochMillis: index + 1,
      revealedAtEpochMillis: index + 2,
    }));
    renderWithState({ ...baseState, museum: { specimens } });
    fireEvent.click(screen.getByRole("button", { name: /Museum$/ }));
    fireEvent.click(screen.getAllByRole("button", { name: /Uncommon Ammonite specimen/i })[0]);
    fireEvent.click(screen.getByRole("button", { name: "Combine three" }));
    const cards = screen.getAllByRole("button", { name: /Uncommon Ammonite specimen/i });
    fireEvent.click(cards[1]);
    fireEvent.click(cards[2]);
    fireEvent.click(screen.getByRole("button", { name: "Review" }));
    const dialog = screen.getByRole("dialog", { name: /restore these three/i });
    fireEvent.click(within(dialog).getByRole("button", { name: "Restore specimen" }));
    expect(lastRequest()).toMatchObject({
      version: 2,
      type: "museum.combine",
      payload: { specimenIds: expect.arrayContaining(["a", "b", "c"]) },
    });
  });

  it("keeps optional online settings off and sends one explicit preference mutation", () => {
    renderWithState(baseState);
    fireEvent.click(screen.getByRole("button", { name: /More$/ }));
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
});

function renderWithState(state: ShellState) {
  render(<App />);
  sendState(state);
}

function sendState(state: ShellState | { preferences: ShellState["preferences"] }) {
  const message = "type" in state ? state : { ...baseState, ...state };
  act(() => {
    window.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(message) }));
  });
}

function lastRequest(): Record<string, unknown> {
  const raw = native.postMessage.mock.calls.at(-1)?.[0];
  return JSON.parse(raw ?? "null") as Record<string, unknown>;
}

const specimen = {
  id: "specimen_1",
  anchoredLocalDate: "2026-10-06",
  generatorVersion: 1,
  createdAtEpochMillis: 1,
  revealedAtEpochMillis: null,
  isLocked: false,
  collectibleState: "ORDINARY" as const,
  provenanceCount: 1,
  family: "AMMONITE",
  tier: "UNCOMMON" as const,
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
