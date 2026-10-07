import { readdir, readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const Ajv2020 = require("ajv/dist/2020.js").default;
const addFormats = require("ajv-formats").default;

const here = dirname(fileURLToPath(import.meta.url));
const contractsRoot = join(here, "../../contracts");
const eventsRoot = join(contractsRoot, "events/v1");
const envelopeSchemaId = "https://afterchime.invalid/contracts/events/v1/envelope.schema.json";

const initialEventNames = [
  "installation_created",
  "app_opened",
  "session_started",
  "session_ended",
  "app_backgrounded",
  "app_updated",
  "auth_session_created",
  "auth_session_refreshed",
  "online_mode_disabled",
  "onboarding_started",
  "onboarding_step_viewed",
  "onboarding_completed",
  "notification_access_prompted",
  "notification_access_result",
  "analytics_consent_changed",
  "notification_aggregate_consent_changed",
  "screen_viewed",
  "tab_selected",
  "help_opened",
  "setting_changed",
  "share_started",
  "share_completed",
  "share_failed",
  "formation_viewed",
  "day_sealed",
  "specimen_generated",
  "specimen_reveal_started",
  "specimen_revealed",
  "specimen_locked",
  "specimen_unlocked",
  "weekly_diorama_created",
  "museum_viewed",
  "museum_filter_changed",
  "specimen_detail_viewed",
  "combine_previewed",
  "combine_completed",
  "combine_cancelled",
  "community_art_viewed",
  "world_selector_opened",
  "world_previewed",
  "store_viewed",
  "product_viewed",
  "checkout_started",
  "checkout_result",
  "entitlements_restored",
  "owned_world_applied",
] as const;

async function readJson(path: string): Promise<unknown> {
  return JSON.parse(await readFile(path, "utf8")) as unknown;
}

async function fixtureDocuments(kind: "valid" | "invalid"): Promise<unknown[]> {
  const directory = join(contractsRoot, "fixtures", kind);
  const names = (await readdir(directory)).filter((name) => name.endsWith(".json")).sort();
  return Promise.all(names.map(async (name) => readJson(join(directory, name))));
}

async function validator() {
  const schemaNames = (await readdir(eventsRoot))
    .filter((name) => name.endsWith(".schema.json"))
    .sort();
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);

  for (const name of schemaNames) {
    ajv.addSchema(await readJson(join(eventsRoot, name)));
  }

  const validate = ajv.getSchema(envelopeSchemaId);
  if (!validate) {
    throw new Error("analytics envelope schema was not registered");
  }
  return validate;
}

describe("analytics contract v1", () => {
  it("accepts one strict fixture for every initial lifecycle and onboarding event", async () => {
    const validate = await validator();
    const fixtures = await fixtureDocuments("valid");

    expect(fixtures).toHaveLength(initialEventNames.length);
    expect(
      fixtures.map((fixture) => (fixture as { event_name?: unknown }).event_name).sort(),
    ).toEqual([...initialEventNames].sort());
    for (const fixture of fixtures) {
      expect(validate(fixture)).toBe(true);
    }
  });

  it("links the bounded analytics batch request to the canonical v1 envelope", async () => {
    const openapi = await readJson(join(contractsRoot, "openapi", "afterchime-v1.yaml"));

    expect(openapi).toMatchObject({
      openapi: "3.1.1",
      paths: {
        "/v1/analytics/events:batch": {
          post: {
            requestBody: {
              content: {
                "application/json": {
                  schema: {
                    additionalProperties: false,
                    properties: {
                      events: {
                        maxItems: 50,
                        items: { $ref: "../events/v1/envelope.schema.json" },
                      },
                    },
                  },
                },
              },
            },
          },
        },
      },
    });
  });

  it("rejects unknown events, unknown properties, unsupported schema versions, and oversized values", async () => {
    const validate = await validator();
    const fixtures = await fixtureDocuments("invalid");

    expect(fixtures).toHaveLength(4);
    for (const fixture of fixtures) {
      expect(validate(fixture)).toBe(false);
    }
  });

  it("binds each invalid shared fixture to the rejection it is intended to cover", async () => {
    const validate = await validator();
    const expectedKeywords = new Map([
      ["01-unknown-event.json", "oneOf"],
      ["02-unknown-property.json", "additionalProperties"],
      ["03-unsupported-schema-version.json", "const"],
      ["04-oversized-value.json", "maxLength"],
    ]);

    for (const [name, expectedKeyword] of expectedKeywords) {
      const fixture = await readJson(join(contractsRoot, "fixtures", "invalid", name));
      expect(validate(fixture)).toBe(false);
      expect(
        validate.errors?.some((error: { keyword?: unknown }) => error.keyword === expectedKeyword),
      ).toBe(true);
    }
  });
});
