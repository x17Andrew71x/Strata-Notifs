# 03 — Museum, Worlds and UI Implementation Plan

> **For Hermes:** Keep the interface deliberately limited. Visual polish must be verified from rendered states, not inferred from source.

**Goal:** Deliver the complete local game loop with a consistent Compose design system, museum, combining, weekly displays and four launch renderers.

**Architecture:** Stateless renderers consume versioned specimen parameters. ViewModels expose immutable UI state from domain repositories. Navigation has four root destinations; nested screens own contextual controls.

**Milestone:** `0.4.0`

---

## Task 1: Quiet Strata design system

**Files:**
- Create: `android/app/src/main/java/.../ui/theme/Color.kt`
- Create: `.../ui/theme/Type.kt`
- Create: `.../ui/theme/Shape.kt`
- Create: `.../ui/theme/Theme.kt`
- Create: `.../ui/components/*`
- Create: `android/app/src/test/.../ui/DesignTokenTest.kt`

Define basalt surfaces, mineral neutrals, copper and restrained mint accents; semantic colour roles; typography; spacing; motion durations; elevations and focus states. Test contrast values and token completeness. Provide reduce-motion and high-contrast adaptations.

## Task 2: App shell and navigation

**Files:**
- Create: `.../ui/navigation/QuietStrataNavGraph.kt`
- Create: `.../ui/navigation/RootDestination.kt`
- Modify: `MainActivity.kt`
- Create: `.../ui/navigation/NavigationTest.kt`

Build Today, Museum, Community and More roots. Keep bottom navigation on roots only. Test root switching, nested detail/back behaviour, state restoration and TalkBack labels.

## Task 3: Today formation screen

**Files:**
- Create: `.../ui/today/TodayScreen.kt`
- Create: `.../ui/today/TodayViewModel.kt`
- Create: `.../ui/today/FormationCanvas.kt`
- Create: `.../ui/today/RevealSequence.kt`
- Create: tests and screenshot fixtures

Render live strata from reduced summaries, clear permission/unobserved states and one primary reveal action. Test observed quiet, noisy, disconnected, sealed-unrevealed and revealed states. Pause animation when backgrounded and honour reduced motion.

## Task 4: Base renderer and world interface

**Files:**
- Create: `.../render/WorldRenderer.kt`
- Create: `.../render/RenderModel.kt`
- Create: `.../render/primeval/PrimevalRenderer.kt`
- Create: `.../render/space/DeepSpaceRenderer.kt`
- Create: `.../render/botanical/BotanicalRenderer.kt`
- Create: `.../render/abyss/AbyssRenderer.kt`
- Create: `.../render/RendererGoldenTest.kt`

Define platform-neutral parameter handling and deterministic rendering. Each renderer must visibly differ while mapping the same specimen identity/tier. Create stable goldens at phone, tablet and export sizes; no renderer may expose source tokens or counts in exported metadata.

## Task 5: Museum and specimen detail

**Files:**
- Create: `.../ui/museum/MuseumScreen.kt`
- Create: `.../ui/museum/MuseumViewModel.kt`
- Create: `.../ui/museum/SpecimenDetailScreen.kt`
- Create: tests/goldens

Use lazy grids, concise filters and persistent selection. Test empty, small, large, locked, restored, community and multi-world collections. Detail exposes display, lock, combine/donate eligibility and share; destructive actions are not implicit gestures.

## Task 6: Combining transaction

**Files:**
- Create: `.../domain/CombineSpecimensUseCase.kt`
- Create: `.../data/local/InventoryMutationEntity.kt`
- Create: `.../ui/museum/CombineDialog.kt`
- Create: tests

Test three identical ordinary to Restored; three identical Restored to Centre Piece; locked/ineligible rejection; duplicate submission; crash rollback; and no cross-family/world confusion. One transaction consumes inputs and creates output with provenance.

## Task 7: Weekly diorama

**Files:**
- Create: `.../domain/CreateWeeklyDisplayUseCase.kt`
- Create: `.../ui/museum/WeeklyDisplayScreen.kt`
- Create: tests/goldens

Arrange seven sealed days without consuming specimens. Handle missing/unobserved days explicitly. Re-render the same display in every owned world.

## Task 8: More, privacy and onboarding

**Files:**
- Create: `.../ui/onboarding/*`
- Create: `.../ui/more/MoreScreen.kt`
- Create: `.../ui/more/PrivacyScreen.kt`
- Create: `.../ui/more/AccessibilityScreen.kt`
- Create: tests/goldens

Provide plain-language notification disclosure before Android settings, consent-independent local mode, data category summary, revoke-access shortcut, clear-local-history confirmation and accessibility controls. No dark patterns or preselected analytics consent.

## Task 9: Worlds and local dev catalogue

**Files:**
- Create: `.../commerce/ProductCatalog.kt`
- Create: `.../commerce/DevEntitlementRepository.kt`
- Create: `.../ui/worlds/WorldsScreen.kt`
- Create: tests

Enable unmistakable development-only world preview/unlock/reset controls. Verify build-time exclusion from production. Buying/applying a world re-renders existing specimens without duplicating inventory.

## Task 10: Sharing

**Files:**
- Create: `.../sharing/SpecimenExporter.kt`
- Create: `.../sharing/ShareUseCase.kt`
- Create: tests

Generate bounded PNG exports with optional date and discreet signature. Strip metadata and verify no notification totals, account IDs or source tokens appear in bytes or share text.

## Phase exit

- Core path works fully offline: onboarding → observe fixture day → seal → reveal → museum → combine/display/share.
- Four renderers pass deterministic goldens.
- Root and nested navigation, large text, reduced motion and accessibility semantics pass.
- No screen adds a fifth root or redundant dashboard clutter.
- Android unit/UI/golden/lint/build gates pass.
