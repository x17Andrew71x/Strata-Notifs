import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  type DevFeedbackItem,
  DevFeedbackTool,
  type DevFeedbackTransport,
  elementSelectionContext,
} from "./DevFeedbackTool";

afterEach(() => cleanup());

function item(overrides: Partial<DevFeedbackItem> = {}): DevFeedbackItem {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    queuePosition: 1,
    revision: 1,
    title: "Tighten the excavation card",
    description: "Reduce the empty space above the fossil.",
    status: "queued",
    note: "Queued in submission order.",
    createdAt: "2026-10-16T00:00:00.000Z",
    updatedAt: "2026-10-16T00:00:00.000Z",
    ...overrides,
  };
}

function transport(items: DevFeedbackItem[] = []): DevFeedbackTransport {
  return {
    listOpen: vi.fn(async () => items),
    submit: vi.fn(async (draft) => item({ title: draft.title, description: draft.description })),
    amend: vi.fn(async (_id, draft) => item({ revision: 2, title: draft.title })),
    accept: vi.fn(async (id) => item({ id, status: "accepted" })),
    close: vi.fn(async (id) => item({ id, status: "closed" })),
    refreshShell: vi.fn(async () => undefined),
  };
}

describe("development feedback tool", () => {
  it("selects a rendered element and submits bounded context through the transport", async () => {
    const api = transport();
    vi.mocked(api.submit).mockImplementation(async (draft) => {
      expect(document.querySelector(".dev-feedback-tool.capturing")).toBeTruthy();
      const panel = document.querySelector<HTMLElement>(".dev-feedback-panel");
      const highlight = document.querySelector<HTMLElement>(".dev-feedback-highlight");
      expect(panel).toBeTruthy();
      expect(highlight).toBeTruthy();
      return item({ title: draft.title, description: draft.description });
    });
    render(
      <>
        <button type="button" data-dev-id="excavation-primary-action" aria-label="Excavate tile 1">
          Excavate
        </button>
        <DevFeedbackTool
          route="today"
          shellRevision={"a".repeat(64)}
          appVersion="0.1.10-dev-debug"
          transport={api}
        />
      </>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Select an element" }));
    fireEvent.click(screen.getByRole("button", { name: "Excavate tile 1" }));

    expect(screen.getByRole("heading", { name: "Stage targeted change" })).toBeTruthy();
    const panelLayer = Number(
      window.getComputedStyle(document.querySelector(".dev-feedback-panel") as HTMLElement).zIndex,
    );
    const highlightLayer = Number(
      window.getComputedStyle(document.querySelector(".dev-feedback-highlight") as HTMLElement)
        .zIndex,
    );
    expect(panelLayer).toBeGreaterThan(highlightLayer);
    fireEvent.change(screen.getByLabelText("Change title"), {
      target: { value: "Tighten the excavation action" },
    });
    fireEvent.change(screen.getByLabelText("Describe the change"), {
      target: { value: "Make this control shorter without moving the fossil." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Queue work item" }));

    await waitFor(() => expect(api.submit).toHaveBeenCalledTimes(1));
    expect(api.submit).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Tighten the excavation action",
        description: "Make this control shorter without moving the fossil.",
        route: "today",
        shellRevision: "a".repeat(64),
        appVersion: "0.1.10-dev-debug",
        selection: expect.objectContaining({
          kind: "element",
          devId: "excavation-primary-action",
          role: "button",
          accessibleLabel: "Excavate tile 1",
        }),
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Queued in submission order.");
  });

  it("clears the draft when the panel is closed", () => {
    const api = transport();
    render(
      <>
        <button type="button" aria-label="Excavate tile 2">
          Excavate
        </button>
        <DevFeedbackTool
          route="today"
          shellRevision={"a".repeat(64)}
          appVersion="0.1.10-dev-debug"
          transport={api}
        />
      </>,
    );

    const selectTarget = () => {
      fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
      fireEvent.click(screen.getByRole("button", { name: "Select an element" }));
      fireEvent.click(screen.getByRole("button", { name: "Excavate tile 2" }));
    };
    selectTarget();
    fireEvent.change(screen.getByLabelText("Change title"), { target: { value: "Old title" } });
    fireEvent.change(screen.getByLabelText("Describe the change"), {
      target: { value: "Old description" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Close development feedback" }));

    selectTarget();
    expect(screen.getByLabelText("Change title")).toHaveValue("");
    expect(screen.getByLabelText("Describe the change")).toHaveValue("");
  });

  it("shows server order and permits acceptance only after Ready for QA", async () => {
    const api = transport([
      item({ id: "22222222-2222-4222-8222-222222222222", queuePosition: 2, title: "Second" }),
      item({
        id: "11111111-1111-4111-8111-111111111111",
        queuePosition: 1,
        title: "First",
        status: "ready_for_qa",
        note: "Deployed to Railway development and ready to check.",
      }),
    ]);
    render(
      <DevFeedbackTool
        route="museum"
        shellRevision={"b".repeat(64)}
        appVersion="0.1.10-dev-debug"
        transport={api}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Work items" }));

    const rows = await screen.findAllByTestId("dev-feedback-item");
    expect(rows[0]).toHaveTextContent("First");
    expect(rows[0]).toHaveTextContent("Original request");
    expect(rows[1]).toHaveTextContent("Second");
    expect(rows[0]).toHaveTextContent("Reduce the empty space above the fossil.");
    expect(rows[0]).not.toHaveTextContent("Deployed to Railway development");
    fireEvent.click(screen.getByRole("button", { name: "View work item #2: Second" }));
    expect(screen.getByRole("button", { name: "View work item #2: Second" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.queryByText("Response")).toBeNull();
    expect(screen.getAllByRole("button", { name: "Accept result" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Add follow-up" })).toHaveLength(2);

    fireEvent.click(screen.getByRole("button", { name: "Accept result" }));
    await waitFor(() =>
      expect(api.accept).toHaveBeenCalledWith("11111111-1111-4111-8111-111111111111"),
    );
  });

  it("reveals only one helpful response bullet for a blocked item", async () => {
    const api = transport([
      item({
        status: "blocked",
        description: "Keep the opened fossil name banner hidden after it is secured.",
        note: "Choose whether the banner should remain hidden on secured fossils. Internal process details follow.",
      }),
    ]);
    render(
      <DevFeedbackTool
        route="today"
        shellRevision={"c".repeat(64)}
        appVersion="0.1.11-dev-debug"
        canCloseItems
        transport={api}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Work items" }));
    const row = await screen.findByTestId("dev-feedback-item");
    expect(row).toHaveTextContent("Keep the opened fossil name banner hidden after it is secured.");
    expect(row).not.toHaveTextContent("Choose whether the banner should remain hidden");

    fireEvent.click(
      screen.getByRole("button", { name: "View work item #1: Tighten the excavation card" }),
    );
    expect(screen.getByText("Response")).toBeTruthy();
    expect(screen.getByRole("list")).toHaveTextContent(
      "Choose whether the banner should remain hidden on secured fossils.",
    );
    expect(screen.getByRole("list")).not.toHaveTextContent("Internal process details");
  });

  it("keeps follow-ups available while an item is actively running", async () => {
    const api = transport([item({ status: "in_progress" })]);
    render(
      <DevFeedbackTool
        route="today"
        shellRevision={"c".repeat(64)}
        appVersion="0.1.10-dev-debug"
        transport={api}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Work items" }));
    expect(await screen.findByRole("button", { name: "Add follow-up" })).toBeTruthy();
    expect(screen.queryByText("Locked while working")).toBeNull();
    expect(screen.queryByRole("button", { name: "Close work item" })).toBeNull();
  });

  it("requires explicit confirmation before closing an unneeded work item", async () => {
    const api = transport([item()]);
    render(
      <DevFeedbackTool
        route="today"
        shellRevision={"c".repeat(64)}
        appVersion="0.1.11-dev-debug"
        canCloseItems
        transport={api}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Work items" }));
    const closeButton = await screen.findByRole("button", { name: "Close work item" });
    fireEvent.click(closeButton);
    expect(api.close).not.toHaveBeenCalled();
    expect(screen.getByRole("group", { name: "Confirm closing work item #1" })).toHaveTextContent(
      "Its history will be retained",
    );

    fireEvent.click(screen.getByRole("button", { name: "Keep work item" }));
    expect(api.close).not.toHaveBeenCalled();
    expect(screen.queryByRole("group", { name: "Confirm closing work item #1" })).toBeNull();

    fireEvent.click(closeButton);
    fireEvent.click(screen.getByRole("button", { name: "Confirm close" }));
    await waitFor(() =>
      expect(api.close).toHaveBeenCalledWith("11111111-1111-4111-8111-111111111111"),
    );
    expect(screen.queryByTestId("dev-feedback-item")).toBeNull();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Closed and removed from the open list. Its history was retained.",
    );
  });

  it("shows visible progress and completion when work items are checked manually", async () => {
    const api = transport([item()]);
    let resolveCheck: (items: DevFeedbackItem[]) => void = () => undefined;
    const checking = new Promise<DevFeedbackItem[]>((resolve) => {
      resolveCheck = resolve;
    });
    render(
      <DevFeedbackTool
        route="today"
        shellRevision={"c".repeat(64)}
        appVersion="0.1.11-dev-debug"
        canCloseItems
        transport={api}
      />,
    );
    await waitFor(() => expect(api.listOpen).toHaveBeenCalledTimes(1));
    vi.mocked(api.listOpen).mockReturnValueOnce(checking);

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Work items" }));
    fireEvent.click(await screen.findByRole("button", { name: "Check now" }));

    expect(screen.getByRole("button", { name: "Checking…" })).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("Checking work items…");
    resolveCheck([item({ note: "Fresh status." })]);
    await waitFor(() => expect(screen.getByRole("button", { name: "Check now" })).toBeEnabled());
    expect(screen.getByRole("status")).toHaveTextContent("Work items updated.");
  });

  it("offers an in-tool shell refresh without clearing application state", async () => {
    const api = transport();
    render(
      <DevFeedbackTool
        route="more"
        shellRevision={"c".repeat(64)}
        appVersion="0.1.10-dev-debug"
        transport={api}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Refresh development app" }));

    await waitFor(() => expect(api.refreshShell).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("Refreshing the verified development shell…")).toBeTruthy();
  });

  it("shows immutable ordered history and submits one empty follow-up field", async () => {
    const original = item({
      revision: 2,
      status: "ready_for_qa",
      description:
        "Original request: Reduce the empty space above the fossil. | Follow-up 1: Keep the fossil centred after the spacing change.",
    });
    const api = transport([original]);
    render(
      <>
        <div data-dev-id="excavation-card">Daily excavation</div>
        <DevFeedbackTool
          route="today"
          shellRevision={"d".repeat(64)}
          appVersion="0.1.10-dev-debug"
          transport={api}
        />
      </>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Work items" }));
    fireEvent.click(await screen.findByRole("button", { name: "Add follow-up" }));
    fireEvent.click(screen.getByRole("button", { name: "Select an element" }));
    fireEvent.click(screen.getByText("Daily excavation"));
    expect(screen.queryByText("Tap the element to change.")).toBeNull();
    expect(screen.queryByLabelText("Change title")).toBeNull();
    expect(screen.queryByLabelText("Describe the change")).toBeNull();
    expect(screen.getByRole("region", { name: "Prior request history" })).toHaveTextContent(
      "Original requestReduce the empty space above the fossil.Follow-up 1Keep the fossil centred after the spacing change.",
    );
    expect(screen.getByLabelText("New follow-up notes")).toHaveValue("");
    const panel = document.querySelector<HTMLElement>(".dev-feedback-panel");
    const body = document.querySelector<HTMLElement>(".dev-feedback-panel-body");
    const close = screen.getByRole("button", { name: "Close development feedback" });
    expect(panel).toContainElement(close);
    expect(body).not.toContainElement(close);
    fireEvent.change(screen.getByLabelText("New follow-up notes"), {
      target: { value: "The deployed spacing is better;\n\nmove the label slightly lower." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Send follow-up" }));

    await waitFor(() => expect(api.amend).toHaveBeenCalledTimes(1));
    expect(api.amend).toHaveBeenCalledWith(
      original.id,
      expect.objectContaining({
        title: original.title,
        description: "The deployed spacing is better; move the label slightly lower.",
        selection: expect.objectContaining({ devId: "excavation-card" }),
      }),
    );
  });

  it("keeps follow-up notes when the server rejects a changed item", async () => {
    const original = item({ status: "ready_for_qa" });
    const api = transport([original]);
    vi.mocked(api.amend).mockRejectedValueOnce(new Error("conflict"));
    render(
      <>
        <div data-dev-id="excavation-card">Daily excavation</div>
        <DevFeedbackTool
          route="today"
          shellRevision={"d".repeat(64)}
          appVersion="0.1.10-dev-debug"
          transport={api}
        />
      </>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Open development feedback" }));
    fireEvent.click(screen.getByRole("button", { name: "Work items" }));
    fireEvent.click(await screen.findByRole("button", { name: "Add follow-up" }));
    fireEvent.click(screen.getByRole("button", { name: "Select an element" }));
    fireEvent.click(screen.getByText("Daily excavation"));
    fireEvent.change(screen.getByLabelText("New follow-up notes"), {
      target: { value: "Keep these notes after failure." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Send follow-up" }));

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(
        "The follow-up could not be queued. Your notes were kept.",
      ),
    );
    expect(screen.getByLabelText("New follow-up notes")).toHaveValue(
      "Keep these notes after failure.",
    );
  });

  it("bounds semantic element context and excludes tool-owned controls", () => {
    const target = document.createElement("button");
    target.dataset.devId = "collection-card";
    target.dataset.devLabel = "B".repeat(300);
    target.setAttribute("aria-label", "A".repeat(300));
    const hiddenPrivateText = document.createElement("span");
    hiddenPrivateText.hidden = true;
    hiddenPrivateText.textContent = "private notification preview";
    target.append(hiddenPrivateText);
    document.body.append(target);

    const context = elementSelectionContext(target, { x: 1, y: 2, width: 30, height: 40 });
    expect(context?.devId).toBe("collection-card");
    expect(context?.accessibleLabel.length).toBeLessThanOrEqual(160);
    expect(context?.visibleLabel.length).toBeLessThanOrEqual(160);
    expect(context?.visibleLabel).not.toContain("private notification preview");

    const tool = document.createElement("button");
    tool.dataset.devFeedbackUi = "true";
    expect(elementSelectionContext(tool, { x: 0, y: 0, width: 1, height: 1 })).toBeNull();
  });
});
