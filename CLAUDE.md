# Skyblock Shopping List (formerly InventoryReader)

Fabric client mod (mod id `skyblock-shopping-list`, Java package `inventoryreader.ir`) for Hypixel SkyBlock, Minecraft 26.2 (Loom 1.17, Java 25). Build with `./gradlew build`.

## Versioning
`mod_version` in `gradle.properties` is MAJOR.MINOR.PATCH, bumped in the same commit as the change:
- Major feature (a new area such as Appearance settings, the Forging panel, pet recipes): bump MINOR,
  reset PATCH (4.4.0 -> 4.5.0).
- Anything that adds to, removes from or modifies an existing feature (e.g. a new Appearance option such
  as rounded corners, per-section text sizes, list reordering), and every bug fix: bump PATCH
  (4.4.0 -> 4.4.1).
- MAJOR only for breaking changes such as a Minecraft version port.
One bump per change. Docs-only commits (readme, this file) don't need a bump.
Order per change: edit, bump `mod_version`, `./gradlew build`, commit. Bump before the final build so
`build/libs` has the jar for the committed version.

## Docs
When a change adds or changes something a player would notice, update `readme.md` (detailed) and, if it is
worth advertising, `modrinth.md` (short, synced to the Modrinth description) in the same commit. Internal
changes and small fixes don't need a docs note.

## Hypixel rules
The mod must stay read-only and client-side (see the "Hypixel rules" section in `readme.md`): never send
packets, chat or commands, never cancel or modify events, never automate clicks or item movement.
Tracking, the HUD and keybinds run only on SkyBlock (`SkyblockDetector`).
