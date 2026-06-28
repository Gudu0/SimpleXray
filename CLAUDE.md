# SimpleXray

A client-side x-ray mod for Fabric (Minecraft 1.20.1). Lets the player choose specific
blocks to render with a colored wireframe outline, visible through terrain, with an
in-game screen to manage the list.

This file exists so an agent (or future-you) picking this project back up doesn't have
to rediscover decisions and gotchas that were already worked out by hand. **Keep it
updated** — see the "Comments and documentation policy" section below; the same rule
applies to this file as to in-code comments.

## Status

Functionally complete for its original scope: outline rendering, full config screen
(search, drag-and-drop, per-block color, removal), keybind to add the looked-at block,
chunk-based caching with live-update tracking, and disk persistence. See "Known
limitations / not yet built" below for what's deliberately been left out so far.

## Environment

- **Minecraft**: 1.20.1
- **Mappings**: Yarn
- **Loader**: Fabric Loom
- **Java**: targeting release 17 in `build.gradle` — double check this against whatever
  JDK is actually installed before assuming it's correct; it hasn't been revisited since
  the project was first generated.
- **Mod ID**: `simplexray`
- **Base package**: `com.gudu0.simplexray`
- **Client-only mod**: `splitEnvironmentSourceSets()` is enabled. There is no server-side
  code, and there shouldn't be one unless a feature genuinely needs it (e.g. a future
  real server-opt-in system — see "Distribution notes").
- **Dependencies**: Fabric API only. Deliberately no Cloth Config / ModMenu — the config
  screen is a fully custom `Screen` subclass, hand-drawn, by choice (see below).

## Project structure

| File                                           | Responsibility                                                                                                                                                                                                                                                                                                                                                                                         |
|------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `XrayRenderer`                                 | Registers the `WorldRenderEvents.AFTER_ENTITIES` callback; iterates `XrayBlockCache`'s matches each frame and draws a wireframe box per match using the cached per-block color.                                                                                                                                                                                                                        |
| `net.minecraft.client.render.XrayRenderLayers` | **Lives in vanilla's own package on purpose** — see the gotcha below. Holds the single `OUTLINE` `RenderLayer` constant with depth testing disabled, which is the entire mechanism that makes outlines render through walls.                                                                                                                                                                           |
| `XrayConfig`                                   | The data model: which blocks are enabled and what color each has (`LinkedHashMap<Block, Integer>` indexing into a fixed 8-color palette). Owns load/save to a JSON file in the Fabric config dir, writing on every mutation rather than on shutdown.                                                                                                                                                   |
| `XrayBlockCache`                               | Performance layer sitting between `XrayConfig` and the renderer. Caches matched block positions per chunk so the renderer never scans the world directly. Populated on `ClientChunkEvents.CHUNK_LOAD`, fully rescanned on config changes (`addBlock`/`removeBlock`), and incrementally patched on individual block changes via the Mixin below.                                                        |
| `WorldSetBlockStateMixin`                      | The one Mixin in the project. Injects into `World#setBlockState(BlockPos, BlockState, int, int)` (the 4-arg overload — it's the common funnel point other overloads delegate to), gated to only act when `this instanceof ClientWorld`, and calls `XrayBlockCache.onBlockChanged(...)` so already-cached chunks stay correct after a block is placed/broken rather than only updating on chunk reload. |
| `XrayConfigScreen`                             | The in-game config UI. See its own header comment for the panel layout; it's been kept fairly heavily commented already since it's the most complex file.                                                                                                                                                                                                                                              |
| Client initializer (`ClientModInitializer`)    | Calls `XrayRenderer.register()`, `XrayBlockCache.register()`, `XrayConfig.load()` on startup; registers the two keybinds (open config screen, add looked-at block) and polls them in `ClientTickEvents.END_CLIENT_TICK`.                                                                                                                                                                               |

## Key architectural decisions and gotchas

These are easy to "fix" by someone who doesn't know why they're there — please read this
before refactoring any of them.

- **`XrayRenderLayers` is declared under `package net.minecraft.client.render;`,** physically
  inside this mod's own client source set. This isn't a mistake — `RenderLayer.of(...)`
  and `RenderLayer.MultiPhaseParameters` are package-private/protected in vanilla, and the
  standard (if odd-looking) workaround is to place the constructing class in that exact
  package so Java's access rules grant it visibility. The field type must stay `RenderLayer`,
  never `RenderLayer.MultiPhase` — that nested type is `private` and unnameable outside
  vanilla's own class.
- **`shouldPause()` is overridden to `false`** in `XrayConfigScreen`. Plain `Screen`
  subclasses pause singleplayer by default; without this override, opening the config
  screen freezes the world (mobs, particles, time) the same way the Esc menu does.
- **Mixin must stay in the "client" array**, not a common one, in the mixins JSON —
  even though it targets `World` (a class shared with the server), its body references
  `ClientWorld`, which doesn't exist in a dedicated-server classloading context.
- **The config screen draws everything by hand** rather than using `ButtonWidget` for
  per-row content (color swatch, remove button, search results). This was a deliberate
  choice: rebuilding/destroying widget lists every time the underlying data changes
  risks desyncing Minecraft's internal widget tracking. Hit-testing is done manually in
  `mouseClicked`/`mouseScrolled` instead.
- **Hotbar drag-and-drop never touches the real inventory.** `draggingStack` is just a
  reference held for rendering and for reading on drop; nothing about it is consumed,
  removed, or moved. If inventory-grid dragging (not just the hotbar) is ever added, keep
  this property.
- **Cache updates are incremental, not full rescans, for single block changes** —
  `XrayBlockCache.onBlockChanged` patches one chunk's match list in place. Full rescans
  only happen on chunk load and on config list changes (add/remove a tracked block type),
  since those are the only cases that can't be resolved by patching a single position.
- **Persistence writes on every mutation**, not on shutdown, specifically so a crash
  mid-session doesn't lose changes. This is a deliberate tradeoff of a little extra disk
  I/O for safety; it's fine because these are rare, user-driven actions, not a hot path.

## Comments and documentation policy

This came up explicitly while building the project, and matters enough to spell out:

- **Comments should explain *why*, not *what*.** The code already says what it does;
  comment what isn't obvious from reading it — a non-obvious API choice, a tradeoff, a
  workaround for something that looks like a bug but isn't (the package-placement trick
  above is the canonical example).
- **A stale comment is worse than no comment.** If you change behavior a comment
  describes, update or remove the comment in the same edit — don't leave it describing
  the old behavior.
- **Use section banners in files with distinct logical regions** (see `XrayConfigScreen`
  for the established style — `// ====` banners separating setup / layout helpers /
  input handling / render / lifecycle). Not every file needs this; only ones that have
  grown enough that scrolling to find something is a real problem.
- **This file is part of that policy.** If you add a feature, change an architectural
  decision, or retire a limitation listed below, update the relevant section here in the
  same change — don't let this drift into describing a project that no longer exists.

## Known limitations / not yet built

Deliberately deferred, not forgotten — listed here so they don't get silently
re-proposed as if new, and so it's clear which ones are simple additions versus real
redesigns:

- **No global enable/disable toggle.** Right now "off" means an empty block list; there's
  no actual kill switch. Cheap to add (one boolean + a render-time check).
- **No outline render distance / radius cutoff.** The renderer draws every cached match
  in every loaded chunk regardless of distance from the player. Also, cheap to add.
- **Outline appearance (line width etc.) is a hardcoded constant** in `XrayRenderLayers`,
  not user-configurable. Making it adjustable means either rebuilding the `RenderLayer`
  on change or keeping a small set of preset layers — it can't stay a single eternal
  `static final` if it becomes a setting.
- **Color is a fixed 8-entry palette you cycle through**, not a real picker. A real
  picker is a meaningfully larger UI component than anything else on the screen so far —
  budget real time for it, don't treat it as a quick add.
- **Right panel (search results) doesn't truncate long names** — only the left "Enabled"
  panel got that fix. Same `truncateName` helper would cover it if wanted.
- **Drag-and-drop only works from the hotbar**, not the full inventory grid (which isn't
  even rendered in this screen yet).
- **No tag or blockstate-level matching** — everything is keyed on `Block`. Tags match a
  *set* of blocks and would need a second matching pathway; blockstate-level rules (e.g.
  "this ore, but not when waterlogged") are finer-grained than `Block` entirely and would
  mean re-keying `XrayConfig` and `XrayBlockCache` around `BlockState` or a predicate. This
  ripples through nearly every file in the project — treat it as a from-scratch redesign
  of the data model, not a feature bolted onto the current one.

## Distribution notes

Published to CurseForge and direct GitHub releases. **Deliberately not published to
Modrinth** — Modrinth's content rules prohibit client-side x-ray without a genuine
server-side opt-in, and this mod has none. Don't re-attempt a Modrinth listing for this
mod unless that changes (i.e. an actual networking handshake is built where the client
only renders anything after an explicit signal from the connected server — that would be
a real, separate feature, not a config flag).

## Working norms for this project

Patterns that have worked well so far, worth keeping:

- One feature/fix at a time, confirmed working in-game before moving to the next —
  don't bundle unrelated changes into one pass.
- When an exact API name/signature can't be confirmed with certainty (Minecraft's client
  rendering internals especially churn release-to-release), say so explicitly and flag
  what to check, rather than presenting a guess as settled fact.
- Performance tradeoffs (the cache's full-rescan-on-config-change cost, write-on-mutation
  persistence, etc.) are worth stating out loud even when they're the right call — not
  just making the choice silently.

## Things I was unsure about (user filled in)

- [x] `WorldSetBlockStateMixin` lives at
    `src/client/java/com/gudu0/simplexray/client/mixin/WorldSetBlockStateMixin.java`,
    package `com.gudu0.simplexray.client.mixin`.
- [x] Dev/test loop: `./gradlew runClient`.
- [x] Java: project targets 17 (matches what MC 1.20.1 actually requires); IDE's
    21 setting is just a newer JDK being used to compile/run it, which is fine.