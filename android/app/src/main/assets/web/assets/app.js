(() => {
  const state = document.getElementById("state");
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
          ? "Notification access is on. Your first specimen is taking shape."
          : "Notification access is off. You can turn it on whenever you are ready.";
      }
      if (
        message.type === "action.result" &&
        message.action === "notificationAccess.openSettings"
      ) {
        state.textContent = message.ok
          ? "Notification access settings are open."
          : "Settings could not be opened. You can change access in Android Settings.";
      }
    } catch (_) {
      /* malformed bridge response ignored */
    }
  });
  send("capabilities.get");
  document.getElementById("begin").addEventListener("click", () => {
    state.textContent = send("notificationAccess.openSettings")
      ? "Afterchime only uses notification timing and broad categories. Message content stays private on this device."
      : "Open Android Settings and choose Notification access for Afterchime to begin.";
  });
})();
