import { readFile } from "node:fs/promises";
import path from "node:path";
import { describe, expect, it } from "vitest";

describe("startup document", () => {
  it("holds the hosted shell on literal black until application state is ready", async () => {
    const html = await readFile(path.resolve(process.cwd(), "index.html"), "utf8");

    expect(html).toContain('<html lang="en" class="app-loading">');
    expect(html).toContain('<meta name="theme-color" content="#000000" />');
    expect(html).toContain("html.app-loading body > *");
    expect(html).toContain("visibility: hidden !important;");
    expect(html).toContain("background: #000000 !important;");
  });
});
