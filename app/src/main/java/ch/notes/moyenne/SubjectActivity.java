package ch.notes.moyenne;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** One subject: its grades, its average and the "note needed" calculator. */
public class SubjectActivity extends Activity {

    static final String EXTRA_INDEX = "index";
    private static final int ID_MODE_NOTE = 1;
    private static final int ID_MODE_POINTS = 2;

    private Store store;
    private Store.Subject subject;
    private LinearLayout content;
    // Kept across re-renders so the calculator keeps what the user typed.
    private String targetText = "4";
    private String nextWeightText = "1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.load(this);
        int index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        if (index < 0 || index >= store.subjects.size()) {
            finish();
            return;
        }
        subject = store.subjects.get(index);
        setTitle(subject.name);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        content = Ui.column(this);
        int p = Ui.dp(this, 16);
        content.setPadding(p, p, p, p);
        scroll.addView(content);
        setContentView(scroll);
        render();
    }

    private void render() {
        content.removeAllViews();

        LinearLayout summary = Ui.card(this);
        summary.setGravity(Gravity.CENTER_HORIZONTAL);
        summary.addView(Ui.text(this, "Moyenne de la branche", 15, Ui.MUTED, false));
        Double avg = subject.average();
        if (avg == null) {
            summary.addView(Ui.text(this, "–", 44, Ui.MUTED, true));
        } else {
            double rounded = store.roundedAverage(subject);
            summary.addView(Ui.text(this, GradeCalc.format(rounded), 44, Ui.colorFor(rounded), true));
            summary.addView(Ui.text(this, "Exacte : " + GradeCalc.format(GradeCalc.round(avg, 0.01)),
                    14, Ui.MUTED, false));
        }
        content.addView(summary);

        content.addView(Ui.header(this, "NOTES"));
        if (subject.grades.isEmpty()) {
            TextView empty = Ui.text(this, "Aucune note. Ajoute ta première note ci-dessous.", 15, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            content.addView(empty);
        }
        for (int i = 0; i < subject.grades.size(); i++) content.addView(gradeRow(i));

        Button add = new Button(this);
        add.setText("+ Ajouter une note");
        add.setOnClickListener(v -> editGrade(-1));
        content.addView(add);

        content.addView(Ui.header(this, "NOTE NÉCESSAIRE"));
        content.addView(calculatorCard());
    }

    private LinearLayout gradeRow(int index) {
        Store.Grade g = subject.grades.get(index);
        LinearLayout card = Ui.card(this);
        LinearLayout row = Ui.row(this);
        card.addView(row);

        LinearLayout left = Ui.column(this);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        String title = g.label == null || g.label.isEmpty() ? "Note " + (index + 1) : g.label;
        left.addView(Ui.text(this, title, 16, Ui.TEXT, true));
        String detail = "Poids " + GradeCalc.format(g.weight);
        if (g.maxPoints > 0) {
            detail += "  ·  " + GradeCalc.format(g.points) + " / " + GradeCalc.format(g.maxPoints) + " pts";
        }
        left.addView(Ui.text(this, detail, 13, Ui.MUTED, false));

        row.addView(Ui.text(this, GradeCalc.format(g.value), 22, Ui.colorFor(g.value), true));
        card.setOnClickListener(v -> editGrade(index));
        return card;
    }

    private LinearLayout calculatorCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout fields = Ui.row(this);
        card.addView(fields);

        LinearLayout targetCol = Ui.column(this);
        targetCol.addView(Ui.text(this, "Objectif (moyenne)", 13, Ui.MUTED, false));
        EditText target = Ui.numberField(this, "4", targetText);
        targetCol.addView(target);
        fields.addView(targetCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout weightCol = Ui.column(this);
        weightCol.addView(Ui.text(this, "Poids du prochain test", 13, Ui.MUTED, false));
        EditText weight = Ui.numberField(this, "1", nextWeightText);
        weightCol.addView(weight);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        wlp.leftMargin = Ui.dp(this, 12);
        fields.addView(weightCol, wlp);

        TextView result = Ui.text(this, "", 15, Ui.TEXT, false);
        result.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(result);

        Runnable update = () -> {
            targetText = target.getText().toString();
            nextWeightText = weight.getText().toString();
            Double t = Ui.parse(target);
            Double w = Ui.parse(weight);
            if (t == null || w == null || w <= 0) {
                result.setText("Entre un objectif et un poids.");
                result.setTextColor(Ui.MUTED);
                return;
            }
            // With rounding, reaching target − step/2 is enough to show target on the report card.
            double effective = store.subjectRounding > 0 ? t - store.subjectRounding / 2 : t;
            double needed = GradeCalc.requiredNote(subject.pairs(), effective, w);
            String suffix = store.subjectRounding > 0
                    ? " (moyenne exacte ≥ " + GradeCalc.format(effective) + ", arrondie à " + GradeCalc.format(t) + ")"
                    : "";
            if (needed > GradeCalc.MAX) {
                result.setText("Impossible avec un seul test : il faudrait " + GradeCalc.format(GradeCalc.round(needed, 0.01)) + "." + suffix);
                result.setTextColor(Ui.BAD);
            } else if (needed <= GradeCalc.MIN) {
                result.setText("Objectif assuré, même avec un 1." + suffix);
                result.setTextColor(Ui.GOOD);
            } else {
                // Round up to the tenth so the result is really enough.
                double shown = Math.ceil(needed * 10 - 1e-9) / 10;
                result.setText("Il te faut au moins " + GradeCalc.format(shown) + " au prochain test." + suffix);
                result.setTextColor(shown > 5 ? Ui.WARN : Ui.TEXT);
            }
        };
        TextWatcher watcher = new SimpleWatcher(update);
        target.addTextChangedListener(watcher);
        weight.addTextChangedListener(watcher);
        update.run();
        return card;
    }

    private void editGrade(int index) {
        Store.Grade existing = index >= 0 ? subject.grades.get(index) : null;
        boolean pointsMode = existing != null && existing.maxPoints > 0;

        LinearLayout body = Ui.dialogBody(this);
        body.addView(Ui.label(this, "Description (facultatif)"));
        EditText label = Ui.textField(this, "ex. TE algèbre", existing != null ? existing.label : null);
        body.addView(label);

        RadioGroup mode = new RadioGroup(this);
        mode.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton byNote = new RadioButton(this);
        byNote.setId(ID_MODE_NOTE);
        byNote.setText("Note");
        RadioButton byPoints = new RadioButton(this);
        byPoints.setId(ID_MODE_POINTS);
        byPoints.setText("Points (barème fédéral)");
        mode.addView(byNote);
        mode.addView(byPoints);
        mode.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(mode);

        LinearLayout noteBox = Ui.column(this);
        noteBox.addView(Ui.label(this, "Note (1 à 6)"));
        EditText note = Ui.numberField(this, "ex. 4.5",
                existing != null && !pointsMode ? GradeCalc.format(existing.value) : null);
        noteBox.addView(note);
        body.addView(noteBox);

        LinearLayout pointsBox = Ui.row(this);
        LinearLayout ptsCol = Ui.column(this);
        ptsCol.addView(Ui.label(this, "Points obtenus"));
        EditText pts = Ui.numberField(this, "ex. 18", pointsMode ? GradeCalc.format(existing.points) : null);
        ptsCol.addView(pts);
        pointsBox.addView(ptsCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout maxCol = Ui.column(this);
        maxCol.addView(Ui.label(this, "Points max"));
        EditText max = Ui.numberField(this, "ex. 24", pointsMode ? GradeCalc.format(existing.maxPoints) : null);
        maxCol.addView(max);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        mlp.leftMargin = Ui.dp(this, 12);
        pointsBox.addView(maxCol, mlp);
        body.addView(pointsBox);

        TextView preview = Ui.text(this, "", 14, Ui.PRIMARY, true);
        preview.setPadding(0, Ui.dp(this, 6), 0, 0);
        body.addView(preview);

        body.addView(Ui.label(this, "Poids de la note (ex. 2 pour un examen qui compte double)"));
        EditText weight = Ui.numberField(this, "1", existing != null ? GradeCalc.format(existing.weight) : "1");
        body.addView(weight);

        Runnable updatePreview = () -> {
            Double p = Ui.parse(pts);
            Double m = Ui.parse(max);
            if (m == null || m <= 0) {
                preview.setText("Note = points ÷ max × 5 + 1");
                return;
            }
            String text = "4 = " + GradeCalc.format(GradeCalc.round(GradeCalc.pointsFor(4, m), 0.01)) + " pts";
            if (p != null) {
                double n = GradeCalc.round(GradeCalc.fromPoints(p, m), store.testRounding);
                text = "→ Note : " + GradeCalc.format(GradeCalc.round(n, 0.01)) + "     (" + text + ")";
            }
            preview.setText(text);
        };
        TextWatcher watcher = new SimpleWatcher(updatePreview);
        pts.addTextChangedListener(watcher);
        max.addTextChangedListener(watcher);

        mode.setOnCheckedChangeListener((g, id) -> {
            boolean usePoints = id == ID_MODE_POINTS;
            noteBox.setVisibility(usePoints ? View.GONE : View.VISIBLE);
            pointsBox.setVisibility(usePoints ? View.VISIBLE : View.GONE);
            preview.setVisibility(usePoints ? View.VISIBLE : View.GONE);
            updatePreview.run();
        });
        mode.check(pointsMode ? ID_MODE_POINTS : ID_MODE_NOTE);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing != null ? "Modifier la note" : "Nouvelle note")
                .setView(scroll)
                .setPositiveButton("OK", null)
                .setNegativeButton("Annuler", null);
        if (existing != null) b.setNeutralButton("Supprimer", (d, w) -> {
            subject.grades.remove(index);
            store.save();
            render();
        });
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Double w = Ui.parse(weight);
            if (w == null || w <= 0) {
                weight.setError("Poids > 0 requis");
                return;
            }
            Store.Grade g = existing != null ? existing : new Store.Grade();
            if (mode.getCheckedRadioButtonId() == ID_MODE_POINTS) {
                Double p = Ui.parse(pts);
                Double m = Ui.parse(max);
                if (m == null || m <= 0) {
                    max.setError("Points max > 0 requis");
                    return;
                }
                if (p == null || p < 0) {
                    pts.setError("Points requis");
                    return;
                }
                g.points = p;
                g.maxPoints = m;
                g.value = GradeCalc.round(GradeCalc.fromPoints(p, m), store.testRounding);
            } else {
                Double n = Ui.parse(note);
                if (n == null || n < GradeCalc.MIN || n > GradeCalc.MAX) {
                    note.setError("Note entre 1 et 6");
                    return;
                }
                g.value = n;
                g.points = 0;
                g.maxPoints = 0;
            }
            g.label = label.getText().toString().trim();
            g.weight = w;
            if (existing == null) subject.grades.add(g);
            store.save();
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {
            onChange.run();
        }
    }
}
