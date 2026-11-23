package io.github.rkaushikdev.trammelworks.spool;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Identity;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.Sections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The classes on this site and which unit is in which.
 *
 * <p>A class decides ship order, eviction order and retention floor all at once. They were separate
 * once and sites ended up with a class shipped first and evicted first, which is the worst of both.
 * Ordering is by weight, high first, and two classes may not share a weight, because "which of
 * these goes first" would then depend on the order somebody wrote the config in.
 *
 * <p>A record can also be promoted out of its unit's class by a label it carries. By default an
 * environmental tank alarm is promoted into the heaviest protected class, so a leak report is never
 * evicted however full the spool gets.
 *
 * @param classes every class on the site
 * @param assignments unit name to class name
 * @param defaultClass the class of any unit not assigned
 * @param promotions labels that lift a record into a class, the first match winning
 */
public record PriorityTable(
        List<PriorityClass> classes, Map<String, String> assignments, String defaultClass, List<Promotion> promotions) {

    /** The class an unassigned unit lands in. */
    public static final String DEFAULT_CLASS = "standard";

    /** Payment, protected; standard; chatter. */
    public static final List<PriorityClass> BUILTIN_CLASSES = List.of(
            new PriorityClass("payment", 100, true),
            new PriorityClass("standard", 50),
            new PriorityClass("chatter", 10));

    /** The label carrying how serious a tank alarm is. */
    public static final String SEVERITY_LABEL = "alarm_severity";

    /** The severity whose records the default promotion lifts. */
    public static final String ENVIRONMENTAL = "environmental";

    static final Set<String> KEYS = Set.of("classes", "units", "default", "promote");

    /** Validates the classes and normalizes the assignments. */
    public PriorityTable {
        classes = List.copyOf(classes);
        Set<String> names = new HashSet<>();
        for (PriorityClass item : classes) {
            names.add(item.name());
        }
        if (names.size() != classes.size()) {
            throw new ConfigError("two priority classes share a name");
        }
        if (classes.stream().map(PriorityClass::weight).distinct().count() != classes.size()) {
            throw new ConfigError("two priority classes share a weight");
        }
        if (!names.contains(defaultClass)) {
            throw new ConfigError("the default class is not defined", defaultClass);
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            if (!names.contains(entry.getValue())) {
                throw new ConfigError("a unit is assigned to an unknown class", entry.getValue());
            }
            cleaned.put(Identity.parseUnit(entry.getKey()), entry.getValue());
        }
        assignments = Maps.frozen(cleaned);
        promotions = List.copyOf(promotions);
        for (Promotion promotion : promotions) {
            if (!names.contains(promotion.klass())) {
                throw new ConfigError("a promotion names an unknown class", promotion.klass());
            }
        }
    }

    /** A table with the default promotion for its classes. */
    public PriorityTable(List<PriorityClass> classes, Map<String, String> assignments, String defaultClass) {
        this(classes, assignments, defaultClass, defaultPromotions(classes));
    }

    /** The built in classes with no assignments. */
    public PriorityTable() {
        this(Map.of());
    }

    /** The built in classes with some units assigned. */
    public PriorityTable(Map<String, String> assignments) {
        this(BUILTIN_CLASSES, assignments, DEFAULT_CLASS);
    }

    /** A class by name. */
    public PriorityClass byName(String name) {
        for (PriorityClass item : classes) {
            if (item.name().equals(name)) {
                return item;
            }
        }
        throw new ConfigError("unknown priority class", name);
    }

    /**
     * Environmental alarms into the heaviest protected class, when the table has one. A site with
     * no protected class has said nothing is to be kept at all costs, and is taken at its word.
     */
    public static List<Promotion> defaultPromotions(List<PriorityClass> classes) {
        return classes.stream()
                .filter(PriorityClass::isProtected)
                .max(Comparator.comparingInt(PriorityClass::weight))
                .map(top -> List.of(new Promotion(SEVERITY_LABEL, ENVIRONMENTAL, top.name())))
                .orElse(List.of());
    }

    /** Which class a unit's records belong to. */
    public PriorityClass classFor(String unit) {
        return byName(assignments.getOrDefault(unit, defaultClass));
    }

    /** Which class this record belongs to: a promotion it matches, otherwise its unit's class. */
    public PriorityClass classFor(LogRecord record) {
        for (Promotion promotion : promotions) {
            if (promotion.matches(record)) {
                return byName(promotion.klass());
            }
        }
        return classFor(record.unit());
    }

    /** Class names, highest weight first. */
    public List<String> shipOrder() {
        return classes.stream()
                .sorted(Comparator.comparingInt((PriorityClass item) -> -item.weight())
                        .thenComparing(PriorityClass::name))
                .map(PriorityClass::name)
                .toList();
    }

    /**
     * Class names in the order they may be deleted, lowest weight first. Protected classes are not
     * in this list at all: a protected class is never evicted, and putting it at the end would let
     * a caller that ran off the end delete it anyway.
     */
    public List<String> evictOrder() {
        return classes.stream()
                .filter(item -> !item.isProtected())
                .sorted(Comparator.comparingInt(PriorityClass::weight).thenComparing(PriorityClass::name))
                .map(PriorityClass::name)
                .toList();
    }

    /** The names of every protected class. */
    public Set<String> protectedClasses() {
        Set<String> out = new LinkedHashSet<>();
        for (PriorityClass item : classes) {
            if (item.isProtected()) {
                out.add(item.name());
            }
        }
        return Set.copyOf(out);
    }

    /** Build the table from the config section. */
    public static PriorityTable tableFromMapping(Map<String, ?> raw) {
        Sections.checkKeys(raw, KEYS, "priority");
        Object classesRaw = raw.get("classes");
        List<PriorityClass> classes;
        if (classesRaw == null) {
            classes = BUILTIN_CLASSES;
        } else {
            Map<String, Object> written = Sections.mapping(classesRaw, "priority classes must be a mapping", null);
            classes = new ArrayList<>();
            for (Map.Entry<String, Object> entry : written.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> body) {
                    Object weight = body.containsKey("weight") ? body.get("weight") : 0;
                    Object guarded = body.containsKey("protected") ? body.get("protected") : false;
                    classes.add(new PriorityClass(entry.getKey(), Coerce.toInt(weight), Coerce.toBool(guarded)));
                } else {
                    classes.add(new PriorityClass(entry.getKey(), Coerce.toInt(entry.getValue()), false));
                }
            }
        }
        Object unitsRaw = raw.containsKey("units") ? raw.get("units") : Map.of();
        Map<String, Object> units = Sections.mapping(unitsRaw, "priority unit assignments must be a mapping", null);
        Map<String, String> assignments = new LinkedHashMap<>();
        units.forEach((unit, klass) -> assignments.put(unit, Coerce.text(klass)));
        Object fallback = raw.containsKey("default") ? raw.get("default") : DEFAULT_CLASS;
        if (!raw.containsKey("promote")) {
            return new PriorityTable(classes, assignments, Coerce.text(fallback));
        }
        Map<String, Object> written =
                Sections.mapping(raw.get("promote"), "priority promotions must be a mapping", null);
        List<Promotion> promotions = new ArrayList<>();
        written.forEach((key, klass) -> promotions.add(Promotion.parse(key, Coerce.text(klass))));
        return new PriorityTable(classes, assignments, Coerce.text(fallback), promotions);
    }
}
