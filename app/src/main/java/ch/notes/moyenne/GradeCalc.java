package ch.notes.moyenne;

import java.util.List;

/** Pure calculation logic for the Swiss 1–6 grading scale (no Android dependencies). */
public final class GradeCalc {

    public static final double MIN = 1.0;
    public static final double MAX = 6.0;
    public static final double PASS = 4.0;

    private GradeCalc() {}

    /** Federal scale: note = points / max × 5 + 1, clamped to 1–6. */
    public static double fromPoints(double points, double maxPoints) {
        if (maxPoints <= 0) throw new IllegalArgumentException("maxPoints must be > 0");
        return clamp(points / maxPoints * 5.0 + 1.0);
    }

    /** Points needed out of maxPoints to obtain a given note on the federal scale. */
    public static double pointsFor(double note, double maxPoints) {
        return (note - 1.0) / 5.0 * maxPoints;
    }

    /** Rounds to the nearest multiple of step (0.5, 0.25, 0.1…); step <= 0 means no rounding. */
    public static double round(double value, double step) {
        if (step <= 0) return value;
        // The small epsilon makes x.25 / x.75 round up despite floating point error.
        return Math.round(value / step + 1e-9) * step;
    }

    public static double clamp(double v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    /** Weighted average of {value, weight} pairs, or null when total weight is zero. */
    public static Double weightedAverage(List<double[]> valueWeightPairs) {
        double sum = 0, weights = 0;
        for (double[] p : valueWeightPairs) {
            sum += p[0] * p[1];
            weights += p[1];
        }
        return weights > 0 ? sum / weights : null;
    }

    /**
     * Note required on the next test of weight nextWeight so the weighted average reaches target.
     * May fall outside 1–6, which tells the user the target is impossible or already secured.
     */
    public static double requiredNote(List<double[]> valueWeightPairs, double target, double nextWeight) {
        double sum = 0, weights = 0;
        for (double[] p : valueWeightPairs) {
            sum += p[0] * p[1];
            weights += p[1];
        }
        return (target * (weights + nextWeight) - sum) / nextWeight;
    }

    /** Sum of points above 4 ("points positifs"). */
    public static double positivePoints(List<Double> averages) {
        double s = 0;
        for (double a : averages) if (a > PASS) s += a - PASS;
        return s;
    }

    /** Sum of points below 4 ("points négatifs"). */
    public static double negativePoints(List<Double> averages) {
        double s = 0;
        for (double a : averages) if (a < PASS) s += PASS - a;
        return s;
    }

    public static int countInsufficient(List<Double> averages) {
        int n = 0;
        for (double a : averages) if (a < PASS) n++;
        return n;
    }

    /** Formats 4.5 as "4.5" and 4.25 as "4.25", dropping useless trailing zeros. */
    public static String format(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }
}
