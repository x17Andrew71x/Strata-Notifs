import { type CSSProperties, useEffect, useState } from "react";
import {
  installResponseListener,
  type NativeAction,
  type NativeResponse,
  type PreferenceKey,
  type ShellSpecimen,
  type ShellState,
  sendNativeRequest,
  type Tier,
  type World,
} from "./bridge";
import { artifactFor, WORLD_DEFINITIONS, worldDefinition } from "./worlds";

type ShellWorkerRegistrar = {
  register(scriptURL: string, options?: RegistrationOptions): Promise<unknown>;
};

type ShellMetadata = {
  shellVersion: number;
  bridgeVersion: number;
  revision: string;
  entry: string;
};

type RootRoute = "today" | "museum" | "community" | "more";
type Route = RootRoute | "worlds" | `specimen/${string}`;
export const TOAST_FADE_START_MS = 2_700;
export const TOAST_DURATION_MS = 3_000;
export async function registerShellWorker(
  isProduction: boolean,
  registrar: ShellWorkerRegistrar | undefined = typeof navigator !== "undefined" &&
  "serviceWorker" in navigator
    ? navigator.serviceWorker
    : undefined,
): Promise<void> {
  if (!isProduction || !registrar) return;
  try {
    await registrar.register("/service-worker.js", { scope: "/" });
  } catch {
    // Android retains the last complete remote shell and has a bundled fallback.
  }
}

function App() {
  const [shellState, setShellState] = useState<ShellState | null>(null);
  const [access, setAccess] = useState<boolean | null>(null);
  const [appDetailsAction, setAppDetailsAction] = useState(false);
  const [message, setMessage] = useState("");
  const [toastExiting, setToastExiting] = useState(false);
  const [metadata, setMetadata] = useState<ShellMetadata | null>(null);
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash));
  const [tier, setTier] = useState<Tier | "ALL">("ALL");
  const [combineIds, setCombineIds] = useState<string[]>([]);
  const [confirmCombine, setConfirmCombine] = useState(false);

  useEffect(() => {
    const remoteOrigin = window.location.hostname !== "appassets.androidplatform.net";
    void registerShellWorker(import.meta.env.PROD && remoteOrigin);
    if (remoteOrigin) {
      void fetch("/shell/metadata", { cache: "no-store", credentials: "same-origin" })
        .then(async (response) => (response.ok ? ((await response.json()) as unknown) : null))
        .then((value) => {
          if (isShellMetadata(value)) setMetadata(value);
        })
        .catch(() => undefined);
    }
    const stop = installResponseListener((response: NativeResponse) => {
      if (response.type === "shell.state") {
        setShellState(response);
        setAccess(response.notificationAccess);
      } else if (response.type === "capabilities.state") {
        setAccess(response.notificationAccess);
        setAppDetailsAction(response.appDetailsAction);
      } else if (response.type === "action.result") {
        setMessage(actionMessage(response.action, response.ok));
      }
    });
    const onHashChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener("hashchange", onHashChange);
    sendNativeRequest("capabilities.get");
    sendNativeRequest("state.get");
    return () => {
      stop();
      window.removeEventListener("hashchange", onHashChange);
    };
  }, []);

  useEffect(() => {
    if (!message) {
      setToastExiting(false);
      return;
    }
    setToastExiting(false);
    const fadeTimeout = window.setTimeout(() => setToastExiting(true), TOAST_FADE_START_MS);
    return () => window.clearTimeout(fadeTimeout);
  }, [message]);

  useEffect(() => {
    if (!toastExiting) return;
    const dismissTimeout = window.setTimeout(
      () => setMessage(""),
      TOAST_DURATION_MS - TOAST_FADE_START_MS,
    );
    return () => window.clearTimeout(dismissTimeout);
  }, [toastExiting]);

  useEffect(() => {
    if (!shellState) return;
    const ownedIds = new Set(shellState.museum.specimens.map((specimen) => specimen.id));
    setCombineIds((current) => current.filter((id) => ownedIds.has(id)));
  }, [shellState]);

  const navigate = (next: Route) => {
    const nextHash = `#${next}`;
    setRoute(next);
    if (window.location.hash !== nextHash) window.location.hash = next;
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
  };

  const act = (type: NativeAction, payload?: Record<string, unknown>, pending?: string) => {
    if (pending) setMessage(pending);
    if (!sendNativeRequest(type, payload)) {
      setMessage("This action needs the installed Afterchime shell.");
      return false;
    }
    return true;
  };

  if (!shellState) {
    return (
      <main className="boot-screen">
        <Brand />
        <div className="boot-orbit" aria-hidden="true" />
        <p>
          {window.AfterchimeBridge
            ? "Loading your collection…"
            : "Open Afterchime on Android to view your collection."}
        </p>
      </main>
    );
  }

  const effectiveAccess = access ?? shellState.notificationAccess;
  if (!shellState.preferences.onboardingComplete) {
    return (
      <Onboarding
        access={effectiveAccess}
        appDetailsAction={appDetailsAction}
        message={message}
        onContinue={() => act("onboarding.complete", undefined, "Preparing your collection…")}
        onOpenSettings={() =>
          act("notificationAccess.openSettings", undefined, "Opening notification access…")
        }
        onOpenAppInfo={() =>
          act(
            "notificationAccess.openAppDetails",
            undefined,
            "In App info, tap ⋮ and choose Allow restricted settings.",
          )
        }
      />
    );
  }

  const selectedSpecimenId = route.startsWith("specimen/") ? route.slice("specimen/".length) : null;
  const selectedSpecimen = selectedSpecimenId
    ? (shellState.museum.specimens.find((specimen) => specimen.id === selectedSpecimenId) ?? null)
    : null;
  const selectedRoot = rootFor(route);
  const selectedWorld = worldDefinition(shellState.worlds.selected);

  return (
    <div
      className={`app-shell world-${selectedWorld.theme}${shellState.preferences.highContrastEnabled ? " high-contrast" : ""}${
        shellState.preferences.reduceMotionEnabled ? " reduce-motion" : ""
      }`}
      data-world={selectedWorld.name}
    >
      <header className="app-header">
        <Brand />
        <span className={`status-pill ${effectiveAccess ? "active" : "paused"}`}>
          {effectiveAccess ? "Collecting" : "Paused"}
        </span>
      </header>
      <main className="app-content">
        {route === "today" && (
          <Today
            state={shellState}
            onAction={(type) =>
              act(type, undefined, type === "formation.reveal" ? "Revealing…" : "Opening settings…")
            }
          />
        )}
        {route === "museum" && (
          <Museum
            state={shellState}
            tier={tier}
            combineIds={combineIds}
            onTier={setTier}
            onOpen={(id) => navigate(`specimen/${id}`)}
            onToggleCombine={(specimen) =>
              setCombineIds((current) =>
                toggleCombine(current, specimen, shellState.museum.specimens),
              )
            }
            onCancelCombine={() => setCombineIds([])}
            onReviewCombine={() => setConfirmCombine(true)}
          />
        )}
        {route === "community" && <Community />}
        {route === "more" && (
          <More
            state={shellState}
            metadata={metadata}
            message={message}
            onPreference={(key, value) =>
              act("preferences.update", { key, value }, "Saving on this device…")
            }
            onOpenSettings={() =>
              act("notificationAccess.openSettings", undefined, "Opening notification access…")
            }
            onWorlds={() => navigate("worlds")}
          />
        )}
        {route === "worlds" && (
          <Worlds
            state={shellState}
            onBack={() => history.back()}
            onSelect={(world) => act("worlds.select", { world }, "Applying world…")}
            onOwn={(world) => act("worlds.own", { world }, "Unlocking for development…")}
            onReset={() => act("worlds.reset", undefined, "Resetting development worlds…")}
          />
        )}
        {selectedSpecimenId && (
          <SpecimenDetail
            specimen={selectedSpecimen}
            world={shellState.worlds.selected}
            onBack={() => history.back()}
            onLock={(specimen, locked) =>
              act(
                "museum.lock",
                { specimenId: specimen.id, locked },
                locked ? "Protecting specimen…" : "Removing protection…",
              )
            }
            onShare={(specimen) =>
              act("museum.share", { specimenId: specimen.id }, "Opening Android share…")
            }
            onCombine={(specimen) => {
              setCombineIds([specimen.id]);
              navigate("museum");
            }}
          />
        )}
      </main>
      {!selectedSpecimenId && route !== "worlds" && (
        <nav className="bottom-nav" aria-label="Main navigation">
          {(["today", "museum", "community", "more"] as RootRoute[]).map((item) => (
            <button
              type="button"
              key={item}
              className={selectedRoot === item ? "selected" : ""}
              aria-current={selectedRoot === item ? "page" : undefined}
              onClick={() => navigate(item)}
            >
              <span aria-hidden="true">{navGlyph(item)}</span>
              {titleCase(item)}
            </button>
          ))}
        </nav>
      )}
      {message && (
        <div className={`toast${toastExiting ? " exiting" : ""}`} role="status">
          {message}
        </div>
      )}
      {confirmCombine && (
        <CombineDialog
          specimens={combineIds
            .map((id) => shellState.museum.specimens.find((specimen) => specimen.id === id))
            .filter((specimen): specimen is ShellSpecimen => specimen !== undefined)}
          onCancel={() => setConfirmCombine(false)}
          onConfirm={() => {
            const sent = act("museum.combine", { specimenIds: combineIds }, "Restoring specimen…");
            if (sent) setCombineIds([]);
            setConfirmCombine(false);
          }}
        />
      )}
    </div>
  );
}

function Brand() {
  return (
    <div className="brand">
      <span aria-hidden="true">✳</span>
      <strong>afterchime</strong>
    </div>
  );
}

function Onboarding({
  access,
  appDetailsAction,
  message,
  onContinue,
  onOpenSettings,
  onOpenAppInfo,
}: {
  access: boolean;
  appDetailsAction: boolean;
  message: string;
  onContinue(): void;
  onOpenSettings(): void;
  onOpenAppInfo(): void;
}) {
  return (
    <main className="onboarding">
      <div className="onboarding-copy">
        <Brand />
        <p className="eyebrow">Private by design</p>
        <h1>A quiet collection, kept here.</h1>
        <p className="lead">
          Afterchime reduces notifications on your device to timing, category, and colour signals.
          Message text, titles, senders, contacts, actions, and media are never stored.
        </p>
        <div className="privacy-card">
          <strong>Your choices remain yours.</strong>
          <p>Cloud features, analytics, and aggregate sharing stay off unless you enable them.</p>
        </div>
        {!access && (
          <div className="notice-card">
            <p>
              Android uses one broad notification-access switch. Sensitive app categories and
              conversations, silent, and ongoing notifications remain excluded.
            </p>
            <button type="button" className="secondary" onClick={onOpenSettings}>
              Choose included apps
            </button>
            {appDetailsAction && (
              <button type="button" className="text-button" onClick={onOpenAppInfo}>
                Restricted setting? Open app info
              </button>
            )}
          </div>
        )}
        <button type="button" className="primary wide" onClick={onContinue}>
          Continue
        </button>
        {message && (
          <p className="inline-status" role="status">
            {message}
          </p>
        )}
      </div>
      <SpecimenVisual decorative />
    </main>
  );
}

function Today({ state, onAction }: { state: ShellState; onAction(type: NativeAction): void }) {
  const specimen = state.today.specimen;
  const copy = todayCopy(state);
  return (
    <section className="screen today-screen" aria-labelledby="today-title">
      <div className="screen-heading">
        <p className="eyebrow">{formatDate(state.today.localDate)}</p>
        <h1 id="today-title">{copy.title}</h1>
        <p>{copy.body}</p>
      </div>
      <div className="formation-stage">
        {specimen && specimen.revealedAtEpochMillis !== null ? (
          <SpecimenVisual specimen={specimen} world={state.worlds.selected} />
        ) : (
          <Formation layers={state.today.layers} ready={state.today.primaryAction === "REVEAL"} />
        )}
      </div>
      {state.today.primaryAction === "ENABLE_ACCESS" && (
        <button
          type="button"
          className="primary wide"
          onClick={() => onAction("notificationAccess.openSettings")}
        >
          {state.today.observation === "AwaitingAccess"
            ? "Start collecting"
            : "Open notification settings"}
        </button>
      )}
      {state.today.primaryAction === "REVEAL" && (
        <button
          type="button"
          className="primary wide"
          disabled={state.today.revealInFlight}
          onClick={() => onAction("formation.reveal")}
        >
          {state.today.revealInFlight ? "Revealing…" : "Reveal specimen"}
        </button>
      )}
    </section>
  );
}

function Formation({ layers, ready }: { layers: ShellState["today"]["layers"]; ready: boolean }) {
  const visible = layers.slice(-18);
  return (
    <div
      className={`formation ${ready ? "ready" : ""}`}
      role="img"
      aria-label="Today's forming specimen"
    >
      <div className="formation-core" />
      {visible.map((layer, index) => {
        const colour = `#${(layer.sourceColourRgb & 0xffffff).toString(16).padStart(6, "0")}`;
        return (
          <span
            key={`${layer.localHour}-${layer.category}-${layer.sourceColourRgb}`}
            className="formation-layer"
            style={
              {
                "--layer-colour": colour,
                "--layer-index": index,
                "--layer-total": Math.max(visible.length, 1),
              } as CSSProperties
            }
          />
        );
      })}
      {visible.length === 0 && (
        <span className="formation-empty">Quiet days still leave a trace.</span>
      )}
    </div>
  );
}

function Museum({
  state,
  tier,
  combineIds,
  onTier,
  onOpen,
  onToggleCombine,
  onCancelCombine,
  onReviewCombine,
}: {
  state: ShellState;
  tier: Tier | "ALL";
  combineIds: string[];
  onTier(value: Tier | "ALL"): void;
  onOpen(id: string): void;
  onToggleCombine(specimen: ShellSpecimen): void;
  onCancelCombine(): void;
  onReviewCombine(): void;
}) {
  const world = worldDefinition(state.worlds.selected);
  const specimens = state.museum.specimens
    .filter((specimen) => specimen.revealedAtEpochMillis !== null)
    .filter((specimen) => tier === "ALL" || specimen.tier === tier)
    .sort((a, b) => b.createdAtEpochMillis - a.createdAtEpochMillis || a.id.localeCompare(b.id));
  const selected = combineIds
    .map((id) => state.museum.specimens.find((specimen) => specimen.id === id))
    .filter((specimen): specimen is ShellSpecimen => specimen !== undefined);
  const canReview = combineOutput(selected) !== null;
  return (
    <section className="screen museum-screen" aria-labelledby="museum-title">
      <div className="screen-heading compact">
        <p className="eyebrow">{world.eyebrow}</p>
        <h1 id="museum-title">{world.collectionName}</h1>
      </div>
      {combineIds.length > 0 && (
        <div className="combine-bar">
          <div>
            <strong>Restore a specimen</strong>
            <span>{combineIds.length} of 3 matching specimens selected</span>
          </div>
          <button type="button" className="text-button" onClick={onCancelCombine}>
            Cancel
          </button>
          <button
            type="button"
            className="primary small"
            disabled={!canReview}
            onClick={onReviewCombine}
          >
            Review
          </button>
        </div>
      )}
      <fieldset className="filter-row" aria-label="Filter by rarity">
        {(["ALL", "COMMON", "UNCOMMON", "RARE", "EXCEPTIONAL", "SINGULAR"] as const).map(
          (value) => (
            <button
              type="button"
              key={value}
              className={tier === value ? "selected" : ""}
              onClick={() => onTier(value)}
            >
              {titleCase(value)}
            </button>
          ),
        )}
      </fieldset>
      {specimens.length === 0 ? (
        <div className="empty-state">
          <SpecimenVisual decorative compact />
          <h2>Your shelves are waiting.</h2>
          <p>Reveal a daily specimen and it will appear here.</p>
        </div>
      ) : (
        <div className="specimen-grid">
          {specimens.map((specimen) => {
            const artifact = artifactFor(state.worlds.selected, specimen.id);
            const combining = combineIds.length > 0;
            const selectable =
              !combining || canAddToCombine(combineIds, specimen, state.museum.specimens);
            return (
              <button
                type="button"
                key={specimen.id}
                className={`specimen-card${combineIds.includes(specimen.id) ? " selected" : ""}`}
                disabled={!selectable}
                onClick={() => (combining ? onToggleCombine(specimen) : onOpen(specimen.id))}
              >
                <SpecimenVisual specimen={specimen} world={state.worlds.selected} compact />
                <strong>{artifact.name}</strong>
                <span>{formatName(specimen.tier)}</span>
                <small>
                  {specimen.anchoredLocalDate ? formatDate(specimen.anchoredLocalDate) : "Restored"}
                </small>
              </button>
            );
          })}
        </div>
      )}
    </section>
  );
}

function SpecimenDetail({
  specimen,
  world,
  onBack,
  onLock,
  onShare,
  onCombine,
}: {
  specimen: ShellSpecimen | null;
  world: World;
  onBack(): void;
  onLock(specimen: ShellSpecimen, locked: boolean): void;
  onShare(specimen: ShellSpecimen): void;
  onCombine(specimen: ShellSpecimen): void;
}) {
  if (!specimen || specimen.revealedAtEpochMillis === null) {
    return (
      <section className="screen detail-screen">
        <button type="button" className="back-button" onClick={onBack}>
          ← Back
        </button>
        <h1>Specimen unavailable</h1>
        <p>This specimen is no longer in the local collection.</p>
      </section>
    );
  }
  const artifact = artifactFor(world, specimen.id);
  return (
    <section className="screen detail-screen" aria-labelledby="detail-title">
      <button type="button" className="back-button" onClick={onBack}>
        ← Back
      </button>
      <p className="eyebrow">{worldDefinition(world).name}</p>
      <h1 id="detail-title">{artifact.name}</h1>
      <div className="detail-card">
        <SpecimenVisual specimen={specimen} world={world} />
        <dl>
          <div>
            <dt>Artifact</dt>
            <dd>{artifact.name}</dd>
          </div>
          <div>
            <dt>Source pattern</dt>
            <dd>{formatName(specimen.family)}</dd>
          </div>
          <div>
            <dt>Rarity</dt>
            <dd>{formatName(specimen.tier)}</dd>
          </div>
          <div>
            <dt>{specimen.anchoredLocalDate ? "Sealed" : "Restored"}</dt>
            <dd>
              {specimen.anchoredLocalDate
                ? formatDate(specimen.anchoredLocalDate)
                : formatName(specimen.collectibleState)}
            </dd>
          </div>
          {specimen.isLocked && (
            <div>
              <dt>Protection</dt>
              <dd>Protected</dd>
            </div>
          )}
        </dl>
        <div className="action-row">
          <button type="button" className="secondary" onClick={() => onShare(specimen)}>
            Share image
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => onLock(specimen, !specimen.isLocked)}
          >
            {specimen.isLocked ? "Remove protection" : "Protect specimen"}
          </button>
          {!specimen.isLocked && specimen.collectibleState !== "CENTRE_PIECE" && (
            <button type="button" className="primary" onClick={() => onCombine(specimen)}>
              Combine three
            </button>
          )}
        </div>
      </div>
    </section>
  );
}

function Community() {
  return (
    <section className="screen placeholder-screen">
      <p className="eyebrow">Optional and private</p>
      <h1>Community</h1>
      <p>Nothing leaves this device unless you explicitly enable an online feature.</p>
      <div className="coming-soon">Community features are not enabled yet.</div>
    </section>
  );
}

function More({
  state,
  metadata,
  message,
  onPreference,
  onOpenSettings,
  onWorlds,
}: {
  state: ShellState;
  metadata: ShellMetadata | null;
  message: string;
  onPreference(key: PreferenceKey, value: boolean): void;
  onOpenSettings(): void;
  onWorlds(): void;
}) {
  const preferences = state.preferences;
  return (
    <section className="screen more-screen" aria-labelledby="more-title">
      <div className="screen-heading compact">
        <p className="eyebrow">Local controls</p>
        <h1 id="more-title">More</h1>
      </div>
      <div className="settings-group">
        <button type="button" className="settings-link" onClick={onWorlds}>
          <span>
            <strong>Worlds</strong>
            <small>{worldDefinition(state.worlds.selected).name}</small>
          </span>
          <span>›</span>
        </button>
        <button type="button" className="settings-link" onClick={onOpenSettings}>
          <span>
            <strong>Notification access</strong>
            <small>{state.notificationAccess ? "Collecting" : "Paused"}</small>
          </span>
          <span>›</span>
        </button>
      </div>
      <div className="settings-group">
        <Toggle
          label="Online features"
          detail="Off unless you choose otherwise"
          checked={preferences.onlineFeaturesEnabled}
          onChange={(value) => onPreference("onlineFeaturesEnabled", value)}
        />
        <Toggle
          label="Product analytics"
          checked={preferences.productAnalyticsEnabled}
          disabled={!preferences.onlineFeaturesEnabled}
          onChange={(value) => onPreference("productAnalyticsEnabled", value)}
        />
        <Toggle
          label="Aggregate sharing"
          checked={preferences.notificationAggregateSharingEnabled}
          disabled={!preferences.onlineFeaturesEnabled}
          onChange={(value) => onPreference("notificationAggregateSharingEnabled", value)}
        />
      </div>
      <div className="settings-group">
        <Toggle
          label="Reduce motion"
          checked={preferences.reduceMotionEnabled}
          onChange={(value) => onPreference("reduceMotionEnabled", value)}
        />
        <Toggle
          label="High contrast"
          checked={preferences.highContrastEnabled}
          onChange={(value) => onPreference("highContrastEnabled", value)}
        />
        <Toggle
          label="Haptics"
          checked={preferences.hapticsEnabled}
          onChange={(value) => onPreference("hapticsEnabled", value)}
        />
      </div>
      <p className="version-line">
        Shell {metadata ? `v${metadata.shellVersion} · ${metadata.revision.slice(0, 8)}` : "cached"}{" "}
        · Bridge v2
      </p>
      {message && (
        <p className="inline-status" role="status">
          {message}
        </p>
      )}
    </section>
  );
}

function Toggle({
  label,
  detail,
  checked,
  disabled = false,
  onChange,
}: {
  label: string;
  detail?: string;
  checked: boolean;
  disabled?: boolean;
  onChange(value: boolean): void;
}) {
  return (
    <label className={`toggle-row${disabled ? " disabled" : ""}`}>
      <span>
        <strong>{label}</strong>
        {detail && <small>{detail}</small>}
      </span>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span className="switch" aria-hidden="true" />
    </label>
  );
}

function Worlds({
  state,
  onBack,
  onSelect,
  onOwn,
  onReset,
}: {
  state: ShellState;
  onBack(): void;
  onSelect(world: World): void;
  onOwn(world: World): void;
  onReset(): void;
}) {
  return (
    <section className="screen worlds-screen" aria-labelledby="worlds-title">
      <button type="button" className="back-button" onClick={onBack}>
        ← Back
      </button>
      <p className="eyebrow">Four authored worlds</p>
      <h1 id="worlds-title">Worlds</h1>
      <p className="lead-small">
        Relic Vault is included. The other worlds are permanent cosmetic unlocks: buy once or earn
        through long-form collection milestones. This development build uses local test unlocks.
      </p>
      <div className="world-list">
        {WORLD_DEFINITIONS.filter((world) => state.worlds.available.includes(world.id)).map(
          (world) => {
            const owned = world.baseline || state.worlds.owned.includes(world.id);
            const selected = state.worlds.selected === world.id;
            return (
              <article
                className={`world-card theme-${world.theme}${selected ? " selected" : ""}`}
                key={world.id}
              >
                <div className="world-preview">
                  <SpecimenVisual decorative compact world={world.id} />
                </div>
                <div className="world-copy">
                  <p className="world-eyebrow">{world.eyebrow}</p>
                  <h2>{world.name}</h2>
                  <p>{world.description}</p>
                  <ul className="artifact-list" aria-label={`${world.name} artifacts`}>
                    {world.artifacts.map((artifact) => (
                      <li key={artifact.name}>{artifact.name}</li>
                    ))}
                  </ul>
                  <p className="world-status">
                    {selected
                      ? "Active world"
                      : world.baseline
                        ? "Included with Afterchime"
                        : owned
                          ? "Permanently unlocked"
                          : "Locked · one-time purchase or long-form mastery"}
                  </p>
                </div>
                {!selected && owned && state.worlds.developmentControlsEnabled && (
                  <button type="button" className="secondary" onClick={() => onSelect(world.id)}>
                    Apply
                  </button>
                )}
                {!owned && state.worlds.developmentControlsEnabled && (
                  <button type="button" className="secondary" onClick={() => onOwn(world.id)}>
                    Unlock for testing
                  </button>
                )}
              </article>
            );
          },
        )}
      </div>
      {state.worlds.developmentControlsEnabled && (
        <button type="button" className="text-button" onClick={onReset}>
          Reset test unlocks
        </button>
      )}
    </section>
  );
}

function CombineDialog({
  specimens,
  onCancel,
  onConfirm,
}: {
  specimens: ShellSpecimen[];
  onCancel(): void;
  onConfirm(): void;
}) {
  const output = combineOutput(specimens);
  return (
    <div className="modal-backdrop" role="presentation">
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="combine-title">
        <p className="eyebrow">Irreversible local action</p>
        <h2 id="combine-title">Restore these three?</h2>
        <p>
          The three selected specimens will become one {output ? formatName(output) : "restored"}{" "}
          specimen. This cannot be undone.
        </p>
        <div className="modal-actions">
          <button type="button" className="secondary" onClick={onCancel}>
            Keep them
          </button>
          <button type="button" className="primary" disabled={!output} onClick={onConfirm}>
            Restore specimen
          </button>
        </div>
      </section>
    </div>
  );
}

function SpecimenVisual({
  specimen,
  world = "PRIMEVAL_STRATA",
  decorative = false,
  compact = false,
}: {
  specimen?: ShellSpecimen;
  world?: World;
  decorative?: boolean;
  compact?: boolean;
}) {
  const artifact = artifactFor(world, specimen?.id);
  const definition = worldDefinition(world);
  const accessibility = decorative
    ? { "aria-hidden": true }
    : {
        role: "img" as const,
        "aria-label": `${formatName(specimen?.tier ?? "COMMON")} ${artifact.name} artifact from ${definition.name}`,
      };
  return (
    <figure
      className={`specimen-visual theme-${definition.theme}${compact ? " compact" : ""}`}
      {...accessibility}
    >
      <img src={artifact.image} alt="" draggable="false" />
      {!compact && <figcaption>{artifact.name}</figcaption>}
    </figure>
  );
}

function todayCopy(state: ShellState): { title: string; body: string } {
  const { today } = state;
  const priorDate = today.specimen?.anchoredLocalDate;
  if (
    today.observation !== "SealedObserved" &&
    today.specimen?.revealedAtEpochMillis === null &&
    priorDate &&
    priorDate < today.localDate
  ) {
    return {
      title: isYesterday(priorDate, today.localDate)
        ? "Yesterday’s specimen is ready"
        : "A prior specimen is ready",
      body: "Reveal it whenever you return. Waiting longer will not change its rarity or quality.",
    };
  }
  switch (today.observation) {
    case "AwaitingAccess":
      return {
        title: "Begin your collection",
        body: "Choose which alerting apps may contribute. Content never enters the collection.",
      };
    case "Active":
      return {
        title: "Today is still forming",
        body: worldDefinition(state.worlds.selected).formingCopy,
      };
    case "Disconnected":
      return {
        title: "Collection paused",
        body: "Reconnect notification access to continue today’s formation.",
      };
    case "Revoked":
      return {
        title: "Collection paused",
        body: "Notification access was removed. Your existing collection remains here.",
      };
    case "SealedUnobserved":
      return {
        title: "A quiet day",
        body: "No usable signals were observed for this day, so no specimen was formed.",
      };
    case "SealedObserved":
      return today.specimen?.revealedAtEpochMillis !== null
        ? { title: "Specimen revealed", body: "It is now safely stored in your Museum." }
        : {
            title: "Your specimen is ready",
            body: "Reveal the completed specimen from the prior day.",
          };
  }
}

function toggleCombine(current: string[], specimen: ShellSpecimen, all: ShellSpecimen[]): string[] {
  if (current.includes(specimen.id)) return current.filter((id) => id !== specimen.id);
  return canAddToCombine(current, specimen, all) ? [...current, specimen.id] : current;
}

function canAddToCombine(
  current: string[],
  specimen: ShellSpecimen,
  all: ShellSpecimen[],
): boolean {
  if (current.length === 0) {
    return (
      specimen.revealedAtEpochMillis !== null &&
      !specimen.isLocked &&
      specimen.collectibleState !== "CENTRE_PIECE"
    );
  }
  if (current.includes(specimen.id)) return true;
  if (current.length >= 3 || specimen.revealedAtEpochMillis === null || specimen.isLocked)
    return false;
  const seed = all.find((candidate) => candidate.id === current[0]);
  return Boolean(
    seed &&
      specimen.family === seed.family &&
      specimen.tier === seed.tier &&
      specimen.collectibleState === seed.collectibleState,
  );
}

function combineOutput(specimens: ShellSpecimen[]): "RESTORED" | "CENTRE_PIECE" | null {
  if (specimens.length !== 3 || new Set(specimens.map((specimen) => specimen.id)).size !== 3)
    return null;
  const first = specimens[0];
  if (
    !first ||
    specimens.some(
      (specimen) =>
        specimen.revealedAtEpochMillis === null ||
        specimen.isLocked ||
        specimen.family !== first.family ||
        specimen.tier !== first.tier ||
        specimen.collectibleState !== first.collectibleState,
    )
  )
    return null;
  if (first.collectibleState === "ORDINARY") return "RESTORED";
  if (first.collectibleState === "RESTORED") return "CENTRE_PIECE";
  return null;
}

function actionMessage(action: NativeAction, ok: boolean): string {
  if (!ok) return "That action could not be completed. Nothing was changed.";
  switch (action) {
    case "notificationAccess.openSettings":
      return "Notification access settings are open.";
    case "notificationAccess.openAppDetails":
      return "In App info, tap ⋮ and choose Allow restricted settings.";
    case "museum.share":
      return "Android sharing is open.";
    case "museum.combine":
      return "The restored specimen is now in your Museum.";
    case "formation.reveal":
      return "Specimen revealed.";
    default:
      return "Saved on this device.";
  }
}

function parseRoute(hash: string): Route {
  const value = decodeURIComponent(hash.replace(/^#/, ""));
  if (["today", "museum", "community", "more", "worlds"].includes(value)) return value as Route;
  if (/^specimen\/[A-Za-z0-9_-]{1,128}$/.test(value)) return value as Route;
  return "today";
}

function rootFor(route: Route): RootRoute {
  if (route === "museum" || route.startsWith("specimen/")) return "museum";
  if (route === "community") return "community";
  if (route === "more" || route === "worlds") return "more";
  return "today";
}

function navGlyph(route: RootRoute): string {
  return { today: "◌", museum: "◇", community: "○", more: "•••" }[route];
}

function titleCase(value: string): string {
  return value
    .toLowerCase()
    .replace(
      /(^|_)([a-z])/g,
      (_match, space: string, letter: string) => `${space ? " " : ""}${letter.toUpperCase()}`,
    );
}

function formatName(value: string): string {
  return titleCase(value);
}

function formatDate(value: string): string {
  const date = new Date(`${value}T12:00:00`);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(undefined, {
        month: "short",
        day: "numeric",
        year: "numeric",
      }).format(date);
}

function isYesterday(candidate: string, today: string): boolean {
  const current = new Date(`${today}T12:00:00Z`);
  const prior = new Date(current.getTime() - 86_400_000).toISOString().slice(0, 10);
  return candidate === prior;
}

function isShellMetadata(value: unknown): value is ShellMetadata {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  return (
    Object.keys(record).length === 4 &&
    Number.isInteger(record.shellVersion) &&
    Number.isInteger(record.bridgeVersion) &&
    typeof record.revision === "string" &&
    /^[a-f0-9]{64}$/.test(record.revision) &&
    record.entry === "/"
  );
}

export default App;
