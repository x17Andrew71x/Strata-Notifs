import { describe, expect, it } from "vitest";
import {
  buildExhibitRevealRanks,
  EXHIBIT_CELL_COUNT,
  formatProgressBasisPoints,
  revealedCellsForBasisPoints,
} from "./exhibits";

describe("Exhibits reveal mapping", () => {
  it("maps private contribution scaling onto the fixed public reveal grid", () => {
    expect(EXHIBIT_CELL_COUNT).toBe(102_400);
    expect(revealedCellsForBasisPoints(0)).toBe(0);
    expect(revealedCellsForBasisPoints(5_646)).toBe(57_815);
    expect(revealedCellsForBasisPoints(10_000)).toBe(EXHIBIT_CELL_COUNT);
    expect(formatProgressBasisPoints(5_646)).toBe("56.46%");
  });

  it("builds one unique rank per cell with a top-left to bottom-right reveal", () => {
    const ranks = buildExhibitRevealRanks();
    expect(ranks).toHaveLength(EXHIBIT_CELL_COUNT);
    expect(new Set(ranks).size).toBe(EXHIBIT_CELL_COUNT);
    expect(ranks[0]).toBeLessThan(ranks[ranks.length - 1]);

    const shownAt = (x: number, y: number) => ranks[y * 320 + x] < EXHIBIT_CELL_COUNT / 2;
    expect(shownAt(20, 20)).toBe(true);
    expect(shownAt(299, 299)).toBe(false);
  });
});
