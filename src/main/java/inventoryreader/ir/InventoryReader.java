package inventoryreader.ir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InventoryReader {
    public static final String MOD_ID = "skyblock-shopping-list";
    public static final String NAME = "Skyblock Shopping List";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** Settings > Advanced > Debug logging. Off: only warnings and errors reach latest.log. */
    public static volatile boolean debugLogging = false;

    private InventoryReader() {}

    /** Logs a diagnostic line only while Debug logging is on. */
    public static void debug(String message, Object... args) {
        if (debugLogging) LOGGER.info(message, args);
    }
}
