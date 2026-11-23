package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A completion the pre-authorisation does not cover.
 *
 * @param reference the transaction reference
 * @param pump the fuelling point, where the kernel named one
 * @param at when the completion was sent
 * @param authorised what the issuer agreed to hold, empty when no pre-authorisation was seen
 * @param completed what the completion asked for
 */
public record Overrun(
        String reference, String pump, NanoTime at, Optional<BigDecimal> authorised, BigDecimal completed) {

    /** By how much the completion exceeds the hold; the whole completion when there was none. */
    public BigDecimal excess() {
        return completed.subtract(authorised.orElse(BigDecimal.ZERO));
    }

    /**
     * Every completion in the records that is above its pre-authorisation or has none. Replayed in
     * time order; a completion for a reference whose pre-authorisation is older than what was read
     * reports as having none, which the caller narrows by reading a wider window.
     */
    public static List<Overrun> find(List<StoredRecord> records) {
        Map<String, BigDecimal> held = new LinkedHashMap<>();
        List<Overrun> out = new ArrayList<>();
        records.stream().sorted(Comparator.comparing(StoredRecord::bestAt)).forEach(record -> {
            Map<String, String> labels = record.labels();
            String stage = labels.get(AuthStage.LABEL);
            String reference = labels.get(AuthStage.REF_LABEL);
            String amount = labels.get(AuthStage.AMOUNT_LABEL);
            if (stage == null || reference == null || amount == null) {
                return;
            }
            BigDecimal value = new BigDecimal(amount);
            if (AuthStage.PREAUTH.equals(stage)) {
                held.put(reference, value);
                return;
            }
            Optional<BigDecimal> authorised = Optional.ofNullable(held.remove(reference));
            if (authorised.isEmpty() || value.compareTo(authorised.get()) > 0) {
                out.add(new Overrun(
                        reference, labels.getOrDefault(FuellingPoint.LABEL, ""), record.bestAt(), authorised, value));
            }
        });
        return out;
    }
}
