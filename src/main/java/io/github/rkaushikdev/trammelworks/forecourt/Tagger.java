package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.LogRecord;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stamping what a line is about onto it as labels.
 *
 * <p>It runs on the redacted record, after the card data is out, so nothing it reads can carry a
 * card number into a label. It only adds: a label the record already carries is the container's
 * own statement and is never overwritten, and a record with nothing to add comes back as the same
 * object.
 */
public final class Tagger {

    private final ForecourtConfig config;
    private long tagged;

    /** A tagger reading roles from {@code config}. */
    public Tagger(ForecourtConfig config) {
        this.config = config;
    }

    /** The roles it reads by. */
    public ForecourtConfig config() {
        return config;
    }

    /** Records that came out with at least one new label. */
    public long tagged() {
        return tagged;
    }

    /** The labels this record should gain, before anything already on it is considered. */
    public Map<String, String> labelsFor(LogRecord record) {
        Map<String, String> found = new LinkedHashMap<>();
        String message = record.message();
        switch (config.roleOf(record.unit())) {
            case DISPENSER -> dispenser(message, found);
            case PAYMENT -> payment(message, found);
            case TANK_GAUGE -> tankGauge(message, found);
            case PRICE_SIGN -> price(message, found);
            default -> {}
        }
        return found;
    }

    /**
     * The fuelling point and its state. A state is only read off a line that also names its point:
     * "AUTHORISED" on its own could be about anything.
     */
    private static void dispenser(String message, Map<String, String> found) {
        FuellingPoint.find(message).ifPresent(point -> {
            found.put(FuellingPoint.LABEL, point);
            PumpState.find(message).ifPresent(state -> {
                found.put(PumpState.LABEL, state.value());
                if (state == PumpState.COMPLETED) {
                    DeliveredVolume.read(message)
                            .ifPresent(litres -> found.put(DeliveredVolume.LABEL, litres.toPlainString()));
                }
            });
            price(message, found);
        });
    }

    /** The grade and the price a sign or a dispenser line sets. */
    private static void price(String message, Map<String, String> found) {
        FuelPrice.read(message).ifPresent(reading -> {
            found.put(Grade.LABEL, reading.grade().value());
            found.put(FuelPrice.LABEL, reading.written());
        });
    }

    /** The pump the payment is for, what the host said about it, and how deep the offline queue is. */
    private static void payment(String message, Map<String, String> found) {
        StoreAndForward.depth(message).ifPresent(depth -> found.put(StoreAndForward.LABEL, Long.toString(depth)));
        FuellingPoint.find(message).ifPresent(point -> found.put(FuellingPoint.LABEL, point));
        ResponseCode.find(message).ifPresent(code -> {
            found.put(ResponseCode.LABEL, code.code());
            found.put(Outcome.LABEL, code.outcome().value());
        });
        AuthStage.read(message).ifPresent(stage -> {
            found.put(AuthStage.LABEL, stage.stage());
            found.put(AuthStage.AMOUNT_LABEL, stage.amount());
            found.put(AuthStage.REF_LABEL, stage.reference());
        });
    }

    /** The tank, the alarm on it and whether it is starting or ending. */
    private static void tankGauge(String message, Map<String, String> found) {
        TankAlarm.read(message).ifPresent(reading -> {
            found.put(TankAlarm.TANK_LABEL, reading.tank());
            found.put(TankAlarm.LABEL, reading.alarm().value());
            found.put(TankAlarm.STATE_LABEL, reading.state());
            found.put(TankAlarm.SEVERITY_LABEL, reading.alarm().severity().value());
        });
    }

    /** The record with whatever it gained. */
    public LogRecord tag(LogRecord record) {
        Map<String, String> fresh = new LinkedHashMap<>(labelsFor(record));
        fresh.keySet().removeAll(record.labels().keySet());
        if (fresh.isEmpty()) {
            return record;
        }
        tagged++;
        return record.withLabels(fresh);
    }
}
