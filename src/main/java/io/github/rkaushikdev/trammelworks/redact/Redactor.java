package io.github.rkaushikdev.trammelworks.redact;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.RedactionError;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The redaction pass: the fixed card rules, then the site's own.
 *
 * <p>The order is not a preference. Stripes go first because a track 2 image contains a PAN, and
 * masking the PAN inside it would leave the expiry and service code beside a masked number, which
 * reads as safe and is not. Then PANs, then the site rules, which therefore see text with the card
 * data already out of it.
 *
 * <p><b>This pass fails closed.</b> If anything in it fails, the record is refused rather than
 * shipped half cleaned. A record that skipped redaction because of a bug is a cardholder data leak.
 */
public final class Redactor {

    /** The label a changed record carries, holding how many things went. */
    public static final String REDACTED_LABEL = "redacted";

    /** The audit name card numbers are counted under. */
    public static final String CARD_RULE = "card_pan";

    /** The audit name stripes, PIN blocks and card codes are counted under. */
    public static final String STRIPE_RULE = "card_stripe";

    private final List<Rule> rules;
    private final PanMask mask;
    private final RedactionAudit audit = new RedactionAudit();

    /** The card rules only, keeping six and four. */
    public Redactor() {
        this(List.of());
    }

    /** The card rules and the site's own, keeping six and four. */
    public Redactor(List<Rule> rules) {
        this(rules, PanMask.STANDARD);
    }

    /** The card rules and the site's own, keeping the given digits where the length allows. */
    public Redactor(List<Rule> rules, int keepFirst, int keepLast) {
        this(rules, new PanMask(keepFirst, keepLast));
    }

    /** Everything given. */
    public Redactor(List<Rule> rules, PanMask mask) {
        this.rules = List.copyOf(rules);
        this.mask = mask;
    }

    /** A pass built from a site's redact section. */
    public static Redactor of(RedactConfig config) {
        return new Redactor(config.rules(), config.mask());
    }

    /** What is left of a card number this pass masks. */
    public PanMask mask() {
        return mask;
    }

    /** What this pass has removed so far. */
    public RedactionAudit audit() {
        return audit;
    }

    /** Run the whole pass over one line. */
    public RedactionResult redactText(String text) {
        int stripes;
        int pans;
        String working;
        List<RuleHit> hits = new ArrayList<>();
        try {
            stripes = Stripes.findSecrets(text).size();
            working = Stripes.stripSecrets(text);
            pans = Pans.findPans(working).size();
            working = Pans.maskPans(working, mask);
            for (Rule rule : rules) {
                Rules.Applied applied = Rules.applyRule(rule, working);
                working = applied.text();
                hits.addAll(applied.hits());
            }
        } catch (RuntimeException | StackOverflowError exc) {
            audit.countRefused();
            throw new RedactionError(
                    "redaction failed, holding the record", Objects.toString(exc.getMessage(), exc.toString()), exc);
        }
        audit.note(STRIPE_RULE, stripes);
        audit.note(CARD_RULE, pans);
        for (RuleHit hit : hits) {
            audit.note(hit.rule());
        }
        return new RedactionResult(working, hits, stripes, pans);
    }

    /** Redact a record's message, labelling it when anything went. */
    public LogRecord redact(LogRecord record) {
        audit.countRecord();
        RedactionResult found = redactText(record.message());
        if (!found.changed()) {
            return record;
        }
        audit.countChanged();
        String count =
                Integer.toString(found.stripes() + found.pans() + found.hits().size());
        return record.withMessage(found.text()).withLabel(REDACTED_LABEL, count);
    }

    /** Redact a batch, keeping its order. */
    public List<LogRecord> redactAll(List<LogRecord> records) {
        return records.stream().map(this::redact).toList();
    }
}
