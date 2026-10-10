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
  artifacts: readonly Artifact[];
}>;

export const RELIC_VAULT_ARTIFACTS = [
  {
    id: "relic-dactylioceras-ammonite",
    name: "Ribbed Jurassic Ammonite",
    museumImage: "/worlds/relic-dactylioceras-ammonite-museum-07245dd61006.jpg",
    excavationImage: "/worlds/relic-dactylioceras-ammonite-excavation-df9a1e512a2e.jpg",
    tier: "COMMON",
    description: "A ribbed Jurassic ammonite shell weathered in compact marine limestone.",
  },
  {
    id: "relic-belemnite-rostra",
    name: "Belemnite Rostra",
    museumImage: "/worlds/relic-belemnite-rostra-museum-266fa31b2823.jpg",
    excavationImage: "/worlds/relic-belemnite-rostra-excavation-3cd7baaa3bd3.jpg",
    tier: "COMMON",
    description: "Three bullet-shaped calcitic guards preserved together in Jurassic limestone.",
  },
  {
    id: "relic-spiriferid-brachiopod",
    name: "Spiriferid Brachiopod",
    museumImage: "/worlds/relic-spiriferid-brachiopod-museum-8599c160ed48.jpg",
    excavationImage: "/worlds/relic-spiriferid-brachiopod-excavation-3cbf83503874.jpg",
    tier: "COMMON",
    description: "A broad Palaeozoic shell with a central fold and strong radiating ribs.",
  },
  {
    id: "relic-gryphaea-oyster",
    name: "Gryphaea Oyster",
    museumImage: "/worlds/relic-gryphaea-oyster-museum-6b486d5a5ae9.jpg",
    excavationImage: "/worlds/relic-gryphaea-oyster-excavation-9284358ae77c.jpg",
    tier: "COMMON",
    description: "A heavy Jurassic oyster valve with a recurved beak and layered growth bands.",
  },
  {
    id: "relic-crinoid-columnals",
    name: "Crinoid Columnals",
    museumImage: "/worlds/relic-crinoid-columnals-museum-a31486e257b8.jpg",
    excavationImage: "/worlds/relic-crinoid-columnals-excavation-450ea7925a62.jpg",
    tier: "COMMON",
    description: "A short crinoid stem with loose star-holed calcite columnals.",
  },
  {
    id: "relic-rugose-horn-coral",
    name: "Rugose Horn Coral",
    museumImage: "/worlds/relic-rugose-horn-coral-museum-e9636c0b7768.jpg",
    excavationImage: "/worlds/relic-rugose-horn-coral-excavation-a7106a236fb8.jpg",
    tier: "COMMON",
    description: "A solitary Palaeozoic horn coral with radial septa visible in its calice.",
  },
  {
    id: "relic-lamniform-shark-tooth",
    name: "Lamniform Shark Tooth",
    museumImage: "/worlds/relic-lamniform-shark-tooth-museum-68e30be26fe3.jpg",
    excavationImage: "/worlds/relic-lamniform-shark-tooth-excavation-8d15042e0105.jpg",
    tier: "COMMON",
    description: "A modest fossil shark tooth with a mineralised crown and porous root.",
  },
  {
    id: "relic-carbonised-fern-frond",
    name: "Carbonised Fern Frond",
    museumImage: "/worlds/relic-carbonised-fern-frond-museum-8d3ae7438557.jpg",
    excavationImage: "/worlds/relic-carbonised-fern-frond-excavation-152d3a6ffff1.jpg",
    tier: "COMMON",
    description: "A flat carbon film preserving a branching frond across split shale.",
  },
  {
    id: "relic-domal-stromatolite",
    name: "Domal Stromatolite",
    museumImage: "/worlds/relic-domal-stromatolite-museum-a7338bacd3fa.jpg",
    excavationImage: "/worlds/relic-domal-stromatolite-excavation-c5163e7036a4.jpg",
    tier: "COMMON",
    description: "Nested microbial laminae preserved in the section of an ancient stone dome.",
  },
  {
    id: "relic-echinocorys-echinoid",
    name: "Chalk Echinoid",
    museumImage: "/worlds/relic-echinocorys-echinoid-museum-d03bc3c73a92.jpg",
    excavationImage: "/worlds/relic-echinocorys-echinoid-excavation-e40eefb92545.jpg",
    tier: "COMMON",
    description: "An oval chalk echinoid test with five petaloid pore bands.",
  },
  {
    id: "relic-articulated-trilobite",
    name: "Articulated Trilobite",
    museumImage: "/worlds/relic-articulated-trilobite-museum-5d6a97612201.jpg",
    excavationImage: "/worlds/relic-articulated-trilobite-excavation-727119dbe563.jpg",
    tier: "UNCOMMON",
    description: "A complete trilobite whose cephalon, thorax and pygidium remain articulated.",
  },
  {
    id: "relic-articulated-fossil-fish",
    name: "Articulated Fossil Fish",
    museumImage: "/worlds/relic-articulated-fossil-fish-museum-2a21e3feb7da.jpg",
    excavationImage: "/worlds/relic-articulated-fossil-fish-excavation-c9d7a358910a.jpg",
    tier: "UNCOMMON",
    description: "A small ray-finned fish with its skull, spine, ribs and fin rays in life order.",
  },
  {
    id: "relic-complete-starfish",
    name: "Complete Fossil Starfish",
    museumImage: "/worlds/relic-complete-starfish-museum-1734b61d2fa3.jpg",
    excavationImage: "/worlds/relic-complete-starfish-excavation-e09cdf8b3af6.jpg",
    tier: "UNCOMMON",
    description: "A whole fossil starfish with five connected arms and articulated ossicles.",
  },
  {
    id: "relic-articulated-fossil-crab",
    name: "Articulated Fossil Crab",
    museumImage: "/worlds/relic-articulated-fossil-crab-museum-ef7aed36704b.jpg",
    excavationImage: "/worlds/relic-articulated-fossil-crab-excavation-a96dda0a68fe.jpg",
    tier: "UNCOMMON",
    description: "A brachyuran crab with its carapace, claws and walking legs still associated.",
  },
  {
    id: "relic-insect-amber",
    name: "Insect in Amber",
    museumImage: "/worlds/relic-insect-amber-museum-1c9d8be45749.jpg",
    excavationImage: "/worlds/relic-insect-amber-excavation-05f377832582.jpg",
    tier: "UNCOMMON",
    description: "A small winged insect held in cloudy fossil resin beneath weathered crust.",
  },
  {
    id: "relic-dinosaur-embryo-egg",
    name: "Dinosaur Embryo in Egg",
    museumImage: "/worlds/relic-dinosaur-embryo-egg-museum-bb49f06000bc.jpg",
    excavationImage: "/worlds/relic-dinosaur-embryo-egg-excavation-84b7863fdbf4.jpg",
    tier: "RARE",
    description: "A naturally fractured fossil egg preserving a curled dinosaur embryo within.",
  },
  {
    id: "relic-archaeopteryx-slab",
    name: "Archaeopteryx Slab",
    museumImage: "/worlds/relic-archaeopteryx-slab-museum-2f4084bf3ebb.jpg",
    excavationImage: "/worlds/relic-archaeopteryx-slab-excavation-d5ae028c3a42.jpg",
    tier: "RARE",
    description:
      "An articulated Archaeopteryx skeleton surrounded by delicate feather impressions.",
  },
] as const satisfies readonly Artifact[];

export const LEGACY_RELIC_ARTIFACT_ALIASES = {
  "relic-fossil-choir": "relic-dactylioceras-ammonite",
  "relic-lunar-ash": "relic-domal-stromatolite",
  "relic-abyssal-glass": "relic-dinosaur-embryo-egg",
} as const;

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
    artifacts: RELIC_VAULT_ARTIFACTS,
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
for (const [legacyId, canonicalId] of Object.entries(LEGACY_RELIC_ARTIFACT_ALIASES)) {
  const artifact = ARTIFACT_BY_ID.get(canonicalId);
  if (!artifact) throw new Error(`Legacy fossil alias points to unknown artifact: ${canonicalId}`);
  ARTIFACT_BY_ID.set(legacyId, artifact);
}

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
