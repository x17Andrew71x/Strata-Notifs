import { readFile } from "node:fs/promises";
import path from "node:path";
import { describe, expect, it } from "vitest";

const styles = await readFile(path.resolve(process.cwd(), "src/style.css"), "utf8");

function declarationBlock(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = styles.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, "s"));
  if (!match) throw new Error(`Missing CSS rule for ${selector}`);
  return match[1];
}

function lastDeclarationBlock(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const matches = [...styles.matchAll(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, "gs"))];
  const match = matches.at(-1);
  if (!match) throw new Error(`Missing CSS rule for ${selector}`);
  return match[1];
}

describe("Museum rarity card styling", () => {
  it("clips the image inside its frame while keeping the caption below it", () => {
    const frame = declarationBlock(".specimen-frame");
    const caption = declarationBlock(".specimen-visual figcaption");

    expect(frame).toContain("overflow: hidden");
    expect(frame).toContain("border-radius: 18px");
    expect(caption).toContain("margin-top: 10px");
    expect(caption).not.toContain("position: absolute");
  });

  it("keeps the rarity glow compact while preserving its low-medium-high ladder", () => {
    expect(declarationBlock(".specimen-card.rarity-common")).toContain(
      "--rarity-glow-far-size: 15px",
    );
    expect(declarationBlock(".specimen-card.rarity-uncommon")).toContain(
      "--rarity-glow-far-size: 25px",
    );
    expect(declarationBlock(".specimen-card.rarity-rare")).toContain(
      "--rarity-glow-far-size: 36px",
    );
  });

  it("keeps the existing card treatment without adding a second image border", () => {
    const frame = declarationBlock(".specimen-card .specimen-frame");
    expect(frame).toContain("border: 0");
    expect(frame).toContain("box-shadow: none");
  });
});

describe("excavation motion", () => {
  it("keeps dig statistics above and outside the excavation bed", () => {
    const excavation = declarationBlock(".excavation");
    const excavationBed = declarationBlock(".excavation-bed");
    const hud = declarationBlock(".excavation-hud");

    expect(excavation).toContain("display: flex");
    expect(excavation).toContain("flex-direction: column");
    expect(excavationBed).toContain("position: relative");
    expect(hud).not.toContain("position: absolute");
    expect(hud).not.toContain("z-index:");
  });

  it("rounds every corner tile to follow the fossil beneath the excavation bed", () => {
    expect(declarationBlock(".excavation-tile-corner-top-left")).toContain(
      "border-top-left-radius: 15px",
    );
    expect(declarationBlock(".excavation-tile-corner-top-right")).toContain(
      "border-top-right-radius: 15px",
    );
    expect(declarationBlock(".excavation-tile-corner-bottom-left")).toContain(
      "border-bottom-left-radius: 15px",
    );
    expect(declarationBlock(".excavation-tile-corner-bottom-right")).toContain(
      "border-bottom-right-radius: 15px",
    );
  });

  it("gives new digs a bounded brush-away, grit burst, and energy pulse", () => {
    expect(declarationBlock(".excavation-tile.dug.is-clearing")).toContain(
      "animation: excavation-brush-away 600ms",
    );
    expect(declarationBlock(".excavation-tile.dug.is-clearing::before")).toContain(
      "animation: excavation-brush-sweep 600ms",
    );
    expect(lastDeclarationBlock(".excavation-tile.dug.is-clearing::after")).toContain(
      "animation: excavation-grit 600ms",
    );
    expect(declarationBlock(".excavation-hud strong.energy-gained")).toContain(
      "animation: dig-energy-gain 700ms",
    );
  });

  it("lifts a dark cover through bronze light for the final specimen reveal", () => {
    expect(declarationBlock(".specimen-visual.specimen-reveal .specimen-frame")).toContain(
      "animation: specimen-reveal 1.4s",
    );
    expect(declarationBlock(".specimen-visual.specimen-reveal .specimen-frame::before")).toContain(
      "animation: specimen-bronze-light 1.4s",
    );
    expect(styles).toContain("animation: specimen-cover-lift 1.4s");
  });

  it("keeps the existing reduce-motion override as the master off switch", () => {
    expect(styles).toContain(".reduce-motion *");
    expect(styles).toContain(".reduce-motion .excavation-tile.dug.is-clearing");
    expect(styles).toContain(".reduce-motion .excavation-hud strong.energy-gained");
    expect(styles).toContain(
      ".reduce-motion .specimen-visual.specimen-reveal .specimen-frame::after",
    );
    expect(declarationBlock(".reduce-motion .excavation-tile")).toContain("transition: none");
  });
});
