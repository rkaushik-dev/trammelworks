package io.github.rkaushikdev.trammelworks.forecourt;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The payment kernel's store and forward queue.
 *
 * <p>When the host cannot be reached an outdoor terminal keeps selling under its floor limits and
 * holds the approvals it gave itself in a queue, to be sent when the link comes back. Every one of
 * those is money the site has handed over fuel for and not yet been paid. A queue that is still
 * deep the next morning means the link never came back, or the host is refusing the uploads, and
 * either way the site is lending money to strangers.
 *
 * <p>The kernel reports the depth as {@code SAF depth 14}, {@code saf_pending=14} or {@code
 * store and forward queue 14}.
 */
public final class StoreAndForward {

    /** The label carrying the queue depth the kernel last reported. */
    public static final String LABEL = "saf_depth";

    static final Pattern DEPTH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:saf(?:[ _]queue)?[ _]depth|saf_pending|store and forward queue)\\s*[=:]?\\s*(\\d{1,6})(?!\\d)");

    private StoreAndForward() {}

    /** The queue depth a payment line reports. */
    public static Optional<Long> depth(String message) {
        Matcher match = DEPTH.matcher(message);
        return match.find() ? Optional.of(Long.parseLong(match.group(1))) : Optional.empty();
    }
}
