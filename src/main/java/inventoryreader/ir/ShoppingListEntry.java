package inventoryreader.ir;

/** One recipe on the shopping list. Plain fields so Gson can store it in widget_config.json. */
public final class ShoppingListEntry {
    public String recipe;
    public int amount;
    /** How many of the item the player had when it was added; reaching startCount + amount means achieved. */
    public int startCount;

    public ShoppingListEntry() {}

    public ShoppingListEntry(String recipe, int amount, int startCount) {
        this.recipe = recipe;
        this.amount = amount;
        this.startCount = startCount;
    }

    ShoppingListEntry copy() {
        return new ShoppingListEntry(recipe, amount, startCount);
    }
}
