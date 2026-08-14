/* Android IMSI-Catcher Detector | (c) AIMSICD Privacy Project
 * -----------------------------------------------------------
 * LICENSE:  http://git.io/vki47 | TERMS:  http://git.io/vki4o
 * -----------------------------------------------------------
 */
package com.secupwn.aimsicd.utils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Description:     Helpers that keep the "value not available" sentinels of the AOS telephony
 *                  API out of the rest of the app.
 *
 *                  Almost every signal related getter in AOS returns {@code Integer.MAX_VALUE}
 *                  (2147483647) when the modem does not report a value, and some of them use
 *                  {@code 99} (ASU) or {@code -1} instead. Those numbers used to be stored and
 *                  compared as if they were real measurements, which produced bogus averages,
 *                  bogus "mysterious signal" hits and integer overflow in the comparisons.
 *
 *                  {@link Cell} already initialises its unknown fields to
 *                  {@code Integer.MAX_VALUE}, so {@link #UNKNOWN_DBM} deliberately uses the
 *                  very same sentinel: an unknown reading simply leaves the cell as-is.
 */
public final class SignalStrengthUtils {

    /**
     * Value used across the app to say "we do not have a reading". Same sentinel that
     * {@link Cell} initialises its numeric fields with.
     */
    public static final int UNKNOWN_DBM = Integer.MAX_VALUE;

    /**
     * ASU value meaning "unknown". Identical to
     * {@link android.telephony.NeighboringCellInfo#UNKNOWN_RSSI}.
     */
    private static final int UNKNOWN_ASU = 99;

    /** Widest plausible received power over all RATs: LTE RSRP bottoms out at -140 dBm. */
    private static final int MIN_PLAUSIBLE_DBM = -140;

    /** No RAT reports a positive received power, and 0 / -1 are used as "invalid" by modems. */
    private static final int MAX_PLAUSIBLE_DBM = -2;

    /** Field names that carry a dBm reading in {@code SignalStrength.toString()}, best first. */
    private static final String[] DBM_FIELD_NAMES = {"rsrp", "rscp", "rssi", "dbm"};

    /** Matches the {@code key=value} pairs of a modern {@code SignalStrength.toString()}. */
    private static final Pattern KEY_VALUE = Pattern.compile("([a-z]+)\\s*=\\s*(-?\\d+)");

    private SignalStrengthUtils() {
        // Utility class
    }

    /**
     * @return true if {@code dbm} looks like an actual received power measurement and not one
     *         of the AOS "unknown" sentinels.
     */
    public static boolean isValidDbm(int dbm) {
        return dbm >= MIN_PLAUSIBLE_DBM && dbm <= MAX_PLAUSIBLE_DBM;
    }

    /**
     * @return {@code dbm} itself when it is a plausible measurement, {@link #UNKNOWN_DBM}
     *         otherwise. Use this on every value coming straight out of the telephony API.
     */
    public static int sanitizeDbm(int dbm) {
        return isValidDbm(dbm) ? dbm : UNKNOWN_DBM;
    }

    /**
     * Converts a GSM signal strength in ASU into dBm.
     *
     * {@code SignalStrength.getGsmSignalStrength()} returns ASU in the range [0-31], where 99
     * means "unknown". It is <em>not</em> a dBm value, so it must never be stored as one.
     *
     * @return the received power in [dBm], or {@link #UNKNOWN_DBM} if the ASU is unknown.
     */
    public static int gsmAsuToDbm(int asu) {
        if (asu == UNKNOWN_ASU || asu < 0 || asu > 31) {
            return UNKNOWN_DBM;
        }
        return sanitizeDbm(-113 + (2 * asu));
    }

    /**
     * @return the lowest of the two readings (conservative), ignoring unknown ones, or
     *         {@link #UNKNOWN_DBM} when neither is usable.
     */
    public static int lowestKnownDbm(int firstDbm, int secondDbm) {
        if (!isValidDbm(firstDbm)) {
            return sanitizeDbm(secondDbm);
        }
        if (!isValidDbm(secondDbm)) {
            return firstDbm;
        }
        return Math.min(firstDbm, secondDbm);
    }

    /**
     * Last resort extraction of a dBm reading out of {@code SignalStrength.toString()}.
     *
     * {@code toString()} is not part of the public API and its layout changed: it used to be a
     * bare, space separated list of integers, while modern AOS dumps named fields such as
     * {@code "CellSignalStrengthGsm: rssi=-95 ber=2147483647 mTa=2147483647 mLevel=3"}.
     * Fields are therefore looked up <em>by name</em>; picking a fixed token position is what
     * used to throw {@code NumberFormatException: For input string: "ber=2147483647"}.
     *
     * @return the received power in [dBm], or {@link #UNKNOWN_DBM} if nothing usable was found.
     */
    public static int parseDbmFromString(String signalStrengthDump) {
        if (signalStrengthDump == null) {
            return UNKNOWN_DBM;
        }
        String dump = signalStrengthDump.toLowerCase(Locale.US);

        if (dump.indexOf('=') >= 0) {
            for (String fieldName : DBM_FIELD_NAMES) {
                Matcher matcher = KEY_VALUE.matcher(dump);
                while (matcher.find()) {
                    // endsWith() so that prefixed variants (mRssi, lteRsrp, cdmaDbm, ...) match
                    if (matcher.group(1).endsWith(fieldName)) {
                        int dbm = parseIntOrUnknown(matcher.group(2));
                        if (isValidDbm(dbm)) {
                            return dbm;
                        }
                    }
                }
            }
            return UNKNOWN_DBM;
        }

        // Legacy layout: a bare list of integers with no way to tell which column belongs to
        // which RAT, so take the first token that is a plausible dBm reading.
        for (String token : dump.split("\\s+")) {
            int dbm = parseIntOrUnknown(token);
            if (isValidDbm(dbm)) {
                return dbm;
            }
        }
        return UNKNOWN_DBM;
    }

    /**
     * @return the parsed value, or {@link #UNKNOWN_DBM} when {@code value} is not an int.
     */
    private static int parseIntOrUnknown(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return UNKNOWN_DBM;
        }
    }
}
