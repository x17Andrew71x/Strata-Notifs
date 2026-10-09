import type { Tier, World } from "./bridge";

export type WorldTheme = "relic" | "field" | "control" | "atlas";

export type Artifact = Readonly<{
  id: string;
  name: string;
  museumImage: string;
  excavationImage: string;
  tier: Tier;
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
        id: "relic-fossil-choir",
        name: "Fossil Choir",
        museumImage: "/worlds/relic-fossil-choir-museum-4089a53325a8.jpg",
        excavationImage: "/worlds/relic-fossil-choir-excavation-fb847346a7cf.jpg",
        tier: "UNCOMMON",
        description: "Compressed shells arranged as one ancient chorus.",
      },
      {
        id: "relic-abyssal-glass",
        name: "Abyssal Glass",
        museumImage: "/worlds/relic-abyssal-glass-museum-f65a8e00a8e4.jpg",
        excavationImage: "/worlds/relic-abyssal-glass-excavation-464607e244ad.jpg",
        tier: "RARE",
        description: "A dark vitreous relic with a pale fossil heart.",
      },
      {
        id: "relic-lunar-ash",
        name: "Lunar Ash",
        museumImage: "/worlds/relic-lunar-ash-museum-b6b6f2680c29.jpg",
        excavationImage: "/worlds/relic-lunar-ash-excavation-8e46d17e54d6.jpg",
        tier: "COMMON",
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
        id: "field-verdant-crown",
        name: "Verdant Crown",
        museumImage: "/worlds/field-verdant-crown-16cd41ad4394.jpg",
        excavationImage: "/worlds/field-verdant-crown-16cd41ad4394.jpg",
        tier: "COMMON",
        description: "A dense canopy study cut through with new growth.",
      },
      {
        id: "field-tidal-archive",
        name: "Tidal Archive",
        museumImage: "/worlds/field-tidal-archive-7870aabaf855.jpg",
        excavationImage: "/worlds/field-tidal-archive-7870aabaf855.jpg",
        tier: "UNCOMMON",
        description: "A coastal plate of tide pools, shells, and salt-worn forms.",
      },
      {
        id: "field-cinder-vale",
        name: "Cinder Vale",
        museumImage: "/worlds/field-cinder-vale-baba2fad26b5.jpg",
        excavationImage: "/worlds/field-cinder-vale-baba2fad26b5.jpg",
        tier: "RARE",
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
        id: "control-canopy-frequency",
        name: "Canopy Frequency",
        museumImage: "/worlds/control-canopy-frequency-264930918583.jpg",
        excavationImage: "/worlds/control-canopy-frequency-264930918583.jpg",
        tier: "COMMON",
        description: "A living frequency rendered as a precise field instrument.",
      },
      {
        id: "control-pelagic-channel",
        name: "Pelagic Channel",
        museumImage: "/worlds/control-pelagic-channel-679685abf5a2.jpg",
        excavationImage: "/worlds/control-pelagic-channel-679685abf5a2.jpg",
        tier: "UNCOMMON",
        description: "A deep-water transmission traced across a disciplined console.",
      },
      {
        id: "control-lunar-silence",
        name: "Lunar Silence",
        museumImage: "/worlds/control-lunar-silence-af1ea8ce38ec.jpg",
        excavationImage: "/worlds/control-lunar-silence-af1ea8ce38ec.jpg",
        tier: "RARE",
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
        id: "atlas-hollow-range",
        name: "Hollow Range",
        museumImage: "/worlds/atlas-hollow-range-40fffccf4155.jpg",
        excavationImage: "/worlds/atlas-hollow-range-40fffccf4155.jpg",
        tier: "COMMON",
        description: "A folded mountain survey cut by a silent interior valley.",
      },
      {
        id: "atlas-ember-roads",
        name: "Ember Roads",
        museumImage: "/worlds/atlas-ember-roads-fe87b2c52620.jpg",
        excavationImage: "/worlds/atlas-ember-roads-fe87b2c52620.jpg",
        tier: "UNCOMMON",
        description: "Rust-red routes crossing a soot-dark terrestrial plan.",
      },
      {
        id: "atlas-white-quarry",
        name: "White Quarry",
        museumImage: "/worlds/atlas-white-quarry-40e3ba96733d.jpg",
        excavationImage: "/worlds/atlas-white-quarry-40e3ba96733d.jpg",
        tier: "RARE",
        description: "A pale excavation mapped against compressed graphite ground.",
      },
    ],
  },
] as const;

const WORLD_BY_ID = new Map(WORLD_DEFINITIONS.map((world) => [world.id, world]));
const ARTIFACT_BY_ID = new Map(
  WORLD_DEFINITIONS.flatMap((world) => world.artifacts).map((artifact) => [artifact.id, artifact]),
);

export const WORLD_ASSET_PATHS = [
  ...new Set(
    WORLD_DEFINITIONS.flatMap((world) =>
      world.artifacts.flatMap((artifact) => [artifact.museumImage, artifact.excavationImage]),
    ),
  ),
];

export function worldDefinition(world: World): WorldDefinition {
  return WORLD_BY_ID.get(world) ?? WORLD_DEFINITIONS[0];
}

export function artifactById(id: string | null | undefined): Artifact | null {
  return id ? (ARTIFACT_BY_ID.get(id) ?? null) : null;
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
