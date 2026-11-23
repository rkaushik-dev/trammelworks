package io.github.rkaushikdev.trammelworks.forecourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.trammelworks.Json;
import io.github.rkaushikdev.trammelworks.LogRecord;
import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.Stream;
import io.github.rkaushikdev.trammelworks.archive.StoredRecord;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ForecourtPriceTest {

    static final NanoTime NOON = NanoTime.parse("2025-03-04T12:00:00Z");

    static NanoTime at(int minutes) {
        return NOON.shifted(minutes * 60 * NanoTime.NS_PER_SECOND);
    }

    static StoredRecord stored(String unit, int minutes, String message) {
        LogRecord tagged = new Tagger(new ForecourtConfig())
                .tag(new LogRecord("US-TX-0417", unit, Stream.STDOUT, at(minutes), message));
        return new StoredRecord(
                "k" + unit + minutes,
                tagged.site(),
                tagged.unit(),
                "standard",
                tagged.stream(),
                tagged.observedAt(),
                Optional.empty(),
                "unknown",
                tagged.message(),
                tagged.labels(),
                NOON);
    }

    static FuelPrice price(Grade grade, String value) {
        return new FuelPrice(grade, new BigDecimal(value));
    }

    @ParameterizedTest
    @CsvSource({
        "'price unleaded 1459', unleaded, 1.459",
        "'PRICE ULR 1.459 set', unleaded, 1.459",
        "'price change premium=1.659', premium, 1.659",
        "'FP3 price grade super: 1.71', premium, 1.710",
        "'price dsl 1529', diesel, 1.529",
        "'price plus 1.5', midgrade, 1.500"
    })
    void aPriceIsReadInEitherForm(String line, String grade, String value) {
        FuelPrice found = FuelPrice.read(line).orElseThrow();
        assertThat(found.grade().value()).isEqualTo(grade);
        assertThat(found.written()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unleaded 1459",
                "price unleaded",
                "price kerosene 1.2",
                "price unleaded 12",
                "price premium 1.4599"
            })
    void aLineWithoutAPriceForAGradeHasNone(String line) {
        assertThat(FuelPrice.read(line)).isEmpty();
    }

    @Test
    void theSignAndADispenserAreTaggedTheirOwnWay() {
        assertThat(stored("price-sign", 0, "price unleaded 1459").labels())
                .containsEntry("grade", "unleaded")
                .containsEntry("price", "1.459")
                .doesNotContainKey("pump");
        assertThat(stored("pump-bridge", 0, "FP03 price unleaded 1.479").labels())
                .containsEntry("pump", "3")
                .containsEntry("price", "1.479");
    }

    @Test
    void aChangeThatReachesBothInsideTheGraceIsFine() {
        PriceAudit audit = new PriceAudit();
        audit.signShows(price(Grade.UNLEADED, "1.459"), at(0));
        audit.pumpCharges("1", price(Grade.UNLEADED, "1.459"), at(0));
        audit.signShows(price(Grade.UNLEADED, "1.479"), at(10));
        audit.pumpCharges("1", price(Grade.UNLEADED, "1.479"), at(12));
        assertThat(audit.mismatches(at(60))).isEmpty();
    }

    @Test
    void aPumpThatMissedTheChangeIsReportedUntilItCatchesUp() {
        PriceAudit audit = new PriceAudit();
        audit.pumpCharges("2", price(Grade.DIESEL, "1.529"), at(0));
        audit.signShows(price(Grade.DIESEL, "1.549"), at(10));
        audit.pumpCharges("2", price(Grade.DIESEL, "1.549"), at(40));
        List<PriceMismatch> found = audit.mismatches(at(60));
        assertThat(found)
                .containsExactly(new PriceMismatch(
                        "2",
                        Grade.DIESEL,
                        new BigDecimal("1.549"),
                        new BigDecimal("1.529"),
                        at(10),
                        Optional.of(at(40))));
        assertThat(found.get(0).lasted(at(60))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aMismatchStillStandingIsMeasuredToNow() {
        PriceAudit audit = new PriceAudit();
        audit.signShows(price(Grade.PREMIUM, "1.659"), at(0));
        audit.pumpCharges("4", price(Grade.PREMIUM, "1.639"), at(1));
        assertThat(audit.mismatches(at(3))).isEmpty();
        PriceMismatch standing = audit.mismatches(at(30)).get(0);
        assertThat(standing.until()).isEmpty();
        assertThat(standing.lasted(at(30))).isEqualTo(Duration.ofMinutes(29));
    }

    @Test
    void otherGradesAndPumpsAreKeptApart() {
        PriceAudit audit = new PriceAudit();
        audit.signShows(price(Grade.UNLEADED, "1.459"), at(0));
        audit.pumpCharges("1", price(Grade.UNLEADED, "1.459"), at(0));
        audit.pumpCharges("1", price(Grade.DIESEL, "1.529"), at(0));
        audit.signShows(price(Grade.PREMIUM, "1.659"), at(1));
        assertThat(audit.mismatches(at(60))).isEmpty();
    }

    @Test
    void archivedRecordsAreReplayedInTimeOrder() {
        List<StoredRecord> records = List.of(
                stored("pump-bridge", 30, "FP02 price unleaded 1.479"),
                stored("price-sign", 0, "price unleaded 1479"),
                stored("pump-bridge", 1, "FP02 price unleaded 1.459"),
                stored("pump-bridge", 2, "FP02 IDLE"));
        List<PriceMismatch> found = PriceAudit.replay(records).mismatches(at(60));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).since()).isEqualTo(at(1));
        assertThat(found.get(0).until()).contains(at(30));
    }

    @Test
    void aValuedEnumIsLookedUpByExactlyItsValue() {
        assertThat(Json.Valued.lookup(Grade.class, "diesel")).contains(Grade.DIESEL);
        assertThat(Json.Valued.lookup(Grade.class, "Diesel")).isEmpty();
        assertThat(Grade.fromValue("premium")).contains(Grade.PREMIUM);
        assertThat(Grade.MIDGRADE.spellings()).contains("plus");
        assertThat(Grade.UNLEADED).hasToString("unleaded");
    }
}
