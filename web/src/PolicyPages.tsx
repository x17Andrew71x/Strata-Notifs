import { type ReactNode, useEffect } from "react";

export type PolicyPageKind = "privacy" | "support" | "delete-account";
export const POLICY_EFFECTIVE_DATE = "November 5, 2026";
export const PRIVACY_CONTACT_EMAIL = "privacy@techfullymade.com";
export const SUPPORT_CONTACT_EMAIL = "support@techfullymade.com";

export function publicPolicyKind(pathname: string): PolicyPageKind | null {
  if (pathname === "/privacy") return "privacy";
  if (pathname === "/support") return "support";
  if (pathname === "/delete-account") return "delete-account";
  return null;
}

export function PublicPolicyPage({
  kind,
  brandMarkPath,
}: {
  kind: PolicyPageKind;
  brandMarkPath: string;
}) {
  useEffect(() => {
    const title =
      kind === "privacy" ? "Privacy policy" : kind === "support" ? "Support" : "Delete account";
    document.title = `${title} | Afterchime`;
    document.documentElement.classList.remove("app-loading");
  }, [kind]);

  return (
    <div className="public-policy-shell">
      <header className="public-policy-header">
        <a className="brand" href="/" aria-label="Afterchime home">
          <img className="brand-mark" src={brandMarkPath} alt="" draggable="false" />
          <strong>Afterchime</strong>
        </a>
        <nav aria-label="Policy pages">
          <a aria-current={kind === "privacy" ? "page" : undefined} href="/privacy">
            Privacy
          </a>
          <a aria-current={kind === "support" ? "page" : undefined} href="/support">
            Support
          </a>
          <a aria-current={kind === "delete-account" ? "page" : undefined} href="/delete-account">
            Delete account
          </a>
        </nav>
      </header>
      <main className="policy-document">
        <PolicyContent kind={kind} publicPage />
      </main>
      <footer className="policy-footer">Afterchime · TechfullyMade</footer>
    </div>
  );
}

export function InAppPolicyPage({
  kind,
  canDeleteAccount,
  onDeleteAccount,
}: {
  kind: PolicyPageKind;
  canDeleteAccount: boolean;
  onDeleteAccount(): void;
}) {
  return (
    <section className="screen policy-screen" aria-label={policyTitle(kind)}>
      <PolicyContent
        kind={kind}
        publicPage={false}
        canDeleteAccount={canDeleteAccount}
        onDeleteAccount={onDeleteAccount}
      />
    </section>
  );
}

function PolicyContent({
  kind,
  publicPage,
  canDeleteAccount = false,
  onDeleteAccount = () => undefined,
}: {
  kind: PolicyPageKind;
  publicPage: boolean;
  canDeleteAccount?: boolean;
  onDeleteAccount?: () => void;
}) {
  if (kind === "support") return <SupportContent />;
  if (kind === "delete-account") {
    return (
      <AccountDataContent
        publicPage={publicPage}
        canDeleteAccount={canDeleteAccount}
        onDeleteAccount={onDeleteAccount}
      />
    );
  }
  return <PrivacyContent />;
}

function PrivacyContent() {
  return (
    <article>
      <PolicyHeading eyebrow="TechfullyMade" title="Privacy policy" />
      <p className="policy-updated">Effective {POLICY_EFFECTIVE_DATE}</p>
      <p>
        Afterchime is a private-by-construction Android collection app. Its daily excavation works
        without an account or network connection. This policy explains what the app accesses, what
        stays on your device, and what is sent only when you turn on optional online features.
      </p>

      <PolicySection title="Notification access">
        <p>
          Android gives notification-listener apps broad technical access. Afterchime immediately
          reduces eligible notifications to limited timing, broad category, and device-local colour
          signals used for excavation. Notification text, titles, senders, contacts, actions, and
          media are never stored or transmitted. Raw app package names and notification keys are not
          stored, logged, analysed, or sent to TechfullyMade.
        </p>
        <p>
          Financial, payment, cryptocurrency-wallet, password-manager, authenticator, VPN,
          conversation, silent, ongoing, group-summary, and Afterchime notifications are excluded by
          local rules before they can contribute.
        </p>
      </PolicySection>

      <PolicySection title="Data kept on your device">
        <ul>
          <li>Reduced notification timing, broad category, and local source-colour records.</li>
          <li>
            Daily excavation state, fossils, collection history, preferences, and accessibility
            settings.
          </li>
          <li>
            An encrypted anonymous online credential; queued uploads exist only while online
            features are enabled.
          </li>
        </ul>
        <p>
          Individual reduced notification records are removed when their local day is sealed. A
          daily summary and your private collection remain on the device until you clear the app’s
          data or use an approved deletion control.
        </p>
      </PolicySection>

      <PolicySection title="Optional online data">
        <p>
          Online features start off. If you turn them on, Afterchime creates a pseudonymous
          installation identity and may process the following data for the choices you enable:
        </p>
        <ul>
          <li>
            App version, Android version, language, broad device class, local date, and time-zone
            offset.
          </li>
          <li>Approved app-use events, such as screens opened and feature outcomes.</li>
          <li>
            Daily notification totals by broad category and hour, never individual notification
            content.
          </li>
          <li>
            Exhibit contributions, eligibility, unlocks, and account/session security records.
          </li>
        </ul>
        <p>
          Online features, product-use reporting, and daily-total sharing can be switched off. The
          server scopes are then revoked and future uploads stop. The encrypted anonymous credential
          remains on the device only so the same identity can be re-enabled or an authenticated
          deletion can finish; deleting the account or clearing app data removes it. The app does
          not automatically collect an advertising ID, precise location, contacts, phone number,
          email address, hardware serial number, microphone data, camera data, notification content,
          or raw app inventory in the current release.
        </p>
      </PolicySection>

      <PolicySection title="Support and deletion requests">
        <p>
          If you contact TechfullyMade by email, we receive the email address, message, and any
          attachments you choose to send. We use that information only to answer the request,
          investigate the reported problem, or complete and document a deletion request. Support
          correspondence is retained only as long as reasonably needed for those purposes and
          applicable security or legal obligations.
        </p>
      </PolicySection>

      <PolicySection title="How data is used and shared">
        <p>
          TechfullyMade uses optional online data only to operate sync and Exhibits, protect the
          service, understand product reliability and use, and restore eligible purchases or awards.
          We do not sell personal or sensitive data and do not use it for advertising.
        </p>
        <p>
          Railway provides application hosting and managed PostgreSQL as a service provider. Google
          Play handles app distribution and future Play purchases under Google’s own terms. We do
          not provide Afterchime data to unrelated third parties for their own marketing.
        </p>
      </PolicySection>

      <PolicySection title="Retention and deletion">
        <ul>
          <li>
            Consented app-use events and per-installation daily totals are retained for up to 24
            months.
          </li>
          <li>
            Account-linked Exhibit contributions, unlocks, consent records, and credentials remain
            only while needed to provide the online account or until deletion.
          </li>
          <li>
            Short operational and security records are retained only as needed for reliability,
            abuse prevention, and incident response.
          </li>
        </ul>
        <p>
          Deleting an account removes its identity, sessions, credentials, consent history, direct
          analytics, daily totals, personal Exhibit records, and other account-linked server data.
          Anonymous Exhibit progress and anonymous global analytics rollups may remain so shared
          totals stay accurate, but the contribution or installation link that could associate those
          totals with the deleted account is removed.
        </p>
      </PolicySection>

      <PolicySection title="Security">
        <p>
          Online traffic uses HTTPS. Credentials are encrypted on the device; server refresh
          credentials are stored as non-reversible hashes. Access is restricted through scoped
          authentication and database controls. No system can be guaranteed perfectly secure, but
          TechfullyMade limits collection so less sensitive data exists to protect.
        </p>
      </PolicySection>

      <PolicySection title="Your choices">
        <ul>
          <li>Use the complete daily collection locally without enabling online features.</li>
          <li>Choose which alerting apps Android permits to contribute.</li>
          <li>Turn optional app-use reporting or daily-total sharing off independently.</li>
          <li>Delete your online account and its associated data.</li>
          <li>Contact TechfullyMade with a privacy or deletion question.</li>
        </ul>
      </PolicySection>

      <PolicySection title="Contact">
        <p>
          Afterchime is provided by TechfullyMade. Privacy questions can be sent to{" "}
          <a href={`mailto:${PRIVACY_CONTACT_EMAIL}`}>{PRIVACY_CONTACT_EMAIL}</a>.
        </p>
      </PolicySection>
    </article>
  );
}

function AccountDataContent({
  publicPage,
  canDeleteAccount,
  onDeleteAccount,
}: {
  publicPage: boolean;
  canDeleteAccount: boolean;
  onDeleteAccount(): void;
}) {
  return (
    <article>
      <PolicyHeading
        eyebrow="Privacy control"
        title={publicPage ? "Delete account" : "Account & data"}
      />
      <p>
        Deleting your Afterchime account permanently removes the account and data linked to it from
        TechfullyMade’s systems. It is not a temporary deactivation.
      </p>
      <PolicySection title="What is deleted">
        <ul>
          <li>The pseudonymous account and installation identity.</li>
          <li>
            Sessions, refresh credentials, consent history, direct analytics, and daily totals.
          </li>
          <li>
            Personal Exhibit contribution records, unlock eligibility, and other account-linked
            records.
          </li>
          <li>
            The synced local copy and account credentials on the device that completes deletion.
          </li>
        </ul>
      </PolicySection>
      <PolicySection title="What may remain">
        <p>
          Anonymous Exhibit progress and anonymous global analytics rollups may remain so public
          totals stay correct. The contribution row and all user or installation links are removed,
          so the retained total cannot be traced back to the deleted account.
        </p>
      </PolicySection>
      {publicPage ? (
        <PolicySection title="Request deletion">
          <p>
            If Afterchime is still installed, use Settings → Account & data for immediate
            authenticated deletion. If you cannot access the app, email TechfullyMade. Include
            “Afterchime deletion” in the subject; never send passwords or verification codes.
          </p>
          <a
            className="policy-action-link"
            href={`mailto:${PRIVACY_CONTACT_EMAIL}?subject=Afterchime%20deletion`}
          >
            Email a deletion request
          </a>
        </PolicySection>
      ) : (
        <div className="policy-actions">
          <button
            type="button"
            className="danger-action"
            disabled={!canDeleteAccount}
            onClick={onDeleteAccount}
          >
            Delete account and online data
          </button>
          {!canDeleteAccount && (
            <p className="policy-note">
              Update the installed app to enable authenticated deletion.
            </p>
          )}
          <button type="button" className="secondary" disabled>
            Reset this device
          </button>
          <p className="policy-note">
            Device-only reset will be enabled when recoverable accounts can restore saved data. The
            anonymous beta cannot safely recover after a local reset.
          </p>
        </div>
      )}
    </article>
  );
}

function SupportContent() {
  return (
    <article>
      <PolicyHeading eyebrow="TechfullyMade" title="Support" />
      <p>
        For help with Afterchime, account deletion, privacy, purchase restoration, or a technical
        problem, contact <a href={`mailto:${SUPPORT_CONTACT_EMAIL}`}>{SUPPORT_CONTACT_EMAIL}</a>.
      </p>
      <PolicySection title="What to include">
        <ul>
          <li>Your Afterchime app version and Android version.</li>
          <li>A short description of what happened and what you expected.</li>
          <li>A screenshot only if it contains no private notification information.</li>
        </ul>
        <p>
          Never send passwords, verification codes, notification content, or purchase credentials.
        </p>
      </PolicySection>
      <PolicySection title="Privacy and deletion">
        <p>
          Privacy questions and deletion requests can be sent to{" "}
          <a href={`mailto:${PRIVACY_CONTACT_EMAIL}`}>{PRIVACY_CONTACT_EMAIL}</a>. Put “Afterchime
          privacy” or “Afterchime deletion” in the subject so the request can be handled promptly.
        </p>
      </PolicySection>
    </article>
  );
}

function PolicyHeading({ eyebrow, title }: { eyebrow: string; title: string }) {
  return (
    <div className="screen-heading policy-heading">
      <p className="eyebrow">{eyebrow}</p>
      <h1>{title}</h1>
    </div>
  );
}

function PolicySection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="policy-section">
      <h2>{title}</h2>
      {children}
    </section>
  );
}

function policyTitle(kind: PolicyPageKind): string {
  if (kind === "privacy") return "Privacy policy";
  if (kind === "support") return "Support";
  return "Account & data";
}
