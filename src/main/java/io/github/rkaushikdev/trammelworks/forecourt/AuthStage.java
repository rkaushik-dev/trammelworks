package io.github.rkaushikdev.trammelworks.forecourt;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two halves of a pay at the pump sale, as the kernel logs them.
 *
 * <p>Before the pump is released the kernel asks the issuer to hold an amount, the pre-authorisation.
 * When the nozzle goes back it sends the completion for what was actually dispensed, against the
 * same reference. The issuer only ever agreed to the held amount, so a completion above it is
 * money the site may never see and a chargeback it will lose: a pump whose preset failed, or a
 * controller that lost the limit, shows up here first.
 *
 * <p>A line reads {@code preauth pump=3 amount=100.00 ref 551203}, then {@code completion pump=3
 * amount=62.18 ref 551203}. Amounts are kept as written and compared as decimals, never as floating
 * point.
 *
 * @param stage {@code preauth} or {@code completion}
 * @param amount the amount, as written
 * @param reference the transaction reference both halves share
 */
public record AuthStage(String stage, String amount, String reference) {

    /** The label carrying which half of the sale a line is. */
    public static final String LABEL = "auth_stage";

    /** The label carrying the amount. */
    public static final String AMOUNT_LABEL = "amount";

    /** The label carrying the shared reference. */
    public static final String REF_LABEL = "ref";

    /** The first half. */
    public static final String PREAUTH = "preauth";

    /** The second half. */
    public static final String COMPLETION = "completion";

    static final Pattern LINE =
            Pattern.compile("(?i)(?<![A-Za-z])(pre-?auth(?:orisation|orization)?|completion)(?![A-Za-z])"
                    + ".*?\\bamount\\s*[=:]\\s*(\\d{1,7}(?:\\.\\d{1,3})?)(?!\\d)"
                    + ".*?\\bref\\s*[=:#]?\\s*([0-9A-Za-z]{4,20})(?![0-9A-Za-z])");

    /** The amount as a decimal. */
    public BigDecimal value() {
        return new BigDecimal(amount);
    }

    /** The half of a sale a payment line reports, when it names both its amount and reference. */
    public static Optional<AuthStage> read(String message) {
        Matcher match = LINE.matcher(message);
        if (!match.find()) {
            return Optional.empty();
        }
        String stage = match.group(1).toLowerCase(Locale.ROOT).startsWith("pre") ? PREAUTH : COMPLETION;
        return Optional.of(new AuthStage(stage, match.group(2), match.group(3)));
    }
}
