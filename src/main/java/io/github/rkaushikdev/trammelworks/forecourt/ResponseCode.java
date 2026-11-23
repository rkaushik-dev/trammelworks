package io.github.rkaushikdev.trammelworks.forecourt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ISO 8583 response code, field 39, as the payment kernel logs it.
 *
 * <p>The table is the codes a forecourt actually sees, not the whole standard. Two families need
 * saying out loud: {@code 10} is a partial approval, which is an approval (a prepaid card covering
 * part of a fill), and the {@code Y} and {@code Z} codes are the ones the chip card decides on its
 * own when the terminal could not reach the host, so {@code Z3} is a decline that never left the
 * site. A code the table does not know is read as a decline, because that is what the terminal did
 * with it.
 *
 * @param code the two characters, upper case
 * @param meaning what the scheme manuals call it
 * @param outcome what it means at the pump
 */
public record ResponseCode(String code, String meaning, Outcome outcome) {

    /** The label a response code is carried under. */
    public static final String LABEL = "response";

    /** The codes this build knows. */
    public static final Map<String, ResponseCode> KNOWN = table(List.of(
            new ResponseCode("00", "approved", Outcome.APPROVED),
            new ResponseCode("01", "refer to card issuer", Outcome.REFERRAL),
            new ResponseCode("02", "refer to card issuer, special condition", Outcome.REFERRAL),
            new ResponseCode("03", "invalid merchant", Outcome.DECLINED),
            new ResponseCode("04", "pick up card", Outcome.PICK_UP),
            new ResponseCode("05", "do not honour", Outcome.DECLINED),
            new ResponseCode("08", "honour with identification", Outcome.APPROVED),
            new ResponseCode("10", "partial approval", Outcome.APPROVED),
            new ResponseCode("12", "invalid transaction", Outcome.DECLINED),
            new ResponseCode("13", "invalid amount", Outcome.DECLINED),
            new ResponseCode("14", "invalid card number", Outcome.DECLINED),
            new ResponseCode("41", "lost card, pick up", Outcome.PICK_UP),
            new ResponseCode("43", "stolen card, pick up", Outcome.PICK_UP),
            new ResponseCode("51", "insufficient funds", Outcome.DECLINED),
            new ResponseCode("54", "expired card", Outcome.DECLINED),
            new ResponseCode("55", "incorrect PIN", Outcome.DECLINED),
            new ResponseCode("57", "not permitted to cardholder", Outcome.DECLINED),
            new ResponseCode("58", "not permitted to terminal", Outcome.DECLINED),
            new ResponseCode("61", "exceeds amount limit", Outcome.DECLINED),
            new ResponseCode("62", "restricted card", Outcome.DECLINED),
            new ResponseCode("65", "exceeds frequency limit", Outcome.DECLINED),
            new ResponseCode("75", "PIN tries exceeded", Outcome.DECLINED),
            new ResponseCode("91", "issuer or switch inoperative", Outcome.HOST_UNAVAILABLE),
            new ResponseCode("92", "routing not found", Outcome.HOST_UNAVAILABLE),
            new ResponseCode("96", "system malfunction", Outcome.HOST_UNAVAILABLE),
            new ResponseCode("Y1", "offline approved by the card", Outcome.APPROVED),
            new ResponseCode("Z1", "offline declined by the card", Outcome.DECLINED),
            new ResponseCode("Y3", "approved by the card, host unreachable", Outcome.APPROVED),
            new ResponseCode("Z3", "declined by the card, host unreachable", Outcome.DECLINED)));

    static final Pattern MENTION = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:rc|resp|response_code|respcode|de39|field39)\\s*[=:]\\s*\"?([0-9A-Z]{2})(?![0-9A-Za-z])");

    private static Map<String, ResponseCode> table(List<ResponseCode> codes) {
        Map<String, ResponseCode> out = new LinkedHashMap<>();
        codes.forEach(code -> out.put(code.code(), code));
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The code as this build reads it, an unknown one counting as a decline. */
    public static ResponseCode of(String raw) {
        String code = raw.toUpperCase(Locale.ROOT);
        ResponseCode known = KNOWN.get(code);
        return known != null ? known : new ResponseCode(code, "not in the table", Outcome.DECLINED);
    }

    /** The response code a payment line reports. */
    public static Optional<ResponseCode> find(String message) {
        Matcher match = MENTION.matcher(message);
        return match.find() ? Optional.of(of(match.group(1))) : Optional.empty();
    }

    /** Whether this build knows the code. */
    public boolean known() {
        return KNOWN.containsKey(code);
    }
}
