package io.github.rkaushikdev.trammelworks.forecourt;

import io.github.rkaushikdev.trammelworks.Json;

/** Who has to hear about a tank gauge alarm, and how fast. */
public enum AlarmSeverity implements Json.Valued {
    /**
     * Product may be leaving the tank. Release detection alarms are what the underground storage
     * tank rules make the operator investigate and, when they are confirmed, report to the
     * regulator, so these are the alarms that must survive anything the box does to save space.
     */
    ENVIRONMENTAL("environmental"),
    /** The site is about to run dry or overfill: somebody has to call the haulier. */
    OPERATIONAL("operational"),
    /** The gauge cannot see the tank properly, so every reading after this is suspect. */
    EQUIPMENT("equipment");

    private final String value;

    AlarmSeverity(String value) {
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
