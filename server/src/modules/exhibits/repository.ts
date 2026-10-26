import { randomUUID } from "node:crypto";
import type postgres from "postgres";
import type { DatabaseClient } from "../../db/client.js";
import type { AccessTokenClaims } from "../../security/tokens.js";
import { ConsentInactiveError } from "../consent/service.js";

export type ExhibitKind = "featured" | "archive";

type ExhibitRow = Readonly<{
  build_channel: "dev" | "prod";
  daily_contribution_cap: number;
  ends_at: Date | null;
  id: string;
  image_path: string;
  kind: ExhibitKind;
  personal_minimum_units: number;
  season_label: string;
  slug: string;
  starts_at: Date;
  status: "draft" | "ready" | "open" | "closed" | "cancelled";
  target_units: number;
  title: string;
  total_units: number;
  version: number;
}>;

type InstallationRow = Readonly<{
  build_channel: "dev" | "prod";
}>;

type StoredContribution = Readonly<{
  exhibit_id: string;
  local_date: string;
  units: number;
}>;

export type ExhibitView = Readonly<{
  closed: boolean;
  communityComplete: boolean;
  daysRemaining: number | null;
  id: string;
  imagePath: string;
  personalContribution: number;
  personalMinimum: number;
  progressBasisPoints: number;
  qualified: boolean;
  seasonLabel: string;
  title: string;
  unlocked: boolean;
}>;

export type PastExhibitView = Readonly<{
  id: string;
  imagePath: string;
  lockedReason: string | null;
  seasonLabel: string;
  title: string;
  unlocked: boolean;
}>;

export type ExhibitsState = Readonly<{
  archive: ExhibitView | null;
  dailyContributionCap: number;
  featured: ExhibitView | null;
  past: readonly PastExhibitView[];
  status: "ready";
  todayContributedUnits: number;
  version: number;
}>;

export type ExhibitsRequest = AccessTokenClaims &
  Readonly<{
    buildChannel: "dev" | "prod";
    localDate: string;
    now: Date;
  }>;

export type ContributeRequest = ExhibitsRequest &
  Readonly<{
    exhibitId: string;
    idempotencyKey: string;
    units: number;
  }>;

export type ContributionResult = Readonly<{
  state: ExhibitsState;
  status: "accepted" | "duplicate";
}>;

export class ExhibitsRequestError extends Error {
  public readonly statusCode = 400;

  public constructor() {
    super("exhibits_request_invalid");
    this.name = "ExhibitsRequestError";
  }
}

export class ExhibitsConflictError extends Error {
  public constructor(
    public readonly code: "daily_cap_exceeded" | "exhibit_closed" | "exhibit_unavailable",
  ) {
    super(code);
    this.name = "ExhibitsConflictError";
  }
}

export class ExhibitsInputError extends Error {
  public constructor() {
    super("request_invalid");
    this.name = "ExhibitsInputError";
  }
}

export class ExhibitsRepository {
  public constructor(private readonly database: DatabaseClient) {}

  public async state(input: ExhibitsRequest): Promise<ExhibitsState> {
    if (!isPlausibleLocalDate(input.localDate, input.now)) throw new ExhibitsInputError();
    return this.database.begin(async (transaction) => {
      await setActor(transaction, input);
      await assertInstallationAndConsent(transaction, input);
      return readState(transaction, input);
    });
  }

  public async contribute(input: ContributeRequest): Promise<ContributionResult> {
    if (!isPlausibleLocalDate(input.localDate, input.now)) throw new ExhibitsInputError();
    return this.database.begin(async (transaction) => {
      await setActor(transaction, input);
      await assertInstallationAndConsent(transaction, input);
      await transaction`
        SELECT id
        FROM installations
        WHERE id = ${input.installationId}
          AND user_id = ${input.userId}
        FOR UPDATE
      `;

      const [existing] = await transaction<StoredContribution[]>`
        SELECT exhibit_id::text, local_date::text, units
        FROM exhibit_contributions
        WHERE installation_id = ${input.installationId}
          AND idempotency_key = ${input.idempotencyKey}
        FOR UPDATE
      `;
      if (existing) {
        const [sameExhibit] = await transaction<Readonly<{ slug: string }>[]>`
          SELECT slug FROM exhibits WHERE id = ${existing.exhibit_id}
        `;
        if (
          sameExhibit?.slug !== input.exhibitId ||
          existing.local_date !== input.localDate ||
          existing.units !== input.units
        ) {
          throw new ExhibitsRequestError();
        }
        return { state: await readState(transaction, input), status: "duplicate" };
      }

      const [exhibit] = await transaction<ExhibitRow[]>`
        SELECT
          id::text,
          slug,
          title,
          season_label,
          image_path,
          kind::text,
          status::text,
          build_channel::text,
          target_units,
          personal_minimum_units,
          daily_contribution_cap,
          total_units,
          version,
          starts_at,
          ends_at
        FROM exhibits
        WHERE slug = ${input.exhibitId}
          AND build_channel = ${input.buildChannel}::build_channel
        FOR UPDATE
      `;
      if (!exhibit) throw new ExhibitsConflictError("exhibit_unavailable");
      if (!isContributable(exhibit, input.now)) throw new ExhibitsConflictError("exhibit_closed");

      const [personal] = await transaction<Readonly<{ total: number }>[]>`
        SELECT COALESCE(sum(units), 0)::integer AS total
        FROM exhibit_contributions
        WHERE installation_id = ${input.installationId}
          AND exhibit_id = ${exhibit.id}
      `;
      if (
        exhibit.total_units >= exhibit.target_units &&
        (personal?.total ?? 0) >= exhibit.personal_minimum_units
      ) {
        throw new ExhibitsConflictError("exhibit_unavailable");
      }

      const [daily] = await transaction<Readonly<{ total: number }>[]>`
        SELECT COALESCE(sum(units), 0)::integer AS total
        FROM exhibit_contributions
        WHERE installation_id = ${input.installationId}
          AND local_date = ${input.localDate}::date
      `;
      const [cap] = await transaction<Readonly<{ value: number }>[]>`
        SELECT min(daily_contribution_cap)::integer AS value
        FROM exhibits
        WHERE build_channel = ${input.buildChannel}::build_channel
          AND status = 'open'::exhibit_status
          AND starts_at <= ${input.now}
          AND (ends_at IS NULL OR ends_at > ${input.now})
      `;
      const alreadyContributed = daily?.total ?? 0;
      const sharedDailyCap = cap?.value ?? exhibit.daily_contribution_cap;
      if (alreadyContributed + input.units > sharedDailyCap) {
        throw new ExhibitsConflictError("daily_cap_exceeded");
      }

      await transaction`
        INSERT INTO exhibit_contributions (
          id,
          exhibit_id,
          installation_id,
          idempotency_key,
          local_date,
          units
        ) VALUES (
          ${randomUUID()},
          ${exhibit.id},
          ${input.installationId},
          ${input.idempotencyKey},
          ${input.localDate}::date,
          ${input.units}
        )
      `;
      await transaction`
        UPDATE exhibits
        SET
          total_units = LEAST(target_units, total_units + ${input.units}),
          version = version + 1,
          updated_at = now()
        WHERE id = ${exhibit.id}
      `;
      return { state: await readState(transaction, input), status: "accepted" };
    });
  }
}

export function progressBasisPoints(totalUnits: number, targetUnits: number): number {
  if (
    !Number.isSafeInteger(totalUnits) ||
    !Number.isSafeInteger(targetUnits) ||
    totalUnits < 0 ||
    targetUnits < 1
  ) {
    throw new ExhibitsRequestError();
  }
  return Math.max(0, Math.min(10_000, Math.floor((totalUnits * 10_000) / targetUnits)));
}

export function isPlausibleLocalDate(localDate: string, now: Date): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(localDate) || !Number.isFinite(now.getTime())) return false;
  const parsed = new Date(`${localDate}T00:00:00.000Z`);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== localDate)
    return false;
  const currentUtcDay = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  return Math.abs(parsed.getTime() - currentUtcDay) <= 86_400_000;
}

async function setActor(
  transaction: postgres.TransactionSql,
  input: AccessTokenClaims,
): Promise<void> {
  await transaction`SELECT set_config('afterchime.user_id', ${input.userId}, true)`;
  await transaction`SELECT set_config('afterchime.installation_id', ${input.installationId}, true)`;
}

async function assertInstallationAndConsent(
  transaction: postgres.TransactionSql,
  input: ExhibitsRequest,
): Promise<void> {
  const [installation] = await transaction<InstallationRow[]>`
    SELECT build_channel::text
    FROM installations
    WHERE id = ${input.installationId}
      AND user_id = ${input.userId}
  `;
  if (!installation || installation.build_channel !== input.buildChannel) {
    throw new ExhibitsRequestError();
  }
  const [consent] = await transaction<Readonly<{ granted: boolean; scope_version: string }>[]>`
    SELECT granted, scope_version
    FROM consent_records
    WHERE installation_id = ${input.installationId}
      AND scope = 'essential_online'::consent_scope
    ORDER BY recorded_at DESC, id DESC
    LIMIT 1
  `;
  if (consent?.granted !== true || consent.scope_version !== "1") {
    throw new ConsentInactiveError();
  }
}

async function readState(
  transaction: postgres.TransactionSql,
  input: ExhibitsRequest,
): Promise<ExhibitsState> {
  const rows = await transaction<ExhibitRow[]>`
    SELECT
      id::text,
      slug,
      title,
      season_label,
      image_path,
      kind::text,
      status::text,
      build_channel::text,
      target_units,
      personal_minimum_units,
      daily_contribution_cap,
      total_units,
      version,
      starts_at,
      ends_at
    FROM exhibits
    WHERE build_channel = ${input.buildChannel}::build_channel
      AND status IN ('open'::exhibit_status, 'closed'::exhibit_status)
    ORDER BY starts_at DESC, id
  `;
  const contributionRows = await transaction<Readonly<{ exhibit_id: string; total: number }>[]>`
    SELECT exhibit_id::text, COALESCE(sum(units), 0)::integer AS total
    FROM exhibit_contributions
    WHERE installation_id = ${input.installationId}
    GROUP BY exhibit_id
  `;
  const contributionByExhibit = new Map(
    contributionRows.map((row) => [row.exhibit_id, row.total] as const),
  );
  const [today] = await transaction<Readonly<{ total: number }>[]>`
    SELECT COALESCE(sum(units), 0)::integer AS total
    FROM exhibit_contributions
    WHERE installation_id = ${input.installationId}
      AND local_date = ${input.localDate}::date
  `;

  const active = rows.filter((row) => isContributable(row, input.now));
  const featuredRow = active.find((row) => row.kind === "featured") ?? null;
  const archiveRow = active.find((row) => row.kind === "archive") ?? null;
  const currentDailyCap = Math.min(
    ...active.map((row) => row.daily_contribution_cap),
    Number.MAX_SAFE_INTEGER,
  );
  const pastRows = rows
    .filter((row) => row.kind === "featured" && !isContributable(row, input.now))
    .slice(0, 20);

  return {
    status: "ready",
    featured: featuredRow ? exhibitView(featuredRow, contributionByExhibit, input.now) : null,
    archive: archiveRow ? exhibitView(archiveRow, contributionByExhibit, input.now) : null,
    past: pastRows.map((row) => pastView(row, contributionByExhibit)),
    todayContributedUnits: today?.total ?? 0,
    dailyContributionCap: currentDailyCap === Number.MAX_SAFE_INTEGER ? 0 : currentDailyCap,
    version: rows.reduce((version, row) => version + row.version, 0),
  };
}

function exhibitView(
  row: ExhibitRow,
  contributions: ReadonlyMap<string, number>,
  now: Date,
): ExhibitView {
  const personal = contributions.get(row.id) ?? 0;
  const communityComplete = row.total_units >= row.target_units;
  const qualified = personal >= row.personal_minimum_units;
  const closed = !isContributable(row, now);
  return {
    id: row.slug,
    title: row.title,
    seasonLabel: row.season_label,
    imagePath: row.image_path,
    progressBasisPoints: progressBasisPoints(row.total_units, row.target_units),
    personalContribution: personal,
    personalMinimum: row.personal_minimum_units,
    daysRemaining: row.ends_at
      ? Math.max(0, Math.ceil((row.ends_at.getTime() - now.getTime()) / 86_400_000))
      : null,
    communityComplete,
    qualified,
    unlocked: communityComplete && qualified,
    closed,
  };
}

function pastView(row: ExhibitRow, contributions: ReadonlyMap<string, number>): PastExhibitView {
  const personal = contributions.get(row.id) ?? 0;
  const communityComplete = row.total_units >= row.target_units;
  const qualified = personal >= row.personal_minimum_units;
  const unlocked = communityComplete && qualified;
  return {
    id: row.slug,
    title: row.title,
    seasonLabel: row.season_label,
    imagePath: row.image_path,
    unlocked,
    lockedReason: unlocked ? null : communityComplete ? "Minimum not met" : "Community goal missed",
  };
}

function isContributable(row: ExhibitRow, now: Date): boolean {
  if (row.status !== "open" || row.starts_at.getTime() > now.getTime()) return false;
  return row.ends_at === null || row.ends_at.getTime() > now.getTime();
}
