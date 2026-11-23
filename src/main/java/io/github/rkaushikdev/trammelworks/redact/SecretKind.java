package io.github.rkaushikdev.trammelworks.redact;

import io.github.rkaushikdev.trammelworks.Json;

/** What kind of secret was found, which is what the audit trail counts. */
public enum SecretKind implements Json.Valued {
    /** A track 1 stripe image, which carries the cardholder name too. */
    TRACK1("track1"),
    /** A track 2 stripe image: PAN, expiry and service code together. */
    TRACK2("track2"),
    /** A PIN block, found by its key. */
    PIN_BLOCK("pin_block"),
    /** A card verification code, found by its key. */
    CARD_CODE("card_code"),
    /** The value of a cardholder data tag inside an EMV TLV dump. */
    EMV_TAG("emv_tag");

    private final String value;

    SecretKind(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
