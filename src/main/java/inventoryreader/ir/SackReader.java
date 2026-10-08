package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Tracks sack contents. Each item lives in exactly one sack, so a single item-to-count snapshot covers all
 * sacks. Opening a sack replaces the snapshot entries for its items and passes the difference on to
 * {@link ResourcesManager}; "[Sacks]" chat updates adjust the snapshot by the same delta, so reopening a
 * sack never counts a change twice.
 */
public class SackReader {
    private static final Set<String> GEMSTONE_RARITIES = Set.of("Rough", "Flawed", "Fine", "Flawless", "Perfect");
    private static final Pattern STORED = Pattern.compile("Stored:\\s*([\\d,]+)");
    private static final Pattern GEMSTONE_LINE = Pattern.compile("\\b(Rough|Flawed|Fine|Flawless|Perfect):\\s*([\\d,]+)");
    private static final Type MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();
    private static final SackReader INSTANCE = new SackReader();
    private static volatile boolean needsReminder = false;

    private Map<String, Integer> snapshot;
    /** Epoch ms of the last sack menu read, 0 if never; null until loaded. */
    private Long lastRead;

    private SackReader() {}

    public static SackReader getInstance() {
        return INSTANCE;
    }

    public static void setNeedsReminder(boolean state) {
        needsReminder = state;
    }

    public static boolean getNeedsReminder() {
        return needsReminder;
    }

    private Map<String, Integer> snapshot() {
        if (snapshot == null) {
            Map<String, Integer> loaded = JsonFiles.read(FilePathManager.SACKS_JSON, MAP_TYPE);
            snapshot = loaded != null ? new LinkedHashMap<>(loaded) : new LinkedHashMap<>();
        }
        return snapshot;
    }

    /** Forgets the in-memory snapshot (after a reset deleted sacks.json). */
    public synchronized void clear() {
        snapshot = null;
        lastRead = null;
    }

    /** When a sack menu was last read (epoch ms), or 0 if never. Chat updates don't count. */
    public synchronized long getLastRead() {
        if (lastRead == null) {
            SackMeta meta = JsonFiles.read(FilePathManager.SACKS_META_JSON, SackMeta.class);
            lastRead = meta != null ? meta.lastRead : 0L;
        }
        return lastRead;
    }

    private static class SackMeta {
        long lastRead;
    }

    /** Reads every sack item's stored amount from an open sack menu. */
    public synchronized void readSack(AbstractContainerMenu handler, String title) {
        setNeedsReminder(false);
        Map<String, Integer> current = new LinkedHashMap<>();
        boolean gemstoneSack = title.contains("Gemstone");
        List<Slot> slots = handler.slots;
        // The last 36 slots are the player's own inventory.
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            if (gemstoneSack) {
                String itemName = ItemNames.clean(stack.getHoverName().getString());
                readGemstoneLore(itemName, lore.lines(), current);
            } else {
                readStoredLore(ItemNames.clean(ItemIds.nameOf(stack)), lore.lines(), current);
            }
        }
        if (current.isEmpty()) return;

        SackMeta meta = new SackMeta();
        meta.lastRead = lastRead = System.currentTimeMillis();
        JsonFiles.write(FilePathManager.SACKS_META_JSON, meta);

        Map<String, Integer> snap = snapshot();
        Map<String, Integer> deltas = new LinkedHashMap<>();
        current.forEach((name, count) -> {
            Integer previous = snap.put(name, count);
            int delta = count - (previous == null ? 0 : previous);
            if (delta != 0) deltas.put(name, delta);
        });
        if (!deltas.isEmpty()) {
            ResourcesManager.getInstance().saveData(deltas);
            JsonFiles.write(FilePathManager.SACKS_JSON, snap);
        }
    }

    /** Applies "[Sacks]" chat deltas to both the snapshot and the resource counts. */
    public synchronized void applyChatDeltas(Map<String, Integer> deltas) {
        if (deltas.isEmpty()) return;
        Map<String, Integer> snap = snapshot();
        deltas.forEach((name, delta) -> snap.merge(ItemNames.clean(name), delta, Integer::sum));
        ResourcesManager.getInstance().saveData(deltas);
        JsonFiles.write(FilePathManager.SACKS_JSON, snap);
    }

    private static void readStoredLore(String itemName, List<Component> lines, Map<String, Integer> out) {
        for (Component line : lines) {
            Matcher m = STORED.matcher(line.getString());
            if (m.find()) {
                Integer count = parseCount(m.group(1));
                if (count != null) out.put(itemName, count);
                return;
            }
        }
    }

    /** Gemstone sack items are per gem type ("Jade Gemstones") with one lore line per rarity ("Fine: 1,234"). */
    private static void readGemstoneLore(String itemName, List<Component> lines, Map<String, Integer> out) {
        if (!itemName.contains("Gemstone")) return;
        String gem = itemName.endsWith("s") ? itemName.substring(0, itemName.length() - 1) : itemName;
        for (Component line : lines) {
            Matcher m = GEMSTONE_LINE.matcher(line.getString());
            if (m.find() && GEMSTONE_RARITIES.contains(m.group(1))) {
                Integer count = parseCount(m.group(2));
                if (count != null) out.put(m.group(1) + " " + gem, count);
            }
        }
    }

    private static Integer parseCount(String s) {
        try {
            return Integer.parseInt(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
