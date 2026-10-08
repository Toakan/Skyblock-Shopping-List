package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers what is cooking in the Dwarven Forge, read from "The Forge" menu when it is opened. Each forge
 * slot's item has a "Currently making: <item>" line and, while cooking, "Time Remaining: 12h 23m 50s".
 */
public final class ForgeTracker {
    private static final Pattern MAKING = Pattern.compile("Currently making:\\s*(.+)");
    private static final Pattern REMAINING = Pattern.compile("Time Remaining:\\s*(.+)");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)\\s*([dhms])");
    private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();

    /** One forge slot: what it makes, how many, and when it is done (epoch ms). */
    public static final class Entry {
        public String name;
        public int count;
        public long endsAt;

        Entry(String name, int count, long endsAt) {
            this.name = name;
            this.count = count;
            this.endsAt = endsAt;
        }
    }

    private static volatile List<Entry> entries;
    private static volatile long version = 0;
    private static boolean loggedReadyLore = false;

    private ForgeTracker() {}

    public static boolean isForgeMenu(String title) {
        return title.equals("The Forge");
    }

    /** Replaces the remembered forge contents with what the open Forge menu shows. */
    public static void readForge(AbstractContainerMenu menu) {
        long now = System.currentTimeMillis();
        List<Entry> found = new ArrayList<>();
        List<Slot> slots = menu.slots;
        // The last 36 slots are the player's own inventory.
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            String making = null;
            Long remaining = null;
            for (Component line : lore.lines()) {
                String text = line.getString();
                Matcher m = MAKING.matcher(text);
                if (m.find()) making = ItemNames.clean(m.group(1));
                Matcher r = REMAINING.matcher(text);
                if (r.find()) remaining = parseDuration(r.group(1));
            }
            // Glass panes and buttons have no "Currently making" line.
            if (making == null) continue;
            if (remaining == null && !loggedReadyLore) {
                loggedReadyLore = true;
                InventoryReader.LOGGER.info("Forge slot without a time, treated as ready: {}",
                    lore.lines().stream().map(Component::getString).toList());
            }
            String name = ItemNames.clean(ItemIds.nameOf(stack));
            found.add(new Entry(name.isEmpty() ? making : name, stack.getCount(), now + (remaining == null ? 0 : remaining)));
        }
        if (sameAs(getEntries(), found)) return;
        entries = List.copyOf(found);
        version++;
        JsonFiles.write(FilePathManager.FORGE_JSON, found);
    }

    /** Same items and counts, finishing within a few seconds of each other (times are read to the second). */
    private static boolean sameAs(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Entry x = a.get(i);
            Entry y = b.get(i);
            if (!x.name.equals(y.name) || x.count != y.count || Math.abs(x.endsAt - y.endsAt) > 5000) return false;
        }
        return true;
    }

    /** What was in the forge when it was last opened. */
    public static List<Entry> getEntries() {
        List<Entry> current = entries;
        if (current == null) {
            List<Entry> loaded = JsonFiles.read(FilePathManager.FORGE_JSON, LIST_TYPE);
            current = loaded != null ? List.copyOf(loaded) : List.of();
            entries = current;
        }
        return current;
    }

    /** Changes every time the forge is read. */
    public static long getVersion() {
        return version;
    }

    /** Forgets the in-memory copy (after a reset deleted the file). */
    public static void clear() {
        entries = null;
        version++;
    }

    /** "12h 23m 50s" -> milliseconds; null when no part matched. */
    static Long parseDuration(String text) {
        Matcher m = DURATION_PART.matcher(text);
        long seconds = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            seconds += switch (m.group(2)) {
                case "d" -> n * 86400;
                case "h" -> n * 3600;
                case "m" -> n * 60;
                default -> n;
            };
        }
        return any ? seconds * 1000 : null;
    }

    /** Time left as "12h 23m", "5m", "<1m", or "Ready". */
    public static String formatRemaining(long endsAt, long now) {
        long minutes = (endsAt - now) / 60_000;
        if (endsAt <= now) return "Ready";
        if (minutes < 1) return "<1m";
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }
}
