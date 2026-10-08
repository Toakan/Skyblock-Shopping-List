# Skyblock Shopping List (formerly InventoryReader)

Fabric client mod (mod id `skyblock-shopping-list`, Java package `inventoryreader.ir`) for Hypixel SkyBlock, Minecraft 26.2 (Loom 1.17, Java 25). Build with `./gradlew build`.

## Versioning
`mod_version` in `gradle.properties` is MAJOR.MINOR.PATCH, bumped in the same commit as the change:
- New feature: bump MINOR, reset PATCH (4.4.0 -> 4.5.0).
- Bug fix or change to existing behaviour: bump PATCH (4.4.0 -> 4.4.1).
- MAJOR only for breaking changes such as a Minecraft version port.
One bump per change. Docs-only commits (readme, this file) don't need a bump.

## Hypixel rules
The mod must stay read-only and client-side (see the "Hypixel rules" section in `readme.md`): never send
packets, chat or commands, never cancel or modify events, never automate clicks or item movement.
Tracking, the HUD and keybinds run only on SkyBlock (`SkyblockDetector`).
