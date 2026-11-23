package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.Json;
import java.util.Optional;

/** What a payment host's answer means for the sale at the pump. */
public enum Outcome implements Json.Valued {
    /** The sale may go ahead, possibly for less than was asked. */
    APPROVED("approved"),
    /** The issuer said no. */
    DECLINED("declined"),
    /** The issuer wants a voice call before it will say yes, which nobody at a pump can make. */
    REFERRAL("referral"),
    /** The issuer wants the card kept, which an outdoor terminal cannot do either. */
    PICK_UP("pick_up"),
    /** Nobody answered: the issuer, the switch or the host is down. */
    HOST_UNAVAILABLE("host_unavailable");

    /** The label an outcome is carried under. */
    public static final String LABEL = "outcome";

    private final String value;

    Outcome(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    /** The outcome with this label value. */
    public static Optional<Outcome> fromValue(String raw) {
        return Json.Valued.lookup(Outcome.class, raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
