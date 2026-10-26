export const EXHIBIT_GRID_SIZE = 320;
export const EXHIBIT_CELL_COUNT = EXHIBIT_GRID_SIZE * EXHIBIT_GRID_SIZE;
export const EXHIBIT_PROGRESS_MAX_BASIS_POINTS = 10_000;

function seededNoise(x: number, y: number): number {
  const value = Math.sin(x * 12.9898 + y * 78.233 + 4.731) * 43_758.5453;
  return value - Math.floor(value);
}

/**
 * One immutable rank per logical reveal cell. The rank order forms a broad diagonal restoration
 * front with small-scale scatter rather than independent holes.
 */
export function buildExhibitRevealRanks(): Uint32Array {
  const scores = new Float32Array(EXHIBIT_CELL_COUNT);
  const order = Array.from({ length: EXHIBIT_CELL_COUNT }, (_, index) => index);
  for (let index = 0; index < EXHIBIT_CELL_COUNT; index += 1) {
    const column = index % EXHIBIT_GRID_SIZE;
    const row = Math.floor(index / EXHIBIT_GRID_SIZE);
    const x = (column + 0.5) / EXHIBIT_GRID_SIZE;
    const y = (row + 0.5) / EXHIBIT_GRID_SIZE;
    const diagonal = x * 0.52 + y * 0.48;
    const broadEdge =
      Math.sin(y * 19 + Math.sin(x * 11) * 1.4) * 0.024 + Math.cos(x * 24 + y * 7) * 0.016;
    const smallCluster = (seededNoise(Math.floor(column / 6), Math.floor(row / 6)) - 0.5) * 0.035;
    const pixelScatter = (seededNoise(column, row) - 0.5) * 0.018;
    scores[index] = diagonal + broadEdge + smallCluster + pixelScatter;
  }
  order.sort((left, right) => scores[left] - scores[right] || left - right);
  const ranks = new Uint32Array(EXHIBIT_CELL_COUNT);
  order.forEach((pixel, rank) => {
    ranks[pixel] = rank;
  });
  return ranks;
}

let cachedRanks: Uint32Array | null = null;

export function exhibitRevealRanks(): Uint32Array {
  cachedRanks ??= buildExhibitRevealRanks();
  return cachedRanks;
}

export function revealedCellsForBasisPoints(basisPoints: number): number {
  if (!Number.isSafeInteger(basisPoints) || basisPoints < 0 || basisPoints > 10_000) {
    throw new Error("exhibit_progress_invalid");
  }
  return Math.floor((basisPoints * EXHIBIT_CELL_COUNT) / EXHIBIT_PROGRESS_MAX_BASIS_POINTS);
}

export function formatProgressBasisPoints(basisPoints: number): string {
  if (!Number.isSafeInteger(basisPoints) || basisPoints < 0 || basisPoints > 10_000) {
    throw new Error("exhibit_progress_invalid");
  }
  return `${(basisPoints / 100).toFixed(2)}%`;
}
