package io.github.rkaushikdev.trammelworks.query;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * A whole filter: what to match and how much to return.
 *
 * @param where the condition, or empty to match everything
 * @param limit the most records to return, or empty for no limit
 */
public record Query(Optional<Node> where, OptionalInt limit) {

    /** A query matching everything, without a limit. */
    public Query() {
        this(Optional.empty(), OptionalInt.empty());
    }

    /** A query with a condition and no limit. */
    public static Query of(Node where) {
        return new Query(Optional.of(where), OptionalInt.empty());
    }

    /** The same, with {@code extra} also required, and no limit: the caller is reading a set, not a page. */
    public Query narrowedTo(Node extra) {
        return new Query(
                Optional.of(where.<Node>map(node -> new And(node, extra)).orElse(extra)), OptionalInt.empty());
    }

    /** The same condition with no limit. */
    public Query unlimited() {
        return new Query(where, OptionalInt.empty());
    }

    /** The filter as text the parser reads back. */
    public String render() {
        StringBuilder out = new StringBuilder();
        where.ifPresent(node -> out.append(node.render()));
        limit.ifPresent(
                value -> out.append(out.isEmpty() ? "" : " ").append("limit ").append(value));
        return out.isEmpty() ? "everything" : out.toString();
    }

    /** Whether there is no condition at all. */
    public boolean matchesEverything() {
        return where.isEmpty();
    }
}
