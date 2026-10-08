# Skyblock Shopping List

Skyblock Shopping List is a Fabric client mod for Hypixel SkyBlock. It keeps a count of the items in your inventory, sacks, backpacks and ender chest, and shows a shopping list HUD for a recipe you pick: what you can already craft and what you still need.

This is a renamed, maintained fork of Inventory Reader ([Scholiboi/InventoryReader](https://github.com/Scholiboi/InventoryReader)), originally written by Scholiboi. Remove any old Inventory Reader jar from `mods/` before installing; its data folder (`.ir-data`) is moved over automatically on first launch.

## Requirements
- Minecraft 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API 0.161.0+26.2 or newer
- Java 25

## Installation
1. Install Fabric Loader for Minecraft 26.2.
2. Put Fabric API and the Skyblock Shopping List jar in your `mods/` folder.
3. Launch Minecraft with the Fabric profile.

## First-time setup
The mod only knows what you have shown it. Open each of your sacks, backpacks and ender chest pages once in SkyBlock. After that, counts stay up to date from your inventory, from the containers you open, and from the `[Sacks]` chat summaries.

## Keys and commands
| Key / command | Action |
| --- | --- |
| `V` or `/ssl menu` | Open the Sandbox Viewer (resources, recipes, forge planner, manual edits) |
| `B` or `/ssl widget` | Open HUD widget customization |
| `H` | Toggle the HUD widget |
| `J` | Open HUD positioning |
| `/ssl` | List commands |
| `/ssl reset` | Delete all tracked item data and widget settings |
| `/ssl done` | Stop the "open a sack" reminder |
| `/ssl credits` | Show credits |

`/ir` still works as an alias for `/ssl`. Keys can be rebound under Options > Controls.

## Hypixel rules
Skyblock Shopping List is designed to stay within the [Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications) guidelines:
- It is read-only. It looks at screens you open and chat messages you receive, and never sends packets, chat messages or commands to the server.
- It does not automate anything: no clicking, crafting, moving items or opening menus for you.
- The HUD only shows your own items and recipe arithmetic.

As Hypixel states, every modification is used at your own risk.

## Where data is stored
Everything lives in `.skyblock-shopping-list/data/` inside your Minecraft folder:
- `resources.json`: tracked item counts
- `inventorydata.json`, `allcontainerData.json`, `sacks.json`: last-seen inventory, container and sack contents, used to work out changes
- `widget_config.json`: HUD position, size, selected recipe and expanded nodes
- `forging.json`, `gemstone_recipes.json`: built-in fallback recipes
- `recipes_remote.json`, `recipes_remote_forge.json`: recipes parsed from the NEU repository
- `remote_sources.json`, `remote_sources_meta.json`: recipe source list and download cache state
- `neu-repo-extracted/`: the unpacked NEU repository

## Network access
On startup the mod downloads the [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) archive from GitHub (`codeload.github.com`) to get recipes. The request is a plain GET with an ETag, so unchanged data is not downloaded again. No player data is sent anywhere.

The sources are listed in `remote_sources.json`. Only `https` URLs and local files are accepted. The archive is unpacked to `neu-repo-extracted/` with path-traversal, entry-count and size limits, then parsed as JSON. Nothing from it is executed.

## Building from source
Requires JDK 25.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient` starts a development client.

## Known limitations
- Items are matched by display name. Names are normalized (colour codes, stat symbols and star glyphs are ignored), but items that share a display name are still treated as one item in your counts. Recipes for such items are listed separately with their SkyBlock ID in brackets.
- Recipes cover what the NEU repository lists as crafting and forge recipes.

## License and attribution
- Code: CC-BY-SA-4.0 (see `LICENSE`). Based on Inventory Reader by Scholiboi.
- Recipe and item data comes from NotEnoughUpdates-REPO; follow its license when reusing that data.
