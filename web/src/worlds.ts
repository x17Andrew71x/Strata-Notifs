import type { Tier, World } from "./bridge";

export type WorldTheme = "relic" | "signal";
export type SeriesId = World | "SIGNAL_RUINS";

export type Artifact = Readonly<{
  id: string;
  name: string;
  museumImage: string;
  excavationImage: string;
  tier: Tier;
  description: string;
}>;

export type WorldDefinition = Readonly<{
  id: SeriesId;
  name: string;
  theme: WorldTheme;
  baseline: boolean;
  eyebrow: string;
  description: string;
  collectionName: string;
  formingCopy: string;
  cardImage?: string;
  artifacts: readonly Artifact[];
}>;

export const RELIC_VAULT_ARTIFACTS = [
  {
    id: "relic-dactylioceras-ammonite",
    name: "Ribbed Jurassic Ammonite",
    museumImage: "/worlds/relic-dactylioceras-ammonite-museum-6a351e2bb9c1.webp",
    excavationImage: "/worlds/relic-dactylioceras-ammonite-excavation-50dc0ab0359a.webp",
    tier: "COMMON",
    description: "A ribbed Jurassic ammonite shell weathered in compact marine limestone.",
  },
  {
    id: "relic-belemnite-rostra",
    name: "Belemnite Rostra",
    museumImage: "/worlds/relic-belemnite-rostra-museum-f5aba5efde48.webp",
    excavationImage: "/worlds/relic-belemnite-rostra-excavation-49cbdaff034c.webp",
    tier: "COMMON",
    description: "Three bullet-shaped calcitic guards preserved together in Jurassic limestone.",
  },
  {
    id: "relic-spiriferid-brachiopod",
    name: "Spiriferid Brachiopod",
    museumImage: "/worlds/relic-spiriferid-brachiopod-museum-004f488c0763.webp",
    excavationImage: "/worlds/relic-spiriferid-brachiopod-excavation-9aebbad976c9.webp",
    tier: "COMMON",
    description: "A broad Palaeozoic shell with a central fold and strong radiating ribs.",
  },
  {
    id: "relic-gryphaea-oyster",
    name: "Gryphaea Oyster",
    museumImage: "/worlds/relic-gryphaea-oyster-museum-effeafad73d8.webp",
    excavationImage: "/worlds/relic-gryphaea-oyster-excavation-0fcbced0bc19.webp",
    tier: "COMMON",
    description: "A heavy Jurassic oyster valve with a recurved beak and layered growth bands.",
  },
  {
    id: "relic-crinoid-columnals",
    name: "Crinoid Columnals",
    museumImage: "/worlds/relic-crinoid-columnals-museum-29e80d02b875.webp",
    excavationImage: "/worlds/relic-crinoid-columnals-excavation-e6226056f5ee.webp",
    tier: "COMMON",
    description: "A short crinoid stem with loose star-holed calcite columnals.",
  },
  {
    id: "relic-rugose-horn-coral",
    name: "Rugose Horn Coral",
    museumImage: "/worlds/relic-rugose-horn-coral-museum-be8aaef85cef.webp",
    excavationImage: "/worlds/relic-rugose-horn-coral-excavation-6ed7a9581d03.webp",
    tier: "COMMON",
    description: "A solitary Palaeozoic horn coral with radial septa visible in its calice.",
  },
  {
    id: "relic-lamniform-shark-tooth",
    name: "Lamniform Shark Tooth",
    museumImage: "/worlds/relic-lamniform-shark-tooth-museum-d30b4b4049c8.webp",
    excavationImage: "/worlds/relic-lamniform-shark-tooth-excavation-fa0a9f99d80e.webp",
    tier: "COMMON",
    description: "A modest fossil shark tooth with a mineralised crown and porous root.",
  },
  {
    id: "relic-carbonised-fern-frond",
    name: "Carbonised Fern Frond",
    museumImage: "/worlds/relic-carbonised-fern-frond-museum-cf7f16401d3c.webp",
    excavationImage: "/worlds/relic-carbonised-fern-frond-excavation-26b28f3ae9ee.webp",
    tier: "COMMON",
    description: "A flat carbon film preserving a branching frond across split shale.",
  },
  {
    id: "relic-domal-stromatolite",
    name: "Domal Stromatolite",
    museumImage: "/worlds/relic-domal-stromatolite-museum-d04e006b69cc.webp",
    excavationImage: "/worlds/relic-domal-stromatolite-excavation-bb31db3a6aac.webp",
    tier: "COMMON",
    description: "Nested microbial laminae preserved in the section of an ancient stone dome.",
  },
  {
    id: "relic-echinocorys-echinoid",
    name: "Chalk Echinoid",
    museumImage: "/worlds/relic-echinocorys-echinoid-museum-b7d1d344cd30.webp",
    excavationImage: "/worlds/relic-echinocorys-echinoid-excavation-48eba03377db.webp",
    tier: "COMMON",
    description: "An oval chalk echinoid test with five petaloid pore bands.",
  },
  {
    id: "relic-articulated-trilobite",
    name: "Articulated Trilobite",
    museumImage: "/worlds/relic-articulated-trilobite-museum-3a5690b7a828.webp",
    excavationImage: "/worlds/relic-articulated-trilobite-excavation-dcdef6c209ba.webp",
    tier: "UNCOMMON",
    description: "A complete trilobite whose cephalon, thorax and pygidium remain articulated.",
  },
  {
    id: "relic-articulated-fossil-fish",
    name: "Articulated Fossil Fish",
    museumImage: "/worlds/relic-articulated-fossil-fish-museum-6f4bfdf3d18a.webp",
    excavationImage: "/worlds/relic-articulated-fossil-fish-excavation-65325e367eef.webp",
    tier: "UNCOMMON",
    description: "A small ray-finned fish with its skull, spine, ribs and fin rays in life order.",
  },
  {
    id: "relic-complete-starfish",
    name: "Complete Fossil Starfish",
    museumImage: "/worlds/relic-complete-starfish-museum-2ff6426b0f62.webp",
    excavationImage: "/worlds/relic-complete-starfish-excavation-e322052c3d45.webp",
    tier: "UNCOMMON",
    description: "A whole fossil starfish with five connected arms and articulated ossicles.",
  },
  {
    id: "relic-articulated-fossil-crab",
    name: "Articulated Fossil Crab",
    museumImage: "/worlds/relic-articulated-fossil-crab-museum-6ec3a14500c2.webp",
    excavationImage: "/worlds/relic-articulated-fossil-crab-excavation-b0cf0d768173.webp",
    tier: "UNCOMMON",
    description: "A brachyuran crab with its carapace, claws and walking legs still associated.",
  },
  {
    id: "relic-insect-amber",
    name: "Insect in Amber",
    museumImage: "/worlds/relic-insect-amber-museum-e0486f183aa8.webp",
    excavationImage: "/worlds/relic-insect-amber-excavation-37eb68ac300d.webp",
    tier: "UNCOMMON",
    description: "A small winged insect held in cloudy fossil resin beneath weathered crust.",
  },
  {
    id: "relic-dinosaur-embryo-egg",
    name: "Dinosaur Embryo in Egg",
    museumImage: "/worlds/relic-dinosaur-embryo-egg-museum-db0ceecfe047.webp",
    excavationImage: "/worlds/relic-dinosaur-embryo-egg-excavation-3b9a0207c942.webp",
    tier: "RARE",
    description: "A naturally fractured fossil egg preserving a curled dinosaur embryo within.",
  },
  {
    id: "relic-archaeopteryx-slab",
    name: "Archaeopteryx Slab",
    museumImage: "/worlds/relic-archaeopteryx-slab-museum-492a72012ed0.webp",
    excavationImage: "/worlds/relic-archaeopteryx-slab-excavation-30a8e4b75684.webp",
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

export const RELIC_VAULT_CATALOG_REVISION = 2;

type RelicArtifactId = (typeof RELIC_VAULT_ARTIFACTS)[number]["id"];
type NativeFossilMetadata = Readonly<{
  selectionWeight: number;
  family: string;
  visual: Readonly<{
    hueDegrees: number;
    strataCount: number;
    inclusionDensityPercent: number;
    reliefPercent: number;
    rotationDegrees: number;
  }>;
}>;

const NATIVE_FOSSIL_METADATA: Readonly<Record<RelicArtifactId, NativeFossilMetadata>> = {
  "relic-dactylioceras-ammonite": nativeFossil(86, "AMMONITE", 32, 8, 30, 50, 0),
  "relic-belemnite-rostra": nativeFossil(86, "BELEMNITE", 36, 6, 28, 44, 8),
  "relic-spiriferid-brachiopod": nativeFossil(86, "BRACHIOPOD", 30, 7, 32, 48, 0),
  "relic-gryphaea-oyster": nativeFossil(86, "BIVALVE", 28, 6, 34, 52, 14),
  "relic-crinoid-columnals": nativeFossil(86, "CRINOID", 34, 9, 38, 55, 0),
  "relic-rugose-horn-coral": nativeFossil(86, "CORAL", 26, 8, 36, 58, 22),
  "relic-lamniform-shark-tooth": nativeFossil(86, "SHARK_TOOTH", 24, 5, 30, 50, 0),
  "relic-carbonised-fern-frond": nativeFossil(86, "FERN_IMPRINT", 30, 10, 42, 46, 0),
  "relic-domal-stromatolite": nativeFossil(86, "STROMATOLITE", 32, 12, 40, 54, 0),
  "relic-echinocorys-echinoid": nativeFossil(86, "ECHINOID", 38, 8, 34, 52, 0),
  "relic-articulated-trilobite": nativeFossil(24, "TRILOBITE", 28, 10, 52, 70, 0),
  "relic-articulated-fossil-fish": nativeFossil(24, "FISH", 30, 12, 56, 74, 0),
  "relic-complete-starfish": nativeFossil(24, "STARFISH", 34, 10, 58, 72, 0),
  "relic-articulated-fossil-crab": nativeFossil(24, "CRAB", 28, 11, 60, 76, 0),
  "relic-insect-amber": nativeFossil(24, "AMBER", 38, 9, 62, 70, 0),
  "relic-dinosaur-embryo-egg": nativeFossil(10, "DINOSAUR_EMBRYO", 24, 13, 72, 88, 0),
  "relic-archaeopteryx-slab": nativeFossil(10, "ARCHAEOPTERYX", 32, 14, 76, 92, 0),
};

export function relicVaultCatalogUpdate(): Record<string, unknown> {
  return {
    schemaVersion: 1,
    revision: RELIC_VAULT_CATALOG_REVISION,
    items: RELIC_VAULT_ARTIFACTS.map(({ id, tier }) => ({
      id,
      tier,
      ...NATIVE_FOSSIL_METADATA[id],
    })),
  };
}

function nativeFossil(
  selectionWeight: number,
  family: string,
  hueDegrees: number,
  strataCount: number,
  inclusionDensityPercent: number,
  reliefPercent: number,
  rotationDegrees: number,
): NativeFossilMetadata {
  return {
    selectionWeight,
    family,
    visual: {
      hueDegrees,
      strataCount,
      inclusionDensityPercent,
      reliefPercent,
      rotationDegrees,
    },
  };
}

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
    id: "SIGNAL_RUINS",
    name: "Signal Ruins",
    theme: "signal",
    baseline: false,
    eyebrow: "Extraterrestrial artifact archive",
    description:
      "Recovered objects from beyond Earth—impossible alloys, broken star maps, and relics with no known maker.",
    collectionName: "Signal Archive",
    formingCopy: "Signal Ruins is under development.",
    cardImage: "/worlds/signal-ruins-artifact-67f7d4054fb5.webp",
    artifacts: [],
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
      [
        ...(world.cardImage ? [world.cardImage] : []),
        ...world.artifacts.flatMap((artifact) => [artifact.museumImage, artifact.excavationImage]),
      ],
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
  const artifacts = definition.artifacts.length > 0 ? definition.artifacts : WORLD_DEFINITIONS[0].artifacts;
  if (specimenKey === "decorative") return artifacts[0];
  let hash = 2166136261;
  for (let index = 0; index < specimenKey.length; index += 1) {
    hash ^= specimenKey.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return artifacts[(hash >>> 0) % artifacts.length];
}
