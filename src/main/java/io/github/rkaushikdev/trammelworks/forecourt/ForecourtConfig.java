package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.Identity;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.Sections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The {@code forecourt} section of a site config: which container plays which part.
 *
 * <p>Units the section does not name fall back to the names the reference build ships with, so a
 * site commissioned from the standard image needs no section at all. A site that renamed its
 * containers names them here, and a name here always beats the default.
 *
 * @param roles unit name to role, as the site wrote it
 */
public record ForecourtConfig(Map<String, Role> roles) {

    /** The roles of the containers in the reference build. */
    public static final Map<String, Role> DEFAULT_ROLES = Map.of(
            "pump-bridge", Role.DISPENSER,
            "payment-kernel", Role.PAYMENT,
            "emv-kernel", Role.PAYMENT,
            "tank-gauge", Role.TANK_GAUGE,
            "price-sign", Role.PRICE_SIGN);

    static final Set<String> KEYS = Set.of("roles");

    /** Normalizes the unit names. */
    public ForecourtConfig {
        Map<String, Role> cleaned = new LinkedHashMap<>();
        roles.forEach((unit, role) -> cleaned.put(Identity.parseUnit(unit), role));
        roles = Maps.frozen(cleaned);
    }

    /** No site specific roles. */
    public ForecourtConfig() {
        this(Map.of());
    }

    /** The part a unit plays: the site's word first, then the reference build's, then other. */
    public Role roleOf(String unit) {
        Role named = roles.get(unit);
        if (named != null) {
            return named;
        }
        return DEFAULT_ROLES.getOrDefault(unit, Role.OTHER);
    }

    /** The section as the config file writes it. */
    public Map<String, Object> asDict() {
        Map<String, Object> written = new LinkedHashMap<>();
        roles.forEach((unit, role) -> written.put(unit, role.value()));
        return Maps.of("roles", written);
    }

    /** Build the section, refusing anything it does not recognise. */
    public static ForecourtConfig forecourtFromMapping(Map<String, ?> raw) {
        Sections.checkKeys(raw, KEYS, "forecourt");
        Map<String, Role> roles = new LinkedHashMap<>();
        Sections.section(raw, "roles", "forecourt")
                .forEach((unit, role) -> roles.put(unit, Role.parse(Coerce.text(role))));
        return new ForecourtConfig(roles);
    }
}
