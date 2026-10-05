import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App, { registerShellWorker } from "./App";

const native = { postMessage: vi.fn() };

beforeEach(() => {
  cleanup();
  window.localStorage.clear();
  Object.defineProperty(window, "AfterchimeBridge", { configurable: true, value: native });
  native.postMessage.mockClear();
});
afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

describe("service worker registration", () => {
  it("does not register the production worker outside a production build", async () => {
    const register = vi.fn().mockResolvedValue(undefined);

    await registerShellWorker(false, { register });

    expect(register).not.toHaveBeenCalled();
  });

  it("registers the shell worker for production builds", async () => {
    const register = vi.fn().mockResolvedValue(undefined);

    await registerShellWorker(true, { register });

    expect(register).toHaveBeenCalledWith("/service-worker.js", { scope: "/" });
  });
});

describe("onboarding shell", () => {
  it("keeps the approved game copy and notification action", () => {
    render(<App />);
    expect(screen.getByRole("heading", { name: /begin your collection/i })).toBeTruthy();
    expect(
      screen.getByText(/Turn the rhythm of your day into one-of-a-kind specimens/i),
    ).toBeTruthy();
    expect(screen.getByRole("button", { name: /choose apps & start/i })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /open app info/i })).toBeNull();
    expect(
      screen.getByText(/Android only offers one broad notification-access switch/i),
    ).toBeTruthy();
    expect(
      screen.getByText(/fresh installs start with no notification types selected/i),
    ).toBeTruthy();
    expect(screen.getByText(/financial, password, authenticator, and VPN apps/i)).toBeTruthy();
    expect(screen.getByText(/tap .*Allow restricted settings/i)).toBeTruthy();
    expect(document.body.textContent?.toLowerCase()).not.toContain("strata");
  });

  it("requests only capability state and invokes the user-initiated settings action", () => {
    render(<App />);
    expect(native.postMessage).toHaveBeenCalledTimes(1);
    const request = native.postMessage.mock.calls[0]?.[0];
    expect(request).toBeDefined();
    expect(JSON.parse(request ?? "null")).toMatchObject({ version: 1, type: "capabilities.get" });
    act(() => {
      window.dispatchEvent(
        new MessageEvent("message", {
          data: JSON.stringify({
            version: 1,
            id: "capability_response",
            type: "capabilities.state",
            notificationAccess: true,
          }),
        }),
      );
    });
    expect(screen.getByText(/Notification access is on/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /choose included apps/i }));
    const action = native.postMessage.mock.calls[1]?.[0];
    expect(action).toBeDefined();
    expect(JSON.parse(action ?? "null")).toMatchObject({
      version: 1,
      type: "notificationAccess.openSettings",
    });

    expect(screen.queryByRole("button", { name: /open app info/i })).toBeNull();
  });

  it("offers the restricted-setting recovery path while access is off", () => {
    render(<App />);
    act(() => {
      window.dispatchEvent(
        new MessageEvent("message", {
          data: JSON.stringify({
            version: 1,
            id: "capability_response",
            type: "capabilities.state",
            notificationAccess: false,
            appDetailsAction: true,
          }),
        }),
      );
    });

    fireEvent.click(screen.getByRole("button", { name: /open app info/i }));
    const appInfoAction = native.postMessage.mock.calls[1]?.[0];
    expect(appInfoAction).toBeDefined();
    expect(JSON.parse(appInfoAction ?? "null")).toMatchObject({
      version: 1,
      type: "notificationAccess.openAppDetails",
    });
  });

  it("keeps manual recovery guidance for older shells without the app-info action", () => {
    render(<App />);
    act(() => {
      window.dispatchEvent(
        new MessageEvent("message", {
          data: JSON.stringify({
            version: 1,
            id: "legacy_capability_response",
            type: "capabilities.state",
            notificationAccess: false,
          }),
        }),
      );
    });

    expect(screen.getByText(/tap .*Allow restricted settings/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /open app info/i })).toBeNull();
  });

  it("does not pretend a browser has native notification settings", () => {
    Object.defineProperty(window, "AfterchimeBridge", { configurable: true, value: undefined });
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: /choose apps & start/i }));
    expect(screen.getByText(/Open Android Settings/)).toBeTruthy();
  });
});
