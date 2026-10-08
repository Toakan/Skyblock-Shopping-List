# InventoryReader

Fabric client mod for Hypixel SkyBlock, Minecraft 26.2 (Loom 1.17, Java 25). Build with `./gradlew build`.

## Versioning
Every change gets its own minor version bump: increment the minor part of `mod_version` in
`gradle.properties` (e.g. 4.1.0 -> 4.2.0, patch reset to 0) in the same commit as the change.
Docs-only commits (readme, this file) don't need a bump.

## Hypixel rules
The mod must stay read-only and client-side (see the "Hypixel rules" section in `readme.md`): never send
packets, chat or commands, never cancel or modify events, never automate clicks or item movement.
