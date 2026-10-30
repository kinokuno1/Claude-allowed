package ch.notes.moyenne;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class GradeCalcTest {

    private static final double D = 1e-9;

    @Test
    public void federalScale() {
        assertEquals(6.0, GradeCalc.fromPoints(40, 40), D);
        assertEquals(1.0, GradeCalc.fromPoints(0, 40), D);
        assertEquals(4.0, GradeCalc.fromPoints(24, 40), D);
        assertEquals(3.5, GradeCalc.fromPoints(20, 40), D);
        assertEquals(6.0, GradeCalc.fromPoints(45, 40), D); // bonus points are capped
        assertEquals(24.0, GradeCalc.pointsFor(4.0, 40), D);
    }

    @Test
    public void rounding() {
        assertEquals(4.5, GradeCalc.round(4.25, 0.5), D);
        assertEquals(4.0, GradeCalc.round(4.24, 0.5), D);
        assertEquals(5.0, GradeCalc.round(4.75, 0.5), D);
        assertEquals(4.3, GradeCalc.round(4.25, 0.1), D);
        assertEquals(4.25, GradeCalc.round(4.3, 0.25), D);
        assertEquals(4.123, GradeCalc.round(4.123, 0), D);
    }

    @Test
    public void weightedAverage() {
        List<double[]> grades = Arrays.asList(new double[] {5, 1}, new double[] {4, 2});
        assertEquals(13.0 / 3.0, GradeCalc.weightedAverage(grades), D);
        assertNull(GradeCalc.weightedAverage(Collections.emptyList()));
    }

    @Test
    public void requiredNote() {
        List<double[]> grades = Arrays.asList(new double[] {3.5, 1}, new double[] {4, 1});
        // (4 × 3 − 7.5) / 1 = 4.5
        assertEquals(4.5, GradeCalc.requiredNote(grades, 4.0, 1), D);
        assertEquals(4.0, GradeCalc.requiredNote(Collections.emptyList(), 4.0, 1), D);
    }

    @Test
    public void compensation() {
        List<Double> avgs = Arrays.asList(5.0, 3.5, 4.5, 3.0);
        assertEquals(1.5, GradeCalc.positivePoints(avgs), D);
        assertEquals(1.5, GradeCalc.negativePoints(avgs), D);
        assertEquals(2, GradeCalc.countInsufficient(avgs));
    }

    @Test
    public void format() {
        assertEquals("4.5", GradeCalc.format(4.5));
        assertEquals("4", GradeCalc.format(4.0));
        assertEquals("4.25", GradeCalc.format(4.25));
        assertEquals("4.33", GradeCalc.format(13.0 / 3.0));
    }
}
