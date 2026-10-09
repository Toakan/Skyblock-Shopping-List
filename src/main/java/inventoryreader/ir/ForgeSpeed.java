package inventoryreader.ir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.io.File;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much faster the Forge runs for this player: the HOTM Quick Forge perk (read from the Heart of the
 * Mountain menu when it is opened) plus Cole's Molten Forge perk when he is mayor or minister (read from
 * Hypixel's public election resource, at most once an hour). The two stack additively.
 */
public final class ForgeSpeed {
    private static final Pattern QUICK_FORGE = Pattern.compile("forge,?\\s+by ([\\d.]+)%");
    private static final Pattern PERCENT = Pattern.compile("([\\d.]+)%");
    private static final String ELECTION_URL = "https://api.hypixel.net/v2/resources/skyblock/election";
    private static final String MOLTEN_FORGE = "Molten Forge";
    /** Molten Forge's cut if its description can't be read. */
    private static final double MOLTEN_FORGE_DEFAULT = 25;
    private static final long MAYOR_REFRESH_MS = 60 * 60 * 1000L;
    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);

    /** Saved to forge_speed.json. */
    private static final class Data {
        double quickForge;
        double mayorBonus;
        long mayorCheckedAt;
        /**
         * The profile file this was read from; changes are only saved back there, so an update that lands during a
         * profile switch (the mayor check runs on another thread) can't write this profile's values to the next one.
         */
        transient File file;
    }

    private static volatile Data data;
    private static volatile long version = 0;
    private static boolean loggedQuickForge = false;

    private ForgeSpeed() {}

    public static boolean isHotmMenu(String title) {
        return title.contains("Heart of the Mountain");
    }

    /** Reads the Quick Forge perk from the open Heart of the Mountain menu, if it is on this page. */
    public static void readHotm(AbstractContainerMenu menu) {
        for (ItemStack stack : MenuSlots.containerStacks(menu)) {
            if (!ItemNames.clean(stack.getHoverName().getString()).equals("Quick Forge")) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) return;
            List<String> lines = lore.lines().stream().map(Component::getString).toList();
            if (!loggedQuickForge && InventoryReader.debugLogging) {
                loggedQuickForge = true;
                InventoryReader.debug("Quick Forge lore: {}", lines);
            }
            // The lore wraps mid-sentence ("...to forge," / "by 30%."), so match across the joined lines.
            Matcher m = QUICK_FORGE.matcher(String.join(" ", lines));
            double percent = m.find() ? parse(m.group(1)) : 0;
            // The state is a line of its own, "ENABLED" or "DISABLED"; a switched-off perk does nothing.
            if (lines.stream().anyMatch(line -> line.trim().equalsIgnoreCase("DISABLED"))) percent = 0;
            if (get().quickForge != percent) {
                double value = percent;
                update(d -> d.quickForge = value);
            }
            return;
        }
    }

    /** Starts a background check of the current mayor and minister when the last one is over an hour old. */
    public static void refreshMayorIfDue() {
        // Only Forge times uses the bonus; with them off there is no reason to ask.
        if (HudStyle.get().forgeTimes == HudStyle.ForgeTimes.OFF) return;
        if (System.currentTimeMillis() - get().mayorCheckedAt < MAYOR_REFRESH_MS || !SkyblockDetector.isOnSkyblock()) return;
        if (!FETCHING.compareAndSet(false, true)) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create(ELECTION_URL)).timeout(Duration.ofSeconds(10)).GET().build();
        Http.CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            try {
                // Failures are retried at the next hourly check; the last known bonus stays in use.
                Double bonus = null;
                if (error == null && response.statusCode() / 100 == 2) {
                    bonus = moltenForgeBonus(response.body());
                } else {
                    InventoryReader.LOGGER.warn("Mayor check failed: {}", error != null ? error.toString() : "HTTP " + response.statusCode());
                }
                Double found = bonus;
                update(d -> {
                    d.mayorCheckedAt = System.currentTimeMillis();
                    if (found != null) d.mayorBonus = found;
                });
            } catch (RuntimeException e) {
                InventoryReader.LOGGER.warn("Mayor check failed: {}", e.toString());
            } finally {
                FETCHING.set(false);
            }
        });
    }

    /** Molten Forge's cut in percent when the mayor or minister has it, else 0. */
    private static double moltenForgeBonus(String body) {
        JsonObject mayor = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("mayor");
        if (mayor == null) return 0;
        JsonArray perks = mayor.getAsJsonArray("perks");
        if (perks != null) {
            for (JsonElement perk : perks) {
                Double bonus = moltenForge(perk.getAsJsonObject());
                if (bonus != null) return bonus;
            }
        }
        JsonObject minister = mayor.getAsJsonObject("minister");
        if (minister != null && minister.has("perk")) {
            Double bonus = moltenForge(minister.getAsJsonObject("perk"));
            if (bonus != null) return bonus;
        }
        return 0;
    }

    private static Double moltenForge(JsonObject perk) {
        if (perk == null || !perk.has("name") || !MOLTEN_FORGE.equals(perk.get("name").getAsString())) return null;
        String description = perk.has("description") ? ItemNames.clean(perk.get("description").getAsString()) : "";
        Matcher m = PERCENT.matcher(description);
        double bonus = m.find() ? parse(m.group(1)) : MOLTEN_FORGE_DEFAULT;
        InventoryReader.debug("Molten Forge active: -{}% forge time", bonus);
        return bonus > 0 ? bonus : MOLTEN_FORGE_DEFAULT;
    }

    /** Forge time after every bonus, as a fraction of the base time. */
    public static double multiplier() {
        Data d = get();
        return Math.max(0, 1 - (d.quickForge + d.mayorBonus) / 100.0);
    }

    /** Changes whenever a bonus changes. */
    public static long getVersion() {
        return version;
    }

    /** Forgets the in-memory copy (after a reset deleted the file). */
    public static synchronized void clear() {
        data = null;
        version++;
    }

    private static Data get() {
        Data d = data;
        return d != null ? d : load();
    }

    /** Under the same lock as {@link #clear()}, so a load from before a profile switch can't be kept after it. */
    private static synchronized Data load() {
        if (data == null) {
            File file = FilePathManager.forgeSpeedJson();
            Data d = JsonFiles.read(file, Data.class);
            if (d == null) d = new Data();
            d.file = file;
            data = d;
        }
        return data;
    }

    /**
     * Applies {@code change} to a copy and swaps the copy in, so readers on other threads (the HUD, the mayor
     * check) never see a half-changed value. Synchronized so two updates can't lose each other's change.
     */
    private static synchronized void update(java.util.function.Consumer<Data> change) {
        Data current = get();
        Data next = new Data();
        next.quickForge = current.quickForge;
        next.mayorBonus = current.mayorBonus;
        next.mayorCheckedAt = current.mayorCheckedAt;
        next.file = current.file;
        change.accept(next);
        data = next;
        version++;
        JsonFiles.writeAsync(next.file, () -> next);
    }

    private static double parse(String number) {
        try {
            return Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
