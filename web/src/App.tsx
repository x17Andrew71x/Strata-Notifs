import {
  type CSSProperties,
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
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
import type { DevFeedbackResponseSink } from "./dev-feedback/DevelopmentFeedbackRuntime";
import { referenceNotesFor } from "./relic-reference";
import {
  type Artifact,
  artifactById,
  artifactFor,
  RELIC_VAULT_CATALOG_REVISION,
  relicVaultCatalogUpdate,
  WORLD_ASSET_PATHS,
  WORLD_DEFINITIONS,
  worldDefinition,
} from "./worlds";

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
const ROOT_NAV_ITEMS: readonly { route: RootRoute; label: string }[] = [
  { route: "today", label: "Excavate" },
  { route: "museum", label: "Collection" },
  { route: "community", label: "Exhibits" },
  { route: "more", label: "Settings" },
];
export function brandMarkPath(hostname: string): string {
  return hostname === "appassets.androidplatform.net"
    ? "/assets/afterchime-mark.webp"
    : "/afterchime-mark.webp";
}
const BRAND_MARK_PATH = brandMarkPath(window.location.hostname);
export const TOAST_FADE_START_MS = 2_700;
export const TOAST_DURATION_MS = 3_000;
export const SHELL_STATE_REFRESH_MS = 2_000;
export const IMAGE_RETRY_DELAYS_MS = [250, 750, 1_500, 3_000, 5_000] as const;
export const EXCAVATION_CLEAR_MS = 600;
export const ENERGY_GAIN_PULSE_MS = 700;
const EXCAVATION_TILE_IDS = Array.from(
  { length: 100 },
  (_, tileIndex) => `excavation-tile-${tileIndex}`,
);
const LAUNCH_WORLD: World = "PRIMEVAL_STRATA";
const DEV_FEEDBACK_BUILD = import.meta.env.VITE_DEV_FEEDBACK_ENABLED === "true";
const DevelopmentFeedbackRuntime = DEV_FEEDBACK_BUILD
  ? lazy(() => import("./dev-feedback/DevelopmentFeedbackRuntime"))
  : null;

export function launchSafeDisplayState(state: ShellState): ShellState {
  return {
    ...state,
    worlds: {
      ...state.worlds,
      selected: LAUNCH_WORLD,
      owned: [LAUNCH_WORLD],
      developmentControlsEnabled: false,
    },
  };
}

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

type CatalogCacheStorage = Pick<CacheStorage, "keys" | "open">;

export async function catalogAssetsAreCached(
  revision: number,
  storage: CatalogCacheStorage | undefined = typeof caches === "undefined" ? undefined : caches,
): Promise<boolean> {
  if (!storage) return false;
  try {
    for (const name of await storage.keys()) {
      const stateResponse = await (await storage.open(name)).match("/.afterchime/current");
      if (!stateResponse) continue;
      const state = (await stateResponse.json()) as unknown;
      if (!state || typeof state !== "object") continue;
      const candidate = state as { catalogRevision?: unknown; assets?: unknown };
      if (candidate.catalogRevision !== revision || !Array.isArray(candidate.assets)) continue;
      const paths = new Set(
        candidate.assets.flatMap((asset) =>
          asset &&
          typeof asset === "object" &&
          typeof (asset as { pathname?: unknown }).pathname === "string"
            ? [(asset as { pathname: string }).pathname]
            : [],
        ),
      );
      if (WORLD_ASSET_PATHS.every((pathname) => paths.has(pathname))) return true;
    }
  } catch {
    // A partial or unreadable cache must never authorize a native catalogue revision.
  }
  return false;
}

export async function waitForCatalogAssets(
  revision: number,
  storage: CatalogCacheStorage | undefined = typeof caches === "undefined" ? undefined : caches,
  attempts = 120,
  delayMs = 250,
): Promise<boolean> {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (await catalogAssetsAreCached(revision, storage)) return true;
    if (attempt + 1 < attempts) {
      await new Promise<void>((resolve) => window.setTimeout(resolve, delayMs));
    }
  }
  return false;
}

function App() {
  const devFeedbackResponseSink = useRef<DevFeedbackResponseSink | null>(null);
  const [shellState, setShellState] = useState<ShellState | null>(null);
  const [access, setAccess] = useState<boolean | null>(null);
  const [appDetailsAction, setAppDetailsAction] = useState(false);
  const [message, setMessage] = useState("");
  const [toastExiting, setToastExiting] = useState(false);
  const [metadata, setMetadata] = useState<ShellMetadata | null>(null);
  const [devFeedbackEnabled, setDevFeedbackEnabled] = useState(false);
  const [nativeAppVersion, setNativeAppVersion] = useState("");
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash));
  const [tier, setTier] = useState<Tier | "ALL">("ALL");

  useEffect(() => {
    const remoteOrigin = window.location.hostname !== "appassets.androidplatform.net";
    let active = true;
    let catalogSyncStarted = false;
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
      devFeedbackResponseSink.current?.(response);
      if (response.type === "shell.state") {
        setShellState(response);
        setAccess(response.notificationAccess);
      } else if (response.type === "capabilities.state") {
        setAccess(response.notificationAccess);
        setAppDetailsAction(response.appDetailsAction);
        setDevFeedbackEnabled(DEV_FEEDBACK_BUILD && response.devFeedback === true);
        setNativeAppVersion(response.appVersion ?? "");
        if (response.catalogUpdates && remoteOrigin && !catalogSyncStarted) {
          catalogSyncStarted = true;
          void waitForCatalogAssets(RELIC_VAULT_CATALOG_REVISION).then((ready) => {
            if (active && ready) sendNativeRequest("catalog.update", relicVaultCatalogUpdate());
          });
        }
      } else if (response.type === "action.result" && response.action !== "catalog.update") {
        setMessage(actionMessage(response.action, response.ok));
      }
    });
    const onHashChange = () => setRoute(parseRoute(window.location.hash));
    const refreshState = () => {
      if (document.visibilityState !== "hidden") sendNativeRequest("state.get");
    };
    window.addEventListener("hashchange", onHashChange);
    window.addEventListener("focus", refreshState);
    window.addEventListener("pageshow", refreshState);
    document.addEventListener("visibilitychange", refreshState);
    const refreshInterval = window.setInterval(refreshState, SHELL_STATE_REFRESH_MS);
    sendNativeRequest("capabilities.get");
    sendNativeRequest("state.get");
    return () => {
      active = false;
      stop();
      window.clearInterval(refreshInterval);
      window.removeEventListener("hashchange", onHashChange);
      window.removeEventListener("focus", refreshState);
      window.removeEventListener("pageshow", refreshState);
      document.removeEventListener("visibilitychange", refreshState);
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

  const registerDevFeedbackResponseSink = useCallback((sink: DevFeedbackResponseSink | null) => {
    devFeedbackResponseSink.current = sink;
  }, []);
  const feedbackTool =
    DevelopmentFeedbackRuntime && devFeedbackEnabled && metadata && nativeAppVersion ? (
      <Suspense fallback={null}>
        <DevelopmentFeedbackRuntime
          route={route}
          shellRevision={metadata.revision}
          appVersion={nativeAppVersion}
          registerResponseSink={registerDevFeedbackResponseSink}
        />
      </Suspense>
    ) : null;

  if (!shellState) {
    return (
      <>
        <main className="boot-screen">
          <Brand />
          <div className="boot-orbit" aria-hidden="true" />
          <p>
            {window.AfterchimeBridge
              ? "Loading your collection…"
              : "Open Afterchime on Android to view your collection."}
          </p>
        </main>
        {feedbackTool}
      </>
    );
  }

  const displayState = launchSafeDisplayState(shellState);
  const effectiveAccess = access ?? displayState.notificationAccess;
  if (!displayState.preferences.onboardingComplete) {
    return (
      <>
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
        {feedbackTool}
      </>
    );
  }

  const selectedSpecimenId = route.startsWith("specimen/") ? route.slice("specimen/".length) : null;
  const selectedSpecimen = selectedSpecimenId
    ? (displayState.museum.specimens.find((specimen) => specimen.id === selectedSpecimenId) ?? null)
    : null;
  const selectedStack = selectedSpecimen
    ? stackMuseumSpecimens(displayState.worlds.selected, displayState.museum.specimens).find(
        ({ specimens }) => specimens.some(({ id }) => id === selectedSpecimen.id),
      )
    : null;
  const selectedRoot = rootFor(route);
  const selectedWorld = worldDefinition(displayState.worlds.selected);

  return (
    <div
      className={`app-shell route-${selectedRoot} world-${selectedWorld.theme}${displayState.preferences.highContrastEnabled ? " high-contrast" : ""}${
        displayState.preferences.reduceMotionEnabled ? " reduce-motion" : ""
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
            state={displayState}
            onAction={(type, payload) =>
              act(
                type,
                payload,
                type === "formation.reveal"
                  ? "Revealing…"
                  : type === "excavation.dig"
                    ? "Brushing away earth…"
                    : "Opening settings…",
              )
            }
          />
        )}
        {route === "museum" && (
          <Museum
            state={displayState}
            tier={tier}
            onTier={setTier}
            onOpen={(id) => navigate(`specimen/${id}`)}
          />
        )}
        {route === "community" && <Community />}
        {route === "more" && (
          <More
            state={displayState}
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
        {route === "worlds" && <Worlds state={displayState} onBack={() => history.back()} />}
        {selectedSpecimenId && (
          <SpecimenDetail
            specimen={selectedSpecimen}
            world={displayState.worlds.selected}
            ownedCount={selectedStack?.specimens.length ?? 0}
            onBack={() => history.back()}
          />
        )}
      </main>
      {!selectedSpecimenId && route !== "worlds" && (
        <nav className="bottom-nav" aria-label="Main navigation">
          {ROOT_NAV_ITEMS.map(({ route: item, label }) => (
            <button
              type="button"
              key={item}
              className={selectedRoot === item ? "selected" : ""}
              aria-current={selectedRoot === item ? "page" : undefined}
              onClick={() => navigate(item)}
            >
              <span className="bottom-nav-icon" aria-hidden="true">
                <NavIcon route={item} />
              </span>
              <span className="bottom-nav-label">{label}</span>
            </button>
          ))}
        </nav>
      )}
      {message && (
        <div className={`toast${toastExiting ? " exiting" : ""}`} role="status">
          {message}
        </div>
      )}
      {feedbackTool}
    </div>
  );
}

function Brand() {
  return (
    <div className="brand">
      <img className="brand-mark" src={BRAND_MARK_PATH} alt="" draggable="false" />
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
          <p>
            Online tools, app-improvement reports, and daily activity totals stay off unless you
            turn them on.
          </p>
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

function Today({
  state,
  onAction,
}: {
  state: ShellState;
  onAction(type: NativeAction, payload?: Record<string, unknown>): void;
}) {
  const specimen = state.today.specimen;
  const excavation = state.today.excavation;
  const copy = todayCopy(state);
  return (
    <section className="screen today-screen" aria-labelledby="today-title">
      <div className="screen-heading">
        <p className="eyebrow">{formatDate(state.today.localDate)}</p>
        <h1 id="today-title">{copy.title}</h1>
        {copy.body && <p>{copy.body}</p>}
      </div>
      <div className="formation-stage">
        {specimen && specimen.revealedAtEpochMillis !== null ? (
          <SpecimenVisual specimen={specimen} world={state.worlds.selected} revealMotion />
        ) : excavation && artifactById(excavation.artifactId) ? (
          <Excavation
            excavation={excavation}
            world={state.worlds.selected}
            digInFlight={state.today.digInFlight ?? false}
            onDig={(tileIndex) => onAction("excavation.dig", { tileIndex })}
          />
        ) : (
          <Formation
            layers={state.today.layers}
            ready={state.today.primaryAction === "REVEAL"}
            world={state.worlds.selected}
          />
        )}
      </div>
      {state.today.primaryAction === "ENABLE_ACCESS" &&
        state.today.observation !== "AwaitingAccess" && (
          <button
            type="button"
            className="primary wide"
            onClick={() => onAction("notificationAccess.openSettings")}
          >
            Open notification settings
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

function Excavation({
  excavation,
  world,
  digInFlight,
  onDig,
}: {
  excavation: NonNullable<ShellState["today"]["excavation"]>;
  world: World;
  digInFlight: boolean;
  onDig(tileIndex: number): void;
}) {
  const previousDugTiles = useRef(new Set(excavation.dugTiles));
  const clearingTimers = useRef(new Map<number, number>());
  const energyTimer = useRef<number | null>(null);
  const previousEnergy = useRef(excavation.energyAvailable);
  const [clearingTiles, setClearingTiles] = useState<Set<number>>(() => new Set());
  const [energyGained, setEnergyGained] = useState(false);

  useLayoutEffect(() => {
    const currentDugTiles = new Set(excavation.dugTiles);
    const newlyDugTiles = excavation.dugTiles.filter(
      (tileIndex) => !previousDugTiles.current.has(tileIndex),
    );
    previousDugTiles.current = currentDugTiles;
    if (newlyDugTiles.length === 0) return;

    setClearingTiles((current) => new Set([...current, ...newlyDugTiles]));
    for (const tileIndex of newlyDugTiles) {
      const existingTimer = clearingTimers.current.get(tileIndex);
      if (existingTimer !== undefined) window.clearTimeout(existingTimer);
      clearingTimers.current.set(
        tileIndex,
        window.setTimeout(() => {
          setClearingTiles((current) => {
            if (!current.has(tileIndex)) return current;
            const next = new Set(current);
            next.delete(tileIndex);
            return next;
          });
          clearingTimers.current.delete(tileIndex);
        }, EXCAVATION_CLEAR_MS),
      );
    }
  }, [excavation.dugTiles]);

  useLayoutEffect(() => {
    const previous = previousEnergy.current;
    const gained = excavation.energyAvailable > previous;
    previousEnergy.current = excavation.energyAvailable;
    if (!gained) {
      if (excavation.energyAvailable < previous && energyTimer.current !== null) {
        window.clearTimeout(energyTimer.current);
        energyTimer.current = null;
        setEnergyGained(false);
      }
      return;
    }

    setEnergyGained(true);
    if (energyTimer.current !== null) window.clearTimeout(energyTimer.current);
    energyTimer.current = window.setTimeout(() => {
      setEnergyGained(false);
      energyTimer.current = null;
    }, ENERGY_GAIN_PULSE_MS);
  }, [excavation.energyAvailable]);

  useEffect(
    () => () => {
      for (const timer of clearingTimers.current.values()) window.clearTimeout(timer);
      if (energyTimer.current !== null) window.clearTimeout(energyTimer.current);
    },
    [],
  );

  const artifact =
    world === "PRIMEVAL_STRATA"
      ? artifactById(excavation.artifactId)
      : artifactFor(world, excavation.artifactId);
  if (!artifact) return null;
  const dugTiles = new Set(excavation.dugTiles);
  const tileCount = excavation.gridColumns * excavation.gridRows;
  const canAfford = excavation.energyAvailable >= excavation.tileEnergyCost;
  return (
    <fieldset
      className="excavation"
      aria-label={`Excavate today’s concealed fossil. ${excavation.energyAvailable} energy available.`}
    >
      <RetryingImage
        key={artifact.excavationImage}
        className="excavation-artifact"
        src={artifact.excavationImage}
      />
      <div
        className="excavation-grid"
        style={
          {
            "--excavation-columns": excavation.gridColumns,
            "--excavation-rows": excavation.gridRows,
          } as CSSProperties
        }
      >
        {EXCAVATION_TILE_IDS.slice(0, tileCount).map((tileId) => {
          const tileIndex = Number(tileId.slice("excavation-tile-".length));
          const dug = dugTiles.has(tileIndex);
          return (
            <button
              type="button"
              key={tileId}
              className={`excavation-tile${dug ? " dug" : ""}${clearingTiles.has(tileIndex) ? " is-clearing" : ""}`}
              data-tile-index={tileIndex}
              aria-label={
                dug
                  ? `Excavation tile ${tileIndex + 1} cleared`
                  : `Excavate tile ${tileIndex + 1} for ${excavation.tileEnergyCost} energy`
              }
              aria-hidden={dug ? "true" : undefined}
              tabIndex={dug ? -1 : 0}
              disabled={dug || digInFlight || !canAfford}
              onClick={() => onDig(tileIndex)}
            />
          );
        })}
      </div>
      <div className="excavation-hud">
        <div>
          <span>Dig energy</span>
          <strong className={energyGained ? "energy-gained" : undefined}>
            {excavation.energyAvailable}
          </strong>
        </div>
        <div>
          <span>Excavated</span>
          <strong>
            {excavation.dugTiles.length}/{tileCount}
          </strong>
        </div>
      </div>
      <p className="excavation-capture">
        {excavation.energyEarned} energy earned · {excavation.tileEnergyCost} per tile
      </p>
    </fieldset>
  );
}

function RetryingImage({ className, src }: { className: string; src: string }) {
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!failed || loaded) return;
    const delay = IMAGE_RETRY_DELAYS_MS[Math.min(attempt, IMAGE_RETRY_DELAYS_MS.length - 1)];
    const retry = window.setTimeout(() => {
      setFailed(false);
      setAttempt((current) => current + 1);
    }, delay);
    return () => window.clearTimeout(retry);
  }, [attempt, failed, loaded]);

  return (
    <img
      key={`${src}-${attempt}`}
      className={`${className}${loaded ? " is-loaded" : ""}`}
      src={src}
      alt=""
      draggable="false"
      decoding="async"
      fetchPriority="high"
      onLoad={() => {
        setLoaded(true);
        setFailed(false);
      }}
      onError={() => {
        setLoaded(false);
        setFailed(true);
      }}
    />
  );
}

function Formation({
  layers,
  ready,
  world,
}: {
  layers: ShellState["today"]["layers"];
  ready: boolean;
  world: World;
}) {
  const visible = layers.slice(-18);
  const occurrences = new Map<string, number>();
  const keyedVisible = visible.map((layer) => {
    const signature = `${layer.localHour}-${layer.category}-${layer.sourceColourRgb}`;
    const occurrence = occurrences.get(signature) ?? 0;
    occurrences.set(signature, occurrence + 1);
    return { key: `${signature}-${occurrence}`, layer };
  });
  const theme = worldDefinition(world).theme;
  return (
    <div
      className={`formation formation-${theme} ${ready ? "ready" : ""}`}
      role="img"
      aria-label="Today's notifications settling into a forming specimen"
      style={{ "--formation-progress": Math.min(visible.length / 18, 1) } as CSSProperties}
    >
      <div className="formation-bed" aria-hidden="true">
        {keyedVisible.map(({ key, layer }, index) => {
          const colour = `#${(layer.sourceColourRgb & 0xffffff).toString(16).padStart(6, "0")}`;
          const newest = index === keyedVisible.length - 1;
          return (
            <span
              key={key}
              className={`formation-layer${newest ? " is-newest" : ""}`}
              data-category={layer.category}
              data-newest={newest ? "true" : undefined}
              style={
                {
                  "--layer-colour": colour,
                  "--layer-index": index,
                  "--layer-inset": `${[1, 0, 2, 0, 1][index % 5]}%`,
                  "--layer-shift": `${[-1, 0.5, -0.5, 1, 0][index % 5]}%`,
                } as CSSProperties
              }
            />
          );
        })}
      </div>
      <FormationImprint theme={theme} />
      {visible.length === 0 && (
        <span className="formation-empty">Quiet days still leave a trace.</span>
      )}
    </div>
  );
}

function FormationImprint({ theme }: { theme: ReturnType<typeof worldDefinition>["theme"] }) {
  return (
    <svg className={`formation-imprint imprint-${theme}`} viewBox="0 0 100 100" aria-hidden="true">
      {theme === "relic" && (
        <>
          <path
            className="imprint-shadow"
            d="M50 10C70 9 88 27 90 48c2 21-15 39-36 42-22 3-42-13-44-35C8 34 25 12 50 10Z"
          />
          <path
            className="imprint-line"
            d="M50 48.2 50.5 47.8 51.2 47.5 52.1 47.5 53 47.7 53.9 48.3 54.7 49.1 55.2 50.2 55.5 51.5 55.5 53 55 54.5 54.1 55.9 52.8 57.2 51.2 58.1 49.3 58.7 47.2 58.7 45 58.3 42.9 57.3 41 55.7 39.4 53.7 38.4 51.2 37.9 48.5 38.1 45.6 39 42.7 40.7 40.1 42.9 37.8 45.8 36 49.1 34.9 52.6 34.6 56.2 35.2 59.8 36.6 62.9 38.8 65.6 41.9 67.5 45.5 68.6 49.6 68.7 54 67.7 58.3 65.7 62.5 62.8 66.1 59 69 54.5 71.1 49.5 72 44.4 71.8 39.3 70.4 34.6 67.8 30.6 64.1 27.4 59.5 25.4 54.2 24.6 48.4 25.1 42.5 27.1 36.8 30.3 31.5 34.8 27.1 40.3 23.8 46.4 21.8 53 21.2 59.7 22.2 66 24.7 71.7 28.7 76.4 34 79.9 40.3 81.8 47.3 82 54.7 80.6 62.1 77.4 69 72.7 75.2 66.6 80.1 59.4 83.6 51.5 85.3 43.3 85.2 35.2 83.2 27.7 79.3 21.2 73.8 16.1 66.8 12.7 58.7 11.2 50"
          />
          <path
            className="imprint-detail"
            d="M50 23 50 11.5M61.7 25.7 66.7 15.3M71.1 33.2 80.1 26M76.3 44 87.5 41.4M76.3 56 87.5 58.6M71.1 66.8 80.1 74M61.7 74.3 66.7 84.7M50 77 50 88.5M38.3 74.3 33.3 84.7M28.9 66.8 19.9 74M23.7 56 12.5 58.6M23.7 44 12.5 41.4M28.9 33.2 19.9 26M38.3 25.7 33.3 15.3"
          />
        </>
      )}
    </svg>
  );
}

function Museum({
  state,
  tier,
  onTier,
  onOpen,
}: {
  state: ShellState;
  tier: Tier | "ALL";
  onTier(value: Tier | "ALL"): void;
  onOpen(id: string): void;
}) {
  const world = worldDefinition(state.worlds.selected);
  const stacks = stackMuseumSpecimens(state.worlds.selected, state.museum.specimens).filter(
    (stack) => tier === "ALL" || stack.artifact.tier === tier,
  );
  return (
    <section className="screen museum-screen" aria-labelledby="museum-title">
      <div className="screen-heading compact">
        <p className="eyebrow">{world.eyebrow}</p>
        <h1 id="museum-title">{world.collectionName}</h1>
      </div>

      <fieldset className="filter-row" aria-label="Filter by rarity">
        {(["ALL", "COMMON", "UNCOMMON", "RARE"] as const).map((value) => (
          <button
            type="button"
            key={value}
            className={tier === value ? "selected" : ""}
            onClick={() => onTier(value)}
          >
            {titleCase(value)}
          </button>
        ))}
      </fieldset>
      {stacks.length === 0 ? (
        <div className="empty-state">
          <SpecimenVisual decorative compact />
          <h2>Your shelves are waiting.</h2>
          <p>Reveal a daily specimen and it will appear here.</p>
        </div>
      ) : (
        <div className="specimen-grid">
          {stacks.map((stack) => {
            const { artifact, representative, specimens } = stack;
            return (
              <button
                type="button"
                key={artifact.id}
                className={`specimen-card rarity-${artifact.tier.toLowerCase()}`}
                onClick={() => onOpen(representative.id)}
              >
                <SpecimenVisual
                  specimen={representative}
                  world={state.worlds.selected}
                  displayTier={artifact.tier}
                  compact
                />
                <strong>{artifact.name}</strong>
                <span>{formatName(artifact.tier)}</span>
                {specimens.length > 1 && (
                  <small className="specimen-count">{specimens.length} owned</small>
                )}
                <small className="specimen-date">
                  {representative.anchoredLocalDate
                    ? formatDate(representative.anchoredLocalDate)
                    : "Restored"}
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
  ownedCount,
  onBack,
}: {
  specimen: ShellSpecimen | null;
  world: World;
  ownedCount: number;
  onBack(): void;
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
  const artifact = artifactForSpecimen(world, specimen);
  return (
    <section className="screen detail-screen" aria-labelledby="detail-title">
      <button type="button" className="back-button" onClick={onBack}>
        ← Back
      </button>
      <p className="eyebrow">{worldDefinition(world).name}</p>
      <h1 id="detail-title">{artifact.name}</h1>
      <div className="detail-card">
        <SpecimenVisual
          specimen={specimen}
          world={world}
          displayTier={artifact.tier}
          showCaption={false}
        />
        <div className="detail-copy">
          <dl>
            <div>
              <dt>Rarity</dt>
              <dd>{formatName(artifact.tier)}</dd>
            </div>
            <div>
              <dt>{specimen.anchoredLocalDate ? "Sealed" : "Restored"}</dt>
              <dd>
                {specimen.anchoredLocalDate
                  ? formatDate(specimen.anchoredLocalDate)
                  : formatName(specimen.collectibleState)}
              </dd>
            </div>
          </dl>
          <ReferenceSeries artifactId={artifact.id} ownedCount={ownedCount} />
        </div>
      </div>
    </section>
  );
}

function ReferenceSeries({ artifactId, ownedCount }: { artifactId: string; ownedCount: number }) {
  const notes = referenceNotesFor(artifactId);
  if (notes.length === 0) return null;
  return (
    <section className="reference-series" aria-labelledby="reference-series-title">
      <div className="reference-series-heading">
        <h2 id="reference-series-title">Reference series</h2>
        <strong>{ownedCount} owned</strong>
      </div>
      <ol className="reference-notes">
        {notes.map((note) => {
          const unlocked = ownedCount >= note.requiredOwned;
          return (
            <li
              key={note.requiredOwned}
              className={`reference-note ${unlocked ? "unlocked" : "locked"}`}
            >
              <div className="reference-note-heading">
                <strong>{note.label}</strong>
                <span className="reference-note-threshold">{note.requiredOwned} specimens</span>
              </div>
              {unlocked ? (
                <>
                  <p>{note.text}</p>
                  <cite title={note.sourceUrl}>Source · {note.source}</cite>
                </>
              ) : (
                <span className="reference-lock">Locked</span>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

function Community() {
  return (
    <section className="screen placeholder-screen" aria-labelledby="exhibits-title">
      <p className="eyebrow">Optional and private</p>
      <h1 id="exhibits-title">Exhibits</h1>
      <p>Nothing leaves this device unless you explicitly enable an online feature.</p>
      <div className="coming-soon">Shared exhibits are not enabled yet.</div>
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
    <section className="screen more-screen" aria-labelledby="settings-title">
      <div className="screen-heading compact">
        <p className="eyebrow">Local controls</p>
        <h1 id="settings-title">Settings</h1>
      </div>
      <div className="settings-group">
        <button type="button" className="settings-link" onClick={onWorlds}>
          <span>
            <strong>Series</strong>
            <small>Relic Vault · Included for launch</small>
          </span>
          <span>›</span>
        </button>
        <button type="button" className="settings-link" onClick={onOpenSettings}>
          <span>
            <strong>Notification access</strong>
            <small>
              Choose which apps may add layers ·{" "}
              {state.notificationAccess ? "Collecting" : "Paused"}
            </small>
          </span>
          <span>›</span>
        </button>
      </div>
      <div className="settings-group">
        <Toggle
          label="Online features"
          detail="Connect to optional online tools; your collection still works offline"
          checked={preferences.onlineFeaturesEnabled}
          onChange={(value) => onPreference("onlineFeaturesEnabled", value)}
        />
        <Toggle
          label="Help improve Afterchime"
          detail="Send which parts of Afterchime are used and basic device details such as app version, Android version, language, and phone or tablet. Never includes notification text, app names, senders, contacts, or media."
          checked={preferences.productAnalyticsEnabled}
          disabled={!preferences.onlineFeaturesEnabled}
          onChange={(value) => onPreference("productAnalyticsEnabled", value)}
        />
        <Toggle
          label="Share daily activity totals"
          detail="Send one day’s counts by broad type and hour, plus whether collection was active all day. Never includes notification text, app names, senders, contacts, or media."
          checked={preferences.notificationAggregateSharingEnabled}
          disabled={!preferences.onlineFeaturesEnabled}
          onChange={(value) => onPreference("notificationAggregateSharingEnabled", value)}
        />
      </div>
      <div className="settings-group">
        <Toggle
          label="Reduce motion"
          detail="Turn off excavation and reveal animations"
          checked={preferences.reduceMotionEnabled}
          onChange={(value) => onPreference("reduceMotionEnabled", value)}
        />
        <Toggle
          label="High contrast"
          detail="Increase separation between text, controls, and surfaces"
          checked={preferences.highContrastEnabled}
          onChange={(value) => onPreference("highContrastEnabled", value)}
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

function Worlds({ state, onBack }: { state: ShellState; onBack(): void }) {
  return (
    <section className="screen worlds-screen" aria-labelledby="worlds-title">
      <button type="button" className="back-button" onClick={onBack}>
        ← Back
      </button>
      <p className="eyebrow">Two authored series</p>
      <h1 id="worlds-title">Series</h1>
      <p className="lead-small">Relic Vault is available now. Signal Ruins is under development.</p>
      <div className="world-list">
        {WORLD_DEFINITIONS.map((world) => {
          const selected = state.worlds.selected === world.id;
          const locked = !world.baseline;
          return (
            <article
              className={`world-card theme-${world.theme}${selected ? " selected" : ""}${locked ? " locked" : ""}`}
              key={world.id}
              aria-disabled={locked ? "true" : undefined}
            >
              <div className="world-preview">
                {world.baseline ? (
                  <SpecimenVisual decorative compact world={LAUNCH_WORLD} />
                ) : (
                  <img className="world-card-image" src={world.cardImage} alt="" />
                )}
                {locked && (
                  <span className="world-lock-overlay" aria-hidden="true">
                    <SeriesLockIcon />
                  </span>
                )}
              </div>
              <div className="world-copy">
                <p className="world-eyebrow">{world.eyebrow}</p>
                <h2>{world.name}</h2>
                <p>{world.description}</p>
                <p className="world-status">
                  <span>{selected ? "Active series" : "Under development"}</span>
                </p>
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}

function SeriesLockIcon() {
  return (
    <svg
      className="world-lock-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="5" y="10" width="14" height="10" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </svg>
  );
}

function artifactForSpecimen(world: World, specimen: ShellSpecimen) {
  return world === "PRIMEVAL_STRATA"
    ? (artifactById(specimen.catalogItemId) ?? artifactFor(world, specimen.id))
    : artifactFor(world, specimen.id);
}

type SpecimenStack = {
  artifact: Artifact;
  representative: ShellSpecimen;
  specimens: ShellSpecimen[];
};

function stackMuseumSpecimens(world: World, specimens: ShellSpecimen[]): SpecimenStack[] {
  const stacks = new Map<string, SpecimenStack>();
  const revealed = specimens
    .filter((specimen) => specimen.revealedAtEpochMillis !== null)
    .sort((a, b) => b.createdAtEpochMillis - a.createdAtEpochMillis || a.id.localeCompare(b.id));

  for (const specimen of revealed) {
    const artifact = artifactForSpecimen(world, specimen);
    const stack = stacks.get(artifact.id);
    if (stack) stack.specimens.push(specimen);
    else stacks.set(artifact.id, { artifact, representative: specimen, specimens: [specimen] });
  }
  return [...stacks.values()];
}

function SpecimenVisual({
  specimen,
  world = "PRIMEVAL_STRATA",
  decorative = false,
  compact = false,
  showCaption = true,
  revealMotion = false,
  displayTier,
}: {
  specimen?: ShellSpecimen;
  world?: World;
  decorative?: boolean;
  compact?: boolean;
  showCaption?: boolean;
  revealMotion?: boolean;
  displayTier?: Tier;
}) {
  const artifact = specimen
    ? artifactForSpecimen(world, specimen)
    : artifactFor(world, "decorative");
  const definition = worldDefinition(world);
  const visualTier = displayTier ?? specimen?.tier ?? artifact.tier;
  const accessibility = decorative
    ? { "aria-hidden": true }
    : {
        role: "img" as const,
        "aria-label": `${formatName(visualTier)} ${artifact.name} artifact from ${definition.name}`,
      };
  return (
    <figure
      className={`specimen-visual theme-${definition.theme} rarity-${visualTier.toLowerCase()}${compact ? " compact" : ""}${revealMotion ? " specimen-reveal" : ""}`}
      {...accessibility}
    >
      <img src={artifact.museumImage} alt="" draggable="false" />
      {!compact && showCaption && <figcaption>{artifact.name}</figcaption>}
    </figure>
  );
}

function todayCopy(state: ShellState): { title: string; body: string | null } {
  const { today } = state;
  if (
    today.excavation?.completedAtEpochMillis !== null &&
    today.excavation?.completedAtEpochMillis !== undefined &&
    today.specimen?.revealedAtEpochMillis != null
  ) {
    return {
      title: "Today’s fossil is secured",
      body: "The completed discovery is now displayed on its Museum pedestal.",
    };
  }
  if (today.observation === "Active" && today.excavation) {
    return {
      title: "Excavate today’s fossil",
      body: null,
    };
  }
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
        body: null,
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
    case "excavation.dig":
      return "Excavation updated.";
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

function NavIcon({ route }: { route: RootRoute }) {
  const common = {
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.8,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
  };
  switch (route) {
    case "today":
      return (
        <svg {...common} aria-hidden="true" focusable="false">
          <path d="m15.5 4.5 4 4" />
          <path d="m17.5 6.5-8 8" />
          <path d="m9.5 13.5-5-1-2 8 8-2-1-5Z" />
        </svg>
      );
    case "museum":
      return (
        <svg {...common} aria-hidden="true" focusable="false">
          <path d="M3 9h18L12 4 3 9Z" />
          <path d="M5 20h14" />
          <path d="M6 9v8M10 9v8M14 9v8M18 9v8" />
        </svg>
      );
    case "community":
      return (
        <svg {...common} aria-hidden="true" focusable="false">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <circle cx="9" cy="10" r="2" />
          <path d="m4 17 5-4 4 3 3-2 4 3" />
        </svg>
      );
    case "more":
      return (
        <svg {...common} aria-hidden="true" focusable="false">
          <path d="M4 6h7M15 6h5M4 12h2M10 12h10M4 18h10M18 18h2" />
          <circle cx="13" cy="6" r="2" />
          <circle cx="8" cy="12" r="2" />
          <circle cx="16" cy="18" r="2" />
        </svg>
      );
  }
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
