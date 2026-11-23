package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.Text;
import java.util.Locale;

/**
 * What a container does on the forecourt, which decides what its lines are read for.
 *
 * <p>The unit name is whatever the integrator called the container, and it differs between
 * estates: one calls the dispenser bridge {@code pump-bridge}, another {@code fcc}. The role is the
 * fixed vocabulary the rest of stanchion reads lines by.
 */
public enum Role implements Json.Valued {
    /** The forecourt controller bridge talking to the dispensers. */
    DISPENSER("dispenser"),
    /** The payment kernel or the EMV kernel. */
    PAYMENT("payment"),
    /** The automatic tank gauge poller. */
    TANK_GAUGE("tank_gauge"),
    /** The price sign driver. */
    PRICE_SIGN("price_sign"),
    /** Anything else on the box. */
    OTHER("other");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    /** The role spelt this way, ignoring case and surrounding space. */
    public static Role parse(String raw) {
        String cleaned = Text.strip(raw).toLowerCase(Locale.ROOT);
        for (Role role : values()) {
            if (role.value.equals(cleaned)) {
                return role;
            }
        }
        throw new ConfigError("unknown forecourt role", raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
