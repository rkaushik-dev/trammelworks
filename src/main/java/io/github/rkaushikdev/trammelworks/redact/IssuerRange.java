package io.github.rkaushikdev.trammelworks.redact;

import io.github.rkaushikdev.trammelworks.ConfigError;

/**
 * One block of issuer identification numbers and the scheme that owns it.
 *
 * <p>Schemes hand out their space as ranges of leading digits, not as prefixes: the Mastercard
 * 2-series is 2221 to 2720, JCB is 3528 to 3589. Written as prefixes those become "22" to "27" and
 * "35", which name cards from other schemes as Mastercard and JCB and, worse, make the table look
 * finished. A range is compared on the same number of leading digits as its bounds.
 *
 * @param brand the scheme the audit and the PAN match name
 * @param low the lowest leading digits in the block
 * @param high the highest, the same width as {@code low}
 */
public record IssuerRange(String brand, String low, String high) {

    /** Refuses bounds that are not digits, differ in width, or run backwards. */
    public IssuerRange {
        if (brand == null || brand.isEmpty()) {
            throw new ConfigError("an issuer range needs a brand");
        }
        if (!Luhn.isDigits(low) || !Luhn.isDigits(high)) {
            throw new ConfigError("an issuer range is written in digits", low + "-" + high);
        }
        if (low.length() != high.length()) {
            throw new ConfigError("both ends of an issuer range need the same width", low + "-" + high);
        }
        if (low.compareTo(high) > 0) {
            throw new ConfigError("an issuer range runs low to high", low + "-" + high);
        }
    }

    /** A range written {@code low-high}, or a single prefix. */
    public static IssuerRange of(String brand, String written) {
        int dash = written.indexOf('-');
        if (dash < 0) {
            return new IssuerRange(brand, written, written);
        }
        return new IssuerRange(brand, written.substring(0, dash), written.substring(dash + 1));
    }

    /** How many leading digits the range is decided on. */
    public int width() {
        return low.length();
    }

    /** How many distinct leading digit strings the range holds. */
    public long size() {
        return Long.parseLong(high) - Long.parseLong(low) + 1;
    }

    /** Whether a number's leading digits fall inside the range. */
    public boolean covers(String digits) {
        if (digits.length() < width()) {
            return false;
        }
        String head = digits.substring(0, width());
        return low.compareTo(head) <= 0 && head.compareTo(high) <= 0;
    }

    /** Longer bounds first, then the narrower block. */
    public boolean moreSpecificThan(IssuerRange other) {
        if (width() != other.width()) {
            return width() > other.width();
        }
        return size() < other.size();
    }

    @Override
    public String toString() {
        return low.equals(high) ? brand + " " + low : brand + " " + low + "-" + high;
    }
}
