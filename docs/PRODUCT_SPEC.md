# Afterchime — Product, Game, Data and System Specification

**Status:** Canonical pre-beta specification<br>
**Product owner:** Andrew<br>
**Delivery owner:** Alfred<br>
**Working repository:** `x17Andrew71x/Strata-Notifs`<br>
**Platform:** Android / Google Play<br>
**Current target:** Development beta; production deployment requires Andrew's later approval<br>
**Initial version:** `0.1.0`<br>
**Last revised:** 2026-10-05

---

## 1. Executive summary

Afterchime is a small, distinctly Android collection game that turns a privacy-reduced count of eligible notifications into energy for one daily excavation. It never reads, retains or uploads notification text. One fossil is privately pre-rolled for the local day; the user spends earned energy to uncover it tile by tile, then keeps the completed discovery in a private museum.

The product is intentionally restrained. The primary experience is a beautiful daily object, not a dashboard full of charts and not another notification-management utility. It should feel like a quiet game: tactile, collectible and lightly mysterious, with no streak pressure and no reward for manufacturing extra notifications after the daily opportunity is exhausted.

The free product is complete. Revenue comes from optional one-time cosmetic world packs and a launch bundle. There are no forced adverts, no paid rarity boosts, no loot boxes, no consumable currency and no subscription until genuinely recurring content exists.

A lightweight Railway backend and PostgreSQL database support pseudonymous installations, opt-in product analytics, community campaigns, aggregate notification milestones, purchase verification and reliable award delivery. Raw notification content and package identities never leave the device. Development and production are isolated Railway environments. Production remains inert until Andrew approves a beta promotion.

---

## 2. Product promise

> **Uncover what remains after the noise.**

Each qualifying notification earns one unit of dig energy. The user decides when and where to excavate a concealed daily fossil. The fossil's identity and rarity are fixed before activity begins and cannot be improved by generating more notifications.

### 2.1 User value

- Turn an otherwise disposable stream of interruptions into personal generative art.
- Receive one finite, meaningful reward per day without chores or attention traps.
- Build a museum whose objects reflect personal rhythm without exposing private content.
- Contribute unwanted duplicates to shared artworks and receive the finished work.
- Re-render existing history through optional cosmetic worlds without losing progress.

### 2.2 Product principles

1. **Quiet before clever.** The app must never nag the user to create activity.
2. **Private by construction.** Do not collect data merely because Android exposes it.
3. **One clear purpose per screen.** Controls and copy earn their place.
4. **Patterns, not volume.** More notifications must not mean better rewards.
5. **Cosmetics, not advantage.** Money changes presentation, never rarity or contribution power.
6. **Offline first.** Daily collection remains functional without the backend.
7. **Cooperation, not status anxiety.** Community work is shared; global rankings are excluded.
8. **Deterministic outcomes.** The same source summary and rules version produce the same specimen.
9. **Recoverable operations.** Sync, donations and awards are idempotent and retry safely.
10. **Evidence over intuition.** Product decisions use consented analytics and declared success metrics.

---

## 3. Audience and positioning

### 3.1 Primary audience

- Android users who enjoy compact collection games, live wallpapers and generative art.
- People curious about their digital rhythm but uninterested in guilt-driven screen-time tools.
- Users who want a low-maintenance game that develops over weeks and months.

### 3.2 Secondary audience

- Wallpaper customisation enthusiasts.
- Completionists who enjoy collections without competitive pressure.
- Privacy-conscious users who want a local-only option.

### 3.3 Positioning

Afterchime is an ambient collection game, not a productivity application. Store language may mention digital rhythm but should not promise behaviour change, diagnosis or wellbeing outcomes.

### 3.4 Deliberate non-goals

- Reading, classifying or summarising message content.
- Replacing Android notification controls.
- Social feeds, chat, user-authored captions or uploaded images.
- Global leaderboards.
- Advertising-driven engagement.
- Cryptocurrency, NFTs or transferable ownership.
- A complex virtual economy.
- Desktop or iOS support during the Android beta.

---

## 4. Product identity

### 4.1 Name

**Afterchime** is the working product name. It describes what remains after the day’s notifications have passed: their rhythm preserved as a quiet collectible. It reads naturally, avoids geological jargon, and remains broad enough for every visual world.

A preliminary exact-name screen on 2026-10-02 found:

- no `AFTERCHIME` live or dead records in the official USPTO wordmark search;
- no exact-name app in the reviewed Google Play or Apple App Store searches;
- at least two music tracks named “Afterchime,” but no material exact-name software, game or company result in the reviewed general web search; and
- `afterchime.com` registered, while `afterchime.app` returned an RDAP `404`, suggesting the `.app` domain was unregistered at that moment but not reserving it.

This is a preliminary screen, not legal clearance or a domain purchase. A broader confusing-similarity and international trademark review remains required before public release. The repository name may remain `Strata-Notifs`.

### 4.2 Android identities

- Production application ID: `com.techfullymade.afterchime`
- Development application ID: `com.techfullymade.afterchime.dev`
- Development display name: `Afterchime Dev`
- Production display name: `Afterchime`
- Minimum supported version: Android 12 / API 31, required for enforceable listener-type filters.

Development and production builds must be installable together and use different API base URLs, icons and signing material.

### 4.3 Tone

Copy is short, calm and concrete. Prefer “Seal today’s layer” to “Embark upon your geological journey.” Fossil names may be playful, but the interface must not chatter.

---

## 5. Core game loop

### 5.1 Daily excavation

1. A fresh install's `NotificationListenerService` starts with no notification types selected.
2. Android's listener controls permanently disable ongoing, conversation and silent notifications. The user explicitly enables alerting notifications and chooses which apps may contribute.
3. A non-overridable local safety policy rejects a curated set of common financial, payment, cryptocurrency-wallet, password-manager, authenticator and VPN sources before notification fields are inspected. This is defence in depth, not an exhaustive catalogue; users can exclude any additional app in Android's system controls.
4. The app immediately reduces each eligible event to an approved local record: timestamp bucket, coarse Android category and a device-local pseudonymous source token/colour.
5. Group summaries, the app's own notifications, the built-in safety policy and user-selected system exclusions do not count.
6. A local-secret/date-bound weighted draw fixes one catalogue fossil for the day before notification activity can influence it; rarity stays concealed until discovery.
7. A notification earns one unit of dig energy only when that source has not already earned energy within the configured rolling cooldown. Initial tuning is one energy per qualifying notification and a five-minute per-source cooldown.
8. The user spends the configured tile cost—initially three energy—to clear a chosen tile from the real paired excavation image.
9. Clearing every configured tile immediately places the same fossil's paired Museum image on its standard pedestal and adds the specimen to the local collection.
10. No second personal plate begins that day. Incomplete excavations expire at the next local day boundary; completed specimens remain immutable in the Museum.

### 5.2 No-notification and missing days

A day with insufficient eligible notifications may end without a specimen. The unfinished concealed fossil expires rather than entering the Museum. A day when the service lacked permission is marked **unobserved**, not interpreted as quiet. Afterchime has no streak counter and never downgrades an already selected fossil because activity was low.

### 5.3 Day boundaries

- Local calendar date is authoritative for the personal daily specimen.
- App launch and service startup establish the date-bound excavation and catch up aggregate day records.
- The sealed input includes the zone offset used for that day.
- Once fully excavated, a specimen is immutable except for an explicit catalogue migration that preserves the original version and outcome.
- Clock changes and travel must not mint more than one ordinary specimen for the same anchored local day.

### 5.4 Selection and energy inputs

The fossil draw may use only:

- deterministic daily entropy derived from the device-local secret and local date;
- the versioned fossil catalogue and each item's independently tunable positive selection weight.

Energy eligibility may use only event time and the device-local pseudonymous source token. Neither selection nor energy may use message text, titles, sender identities, contact data, raw application package names, notification actions or media.

#### 5.4.1 Hosted catalogue delivery

The installed Android binary contains a validated baseline catalogue for first-launch and offline fallback, but routine fossil additions are hosted-shell content releases rather than APK releases. On capability negotiation, the authenticated same-origin shell offers a strict, versioned catalogue manifest to native storage. Native accepts only bounded, append-only revisions that preserve every existing artifact identity, rejects rollback or malformed payloads, and continues using its last valid snapshot offline. A newly accepted revision applies to daily beds created after synchronisation; an already-created or partly excavated bed never changes underneath the user.

Museum and excavation art use content-addressed server URLs. The hosted Service Worker verifies the filename digest, atomically promotes only a complete shell/art set, and keeps the last verified set offline. Native package releases are required only when the catalogue protocol, security boundary, persistent schema or other installed Android behaviour changes—not when approved fossils or their artwork are added. The first updater-capable Android release is therefore a one-time bootstrap for this delivery contract; subsequent catalogue growth must prove hosted deployment against that unchanged APK.

### 5.5 Fairness rules

- Notification count never influences rarity or fossil identity.
- Repeated events from one source inside the configured rolling cooldown earn no energy.
- Every gameplay number—energy earned, tile cost, grid dimensions and cooldown—lives in the central balance constants; item rarity is tuned through per-item catalogue weights.
- The approved 17-fossil catalogue uses an 86% common, 12% uncommon and 2% rare tier distribution, with equal item weights inside each tier.
- A paid world uses the same underlying specimen class and rarity as the free world.
- Changing device time, replaying sync or reinstalling must not create duplicate ordinary rewards.

---

## 6. Specimens and collection

### 6.1 Base-world families

The free **Primeval Strata** world includes at least these families:

| Family | Pattern association | Visual character |
|---|---|---|
| Ammonite | Rhythmic recurring bursts | Spiral shell impression |
| Trilobite | Stable activity across several periods | Segmented relief |
| Fern imprint | Broadly spaced light activity | Delicate branching compression |
| Shark tooth | Isolated sharp events | Angular dark inclusion |
| Trackway | Small clusters separated by quiet | Repeating trace path |
| Amber | Long clean interval surrounded by activity | Warm translucent inclusion |
| Geode | Strong quiet core with outer activity | Crystalline cavity |
| Meteorite fragment | Unusual asymmetric pattern | Metallic inclusion |
| Trace plate | Very quiet or no-event observed day | Minimal surface markings |
| Coprolite | Comically repetitive low-diversity activity | Rare tongue-in-cheek specimen |

Scientific labels must be clearly presented as stylised game taxonomy rather than claims that notification patterns literally identify fossil formation.

### 6.2 Quality tiers

- **Common**
- **Uncommon**
- **Rare**
- **Exceptional**
- **Singular**

The tier belongs to the selected catalogue item. Common fossils use restrained natural materials; medium tiers gain stronger silhouettes and richer mineral detail; rare and higher tiers must provide an immediate visual “wow” without relying only on glow. The weighted draw is deterministic for a date and local secret, cannot be purchased, and cannot be altered by notification volume.

### 6.3 Restoration and combining

- Three identical ordinary specimens combine into one **Restored** specimen.
- Three identical Restored specimens combine into one **Centre Piece**.
- Combining is explicit, previewed and confirmed.
- The operation is transactional and idempotent.
- Restored and Centre Piece items retain provenance counts but not sensitive source data.
- A user may lock favourites to prevent accidental combining or donation.

### 6.4 Weekly displays

Seven sealed local days can be arranged into a weekly diorama. Dioramas do not consume specimens and may be regenerated when a user changes worlds.

### 6.5 Museum

The museum is the long-term home for:

- daily specimens;
- restored specimens and centre pieces;
- weekly dioramas;
- completed community artworks;
- seasonal contributor editions;
- achievement plaques;
- purchased-world renderings.

Filters are limited to world, family, tier, state and date. Search and sort remain secondary controls, not permanent clutter.

---

## 7. Cosmetic worlds

A world is a renderer, catalogue and sound/haptic palette applied to the same approved daily feature vector. Buying a world re-renders the user's eligible historical collection immediately.

### 7.1 Launch worlds

| World | Commercial status | Outputs |
|---|---|---|
| Primeval Strata | Free | Fossils, minerals, layers |
| Deep Space | Paid | Planets, nebulae, comets, alien fragments |
| Botanical Archive | Paid | Pressed flowers, fungi, seeds, tree rings |
| The Abyss | Paid | Shells, coral, trenches, bioluminescent life |

Later candidates are Clockwork, Arcane Cabinet and Stained Glass. They are not launch blockers.

### 7.2 Commercial rules

- The free world provides the entire game loop and all rarity tiers.
- Paid worlds are cosmetic and have no gameplay multiplier.
- Re-rendering never creates extra inventory or community contribution value.
- Purchases restore through Google Play.
- Production entitlements require server verification and acknowledgement.
- Development builds use an unmistakable fake-billing provider; fake entitlements cannot cross into production.

---

## 8. Community game

### 8.1 Purpose

Community play gives duplicates and unwanted high-level pieces a satisfying destination without exposing social profiles or creating a popularity contest.

### 8.2 Seasonal exhibitions

The service runs four principal seasonal exhibitions using globally neutral names and art direction:

- **Bloom**
- **Sunstone**
- **Harvest**
- **Frost**

The app may describe them as spring, summer, autumn and winter according to a user-selected northern, southern or neutral season preference. No location permission is required.

Each exhibition defines:

- opening and closing timestamps;
- a deterministic mosaic blueprint and palette;
- accepted specimen families or any-specimen rules;
- a contribution target;
- progress milestones;
- a completion-art manifest;
- contributor award presentation.

### 8.3 Donating specimens

- Any unlocked ordinary, Restored or Centre Piece specimen may be donated.
- Ordinary contributes one unit, Restored three units and Centre Piece nine units.
- The confirmation screen shows exactly what leaves the museum and what it contributes.
- Donation uses a client-generated idempotency key and one server transaction.
- A successful donation is final; transient uncertainty is reconciled before the app allows another attempt.
- Premium visual worlds do not change contribution units.
- No free-text, images or other user-generated content are accepted.

### 8.4 Completing the artwork

Each contribution fills deterministic cells in the exhibition blueprint. The server stores the manifest and contribution ledger; the Android client renders the finished artwork from that signed manifest. This avoids a separate image-processing or object-storage service for the beta.

When the target is reached:

1. The campaign closes atomically.
2. A final manifest hash is recorded.
3. An outbox event schedules awards.
4. Every distinct contributor receives one permanent completed artwork in the museum.
5. Awards retry idempotently until acknowledged.
6. A contributor frame may reflect contribution bands, but the underlying artwork is identical for everyone.

Non-contributors may see the public completed preview but do not receive the collectible contributor edition.

### 8.5 Global notification creations

The app also builds a season-long **World Formation** from notifications observed across participating users.

Two metrics must remain separate:

- **Analytics count:** the true aggregate number of eligible notifications reported by consenting installations.
- **Game pressure:** a capped, deduplicated daily value per installation used to advance the World Formation fairly.

This prevents one malfunctioning or deliberately noisy device from controlling the game while retaining accurate aggregate analytics. Individual notification totals are never publicly ranked or exposed.

Milestones change the shared formation: sediment, fractures, inclusions, a core and finally a monumental community specimen. Participants who contributed at least one eligible day during the season receive the completed World Formation artwork.

### 8.6 No leaderboard

There is no global leaderboard in the beta. It would reward noise, favour older accounts, invite manipulation and create avoidable moderation and privacy work. Personal museum completion may be shown privately.

---

## 9. Monetisation

### 9.1 Launch model

- Free download.
- Complete Primeval Strata game.
- Individual paid world packs.
- Discounted launch bundle.
- Optional supporter purchases may be considered after beta.
- No adverts in the beta.
- No subscription in the beta.

### 9.2 Initial catalogue targets

- Individual world: target list price around USD 2.99, localised by Google Play.
- Three-world launch bundle: target list price around USD 6.99.
- Curator launch edition: target list price around USD 14.99 if the included value is materially distinct.

Exact regional prices are a release decision, not hard-coded game logic.

### 9.3 Prohibited commercial mechanics

- Loot boxes or random paid rewards.
- Paid rarity boosts.
- Consumable energy.
- Pay-to-contribute community multipliers.
- Purchase-gated privacy controls.
- Artificial delay removal.
- Forced or unexpected advertisements.

### 9.4 Product analytics for monetisation

Track the consented funnel from theme impression to preview, checkout start, purchase result, entitlement restore and later usage. Never send Google purchase tokens through the general analytics event payload; use the protected purchase-verification domain.

---

## 10. User experience and visual system

### 10.1 Navigation

Four destinations are sufficient:

1. **Today** — live formation, observation state and reveal.
2. **Museum** — collection, restoration and displays.
3. **Community** — current exhibition and World Formation.
4. **More** — worlds, privacy, data controls, accessibility, account/installation and help.

The theme shop lives under More and contextual world selectors. It is not a permanent fifth advertising tab.

### 10.2 Screen rules

- One primary action per screen.
- Persistent bottom navigation only at root destinations.
- Contextual top bars only for nested screens.
- Avoid nested card-on-card layouts.
- Progressive disclosure for advanced statistics and data controls.
- Important destructive or irreversible actions require a plain-language confirmation.
- Empty states explain the next natural event without pressuring the user.

### 10.3 Visual direction

- Dark basalt foundation rather than absolute black.
- Warm mineral neutrals, oxidised copper and restrained teal/mint accents.
- Specimens carry most colour; navigation chrome remains quiet.
- Rounded geometry is modest, not bubbly.
- Texture is procedural and subtle; text remains crisp.
- Animations resemble excavation, settling sediment and light moving across relief.
- Haptics are brief and optional.

### 10.4 Game presentation

Game character comes from reveal choreography, collection shelves, restoration and world art—not from excessive badges, currencies or exclamation marks. The interface should be credible as an art object with its labels hidden.

### 10.5 Accessibility

- Compose semantics and meaningful content descriptions.
- Dynamic type without clipped controls.
- Minimum touch targets.
- Contrast that meets WCAG AA for text and essential controls.
- Colour never carries state alone.
- Reduce-motion option respected throughout.
- Haptics and audio independently disabled.
- TalkBack flow verified for core paths.
- Phone portrait is primary; landscape and tablet layouts must remain usable.

### 10.6 Sharing

Users may export a specimen, diorama or completed community artwork as an image. Exports contain no notification counts, dates or account identifiers unless the user explicitly enables a date label. A discreet Afterchime signature may be included and can be removed for owned premium worlds.

---

## 11. Privacy and consent

### 11.1 Data minimisation boundary

The notification listener may transiently see Android notification objects that pass the user's Android filters, but code must never persist or transmit:

- title or body text;
- sender/contact identity;
- message snippets;
- actions or reply text;
- images, attachments or media;
- raw package names;
- notification keys;
- exact content-bearing extras.

Raw package identity is used only inside the reducer call stack: first to compare a one-way digest against the built-in safety policy, then to derive a device-local salted source token and colour. It is immediately discarded and never logged, persisted, included in analytics or transmitted. The built-in digest catalogue contains no observed-device inventory and never leaves the application binary.

### 11.2 Local-only mode

The complete daily collection and museum work without an account, analytics consent or network. Local-only users cannot participate in community campaigns, restore purchases across devices or contribute aggregate community metrics.

### 11.3 Consent scopes

1. **Essential online records:** created only when the user enables community or cloud-linked features; includes pseudonymous installation identity, contributions, awards and entitlement state.
2. **Product analytics:** explicit opt-in; includes app interactions and coarse performance/error events.
3. **Notification aggregates:** explicit opt-in; includes hourly or daily counts and approved derived measures, never individual notification payloads.

Declining one scope must not silently enable another. Consent changes are themselves audited as domain records but never expose the previous setting through a public surface.

### 11.4 User controls

- View a plain-language summary of collected categories.
- Disable future analytics upload.
- Disable notification aggregate sharing while retaining local gameplay.
- Export local museum metadata.
- Delete online installation/account data.
- Clear local history with confirmation.
- Open Afterchime's Android listener-detail screen to choose eligible alerting apps or revoke notification access.
- Rely on the built-in sensitive-source policy even if Android's per-app filter is later broadened; the policy is not user-overridable at runtime.

### 11.5 Retention

- Raw consented interaction events: up to 24 months.
- Per-installation notification aggregates: up to 24 months, then deleted or irreversibly anonymised.
- Anonymous daily global rollups: retained for longitudinal product analysis.
- Domain records needed to deliver an owned collectible or entitlement: retained while the installation/account exists.
- Security logs: short, documented retention appropriate to incident response.
- Account deletion removes direct installation linkage and queued awards; only already aggregated, non-reidentifiable totals remain.

### 11.6 Disclosure

The in-app disclosure and Play Data Safety answers must match running code and SDK behaviour. Adding any third-party SDK requires a fresh data-flow review before release.

---

## 12. Analytics requirements

### 12.1 Objective

Record enough structured, consented evidence to understand acquisition, activation, permission completion, daily use, retention, collection behaviour, notification volume, community participation, world interest, purchase conversion, reliability and performance. “Track nearly everything” means a durable versioned event and domain model—not unrestricted surveillance.

### 12.2 Collection architecture

- Android writes eligible events to an encrypted local outbox.
- Upload occurs in bounded batches through WorkManager.
- Every event has a client-generated UUID/ULID and schema version.
- The server validates event name, shape, size, consent scope and timestamp range.
- A unique key makes retries idempotent.
- Server receipt time is authoritative for ingestion operations; client occurrence time remains available for behavioural analysis.
- Unknown event versions fail closed and are counted operationally without storing their rejected payload.
- Analytics ingestion never blocks local gameplay.

### 12.3 Common event envelope

Each accepted analytics event contains only approved fields:

- `event_id`
- `event_name`
- `schema_version`
- pseudonymous `user_id` when present
- pseudonymous `installation_id`
- `session_id`
- `occurred_at`
- `received_at`
- `local_date`
- `timezone_offset_minutes`
- `app_version`
- `version_code`
- `build_channel`
- `android_api_level`
- coarse `device_class`
- `locale` at language-region level
- `screen_name` when applicable
- validated event-specific properties
- consent-scope version

Do not collect advertising ID, hardware serials, phone number, email, contact identifiers, precise location, raw package names or free-form user text.

### 12.4 Interaction event catalogue

The beta must define and test events for:

**Lifecycle and sessions**
- `installation_created`
- `app_opened`
- `session_started`
- `session_ended`
- `app_backgrounded`
- `app_updated`
- `auth_session_created`
- `auth_session_refreshed`
- `online_mode_disabled`

**Onboarding and permissions**
- `onboarding_started`
- `onboarding_step_viewed`
- `onboarding_completed`
- `notification_access_prompted`
- `notification_access_result`
- `analytics_consent_changed`
- `notification_aggregate_consent_changed`

**Navigation and engagement**
- `screen_viewed`
- `tab_selected`
- `help_opened`
- `setting_changed`
- `share_started`
- `share_completed`
- `share_failed`

**Daily formation**
- `formation_viewed`
- `day_sealed`
- `specimen_generated`
- `specimen_reveal_started`
- `specimen_revealed`
- `specimen_locked`
- `specimen_unlocked`
- `weekly_diorama_created`

**Museum and restoration**
- `museum_viewed`
- `museum_filter_changed`
- `specimen_detail_viewed`
- `combine_previewed`
- `combine_completed`
- `combine_cancelled`
- `community_art_viewed`

**Worlds and commerce**
- `world_selector_opened`
- `world_previewed`
- `store_viewed`
- `product_viewed`
- `checkout_started`
- `checkout_result`
- `entitlements_restored`
- `owned_world_applied`
- `history_rerender_started`
- `history_rerender_completed`

**Community**
- `campaign_viewed`
- `donation_started`
- `donation_confirmed`
- `donation_result`
- `campaign_milestone_viewed`
- `community_award_received`
- `world_formation_viewed`

**Reliability and performance**
- `sync_started`
- `sync_completed`
- `sync_failed`
- `worker_failed`
- `render_timing`
- `api_timing`
- `nonfatal_error`

Sensitive error messages and stack traces are scrubbed before upload. Crash collection is deferred unless an SDK passes the same privacy review.

### 12.5 Domain facts

Business-critical outcomes must not rely solely on analytics events. PostgreSQL domain tables independently record:

- pseudonymous users and installations;
- consent history;
- auth sessions and refresh-token families;
- daily notification aggregates;
- specimen identity, generator version and server-known sync state;
- inventory mutations;
- campaigns and blueprints;
- donations and contribution units;
- campaign completion manifests;
- contributor awards and acknowledgements;
- product catalogue and entitlements;
- purchase-verification attempts and outcomes;
- outbox jobs and idempotency records.

### 12.6 Derived analytics tables and views

Scheduled aggregation creates or refreshes:

- `analytics_daily_global`
- `analytics_daily_installation`
- `analytics_daily_acquisition`
- `analytics_activation_funnel`
- `analytics_retention_cohorts`
- `analytics_permission_funnel`
- `analytics_notification_volume`
- `analytics_specimen_distribution`
- `analytics_collection_progress`
- `analytics_combine_usage`
- `analytics_community_campaign`
- `analytics_world_interest`
- `analytics_purchase_funnel`
- `analytics_release_health`
- `analytics_consent_coverage`

These must support at least:

- app opens and sessions per active installation;
- new and returning installations;
- consent and permission completion rates;
- notifications per installation per local day;
- true global notifications per UTC day;
- specimens generated, revealed, combined and donated;
- family/tier distribution by generator version;
- campaign participation and completion velocity;
- artwork award delivery success;
- world preview-to-purchase conversion;
- retention by installation cohort and release;
- errors, sync failures and latency by app version.

### 12.7 Data-quality requirements

- Referential and schema constraints reject impossible states.
- Monetary and purchase facts use dedicated typed columns, not arbitrary JSON.
- Event properties use strict per-event schemas.
- Client and server clocks outside an allowed window are flagged.
- Raw event counts reconcile with aggregate jobs within documented tolerances.
- Aggregation jobs use checkpoints and idempotent upserts.
- A data dictionary identifies owner, meaning, grain, retention and sensitivity for every table and event.
- Synthetic development records are unmistakably marked and cannot enter production aggregates.

---

## 13. Identity and authentication

### 13.1 Beta identity

The beta defaults to a pseudonymous installation account created only when online features are enabled. The service returns an opaque rotating refresh credential stored with Android Keystore protection. Short-lived access tokens authorize API calls.

The user does not need to provide an email, name or phone number. Loss of app data may lose access to online awards until an account-recovery method is added; that limitation must be disclosed.

### 13.2 Later account upgrade

Google sign-in through Android Credential Manager may later attach a recoverable identity. The data model must allow an installation to move between anonymous and recoverable user identities without duplicating entitlements or contributions. Google configuration is not a beta blocker.

### 13.3 Authentication controls

- Refresh-token rotation and family revocation.
- Hash refresh credentials at rest.
- Short access-token lifetime.
- Rate limits on registration, refresh, ingestion, donations and purchase verification.
- No token in logs or analytics payloads.
- Installation ownership checked on every user-scoped operation.
- Play Integrity can be added as a risk signal; it must not become an undocumented universal lockout during beta.

---

## 14. Technical architecture

### 14.1 Repository layout

```text
android/                 Native Android application
server/                  Railway API and worker commands
contracts/               Versioned OpenAPI/JSON schemas and fixtures
docs/                    Product, data and operational documentation
docs/plans/              Sequenced implementation plans
scripts/                  Deterministic local and CI helpers
.github/workflows/        CI only; no production auto-deploy
```

### 14.2 Android stack

- Kotlin
- Jetpack Compose and Material 3 foundations with a bespoke Afterchime design system
- Room for local event summaries, museum, outbox and sync state
- WorkManager for sealing, upload and reconciliation
- DataStore for preferences and consent state
- Hilt for dependency injection
- Retrofit/OkHttp or Ktor client for typed HTTPS calls
- Kotlin serialization
- Google Play Billing for production products
- Android Keystore-backed credential protection

No general-purpose analytics or advertising SDK is used in the beta.

### 14.3 Server stack

- Node.js 22
- TypeScript in strict mode
- Fastify
- Zod for request and event validation
- Drizzle ORM and committed forward-only PostgreSQL migrations
- Vitest
- Structured JSON logging with redaction
- OpenAPI contract generated or checked from the route schemas

The API and scheduled worker share domain packages but run as separate commands/services.

### 14.4 Server responsibilities

- anonymous installation registration and token refresh;
- consent-aware event ingestion;
- daily notification aggregate ingestion;
- specimen/inventory sync required for donations and awards;
- transactional community donations;
- campaign progress and signed completion manifests;
- idempotent contributor awards;
- global World Formation progress;
- purchase verification and entitlements;
- aggregation jobs and data-quality checks;
- health/readiness endpoints;
- narrowly authenticated owner analytics queries.

### 14.5 Offline and sync model

Local state is authoritative for private daily generation and viewing. Server-authoritative facts are limited to contributions, awards, entitlements, online identity and aggregate campaign progress.

All mutations use:

- client-generated idempotency key;
- explicit local pending state;
- server transaction;
- durable result or conflict response;
- retry with backoff;
- reconciliation before another destructive mutation.

A specimen being donated must be locked locally until success, definitive failure or reconciliation. A timeout is never treated as failure merely because the client did not see the response.

### 14.6 Community consistency

- PostgreSQL transactions own contribution, inventory mutation and campaign progress.
- Row locks or serializable checks prevent over-completion races.
- Completion creates an immutable manifest hash.
- Transactional outbox rows drive awards.
- Workers claim with bounded leases and idempotent completion.
- Replayed requests return the original result.

### 14.7 Artwork rendering

Procedural artwork is deterministic and implemented in a platform-neutral specification. The Android renderer is the primary visual implementation. Server tests validate manifest and seed determinism; the beta does not require server-side raster rendering.

---

## 15. Railway environments and deployment

### 15.1 Topology

One Railway project contains isolated **development** and **production** environments.

Development contains:

- `afterchime-api-dev`
- `afterchime-worker-dev`
- managed PostgreSQL

Production is created but remains undeployed/inert until Andrew approves promotion. It must not share database credentials, signing material, tokens or public domains with development.

### 15.2 Configuration

- Secrets exist only in Railway environment variables or Android local signing configuration ignored by Git.
- Database URLs use Railway references, not copied values in source.
- Development API accepts only development app identity/audience.
- Production API fails closed if required production verification or secrets are absent.
- Schema migrations are committed and run deliberately before compatible service deployment.
- No production deployment is triggered by pushing `dev`.

### 15.3 Scheduled work

A lightweight worker or Railway cron command performs:

- analytics rollups;
- campaign lifecycle transitions;
- contributor-award delivery;
- stale idempotency cleanup;
- retention/anonymisation;
- data-quality reconciliation.

Every job has a run record, checkpoint, lease, bounded retry and final status. Empty work is a successful no-op.

### 15.4 Health and observability

- Liveness does not query dependencies.
- Readiness proves configuration and database access.
- Deployment records expose version and migration revision without secrets.
- Logs are structured and redact authorization, purchase and notification-related fields.
- Alert-worthy failures include migration mismatch, award backlog, rollup drift and sustained ingestion rejection.

---

## 16. PostgreSQL model

The precise schema is migration-owned, but it must preserve these boundaries.

### 16.1 Identity and consent

- `users`
- `installations`
- `auth_refresh_tokens`
- `consent_records`

### 16.2 Collection domain

- `specimens`
- `inventory_mutations`
- `weekly_displays`
- `community_art_unlocks`

Private local specimens need not all be uploaded. A server specimen row is required before donation and contains only generator identity, family, tier, form and integrity proof—not the underlying notification events.

### 16.3 Community domain

- `campaigns`
- `campaign_blueprints`
- `campaign_contributions`
- `campaign_progress`
- `campaign_manifests`
- `campaign_awards`
- `world_formation_daily`

### 16.4 Commerce domain

- `products`
- `purchase_verifications`
- `entitlements`

### 16.5 Analytics and operations

- `analytics_events`
- `daily_notification_aggregates`
- derived analytics tables listed in Section 12
- `idempotency_records`
- `outbox_jobs`
- `job_runs`
- `schema_metadata`

### 16.6 Database security

- Migrations run as an owner/admin role.
- Runtime uses a distinct non-owner, non-superuser, non-`BYPASSRLS` role.
- User-owned tables enable and force row-level security or are reachable only through rigorously scoped security-definer functions where justified.
- Startup attests that the runtime role is not privileged and cannot assume a privileged role.
- Constraints and transaction tests enforce ownership and inventory invariants independently of API checks.

---

## 17. API outline

All routes are under `/v1` except health endpoints.

### 17.1 Identity

- `POST /installations`
- `POST /auth/refresh`
- `POST /auth/logout`
- `DELETE /account`

### 17.2 Consent and analytics

- `PUT /consents`
- `POST /analytics/events:batch`
- `POST /notification-aggregates:batch`

### 17.3 Collection sync

- `POST /specimens:register`
- `GET /sync`
- `POST /awards/{id}:acknowledge`

### 17.4 Community

- `GET /campaigns/current`
- `GET /campaigns/{id}`
- `POST /campaigns/{id}/contributions`
- `GET /world-formation/current`

### 17.5 Commerce

- `GET /products`
- `POST /purchases:verify`
- `GET /entitlements`

### 17.6 Owner operations

Owner endpoints are read-only for the beta except deliberate campaign administration. They require separate credentials and complete audit records.

- `GET /admin/metrics/summary`
- `GET /admin/metrics/releases`
- `GET /admin/data-quality`
- `POST /admin/campaigns`
- `POST /admin/campaigns/{id}/open`
- `POST /admin/campaigns/{id}/close`

---

## 18. Security requirements

- TLS only outside local tests.
- Strict request and response schemas with size limits.
- Parameterised queries through typed data access.
- Rate limiting and abuse controls at sensitive routes.
- Passwordless anonymous beta identity; no plaintext credential storage.
- Refresh credentials hashed with a suitable keyed or memory-hard strategy.
- Access tokens signed with environment-specific keys and explicit audience/issuer.
- Secrets redacted from logs, exceptions and CI artifacts.
- Dependency lockfiles and automated vulnerability checks.
- Android network-security configuration disallows cleartext production traffic.
- Development trust overrides cannot compile into production.
- Purchase verification fails closed in production.
- Notification parsing has a deny-by-default field boundary and regression tests.
- Exported Android components are minimised and permission-protected.
- Database migrations, RLS and runtime-role topology are tested against real PostgreSQL.
- Admin actions are authenticated, rate-limited and audited.
- Backups and restoration are tested before production approval.

Threat modelling must cover fake clients, event replay, donation replay, inventory forgery, campaign race conditions, token theft, overcollection, log leakage and malicious notification payloads.

---

## 19. Performance and battery requirements

- Notification handling performs bounded local reduction and asynchronous persistence; no network request per notification.
- Uploads are batched and use WorkManager constraints/backoff.
- The live formation renderer targets smooth interaction without continuous background animation.
- Wallpaper rendering pauses when not visible and has low-quality/battery-saver modes.
- Museum lists are paged/lazy and thumbnails cached.
- Server ingestion uses bounded batch sizes and bulk inserts.
- Analytics indexes follow measured query plans rather than speculative indexing.
- Scheduled aggregation is incremental by checkpoint.

Beta acceptance targets will be measured on representative low- and mid-range profiles. Exact thresholds belong in the performance plan and test fixtures, not marketing claims.

---

## 20. Versioning and branching

### 20.1 Semantic versioning

Use `MAJOR.MINOR.PATCH` throughout.

- `0.y.z` indicates pre-public product development.
- Minor increments introduce a coherent planned capability.
- Patch increments fix or polish an existing capability.
- `1.0.0` requires public-release quality and Andrew's approval.

Android `versionCode` is monotonically derived as:

```text
major * 1,000,000 + minor * 1,000 + patch
```

Minor and patch components must remain below 1,000. Android requires a positive version code, so the first installable build is `0.1.0` / `1000`, not literal `0.0.0` / `0`.

### 20.2 Branches

- `main`: approved stable source; never automatically deploys production.
- `dev`: integration branch for the current beta.
- `feature/*`: bounded implementation work merged into `dev` after review.

Production promotion is a separate approved action, not a branch-name side effect.

---

## 21. Quality strategy

### 21.1 Android

- Unit tests for event reduction, day boundaries, generator determinism, rarity fairness, combining and sync state machines.
- Room migration tests.
- WorkManager and notification-listener tests through fakes/Robolectric where appropriate.
- Compose interaction tests for primary paths.
- Deterministic screenshot/golden tests for core screens and world variants.
- Lint, formatting, static analysis and dependency checks.
- Debug APK assembly and package inspection.
- At least one real-device installation/exercise before public beta; Andrew's delivered APK provides an additional acceptance pass.

### 21.2 Server

- Unit and property tests for validators and deterministic rules.
- API tests with real PostgreSQL.
- Migration apply/readback tests from empty and prior revisions.
- Runtime-role/RLS denial tests.
- Idempotency, duplicate delivery and timeout reconciliation tests.
- Campaign completion race tests.
- Outbox crash/restart tests.
- Analytics aggregation reconciliation tests.
- Security and dependency audits.

### 21.3 Contract

- Checked OpenAPI/schema compatibility.
- Shared fixtures consumed by Android and server tests.
- Old app versions receive explicit compatible or upgrade-required responses.
- Unknown fields and versions are handled deliberately.

### 21.4 Review passes before “done”

1. Product-requirement traceability.
2. Architecture and maintainability.
3. Privacy/data-flow and Play disclosure.
4. API/application security.
5. PostgreSQL migrations, permissions and persistence.
6. Analytics correctness and decision utility.
7. Economy/fairness and abuse resistance.
8. Android lifecycle, offline behaviour, battery and performance.
9. UI consistency, accessibility and visual golden review.
10. Failure recovery, idempotency and operational readiness.
11. Dependency/licence and supply-chain review.
12. Clean-clone build and APK integrity.

Every material finding is fixed and its relevant gates rerun before completion is claimed.

---

## 22. Beta completion criteria

The development beta is complete only when:

- Notification access disclosure is clear and the listener stores only approved fields.
- At least one full observed day can seal and reveal deterministically.
- Quiet, noisy and permission-missing days behave distinctly.
- Museum, lock, combine and weekly display flows work offline.
- Primeval Strata and all launch paid-world renderers are present in the dev catalogue.
- Development billing can exercise purchase/restore flows without production credentials.
- Online mode can create a pseudonymous installation and rotate credentials.
- Consented analytics and notification aggregates persist to development PostgreSQL and reconcile into daily tables.
- Seasonal donation, campaign completion, manifest distribution and contributor awards pass duplicate/retry/restart tests.
- World Formation progresses from capped game pressure while retaining separate true analytics totals.
- Account/cloud deletion and consent withdrawal work.
- Development Railway API, worker and PostgreSQL are healthy and isolated.
- Production Railway environment exists but is inert.
- All documented quality and review gates pass or carry an explicit non-release blocker.
- A cleanly built development APK installs, opens and exposes the intended dev identity.
- The APK is delivered to Andrew as a native Discord attachment and delivery is verified.

---

## 23. Release exclusions pending later approval

The development beta does not authorise:

- production Railway deployment;
- production database creation or migration if it would incur unapproved cost;
- public Google Play publication;
- final regional pricing;
- production billing/service-account credentials;
- collection of any data outside this specification;
- advertising SDKs;
- public marketing claims about wellbeing or behaviour change.

---

## 24. Current decision log

| Decision | State |
|---|---|
| Product is an ambient collection game, not a notification manager | Accepted |
| Notification text is never stored or transmitted | Accepted |
| Core daily game works offline | Accepted |
| Primeval Strata is complete and free | Accepted |
| Monetisation is cosmetic one-time world packs | Accepted |
| Forced adverts and launch subscription are excluded | Accepted |
| Duplicates and any tier may be donated to community art | Accepted |
| Contributors receive completed community art in their museum | Accepted |
| Seasonal collaborative pieces are core scope | Accepted |
| Global notification totals drive a separate community creation | Accepted |
| Accurate analytics total and capped game pressure remain separate | Alfred decision |
| Nearly all reasonable interactions are captured under explicit consent | Accepted with privacy boundary |
| Railway/PostgreSQL host development analytics and community services | Accepted |
| Development and production environments are separate | Accepted |
| Production remains inert until Andrew's explicit approval | Accepted |
| Global leaderboard is excluded from beta | Alfred decision |
| UI remains game-oriented, clean, concise and deliberately limited | Accepted |
| Semantic `MAJOR.MINOR.PATCH` versioning begins at `0.1.0` | Accepted interpretation |

---

## 25. Product success measures

Success is not raw notification volume. The beta should make it possible to evaluate:

- onboarding and notification-access completion;
- first-specimen reveal rate;
- seven- and thirty-day return rates among observed installations;
- museum and combining use;
- percentage of eligible users participating in a community exhibition;
- percentage of campaign contributors receiving and viewing the completed art;
- world previews, purchases and later application of owned worlds;
- analytics/aggregate consent coverage;
- sync and award reliability;
- battery/performance regressions by release;
- user deletion and consent-withdrawal correctness.

A commercially promising beta has users returning for the object and collection, not because notifications or monetisation pressure drag them back.
