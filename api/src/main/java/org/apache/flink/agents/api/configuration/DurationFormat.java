/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.agents.api.configuration;

import java.math.BigInteger;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses and formats framework durations in the style of Flink's duration configuration.
 *
 * <p>A value is an integer and one unit, optionally separated by whitespace, such as {@code 30 s}
 * or {@code 5 min}. A leading {@code -} is allowed. A unit is required; a bare number is rejected.
 * Recognized units, case-insensitively, are {@code ns}, {@code us}/{@code µs}, {@code ms}, {@code
 * s}, {@code min}/{@code m}, {@code h}, and {@code d}, plus the longer singular and plural forms
 * ({@code second}, {@code seconds}, {@code millisecond}, and so on). {@code m} means minutes.
 *
 * <p>The value must be a whole number of microseconds, because the Python counterpart is {@code
 * datetime.timedelta}. {@code 1000 ns} is one microsecond; {@code 1 ns} is rejected.
 */
public final class DurationFormat {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);

    private static final Map<String, ChronoUnit> UNITS = units();

    /**
     * Keys removed when duration options dropped their unit suffix. The value is the replacement
     * key. Reading the old key is rejected so a leftover numeric setting is not silently ignored.
     */
    private static final Map<String, String> REMOVED_KEYS = removedKeys();

    private static final ChronoUnit[] FORMAT_UNITS = {
        ChronoUnit.DAYS,
        ChronoUnit.HOURS,
        ChronoUnit.MINUTES,
        ChronoUnit.SECONDS,
        ChronoUnit.MILLIS,
        ChronoUnit.MICROS,
        ChronoUnit.NANOS
    };

    private static final String[] FORMAT_LABELS = {"d", "h", "min", "s", "ms", "us", "ns"};

    private DurationFormat() {}

    /**
     * Parses a duration string or returns a {@link Duration} unchanged after the same alignment
     * check.
     */
    public static Duration parse(Object value) {
        if (value instanceof Duration) {
            return requireMicrosecondAligned((Duration) value, value.toString());
        }
        if (value instanceof CharSequence) {
            return parse(value.toString());
        }
        throw invalid(
                String.valueOf(value),
                "a duration needs a number and a unit, for example '30 s' or '5 min',"
                        + " not a bare number");
    }

    /** Parses one duration string. */
    public static Duration parse(String text) {
        if (text == null) {
            throw invalid("null", "a duration needs a number and a unit, for example '30 s'");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw invalid(text, "a duration needs a number and a unit, for example '30 s'");
        }

        boolean negative = false;
        int index = 0;
        if (trimmed.charAt(0) == '-') {
            negative = true;
            index++;
            while (index < trimmed.length() && trimmed.charAt(index) == ' ') {
                index++;
            }
        }
        int numberEnd = index;
        while (numberEnd < trimmed.length() && isDigit(trimmed.charAt(numberEnd))) {
            numberEnd++;
        }
        if (numberEnd == index) {
            throw invalid(
                    text, "a duration needs an integer and a unit, for example '30 s' or '5 min'");
        }
        String number = trimmed.substring(index, numberEnd);
        String unitLabel = trimmed.substring(numberEnd).trim().toLowerCase(Locale.US);
        if (unitLabel.startsWith(".")) {
            throw invalid(text, "a duration needs an integer and a unit, for example '1500 ms'");
        }
        if (unitLabel.isEmpty()) {
            throw invalid(
                    text,
                    "a duration needs a unit, for example '30 s' or '30 ms', not a bare number");
        }
        ChronoUnit unit = UNITS.get(unitLabel);
        if (unit == null) {
            throw invalid(
                    text,
                    "time unit '"
                            + unitLabel
                            + "' is not recognized. Use ns, us, ms, s, min, h, or d");
        }

        final BigInteger magnitude;
        try {
            magnitude = new BigInteger(number);
        } catch (NumberFormatException e) {
            throw invalid(text, "the number cannot be represented as an integer");
        }
        BigInteger nanos = magnitude.multiply(BigInteger.valueOf(unit.getDuration().toNanos()));
        if (nanos.mod(NANOS_PER_MICRO).signum() != 0) {
            throw invalid(text, "durations finer than 1 us are not supported");
        }
        if (negative) {
            nanos = nanos.negate();
        }
        try {
            return durationFromNanos(nanos);
        } catch (ArithmeticException e) {
            throw invalid(text, "the value overflows a duration");
        }
    }

    /**
     * Formats a duration with the largest unit that does not lose precision, for example {@code 60
     * s} as {@code 1 min}. Zero is {@code 0 ms}.
     */
    public static String format(Duration duration) {
        if (duration.isZero()) {
            return "0 ms";
        }
        if (duration.isNegative()) {
            return "-" + format(duration.negated());
        }
        BigInteger nanos = toNanos(duration);
        for (int i = 0; i < FORMAT_UNITS.length; i++) {
            BigInteger unitNanos = BigInteger.valueOf(FORMAT_UNITS[i].getDuration().toNanos());
            if (nanos.mod(unitNanos).signum() == 0) {
                return nanos.divide(unitNanos).toString() + " " + FORMAT_LABELS[i];
            }
        }
        throw new IllegalArgumentException("Cannot format duration " + duration);
    }

    /** True when the duration is non-null and strictly greater than zero. */
    public static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    /** Returns {@link Duration#ZERO} when {@code duration} is null or negative. */
    public static Duration zeroIfNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    /**
     * Returns an error naming every removed duration key in {@code keys}, or null when none are
     * present.
     */
    public static String removedKeysMessage(Iterable<String> keys) {
        List<String> found = new ArrayList<>();
        for (String key : keys) {
            String replacement = REMOVED_KEYS.get(key);
            if (replacement != null) {
                found.add("'" + key + "' (use '" + replacement + "')");
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        Collections.sort(found);
        return "Unsupported duration configuration keys: "
                + String.join(", ", found)
                + ". Values use a duration such as '30 s' or '5 min'.";
    }

    private static Duration requireMicrosecondAligned(Duration duration, String rendered) {
        if (Math.floorMod(duration.getNano(), 1_000) != 0) {
            throw invalid(rendered, "durations finer than 1 us are not supported");
        }
        return duration;
    }

    private static Duration durationFromNanos(BigInteger nanos) {
        BigInteger[] parts = nanos.divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(parts[0].longValueExact(), parts[1].longValueExact());
    }

    private static BigInteger toNanos(Duration duration) {
        return BigInteger.valueOf(duration.getSeconds())
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(duration.getNano()));
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static IllegalArgumentException invalid(String value, String reason) {
        return new IllegalArgumentException("Invalid duration '" + value + "': " + reason + ".");
    }

    private static Map<String, ChronoUnit> units() {
        Map<String, ChronoUnit> units = new LinkedHashMap<>();
        add(units, ChronoUnit.NANOS, "ns", "nano", "nanos", "nanosecond", "nanoseconds");
        add(
                units,
                ChronoUnit.MICROS,
                "µs",
                "μs",
                "us",
                "micro",
                "micros",
                "microsecond",
                "microseconds");
        add(units, ChronoUnit.MILLIS, "ms", "milli", "millis", "millisecond", "milliseconds");
        add(units, ChronoUnit.SECONDS, "s", "sec", "secs", "second", "seconds");
        add(units, ChronoUnit.MINUTES, "min", "m", "mins", "minute", "minutes");
        add(units, ChronoUnit.HOURS, "h", "hour", "hours");
        add(units, ChronoUnit.DAYS, "d", "day", "days");
        return Collections.unmodifiableMap(units);
    }

    private static void add(Map<String, ChronoUnit> units, ChronoUnit unit, String... labels) {
        for (String label : labels) {
            units.put(label, unit);
        }
    }

    private static Map<String, String> removedKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("async.batch.timeout.ms", "async.batch.timeout");
        keys.put("short-term-memory.state-ttl.ms", "short-term-memory.state-ttl");
        return Collections.unmodifiableMap(keys);
    }
}
