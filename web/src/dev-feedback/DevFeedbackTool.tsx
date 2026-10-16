import { useCallback, useEffect, useRef, useState } from "react";
import { flushSync } from "react-dom";
import type {
  DevFeedbackDraft,
  DevFeedbackItem,
  DevFeedbackSelection,
  DevFeedbackStatus,
  DevFeedbackTransport,
  SelectionRect,
} from "./model";
import "./dev-feedback.css";

export type {
  DevFeedbackDraft,
  DevFeedbackItem,
  DevFeedbackSelection,
  DevFeedbackStatus,
  DevFeedbackTransport,
  SelectionRect,
} from "./model";

type DevFeedbackToolProps = Readonly<{
  route: string;
  shellRevision: string;
  appVersion: string;
  transport: DevFeedbackTransport;
}>;

type Point = Readonly<{ x: number; y: number }>;
type Position = Readonly<{ x: number; y: number }>;
type DragState = {
  start: Point;
  origin: Position;
  moved: boolean;
};

const LABEL_LIMIT = 160;
const PATH_DEPTH = 7;
const POSITION_KEY = "afterchime.dev-feedback.position.v1";
const POLL_INTERVAL_MS = 15_000;
const PANEL_Z_INDEX = 223;
const HIGHLIGHT_Z_INDEX = 221;

export function DevFeedbackTool({
  route,
  shellRevision,
  appVersion,
  transport,
}: DevFeedbackToolProps) {
  const [view, setView] = useState<"menu" | "items" | "form" | null>(null);
  const [selectionMode, setSelectionMode] = useState<"element" | "region" | null>(null);
  const [selection, setSelection] = useState<DevFeedbackSelection | null>(null);
  const [items, setItems] = useState<DevFeedbackItem[]>([]);
  const [expandedItemId, setExpandedItemId] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [idempotencyKey, setIdempotencyKey] = useState(() => newUuid());
  const [amending, setAmending] = useState<DevFeedbackItem | null>(null);
  const [pending, setPending] = useState(false);
  const [capturing, setCapturing] = useState(false);
  const [message, setMessage] = useState("");
  const [position, setPosition] = useState<Position | null>(() => readPosition());
  const [areaStart, setAreaStart] = useState<Point | null>(null);
  const [areaCurrent, setAreaCurrent] = useState<Point | null>(null);
  const drag = useRef<DragState | null>(null);

  const loadItems = useCallback(async () => {
    try {
      const loaded = await transport.listOpen();
      setItems(sortItems(loaded));
    } catch {
      setMessage("Work-item status is temporarily unavailable.");
    }
  }, [transport]);

  useEffect(() => {
    void loadItems();
    const interval = window.setInterval(() => {
      if (document.visibilityState !== "hidden") void loadItems();
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [loadItems]);

  useEffect(() => {
    if (selectionMode !== "element") return;
    const choose = (event: MouseEvent) => {
      const target = event.target;
      if (!(target instanceof Element) || target.closest("[data-dev-feedback-ui]")) return;
      event.preventDefault();
      event.stopPropagation();
      const bounds = target.getBoundingClientRect();
      const selected = elementSelectionContext(target, {
        x: bounds.left,
        y: bounds.top,
        width: bounds.width,
        height: bounds.height,
      });
      if (!selected) return;
      setSelection(selected);
      setSelectionMode(null);
      setView("form");
    };
    document.addEventListener("click", choose, true);
    return () => document.removeEventListener("click", choose, true);
  }, [selectionMode]);

  useEffect(() => {
    const move = (event: PointerEvent) => {
      const current = drag.current;
      if (!current) return;
      const deltaX = event.clientX - current.start.x;
      const deltaY = event.clientY - current.start.y;
      if (Math.abs(deltaX) + Math.abs(deltaY) > 4) current.moved = true;
      const next = clampPosition({ x: current.origin.x + deltaX, y: current.origin.y + deltaY });
      setPosition(next);
    };
    const end = () => {
      const current = drag.current;
      if (!current) return;
      if (current.moved && position) writePosition(position);
      window.setTimeout(() => {
        drag.current = null;
      }, 0);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", end);
    window.addEventListener("pointercancel", end);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", end);
      window.removeEventListener("pointercancel", end);
    };
  }, [position]);

  const clearDraft = () => {
    setSelection(null);
    setSelectionMode(null);
    setAreaStart(null);
    setAreaCurrent(null);
    setTitle("");
    setDescription("");
    setIdempotencyKey(newUuid());
    setAmending(null);
    setExpandedItemId(null);
    setMessage("");
  };

  const closePanel = () => {
    clearDraft();
    setView(null);
  };

  const startSelection = (mode: "element" | "region") => {
    if (!selection) setIdempotencyKey(newUuid());
    setSelection(null);
    setAreaStart(null);
    setAreaCurrent(null);
    setSelectionMode(mode);
    setView(null);
    setMessage(mode === "element" ? "Tap the element to change." : "Drag over the area to change.");
  };

  const submit = async () => {
    if (!selection || !title.trim() || !description.trim() || pending) return;
    setPending(true);
    flushSync(() => {
      setCapturing(true);
      setMessage("Capturing the selected view without development controls…");
    });
    await nextPaint();
    await nextPaint();
    const draft: DevFeedbackDraft = {
      idempotencyKey,
      title: title.trim(),
      description: description.trim(),
      route,
      shellRevision,
      appVersion,
      selection,
    };
    try {
      const saved = amending
        ? await transport.amend(amending.id, draft)
        : await transport.submit(draft);
      setItems((current) =>
        sortItems([saved, ...current.filter((entry) => entry.id !== saved.id)]),
      );
      setSelection(null);
      setTitle("");
      setDescription("");
      setIdempotencyKey(newUuid());
      setAmending(null);
      setView("items");
      setMessage(saved.note || "Queued in submission order.");
    } catch {
      setMessage("The work item could not be queued. Nothing was discarded.");
    } finally {
      setCapturing(false);
      setPending(false);
    }
  };

  const accept = async (id: string) => {
    if (pending) return;
    setPending(true);
    try {
      await transport.accept(id);
      setItems((current) => current.filter((entry) => entry.id !== id));
      setMessage("Accepted and removed from the active list.");
    } catch {
      setMessage("Acceptance could not be recorded.");
    } finally {
      setPending(false);
    }
  };

  const refresh = async () => {
    if (pending) return;
    setMessage("Refreshing the verified development shell…");
    setPending(true);
    try {
      await transport.refreshShell();
    } catch {
      setMessage("The development shell could not be refreshed.");
      setPending(false);
    }
  };

  const beginAmendment = (entry: DevFeedbackItem) => {
    if (entry.status === "in_progress" || entry.status === "accepted") return;
    setAmending(entry);
    setTitle(entry.title);
    setDescription(entry.description);
    setSelection(null);
    setIdempotencyKey(newUuid());
    setView("menu");
    setMessage("Revise the request, then select fresh supporting evidence.");
  };

  const triggerStyle = position ? { left: position.x, top: position.y } : undefined;
  const highlight = selection ?? currentAreaSelection(areaStart, areaCurrent);

  return (
    <div className={`dev-feedback-tool${capturing ? " capturing" : ""}`}>
      {highlight && <SelectionHighlight selection={highlight} />}
      {selectionMode === "region" && (
        <div
          className="dev-feedback-area-capture"
          onPointerDown={(event) => {
            const point = { x: event.clientX, y: event.clientY };
            setAreaStart(point);
            setAreaCurrent(point);
          }}
          onPointerMove={(event) => {
            if (areaStart) setAreaCurrent({ x: event.clientX, y: event.clientY });
          }}
          onPointerUp={(event) => {
            if (!areaStart) return;
            const rect = rectFromPoints(areaStart, { x: event.clientX, y: event.clientY });
            if (rect.width < 8 || rect.height < 8) {
              setMessage("Select a slightly larger area.");
              setAreaStart(null);
              setAreaCurrent(null);
              return;
            }
            setSelection(regionSelectionContext(rect));
            setAreaStart(null);
            setAreaCurrent(null);
            setSelectionMode(null);
            setView("form");
          }}
          data-dev-feedback-ui="true"
        />
      )}

      <button
        type="button"
        className="dev-feedback-trigger"
        aria-label="Open development feedback"
        data-dev-feedback-ui="true"
        style={triggerStyle}
        onPointerDown={(event) => {
          const bounds = event.currentTarget.getBoundingClientRect();
          drag.current = {
            start: { x: event.clientX, y: event.clientY },
            origin: position ?? { x: bounds.left, y: bounds.top },
            moved: false,
          };
        }}
        onClick={() => {
          if (drag.current?.moved) return;
          if (view) closePanel();
          else setView("menu");
        }}
      >
        DEV
      </button>

      {view && (
        <section
          className="dev-feedback-panel"
          aria-label="Development feedback"
          data-dev-feedback-ui="true"
          style={{ zIndex: PANEL_Z_INDEX }}
        >
          <div className="dev-feedback-panel-header">
            <div>
              <span>Development tools</span>
              <strong>{amending ? `Amend #${amending.queuePosition}` : "Targeted changes"}</strong>
            </div>
            <button type="button" aria-label="Close development feedback" onClick={closePanel}>
              ×
            </button>
          </div>

          {view === "menu" && (
            <div className="dev-feedback-menu">
              <button
                type="button"
                aria-label="Select an element"
                onClick={() => startSelection("element")}
              >
                <strong>Select an element</strong>
                <span>Attach the exact rendered control and its semantic path.</span>
              </button>
              <button
                type="button"
                aria-label="Select an area"
                onClick={() => startSelection("region")}
              >
                <strong>Select an area</strong>
                <span>Drag over spacing, artwork, or a group of controls.</span>
              </button>
              {!amending && (
                <button type="button" aria-label="Work items" onClick={() => setView("items")}>
                  <strong>Work items</strong>
                  <span>Review progress, amend a request, or accept a deployed result.</span>
                </button>
              )}
              <button
                type="button"
                aria-label="Refresh development app"
                disabled={pending}
                onClick={() => void refresh()}
              >
                <strong>Refresh development app</strong>
                <span>
                  Reload the verified Railway development shell without clearing local data.
                </span>
              </button>
            </div>
          )}

          {view === "form" && selection && (
            <form
              className="dev-feedback-form"
              onSubmit={(event) => {
                event.preventDefault();
                void submit();
              }}
            >
              <h2>Stage targeted change</h2>
              <p>
                {selection.kind === "element"
                  ? selection.accessibleLabel ||
                    selection.visibleLabel ||
                    selection.devId ||
                    selection.tag
                  : `${Math.round(selection.rect.width)} × ${Math.round(selection.rect.height)} region`}
              </p>
              <label>
                Change title
                <input
                  value={title}
                  maxLength={120}
                  required
                  onChange={(event) => setTitle(event.target.value)}
                />
              </label>
              <label>
                Describe the change
                <textarea
                  value={description}
                  maxLength={4_000}
                  required
                  rows={5}
                  onChange={(event) => setDescription(event.target.value)}
                />
              </label>
              <div className="dev-feedback-actions">
                <button type="button" onClick={() => startSelection(selection.kind)}>
                  Reselect
                </button>
                <button type="submit" disabled={pending || !title.trim() || !description.trim()}>
                  {amending ? "Queue amendment" : "Queue work item"}
                </button>
              </div>
            </form>
          )}

          {view === "items" && (
            <div className="dev-feedback-items">
              <div className="dev-feedback-items-heading">
                <h2>Work items</h2>
                <button type="button" onClick={() => void loadItems()}>
                  Check now
                </button>
              </div>
              {items.length === 0 ? (
                <p className="dev-feedback-empty">No open work items.</p>
              ) : (
                items.map((entry) => {
                  const expanded = expandedItemId === entry.id;
                  const amendable = entry.status !== "in_progress" && entry.status !== "accepted";
                  return (
                    <article key={entry.id} data-testid="dev-feedback-item">
                      <button
                        type="button"
                        className="dev-feedback-item-summary"
                        aria-expanded={expanded}
                        aria-label={`View work item #${entry.queuePosition}: ${entry.title}`}
                        onClick={() => setExpandedItemId(expanded ? null : entry.id)}
                      >
                        <span className="dev-feedback-item-title">
                          <span>#{entry.queuePosition}</span>
                          <strong>{entry.title}</strong>
                          <em className={`status-${entry.status}`}>{statusLabel(entry.status)}</em>
                        </span>
                      </button>
                      {entry.note && <p>{entry.note}</p>}
                      {expanded && (
                        <div className="dev-feedback-item-detail">
                          <strong>Submitted request</strong>
                          <p>{entry.description}</p>
                          <small>Revision {entry.revision} · Original order retained</small>
                        </div>
                      )}
                      <div className="dev-feedback-item-actions">
                        {amendable && (
                          <button type="button" onClick={() => beginAmendment(entry)}>
                            {entry.status === "queued" ? "Revise queued item" : "Add amendment"}
                          </button>
                        )}
                        {entry.status === "in_progress" && (
                          <span className="dev-feedback-item-locked">Locked while working</span>
                        )}
                        {entry.status === "ready_for_qa" && (
                          <button
                            type="button"
                            disabled={pending}
                            onClick={() => void accept(entry.id)}
                          >
                            Accept result
                          </button>
                        )}
                      </div>
                    </article>
                  );
                })
              )}
            </div>
          )}

          {message && (
            <p className="dev-feedback-message" role="status">
              {message}
            </p>
          )}
        </section>
      )}

      {selectionMode && <p className="dev-feedback-selection-hint">{message}</p>}
    </div>
  );
}

export function elementSelectionContext(
  element: Element,
  rect: SelectionRect,
): DevFeedbackSelection | null {
  if (element.closest("[data-dev-feedback-ui]")) return null;
  const html = element instanceof HTMLElement ? element : null;
  return {
    kind: "element",
    rect: boundedRect(rect),
    viewportWidth: boundedDimension(window.innerWidth),
    viewportHeight: boundedDimension(window.innerHeight),
    devId: boundedText(html?.dataset.devId ?? "", 96),
    tag: boundedText(element.tagName.toLowerCase(), 32),
    role: boundedText(element.getAttribute("role") || implicitRole(element), 64),
    accessibleLabel: boundedText(element.getAttribute("aria-label") ?? "", LABEL_LIMIT),
    visibleLabel: boundedText(html?.dataset.devLabel ?? "", LABEL_LIMIT),
    domPath: boundedText(buildDomPath(element), 512),
  };
}

export function regionSelectionContext(rect: SelectionRect): DevFeedbackSelection {
  return {
    kind: "region",
    rect: boundedRect(rect),
    viewportWidth: boundedDimension(window.innerWidth),
    viewportHeight: boundedDimension(window.innerHeight),
    devId: "",
    tag: "",
    role: "",
    accessibleLabel: "",
    visibleLabel: "",
    domPath: "",
  };
}

function SelectionHighlight({ selection }: Readonly<{ selection: DevFeedbackSelection }>) {
  return (
    <div
      className="dev-feedback-highlight"
      aria-hidden="true"
      style={{
        zIndex: HIGHLIGHT_Z_INDEX,
        left: selection.rect.x,
        top: selection.rect.y,
        width: selection.rect.width,
        height: selection.rect.height,
      }}
    />
  );
}

function currentAreaSelection(start: Point | null, current: Point | null) {
  return start && current ? regionSelectionContext(rectFromPoints(start, current)) : null;
}

function rectFromPoints(first: Point, second: Point): SelectionRect {
  return boundedRect({
    x: Math.min(first.x, second.x),
    y: Math.min(first.y, second.y),
    width: Math.abs(first.x - second.x),
    height: Math.abs(first.y - second.y),
  });
}

function boundedRect(rect: SelectionRect): SelectionRect {
  const x = Math.max(0, Math.min(window.innerWidth, finite(rect.x)));
  const y = Math.max(0, Math.min(window.innerHeight, finite(rect.y)));
  return {
    x: Math.round(x),
    y: Math.round(y),
    width: Math.round(Math.max(0, Math.min(window.innerWidth - x, finite(rect.width)))),
    height: Math.round(Math.max(0, Math.min(window.innerHeight - y, finite(rect.height)))),
  };
}

function finite(value: number): number {
  return Number.isFinite(value) ? value : 0;
}

function boundedDimension(value: number): number {
  return Math.round(Math.max(1, Math.min(16_384, finite(value))));
}

function boundedText(value: string, limit: number): string {
  return value.replace(/\s+/g, " ").trim().slice(0, limit);
}

function implicitRole(element: Element): string {
  const tag = element.tagName.toLowerCase();
  if (tag === "button") return "button";
  if (tag === "a" && element.hasAttribute("href")) return "link";
  if (tag === "input") return "textbox";
  return "";
}

function buildDomPath(element: Element): string {
  const path: string[] = [];
  let current: Element | null = element;
  while (current && path.length < PATH_DEPTH && current.tagName.toLowerCase() !== "html") {
    const devId = current instanceof HTMLElement ? current.dataset.devId : undefined;
    if (devId) {
      path.unshift(`[data-dev-id="${devId.replace(/["\\]/g, "")}"]`);
      break;
    }
    const tag = current.tagName.toLowerCase();
    const parent: Element | null = current.parentElement;
    if (!parent) {
      path.unshift(tag);
      break;
    }
    const siblings = [...parent.children].filter((child) => child.tagName === current?.tagName);
    const index = siblings.indexOf(current) + 1;
    path.unshift(`${tag}:nth-of-type(${Math.max(1, index)})`);
    current = parent;
  }
  return path.join(" > ");
}

function sortItems(items: DevFeedbackItem[]): DevFeedbackItem[] {
  return [...items].sort((first, second) => first.queuePosition - second.queuePosition);
}

function statusLabel(status: DevFeedbackStatus): string {
  switch (status) {
    case "queued":
      return "Queued";
    case "in_progress":
      return "Working";
    case "blocked":
      return "Needs input";
    case "ready_for_qa":
      return "Ready for QA";
    case "accepted":
      return "Accepted";
  }
}

function clampPosition(position: Position): Position {
  const edge = 12;
  const size = 52;
  return {
    x: Math.round(Math.max(edge, Math.min(window.innerWidth - size - edge, position.x))),
    y: Math.round(Math.max(edge, Math.min(window.innerHeight - size - edge, position.y))),
  };
}

function readPosition(): Position | null {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(POSITION_KEY) ?? "null");
    if (
      parsed &&
      typeof parsed === "object" &&
      "x" in parsed &&
      "y" in parsed &&
      typeof parsed.x === "number" &&
      typeof parsed.y === "number"
    ) {
      return clampPosition({ x: parsed.x, y: parsed.y });
    }
  } catch {
    // Invalid presentation state falls back to the safe default position.
  }
  return null;
}

function writePosition(position: Position): void {
  try {
    window.localStorage.setItem(POSITION_KEY, JSON.stringify(clampPosition(position)));
  } catch {
    // Moving the control must not depend on persistent browser storage.
  }
}

function newUuid(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

async function nextPaint(): Promise<void> {
  await new Promise<void>((resolve) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      window.clearTimeout(fallback);
      resolve();
    };
    const fallback = window.setTimeout(finish, 100);
    if (typeof window.requestAnimationFrame === "function") window.requestAnimationFrame(finish);
  });
}
