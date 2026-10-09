package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
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

    private Map<String, Integer> snapshot;
    /** Epoch ms of the last sack menu read, 0 if never; null until loaded. */
    private Long lastRead;
    /**
     * Items read from an open sack menu ({@link ItemNames#normalize} keys), so their count is known. Chat
     * updates don't add to it: they only report changes. Null until loaded.
     */
    private Set<String> scanned;
    /**
     * Read-only copy of {@link #scanned} for the HUD, which asks per row per frame: read without the lock, so
     * drawing never waits on a sack being read. Null until the meta file is loaded.
     */
    private volatile Set<String> scannedView;
    /** Changes whenever {@link #scanned} grows or is cleared, so callers can cache what depends on it. */
    private volatile long scanVersion = 0;

    private SackReader() {}

    public static SackReader getInstance() {
        return INSTANCE;
    }

    private Map<String, Integer> snapshot() {
        if (snapshot == null) {
            Map<String, Integer> loaded = JsonFiles.read(FilePathManager.sacksJson(), MAP_TYPE);
            snapshot = loaded != null ? new LinkedHashMap<>(loaded) : new LinkedHashMap<>();
        }
        return snapshot;
    }

    /** Forgets the in-memory snapshot (after a reset deleted sacks.json). */
    public synchronized void clear() {
        snapshot = null;
        lastRead = null;
        scanned = null;
        scannedView = null;
        scanVersion++;
        // Loaded now (on a profile switch or reset), not on the first HUD frame that needs it.
        loadMeta();
    }

    /** When a sack menu was last read (epoch ms), or 0 if never. Chat updates don't count. */
    public synchronized long getLastRead() {
        loadMeta();
        return lastRead;
    }

    private void loadMeta() {
        if (lastRead != null) return;
        SackMeta meta = JsonFiles.read(FilePathManager.sacksMetaJson(), SackMeta.class);
        lastRead = meta != null ? meta.lastRead : 0L;
        scanned = meta != null && meta.scanned != null ? new HashSet<>(meta.scanned) : new HashSet<>();
        scannedView = Set.copyOf(scanned);
    }

    /** Changes whenever the set of scanned sack items changes. */
    public long getScanVersion() {
        return scanVersion;
    }

    /**
     * True when {@code name} is kept in a sack that hasn't been read from its menu yet, so the count shown for
     * it may be too low. False until the sack data is loaded (it is on each profile switch).
     */
    public boolean isUncertain(String name) {
        Set<String> view = scannedView;
        if (view == null || RecipeManager.getInstance().getSack(name).isEmpty()) return false;
        return !view.contains(ItemNames.normalize(name));
    }

    /** The sacks still to open for {@code names}, each once, in the order first needed. */
    public synchronized List<String> unscannedSacks(Collection<String> names) {
        // Runs on the HUD update thread every list update, so this loads the data before the HUD needs it.
        loadMeta();
        Set<String> out = new LinkedHashSet<>();
        for (String name : names) {
            if (isUncertain(name)) out.add(RecipeManager.getInstance().getSack(name));
        }
        return new ArrayList<>(out);
    }

    private static class SackMeta {
        long lastRead;
        List<String> scanned;
    }

    /** Reads every sack item's stored amount from an open sack menu. */
    public synchronized void readSack(AbstractContainerMenu handler, String title) {
        Map<String, Integer> current = new LinkedHashMap<>();
        boolean gemstoneSack = title.contains("Gemstone");
        for (ItemStack stack : MenuSlots.containerStacks(handler)) {
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

        loadMeta();
        boolean grew = false;
        for (String name : current.keySet()) grew |= scanned.add(ItemNames.normalize(name));
        if (grew) {
            scannedView = Set.copyOf(scanned);
            scanVersion++;
        }
        SackMeta meta = new SackMeta();
        meta.lastRead = lastRead = System.currentTimeMillis();
        meta.scanned = new ArrayList<>(scanned);
        JsonFiles.writeAsync(FilePathManager.sacksMetaJson(), () -> meta);

        Map<String, Integer> snap = snapshot();
        Map<String, Integer> deltas = new LinkedHashMap<>();
        current.forEach((name, count) -> {
            Integer previous = snap.put(name, count);
            int delta = count - (previous == null ? 0 : previous);
            if (delta != 0) deltas.put(name, delta);
        });
        if (!deltas.isEmpty()) {
            ResourcesManager.getInstance().saveData(deltas);
            Map<String, Integer> copy = new LinkedHashMap<>(snap);
            JsonFiles.writeAsync(FilePathManager.sacksJson(), () -> copy);
        }
    }

    /** Applies "[Sacks]" chat deltas to both the snapshot and the resource counts. */
    public synchronized void applyChatDeltas(Map<String, Integer> deltas) {
        if (deltas.isEmpty()) return;
        Map<String, Integer> snap = snapshot();
        deltas.forEach((name, delta) -> snap.merge(ItemNames.clean(name), delta, Integer::sum));
        ResourcesManager.getInstance().saveData(deltas);
        Map<String, Integer> copy = new LinkedHashMap<>(snap);
        JsonFiles.writeAsync(FilePathManager.sacksJson(), () -> copy);
    }

    private static void readStoredLore(String itemName, List<Component> lines, Map<String, Integer> out) {
        for (Component line : lines) {
            Matcher m = STORED.matcher(line.getString());
            if (m.find()) {
                Integer count = Counts.parse(m.group(1));
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
                Integer count = Counts.parse(m.group(2));
                if (count != null) out.put(m.group(1) + " " + gem, count);
            }
        }
    }
}
