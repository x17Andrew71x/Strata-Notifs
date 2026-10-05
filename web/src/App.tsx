import { useEffect, useState } from "react";
import { installResponseListener, type NativeResponse, sendNativeRequest } from "./bridge";

type ShellWorkerRegistrar = {
  register(scriptURL: string, options?: RegistrationOptions): Promise<unknown>;
};

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
    // Android falls back to its bundled shell when the remote worker is unavailable.
  }
}

function App() {
  const [online, setOnline] = useState(navigator.onLine);
  const [access, setAccess] = useState<boolean | null>(null);
  const [appDetailsAction, setAppDetailsAction] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    void registerShellWorker(import.meta.env.PROD);
    const updateNetwork = () => setOnline(navigator.onLine);
    window.addEventListener("online", updateNetwork);
    window.addEventListener("offline", updateNetwork);
    const stop = installResponseListener((response: NativeResponse) => {
      if (response.type === "capabilities.state") {
        setAccess(response.notificationAccess);
        setAppDetailsAction(response.appDetailsAction === true);
      }
      if (response.type === "action.result") {
        if (response.action === "notificationAccess.openAppDetails") {
          setMessage(
            response.ok
              ? "In App info, tap ⋮, choose Allow restricted settings, then return and tap Start collecting."
              : "App info could not be opened. Open Android Settings, then Apps, then Afterchime Dev.",
          );
        } else {
          setMessage(
            response.ok
              ? "Notification access settings are open. If Android blocks the switch, return and use Open app info first."
              : "Settings could not be opened. You can change access in Android Settings.",
          );
        }
      }
    });
    sendNativeRequest("capabilities.get");
    return () => {
      stop();
      window.removeEventListener("online", updateNetwork);
      window.removeEventListener("offline", updateNetwork);
    };
  }, []);

  const begin = () => {
    setMessage(
      "Opening Android notification access. If the switch is blocked, use Open app info first.",
    );
    if (!sendNativeRequest("notificationAccess.openSettings")) {
      setMessage("Open Android Settings and choose Notification access for Afterchime to begin.");
    }
  };

  const openAppInfo = () => {
    setMessage("In App info, tap ⋮, choose Allow restricted settings, then return here.");
    if (!sendNativeRequest("notificationAccess.openAppDetails")) {
      setMessage("Open Android Settings, then Apps, then Afterchime Dev.");
    }
  };

  return (
    <main className="page">
      <div className="ambient ambient-one" aria-hidden="true" />
      <div className="ambient ambient-two" aria-hidden="true" />
      <header className="topbar">
        <a className="wordmark" href="#home" aria-label="Afterchime home">
          <span className="brand-mark" aria-hidden="true">
            ✳
          </span>{" "}
          afterchime
        </a>
        <div className={`connection ${online ? "is-online" : "is-offline"}`} role="status">
          <span className="connection-dot" />{" "}
          {online ? "Ready to explore" : "Your collection is here"}
        </div>
      </header>
      <section className="hero" id="home" aria-labelledby="hero-title">
        <div className="hero-copy">
          <p className="eyebrow">A little wonder, gathered daily</p>
          <h1 id="hero-title">
            Begin your
            <br />
            <em>collection.</em>
          </h1>
          <p className="intro">Turn the rhythm of your day into one-of-a-kind specimens.</p>
          <div className="privacy-note">
            <span aria-hidden="true">◈</span>
            <p>
              Android only offers one broad notification-access switch. On Android 12 and newer,
              fresh installs start with no notification types selected. Conversations, silent, and
              ongoing notifications stay blocked; you choose which alerting apps can contribute.
              Common financial, password, authenticator, and VPN apps are always ignored on-device.
              Afterchime never keeps notification text, names, images, or actions.
            </p>
          </div>
          <button type="button" className="primary-action" onClick={begin}>
            <span>{access === true ? "Choose included apps" : "Choose apps & start"}</span>
            <span aria-hidden="true">↗</span>
          </button>
          <p className="access-state" aria-live="polite">
            {access === true
              ? "Notification access is on. Review which alerting apps may contribute."
              : access === false
                ? "Notification access is off. You can turn it on whenever you are ready."
                : "Your collection is private, personal, and ready when you are."}
          </p>
          {access !== true && (
            <div className="restricted-help">
              <p>
                Installed manually? If Android says Controlled by Restricted Setting, open App info,
                tap ⋮, and choose Allow restricted settings. Then return and try again.
              </p>
              {appDetailsAction && (
                <button type="button" className="secondary-action" onClick={openAppInfo}>
                  Open app info
                </button>
              )}
            </div>
          )}
          {message && (
            <p className="action-message" role="status">
              {message}
            </p>
          )}
          <p className="offline-note">
            {online
              ? "Play at your own pace. No streaks, no pressure."
              : "Offline mode · your collection stays available on this device."}
          </p>
        </div>
        <div
          className="art-panel"
          role="img"
          aria-label="A preview of a specimen waiting to be discovered"
        >
          <div className="orbit orbit-a" />
          <div className="orbit orbit-b" />
          <div className="specimen" aria-hidden="true">
            <span className="band band-1" />
            <span className="band band-2" />
            <span className="band band-3" />
            <span className="band band-4" />
            <span className="band band-5" />
            <span className="band band-6" />
            <span className="gem">
              <i />
            </span>
          </div>
          <div className="sparkle sparkle-a">✦</div>
          <div className="sparkle sparkle-b">·</div>
          <p className="art-caption">
            <span>SPECIMEN NO. 001</span>
            <span>Waiting to be found</span>
          </p>
        </div>
      </section>
      <footer>
        <span>Small moments make a world.</span>
        <span>Made for quiet collecting</span>
      </footer>
    </main>
  );
}

export default App;
