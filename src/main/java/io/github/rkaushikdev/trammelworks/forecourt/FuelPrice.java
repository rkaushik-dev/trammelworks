package io.github.rkaushikdev.trammelworks.forecourt;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A price per unit volume for one grade, as a sign or a dispenser reports it.
 *
 * <p>Sign drivers send a price as whole thousandths with no decimal point, {@code price unleaded
 * 1459} for 1.459, because that is what the digits on the sign are, the nine tenths included.
 * Dispensers write the decimal. Both are read into the same decimal so they can be compared; a
 * price is never held in floating point.
 *
 * @param grade the grade
 * @param price the price per unit, to three places
 */
public record FuelPrice(Grade grade, BigDecimal price) {

    /** The label a price is carried under. */
    public static final String LABEL = "price";

    /** Prices are compared to a tenth of a cent, which is what a sign shows. */
    public static final int SCALE = 3;

    static final Pattern LINE = Pattern.compile("(?i)(?<![A-Za-z])price(?![A-Za-z]).*?(?<![A-Za-z])("
            + Arrays.stream(Grade.values())
                    .flatMap(grade -> grade.spellings().stream())
                    .map(Pattern::quote)
                    .collect(Collectors.joining("|"))
            + ")(?![A-Za-z])\\s*[=:]?\\s*(\\d{1,2}\\.\\d{1,3}|\\d{3,5})(?![\\d.])");

    /** Normalizes the price to three places. */
    public FuelPrice {
        price = price.setScale(SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    /** A price as written: with a decimal point it is the price, without it is in thousandths. */
    public static BigDecimal parsePrice(String written) {
        return written.contains(".") ? new BigDecimal(written) : new BigDecimal(written).movePointLeft(SCALE);
    }

    /** The price a line sets, when it says price, names a grade and gives a number after it. */
    public static Optional<FuelPrice> read(String message) {
        Matcher match = LINE.matcher(message);
        if (!match.find()) {
            return Optional.empty();
        }
        String word = match.group(1).toLowerCase(Locale.ROOT);
        Grade grade = Arrays.stream(Grade.values())
                .filter(item -> item.spellings().contains(word))
                .findFirst()
                .orElseThrow();
        return Optional.of(new FuelPrice(grade, parsePrice(match.group(2))));
    }

    /** The price as a label value. */
    public String written() {
        return price.toPlainString();
    }
}
