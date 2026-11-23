package io.github.rkaushikdev.trammelworks.redact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Magnetic stripe data, PIN blocks and the three digit codes.
 *
 * <p>On a forecourt the worse leak is track data, not a PAN. A dispenser reader that fails to parse
 * a swipe often prints the whole stripe, and a track 2 image holds the PAN, the expiry and the
 * service code together, which is enough to clone the card. It must never be stored, not even
 * masked, so unlike a PAN it is replaced wholesale. Track 2 frequently appears without its
 * sentinels, and the PAN part still has to pass the usual tests, which stops an equals sign in a
 * config dump being read as a stripe.
 *
 * <p>PIN blocks, card codes, cardholder names and fleet driver PINs are found by their key name
 * rather than their shape, from the {@link #KEYED} table: sixteen hex characters is also what half
 * the identifiers on the box look like. If a line does not say what the value is, we do not guess.
 */
public final class Stripes {

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    static final Pattern TRACK1 = Pattern.compile("%[A-Z]\\d{12,19}\\^[^^]{0,30}\\^\\d{4}[^?]{0,60}\\??", FLAGS);
    static final Pattern TRACK2_SENTINEL = Pattern.compile(";(\\d{12,19})=(\\d{4}[0-9A-Za-z]{0,50})\\??", FLAGS);
    static final Pattern TRACK2_BARE = Pattern.compile("(?<!\\d)(\\d{12,19})=(\\d{4}\\d{0,50})(?!\\d)", FLAGS);

    /** Every secret found by its key, and the keys a forecourt prints it under. */
    public static final List<KeyedSecret> KEYED = List.of(
            new KeyedSecret(
                    SecretKind.PIN_BLOCK,
                    List.of("pin_block", "pinblock", "pinblk", "encrypted_pin", "encryptedpin"),
                    KeyedSecret.HEX),
            new KeyedSecret(
                    SecretKind.CARD_CODE,
                    List.of("cvv", "cvv2", "cvc", "cvc2", "cid", "cav", "cav2", "csc"),
                    KeyedSecret.HEX),
            new KeyedSecret(
                    SecretKind.CARDHOLDER_NAME,
                    List.of("cardholder", "cardholder_name", "chname", "card_name"),
                    KeyedSecret.NAME),
            // The PIN a fleet driver keys at the dispenser prompt. It is not a card PIN, but it
            // is what stops a stolen fleet card from fuelling a stranger's truck.
            new KeyedSecret(SecretKind.FLEET_PIN, List.of("driver_pin", "fleet_pin", "dpin"), "\\d{4,8}"));

    /** What a removed secret becomes. */
    public static final String REPLACEMENT = "[redacted]";

    private Stripes() {}

    /** Every finder the stripe pass runs, in the order they were written. */
    static final List<Function<String, List<SecretMatch>>> FINDERS =
            List.of(Stripes::findTrack1, Stripes::findTrack2, Stripes::findKeyedSecrets, EmvData::findEmv);

    /** Track 1 images. */
    public static List<SecretMatch> findTrack1(String text) {
        return TRACK1.matcher(text)
                .results()
                .map(match -> new SecretMatch(match.start(), match.end(), SecretKind.TRACK1, match.group()))
                .toList();
    }

    /** Track 2 images, with or without their sentinels. */
    public static List<SecretMatch> findTrack2(String text) {
        List<SecretMatch> found = new ArrayList<>();
        List<int[]> taken = new ArrayList<>();
        Matcher sentinel = TRACK2_SENTINEL.matcher(text);
        while (sentinel.find()) {
            if (!Pans.looksLikePan(Luhn.digitsOnly(sentinel.group(1)))) {
                continue;
            }
            found.add(new SecretMatch(sentinel.start(), sentinel.end(), SecretKind.TRACK2, sentinel.group()));
            taken.add(new int[] {sentinel.start(), sentinel.end()});
        }
        Matcher bare = TRACK2_BARE.matcher(text);
        while (bare.find()) {
            int start = bare.start();
            if (taken.stream().anyMatch(span -> span[0] <= start && start < span[1])) {
                continue;
            }
            if (!Pans.looksLikePan(Luhn.digitsOnly(bare.group(1)))) {
                continue;
            }
            found.add(new SecretMatch(start, bare.end(), SecretKind.TRACK2, bare.group()));
        }
        found.sort(Comparator.comparingInt(SecretMatch::start));
        return found;
    }

    /** Everything in the {@link #KEYED} table, found by the name beside it, in line order. */
    public static List<SecretMatch> findKeyedSecrets(String text) {
        return KEYED.stream()
                .flatMap(secret -> secret.find(text).stream())
                .sorted(Comparator.comparingInt(SecretMatch::start))
                .toList();
    }

    /**
     * Everything that has to go, ordered and non overlapping, the longest winning a tie. EMV dumps
     * are in here rather than beside the PAN scan because a tag 57 value is a track 2 image in all
     * but its separator, and it has to be gone before the PAN scan looks for what is inside it.
     */
    public static List<SecretMatch> findSecrets(String text) {
        List<SecretMatch> everything = new ArrayList<>();
        for (Function<String, List<SecretMatch>> finder : FINDERS) {
            everything.addAll(finder.apply(text));
        }
        everything.sort(
                Comparator.comparingInt(SecretMatch::start).thenComparingInt(item -> -(item.end() - item.start())));
        List<SecretMatch> kept = new ArrayList<>();
        int cursor = -1;
        for (SecretMatch item : everything) {
            if (item.start() < cursor) {
                continue;
            }
            kept.add(item);
            cursor = item.end();
        }
        return kept;
    }

    /** Replace every stripe image, PIN block and card code with the marker. */
    public static String stripSecrets(String text) {
        return stripSecrets(text, REPLACEMENT);
    }

    /** Replace every stripe image, PIN block and card code with {@code replacement}. */
    public static String stripSecrets(String text, String replacement) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (SecretMatch found : findSecrets(text)) {
            out.append(text, cursor, found.start());
            out.append(replacement);
            cursor = found.end();
        }
        out.append(text.substring(cursor));
        return out.toString();
    }
}
