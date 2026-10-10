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

describe("Museum rarity card styling", () => {
  it("keeps the rarity glow compact while preserving its low-medium-high ladder", () => {
    expect(declarationBlock(".specimen-card.rarity-common")).toContain(
      "--rarity-glow-far-size: 12px",
    );
    expect(declarationBlock(".specimen-card.rarity-uncommon")).toContain(
      "--rarity-glow-far-size: 20px",
    );
    expect(declarationBlock(".specimen-card.rarity-rare")).toContain(
      "--rarity-glow-far-size: 28px",
    );
  });

  it("uses one neutral dark image frame for every Museum rarity", () => {
    const frame = declarationBlock(".specimen-card .specimen-visual");
    expect(frame).toContain("border-color: #303230");
    expect(frame).toContain("box-shadow:");
  });
});
