export const BRIDGE_VERSION = 1 as const;

declare global {
  interface Window {
    AfterchimeBridge?: { postMessage(message: string): void };
  }
}

export type NativeRequest =
  | { version: 1; id: string; type: "capabilities.get" }
  | { version: 1; id: string; type: "notificationAccess.openSettings" }
  | { version: 1; id: string; type: "notificationAccess.openAppDetails" };

export type NativeResponse =
  | {
      version: 1;
      id: string;
      type: "capabilities.state";
      notificationAccess: boolean;
      appDetailsAction?: boolean;
    }
  | {
      version: 1;
      id: string;
      type: "action.result";
      action: "notificationAccess.openSettings" | "notificationAccess.openAppDetails";
      ok: boolean;
    };

export function parseNativeRequest(value: unknown): NativeRequest | null {
  if (!isRecord(value) || Object.keys(value).length !== 3) return null;
  if (value.version !== BRIDGE_VERSION || !isValidId(value.id)) return null;
  if (
    value.type === "capabilities.get" ||
    value.type === "notificationAccess.openSettings" ||
    value.type === "notificationAccess.openAppDetails"
  ) {
    return { version: 1, id: value.id, type: value.type };
  }
  return null;
}

export function sendNativeRequest(type: NativeRequest["type"]): string | null {
  const bridge = window.AfterchimeBridge;
  if (!bridge) return null;
  const id = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
  bridge.postMessage(JSON.stringify({ version: BRIDGE_VERSION, id, type }));
  return id;
}

export function installResponseListener(
  onResponse: (response: NativeResponse) => void,
): () => void {
  const listener = (event: MessageEvent<string>) => {
    if (typeof event.data !== "string" || event.data.length > 1024) return;
    try {
      const value: unknown = JSON.parse(event.data);
      if (!isRecord(value) || value.version !== BRIDGE_VERSION || !isValidId(value.id)) return;
      const keyCount = Object.keys(value).length;
      const hasAppDetailsAction = Object.hasOwn(value, "appDetailsAction");
      if (
        value.type === "capabilities.state" &&
        ((keyCount === 4 && !hasAppDetailsAction) ||
          (keyCount === 5 && hasAppDetailsAction && typeof value.appDetailsAction === "boolean")) &&
        typeof value.notificationAccess === "boolean"
      ) {
        onResponse(value as NativeResponse);
      } else if (
        value.type === "action.result" &&
        Object.keys(value).length === 5 &&
        (value.action === "notificationAccess.openSettings" ||
          value.action === "notificationAccess.openAppDetails") &&
        typeof value.ok === "boolean"
      ) {
        onResponse(value as NativeResponse);
      }
    } catch {
      // Ignore malformed native messages; the page never treats message text as authority.
    }
  };
  window.addEventListener("message", listener);
  return () => window.removeEventListener("message", listener);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isValidId(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(value);
}
