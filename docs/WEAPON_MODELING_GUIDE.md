# Weapon Modeling Guide - Blockbench MCP + WeaponMechanics

Everything needed to design new custom weapon models (WW1/WW2-era guns and beyond) for
Skirmish's WeaponMechanics (WM) install, using the Blockbench MCP server. This doc is a
reference, not a tutorial - it documents what was verified directly against the installed
WeaponMechanics 4.3.1 resource pack, the installed weapon configs, and the Blockbench MCP
plugin's actual tool source, rather than assumed from general WM/Blockbench knowledge.

Everything under "Verified against the install" was confirmed by inspecting real files on
2026-07-27. Everything under "To verify before depending on it" is inference or upstream docs
that weren't independently confirmed - check before building a workflow around it.

---

## 1. Current setup

- **Blockbench MCP server**: registered in `.mcp.json` (gitignored, machine-local) as
  `http://localhost:3000/bb-mcp`. Requires Blockbench desktop running with the
  `blockbench-mcp-plugin` loaded and its MCP server enabled the entire time it's used -
  it's not a background service.
- **WeaponMechanics**: 4.3.1, installed at `~/Desktop/SkirmishTestServer/plugins/WeaponMechanics/`.
  Its bundled resource pack is `WeaponMechanicsResourcePack.zip` in that same folder.
- **Skirmish's loadout catalog**: `src/main/resources/loadout-catalog.yml` (+ the deployed
  copy at `~/Desktop/SkirmishTestServer/plugins/Skirmish/loadout-catalog.yml`) - a catalog
  entry's `wm-weapon` value must exactly match a weapon's top-level YAML key in WM's
  `weapons/**/*.yml` config, not the filename (they happen to match by convention).

---

## 2. How WeaponMechanics actually renders a custom gun model

This is the part that determines everything else about how a new model has to be built. It
is **not** "each weapon is its own Minecraft item" - WM hijacks a single vanilla item and
switches its displayed model via `custom_model_data`.

### 2.1 The base item

Every WM weapon's `Info.Weapon_Item.Type` is `"FEATHER"` (confirmed on `AK_47.yml`,
`M4A1.yml`). All guns in this install are reskinned, unbreakable Feathers - this is
presumably to get a lightweight, stackless, no-durability base item, not because it's
thematically a feather.

### 2.2 The dispatch table

`assets/minecraft/items/feather.json` in the resource pack is a **modern Minecraft
item-model component** (`range_dispatch` on the `custom_model_data` property - this is the
1.21.2+ item-model system, not the legacy integer `CustomModelData` NBT tag, though
WeaponMechanics still config-side treats it as a plain integer/float). Every possible
`custom_model_data` value the item can carry is mapped, via numeric threshold, to a specific
model file under `assets/minecraft/models/item/`. `pack.mcmeta` declares `pack_format: 46` -
confirm this still matches your Paper version before shipping; it's not something this doc
independently verified beyond "it's what shipped with WM 4.3.1."

### 2.3 The weapon config → dispatch value linkage

Each weapon `.yml` has a `Skin:` block that computes the `custom_model_data` value from a
base plus state offsets. Verified from `AK_47.yml`:

```yaml
Skin:
  Default: 5          # base value -> dispatches to item/weapons/ak47
  Scope: ADD 1000      # while aiming: 5 + 1000 = 1005 -> item/weapons/ak47aiming
  Sprint: ADD 2000      # while sprinting: 5 + 2000 = 2005 -> item/weapons/ak47sprinting
```

And from `M4A1.yml` (which has the optional attachment states):

```yaml
Skin:
  Default: 7
  Scope: ADD 1000
  Sprint: ADD 2000
  Attachments:  # "will only work if you have purchased WeaponMechanicsPlus"
    Reflex_Sight: ADD 100000
    Suppressor: ADD 1000000
```

**Attachment offsets require WeaponMechanicsPlus (a separate paid addon Skirmish doesn't
have installed).** Don't plan reflex-sight/suppressor model variants unless that's
purchased - the offsets exist in the dispatch table because M4A1/STG44 already ship with
them, not because the base plugin supports attachments.

### 2.4 Reserved `Default` values - do not collide

Every weapon shares the same Feather item and the same dispatch table, so every `Default`
value in the whole server must be unique. Reading `feather.json`'s base thresholds (the
`Default` values, before any `ADD` offset), the following are already taken:

| Value | Weapon | Value | Weapon |
|---|---|---|---|
| 1 | Uzi | 10 | Kar98k |
| 2 | AUG | 11 | MG34 |
| 3 | Origin 12 | 12 | FAMAS G2 |
| 4 | RPG-7 | 13 | AX-50 |
| 5 | AK-47 | 14 | DP-12 |
| 6 | FN FAL | 15 | STG-44 |
| 7 | M4A1 | 16 | Fatman |
| 8 | Colt Python | 17 | Fatman (rocket ammo variant) |
| 9 | Desert Eagle | | |

Also reserved, outside the 1-17 range: negative values (-1 to -1002) are misc items
(suppressor icon, reflex sight icon, scope reticle icon, ammo icons, combat knife, stim);
`10004`/`10005`/`20004`/`20005` etc. are `_blue`/`_red` team-tinted variants of RPG-7/AK-47
(see §2.5); everything ≥ `100000` is attachment-offset space (WMPlus-gated, per §2.3).

**Next free `Default` slots: 18 upward.** Pick values with headroom (e.g. start at 30) in
case a future WM update ships more default weapons and claims 18-29.

### 2.5 Team-tinted variants (potentially useful for Skirmish specifically)

`ak47_blue`/`ak47_red` and `rpg7_blue`/`rpg7_red` model variants already exist in the pack,
each with their own aiming/sprinting states, dispatched via a `+10000`(-ish; see the exact
threshold table in `feather.json`, reproduced in full in the codebase notes below)
offset pattern. This is WM's own precedent for **per-team weapon skins** - worth knowing
about since Skirmish is a team-based gamemode. Not investigated further here (no team-tinted
STG44/Kar98k exist), but if team-colored guns are ever wanted, this is the existing pattern
to copy rather than invent a new mechanism.

### 2.6 Scope overlay trick (context, not needed for modeling)

`assets/minecraft/textures/misc/pumpkinblur.png`, `pumpkin_override_empty.png`,
`pumpkin_override_scope.png` exist in the pack. `pumpkinblur.png` is the *vanilla* file path
used for the carved-pumpkin head overlay - WM overrides it to render a scope reticle/vignette
full-screen effect while scoped, almost certainly via the classic "give the player an
invisible worn pumpkin while scoped" trick (predates modern GUI overlay APIs). This is
unrelated to item models and nothing about it needs to change to add new guns - noted here
only because it's part of "everything knowledgeable about the pack."

---

## 3. Resource pack file layout

```
WeaponMechanicsResourcePack.zip
├── pack.mcmeta
└── assets/minecraft/
    ├── items/
    │   └── feather.json              # the range_dispatch table - §2.2
    ├── models/item/
    │   ├── weapons/<name>[state].json   # one JSON per weapon per state - §4
    │   └── misc/{reflexsight,suppressor}.json
    ├── textures/
    │   ├── block/color/*.png         # the shared 16×16 flat-color material palette - §5
    │   ├── gui/sprites/hud/crosshair.png
    │   ├── misc/pumpkin*.png         # scope overlay, §2.6
    │   └── rosstail/famas.png        # one-off, not part of the shared palette
    └── sounds/
        ├── shoot/{ambient,quiet,loud}/<name>.ogg
        ├── reload/...
        ├── scope/...
        └── sounds.json                # registers custom sound event names - §6
```

**Resolved, verified against a live server during the Thompson build:** the zip is a static
asset - hand-editing `items/feather.json` and adding model files directly into it is the
correct, working workflow (confirmed: a weapon added this way rendered and functioned
correctly in-game). WM does **not** regenerate/merge it from `weapons/` configs at runtime.
However, **`config.yml`'s `Resource_Pack_Download.Link: "LATEST"` will fetch WM's own official
pack from GitHub and silently overwrite the local hand-edited zip** unless
`Resource_Pack_Download.Enabled`, `Resource_Pack_Download.Automatically_Send_To_Player`, and
`Update_Checker.Enable` are all set to `false` - this is a config trap, not evidence the zip
itself is regenerated. See `.claude/skills/weapon-modeling/SKILL.md` §5 for the full
deployment/testing checklist, including a client-side caching trap that looks identical to
this one but has a different fix.

---

## 4. Model JSON anatomy

Verified against `assets/minecraft/models/item/weapons/{combatknife,stg44}.json`.

- **Format**: standard Minecraft Java item model JSON (cuboid `elements`, not an OBJ/glTF
  mesh). No `parent` field - every weapon model defines its geometry from scratch rather
  than inheriting `item/generated` or `item/handheld`.
- **Scale of geometry**: this pack does NOT build guns as a handful of large boxes. The
  combat knife alone is **206 elements** (tiny rotated cuboids). STG-44 is **606 elements**.
  Expect any new weapon built to this pack's visual standard to need many hundreds of small
  cuboids, not a dozen.
- **Textures are flat color swatches, not painted art.** Every model's `textures` block maps
  a short key (either a number like `"0"`/`"1"` or the swatch's own name, both conventions
  appear) to a path like `block/color/graymetal_medium` - a 16×16 **single solid color**
  PNG (confirmed by pixel-uniqueness check: 1 unique color for material swatches, only the
  `reticle_*` scope-overlay textures are actual detailed images, and those are 512×512).
  Every face's `uv` is `[0, 0, 1, 1]` (the whole swatch) - visual detail comes entirely from
  **geometry** (cuboid placement/rotation/size), not from UV-mapped texture painting.
  `face.rotation` (90/180/270) is used to vary which edge of a symmetric swatch faces which
  way, not to reveal different painted regions.
- **`gui_light`**: `"front"` on every model that sets it (27 of the ~80 model files set it
  explicitly - presumably the rest inherit Minecraft's default, which is also `front` for
  items, so this may not need to be set explicitly).
- **`display` block is required and hand-tuned per weapon.** From `stg44.json`:

  ```json
  "display": {
    "thirdperson_righthand": { "rotation": [0, -90, 0], "translation": [0, 1.25, -1.5], "scale": [0.8, 0.8, 0.8] },
    "thirdperson_lefthand":  { "rotation": [0, 90, 0],  "translation": [0, 1.25, -1.5], "scale": [0.8, 0.8, 0.8] },
    "firstperson_righthand": { "rotation": [0, -90, 0], "translation": [-6.5, 5, 0] },
    "firstperson_lefthand":  { "rotation": [0, 90, 0],  "translation": [-6.5, 5, 0] },
    "ground": { "rotation": [0, 0, -45], "translation": [0.75, 7.5, 0], "scale": [0.8, 0.8, 0.8] },
    "gui":    { "rotation": [30, 45, 0], "translation": [0.75, 1, 0], "scale": [0.6, 0.6, 0.6] },
    "head":   { "rotation": [0, 180, 0] },
    "fixed":  { "rotation": [0, 180, 0] }
  }
  ```

  These values are specific to STG-44's geometry/proportions - copy them as a *starting
  point*, not a universal constant, and expect to re-tune per weapon (especially
  `firstperson_*` translation, which depends on where the gun's geometry sits relative to
  origin).
- **`groups`** appears in the combat knife model (Blockbench-specific metadata for
  in-editor organization - bones/groups from the Blockbench project). Vanilla Minecraft
  ignores this key at runtime; it's safe to have but not functionally required.
- **State suffix naming convention** (matches §2.3's offsets): base = `<name>.json`,
  aiming = `<name>aiming.json`, sprinting = `<name>sprinting.json`. Attachment combos (WMPlus
  only) stack as `<name>_reflexsight.json`, `<name>_suppressor.json`,
  `<name>_reflexsight_suppressor.json`, each with their own `aiming`/`sprinting` variants.

---

## 5. The existing material palette (reuse before making new textures)

`assets/minecraft/textures/block/color/` has **171** pre-made 16×16 flat-color swatches.
Reusing these means a new weapon needs zero new texture files - only geometry and a model
JSON referencing existing swatch paths. Grouped by what's actually useful for a WW1/WW2 gun:

**Metals (grays)** - the primary palette for gunmetal: `graymetal_darker`, `graymetal_dark`,
`graymetal_medium`, `graymetal_light`, `graymetal_lighter`, `graymedium_one`,
`graymedium_two`, `graymedium_three`, `grayspecial`, `grayspecial_dark`, `grayspecial_light`,
`darkergray`, `darkergray_two`, `darkgray`, `lightgray`, `lightgray_two`, `lightgray_three`,
`lightergray`, `gray`, `gray_two`, `grayblue`, `silver`, `fadedblack`, `lightblack`, `black`.

**Wood** (stocks/furniture - very relevant for WW1/WW2 rifles, which are mostly wood
furniture over a steel action): `wood_one` through `wood_fourteen`, `brown_wood`,
`decoratedwood`, `decoratedwood_two`, `birch_planks`, `spruce_planks`, and - notably -
**`wood_tommygun`**, a wood swatch literally named for a Tommy Gun's wood furniture (it's
just a flat brown color despite the name, but confirms this pack's authors built a Thompson
at some point, or at least planned to; worth knowing this swatch exists and is semantically
labeled for exactly this use case).

**Bronze/brass/gold** (period-appropriate for brass cartridge casings, WW1-era fittings):
`bronze`, `bronze_two`, `bronze_three`, `gold_one` through `gold_seven`,
`dark_gold_one` through `dark_gold_five`.

**Named-gun swatches already present**: `hkassaultrifle`/`_two`/`_three`, `sks`, `vector`/
`_two`, `asval`, `fiveseven`/`_two`, `pmag_one`/`_two` - these are color swatches sampled
from other (modern) guns' textures, reused generically. Not WW-era but confirms the "sample
a color from a reference photo, save as a named flat swatch, reuse across models" workflow
this pack's authors actually used - the same technique applies to WW1/WW2 references.

**Camo patterns**: `camo_one`, `armycamoone`/`two`/`three`, `desertcamo_one` through `_five`,
`blue_camo` + variants - flat colors, not printed patterns, despite the name (confirmed
same 1-unique-color test as the metals). Useful for uniform/furniture color variety, not
literal camo texture.

**Glass/optics**: `glass`, `blueglas`, `grayglass`, `greenglass`, `scopeglass`,
`okp_glass`, `thermalvisionglass`, `thermalvisionround`, `nightvision`, `nightvisionround` -
plus the **detailed** (512×512, not flat) `reticle_*` set (17 scope-specific crosshair
overlays: `reticle_standard`, `reticle_acog`, `reticle_barrett`, `reticle_svd`, etc.) used
on the lens face of scoped weapons.

**Solid colors** (uniforms, insignia, generic use): `red`, `green`, `blue` (via `_camo`
variants), `white`, `black`, `orange_terracotta`, `red_terracotta`, `cyan_terracotta`,
`lime_terracotta`, `magenta`, `yellow`, `brightgreen`, `brightpurple`.

**Stone/misc** (unlikely useful for guns, listed for completeness): `stone`,
`smooth_sandstone` + variants, `rough_sandstone` + variants, `quartz_pillar`, `ice`, `snow`,
`gray_concrete`, `red_concrete`.

**Recommendation**: for STG-44/Kar98k-adjacent WW2 guns (already in the loadout catalog -
see §7), reuse `graymetal_dark`/`graymetal_medium`/`graymetal_light` for the receiver/barrel,
`wood_one`-`wood_five` or `wood_tommygun` for stocks/handguards, and `bronze`/`dark_gold_two`
for any brass detailing. Only create a new swatch if none of the 171 existing ones fit -
each new swatch is trivial to add (`create_texture` with `fill_color`, §8) but there's
likely no need.

---

## 6. Sound requirements

Verified against `AK_47.yml` and `sounds.json`:

1. Each weapon has **three volume-tier `.ogg` files**: `sounds/shoot/ambient/<name>.ogg`,
   `sounds/shoot/quiet/<name>.ogg`, `sounds/shoot/loud/<name>.ogg` (confirmed present for
   both `stg44` and `kar98k` already - no new audio needed for those two).
2. `assets/minecraft/sounds.json` registers each as a named sound event, e.g.:
   ```json
   "shoot.ak47.loud": { "category": "master", "sounds": ["shoot/loud/ak47"] }
   ```
   All three tiers (`ambient`/`quiet`/`loud`) get their own entry.
3. The weapon `.yml`'s `Shoot.Mechanics` list references only the `.loud` variant directly
   (`CustomSound{sound=shoot.ak47.loud, volume=6, noise=0.1}"`). **Not independently
   verified**: whether WM auto-resolves the `.ambient`/`.quiet` tiers by naming convention
   based on listener distance, or whether all three need explicit mechanic entries somewhere
   else in the config. Check WM's distance-based-sound documentation before assuming - this
   doc only confirms the *files and registration* exist, not the full trigger mechanism.
4. Reload and scope-in/out sounds (`reload.start.normal`, `reload.end.normal`, `scope.in`,
   `scope.out`) are **shared/generic**, not per-weapon - a new gun doesn't need its own
   reload sound unless a distinct one is wanted.

Since STG-44 and Kar98k already have all three shoot-sound tiers bundled, **no new sound
work is needed for the two weapons already added to the loadout catalog.**

---

## 7. Linking a new model into WeaponMechanics + Skirmish

Four things have to be added/edited together for a new gun to actually work, in this order:

1. **Model JSON(s)** at `assets/minecraft/models/item/weapons/<name>[state].json` (§4) -
   minimum viable set is base + `aiming` + `sprinting` (3 files); skip attachment variants
   (WMPlus-gated, §2.3).
2. **Dispatch entries** in `assets/minecraft/items/feather.json` - one `range_dispatch` entry
   per state, using the reserved `Default` value (§2.4) plus the standard `ADD 1000`/
   `ADD 2000` offsets for aiming/sprinting.
3. **Weapon `.yml`** under `WeaponMechanics/weapons/<category>/<Name>.yml` - needs at minimum
   `Info.Weapon_Item.Type: "FEATHER"`, a `Skin:` block matching step 2's values, and whatever
   ballistics/sound config makes it a functioning gun (copy an existing weapon of similar
   category as a template, e.g. `AK_47.yml` for an assault rifle).
4. **`loadout-catalog.yml`** entry (both the source at
   `src/main/resources/loadout-catalog.yml` and the deployed copy) with `wm-weapon:` set to
   the weapon `.yml`'s **top-level YAML key**, not the filename - Skirmish's `WeaponFactory`
   calls `WeaponMechanicsAPI.generateWeapon(title)` with that exact string.

Steps 1-3 are entirely a WeaponMechanics-side change - Skirmish's code needs zero changes to
support a new weapon, only the catalog entry in step 4 (already established in the earlier
STG-44/Kar98k addition, which needed no Java changes since `WeaponFactory` looks up any
`wm-weapon` title generically).

---

## 8. Blockbench MCP tool catalog

Verified against `jasonjgardner/blockbench-mcp-plugin`'s `server/tools/*.ts` source
(commit as of 2026-07-27) - every tool listed here has a real Zod parameter schema in that
repo, not inferred from the README. `status` in the source marks tools as `STATUS_STABLE` or
`STATUS_EXPERIMENTAL`; experimental ones may have rough edges.

### Project setup

| Tool | What it does |
|---|---|
| `create_project` | Creates a new Blockbench project. **`format` must be `"java_block"`** for Minecraft item models (default is `"bedrock_block"` - wrong for this use case, must be overridden). |
| `get_project_info` | Read-only: format, name/UUID, texture resolution, element counts, top-level groups. Use this first when picking up an existing project instead of guessing state. |

### Geometry - cuboid-based (this pack's actual technique, §4)

| Tool | What it does |
|---|---|
| `place_cube` | Places one or more cuboids (array input - can batch many at once). Takes `elements` (cube geometry), `texture`, `group`, and `faces` (auto-UV, explicit face list, or per-face custom UV). |
| `modify_cube` | Edits an existing cube: name, origin, from/to, rotation, autouv mode, uv_offset. |
| `remove_element` | Deletes a cube/mesh/group by ID or name. |
| `add_group` | Adds a group (Blockbench's organizational bone/folder - not a Minecraft-visible construct, but useful for organizing hundreds of cuboids per §4's "combat knife = 206 elements" scale). |
| `duplicate_element` | Duplicates a cube/mesh/group with an offset - useful for repeating detail (rivets, ridges) without re-specifying geometry each time. |
| `rename_element` | Renames by ID or name. |
| `find_elements_by_criteria` | Query by name regex/substring, type, parent group, size bounds, selection state - useful for batch operations on "everything named `barrel_*`" etc. |
| `select_all_of_type` | Bulk-select all cubes/meshes/groups, optionally scoped to a parent group. |
| `filter_by_material` | Select elements by which texture they use - useful for "select everything using `graymetal_dark` and swap it." |
| `get_selection` | Read current selection. |

### Geometry - mesh-based (do not use for anything that needs to export)

**Resolved, the hard way, during the Thompson build: don't use these.** The `java_block`
export codec **silently drops every mesh-type element** - no error, the element is just absent
from the output JSON. A barrel built with `create_cylinder` rendered correctly in Blockbench's
own preview the entire time, but was never in any exported file, and only surfaced as "not
rendering in-game" many redeploys later. Approximate cylinders (barrels, drums, muzzle devices)
as stacked cuboids instead - see `.claude/skills/weapon-modeling/SKILL.md` §1.

| Tool | What it does |
|---|---|
| `place_mesh` | Places an arbitrary mesh. |
| `extrude_mesh` / `subdivide_mesh` / `knife_tool` | Standard mesh-editing operations. |
| `create_sphere` / `create_cylinder` | Parametric primitives - **do not use for exported geometry**, see above. |
| `select_mesh_elements` / `move_mesh_vertices` / `delete_mesh_elements` / `merge_mesh_vertices` / `create_mesh_face` | Vertex/face-level mesh editing. |

### Texturing

| Tool | What it does |
|---|---|
| `create_texture` | New texture: name, width/height (16×16 default - matches this pack's convention, §4), either `data` (file path/data URL) or `fill_color` (solid color - **this is how to make a new flat-color swatch matching the existing 171**, §5). |
| `apply_texture` | Applies a texture to elements/faces. |
| `add_texture_group` / `list_textures` / `get_texture` | Texture management/inspection. |
| `activate_texture` | Sets the texture actively being painted/edited. |
| PBR-related (`create_pbr_material`, `configure_material`, `assign_texture_channel`, etc.) | Not relevant - this pack doesn't use PBR (§4, flat color swatches only). |

### Painting (only relevant if diverging from the flat-swatch style)

`paint_fill_tool`, `draw_shape_tool`, `gradient_tool`, `color_picker_tool`,
`copy_brush_tool`, `eraser_tool`, `paint_with_brush`, `create_brush_preset`,
`load_brush_preset`, `texture_selection`, `texture_layer_management` - full 2D paint tool
suite. Given §4/§5's finding that this pack never paints textures (pure geometry + flat
swatches), these likely won't be needed for matching the existing visual style, but are
there if a hand-painted texture approach is ever wanted instead.

### UV (mesh-only - not used by this pack's cuboid faces, which are all `[0,0,1,1]`)

`set_mesh_uv`, `auto_uv_mesh`, `rotate_mesh_uv` - relevant only if using mesh elements
(previous section) rather than plain cuboids.

### Export

| Tool | What it does |
|---|---|
| `list_export_formats` | Lists available codecs (id, name, extension, compile/export support) - **run this before `export_model`** to confirm the exact codec ID for Java item-model JSON export (not independently confirmed in this doc - likely something like `java_block` or `item`, matching the `create_project` format, but verify by calling `list_export_formats` since guessing wrong here silently produces the wrong file). |
| `export_model` | Compiles and returns (and optionally writes to disk) the model. `path` writes directly to a filesystem path - could point straight at `assets/minecraft/models/item/weapons/<name>.json` inside a working copy of the resource pack, if Blockbench v5.0+'s filesystem-access permission prompt is accepted. `max_content_length` caps how much of the compiled JSON comes back in the tool response (default 100k chars) - given STG-44's model is 335KB of JSON, expect to need `path`-based export rather than reading the full content back through the MCP response for anything beyond a small test model. |

### Camera / reference

| Tool | What it does |
|---|---|
| `capture_screenshot` / `capture_app_screenshot` | Returns image data of the current view / whole app - useful for visually checking progress without a human looking at the screen, since I can request a screenshot and inspect it. |
| `set_camera_angle` | Positions the viewport camera. |

*(No dedicated "import reference image as background/onion-skin" tool was found in the MCP's
tool source - Blockbench itself supports reference images natively as a manual, in-app
feature; it's just not exposed through this MCP's tool surface. If tracing a real WW2 rifle
photo is wanted, that reference-image setup would need to happen by hand in Blockbench, not
through me.)*

### History

`undo`, `redo`, `get_undo_stack`, `save_checkpoint` - standard, useful for recovering from a
bad batch of `place_cube` calls without starting over.

### Animation / armature / material-instances

Full bone-rigging and keyframe-animation tool sets exist (`create_animation`,
`manage_keyframes`, `bone_rigging`, armature/bone CRUD, vertex-weight tools,
`material_instances`). **Not needed for this pack's technique** - §4/§2.3 confirmed WM's
"animation" between states (idle → aiming → sprinting) is done by swapping to an entirely
different static model file via `custom_model_data`, not by skeletal in-between animation
within one model. These tools would matter for a Blockbench *entity* model (mobs, "Java
Entity" format) or Bedrock-format animated models, neither of which is what a WM weapon is.

### UI / misc

`trigger_action`, `risky_eval` (arbitrary JS execution inside Blockbench - powerful escape
hatch for anything not covered by a dedicated tool, e.g. setting the `display` transform
block, which no dedicated tool above appears to cover directly), `emulate_clicks`,
`fill_dialog`.

### Hytale-specific

`hytale_*` tools (quad creation, attachment pieces, stretch, visibility keyframes) - for
Hytale's model format specifically. Not relevant to Minecraft/WeaponMechanics.

---

## 9. Suggested workflow for one new weapon

Untested end-to-end (this doc is prep, not a validated run) - a reasonable order based on
everything above:

1. `create_project` with `format: "java_block"`.
2. `get_project_info` to confirm the format took and texture resolution defaults sanely
   (16×16, matching §4/§5).
3. Decide the reserved `Default` custom_model_data value (§2.4 - next free is 18+; pick with
   headroom, e.g. 30).
4. Build geometry with repeated `place_cube` calls (batchable - `elements` takes an array),
   organized into `add_group`s per gun section (receiver, barrel, stock, magazine, sights)
   given the hundreds-of-cuboids scale established in §4. Use `duplicate_element` for
   repeated details rather than re-specifying from scratch.
5. Reuse existing swatches from §5 via `apply_texture` (no new texture files needed for a
   WW2 rifle/SMG in this pack's gray-metal/wood palette) - only fall back to `create_texture`
   with `fill_color` if nothing in the 171 fits.
6. `capture_screenshot` periodically to visually sanity-check proportions.
7. Set the `display` transform block (§4) - no dedicated tool covers this directly; likely
   needs `risky_eval` to set it on the Blockbench project object directly, or a manual pass
   in the app after MCP-driven geometry is done. Start from STG-44's values (§4) as a
   baseline and adjust.
8. `list_export_formats`, confirm the Java item-model codec ID, then `export_model` with
   `path` pointed at `assets/minecraft/models/item/weapons/<name>.json` in a working copy of
   the resource pack (not the live deployed one - edit a copy, verify, then swap in).
9. **Corrected, verified against shipped files (`uziaiming.json`/`uzisprinting.json`):**
   `aiming` and `sprinting` are **not** separate full models - this doc's earlier claim to the
   contrary was wrong. They're ~300-byte files with `"parent": "item/weapons/<name>"`,
   overriding only `firstperson_righthand`/`firstperson_lefthand`. All geometry and every other
   display slot inherit from the base file automatically. Write these two as tiny
   parent-referencing JSON directly, not by re-exporting duplicated/offset geometry.
10. Wire it up per §7 (dispatch entries, weapon `.yml`, `loadout-catalog.yml` - **in both the
    repo source and the deployed copy**, they will drift otherwise).
11. Repackage the resource pack zip, deploy, and test in-game. See
    `.claude/skills/weapon-modeling/SKILL.md` §5 for the deployment/testing checklist - several
    config traps (remote pack auto-download, client-side pack caching) will otherwise burn
    multiple redeploy cycles before you realize the model file was never the problem.

---

## 10. Summary of open questions (don't assume - verify empirically)

Resolved during the Thompson build, kept here for the record - see
`.claude/skills/weapon-modeling/SKILL.md` for the actionable version of each:

- ~~Is `WeaponMechanicsResourcePack.zip` static or regenerated at runtime?~~ **Static - confirmed
  working.** Hand-editing it is correct. The trap is `config.yml`'s remote-download settings
  silently overwriting it, not the zip's own nature (§3).
- ~~Does mixing mesh and cuboid elements work in the same file?~~ **No - meshes are silently
  dropped by the `java_block` export codec.** Cuboids only (§8).
- ~~Is `aiming`/`sprinting` a separate full model or a shared base with overrides?~~ **Tiny
  parent-referencing files overriding only the firstperson display slots** (§9 step 9).
- `export_model` codec ID: confirmed `java_block` (matches the `create_project` format ID).
- Does WM auto-resolve `shoot.<name>.{ambient,quiet,loud}` sound tiers from one config
  reference, or does each tier need explicit config? (§6) - still unconfirmed, not exercised
  during the Thompson build since it reused an existing weapon's sound wholesale.
- Whether any MCP tool sets the `display` transform block directly, versus needing
  `risky_eval` or the in-app Display tab: **either works** - `risky_eval` against
  `Project.display_settings` and manual edits in Blockbench's own Display tab are the same live
  project state and are interchangeable within a session.
- `pack_format: 46` - confirm this still matches whatever Paper/Minecraft version is
  actually running before assuming a new model file is format-compatible.
