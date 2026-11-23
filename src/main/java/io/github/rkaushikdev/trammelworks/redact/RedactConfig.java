package io.github.rkaushikdev.trammelworks.redact;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Sections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code redact} section of a site config: what the redaction pass does on this site.
 *
 * <p>{@code enabled: false} exists for a bench in a lab, where there is no real card data and an
 * engineer benchmarking the reader wants the pass out of the way. It is refused for any site whose
 * id is not in the lab range, because the one way this ends badly is a production site commissioned
 * from a lab template.
 *
 * @param enabled whether the pass runs
 * @param keepFirst leading PAN digits kept, at most eight and only six below sixteen digits
 * @param keepLast trailing PAN digits kept, at most four
 * @param rules the site's own rules, in order
 */
public record RedactConfig(boolean enabled, int keepFirst, int keepLast, List<Rule> rules) {

    /** The default leading digits kept. */
    public static final int DEFAULT_KEEP_FIRST = 6;

    /** The default trailing digits kept. */
    public static final int DEFAULT_KEEP_LAST = 4;

    /** Sites in this range are benches, not forecourts. Nothing in them takes money. */
    public static final List<String> LAB_SITE_PREFIXES = List.of("XX-LAB-", "XX-DEV-");

    static final Set<String> KEYS = Set.of("enabled", "keep_first", "keep_last", "rules");

    /** Validates the keep lengths and the rule names. */
    public RedactConfig {
        rules = List.copyOf(rules);
        new PanMask(keepFirst, keepLast);
        Set<String> names = new HashSet<>();
        for (Rule rule : rules) {
            if (!names.add(rule.name())) {
                throw new ConfigError("two redaction rules share a name");
            }
        }
    }

    /** The defaults: on, keeping six and four, card rules only. */
    public RedactConfig() {
        this(true, DEFAULT_KEEP_FIRST, DEFAULT_KEEP_LAST, List.of());
    }

    /** The defaults with site rules. */
    public static RedactConfig withRules(List<Rule> rules) {
        return new RedactConfig(true, DEFAULT_KEEP_FIRST, DEFAULT_KEEP_LAST, rules);
    }

    /** The defaults, switched off. */
    public static RedactConfig disabled() {
        return new RedactConfig(false, DEFAULT_KEEP_FIRST, DEFAULT_KEEP_LAST, List.of());
    }

    /** Refuse a disabled pass anywhere that could see a real card. */
    public void checkSite(String site) {
        if (enabled) {
            return;
        }
        if (LAB_SITE_PREFIXES.stream().noneMatch(site::startsWith)) {
            throw new ConfigError("redaction can only be disabled on a lab site", site);
        }
    }

    /** What the card mask keeps on this site. */
    public PanMask mask() {
        return new PanMask(keepFirst, keepLast);
    }

    /** The site rules' names, in order. */
    public List<String> ruleNames() {
        return rules.stream().map(Rule::name).toList();
    }

    /** A one line description, for the doctor command. */
    public static RedactSummary summarize(RedactConfig config) {
        return new RedactSummary(config.enabled(), config.mask(), config.ruleNames());
    }

    /** Build the section, refusing anything it does not recognise. */
    public static RedactConfig redactFromMapping(Map<String, ?> raw) {
        Sections.checkKeys(raw, KEYS, "redact");
        Map<String, Object> rulesRaw = Sections.section(raw, "rules", "redact");
        return new RedactConfig(
                raw.containsKey("enabled") ? Coerce.toBool(raw.get("enabled")) : true,
                Coerce.toInt(raw.containsKey("keep_first") ? raw.get("keep_first") : DEFAULT_KEEP_FIRST),
                Coerce.toInt(raw.containsKey("keep_last") ? raw.get("keep_last") : DEFAULT_KEEP_LAST),
                Rules.rulesFromMapping(rulesRaw));
    }
}
