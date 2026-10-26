import { describe, expect, it } from "vitest";
import {
  ExhibitsRequestError,
  isPlausibleLocalDate,
  progressBasisPoints,
} from "../../../src/modules/exhibits/repository.js";

describe("exhibit contribution scaling", () => {
  it("decouples the fixed reveal grid from an immutable season contribution target", () => {
    expect(progressBasisPoints(0, 5_000)).toBe(0);
    expect(progressBasisPoints(2_823, 5_000)).toBe(5_646);
    expect(progressBasisPoints(5_000, 5_000)).toBe(10_000);
    expect(progressBasisPoints(5_100, 5_000)).toBe(10_000);
  });

  it("rejects malformed private counters rather than exposing a bad percentage", () => {
    expect(() => progressBasisPoints(1, 0)).toThrow(ExhibitsRequestError);
    expect(() => progressBasisPoints(-1, 5_000)).toThrow(ExhibitsRequestError);
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
