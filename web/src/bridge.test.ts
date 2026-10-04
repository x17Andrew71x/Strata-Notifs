import { describe, expect, it } from "vitest";
import { parseNativeRequest } from "./bridge";

describe("versioned native bridge requests", () => {
  it("accepts only the two bounded v1 actions", () => {
    expect(parseNativeRequest({ version: 1, id: "req_1", type: "capabilities.get" })).toEqual({
      version: 1,
      id: "req_1",
      type: "capabilities.get",
    });
    expect(
      parseNativeRequest({ version: 1, id: "req_2", type: "notificationAccess.openSettings" }),
    ).toEqual({ version: 1, id: "req_2", type: "notificationAccess.openSettings" });
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
});
