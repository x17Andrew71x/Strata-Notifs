(() => {
  const state = document.getElementById("state");
  const restrictedHelp = document.getElementById("restricted-help");
  const appInfo = document.getElementById("app-info");
  const beginLabel = document.getElementById("begin-label");
  const send = (type) => {
    const bridge = window.AfterchimeBridge;
    if (!bridge) return false;
    bridge.postMessage(JSON.stringify({ version: 1, id: `local_${Date.now()}`, type }));
    return true;
  };
  window.addEventListener("message", (event) => {
    if (typeof event.data !== "string" || event.data.length > 1024) return;
    try {
      const message = JSON.parse(event.data);
      if (message.version !== 1 || typeof message.id !== "string") return;
      if (
        message.type === "capabilities.state" &&
        typeof message.notificationAccess === "boolean"
      ) {
        state.textContent = message.notificationAccess
          ? "Notification access is on. Review which alerting apps may contribute."
          : "Notification access is off. You can turn it on whenever you are ready.";
        restrictedHelp.hidden = message.notificationAccess;
        appInfo.hidden = message.appDetailsAction !== true;
        beginLabel.textContent = message.notificationAccess
          ? "Choose included apps"
          : "Choose apps & start";
      }
      if (message.type === "action.result") {
        if (message.action === "notificationAccess.openAppDetails") {
          state.textContent = message.ok
            ? "In App info, tap ⋮, choose Allow restricted settings, then return and tap Start collecting."
            : "App info could not be opened. Open Android Settings, then Apps, then Afterchime Dev.";
        } else if (message.action === "notificationAccess.openSettings") {
          state.textContent = message.ok
            ? "Notification access settings are open. If Android blocks the switch, return and use Open app info first."
            : "Settings could not be opened. You can change access in Android Settings.";
        }
      }
    } catch (_) {
      /* malformed bridge response ignored */
    }
  });
  send("capabilities.get");
  document.getElementById("begin").addEventListener("click", () => {
    state.textContent = send("notificationAccess.openSettings")
      ? "Opening the included-app and notification-type controls."
      : "Open Android Settings and choose Notification access for Afterchime to begin.";
  });
  document.getElementById("app-info").addEventListener("click", () => {
    state.textContent = send("notificationAccess.openAppDetails")
      ? "In App info, tap ⋮, choose Allow restricted settings, then return here."
      : "Open Android Settings, then Apps, then Afterchime Dev.";
  });
})();
