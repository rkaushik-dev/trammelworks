package io.github.rkaushikdev.trammelworks.redact;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A secret found by the name printed beside it rather than by its shape.
 *
 * <p>Sixteen hex characters is a PIN block and also half the identifiers on the box, four digits is
 * a fleet driver PIN and also a pump's sale number, so these are only taken when the line says what
 * the value is: one of the {@code keys}, then {@code =} or {@code :}, then the value. A value in
 * double quotes is taken whatever it holds, because a quoted value after one of these keys is the
 * secret whether or not it has the shape we expected; an unquoted one has to have the shape.
 *
 */
public final class KeyedSecret {

    /** A run of hex characters as PIN blocks and card codes are printed. */
    public static final String HEX = "[0-9A-Fa-f]{3,32}";

    /** A name as a stripe writes it, {@code SURNAME/GIVEN}, or a single word. */
    public static final String NAME = "[A-Za-z][A-Za-z'.-]*(?:/[A-Za-z'.-]+(?: [A-Za-z'.]+)?)?";

    private final SecretKind kind;
    private final List<String> keys;
    private final Pattern pattern;

    /**
     * A secret printed under any of {@code keys}.
     *
     * @param kind what the audit counts it as
     * @param keys every spelling of the name, matched ignoring case
     * @param shape what an unquoted value looks like, as a regular expression
     */
    public KeyedSecret(SecretKind kind, List<String> keys, String shape) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("a keyed secret needs at least one key");
        }
        this.kind = kind;
        this.keys = List.copyOf(keys);
        String names = this.keys.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        this.pattern = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])(?:" + names + ")\\s*[=:]\\s*(?:\"(?<q>[^\"\\n]{1,60})\"|(?<v>" + shape + "))",
                Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** What the audit counts it as. */
    public SecretKind kind() {
        return kind;
    }

    /** Every spelling of the name. */
    public List<String> keys() {
        return keys;
    }

    /** The pattern finding this secret, its value in a group named {@code q} or {@code v}. */
    public Pattern pattern() {
        return pattern;
    }

    /** Every value of this secret on a line. */
    public List<SecretMatch> find(String text) {
        return pattern()
                .matcher(text)
                .results()
                .map(found -> {
                    String group = found.group("q") != null ? "q" : "v";
                    return new SecretMatch(found.start(group), found.end(group), kind, found.group(group));
                })
                .toList();
    }
}
