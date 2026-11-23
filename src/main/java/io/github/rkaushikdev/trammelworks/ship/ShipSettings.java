package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Coerce;
import io.github.rkaushikdev.trammelworks.ConfigError;
import io.github.rkaushikdev.trammelworks.Maps;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.Sections;
import io.github.rkaushikdev.trammelworks.ShipError;
import io.github.rkaushikdev.trammelworks.Units;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code ship} section of a site config: everything the shipper needs.
 *
 * @param endpoint the collector, or a {@code file://} directory for a site read by visit
 * @param token the bearer token, or empty
 * @param monthlyAllowanceBytes the plan's allowance
 * @param anchorDay the day of the month the plan bills on
 * @param batchBytes the most bytes per batch
 * @param batchRecords the most records per batch
 * @param attemptTimeout how long one upload may take
 * @param interval how often the shipper runs
 * @param floors each class's untouchable fraction of the allowance
 * @param deleteAcknowledged whether a fully acknowledged segment is deleted at once
 * @param signingKeyFile where the signing secret is, or empty
 * @param signingKeyId the signing key's name, or empty
 * @param compact whether part shipped segments are compacted
 * @param compactMinReclaimBytes the smallest saving worth a rewrite
 * @param compactMinFraction the smallest fraction worth a rewrite
 * @param requestOverheadBytes what each upload costs on the bill beyond its body
 */
public record ShipSettings(
        String endpoint,
        String token,
        long monthlyAllowanceBytes,
        int anchorDay,
        long batchBytes,
        int batchRecords,
        Duration attemptTimeout,
        Duration interval,
        Map<String, Double> floors,
        boolean deleteAcknowledged,
        String signingKeyFile,
        String signingKeyId,
        boolean compact,
        long compactMinReclaimBytes,
        double compactMinFraction,
        long requestOverheadBytes) {

    /** Where a commissioned site ships to until it is told otherwise. */
    public static final String DEFAULT_ENDPOINT = "https://collector.invalid/v1/ingest";

    /** The default allowance. */
    public static final long DEFAULT_ALLOWANCE = 2L * 1000 * 1000 * 1000;

    /** How often the shipper runs by default. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    static final Set<String> KEYS = Set.of(
            "endpoint",
            "token",
            "monthly_allowance",
            "anchor_day",
            "batch",
            "batch_records",
            "attempt_timeout",
            "interval",
            "floors",
            "delete_acknowledged",
            "signing_key_file",
            "signing_key_id",
            "compact",
            "compact_min_reclaim",
            "compact_min_fraction",
            "request_overhead");

    /** Checks the section against itself and against the meter. */
    public ShipSettings {
        floors = Maps.frozen(floors);
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://") && !endpoint.startsWith("file://")) {
            throw new ConfigError("endpoint must be an http or file url", endpoint);
        }
        if (attemptTimeout.isNegative() || attemptTimeout.isZero()) {
            throw new ConfigError("attempt_timeout must be positive");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new ConfigError("ship interval must be positive");
        }
        if (batchBytes > monthlyAllowanceBytes) {
            throw new ConfigError("a single batch cannot exceed the monthly allowance");
        }
        if (signingKeyFile.isEmpty() != signingKeyId.isEmpty()) {
            // Half a signing setup is worse than none: the batches go out unsigned and the
            // commissioning sheet says they are signed.
            throw new ConfigError(
                    "signing needs both a key file and a key id",
                    "file " + Repr.of(signingKeyFile) + ", id " + Repr.of(signingKeyId));
        }
        if (compactMinReclaimBytes < 0) {
            throw new ConfigError("compact_min_reclaim cannot be negative", Long.toString(compactMinReclaimBytes));
        }
        if (!(compactMinFraction >= 0.0 && compactMinFraction <= 1.0)) {
            throw new ConfigError("compact_min_fraction is a fraction of a segment", Repr.of(compactMinFraction));
        }
        if (requestOverheadBytes < 0 || requestOverheadBytes >= batchBytes) {
            // An overhead as big as a batch would mean every upload spends more on the
            // handshake than on the records, which is a typo, not a link.
            throw new ConfigError("request_overhead must be under the batch size", Long.toString(requestOverheadBytes));
        }
        try {
            new Meter(monthlyAllowanceBytes, anchorDay, floors, requestOverheadBytes);
        } catch (ShipError exc) {
            throw new ConfigError(exc.message(), exc.detail().orElse(null));
        }
    }

    /** The defaults. */
    public ShipSettings() {
        this(builder());
    }

    private ShipSettings(Builder from) {
        this(
                from.endpoint,
                from.token,
                from.monthlyAllowanceBytes,
                from.anchorDay,
                from.batchBytes,
                from.batchRecords,
                from.attemptTimeout,
                from.interval,
                from.floors,
                from.deleteAcknowledged,
                from.signingKeyFile,
                from.signingKeyId,
                from.compact,
                from.compactMinReclaimBytes,
                from.compactMinFraction,
                from.requestOverheadBytes);
    }

    /** A builder starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Whether batches go to a directory rather than over the link. */
    public boolean toFile() {
        return endpoint.startsWith("file://");
    }

    /** The directory a {@code file://} endpoint names. */
    public Path fileDirectory() {
        if (!toFile()) {
            throw new ConfigError("this endpoint is not a directory", endpoint);
        }
        return Path.of(endpoint.substring("file://".length()));
    }

    /** Whether this site signs its batches. */
    public boolean signs() {
        return !signingKeyFile.isEmpty();
    }

    /**
     * The signing key read off disk, or empty when this site does not sign. Read here rather than
     * held in the settings, so a config passed around or logged never carries the secret.
     */
    public Optional<SigningKey> signingKey() {
        if (!signs()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SigningKey.read(Path.of(signingKeyFile), signingKeyId));
        } catch (ShipError exc) {
            throw new ConfigError(exc.message(), exc.detail().orElse(null));
        }
    }

    /** The batch bounds. */
    public BatchLimits limits() {
        return new BatchLimits(batchBytes, batchRecords);
    }

    /** A fresh meter for this allowance. */
    public Meter meter() {
        return new Meter(monthlyAllowanceBytes, anchorDay, floors, requestOverheadBytes);
    }

    /** The retry curve. */
    public BackoffPolicy backoffPolicy() {
        return new BackoffPolicy();
    }

    /** Build the settings from the config block. */
    public static ShipSettings shipSettingsFromMapping(Map<String, ?> raw) {
        Sections.checkKeys(raw, KEYS, "ship");
        Builder built = builder();
        if (raw.containsKey("endpoint")) {
            built.endpoint(Coerce.text(raw.get("endpoint")));
        }
        if (raw.containsKey("token")) {
            built.token(Coerce.text(raw.get("token")));
        }
        if (raw.containsKey("monthly_allowance")) {
            built.monthlyAllowanceBytes(Units.parseBytes(raw.get("monthly_allowance")));
        }
        if (raw.containsKey("anchor_day")) {
            built.anchorDay(Coerce.toInt(raw.get("anchor_day")));
        }
        if (raw.containsKey("batch")) {
            built.batchBytes(Units.parseBytes(raw.get("batch")));
        }
        if (raw.containsKey("batch_records")) {
            built.batchRecords(Coerce.toInt(raw.get("batch_records")));
        }
        if (raw.containsKey("attempt_timeout")) {
            built.attemptTimeout(Units.parseDuration(raw.get("attempt_timeout")));
        }
        if (raw.containsKey("interval")) {
            built.interval(Units.parseDuration(raw.get("interval")));
        }
        if (raw.get("floors") != null) {
            Map<String, Double> floors = new LinkedHashMap<>();
            Sections.section(raw, "floors", "ship").forEach((name, value) -> floors.put(name, Coerce.toDouble(value)));
            built.floors(floors);
        }
        if (raw.containsKey("delete_acknowledged")) {
            built.deleteAcknowledged(Coerce.toBool(raw.get("delete_acknowledged")));
        }
        if (raw.containsKey("signing_key_file")) {
            built.signingKeyFile(Coerce.text(raw.get("signing_key_file")));
        }
        if (raw.containsKey("signing_key_id")) {
            built.signingKeyId(Coerce.text(raw.get("signing_key_id")));
        }
        if (raw.containsKey("compact")) {
            built.compact(Coerce.toBool(raw.get("compact")));
        }
        if (raw.containsKey("compact_min_reclaim")) {
            built.compactMinReclaimBytes(Units.parseBytes(raw.get("compact_min_reclaim")));
        }
        if (raw.containsKey("compact_min_fraction")) {
            built.compactMinFraction(Coerce.toDouble(raw.get("compact_min_fraction")));
        }
        if (raw.containsKey("request_overhead")) {
            built.requestOverheadBytes(Units.parseBytes(raw.get("request_overhead")));
        }
        return built.build();
    }

    /** Settings built a field at a time, starting from the defaults. */
    public static final class Builder {
        private String endpoint = DEFAULT_ENDPOINT;
        private String token = "";
        private long monthlyAllowanceBytes = DEFAULT_ALLOWANCE;
        private int anchorDay = 1;
        private long batchBytes = BatchLimits.DEFAULT_MAX_BYTES;
        private int batchRecords = BatchLimits.DEFAULT_MAX_RECORDS;
        private Duration attemptTimeout = Duration.ofSeconds(30);
        private Duration interval = DEFAULT_INTERVAL;
        private Map<String, Double> floors = Meter.DEFAULT_FLOORS;
        private boolean deleteAcknowledged = true;
        private String signingKeyFile = "";
        private String signingKeyId = "";
        private boolean compact = true;
        private long compactMinReclaimBytes = Compaction.DEFAULT_MIN_RECLAIM;
        private double compactMinFraction = Compaction.DEFAULT_MIN_FRACTION;
        private long requestOverheadBytes;

        private Builder() {}

        /** The collector or directory. */
        public Builder endpoint(String value) {
            endpoint = value;
            return this;
        }

        /** The bearer token. */
        public Builder token(String value) {
            token = value;
            return this;
        }

        /** The allowance. */
        public Builder monthlyAllowanceBytes(long value) {
            monthlyAllowanceBytes = value;
            return this;
        }

        /** The billing anchor. */
        public Builder anchorDay(int value) {
            anchorDay = value;
            return this;
        }

        /** The byte bound per batch. */
        public Builder batchBytes(long value) {
            batchBytes = value;
            return this;
        }

        /** The record bound per batch. */
        public Builder batchRecords(int value) {
            batchRecords = value;
            return this;
        }

        /** The upload timeout. */
        public Builder attemptTimeout(Duration value) {
            attemptTimeout = value;
            return this;
        }

        /** The shipping interval. */
        public Builder interval(Duration value) {
            interval = value;
            return this;
        }

        /** The class floors. */
        public Builder floors(Map<String, Double> value) {
            floors = value;
            return this;
        }

        /** Whether done segments are deleted at once. */
        public Builder deleteAcknowledged(boolean value) {
            deleteAcknowledged = value;
            return this;
        }

        /** The signing secret's file. */
        public Builder signingKeyFile(String value) {
            signingKeyFile = value;
            return this;
        }

        /** The signing key's name. */
        public Builder signingKeyId(String value) {
            signingKeyId = value;
            return this;
        }

        /** Whether to compact. */
        public Builder compact(boolean value) {
            compact = value;
            return this;
        }

        /** The smallest saving worth a rewrite. */
        public Builder compactMinReclaimBytes(long value) {
            compactMinReclaimBytes = value;
            return this;
        }

        /** The smallest fraction worth a rewrite. */
        public Builder compactMinFraction(double value) {
            compactMinFraction = value;
            return this;
        }

        /** What each upload costs beyond its body. */
        public Builder requestOverheadBytes(long value) {
            requestOverheadBytes = value;
            return this;
        }

        /** The checked settings. */
        public ShipSettings build() {
            return new ShipSettings(this);
        }
    }
}
