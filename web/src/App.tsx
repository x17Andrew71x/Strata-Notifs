import { useEffect, useState } from "react";
import { installResponseListener, type NativeResponse, sendNativeRequest } from "./bridge";

function App() {
  const [access, setAccess] = useState<boolean | null>(null);
  const [online, setOnline] = useState(navigator.onLine);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if ("serviceWorker" in navigator)
      void navigator.serviceWorker
        .register("/service-worker.js", { scope: "/" })
        .catch(() => undefined);
    const updateNetwork = () => setOnline(navigator.onLine);
    window.addEventListener("online", updateNetwork);
    window.addEventListener("offline", updateNetwork);
    const stop = installResponseListener((response: NativeResponse) => {
      if (response.type === "capabilities.state") setAccess(response.notificationAccess);
      if (response.type === "action.result") {
        setMessage(
          response.ok
            ? "Notification access settings are open."
            : "Settings could not be opened. You can change access in Android Settings.",
        );
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
      "Afterchime only uses notification timing and broad categories. Message content stays private on this device.",
    );
    if (!sendNativeRequest("notificationAccess.openSettings")) {
      setMessage("Open Android Settings and choose Notification access for Afterchime to begin.");
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
              Afterchime never reads your messages or notification text. It only uses when
              notifications arrive and their general type to grow your collection. Everything stays
              on this device.
            </p>
          </div>
          <button type="button" className="primary-action" onClick={begin}>
            <span>Start collecting</span>
            <span className="arrow" aria-hidden="true">
              ↗
            </span>
          </button>
          <p className="access-state" aria-live="polite">
            {access === true
              ? "Notification access is on. Your first specimen is taking shape."
              : access === false
                ? "Notification access is off. You can turn it on whenever you are ready."
                : "Your collection is private, personal, and ready when you are."}
          </p>
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
