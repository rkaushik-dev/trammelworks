package io.github.rkaushikdev.trammelworks.forecourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ForecourtPumpTest {

    static final NanoTime NOON = NanoTime.parse("2025-03-04T12:00:00Z");

    static NanoTime at(int seconds) {
        return NOON.shifted(seconds * NanoTime.NS_PER_SECOND);
    }

    static StoredRecord stored(int seconds, Map<String, String> labels) {
        return ForecourtRecords.labelled("pump-bridge", at(seconds), labels);
    }

    @ParameterizedTest
    @CsvSource({
        "'FP03 IDLE', idle",
        "'FP03 nozzle lifted grade 2', calling",
        "'FP03 AUTHORIZED limit 50.00', authorised",
        "'fp=3 state=Fueling', fuelling",
        "'FP03 FINISHED volume 31.20', completed",
        "'FP03 end of delivery', completed",
        "'FP03 PAUSED by cashier', suspended",
        "'FP03 offline', inoperative"
    })
    void aStateIsReadInEveryDialect(String line, String state) {
        assertThat(PumpState.find(line).map(PumpState::value)).contains(state);
    }

    @Test
    void aStateWordInsideAnotherWordIsNotAState() {
        assertThat(PumpState.find("FP03 unidled reclosed")).isEmpty();
    }

    @Test
    void aStateIsFoundByItsLabelValue() {
        assertThat(PumpState.fromValue("fuelling")).contains(PumpState.FUELLING);
        assertThat(PumpState.fromValue("FUELLING")).isEmpty();
        assertThat(PumpState.AUTHORISED.spellings()).contains("authorized");
        assertThat(PumpState.COMPLETED).hasToString("completed");
    }

    @Test
    void aDispenserLineGainsItsState() {
        LogRecord line = new LogRecord("US-TX-0417", "pump-bridge", Stream.STDOUT, NOON, "FP03 AUTHORISED grade 2");
        assertThat(new Tagger(new ForecourtConfig()).tag(line).labels())
                .containsEntry("pump", "3")
                .containsEntry("pump_state", "authorised");
    }

    @Test
    void aStateWithNoFuellingPointIsNotRead() {
        LogRecord line = new LogRecord("US-TX-0417", "pump-bridge", Stream.STDOUT, NOON, "controller AUTHORISED");
        assertThat(new Tagger(new ForecourtConfig()).labelsFor(line)).isEmpty();
    }

    @Test
    void aPaymentLineDoesNotGainAPumpState() {
        LogRecord line = new LogRecord("US-TX-0417", "payment-kernel", Stream.STDOUT, NOON, "pump 3 authorised");
        assertThat(new Tagger(new ForecourtConfig()).labelsFor(line)).containsOnlyKeys("pump");
    }

    @Test
    void anOrdinarySaleRaisesNothing() {
        PumpTimeline timeline = new PumpTimeline();
        for (PumpState state : List.of(
                PumpState.IDLE,
                PumpState.CALLING,
                PumpState.AUTHORISED,
                PumpState.STARTED,
                PumpState.FUELLING,
                PumpState.SUSPENDED,
                PumpState.FUELLING,
                PumpState.COMPLETED,
                PumpState.IDLE)) {
            timeline.feed("3", NOON, state);
        }
        assertThat(timeline.events()).isEmpty();
        assertThat(timeline.states()).containsEntry("3", PumpState.IDLE);
    }

    @Test
    void fuellingStraightFromCallingIsAnUnauthorisedDelivery() {
        PumpTimeline timeline = new PumpTimeline();
        timeline.feed("4", at(0), PumpState.CALLING);
        timeline.feed("4", at(5), PumpState.FUELLING);
        assertThat(timeline.unauthorised())
                .containsExactly(new PumpEvent(
                        "4", at(5), PumpEvent.Kind.UNAUTHORISED_DELIVERY, PumpState.CALLING, PumpState.FUELLING));
        assertThat(timeline.unauthorised().get(0).toString())
                .isEqualTo("pump 4 unauthorised_delivery at 2025-03-04T12:00:05.000000000Z (calling to fuelling)");
    }

    @Test
    void theFirstStateSeenIsTakenAsGiven() {
        PumpTimeline timeline = new PumpTimeline();
        timeline.feed("4", at(0), PumpState.FUELLING);
        assertThat(timeline.events()).isEmpty();
    }

    @Test
    void anAuthorisationThatEndsWithNoDeliveryLapses() {
        PumpTimeline timeline = new PumpTimeline();
        timeline.feed("2", at(0), PumpState.AUTHORISED);
        timeline.feed("2", at(90), PumpState.IDLE);
        assertThat(timeline.events()).extracting(PumpEvent::kind).containsExactly(PumpEvent.Kind.AUTHORISATION_LAPSED);
    }

    @Test
    void aFaultIsReportedOnceUntilThePointRecovers() {
        PumpTimeline timeline = new PumpTimeline();
        timeline.feed("2", at(0), PumpState.INOPERATIVE);
        timeline.feed("2", at(1), PumpState.INOPERATIVE);
        timeline.feed("2", at(2), PumpState.IDLE);
        timeline.feed("2", at(3), PumpState.INOPERATIVE);
        assertThat(timeline.events()).hasSize(2).allMatch(event -> event.kind() == PumpEvent.Kind.INOPERATIVE);
        assertThat(timeline.events().get(0).toString()).contains("(unknown to inoperative)");
    }

    @Test
    void pointsAreReplayedSeparately() {
        PumpTimeline timeline = new PumpTimeline();
        timeline.feed("1", at(0), PumpState.AUTHORISED);
        timeline.feed("2", at(1), PumpState.CALLING);
        timeline.feed("1", at(2), PumpState.FUELLING);
        assertThat(timeline.events()).isEmpty();
    }

    @Test
    void archivedRecordsAreReplayedInTimeOrder() {
        List<StoredRecord> records = List.of(
                stored(10, Map.of("pump", "5", "pump_state", "fuelling")),
                stored(0, Map.of("pump", "5", "pump_state", "authorised")),
                stored(5, Map.of("pump", "5")),
                stored(6, Map.of("pump_state", "idle")),
                stored(7, Map.of("pump", "5", "pump_state", "bogus")));
        PumpTimeline timeline = PumpTimeline.replay(records);
        assertThat(timeline.events()).isEmpty();
        assertThat(timeline.states()).containsExactly(Map.entry("5", PumpState.FUELLING));
    }
}
