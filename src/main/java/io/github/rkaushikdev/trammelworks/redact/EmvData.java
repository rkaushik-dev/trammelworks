package io.github.rkaushikdev.trammelworks.redact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Card data inside the BER-TLV hex dumps an EMV kernel prints.
 *
 * <p>When a chip read or a contactless tap goes wrong, the payment kernel logs the ICC data it got
 * as one long hex string, and that string is a sequence of tag, length and value. The PAN in tag
 * {@code 5A} is packed two digits to a byte with an {@code F} pad, so the digit scan sees
 * {@code 0841111111111111115F} and finds nothing a card scheme issued; the track 2 equivalent in tag
 * {@code 57} uses {@code D} as its separator where a stripe uses {@code =}. Neither is caught by
 * looking for card numbers, so the dump is parsed for what it is and the values of the tags that
 * carry cardholder or authentication data are removed, leaving the tags and lengths so the dump is
 * still readable to the engineer chasing the fault.
 *
 * <p>A hex run counts as a dump only when the whole of it parses as TLV and at least one of those
 * tags is in it. A boot id or a transaction hash does not parse, and is left alone.
 */
public final class EmvData {

    /** The tags whose values never leave the site, and what each one is. */
    public static final Map<String, String> SENSITIVE_TAGS = sensitiveTags();

    static final Pattern HEX_RUN =
            Pattern.compile("(?<![0-9A-Za-z])[0-9A-Fa-f]{2}(?: ?[0-9A-Fa-f]{2}){3,}(?![0-9A-Za-z])");

    /** The most bytes a multi byte tag may run to after its first. */
    static final int MAX_TAG_FOLLOWERS = 3;

    private EmvData() {}

    private static Map<String, String> sensitiveTags() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("56", "track 1 equivalent data");
        out.put("57", "track 2 equivalent data");
        out.put("5A", "application PAN");
        out.put("5F20", "cardholder name");
        out.put("9F1F", "track 1 discretionary data");
        out.put("9F20", "track 2 discretionary data");
        out.put("9F6B", "track 2 data");
        out.put("99", "transaction PIN data");
        return Collections.unmodifiableMap(out);
    }

    /**
     * One primitive element of a dump.
     *
     * @param tag the tag, upper case hex
     * @param valueStart where its value starts, in hex digits from the start of the dump
     * @param valueEnd just past its value
     */
    public record Element(String tag, int valueStart, int valueEnd) {

        /** Whether this element's value must be removed. */
        public boolean sensitive() {
            return SENSITIVE_TAGS.containsKey(tag);
        }
    }

    /**
     * Every primitive element in a dump, constructed templates opened up, or empty when the hex
     * is not TLV from end to end.
     */
    public static Optional<List<Element>> parse(String hex) {
        String upper = hex.toUpperCase(Locale.ROOT);
        if (upper.length() % 2 != 0 || !upper.chars().allMatch(EmvData::isHex)) {
            return Optional.empty();
        }
        List<Element> out = new ArrayList<>();
        return sequence(upper, 0, upper.length(), out) ? Optional.of(List.copyOf(out)) : Optional.empty();
    }

    private static boolean isHex(int character) {
        return ('0' <= character && character <= '9') || ('A' <= character && character <= 'F');
    }

    private static int octet(String hex, int at) {
        return Integer.parseInt(hex.substring(at, at + 2), 16);
    }

    private static boolean sequence(String hex, int from, int to, List<Element> out) {
        int at = from;
        while (at < to) {
            int first = octet(hex, at);
            if (first == 0x00 || first == 0xFF) {
                // Padding between elements, which kernels write to fill a record.
                at += 2;
                continue;
            }
            int tagEnd = at + 2;
            if ((first & 0x1F) == 0x1F) {
                int followers = 0;
                int next;
                do {
                    if (tagEnd >= to || ++followers > MAX_TAG_FOLLOWERS) {
                        return false;
                    }
                    next = octet(hex, tagEnd);
                    tagEnd += 2;
                } while ((next & 0x80) != 0);
            }
            if (tagEnd >= to) {
                return false;
            }
            int lengthByte = octet(hex, tagEnd);
            int valueStart = tagEnd + 2;
            int length;
            if (lengthByte < 0x80) {
                length = lengthByte;
            } else if (lengthByte == 0x81 && valueStart + 2 <= to) {
                length = octet(hex, valueStart);
                valueStart += 2;
            } else if (lengthByte == 0x82 && valueStart + 4 <= to) {
                length = octet(hex, valueStart) * 256 + octet(hex, valueStart + 2);
                valueStart += 4;
            } else {
                return false;
            }
            int valueEnd = valueStart + 2 * length;
            if (valueEnd > to) {
                return false;
            }
            String tag = hex.substring(at, tagEnd);
            boolean constructed = (first & 0x20) != 0;
            if (constructed) {
                if (!sequence(hex, valueStart, valueEnd, out)) {
                    return false;
                }
            } else {
                out.add(new Element(tag, valueStart, valueEnd));
            }
            at = valueEnd;
        }
        return true;
    }

    /** The value of every sensitive tag in every dump on a line, as spans of the line. */
    public static List<SecretMatch> findEmv(String text) {
        List<SecretMatch> found = new ArrayList<>();
        Matcher run = HEX_RUN.matcher(text);
        while (run.find()) {
            List<Integer> positions = new ArrayList<>();
            StringBuilder hex = new StringBuilder();
            for (int index = run.start(); index < run.end(); index++) {
                if (text.charAt(index) != ' ') {
                    positions.add(index);
                    hex.append(text.charAt(index));
                }
            }
            Optional<List<Element>> parsed = parse(hex.toString());
            if (parsed.isEmpty()) {
                continue;
            }
            for (Element element : parsed.get()) {
                if (!element.sensitive() || element.valueEnd() == element.valueStart()) {
                    continue;
                }
                int start = positions.get(element.valueStart());
                int end = positions.get(element.valueEnd() - 1) + 1;
                found.add(new SecretMatch(start, end, SecretKind.EMV_TAG, text.substring(start, end)));
            }
        }
        return found;
    }
}
