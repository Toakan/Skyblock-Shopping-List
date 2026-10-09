Pick the items you want to make, and a HUD shopping list shows what you can already craft and what you still need to gather. Skyblock Shopping List counts the items in your inventory, sacks, backpacks and ender chest, and works out every recipe tree down to its raw materials.

Built with Ironman accounts in mind, but just as useful on a regular profile for keeping track of your stock and planning crafts.

**Coming from Inventory Reader?** This mod grew out of [Inventory Reader](https://github.com/Scholiboi/InventoryReader) by Scholiboi. Remove the old jar before installing; your data is moved to `config/skyblock-shopping-list/` automatically on first launch.

## Features
- **Shopping list HUD:** add recipes with an amount and reorder them by priority. A **Total** section lists every raw material still needed (missing items first), followed by one tree per recipe. Your stock is shared between recipes and never counted twice. Each amount is either a total to hold (what you already have counts) or how many more to make.
- **Easy start:** no need to open every sack. The mod names the sacks your list needs, greys out counts it can't vouch for yet and highlights those sacks in `/sacks`.
- **Recipe filters:** find recipes by ingredient (*coal* finds Torch), item type (armor, accessory, pet...), rarity, or whether you own the item.
- **Saved lists:** save the current list under a name (Mining, Foraging, Combat...) and load it back with one click when you switch what you're working on.
- **Per-profile data:** each SkyBlock profile keeps its own counts and shopping list, and switching profile switches them with it.
- **Craftable:** intermediates you can make right now.
- **Forging:** items cooking in your Dwarven Forge that the list needs, with time left.
- **Recipe locks:** a padlock on rows whose recipe needs a collection, HotM or slayer unlock; hover it in the List tab to see what.
- **NPC price:** a bottom line showing what the whole list costs to buy from NPCs and what NPCs pay for it, with how many items have an NPC price.
- **Forge times:** forge rows show how long is still ahead, e.g. `Titanium Drill DR-X455 [25hrs]`, taking your Quick Forge perk and Cole's Molten Forge into account.
- **Recipes beyond the crafting table:** Dwarven Forge, NPC shop purchases and Kat pet upgrades. Even the Golden Dragon (Legendary), with its 500M coins, Enchanted Gold Blocks and Perfect gems, can go on the list.
- **Coins count as an item:** purse from the sidebar, bank balance when you open the bank. Shop currencies such as Agatha's Coupon can be added to the list too, to track how many you hold.
- **Pop-ups:** "Ready to craft" when a recipe has everything, and "Item achieved" when you've made the amount you asked for (it then leaves the list).
- **Fully customisable look:** colours with transparency, fonts (including resource pack fonts), text sizes, alignment, rounded corners, row spacing, amount formats, HUD scale (50-300%), and Craftable / Forging as separate panels. Drag panels to move them and drag corners or edges to resize them.
- **Presets:** save your look under a name, pick a built-in one (Default, Compact, Minimal), or share a look as a text code, for example in Discord.
- **Menu tabs:** browse any recipe's full tree in **Recipes**, check a forge recipe in **Forge**, and see and correct every tracked count in **Resources**.

## Getting started
The mod only knows what you have shown it. Open each of your sacks, backpacks and ender chest pages once on SkyBlock, plus each Accessory Bag page, each wardrobe page and the Stats & Equipment menu (for worn equipment). After that, counts stay up to date from your inventory, the containers you open and the `[Sacks]` chat summaries (keep those enabled in your SkyBlock settings).

## Keys and commands
| Key / command | Action |
| --- | --- |
| `V` or `/ssl menu` | Open the menu |
| `H` | Toggle the HUD |
| `B` or `/ssl hud` | Move / resize the HUD |
| `/ssl` | List commands |
| `/ssl reset` | Delete the current profile's tracked data and the HUD settings (saved lists and presets are kept) |

Keys can be rebound under Options > Controls > Key Binds > Skyblock Shopping List.

## Hypixel rules
Designed to stay within the [Hypixel Allowed Modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications) guidelines:
- Read-only: it looks at screens you open and chat messages you receive, and never sends packets, chat messages or commands.
- No automation: no clicking, crafting, moving items or opening menus for you.
- Only active on Hypixel SkyBlock. In lobbies, other games and other servers the HUD is hidden and nothing is tracked.

As Hypixel states, every modification is used at your own risk.

## Network access
Recipe Data: On startup the mod downloads the [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) archive from GitHub to get recipes (cached with an ETag, so unchanged data isn't downloaded again). Nothing from the archive is executed.

NPC Sell Prices: On startup it reads Hypixel's public item list for NPC sell prices, and only downloads it again when Hypixel has changed it.

Mayor Interaction Data: At most once an hour while you're on SkyBlock it reads Hypixel's public election data to know whether Cole's Molten Forge perk is active. With Forge times turned off (Settings > Appearance), this data is not read.

No player data is sent anywhere.

## Requirements
Minecraft 26.2 or 26.3 (one jar for both), Java 25, Fabric Loader 0.19.5+, Fabric API, YetAnotherConfigLib (already installed if you use Skyblocker) and the [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) (required: the game won't start without it).

## Known limitations
- Sack contents only fully resync when you open a sack; in between they follow the `[Sacks]` chat summaries. A reminder appears if no sack has been opened for an hour.
- Pets that only drop have no recipe.

## License and credits
GPL-3.0-only, with extra terms: keep the credits, mark modified versions, and give them their own name. Originally based on [Inventory Reader](https://github.com/Scholiboi/InventoryReader) by Scholiboi, whose inventory reading, recipe trees and HUD this mod was built on. Recipe and item data comes from [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO). Source code: [Toakan/Skyblock-Shopping-List](https://github.com/Toakan/Skyblock-Shopping-List).
