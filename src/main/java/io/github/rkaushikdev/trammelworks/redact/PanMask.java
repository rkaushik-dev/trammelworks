package io.github.rkaushikdev.trammelworks.redact;

import io.github.rkaushikdev.trammelworks.ConfigError;

/**
 * How much of a card number survives the mask.
 *
 * <p>The card schemes allow the first six and last four digits of a PAN to be stored. With eight
 * digit BINs the PCI council allows the first <b>eight</b>, but only on a PAN of sixteen digits or
 * more: on a fifteen digit Amex, eight and four would leave three digits hidden, which is a hint
 * and not a mask. So the leading digits kept depend on the length of the number in front of us,
 * and a site that asks for eight gets six on anything shorter than sixteen.
 *
 * @param keepFirst leading digits kept on a PAN of {@link #LONG_PAN_DIGITS} or more
 * @param keepLast trailing digits kept on any PAN
 */
public record PanMask(int keepFirst, int keepLast) {

    /** The most leading digits any PAN may keep. */
    public static final int MAX_KEEP_FIRST = 8;

    /** The most leading digits a PAN shorter than {@link #LONG_PAN_DIGITS} may keep. */
    public static final int SHORT_PAN_KEEP_FIRST = 6;

    /** The most trailing digits any PAN may keep. */
    public static final int MAX_KEEP_LAST = 4;

    /** The shortest PAN an eight digit head is allowed on. */
    public static final int LONG_PAN_DIGITS = 16;

    /** Six and four, which every scheme accepts. */
    public static final PanMask STANDARD = new PanMask(SHORT_PAN_KEEP_FIRST, MAX_KEEP_LAST);

    /** Refuses a negative keep, or more than the schemes allow. */
    public PanMask {
        if (keepFirst < 0 || keepLast < 0) {
            throw new ConfigError("cannot keep a negative number of digits");
        }
        if (keepFirst > MAX_KEEP_FIRST) {
            throw new ConfigError("keeping more than eight leading digits is not allowed", Integer.toString(keepFirst));
        }
        if (keepLast > MAX_KEEP_LAST) {
            throw new ConfigError("keeping more than four trailing digits is not allowed", Integer.toString(keepLast));
        }
    }

    /** The leading digits kept on a PAN of this many digits. */
    public int headFor(int digits) {
        return digits >= LONG_PAN_DIGITS ? keepFirst : Math.min(keepFirst, SHORT_PAN_KEEP_FIRST);
    }

    /** Whether this mask keeps more than six on a long PAN. */
    public boolean usesLongBin() {
        return keepFirst > SHORT_PAN_KEEP_FIRST;
    }

    /** Mask one PAN's digits. */
    public String apply(String digits) {
        return Pans.maskDigits(digits, headFor(digits.length()), keepLast);
    }

    @Override
    public String toString() {
        String shape = keepFirst + "+" + keepLast;
        return usesLongBin()
                ? shape + " (" + SHORT_PAN_KEEP_FIRST + "+" + keepLast + " under " + LONG_PAN_DIGITS + " digits)"
                : shape;
    }
}
