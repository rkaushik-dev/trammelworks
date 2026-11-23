package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Identity;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Sections;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * <p>It also says where the site's trading day ends. The back office cuts every figure at the
 * day close in site time, and a report cut anywhere else does not reconcile with anything.
 *
 * @param roles unit name to role, as the site wrote it
 * @param timezone the site's time zone
 * @param dayClose when the trading day closes, site time
 */
public record ForecourtConfig(Map<String, Role> roles, ZoneId timezone, LocalTime dayClose) {

    /** The roles of the containers in the reference build. */
    public static final Map<String, Role> DEFAULT_ROLES = Map.of(
            "pump-bridge", Role.DISPENSER,
            "payment-kernel", Role.PAYMENT,
            "emv-kernel", Role.PAYMENT,
            "tank-gauge", Role.TANK_GAUGE,
            "price-sign", Role.PRICE_SIGN);

    static final Set<String> KEYS = Set.of("roles", "timezone", "day_close");

    /** Normalizes the unit names. */
    public ForecourtConfig {
        Map<String, Role> cleaned = new LinkedHashMap<>();
        roles.forEach((unit, role) -> cleaned.put(Identity.parseUnit(unit), role));
        roles = Maps.frozen(cleaned);
    }

    /** The zone a site that names none keeps its day in. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

    /** Site roles, the day closing at midnight UTC. */
    public ForecourtConfig(Map<String, Role> roles) {
        this(roles, DEFAULT_ZONE, LocalTime.MIDNIGHT);
    }

    /** No site specific roles. */
    public ForecourtConfig() {
        this(Map.of());
    }

    /** The trading day an instant falls in on this site. */
    public BusinessDay dayOf(NanoTime at) {
        return BusinessDay.containing(at, timezone, dayClose);
    }

    /** The last trading day to have closed by {@code now}. */
    public BusinessDay lastClosedDay(NanoTime now) {
        return BusinessDay.lastClosed(now, timezone, dayClose);
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
        return Maps.of("roles", written, "timezone", timezone.getId(), "day_close", dayClose.toString());
    }

    /** Build the section, refusing anything it does not recognise. */
    public static ForecourtConfig forecourtFromMapping(Map<String, ?> raw) {
        Sections.checkKeys(raw, KEYS, "forecourt");
        Map<String, Role> roles = new LinkedHashMap<>();
        Sections.section(raw, "roles", "forecourt")
                .forEach((unit, role) -> roles.put(unit, Role.parse(Coerce.text(role))));
        ZoneId zone = raw.containsKey("timezone") ? zone(Coerce.text(raw.get("timezone"))) : DEFAULT_ZONE;
        LocalTime close = raw.containsKey("day_close") ? dayClose(raw.get("day_close")) : LocalTime.MIDNIGHT;
        return new ForecourtConfig(roles, zone, close);
    }

    private static ZoneId zone(String written) {
        try {
            return ZoneId.of(written);
        } catch (DateTimeException exc) {
            throw new ConfigError("unknown time zone", written);
        }
    }

    /**
     * A day close written {@code HH:MM}. YAML reads an unquoted {@code 05:00} as the base sixty
     * number 300, which is that time in minutes past midnight, so a number is read as minutes.
     */
    static LocalTime dayClose(Object written) {
        if (written instanceof Integer minutes) {
            if (minutes < 0 || minutes >= 24 * 60) {
                throw new ConfigError("a day close is a time of day", Integer.toString(minutes));
            }
            return LocalTime.of(minutes / 60, minutes % 60);
        }
        try {
            return LocalTime.parse(Coerce.text(written));
        } catch (DateTimeException exc) {
            throw new ConfigError("a day close is written HH:MM", Coerce.text(written));
        }
    }
}
