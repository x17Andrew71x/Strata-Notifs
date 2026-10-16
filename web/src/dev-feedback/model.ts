export type DevFeedbackStatus = "queued" | "in_progress" | "blocked" | "ready_for_qa" | "accepted";

export type SelectionRect = Readonly<{
  x: number;
  y: number;
  width: number;
  height: number;
}>;

export type DevFeedbackSelection = Readonly<{
  kind: "element" | "region";
  rect: SelectionRect;
  viewportWidth: number;
  viewportHeight: number;
  devId: string;
  tag: string;
  role: string;
  accessibleLabel: string;
  visibleLabel: string;
  domPath: string;
}>;

export type DevFeedbackDraft = Readonly<{
  idempotencyKey: string;
  title: string;
  description: string;
  route: string;
  shellRevision: string;
  appVersion: string;
  selection: DevFeedbackSelection;
}>;

export type DevFeedbackItem = Readonly<{
  id: string;
  queuePosition: number;
  revision: number;
  title: string;
  description: string;
  status: DevFeedbackStatus;
  note: string;
  createdAt: string;
  updatedAt: string;
}>;

export type DevFeedbackTransport = Readonly<{
  listOpen(): Promise<DevFeedbackItem[]>;
  submit(draft: DevFeedbackDraft): Promise<DevFeedbackItem>;
  amend(id: string, draft: DevFeedbackDraft): Promise<DevFeedbackItem>;
  accept(id: string): Promise<DevFeedbackItem>;
  refreshShell(): Promise<void>;
}>;
