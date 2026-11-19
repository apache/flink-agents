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
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses, formats, and checks framework durations in the style of Flink's {@code TimeUtils}.
 *
 * <p>A value is an integer and one unit, optionally separated by whitespace, such as {@code 30 s}
 * or {@code 5 min}. A unitless integer means milliseconds, such as {@code 1000}. When the text does
 * not start with a number, it is parsed as an ISO-8601 duration, such as {@code PT30S}, {@code
 * PT1H30M}, or {@code P1DT2H}, including the lower-case forms {@code Duration.parse} accepts. A
 * leading {@code -} is allowed on every form. Recognized units, case-insensitively, are {@code ns},
 * {@code us}/{@code µs}, {@code ms}, {@code s}, {@code min}/{@code m}, {@code h}, and {@code d},
 * plus the longer singular and plural forms ({@code second}, {@code seconds}, {@code millisecond},
 * and so on). {@code m} means minutes.
 *
 * <p>The value must be a whole number of microseconds, because the Python counterpart is {@code
 * datetime.timedelta}. {@code 1000 ns} is one microsecond; {@code 1 ns} is rejected. An ISO-8601 or
 * unitless value that is not microsecond-aligned is rejected the same way. This parser does not
 * truncate to a coarser unit.
 */
public final class DurationUtils {

    private static final String EXPECTED_DURATION_TEXT =
            "expected a duration such as '30 s', '1000' (milliseconds), or 'PT30S'";

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);

    private static final Map<String, ChronoUnit> UNITS = units();

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

    private DurationUtils() {}

    /**
     * Returns a {@link Duration} after checking microsecond alignment. Other non-null values are
     * parsed from their string representation.
     */
    public static Duration parse(Object value) {
        if (value instanceof Duration) {
            return requireMicrosecondAligned((Duration) value, value.toString());
        }
        if (value == null) {
            throw invalid("null", EXPECTED_DURATION_TEXT);
        }
        String text = value.toString();
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw invalid(text, EXPECTED_DURATION_TEXT);
        }
        if (startsWithMagnitude(trimmed)) {
            return parseLabeled(trimmed, text);
        }
        return parseIso8601(trimmed, text);
    }

    /** Returns whether the text starts with digits, optionally preceded by {@code -} and spaces. */
    private static boolean startsWithMagnitude(String trimmed) {
        int index = 0;
        if (trimmed.charAt(0) == '-') {
            index++;
            while (index < trimmed.length() && trimmed.charAt(index) == ' ') {
                index++;
            }
        }
        return index < trimmed.length() && isDigit(trimmed.charAt(index));
    }

    private static Duration parseLabeled(String trimmed, String original) {
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
        String number = trimmed.substring(index, numberEnd);
        String unitLabel = trimmed.substring(numberEnd).trim().toLowerCase(Locale.US);
        if (unitLabel.startsWith(".")) {
            throw invalid(
                    original,
                    "expected an integer with an optional unit, for example '1500 ms' or '1500';"
                            + " use ISO-8601 for fractional seconds, for example 'PT1.5S'");
        }
        final ChronoUnit unit;
        if (unitLabel.isEmpty()) {
            unit = ChronoUnit.MILLIS;
        } else {
            unit = UNITS.get(unitLabel);
            if (unit == null) {
                throw invalid(
                        original,
                        "time unit '"
                                + unitLabel
                                + "' is not recognized. Use ns, us, ms, s, min, h, or d");
            }
        }

        final BigInteger magnitude;
        try {
            magnitude = new BigInteger(number);
        } catch (NumberFormatException e) {
            throw invalid(original, "the number cannot be represented as an integer");
        }
        BigInteger nanos = magnitude.multiply(BigInteger.valueOf(unit.getDuration().toNanos()));
        if (nanos.mod(NANOS_PER_MICRO).signum() != 0) {
            throw invalid(original, "durations finer than 1 us are not supported");
        }
        if (negative) {
            nanos = nanos.negate();
        }
        try {
            return durationFromNanos(nanos);
        } catch (ArithmeticException e) {
            throw invalid(original, "the value overflows a duration");
        }
    }

    /** Parses text that does not start with a numeric magnitude as an ISO-8601 duration. */
    private static Duration parseIso8601(String trimmed, String original) {
        final Duration parsed;
        try {
            parsed = Duration.parse(trimmed);
        } catch (DateTimeParseException e) {
            String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.US);
            if (detail.contains("overflow")) {
                throw invalid(original, "the value overflows a duration");
            }
            throw invalid(original, EXPECTED_DURATION_TEXT);
        }
        return requireMicrosecondAligned(parsed, original);
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

    /**
     * Nanoseconds of a positive duration. Null, zero, and negative return {@code 0}. A positive
     * duration that does not fit in a {@code long} throws {@link ArithmeticException}.
     */
    public static long positiveNanos(Duration duration) {
        if (!isPositive(duration)) {
            return 0L;
        }
        return duration.toNanos();
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
}
