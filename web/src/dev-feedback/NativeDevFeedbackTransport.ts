import { type NativeAction, type NativeResponse, sendNativeRequest } from "../bridge";
import type { DevFeedbackDraft, DevFeedbackItem, DevFeedbackTransport } from "./model";

type NativeSender = (type: NativeAction, payload?: Record<string, unknown>) => string | null;

type PendingRequest = Readonly<{
  resolve(response: NativeResponse): void;
  reject(error: Error): void;
  timeout: number;
}>;

export class NativeDevFeedbackTransport implements DevFeedbackTransport {
  private readonly pending = new Map<string, PendingRequest>();

  public constructor(
    private readonly send: NativeSender = sendNativeRequest,
    private readonly timeoutMilliseconds = 15_000,
  ) {
    if (!Number.isInteger(timeoutMilliseconds) || timeoutMilliseconds < 100) {
      throw new Error("invalid development-feedback timeout");
    }
  }

  public handleResponse(response: NativeResponse): void {
    const request = this.pending.get(response.id);
    if (!request) return;
    if (response.type !== "devFeedback.state" && response.type !== "action.result") return;
    this.pending.delete(response.id);
    window.clearTimeout(request.timeout);
    if (response.type === "devFeedback.state" && !response.ok) {
      request.reject(new Error(response.error || "development_feedback_failed"));
      return;
    }
    if (response.type === "action.result" && !response.ok) {
      request.reject(new Error("development_feedback_failed"));
      return;
    }
    request.resolve(response);
  }

  public async listOpen(): Promise<DevFeedbackItem[]> {
    const response = await this.request("devFeedback.items");
    if (response.type !== "devFeedback.state" || response.operation !== "list") {
      throw new Error("development_feedback_response_mismatch");
    }
    return response.items;
  }

  public async submit(draft: DevFeedbackDraft): Promise<DevFeedbackItem> {
    return this.mutation("devFeedback.submit", draft, "submit");
  }

  public async amend(id: string, draft: DevFeedbackDraft): Promise<DevFeedbackItem> {
    return this.mutation("devFeedback.amend", { ...draft, itemId: id }, "amend");
  }

  public async accept(id: string): Promise<DevFeedbackItem> {
    return this.mutation("devFeedback.accept", { itemId: id }, "accept");
  }

  public async refreshShell(): Promise<void> {
    const response = await this.request("devFeedback.refresh");
    if (response.type !== "action.result" || response.action !== "devFeedback.refresh") {
      throw new Error("development_feedback_response_mismatch");
    }
  }

  private async mutation(
    action: "devFeedback.submit" | "devFeedback.amend" | "devFeedback.accept",
    payload: Record<string, unknown>,
    operation: "submit" | "amend" | "accept",
  ): Promise<DevFeedbackItem> {
    const response = await this.request(action, payload);
    if (
      response.type !== "devFeedback.state" ||
      response.operation !== operation ||
      response.item === null
    ) {
      throw new Error("development_feedback_response_mismatch");
    }
    return response.item;
  }

  private request(type: NativeAction, payload?: Record<string, unknown>): Promise<NativeResponse> {
    const id = this.send(type, payload);
    if (!id) return Promise.reject(new Error("development_feedback_bridge_unavailable"));
    return new Promise<NativeResponse>((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("development_feedback_timeout"));
      }, this.timeoutMilliseconds);
      this.pending.set(id, { resolve, reject, timeout });
    });
  }
}
