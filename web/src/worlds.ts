import type { World } from "./bridge";

export type WorldTheme = "relic" | "field" | "control" | "atlas";

export type Artifact = Readonly<{
  name: string;
  image: string;
  description: string;
}>;

export type WorldDefinition = Readonly<{
  id: World;
  name: string;
  theme: WorldTheme;
  baseline: boolean;
  eyebrow: string;
  description: string;
  collectionName: string;
  formingCopy: string;
  artifacts: readonly [Artifact, Artifact, Artifact];
}>;

export const WORLD_DEFINITIONS: readonly WorldDefinition[] = [
  {
    id: "PRIMEVAL_STRATA",
    name: "Relic Vault",
    theme: "relic",
    baseline: true,
    eyebrow: "The founding collection",
    description: "Fossil reliefs held in a severe, ceremonial museum of bronze and black stone.",
    collectionName: "Vault",
    formingCopy: "A new relief is being pressed into the archive. It will be ready tomorrow.",
    artifacts: [
      {
        name: "Fossil Choir",
        image: "/worlds/relic-fossil-choir-a885cad842a6.jpg",
        description: "Compressed shells arranged as one ancient chorus.",
      },
      {
        name: "Abyssal Glass",
        image: "/worlds/relic-abyssal-glass-a7fd0353491a.jpg",
        description: "A dark vitreous relic with a pale fossil heart.",
      },
      {
        name: "Lunar Ash",
        image: "/worlds/relic-lunar-ash-c08cb38e7b92.jpg",
        description: "Bone-white impressions suspended in charcoal ash.",
      },
    ],
  },
  {
    id: "BOTANICAL_ARCHIVE",
    name: "Living Field Station",
    theme: "field",
    baseline: false,
    eyebrow: "Naturalist field archive",
    description: "Pressed specimens, weathered paper, handwritten catalogues, and living terrain.",
    collectionName: "Field Archive",
    formingCopy: "Today’s field specimen is settling onto its plate. It will be ready tomorrow.",
    artifacts: [
      {
        name: "Verdant Crown",
        image: "/worlds/field-verdant-crown-16cd41ad4394.jpg",
        description: "A dense canopy study cut through with new growth.",
      },
      {
        name: "Tidal Archive",
        image: "/worlds/field-tidal-archive-7870aabaf855.jpg",
        description: "A coastal plate of tide pools, shells, and salt-worn forms.",
      },
      {
        name: "Cinder Vale",
        image: "/worlds/field-cinder-vale-baba2fad26b5.jpg",
        description: "A dry volcanic survey where ember flora takes root.",
      },
    ],
  },
  {
    id: "DEEP_SPACE",
    name: "Expedition Control",
    theme: "control",
    baseline: false,
    eyebrow: "Scientific signal programme",
    description: "Disciplined instrument graphics, measured channels, and expedition telemetry.",
    collectionName: "Signal Archive",
    formingCopy: "Today’s signal is resolving through the instrument array. It will lock tomorrow.",
    artifacts: [
      {
        name: "Canopy Frequency",
        image: "/worlds/control-canopy-frequency-264930918583.jpg",
        description: "A living frequency rendered as a precise field instrument.",
      },
      {
        name: "Pelagic Channel",
        image: "/worlds/control-pelagic-channel-679685abf5a2.jpg",
        description: "A deep-water transmission traced across a disciplined console.",
      },
      {
        name: "Lunar Silence",
        image: "/worlds/control-lunar-silence-af1ea8ce38ec.jpg",
        description: "A near-silent survey whose smallest readings carry the record.",
      },
    ],
  },
  {
    id: "THE_ABYSS",
    name: "Charcoal Atlas",
    theme: "atlas",
    baseline: false,
    eyebrow: "Terrestrial survey folio",
    description: "Charcoal-grey land studies, contour marks, cool paper, and restrained rust ink.",
    collectionName: "Atlas",
    formingCopy:
      "Today’s terrain is being fixed in charcoal. The finished survey arrives tomorrow.",
    artifacts: [
      {
        name: "Hollow Range",
        image: "/worlds/atlas-hollow-range-40fffccf4155.jpg",
        description: "A folded mountain survey cut by a silent interior valley.",
      },
      {
        name: "Ember Roads",
        image: "/worlds/atlas-ember-roads-fe87b2c52620.jpg",
        description: "Rust-red routes crossing a soot-dark terrestrial plan.",
      },
      {
        name: "White Quarry",
        image: "/worlds/atlas-white-quarry-40e3ba96733d.jpg",
        description: "A pale excavation mapped against compressed graphite ground.",
      },
    ],
  },
] as const;

const WORLD_BY_ID = new Map(WORLD_DEFINITIONS.map((world) => [world.id, world]));

export const WORLD_ASSET_PATHS = WORLD_DEFINITIONS.flatMap((world) =>
  world.artifacts.map((artifact) => artifact.image),
);

export function worldDefinition(world: World): WorldDefinition {
  return WORLD_BY_ID.get(world) ?? WORLD_DEFINITIONS[0];
}

export function artifactFor(world: World, specimenKey = "decorative"): Artifact {
  const definition = worldDefinition(world);
  if (specimenKey === "decorative") return definition.artifacts[0];
  let hash = 2166136261;
  for (let index = 0; index < specimenKey.length; index += 1) {
    hash ^= specimenKey.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return definition.artifacts[(hash >>> 0) % definition.artifacts.length];
}
