import { describe, expect, it, vi } from "vitest";
import type { NativeResponse } from "../bridge";
import { type DevFeedbackDraft, type DevFeedbackItem, supportsDevFeedbackClose } from "./model";
import { NativeDevFeedbackTransport } from "./NativeDevFeedbackTransport";

const draft: DevFeedbackDraft = {
  idempotencyKey: "22222222-2222-4222-8222-222222222222",
  title: "Tighten the action",
  description: "Reduce the empty space above it.",
  route: "today",
  shellRevision: "a".repeat(64),
  appVersion: "0.1.10-dev-debug",
  selection: {
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
  },
};

const saved: DevFeedbackItem = {
  id: "11111111-1111-4111-8111-111111111111",
  queuePosition: 1,
  revision: 1,
  title: draft.title,
  description: draft.description,
  status: "queued",
  note: "Queued in submission order.",
  createdAt: "2026-10-16T00:00:00.000Z",
  updatedAt: "2026-10-16T00:00:00.000Z",
};

describe("native development-feedback transport", () => {
  it("shows closure only for native shells that implement it", () => {
    expect(supportsDevFeedbackClose("0.1.10-dev-debug")).toBe(false);
    expect(supportsDevFeedbackClose("0.1.11-dev-debug")).toBe(true);
    expect(supportsDevFeedbackClose("0.2.0-dev")).toBe(true);
    expect(supportsDevFeedbackClose("unknown")).toBe(false);
  });

  it("correlates a submission response and returns the saved item", async () => {
    const send = vi.fn(() => "request_1");
    const transport = new NativeDevFeedbackTransport(send, 2_000);

    const pending = transport.submit(draft);
    expect(send).toHaveBeenCalledWith("devFeedback.submit", draft);
    transport.handleResponse(stateResponse("request_1", "submit", saved, [saved]));

    await expect(pending).resolves.toEqual(saved);
  });

  it("lists in server order and sends amendments with the original item id", async () => {
    let next = 0;
    const send = vi.fn(() => `request_${++next}`);
    const transport = new NativeDevFeedbackTransport(send, 2_000);

    const listing = transport.listOpen();
    transport.handleResponse(stateResponse("request_1", "list", null, [saved]));
    await expect(listing).resolves.toEqual([saved]);

    const amendment = transport.amend(saved.id, draft);
    expect(send).toHaveBeenLastCalledWith("devFeedback.amend", { ...draft, itemId: saved.id });
    const revised = { ...saved, revision: 2 };
    transport.handleResponse(stateResponse("request_2", "amend", revised, [revised]));
    await expect(amendment).resolves.toEqual(revised);
  });

  it("rejects native failures and completes a verified shell refresh", async () => {
    let next = 0;
    const send = vi.fn(() => `request_${++next}`);
    const transport = new NativeDevFeedbackTransport(send, 2_000);

    const acceptance = transport.accept(saved.id);
    transport.handleResponse({
      ...stateResponse("request_1", "accept", null, [saved]),
      ok: false,
      error: "not_ready_for_qa",
    });
    await expect(acceptance).rejects.toMatchObject({ message: "not_ready_for_qa" });

    const refresh = transport.refreshShell();
    transport.handleResponse({
      version: 2,
      id: "request_2",
      type: "action.result",
      action: "devFeedback.refresh",
      ok: true,
    });
    await expect(refresh).resolves.toBeUndefined();
  });

  it("sends a distinct close operation and returns the retained closed item", async () => {
    const send = vi.fn(() => "request_1");
    const transport = new NativeDevFeedbackTransport(send, 2_000);

    const closing = transport.close(saved.id);
    expect(send).toHaveBeenCalledWith("devFeedback.close", { itemId: saved.id });
    const closed = { ...saved, status: "closed" as const };
    transport.handleResponse(stateResponse("request_1", "close", closed, []));

    await expect(closing).resolves.toEqual(closed);
  });
});

function stateResponse(
  id: string,
  operation: "list" | "submit" | "amend" | "accept" | "close",
  item: DevFeedbackItem | null,
  items: DevFeedbackItem[],
): Extract<NativeResponse, { type: "devFeedback.state" }> {
  return {
    version: 2,
    id,
    type: "devFeedback.state",
    operation,
    ok: true,
    error: null,
    item,
    items,
  };
}
