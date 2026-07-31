# Resource Pack Texture Ideas

Brainstorm list for the Skirmish WW2-themed resource pack. Done so far: backpack-leather
survival/creative inventory (`gui/container/inventory.png` + `creative_inventory/tab_inventory.png`),
aged-parchment map chest (`gui/container/generic_54.png`).

## GUI containers (same direct-blit technique, easy wins)

- **Furnace / smoker / blast furnace** (`furnace.png`, `smoker.png`, `blast_furnace.png`) — field
  stove / ammo-forge look, glowing coal slot as a firebox window.
- **Crafting table** (`crafting_table.png`) — workbench/quartermaster's table, wood-and-tool-tray look.
- **Anvil** (`anvil.png`) — repair bench, grease-stained steel.
- **Enchanting table** (`enchanting_table.png`) — doesn't fit WW2 theme thematically; maybe skip or
  reskin as an intel/codebreaking desk if we want full coverage.
- **Brewing stand** (`brewing_stand.png`) — medic's field kit / chemical supplies.
- **Shulker box** (`shulker_box.png`) — ammo crate.
- **Horse inventory** (`horse.png`) — unlikely to matter for a combat gamemode, low priority.
- **Beacon / stonecutter / loom / cartography table** — low priority, rarely used in a Skirmish match.

## HUD elements

- **Hotbar** (`hud/hotbar.png`, `hotbar_selection.png`) — ammo-belt / dog-tag styling.
- **Health/hunger icons** (`hud/heart/*.png`, `hud/food_*.png`) — dog-tag hearts, ration-tin food icons.
- **Experience bar** (`hud/experience_bar_*.png`) — could restyle as a morale/suppression meter look
  even though it's not functionally used for XP in Skirmish.
- **Boss bar** (`hud/boss_bar/*.png`) — already used for the KOTH/Domination objective HUD
  (`ObjectiveUIManager`) — reskin as a radio-signal/capture-progress bar to match the map chest.

## World/item textures (bigger scope, separate effort)

- **Crosshair** — already done (WM's aiming reticle), no action needed.
- **Compass** — since `HillObjective`/`DominationObjective` already hand players a compass pointing
  at the objective, a WW2 map-compass reskin would tie directly into the map-chest theme.
- **Map item** (`map.png`, `filled_map.png`) — paired well with the parchment chest; low effort,
  high thematic payoff.
- **Bundle** — ammo pouch, if Skirmish ever uses bundles for loadouts.

## Potion effects → period equivalents

Splash/lingering potions are the richest reflavor target — each vanilla effect maps to a
grenade, injector, or field-kit item. Same technique as the healing bandages: a `minecraft:select`
item model keyed on the `minecraft:potion_contents` component, one case per potion id, vanilla
tinted-bottle look kept as the fallback for anything not reflavored.

- **Healing I/II** — bandage (plain / bloodstained) — **done**.
- **Regeneration** — morphine syrette (WW2 medic's spring-loaded injector, very iconic prop).
- **Swiftness** — Benzedrine "go pills" (real WW2 amphetamine tablets issued to pilots/infantry —
  strong period fit).
- **Slowness** — sedative syringe, or a mustard-gas canister for the lingering/area version.
- **Strength** — ration tin / protein biscuit tin.
- **Weakness** — spoiled ration or "gassed" status marker.
- **Poison** (splash + lingering) — poison gas grenade / canister — chemical warfare is extremely
  period-appropriate, arguably the single best-fitting reskin on this list.
- **Fire Resistance** — flame-retardant salve tin, or asbestos glove/jacket item.
- **Water Breathing** — combat-diver rebreather kit (early "frogman" gear existed in WW2).
- **Invisibility** — smoke grenade (thematically perfect: throw it, vanish in the cloud).
- **Harming** (splash) — frag/incendiary grenade — damage-on-impact already matches grenade logic.
- **Slow Falling** — parachute pack.
- **Turtle Master** (resistance+slowness) — foxhole entrenchment kit ("digging in" = slow but tanky).
- **Luck** — lucky charm: cigarette case, rabbit's foot, pocket bible — WW2 soldiers carried
  superstitious tokens like this; also a natural flavor for **Totem of Undying** ("stopped a
  bullet" is a real folklore trope).
- **Night Vision / Levitation / Infested / Oozing / Wind Charged** — no clean period equivalent;
  probably leave these on the vanilla tinted-bottle fallback rather than force a reskin.

## Armor → uniforms

- **Leather armor** — basic fatigues / cloth infantry uniform.
- **Iron armor** — standard-issue combat uniform + M1-style steel helmet for the helmet slot.
- **Diamond/Netherite armor** — officer's dress uniform or heavy bomb-disposal/flak suit for the
  top tier — reuse the tiering the same way Healing I/II got two distinct looks.
- **Turtle shell helmet** — M1 steel pot helmet on its own is an easy, iconic win even before doing
  the rest of the armor tiers.
- **Elytra** — parachute/glider canopy on the back — pairs naturally with Slow Falling → parachute
  pack above, and with firework rockets → flare/signal rockets for the boost item.
- **Shield** — weakest period fit (WW2 infantry didn't carry shields); could reflavor as a
  sandbag-mounted mobile cover plate or just skip it.

## Misc item reflavors

- **Spyglass** — field binoculars, very natural fit.
- **Ender pearl** — grappling hook, or paired with the smoke-grenade idea above for a
  throw-and-reposition escape tool.
- **Compass** — see the map/compass idea already listed above.
- **Clock** — pocket stopwatch.
- **Book and quill / written book** — field journal or dog-tag info card.
- **Name tag** — dog-tag engraver.
- **Shears** — wire cutters (barbed-wire theme).
- **Bed** — bedroll (foxhole/respawn flavor).
- **Music disc** — propaganda radio broadcast / morale record.
- **Firework rocket** — signal/flare rocket; also the natural elytra-boost item once elytra is a
  parachute.
- **TNT** — satchel charge / dynamite bundle, minimal art change needed.

## Menu chrome

- **Title screen background/panorama** — sandbag/trench backdrop instead of vanilla panorama.
- **Button textures** (`widget/button.png`) — stenciled military-crate lettering look.
- **Loading screen** — low priority, rarely seen.

## Open questions

- Confirm which of these screens actually appear during a Skirmish match before investing effort —
  no point reskinning the enchanting table if the gamemode never opens one.
- Decide: keep every texture on the shared parchment/canvas/leather palette (visual consistency,
  what we've done so far) vs. per-container theming (stove = metal, medic kit = white/red cross,
  etc.) — consistency is less work and reads as "one pack," themed-per-object is more visually rich
  but more effort per item.
