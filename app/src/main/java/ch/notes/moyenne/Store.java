package ch.notes.moyenne;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Data model and persistence (JSON in SharedPreferences). */
public final class Store {

    public static final class Grade {
        public String label;
        public double value;
        public double weight;
        /** Kept so the grade can be edited in points later; maxPoints == 0 means entered as a note. */
        public double points;
        public double maxPoints;
    }

    public static final class Subject {
        public String name;
        public double coef = 1;
        public final List<Grade> grades = new ArrayList<>();

        public List<double[]> pairs() {
            List<double[]> p = new ArrayList<>();
            for (Grade g : grades) p.add(new double[] {g.value, g.weight});
            return p;
        }

        /** Exact weighted average, or null with no grades. */
        public Double average() {
            return GradeCalc.weightedAverage(pairs());
        }
    }

    private static final String PREFS = "moyenne";
    private static final String KEY_DATA = "data";

    public final List<Subject> subjects = new ArrayList<>();
    /** Rounding applied to a test converted from points (0 = none). */
    public double testRounding = 0.1;
    /** Rounding applied to each subject average, as on a Swiss report card (0 = none). */
    public double subjectRounding = 0.5;

    private final SharedPreferences prefs;

    private Store(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Store load(Context ctx) {
        Store s = new Store(ctx);
        String raw = s.prefs.getString(KEY_DATA, null);
        if (raw == null) return s;
        try {
            JSONObject root = new JSONObject(raw);
            s.testRounding = root.optDouble("testRounding", 0.1);
            s.subjectRounding = root.optDouble("subjectRounding", 0.5);
            JSONArray subs = root.optJSONArray("subjects");
            for (int i = 0; subs != null && i < subs.length(); i++) {
                JSONObject so = subs.getJSONObject(i);
                Subject sub = new Subject();
                sub.name = so.getString("name");
                sub.coef = so.optDouble("coef", 1);
                JSONArray gs = so.optJSONArray("grades");
                for (int j = 0; gs != null && j < gs.length(); j++) {
                    JSONObject go = gs.getJSONObject(j);
                    Grade g = new Grade();
                    g.label = go.optString("label", "");
                    g.value = go.getDouble("value");
                    g.weight = go.optDouble("weight", 1);
                    g.points = go.optDouble("points", 0);
                    g.maxPoints = go.optDouble("maxPoints", 0);
                    sub.grades.add(g);
                }
                s.subjects.add(sub);
            }
        } catch (JSONException e) {
            // Corrupted data: start fresh rather than crash.
            s.subjects.clear();
        }
        return s;
    }

    public void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("testRounding", testRounding);
            root.put("subjectRounding", subjectRounding);
            JSONArray subs = new JSONArray();
            for (Subject sub : subjects) {
                JSONObject so = new JSONObject();
                so.put("name", sub.name);
                so.put("coef", sub.coef);
                JSONArray gs = new JSONArray();
                for (Grade g : sub.grades) {
                    JSONObject go = new JSONObject();
                    go.put("label", g.label);
                    go.put("value", g.value);
                    go.put("weight", g.weight);
                    go.put("points", g.points);
                    go.put("maxPoints", g.maxPoints);
                    gs.put(go);
                }
                so.put("grades", gs);
                subs.put(so);
            }
            root.put("subjects", subs);
            prefs.edit().putString(KEY_DATA, root.toString()).apply();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Subject average rounded per settings, or null with no grades. */
    public Double roundedAverage(Subject s) {
        Double avg = s.average();
        return avg == null ? null : GradeCalc.round(avg, subjectRounding);
    }

    /** Rounded averages of all subjects that have grades (what the report card shows). */
    public List<Double> reportAverages() {
        List<Double> list = new ArrayList<>();
        for (Subject s : subjects) {
            Double r = roundedAverage(s);
            if (r != null) list.add(r);
        }
        return list;
    }

    /** Overall average of the rounded subject averages, weighted by coefficient. */
    public Double overallAverage() {
        List<double[]> p = new ArrayList<>();
        for (Subject s : subjects) {
            Double r = roundedAverage(s);
            if (r != null) p.add(new double[] {r, s.coef});
        }
        return GradeCalc.weightedAverage(p);
    }
}
