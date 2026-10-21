package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import org.junit.Test;

public class PrayerTimesTest {
    /** Makkah, Muslim World League: the times come in order and sit where the sun puts them. */
    @Test public void makkahIsInOrderAndPlausible() {
        int[] t = PrayerTimes.compute(LocalDate.of(2026, 3, 20), 21.4225, 39.8262, 3, 18, 17, null);
        for (int k = 1; k < 6; k++) assertTrue("prayer " + k + " after " + (k - 1), t[k] > t[k - 1]);
        // Near the equinox: Dhuhr about 12:2x local, day length about 12 hours.
        assertTrue(t[PrayerTimes.DHUHR] > 12 * 60 + 15 && t[PrayerTimes.DHUHR] < 12 * 60 + 35);
        int day = t[PrayerTimes.MAGHRIB] - t[PrayerTimes.SUNRISE];
        assertTrue(day > 11 * 60 + 50 && day < 12 * 60 + 20);
    }

    /** Offsets move each prayer by exactly that many minutes. */
    @Test public void offsetsApply() {
        LocalDate d = LocalDate.of(2026, 6, 1);
        int[] base = PrayerTimes.compute(d, 30.0444, 31.2357, 3, 18, 17, null);
        int[] moved = PrayerTimes.compute(d, 30.0444, 31.2357, 3, 18, 17, new int[]{-1, -4, -2, -1, 7, 3});
        int[] off = {-1, -4, -2, -1, 7, 3};
        for (int k = 0; k < 6; k++) assertEquals(base[k] + off[k], moved[k]);
    }
}
