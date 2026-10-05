import { describe, expect, it } from "vitest";
import { installResponseListener, parseNativeRequest } from "./bridge";

describe("versioned native bridge requests", () => {
  it("accepts only the bounded v1 actions", () => {
    expect(parseNativeRequest({ version: 1, id: "req_1", type: "capabilities.get" })).toEqual({
      version: 1,
      id: "req_1",
      type: "capabilities.get",
    });
    expect(
      parseNativeRequest({ version: 1, id: "req_2", type: "notificationAccess.openSettings" }),
    ).toEqual({ version: 1, id: "req_2", type: "notificationAccess.openSettings" });
    expect(
      parseNativeRequest({ version: 1, id: "req_3", type: "notificationAccess.openAppDetails" }),
    ).toEqual({ version: 1, id: "req_3", type: "notificationAccess.openAppDetails" });
  });

  it.each([
    null,
    [],
    { version: 2, id: "req", type: "capabilities.get" },
    { version: 1, id: "!", type: "capabilities.get" },
    { version: 1, id: "req", type: "notification.read" },
    { version: 1, id: "req", type: "capabilities.get", body: "private text" },
  ])("rejects malformed, unsupported, or sensitive-shaped message %#", (message) => {
    expect(parseNativeRequest(message)).toBeNull();
  });

  it("accepts current and previous v1 capability responses without accepting extra fields", () => {
    const responses: unknown[] = [];
    const stop = installResponseListener((response) => responses.push(response));
    for (const data of [
      { version: 1, id: "old", type: "capabilities.state", notificationAccess: false },
      {
        version: 1,
        id: "current",
        type: "capabilities.state",
        notificationAccess: false,
        appDetailsAction: true,
      },
      {
        version: 1,
        id: "unsafe",
        type: "capabilities.state",
        notificationAccess: false,
        body: "private text",
      },
    ]) {
      window.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(data) }));
    }
    stop();

    expect(responses).toHaveLength(2);
  });
});
