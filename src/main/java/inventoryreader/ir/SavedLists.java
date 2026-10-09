package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named shopping lists the player saved (List tab > Saved lists), e.g. "Mining" and "Foraging", to swap the
 * current list for. Kept in saved_lists.json for every profile; not part of the data reset, like HUD presets.
 */
public final class SavedLists {
    public static final int NAME_LIMIT = 32;
    private static final Type TYPE = new TypeToken<LinkedHashMap<String, List<ShoppingListEntry>>>() {}.getType();
    /** Name to entries, in the order they were first saved. */
    private static LinkedHashMap<String, List<ShoppingListEntry>> lists;

    private SavedLists() {}

    private static LinkedHashMap<String, List<ShoppingListEntry>> lists() {
        if (lists == null) {
            LinkedHashMap<String, List<ShoppingListEntry>> saved = JsonFiles.read(FilePathManager.SAVED_LISTS_JSON, TYPE);
            lists = new LinkedHashMap<>();
            if (saved != null) {
                saved.forEach((name, entries) -> {
                    if (name != null && !name.isBlank() && entries != null) lists.put(name, clean(entries));
                });
            }
        }
        return lists;
    }

    public static synchronized List<String> names() {
        return new ArrayList<>(lists().keySet());
    }

    /** The saved entries (copies), or null if there is no list by that name. */
    public static synchronized List<ShoppingListEntry> get(String name) {
        List<ShoppingListEntry> entries = lists().get(name);
        return entries == null ? null : clean(entries);
    }

    /** Saves {@code entries} as {@code name}, replacing a list already saved under it. */
    public static synchronized void save(String name, List<ShoppingListEntry> entries) {
        lists().put(name, clean(entries));
        write();
    }

    public static synchronized void delete(String name) {
        if (lists().remove(name) != null) write();
    }

    private static void write() {
        Map<String, List<ShoppingListEntry>> copy = new LinkedHashMap<>(lists);
        JsonFiles.writeAsync(FilePathManager.SAVED_LISTS_JSON, () -> copy);
    }

    /**
     * Valid entries only, copied. The starting count belongs to the profile that was playing when it was saved,
     * so it is dropped; loading takes it afresh.
     */
    private static List<ShoppingListEntry> clean(List<ShoppingListEntry> entries) {
        List<ShoppingListEntry> out = new ArrayList<>();
        for (ShoppingListEntry e : entries) {
            if (e != null && e.recipe != null && !e.recipe.isBlank()) {
                out.add(new ShoppingListEntry(e.recipe, Math.max(1, e.amount), 0, e.isHaveTotal()));
            }
        }
        return out;
    }
}
