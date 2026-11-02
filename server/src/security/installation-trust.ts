export class InstallationUntrustedError extends Error {
  public constructor() {
    super("installation_untrusted");
    this.name = "InstallationUntrustedError";
  }
}
