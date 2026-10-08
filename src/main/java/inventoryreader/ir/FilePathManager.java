package inventoryreader.ir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;
import inventoryreader.ir.recipes.RemoteRecipeFetcher;

public class FilePathManager {
    /** Everything the mod stores, in Fabric's standard config folder: config/skyblock-shopping-list/. */
    public static final File MOD_DIR = new File(FabricLoader.getInstance().getConfigDir().toFile(), InventoryReader.MOD_ID);
    public static final File DATA_DIR = MOD_DIR;
    /**
     * Folders used by older versions, newest first, in the game directory. Each kept its files in a data/
     * subfolder plus welcome_shown.txt at the top.
     */
    private static final File[] LEGACY_DIRS = {
        new File(FabricLoader.getInstance().getGameDir().toFile(), ".skyblock-shopping-list"),
        new File(FabricLoader.getInstance().getGameDir().toFile(), ".ir-data"),
    };
    public static final File CONTAINER_JSON = new File(DATA_DIR, "allcontainerData.json");
    public static final File INVENTORY_JSON = new File(DATA_DIR, "inventorydata.json");
    public static final File RESOURCES_JSON = new File(DATA_DIR, "resources.json");
    public static final File SACKS_JSON = new File(DATA_DIR, "sacks.json");
    /** When a sack menu was last read, for the stale-sack warning. */
    public static final File SACKS_META_JSON = new File(DATA_DIR, "sacks_meta.json");
    /** Last bank balance seen in the bank menu. */
    public static final File COINS_JSON = new File(DATA_DIR, "coins.json");
    /** What was cooking in the Dwarven Forge when it was last opened. */
    public static final File FORGE_JSON = new File(DATA_DIR, "forge.json");
    public static final File WIDGET_CONFIG_JSON = new File(DATA_DIR, "widget_config.json");
    public static final File FORGING_JSON = new File(DATA_DIR, "forging.json");
    public static final File GEMSTONE_RECIPES_JSON = new File(DATA_DIR, "gemstone_recipes.json");
    public static final File REMOTE_RECIPES_JSON = new File(DATA_DIR, "recipes_remote.json");
    public static final File REMOTE_FORGE_JSON = new File(DATA_DIR, "recipes_remote_forge.json");
    /** NPC shop purchases (coins + items) for items with no crafting or forge recipe. */
    public static final File REMOTE_SHOP_JSON = new File(DATA_DIR, "recipes_remote_shop.json");
    /** SkyBlock item ID to the name recipes use, written by the recipe fetch. */
    public static final File ITEM_NAMES_JSON = new File(DATA_DIR, "item_names.json");
    public static final File REMOTE_SOURCES_JSON = new File(DATA_DIR, "remote_sources.json");
    public static final File REMOTE_META_JSON = new File(DATA_DIR, "remote_sources_meta.json");
    /** Extracted NEU-REPO ZIP contents — read by NEURepository via the neurepoparser library. */
    public static final File NEU_REPO_EXTRACTED = new File(DATA_DIR, "neu-repo-extracted");
    private static final File VERSION_FILE = new File(DATA_DIR, "version.txt");
    /** Files written by older versions that nothing reads any more. */
    private static final File[] OBSOLETE_FILES = {
        new File(DATA_DIR, "recipes_all.json"),
        new File(DATA_DIR, "sackNames.txt"),
    };

    private static boolean initialized = false;

    /** Creates the data directory and default files, loads recipes and starts the background recipe fetch. */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        migrateLegacyDir();
        DATA_DIR.mkdirs();
        handleVersionUpgrade();
        migrateVersionedResources();
        cleanupStaleFiles();
        if (!REMOTE_SOURCES_JSON.exists()) initializeRemoteSourcesConfig();
        seedResources();
        RemoteRecipeFetcher.fetchAsync();
    }

    /** Moves the newest old data folder into config/skyblock-shopping-list/ the first time this version runs. */
    private static void migrateLegacyDir() {
        if (MOD_DIR.exists()) return;
        for (File legacy : LEGACY_DIRS) {
            if (!legacy.isDirectory()) continue;
            try {
                Files.createDirectories(MOD_DIR.toPath());
                File legacyData = new File(legacy, "data");
                File[] entries = legacyData.listFiles();
                if (entries != null) {
                    for (File entry : entries) {
                        Files.move(entry.toPath(), new File(MOD_DIR, entry.getName()).toPath());
                    }
                }
                File welcome = new File(legacy, "welcome_shown.txt");
                if (welcome.exists()) Files.move(welcome.toPath(), new File(MOD_DIR, welcome.getName()).toPath());
                // Only empty folders are removed; anything unexpected is left where it was.
                legacyData.delete();
                legacy.delete();
                InventoryReader.LOGGER.info("Moved {} to {}", legacy.getName(), MOD_DIR);
            } catch (IOException e) {
                InventoryReader.LOGGER.warn("Could not move {} to {}; some data may need moving by hand", legacy, MOD_DIR, e);
            }
            return;
        }
    }

    /** Deletes all tracked item data and widget settings. Recipes are kept. */
    public static synchronized void resetData() {
        for (File f : new File[]{CONTAINER_JSON, INVENTORY_JSON, RESOURCES_JSON, SACKS_JSON, SACKS_META_JSON, COINS_JSON, FORGE_JSON, WIDGET_CONFIG_JSON}) {
            if (f.exists() && !f.delete()) {
                InventoryReader.LOGGER.warn("Could not delete {}", f.getName());
            }
        }
        ResourcesManager.getInstance().reload();
        seedResources();
    }

    private static void seedResources() {
        ResourcesManager.getInstance().ensureResourceNames(Arrays.asList(DEFAULT_RESOURCE_NAMES));
        RecipeManager.getInstance().reload();
    }

    private static String getModVersionString() {
        return FabricLoader.getInstance()
            .getModContainer(InventoryReader.MOD_ID)
            .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
            .orElse("1");
    }

    private static void handleVersionUpgrade() {
        String version = getModVersionString();
        String stored = "";
        try {
            if (VERSION_FILE.exists()) stored = Files.readString(VERSION_FILE.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {}
        if (!version.equals(stored)) {
            FORGING_JSON.delete();
            GEMSTONE_RECIPES_JSON.delete();
            try {
                Files.writeString(VERSION_FILE.toPath(), version, StandardCharsets.UTF_8);
            } catch (IOException e) {
                InventoryReader.LOGGER.warn("Could not write version file", e);
            }
            InventoryReader.LOGGER.info("Version changed {} -> {}, regenerating recipe files", stored.isEmpty() ? "none" : stored, version);
        }
        RecipeFileGenerator.initializeRecipeFiles();
    }

    private static void migrateVersionedResources() {
        if (RESOURCES_JSON.exists()) return;
        File[] versioned = DATA_DIR.listFiles((d, n) -> n.matches("resources\\.v.+\\.json"));
        if (versioned == null || versioned.length == 0) return;
        Arrays.sort(versioned, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        try {
            Files.copy(versioned[0].toPath(), RESOURCES_JSON.toPath(), StandardCopyOption.REPLACE_EXISTING);
            InventoryReader.LOGGER.info("Migrated {} -> resources.json", versioned[0].getName());
            for (File f : versioned) f.delete();
        } catch (IOException e) {
            InventoryReader.LOGGER.warn("Failed to migrate versioned resources: {}", e.getMessage());
        }
    }

    private static void cleanupStaleFiles() {
        File[] stale = DATA_DIR.listFiles((d, n) ->
            n.endsWith(".tmp") ||
            n.matches("(forging|gemstone_recipes|resources)\\.v.+\\.json")
        );
        if (stale != null) {
            for (File f : stale) f.delete();
        }
        for (File f : OBSOLETE_FILES) f.delete();
    }

    private static void initializeRemoteSourcesConfig() {
        Map<String, String> neu = new LinkedHashMap<>();
        neu.put("type", "neu-zip");
        neu.put("url", RemoteRecipeFetcher.DEFAULT_NEU_REPO_URL);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("sources", List.of(neu));
        JsonFiles.write(REMOTE_SOURCES_JSON, cfg);
    }

    /** Items tracked even before any recipe data is available. */
    private static final String[] DEFAULT_RESOURCE_NAMES = {
        "Rough Amber Gemstone",
        "Flawed Amber Gemstone",
        "Fine Amber Gemstone",
        "Flawless Amber Gemstone",
        "Perfect Amber Gemstone",
        "Rough Amethyst Gemstone",
        "Flawed Amethyst Gemstone",
        "Fine Amethyst Gemstone",
        "Flawless Amethyst Gemstone",
        "Perfect Amethyst Gemstone",
        "Rough Aquamarine Gemstone",
        "Flawed Aquamarine Gemstone",
        "Fine Aquamarine Gemstone",
        "Flawless Aquamarine Gemstone",
        "Perfect Aquamarine Gemstone",
        "Rough Citrine Gemstone",
        "Flawed Citrine Gemstone",
        "Fine Citrine Gemstone",
        "Flawless Citrine Gemstone",
        "Perfect Citrine Gemstone",
        "Rough Jade Gemstone",
        "Flawed Jade Gemstone",
        "Fine Jade Gemstone",
        "Flawless Jade Gemstone",
        "Perfect Jade Gemstone",
        "Rough Jasper Gemstone",
        "Flawed Jasper Gemstone",
        "Fine Jasper Gemstone",
        "Flawless Jasper Gemstone",
        "Perfect Jasper Gemstone",
        "Rough Onyx Gemstone",
        "Flawed Onyx Gemstone",
        "Fine Onyx Gemstone",
        "Flawless Onyx Gemstone",
        "Perfect Onyx Gemstone",
        "Rough Opal Gemstone",
        "Flawed Opal Gemstone",
        "Fine Opal Gemstone",
        "Flawless Opal Gemstone",
        "Perfect Opal Gemstone",
        "Rough Peridot Gemstone",
        "Flawed Peridot Gemstone",
        "Fine Peridot Gemstone",
        "Flawless Peridot Gemstone",
        "Perfect Peridot Gemstone",
        "Rough Ruby Gemstone",
        "Flawed Ruby Gemstone",
        "Fine Ruby Gemstone",
        "Flawless Ruby Gemstone",
        "Perfect Ruby Gemstone",
        "Rough Sapphire Gemstone",
        "Flawed Sapphire Gemstone",
        "Fine Sapphire Gemstone",
        "Flawless Sapphire Gemstone",
        "Perfect Sapphire Gemstone",
        "Rough Topaz Gemstone",
        "Flawed Topaz Gemstone",
        "Fine Topaz Gemstone",
        "Flawless Topaz Gemstone",
        "Perfect Topaz Gemstone",
        "Refined Diamond",
        "Enchanted Diamond Block",
        "Enchanted Diamond",
        "Diamond",
        "Refined Mithril",
        "Enchanted Mithril",
        "Mithril",
        "Refined Titanium",
        "Enchanted Titanium",
        "Titanium",
        "Refined Umber",
        "Enchanted Umber",
        "Umber",
        "Refined Tungsten",
        "Enchanted Tungsten",
        "Tungsten",
        "Glacite Jewel",
        "Bejeweled Handle",
        "Drill Motor",
        "Treasurite",
        "Enchanted Iron Block",
        "Enchanted Iron",
        "Iron Ingot",
        "Enchanted Redstone Block",
        "Enchanted Redstone",
        "Redstone",
        "Golden Plate",
        "Enchanted Gold Block",
        "Enchanted Gold",
        "Gold Ingot",
        "Fuel Canister",
        "Enchanted Coal Block",
        "Enchanted Coal",
        "Coal",
        "Gemstone Mixture",
        "Sludge Juice",
        "Glacite Amalgamation",
        "Enchanted Glacite",
        "Glacite",
        "Mithril Plate",
        "Tungsten Plate",
        "Umber Plate",
        "Perfect Plate",
        "Mithril Drill SX-R226",
        "Mithril Drill SX-R326",
        "Ruby Drill TX-15",
        "Gemstone Drill LT-522",
        "Topaz Drill KGR-12",
        "Magma Core",
        "Jasper Drill X",
        "Polished Topaz Rod",
        "Titanium Drill DR-X355",
        "Titanium Drill DR-X455",
        "Titanium Drill DR-X555",
        "Titanium Drill DR-X655",
        "Plasma",
        "Corleonite",
        "Chisel",
        "Reinforced Chisel",
        "Glacite-Plated Chisel",
        "Perfect Chisel",
        "Divan's Drill",
        "Divan's Alloy",
        "Mithril Necklace",
        "Mithril Cloak",
        "Mithril Belt",
        "Mithril Gauntlet",
        "Titanium Necklace",
        "Titanium Cloak",
        "Titanium Belt",
        "Titanium Gauntlet",
        "Refined Mineral",
        "Titanium Talisman",
        "Titanium Ring",
        "Titanium Artifact",
        "Titanium Relic",
        "Divan's Powder Coating",
        "Glossy Gemstone",
        "Divan Fragment",
        "Helmet Of Divan",
        "Chestplate Of Divan",
        "Leggings Of Divan",
        "Boots Of Divan",
        "Amber Necklace",
        "Sapphire Cloak",
        "Jade Belt",
        "Amethyst Gauntlet",
        "Gemstone Chamber",
        "Worm Membrane",
        "Dwarven Handwarmers",
        "Dwarven Metal Talisman",
        "Pendant Of Divan",
        "Shattered Locket",
        "Relic Of Power",
        "Artifact Of Power",
        "Ring Of Power",
        "Talisman Of Power",
        "Diamonite",
        "Pocket Iceberg",
        "Petrified Starfall",
        "Starfall",
        "Pure Mithril",
        "Dwarven Geode",
        "Enchanted Cobblestone",
        "Cobblestone",
        "Titanium Tesseract",
        "Enchanted Lapis Block",
        "Enchanted Lapis Lazuli",
        "Lapis Lazuli",
        "Gleaming Crystal",
        "Scorched Topaz",
        "Enchanted Hard Stone",
        "Hard Stone",
        "Amber Material",
        "Frigid Husk",
        "Starfall Seasoning",
        "Goblin Omelette",
        "Goblin Egg",
        "Spicy Goblin Omelette",
        "Red Goblin Egg",
        "Pesto Goblin Omelette",
        "Green Goblin Egg",
        "Sunny Side Goblin Omelette",
        "Yellow Goblin Egg",
        "Blue Cheese Goblin Omelette",
        "Blue Goblin Egg",
        "Tungsten Regulator",
        "Mithril-Plated Drill Engine",
        "Titanium-Plated Drill Engine",
        "Ruby-Polished Drill Engine",
        "Precursor Apparatus",
        "Control Switch",
        "Electron Transmitter",
        "FTX 3070",
        "Robotron Reflector",
        "Superlite Motor",
        "Synthetic Heart",
        "Sapphire-Polished Drill Engine",
        "Amber-Polished Drill Engine",
        "Mithril-Infused Fuel Tank",
        "Titanium-Infused Fuel Tank",
        "Gemstone Fuel Tank",
        "Perfectly-Cut Fuel Tank",
        "Bejeweled Collar",
        "Beacon I",
        "Beacon II",
        "Glass",
        "Beacon III",
        "Beacon IV",
        "Beacon V",
        "Travel Scroll To The Dwarven Forge",
        "Enchanted Ender Pearl",
        "Ender Pearl",
        "Travel Scroll To The Dwarven Base Camp",
        "Power Crystal",
        "Secret Railroad Pass",
        "Tungsten Key",
        "Umber Key",
        "Skeleton Key",
        "Portable Campfire",
        "Match-Sticks",
        "Sulphur",
        "Stick",
        "Gemstone Gauntlet"
    };
}
