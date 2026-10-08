package inventoryreader.ir;

import java.util.LinkedHashMap;
import java.util.Map;

public class RecipeFileGenerator {

    public static void initializeRecipeFiles() {
        if (!FilePathManager.FORGING_JSON.exists()) {
            generateForgingRecipes();
        }
        if (!FilePathManager.GEMSTONE_RECIPES_JSON.exists()) {
            generateGemstoneRecipes();
        }
    }

    private static void generateForgingRecipes() {
        Map<String, Map<String, Integer>> forgingRecipes = new LinkedHashMap<>();
        forgingRecipes.put("Refined Diamond", mapOf("Enchanted Diamond Block", 2));
        forgingRecipes.put("Refined Mithril", mapOf("Enchanted Mithril", 160));
        forgingRecipes.put("Refined Titanium", mapOf("Enchanted Titanium", 16));
        forgingRecipes.put("Refined Umber", mapOf("Enchanted Umber", 160));
        forgingRecipes.put("Refined Tungsten", mapOf("Enchanted Tungsten", 160));
        forgingRecipes.put("Bejeweled Handle", mapOf("Glacite Jewel", 3));
        forgingRecipes.put("Drill Motor", mapOf(
            new String[]{"Treasurite", "Enchanted Iron Block", "Enchanted Redstone Block", "Golden Plate"},
            new int[]{10, 1, 3, 1}
        ));
        forgingRecipes.put("Fuel Canister", mapOf("Enchanted Coal Block", 2));
        forgingRecipes.put("Gemstone Mixture", mapOf(
            new String[]{"☘ Fine Jade Gemstone", "⸕ Fine Amber Gemstone", "❈ Fine Amethyst Gemstone", "✎ Fine Sapphire Gemstone", "Sludge Juice"},
            new int[]{4, 4, 4, 4, 320}
        ));
        forgingRecipes.put("Glacite Amalgamation", mapOf(
            new String[]{"☠ Fine Onyx Gemstone", "☘ Fine Citrine Gemstone", "☘ Fine Peridot Gemstone", "☂ Fine Aquamarine Gemstone", "Enchanted Glacite"},
            new int[]{4, 4, 4, 4, 256}
        ));
        forgingRecipes.put("Golden Plate", mapOf(
            new String[]{"Refined Diamond", "Enchanted Gold Block", "Glacite Jewel"},
            new int[]{1, 2, 5}
        ));
        forgingRecipes.put("Mithril Plate", mapOf(
            new String[]{"Refined Titanium", "Refined Mithril", "Golden Plate", "Enchanted Iron Block"},
            new int[]{1, 5, 1, 1}
        ));
        forgingRecipes.put("Tungsten Plate", mapOf(
            new String[]{"Refined Tungsten", "Glacite Amalgamation"},
            new int[]{4, 1}
        ));
        forgingRecipes.put("Umber Plate", mapOf(
            new String[]{"Refined Umber", "Glacite Amalgamation"},
            new int[]{4, 1}
        ));
        forgingRecipes.put("Perfect Plate", mapOf(
            new String[]{"Mithril Plate", "Tungsten Plate", "Umber Plate"},
            new int[]{1, 1, 1}
        ));
        forgingRecipes.put("Mithril Drill SX-R226", mapOf(
            new String[]{"Refined Mithril", "Fuel Canister", "Drill Motor"},
            new int[]{3, 1, 1}
        ));
        forgingRecipes.put("Mithril Drill SX-R326", mapOf(
            new String[]{"Mithril Drill SX-R226", "Golden Plate", "Mithril Plate"},
            new int[]{1, 1, 1}
        ));
        forgingRecipes.put("Ruby Drill TX-15", mapOf(
            new String[]{"❤ Fine Ruby Gemstone", "Fuel Canister", "Drill Motor"},
            new int[]{6, 1, 1}
        ));
        forgingRecipes.put("Gemstone Drill LT-522", mapOf(
            new String[]{"Ruby Drill TX-15", "Gemstone Mixture"},
            new int[]{1, 3}
        ));
        forgingRecipes.put("Topaz Drill KGR-12", mapOf(
            new String[]{"Gemstone Drill LT-522", "✧ Flawless Topaz Gemstone", "Gemstone Mixture", "Magma Core"},
            new int[]{1, 1, 3, 8}
        ));
        forgingRecipes.put("Jasper Drill X", mapOf(
            new String[]{"Topaz Drill KGR-12", "❁ Flawless Jasper Gemstone", "Treasurite", "Magma Core"},
            new int[]{1, 1, 100, 16}
        ));
        forgingRecipes.put("Polished Topaz Rod", mapOf(
            new String[]{"✧ Flawless Topaz Gemstone", "Bejeweled Handle"},
            new int[]{2, 3}
        ));
        forgingRecipes.put("Titanium Drill DR-X355", mapOf(
            new String[]{"Refined Titanium", "Refined Mithril", "Drill Motor", "Fuel Canister", "Golden Plate"},
            new int[]{8, 8, 1, 1, 6}
        ));
        forgingRecipes.put("Titanium Drill DR-X455", mapOf(
            new String[]{"Titanium Drill DR-X355", "Refined Diamond", "Refined Titanium", "Mithril Plate"},
            new int[]{1, 10, 12, 5}
        ));
        forgingRecipes.put("Titanium Drill DR-X555", mapOf(
            new String[]{"Titanium Drill DR-X455", "Refined Diamond", "Refined Titanium", "Enchanted Iron Block", "Mithril Plate", "Plasma"},
            new int[]{1, 20, 16, 2, 10, 20}
        ));
        forgingRecipes.put("Titanium Drill DR-X655", mapOf(
            new String[]{"Titanium Drill DR-X555", "❤ Flawless Ruby Gemstone", "Corleonite", "Refined Diamond", "Refined Titanium", "Gemstone Mixture", "Mithril Plate"},
            new int[]{1, 1, 30, 5, 32, 16, 10}
        ));
        forgingRecipes.put("Chisel", mapOf(
            new String[]{"Bejeweled Handle", "Tungsten"},
            new int[]{1, 64}
        ));
        forgingRecipes.put("Reinforced Chisel", mapOf(
            new String[]{"Chisel", "Refined Tungsten", "Refined Umber", "Bejeweled Handle"},
            new int[]{1, 2, 2, 1}
        ));
        forgingRecipes.put("Glacite-Plated Chisel", mapOf(
            new String[]{"Reinforced Chisel", "Glacite Amalgamation", "Mithril Plate", "Bejeweled Handle"},
            new int[]{1, 8, 1, 1}
        ));
        forgingRecipes.put("Perfect Chisel", mapOf(
            new String[]{"Glacite-Plated Chisel", "Perfect Plate", "Bejeweled Handle"},
            new int[]{1, 1, 1}
        ));
        forgingRecipes.put("Divan's Drill", mapOf(
            new String[]{"Titanium Drill DR-X655", "Divan's Alloy"},
            new int[]{1, 1}
        ));
        forgingRecipes.put("Diamonite", mapOf("Refined Diamond", 3));
        forgingRecipes.put("Pocket Iceberg", mapOf("Glacite Jewel", 5));
        forgingRecipes.put("Petrified Starfall", mapOf("Starfall", 512));
        forgingRecipes.put("Pure Mithril", mapOf("Refined Mithril", 2));
        forgingRecipes.put("Dwarven Geode", mapOf(
            new String[]{"Enchanted Cobblestone", "Treasurite"},
            new int[]{128, 64}
        ));
        forgingRecipes.put("Titanium Tesseract", mapOf(
            new String[]{"Refined Titanium", "Enchanted Lapis Lazuli"},
            new int[]{1, 16}
        ));
        forgingRecipes.put("Gleaming Crystal", mapOf(
            new String[]{"Glossy Gemstone", "Refined Mithril", "Refined Diamond"},
            new int[]{32, 1, 2}
        ));
        forgingRecipes.put("Scorched Topaz", mapOf(
            new String[]{"Enchanted Hard Stone", "✧ Flawless Topaz Gemstone"},
            new int[]{128, 1}
        ));
        forgingRecipes.put("Amber Material", mapOf(
            new String[]{"⸕ Fine Amber Gemstone", "Golden Plate"},
            new int[]{12, 1}
        ));
        forgingRecipes.put("Frigid Husk", mapOf(
            new String[]{"Glacite Amalgamation", "☠ Flawless Onyx Gemstone"},
            new int[]{4, 1}
        ));
        forgingRecipes.put("Starfall Seasoning", mapOf(
            new String[]{"Treasurite", "Starfall"},
            new int[]{16, 64}
        ));
        forgingRecipes.put("Goblin Omelette", mapOf("Goblin Egg", 96));
        forgingRecipes.put("Spicy Goblin Omelette", mapOf(
            new String[]{"Red Goblin Egg", "❤ Flawless Ruby Gemstone", "Goblin Omelette"},
            new int[]{96, 1, 1}
        ));
        forgingRecipes.put("Pesto Goblin Omelette", mapOf(
            new String[]{"Green Goblin Egg", "☘ Flawless Jade Gemstone", "Goblin Omelette"},
            new int[]{96, 1, 1}
        ));
        forgingRecipes.put("Sunny Side Goblin Omelette", mapOf(
            new String[]{"Yellow Goblin Egg", "✧ Flawless Topaz Gemstone", "Goblin Omelette"},
            new int[]{96, 1, 1}
        ));
        forgingRecipes.put("Blue Cheese Goblin Omelette", mapOf(
            new String[]{"Blue Goblin Egg", "✎ Flawless Sapphire Gemstone", "Goblin Omelette"},
            new int[]{96, 1, 1}
        ));
        forgingRecipes.put("Tungsten Regulator", mapOf(
            new String[]{"❂ Perfect Opal Gemstone", "Fuel Canister", "Tungsten Plate"},
            new int[]{1, 5, 5}
        ));
        forgingRecipes.put("Mithril-Plated Drill Engine", mapOf(
            new String[]{"Drill Motor", "Mithril Plate"},
            new int[]{2, 1}
        ));
        forgingRecipes.put("Titanium-Plated Drill Engine", mapOf(
            new String[]{"Mithril-Plated Drill Engine", "Refined Titanium", "Drill Motor"},
            new int[]{1, 8, 2}
        ));
        forgingRecipes.put("Ruby-Polished Drill Engine", mapOf(
            new String[]{"Titanium-Plated Drill Engine", "❤ Perfect Ruby Gemstone", "Precursor Apparatus", "Drill Motor"},
            new int[]{1, 1, 4, 5}
        ));
        forgingRecipes.put("Sapphire-Polished Drill Engine", mapOf(
            new String[]{"Ruby-Polished Drill Engine", "✎ Perfect Sapphire Gemstone", "Precursor Apparatus", "Drill Motor", "Plasma"},
            new int[]{1, 3, 8, 5, 16}
        ));
        forgingRecipes.put("Amber-Polished Drill Engine", mapOf(
            new String[]{"Sapphire-Polished Drill Engine", "⸕ Perfect Amber Gemstone", "Precursor Apparatus", "Drill Motor", "Plasma"},
            new int[]{1, 5, 16, 5, 32}
        ));
        forgingRecipes.put("Mithril-Infused Fuel Tank", mapOf(
            new String[]{"Refined Diamond", "Refined Mithril", "Fuel Canister"},
            new int[]{5, 10, 5}
        ));
        forgingRecipes.put("Titanium-Infused Fuel Tank", mapOf(
            new String[]{"Mithril-Infused Fuel Tank", "Refined Titanium", "Refined Diamond", "Fuel Canister"},
            new int[]{1, 10, 5, 5}
        ));
        forgingRecipes.put("Gemstone Fuel Tank", mapOf(
            new String[]{"Titanium-Infused Fuel Tank", "Precursor Apparatus", "Gemstone Mixture"},
            new int[]{1, 4, 10}
        ));
        forgingRecipes.put("Perfectly-Cut Fuel Tank", mapOf(
            new String[]{"Gemstone Fuel Tank", "Precursor Apparatus", "Gemstone Mixture", "Plasma"},
            new int[]{1, 16, 25, 32}
        ));
        forgingRecipes.put("Bejeweled Collar", mapOf(
            new String[]{"Refined Mithril", "Bejeweled Handle"},
            new int[]{4, 1}
        ));
        forgingRecipes.put("Beacon II", mapOf(
            new String[]{"Enchanted Beacon", "Refined Mithril", "Beacon I"},
            new int[]{1, 5, 1}
        ));
        forgingRecipes.put("Beacon III", mapOf(
            new String[]{"Enchanted Beacon", "Refined Mithril", "Beacon II"},
            new int[]{1, 10, 1}
        ));
        forgingRecipes.put("Beacon IV", mapOf(
            new String[]{"Enchanted Beacon", "Refined Mithril", "Plasma", "Beacon III"},
            new int[]{1, 20, 1, 1}
        ));
        forgingRecipes.put("Beacon V", mapOf(
            new String[]{"Enchanted Beacon", "Refined Mithril", "Plasma", "Beacon IV"},
            new int[]{1, 40, 5, 1}
        ));
        forgingRecipes.put("Travel Scroll To The Dwarven Forge", mapOf(
            new String[]{"Titanium", "Enchanted Ender Pearl", "Mithril"},
            new int[]{80, 16, 48}
        ));
        forgingRecipes.put("Travel Scroll To The Dwarven Base Camp", mapOf(
            new String[]{"☠ Flawless Onyx Gemstone", "Enchanted Ender Pearl"},
            new int[]{1, 16}
        ));
        forgingRecipes.put("Power Crystal", mapOf("Nether Star", 256));
        forgingRecipes.put("Secret Railroad Pass", mapOf(
            new String[]{"❤ Flawless Ruby Gemstone", "Refined Mithril", "Corleonite"},
            new int[]{1, 2, 8}
        ));
        forgingRecipes.put("Tungsten Key", mapOf(
            new String[]{"Enchanted Lever", "Enchanted Tungsten", "Bejeweled Handle"},
            new int[]{1, 192, 1}
        ));
        forgingRecipes.put("Umber Key", mapOf(
            new String[]{"Enchanted Dead Bush", "Enchanted Umber", "Bejeweled Handle"},
            new int[]{1, 192, 1}
        ));
        forgingRecipes.put("Skeleton Key", mapOf(
            new String[]{"Tripwire Hook", "Perfect Plate", "Bejeweled Handle"},
            new int[]{1, 1, 1}
        ));
        forgingRecipes.put("Portable Campfire", mapOf(
            new String[]{"Furnace", "Refined Umber", "Match-Sticks"},
            new int[]{1, 1, 16}
        ));
        forgingRecipes.put("Mithril Necklace", mapOf("Enchanted Mithril", 3));
        forgingRecipes.put("Mithril Cloak", mapOf("Enchanted Mithril", 3));
        forgingRecipes.put("Mithril Belt", mapOf("Enchanted Mithril", 3));
        forgingRecipes.put("Mithril Gauntlet", mapOf("Enchanted Mithril", 3));
        forgingRecipes.put("Titanium Necklace", mapOf(
            new String[]{"Refined Mineral", "Refined Titanium", "Mithril Necklace"},
            new int[]{16, 1, 1}
        ));
        forgingRecipes.put("Titanium Cloak", mapOf(
            new String[]{"Refined Mineral", "Refined Titanium", "Mithril Cloak"},
            new int[]{16, 1, 1}
        ));
        forgingRecipes.put("Titanium Belt", mapOf(
            new String[]{"Refined Mineral", "Refined Titanium", "Mithril Belt"},
            new int[]{16, 1, 1}
        ));
        forgingRecipes.put("Titanium Gauntlet", mapOf(
            new String[]{"Refined Mineral", "Refined Titanium", "Mithril Gauntlet"},
            new int[]{16, 1, 1}
        ));
        forgingRecipes.put("Titanium Talisman", mapOf("Refined Titanium", 2));
        forgingRecipes.put("Titanium Ring", mapOf(
            new String[]{"Refined Titanium", "Titanium Talisman"},
            new int[]{6, 1}
        ));
        forgingRecipes.put("Titanium Artifact", mapOf(
            new String[]{"Refined Titanium", "Titanium Ring"},
            new int[]{12, 1}
        ));
        forgingRecipes.put("Titanium Relic", mapOf(
            new String[]{"Refined Titanium", "Titanium Artifact"},
            new int[]{20, 1}
        ));
        forgingRecipes.put("Divan's Powder Coating", mapOf(
            new String[]{"Glossy Gemstone", "Refined Mineral", "Divan Fragment", "Enchanted Gold Block"},
            new int[]{32, 32, 5, 16}
        ));
        forgingRecipes.put("Helmet Of Divan", mapOf(
            new String[]{"❤ Flawless Ruby Gemstone", "Divan Fragment", "Gemstone Mixture"},
            new int[]{1, 5, 10}
        ));
        forgingRecipes.put("Chestplate Of Divan", mapOf(
            new String[]{"❤ Flawless Ruby Gemstone", "Divan Fragment", "Gemstone Mixture"},
            new int[]{1, 8, 10}
        ));
        forgingRecipes.put("Leggings Of Divan", mapOf(
            new String[]{"❤ Flawless Ruby Gemstone", "Divan Fragment", "Gemstone Mixture"},
            new int[]{1, 7, 10}
        ));
        forgingRecipes.put("Boots Of Divan", mapOf(
            new String[]{"❤ Flawless Ruby Gemstone", "Divan Fragment", "Gemstone Mixture"},
            new int[]{1, 4, 10}
        ));
        forgingRecipes.put("Amber Necklace", mapOf(
            new String[]{"Glossy Gemstone", "⸕ Flawless Amber Gemstone"},
            new int[]{32, 2}
        ));
        forgingRecipes.put("Sapphire Cloak", mapOf(
            new String[]{"Glossy Gemstone", "✎ Flawless Sapphire Gemstone"},
            new int[]{32, 2}
        ));
        forgingRecipes.put("Jade Belt", mapOf(
            new String[]{"Glossy Gemstone", "☘ Flawless Jade Gemstone"},
            new int[]{32, 2}
        ));
        forgingRecipes.put("Amethyst Gauntlet", mapOf(
            new String[]{"Glossy Gemstone", "❈ Flawless Amethyst Gemstone"},
            new int[]{32, 2}
        ));
        forgingRecipes.put("Gemstone Chamber", mapOf(
            new String[]{"Worm Membrane", "Gemstone Mixture"},
            new int[]{100, 1}
        ));
        forgingRecipes.put("Dwarven Handwarmers", mapOf(
            new String[]{"☘ Flawless Jade Gemstone", "⸕ Flawless Amber Gemstone", "Tungsten Plate", "Umber Plate"},
            new int[]{1, 1, 1, 1}
        ));
        forgingRecipes.put("Dwarven Metal Talisman", mapOf(
            new String[]{"Refined Umber", "Refined Tungsten", "Glacite Amalgamation"},
            new int[]{4, 4, 4}
        ));
        forgingRecipes.put("Pendant Of Divan", mapOf(
            new String[]{"Shattered Locket", "Perfect Plate", "Divan Fragment"},
            new int[]{1, 1, 10}
        ));
        forgingRecipes.put("Relic Of Power", mapOf(
            new String[]{"Power Artifact", "Perfect Plate", "Glacite Amalgamation"},
            new int[]{1, 1, 32}
        ));
        forgingRecipes.put("Gemstone Gauntlet", mapOf(
            new String[]{"❤ Flawless Ruby Gemstone", "Enchanted Gold Block"},
            new int[]{8, 16}
        ));

        forgingRecipes.put("Enchanted Diamond Block", mapOf("Enchanted Diamond", 160));
        forgingRecipes.put("Enchanted Diamond", mapOf("Diamond", 160));
        forgingRecipes.put("Enchanted Mithril", mapOf("Mithril", 160));
        forgingRecipes.put("Enchanted Titanium", mapOf("Titanium", 160));
        forgingRecipes.put("Enchanted Umber", mapOf("Umber", 160));
        forgingRecipes.put("Enchanted Tungsten", mapOf("Tungsten", 160));
        forgingRecipes.put("Enchanted Iron Block", mapOf("Enchanted Iron", 160));
        forgingRecipes.put("Enchanted Iron", mapOf("Iron Ingot", 160));
        forgingRecipes.put("Enchanted Redstone Block", mapOf("Enchanted Redstone", 160));
        forgingRecipes.put("Enchanted Redstone", mapOf("Redstone", 160));
        forgingRecipes.put("Enchanted Coal Block", mapOf("Enchanted Coal", 160));
        forgingRecipes.put("Enchanted Coal", mapOf("Coal", 160));
        forgingRecipes.put("Enchanted Glacite", mapOf("Glacite", 160));
        forgingRecipes.put("Enchanted Gold Block", mapOf("Enchanted Gold", 160));
        forgingRecipes.put("Enchanted Gold", mapOf("Gold Ingot", 160));
        forgingRecipes.put("Enchanted Lapis Lazuli", mapOf("Lapis Lazuli", 160));
        forgingRecipes.put("Enchanted Cobblestone", mapOf("Cobblestone", 160));

        forgingRecipes.put("Precursor Apparatus", mapOf(
            new String[]{"Control Switch", "Electron Transmitter", "FTX 3070", "Robotron Reflector", "Superlite Motor", "Synthetic Heart"},
            new int[]{1, 1, 1, 1, 1, 1}
        ));

        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("version", 1);
        wrapped.put("recipes", forgingRecipes);
        JsonFiles.write(FilePathManager.FORGING_JSON, wrapped);
    }

    private static void generateGemstoneRecipes() {
        Map<String, Map<String, Integer>> gemstoneRecipes = new LinkedHashMap<>();
        String[] gemstoneTypes = {
            "⸕ Amber", "❈ Amethyst", "☂ Aquamarine", "☘ Citrine", "☘ Jade", "❁ Jasper",
            "☠ Onyx", "❂ Opal", "☘ Peridot", "❤ Ruby", "✎ Sapphire", "✧ Topaz"
        };
        for (String type : gemstoneTypes) {
            String[] parts = type.split(" ", 2);
            String symbol = parts[0];
            String name = parts[1];

            Map<String, Integer> fine = new LinkedHashMap<>();
            fine.put(symbol + " Flawed " + name + " Gemstone", 80);
            gemstoneRecipes.put(symbol + " Fine " + name + " Gemstone", fine);
            Map<String, Integer> flawed = new LinkedHashMap<>();
            flawed.put(symbol + " Rough " + name + " Gemstone", 80);
            gemstoneRecipes.put(symbol + " Flawed " + name + " Gemstone", flawed);
            Map<String, Integer> flawless = new LinkedHashMap<>();
            flawless.put(symbol + " Fine " + name + " Gemstone", 80);
            gemstoneRecipes.put(symbol + " Flawless " + name + " Gemstone", flawless);
            Map<String, Integer> perfect = new LinkedHashMap<>();
            perfect.put(symbol + " Flawless " + name + " Gemstone", 5);
            gemstoneRecipes.put(symbol + " Perfect " + name + " Gemstone", perfect);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("version", 1);
        wrapped.put("recipes", gemstoneRecipes);
        JsonFiles.write(FilePathManager.GEMSTONE_RECIPES_JSON, wrapped);
    }

    private static Map<String, Integer> mapOf(String key, int value) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
    private static Map<String, Integer> mapOf(String[] keys, int[] values) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) {
            m.put(keys[i], values[i]);
        }
        return m;
    }
}
