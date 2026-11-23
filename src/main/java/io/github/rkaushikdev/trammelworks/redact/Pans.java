package io.github.rkaushikdev.trammelworks.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding card numbers in a line of log output.
 *
 * <p>Three tests have to pass together before a run of digits is treated as a PAN, and each one on
 * its own is useless: a length of 13 to 19 digits, the Luhn check, and an issuer range we
 * recognise. A forecourt controller prints a lot of long numbers (transaction references, EMV tags,
 * tank volumes in millilitres), and fuel cards are why the range table is not the usual four
 * brands: an unrecognised fleet PAN ships off site in the clear.
 *
 * <p>The scan keeps spans, because the replacement has to go back exactly where the digits were.
 * Single separators inside a group are allowed, because terminals print {@code 4111 1111 1111
 * 1111}.
 */
public final class Pans {

    /**
     * Issuer ranges seen across the estate, fuel and fleet ranges included. Each entry is a range
     * of leading digits, written {@code low-high} or as a single prefix.
     */
    public static final List<IssuerRange> ISSUER_RANGES = List.of(
            IssuerRange.of("visa", "4"),
            IssuerRange.of("mastercard", "51-55"),
            // The 2-series is 2221 to 2720 and nothing either side of it. A plain "22" would put
            // every Mir card in the Mastercard column.
            IssuerRange.of("mastercard", "2221-2720"),
            IssuerRange.of("mir", "2200-2204"),
            IssuerRange.of("amex", "34"),
            IssuerRange.of("amex", "37"),
            IssuerRange.of("discover", "6011"),
            IssuerRange.of("discover", "644-649"),
            IssuerRange.of("discover", "65"),
            IssuerRange.of("discover", "622126-622925"),
            IssuerRange.of("unionpay", "62"),
            IssuerRange.of("diners", "300-305"),
            IssuerRange.of("diners", "36"),
            IssuerRange.of("diners", "38"),
            IssuerRange.of("jcb", "3528-3589"),
            IssuerRange.of("maestro", "50"),
            IssuerRange.of("maestro", "56-58"),
            IssuerRange.of("maestro", "6759"),
            IssuerRange.of("maestro", "6761-6763"),
            // Fleet and fuel. 7-prefixed ranges are private label cards issued by the fuel brands
            // themselves and they are the bulk of forecourt traffic.
            IssuerRange.of("fleet", "7"),
            IssuerRange.of("fleet", "708"),
            IssuerRange.of("fleet", "70251"),
            IssuerRange.of("fleet", "70256"),
            IssuerRange.of("wex", "690046"),
            IssuerRange.of("wex", "707138"),
            IssuerRange.of("voyager", "8699"));

    static final Pattern CANDIDATE = Pattern.compile("\\d(?:[ -]?\\d){11,22}", Pattern.UNICODE_CHARACTER_CLASS);

    /** What a hidden digit becomes. */
    public static final char MASK_CHARACTER = '*';

    /** Leading digits the card schemes allow to be stored. */
    public static final int KEEP_FIRST = 6;

    /** Trailing digits the card schemes allow to be stored. */
    public static final int KEEP_LAST = 4;

    /**
     * A mask that hides only a digit or two is not a mask. If keeping the head and tail would leave
     * fewer than this many hidden, everything goes instead.
     */
    public static final int MIN_MASKED = 4;

    private Pans() {}

    /** The issuer whose range this number falls in, the most specific range winning. */
    public static Optional<String> brandOf(String digits) {
        return issuerOf(digits).map(IssuerRange::brand);
    }

    /**
     * The range this number falls in. The longest prefix wins, and between two of the same length
     * the narrower range wins, so a co-badged block carved out of a wider one is named for the
     * scheme that carved it.
     */
    public static Optional<IssuerRange> issuerOf(String digits) {
        IssuerRange best = null;
        for (IssuerRange range : ISSUER_RANGES) {
            if (range.covers(digits) && (best == null || range.moreSpecificThan(best))) {
                best = range;
            }
        }
        return Optional.ofNullable(best);
    }

    /** All three tests, in the order that rejects fastest. */
    public static boolean looksLikePan(String digits) {
        return Luhn.plausibleLength(digits)
                && Luhn.luhnValid(digits)
                && brandOf(digits).isPresent();
    }

    /** Every card number in a line, left to right and non overlapping. */
    public static List<PanMatch> findPans(String text) {
        List<PanMatch> found = new ArrayList<>();
        Matcher match = CANDIDATE.matcher(text);
        while (match.find()) {
            String raw = match.group();
            String digits = Luhn.digitsOnly(raw);
            if (looksLikePan(digits)) {
                found.add(new PanMatch(
                        match.start(), match.end(), raw, digits, brandOf(digits).orElse("unknown")));
                continue;
            }
            trimToPan(raw, match.start()).ifPresent(found::add);
        }
        return found;
    }

    /**
     * Try shorter prefixes of an over long run. A terminal that prints a PAN immediately followed by
     * an expiry gives one run of twenty digits; the whole run fails but its first sixteen are a card.
     */
    private static Optional<PanMatch> trimToPan(String raw, int offset) {
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < raw.length(); index++) {
            if (Character.isDigit(raw.charAt(index))) {
                positions.add(index);
            }
        }
        for (int count = positions.size(); count > 12; count--) {
            int end = positions.get(count - 1) + 1;
            String candidate = raw.substring(0, end);
            String digits = Luhn.digitsOnly(candidate);
            if (looksLikePan(digits)) {
                return Optional.of(new PanMatch(
                        offset, offset + end, candidate, digits, brandOf(digits).orElse("unknown")));
            }
        }
        return Optional.empty();
    }

    /** Mask a PAN, keeping the six and four digits the card schemes allow to be stored. */
    public static String maskDigits(String digits) {
        return maskDigits(digits, KEEP_FIRST, KEEP_LAST);
    }

    /** Mask a PAN, keeping the given numbers of leading and trailing digits. */
    public static String maskDigits(String digits, int keepFirst, int keepLast) {
        if (keepFirst < 0 || keepLast < 0) {
            throw new IllegalArgumentException("cannot keep a negative number of digits");
        }
        int hidden = digits.length() - keepFirst - keepLast;
        if (hidden < MIN_MASKED) {
            return String.valueOf(MASK_CHARACTER).repeat(digits.length());
        }
        String head = digits.substring(0, keepFirst);
        String tail = keepLast > 0 ? digits.substring(digits.length() - keepLast) : "";
        return head + String.valueOf(MASK_CHARACTER).repeat(hidden) + tail;
    }

    /** Replace every card number in a line with its masked form. */
    public static String maskPans(String text) {
        return maskPans(text, KEEP_FIRST, KEEP_LAST);
    }

    /** Replace every card number in a line, keeping the given digits where the length allows. */
    public static String maskPans(String text, int keepFirst, int keepLast) {
        return maskPans(text, new PanMask(keepFirst, keepLast));
    }

    /** Replace every card number in a line with what {@code mask} leaves of it. */
    public static String maskPans(String text, PanMask mask) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (PanMatch found : findPans(text)) {
            out.append(text, cursor, found.start());
            out.append(mask.apply(found.digits()));
            cursor = found.end();
        }
        out.append(text.substring(cursor));
        return out.toString();
    }
}
