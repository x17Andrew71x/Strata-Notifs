export const REFERENCE_THRESHOLDS = [3, 6, 10] as const;

export type ReferenceNote = Readonly<{
  requiredOwned: (typeof REFERENCE_THRESHOLDS)[number];
  label: "Identity" | "Fossil record" | "Recognition";
  text: string;
  source: string;
  sourceUrl: string;
}>;

function notes(
  source: string,
  sourceUrl: string,
  identity: string,
  fossilRecord: string,
  recognition: string,
): readonly ReferenceNote[] {
  return [
    { requiredOwned: 3, label: "Identity", text: identity, source, sourceUrl },
    { requiredOwned: 6, label: "Fossil record", text: fossilRecord, source, sourceUrl },
    { requiredOwned: 10, label: "Recognition", text: recognition, source, sourceUrl },
  ];
}

const RELIC_REFERENCE_NOTES: Readonly<Record<string, readonly ReferenceNote[]>> = {
  "relic-dactylioceras-ammonite": notes(
    "Natural History Museum",
    "https://www.nhm.ac.uk/discover/what-is-an-ammonite.html",
    "A ribbed Jurassic ammonite: an extinct coiled shell form, not a living nautilus.",
    "Ammonite shells occur worldwide and can form very large fossil concentrations.",
    "This is the durable shell only; no soft anatomy is preserved.",
  ),
  "relic-belemnite-rostra": notes(
    "British Geological Survey",
    "https://www.bgs.ac.uk/discovering-geology/fossils-and-geological-time/belemnites",
    "The bullet-shaped fossils are calcitic guards, or rostra, from belemnites.",
    "Belemnites lived through the Jurassic and Cretaceous and are commonly found as fossils.",
    "The hard rostrum is the usual fossil part; a whole soft-bodied animal would require exceptional preservation.",
  ),
  "relic-spiriferid-brachiopod": notes(
    "British Geological Survey",
    "https://www.bgs.ac.uk/discovering-geology/fossils-and-geological-time/brachiopods",
    "A spiriferid-style brachiopod is symmetric through each valve rather than between left and right valves.",
    "Brachiopods were especially abundant in Palaeozoic seas and are often the most common fossils in rocks of that age.",
    "A broad shell, central fold and radiating ribs distinguish it from an ordinary bivalve.",
  ),
  "relic-gryphaea-oyster": notes(
    "British Geological Survey",
    "https://www.bgs.ac.uk/discovering-geology/fossils-and-geological-time/bivalves",
    "Gryphaea arcuata is a Jurassic oyster often called a devil’s toenail.",
    "Bivalve valves are the parts usually preserved; this specimen is one robust recurved valve.",
    "Its asymmetrical bowl and recurved beak separate it from an ammonite coil.",
  ),
  "relic-crinoid-columnals": notes(
    "British Geological Survey",
    "https://www.bgs.ac.uk/discovering-geology/fossils-and-geological-time/crinoids",
    "These are calcitic stem ossicles from a crinoid: repeated discs with central openings.",
    "Crinoid soft tissue decays, so stem pieces are common while complete specimens are rare.",
    "A short connected stem and loose star-holed columnals form the specimen—not vertebrae or coins.",
  ),
  "relic-rugose-horn-coral": notes(
    "British Geological Survey",
    "https://www.bgs.ac.uk/discovering-geology/fossils-and-geological-time/corals",
    "A solitary Palaeozoic rugose coral whose cup exposes radial septa.",
    "Rugose corals are frequently found in Silurian-to-Carboniferous rocks.",
    "The radial cup structure identifies coral rather than a mammal horn.",
  ),
  "relic-lamniform-shark-tooth": notes(
    "Florida Museum",
    "https://www.floridamuseum.ufl.edu/discover-fish/sharks/fossil/shark-teeth",
    "A modest lamniform shark tooth with a mineralised crown and porous root.",
    "Sharks shed many thousands of teeth; teeth are the shark parts most commonly fossilised.",
    "The crown-root boundary and modest size keep it distinct from a giant megalodon trophy.",
  ),
  "relic-carbonised-fern-frond": notes(
    "National Park Service",
    "https://www.nps.gov/articles/000/impressions-and-compressions.htm",
    "A Carboniferous seed-fern-like frond preserved as a compression, without claiming a species from appearance alone.",
    "Compression and carbonisation often retain fine detail in leaf material.",
    "It is a flat carbon film with venation, not preserved green tissue or a raised cast.",
  ),
  "relic-domal-stromatolite": notes(
    "Geoscience Australia",
    "https://www.ga.gov.au/__data/assets/pdf_file/0010/188452/Algae-to-Zooxanthellae-Life-in-the-rock-record-Stromatolites_01.pdf",
    "A layered microbial structure built by cyanobacterial communities.",
    "Stromatolites are a major part of the late Archaean and Proterozoic fossil record and peaked in abundance around 1.2 billion years ago.",
    "Nested sedimentary laminae distinguish it from tree rings or agate.",
  ),
  "relic-echinocorys-echinoid": notes(
    "British Geological Survey",
    "https://www.bgs.ac.uk/discovering-geology/fossils-and-geological-time/echinoids",
    "An echinoid test—the hard shell of a sea urchin—without attached soft tissue.",
    "Fossil echinoids are common in Jurassic and Cretaceous rocks, especially Late Cretaceous Chalk.",
    "Fivefold symmetry and petaloid pore bands identify the test; spines are not preserved here.",
  ),
  "relic-articulated-trilobite": notes(
    "National Park Service",
    "https://www.nps.gov/articles/000/fossil-arthropods.htm",
    "A complete articulated trilobite with cephalon, thorax and pygidium together.",
    "Many trilobite fossils are moults or fragments; limbs and antennae rarely preserve.",
    "Three longitudinal lobes and repeated thoracic segments separate head from tail.",
  ),
  "relic-articulated-fossil-fish": notes(
    "National Park Service",
    "https://www.nps.gov/subjects/fossils/taphonomy.htm",
    "A small ray-finned fish preserved laterally on a laminated lakebed slab.",
    "Most fossil fish at Fossil Butte are partly disarticulated because decay can scatter their bones.",
    "This specimen retains the skull, spine, ribs and fins in life order.",
  ),
  "relic-complete-starfish": notes(
    "Proceedings of the Royal Society B",
    "https://pmc.ncbi.nlm.nih.gov/articles/PMC1599871",
    "A complete fossil asteroid with five articulated arms on a marine slab.",
    "Fossil starfish are usually partly or entirely disarticulated; whole animals are rare.",
    "Repeated ossicles and slight asymmetry keep it anatomical rather than a decorative star.",
  ),
  "relic-articulated-fossil-crab": notes(
    "Boletín de la Sociedad Geológica Mexicana",
    "https://www.scielo.org.mx/scielo.php?script=sci_arttext&pid=S1405-33222014000100016",
    "A brachyuran crab with its carapace and major limbs still associated.",
    "More than 3,000 fossil brachyuran species are known, but delicate features such as eyes are extremely rare.",
    "Associated claws, walking legs and plausible joints distinguish it from an isolated carapace.",
  ),
  "relic-insect-amber": notes(
    "National Park Service",
    "https://www.nps.gov/articles/000/preserved-remains.htm",
    "A small gnat- or midge-like insect enclosed inside fossil resin.",
    "Amber can preserve insects and other organic material, but this preservation route is quite rare.",
    "The tiny inclusion is suspended within resin flow lines rather than resting on the surface.",
  ),
  "relic-dinosaur-embryo-egg": notes(
    "Natural History Museum",
    "https://www.nhm.ac.uk/discover/were-dinosaurs-good-parents.html",
    "A curled non-avian dinosaur embryo preserved within a fractured fossil egg.",
    "Embryonic dinosaur bones are extremely uncommon finds.",
    "The skeleton remains compact inside the egg and partly obscured by matrix.",
  ),
  "relic-archaeopteryx-slab": notes(
    "Field Museum",
    "https://www.fieldmuseum.org/blog/unveiling-the-chicago-archaeopteryx",
    "Archaeopteryx preserves an articulated skeleton with feather impressions in fine limestone.",
    "Only 13 Archaeopteryx specimens were known when the Field Museum announced its Chicago specimen.",
    "Long feathered forelimbs, a long tail and fine impressions separate it from a modern bird silhouette.",
  ),
};

export function referenceNotesFor(artifactId: string): readonly ReferenceNote[] {
  return RELIC_REFERENCE_NOTES[artifactId] ?? [];
}
