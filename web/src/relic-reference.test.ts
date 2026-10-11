import { describe, expect, it } from "vitest";
import { REFERENCE_THRESHOLDS, referenceNotesFor } from "./relic-reference";
import { RELIC_VAULT_ARTIFACTS } from "./worlds";

describe("Relic Vault reference series", () => {
  it("provides three sourced research unlocks for every approved fossil", () => {
    for (const artifact of RELIC_VAULT_ARTIFACTS) {
      const notes = referenceNotesFor(artifact.id);
      expect(notes.map(({ requiredOwned }) => requiredOwned)).toEqual(REFERENCE_THRESHOLDS);
      expect(notes.map(({ label }) => label)).toEqual(["Identity", "Fossil record", "Recognition"]);
      expect(notes.every(({ text }) => text.length >= 40)).toBe(true);
      expect(notes.every(({ source }) => source.length >= 3)).toBe(true);
      expect(notes.every(({ sourceUrl }) => sourceUrl.startsWith("https://"))).toBe(true);
    }
  });

  it("does not invent reference notes for non-fossil worlds", () => {
    expect(referenceNotesFor("field-verdant-crown")).toEqual([]);
  });
});
