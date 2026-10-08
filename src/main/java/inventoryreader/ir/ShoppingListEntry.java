package inventoryreader.ir;

/** One recipe on the shopping list. Plain fields so Gson can store it in widget_config.json. */
public final class ShoppingListEntry {
    public String recipe;
    public int amount;
    /** How many of the item the player had when it was added; used by Add more (startCount + amount = done). */
    public int startCount;
    /**
     * Have total (true): the amount is a total to hold, and held copies count. Add more (false): the amount is how
     * many more to make. Boxed so entries saved before this existed read as null, meaning Have total.
     */
    public Boolean haveTotal;

    public ShoppingListEntry() {}

    public ShoppingListEntry(String recipe, int amount, int startCount) {
        this(recipe, amount, startCount, true);
    }

    public ShoppingListEntry(String recipe, int amount, int startCount, boolean haveTotal) {
        this.recipe = recipe;
        this.amount = amount;
        this.startCount = startCount;
        this.haveTotal = haveTotal;
    }

    public boolean isHaveTotal() {
        return haveTotal == null || haveTotal;
    }

    ShoppingListEntry copy() {
        return new ShoppingListEntry(recipe, amount, startCount, isHaveTotal());
    }
}
