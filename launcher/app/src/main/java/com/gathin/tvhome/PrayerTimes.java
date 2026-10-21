package com.gathin.tvhome;

import java.time.LocalDate;

/**
 * Prayer times computed on the device (no network), with the solar algorithms from Meeus' "Astronomical
 * Algorithms" as the Adhan library uses them, so the times agree with the common prayer apps to the minute.
 * Fajr and Isha by sun angle below the horizon (Muslim World League: 18 / 17), Asr by the Shafi'i shadow
 * ratio 1, Dhuhr one minute after transit (the method's own adjustment). Results are minutes after local
 * midnight, rounded to the nearest minute, with per-prayer offsets in minutes added.
 */
final class PrayerTimes {
    static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    /** Muslim World League's own adjustment, as in Adhan: Dhuhr a minute after the sun crosses the meridian. */
    private static final int[] METHOD_ADJUST = {0, 0, 1, 0, 0, 0};

    private PrayerTimes() {}

    /** @param tzHours the UTC offset in hours on that date (e.g. 3 for Palestine summer time). */
    static int[] compute(LocalDate date, double lat, double lng, double tzHours,
                         double fajrAngle, double ishaAngle, int[] offsets) {
        double jd = julianDay(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        Sun prev = new Sun(jd - 1), today = new Sun(jd), next = new Sun(jd + 1);
        double m0 = approximateTransit(lng, today.siderealTime, today.rightAscension);
        double transit = correctedTransit(m0, lng, today.siderealTime, today.rightAscension,
                prev.rightAscension, next.rightAscension);
        double asrAngle = Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(lat - today.declination))))));
        double[] ut = {
                hourAngle(m0, -fajrAngle, lat, lng, false, today, prev, next),
                hourAngle(m0, -50.0 / 60, lat, lng, false, today, prev, next),
                transit,
                hourAngle(m0, asrAngle, lat, lng, true, today, prev, next),
                hourAngle(m0, -50.0 / 60, lat, lng, true, today, prev, next),
                hourAngle(m0, -ishaAngle, lat, lng, true, today, prev, next)};
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            int adjust = METHOD_ADJUST[i] + (offsets != null && i < offsets.length ? offsets[i] : 0);
            int minutes = (int) Math.round((ut[i] + tzHours) * 60) + adjust;
            out[i] = ((minutes % 1440) + 1440) % 1440;
        }
        return out;
    }

    /** Apparent position of the sun at 0h UT on a Julian day. */
    private static final class Sun {
        final double declination, rightAscension, siderealTime;   // degrees

        Sun(double jd) {
            double t = (jd - 2451545.0) / 36525;
            double l0 = unwind(280.4664567 + 36000.76983 * t + 0.0003032 * t * t);
            double lp = unwind(218.3165 + 481267.8813 * t);
            double omega = unwind(125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450000);
            double m = unwind(357.52911 + 35999.05029 * t - 0.0001537 * t * t);
            double c = sin(m) * (1.914602 - 0.004817 * t - 0.000014 * t * t)
                    + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289;
            double lambda = unwind(l0 + c - 0.00569 - 0.00478 * sin(125.04 - 1934.136 * t));
            double theta0 = unwind(280.46061837 + 360.98564736629 * (jd - 2451545)
                    + 0.000387933 * t * t - t * t * t / 38710000);
            double dPsi = (-17.2 / 3600) * sin(omega) - (1.32 / 3600) * sin(2 * l0)
                    - (0.23 / 3600) * sin(2 * lp) + (0.21 / 3600) * sin(2 * omega);
            double dEps = (9.2 / 3600) * cos(omega) + (0.57 / 3600) * cos(2 * l0)
                    + (0.10 / 3600) * cos(2 * lp) - (0.09 / 3600) * cos(2 * omega);
            double eps0 = 23.439291 - 0.013004167 * t - 0.0000001639 * t * t + 0.0000005036 * t * t * t;
            double epsApp = eps0 + 0.00256 * cos(125.04 - 1934.136 * t);
            declination = Math.toDegrees(Math.asin(sin(epsApp) * sin(lambda)));
            rightAscension = unwind(Math.toDegrees(Math.atan2(cos(epsApp) * sin(lambda), cos(lambda))));
            siderealTime = theta0 + (dPsi * 3600 * cos(eps0 + dEps)) / 3600;
        }
    }

    private static double approximateTransit(double lng, double theta0, double ra) {
        return normalize((ra - lng - theta0) / 360, 1);
    }

    private static double correctedTransit(double m0, double lng, double theta0, double ra2, double ra1, double ra3) {
        double theta = unwind(theta0 + 360.985647 * m0);
        double ra = unwind(interpolateAngles(ra2, ra1, ra3, m0));
        double h = closestAngle(theta + lng - ra);
        return (m0 - h / 360) * 24;
    }

    private static double hourAngle(double m0, double h0, double lat, double lng, boolean afterTransit,
                                    Sun s2, Sun s1, Sun s3) {
        double h0r = Math.toDegrees(Math.acos((sin(h0) - sin(lat) * sin(s2.declination))
                / (cos(lat) * cos(s2.declination))));
        double m = afterTransit ? m0 + h0r / 360 : m0 - h0r / 360;
        double theta = unwind(s2.siderealTime + 360.985647 * m);
        double ra = unwind(interpolateAngles(s2.rightAscension, s1.rightAscension, s3.rightAscension, m));
        double decl = interpolate(s2.declination, s1.declination, s3.declination, m);
        double h = theta + lng - ra;
        double alt = Math.toDegrees(Math.asin(sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(h)));
        double dm = (alt - h0) / (360 * cos(decl) * cos(lat) * sin(h));
        return (m + dm) * 24;
    }

    private static double julianDay(int year, int month, int day) {
        int y = month > 2 ? year : year - 1;
        int m = month > 2 ? month : month + 12;
        int a = y / 100;
        int b = 2 - a + a / 4;
        return (int) (365.25 * (y + 4716)) + (int) (30.6001 * (m + 1)) + day + b - 1524.5;
    }

    private static double interpolate(double y2, double y1, double y3, double n) {
        double a = y2 - y1, b = y3 - y2, c = b - a;
        return y2 + (n / 2) * (a + b + n * c);
    }

    private static double interpolateAngles(double y2, double y1, double y3, double n) {
        double a = unwind(y2 - y1), b = unwind(y3 - y2), c = b - a;
        return y2 + (n / 2) * (a + b + n * c);
    }

    private static double normalize(double v, double max) { return v - max * Math.floor(v / max); }
    private static double unwind(double deg) { return normalize(deg, 360); }
    private static double closestAngle(double deg) { return deg >= -180 && deg <= 180 ? deg : deg - 360 * Math.round(deg / 360); }
    private static double sin(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cos(double deg) { return Math.cos(Math.toRadians(deg)); }
}
