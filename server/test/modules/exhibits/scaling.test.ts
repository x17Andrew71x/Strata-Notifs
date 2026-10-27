import { describe, expect, it } from "vitest";
import {
  COMMUNITY_CREDIT_SCALE,
  ExhibitsRequestError,
  contributionCreditMicrounits,
  contributionMultiplierMicros,
  effectiveCommunityUsers,
  isPlausibleLocalDate,
  progressBasisPoints,
} from "../../../src/modules/exhibits/repository.js";

describe("exhibit contribution scaling", () => {
  it("scales newly accepted contributions across the approved 100 to 10000 user range", () => {
    expect(effectiveCommunityUsers(0)).toBe(100);
    expect(effectiveCommunityUsers(99)).toBe(100);
    expect(effectiveCommunityUsers(250)).toBe(250);
    expect(effectiveCommunityUsers(10_000)).toBe(10_000);
    expect(effectiveCommunityUsers(12_000)).toBe(10_000);

    expect(contributionMultiplierMicros(100)).toBe(100 * COMMUNITY_CREDIT_SCALE);
    expect(contributionMultiplierMicros(1_000)).toBe(10 * COMMUNITY_CREDIT_SCALE);
    expect(contributionMultiplierMicros(5_000)).toBe(2 * COMMUNITY_CREDIT_SCALE);
    expect(contributionMultiplierMicros(7_500)).toBe(1_333_333);
    expect(contributionMultiplierMicros(10_000)).toBe(COMMUNITY_CREDIT_SCALE);
    expect(contributionCreditMicrounits(18, 100)).toBe(1_800 * COMMUNITY_CREDIT_SCALE);
  });

  it("keeps historical credit fixed while revealing aggregate microcredit progress", () => {
    expect(progressBasisPoints(0, 500_000)).toBe(0);
    expect(progressBasisPoints(282_300 * COMMUNITY_CREDIT_SCALE, 500_000)).toBe(5_646);
    expect(progressBasisPoints(500_000 * COMMUNITY_CREDIT_SCALE, 500_000)).toBe(10_000);
    expect(progressBasisPoints(510_000 * COMMUNITY_CREDIT_SCALE, 500_000)).toBe(10_000);
  });

  it("rejects malformed private counters rather than exposing a bad percentage", () => {
    expect(() => contributionMultiplierMicros(-1)).toThrow(ExhibitsRequestError);
    expect(() => contributionCreditMicrounits(0, 100)).toThrow(ExhibitsRequestError);
    expect(() => progressBasisPoints(1, 0)).toThrow(ExhibitsRequestError);
    expect(() => progressBasisPoints(-1, 500_000)).toThrow(ExhibitsRequestError);
  });

  it("accepts only plausible local dates around the server UTC day", () => {
    const now = new Date("2026-10-26T23:30:00Z");
    expect(isPlausibleLocalDate("2026-10-25", now)).toBe(true);
    expect(isPlausibleLocalDate("2026-10-26", now)).toBe(true);
    expect(isPlausibleLocalDate("2026-10-27", now)).toBe(true);
    expect(isPlausibleLocalDate("2026-10-24", now)).toBe(false);
    expect(isPlausibleLocalDate("2026-11-26", now)).toBe(false);
    expect(isPlausibleLocalDate("2026-02-30", now)).toBe(false);
  });
});
