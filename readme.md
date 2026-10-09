# Skyblock Shopping List

Skyblock Shopping List is a Fabric client mod for Hypixel SkyBlock. It keeps a count of the items in your inventory, sacks, backpacks and ender chest, and shows a shopping list HUD for a recipe you pick: what you can already craft and what you still need.

This tool has been updated to be really beneficial for Ironman accounts, however it can still be useful for regular accounts to keep track of their inventory and plan their crafting efficiently.


# Notification of Change
This is a renamed, maintained fork of Inventory Reader ([Scholiboi/InventoryReader](https://github.com/Scholiboi/InventoryReader)), originally written by Scholiboi. Remove any old Inventory Reader jar from `mods/` before installing; its data folder (`.ir-data`, or `.skyblock-shopping-list` from earlier versions of this fork) is moved to `config/skyblock-shopping-list/` automatically on first launch.

The license and credits remain the same as the original Inventory Reader project, with modifications only for the renaming and maintenance of this fork.

## Requirements
- Minecraft 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API 0.161.0+26.2 or newer
- YetAnotherConfigLib 3.9.7+26.2 or newer (already installed if you use Skyblocker)
- [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) 1.0.2 or newer (official; used to know when you are on SkyBlock)
- Java 25

## Installation
1. Install Fabric Loader for Minecraft 26.2.
2. Put Fabric API, YetAnotherConfigLib, the Hypixel Mod API and the Skyblock Shopping List jar in your `mods/` folder.
3. Launch Minecraft with the Fabric profile.

## First-time setup
The mod only knows what you have shown it. Open each of your sacks, backpacks and ender chest pages once in SkyBlock, plus each Accessory Bag page, each wardrobe page and the Stats & Equipment menu (for worn necklace, cloak, belt and gloves). Worn armor counts with your inventory. After that, counts stay up to date from your inventory, from the containers you open, and from the `[Sacks]` chat summaries.

## Menu
Press `V` (or `/ssl menu`) to open the menu. It has five tabs:
- **List** (opens first): search recipes, type how many you want and click a recipe to add it (up to 3 by default). Each entry has ▲ / ▼ to change its order, its own amount box and an × to remove it, plus **Clear list**. Order is priority: when recipes share materials, the higher one gets them first. Each entry's **=** / **+** button sets what its amount means: **=** Have total (default: how many you want in total, so what you already hold counts) or **+** Add more (how many more to make on top of what you hold when you switch). Below is a preview of what the HUD shows.
- **Resources**: your item counts with search. Type in a box or use − / + to correct a count; changes save straight away. **Show all** also lists items you have none of.
- **Recipes**: browse any recipe's full tree for a chosen amount, and **Add to list**.
- **Forge**: what you still need for one recipe, with **Add to list**.
- **Settings**: opens the settings screen (YetAnotherConfigLib, same style as Skyblocker), with HUD, Appearance, Notifications, Controls and Advanced categories: HUD on/off, Total section, Total lists, Max recipes, **Move HUD...**, Pop-ups, Auto-remove, Sack reminder, **Key binds...** and **Debug logging** (off by default; when on, writes what the mod reads to latest.log). Changes apply when you press Save.
- **Appearance** (in Settings): design the HUD yourself. Colours (with transparency) for the panel, borders, title, rows, tree lines, text and the done / partly gathered / missing / can craft / cooking states (cooking: you only have enough by counting items still in the Forge); row height, row gap, indent, padding, border thicknesses and rounded corners (panels and rows, 0-6 px); font (Minecraft's fonts plus any your enabled resource packs add), shadow and bold recipe names; separate text sizes for the title, rows, Craftable and Forging, and left / centre / right alignment for the title, Craftable and Forging; tick/cross marks, row boxes, tree lines, and the amount format (Remaining "3×", Have / need "83/5,120", or Required "6×"), with **Short numbers** (on by default) showing big amounts as 5.1k / 500m / 1.5b. A live preview at the top of the description column shows a sample panel with your changes before you press Save. **Reset look to defaults** restores the original look.
  - **HUD scale** (50-300%) sizes every panel. **HUD sizing** sets how it reacts to the window: **Window size** (default) grows and shrinks with the game window, **Minecraft GUI Scale** follows GUI Scale in steps like vanilla menus, **Fixed pixels** never changes. Panels keep their place relative to the screen and stay on it when the window is resized.
  - **Craftable** and **Forging** can each be hidden, or moved to their **own panel** that you place and size separately in Move HUD.
  - In **Move HUD**, drag a panel to move it, drag a **corner** to scale it (keeps its shape; each panel has its own scale on top of HUD scale), or drag an **edge** to make it wider, narrower, taller or shorter.
  - **Presets** (Appearance > Presets): save your look and panel sizes under a name, load one of yours or a built-in (Default, Compact, Minimal), and **Copy share code** / **Paste share code** to share a look as text (e.g. in Discord). Panel positions are never changed. Loading, pasting or resetting first keeps your current look as the preset "Previous (auto)", and a setup you made before presets existed is kept as "My look".
  - Panels grow to fit their content up to the height set in Move HUD; anything beyond is cut off and marked with "…" (make the panel taller or collapse rows).

The HUD (`H` to toggle) shows:
- **Total**: every raw material still needed across all recipes, missing items first. Set **Total lists** to Recipe ingredients to sum the pieces each recipe takes directly (e.g. Refined Titanium) instead. Your stock is shared between recipes, never counted twice (earlier entries get it first).
- One tree per recipe.
- **Craftable**: intermediates you can make right now.
- **Forging**: items cooking in your Dwarven Forge that the list needs, with time left. Updated when you open The Forge.
- **Forge times**: rows made in the Forge show how long is still ahead, e.g. `Titanium Drill DR-X455 [25hrs]`: every forge craft still to make for that row, one after another (forge time × how many are still needed, plus all its ingredients). That is the worst case: forging several at once finishes sooner. Items cooking now add the time they have left and count down on their real timer. Crafts not started yet get your **Quick Forge** perk (read when you open Heart of the Mountain) and Cole's **Molten Forge** while he is mayor or minister (the mayor is checked hourly, only while Forge times is on). Appearance > Forge times: Every row, Top level only, or Off. Long names are cut short so the time always shows.

Recipes cover crafting, the Dwarven Forge and NPC shop purchases (for items with no crafting or forge recipe) and Kat pet upgrades, so things like the **Golden Dragon (Legendary)** (500M coins, 50 Enchanted Gold Block and one of each Perfect gem) can go on the list. Items with no recipe of their own, such as shop currencies (Agatha's Coupon, Miria's Prize), can be added too, to track how many you hold. Pets are listed as "<Pet> (<Rarity>)"; a pet upgrade needs the same pet one rarity lower. Pets you own count once they are in your inventory, a backpack or the ender chest, or after you have opened each page of your Pets menu (`/pets`).

**Coins** count as an item: your purse is read from the SkyBlock sidebar, and your bank balance each time you open the bank menu. Balances above about 2.1 billion are shown capped at that.

When a recipe has everything it needs you get a "Ready to craft" pop-up, and once you have made the amount you asked for an "Item achieved" pop-up and the recipe leaves the list (both can be turned off in Settings).

## Keys and commands
| Key / command | Action |
| --- | --- |
| `V` or `/ssl menu` | Open the menu |
| `H` | Toggle the HUD |
| `B` or `/ssl hud` | Move / resize the HUD |
| `/ssl` | List commands |
| `/ssl reset` | Delete the current profile's tracked data and the HUD settings |
| `/ssl done` | Stop the "open a sack" reminder |
| `/ssl credits` | Show credits |

`/ir` still works as an alias for `/ssl`, and `/ssl widget` for `/ssl menu`. Keys can be rebound under Options > Controls > Key Binds > Skyblock Shopping List (or Settings → Key binds...).

## SkyBlock only
The mod only runs on Hypixel SkyBlock. It uses the official Hypixel Mod API's location event (Hypixel's own signal), so the Hypixel Mod API is required; the game won't start without it. In lobbies, other Hypixel games and other servers the HUD is hidden, the keys do nothing and nothing is tracked, so other inventories never change your counts. The `/ssl` commands still work everywhere.

## Hypixel rules
Skyblock Shopping List is designed to stay within the [Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications) guidelines:
- It is read-only. It looks at screens you open and chat messages you receive (only Hypixel's own `[Sacks]` summaries and `Profile ID` line, matched exactly so player chat can't trigger them), and never sends packets, chat messages or commands to the server. It never hides or changes a message.
- It does not automate anything: no clicking, crafting, moving items or opening menus for you.
- The HUD only shows your own items and recipe arithmetic.

Outside the game connection it downloads recipes from the NEU repository, and at most once an hour while you are on SkyBlock reads Hypixel's public, keyless election data (`api.hypixel.net/v2/resources/skyblock/election`) to know whether Cole's Molten Forge perk is active.

As Hypixel states, every modification is used at your own risk.

## Where data is stored
Everything lives in `config/skyblock-shopping-list/` inside your Minecraft folder.

Each SkyBlock profile keeps its own data in `profiles/<account>/<profile>/`, so switching profile (or account) switches the counts and the shopping list with it. The profile is taken from Hypixel's `Profile ID: ...` chat line, sent each time you join SkyBlock. Right after a server change, tracking waits for that line (or 5 seconds, if another mod hides it) so items are never booked to the wrong profile. Data from before profiles existed is moved into the first profile seen. Per profile:
- `resources.json`: tracked item counts
- `shopping_list.json`: the shopping list
- `inventorydata.json`, `allcontainerData.json`, `sacks.json`, `sacks_meta.json`: last-seen inventory, container and sack contents, used to work out changes
- `coins.json`: last bank balance seen
- `forge.json`: what was cooking in the Forge when you last opened it
- `forge_speed.json`: your Quick Forge % and the current mayor forge bonus

`profiles/<account>/last_profile.txt` remembers the profile you used last. Shared by all profiles:
- `hud_style.json`: your Appearance settings
- `presets/`: your saved HUD presets, one file each (share them by sending the file or a share code); kept by a data reset
- `widget_config.json`: HUD position and size, expanded nodes and settings
- `forging.json`, `gemstone_recipes.json`: built-in fallback recipes
- `recipes_remote.json`, `recipes_remote_forge.json`, `recipes_remote_shop.json`: crafting, forge and NPC shop recipes parsed from the NEU repository
- `item_names.json`: SkyBlock item ID to name table
- `forge_times.json`: base forge time of each forge item, from the NEU repository
- `remote_sources.json`, `remote_sources_meta.json`: recipe source list and download cache state
- `neu-repo-extracted/`: the unpacked NEU repository, only while it is being parsed (deleted afterwards)

## Network access
On startup the mod downloads the [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) archive from GitHub (`codeload.github.com`) to get recipes. The request is a plain GET with an ETag, so unchanged data is not downloaded again. No player data is sent anywhere.

The sources are listed in `remote_sources.json`. Only `https` URLs and local files are accepted. The archive is unpacked to `neu-repo-extracted/` with path-traversal, entry-count and size limits, then parsed as JSON and deleted again. Nothing from it is executed.

## Building from source
Requires JDK 25.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runClient` starts a development client.

## Known limitations
- Items are matched by their SkyBlock ID where the item has one, so reforges and stars don't matter. Items without an ID (some sack and menu icons) fall back to their name, with colour codes and stat symbols ignored.
- Sack contents only resync when you open a sack; between opens they follow the `[Sacks]` chat summaries, so keep those enabled in your SkyBlock settings. If no sack has been opened for an hour while the list has items, a chat message reminds you (Settings > Sack reminder).
- Recipes cover what the NEU repository lists as crafting, forge, NPC shop and Kat upgrade recipes. Pets that only drop have no recipe.

## License and attribution
- Code: CC-BY-SA-4.0 (see `LICENSE`). Based on Inventory Reader by Scholiboi.
- Recipe and item data comes from NotEnoughUpdates-REPO; follow its license when reusing that data.
