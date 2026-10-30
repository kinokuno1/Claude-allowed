package ch.notes.moyenne;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Home screen: overall average and list of subjects. */
public class MainActivity extends Activity {

    private static final int MENU_SETTINGS = 1;
    private static final int MENU_HELP = 2;
    private static final double[] ROUNDING_STEPS = {0.5, 0.25, 0.1, 0};
    private static final String[] ROUNDING_LABELS = {"Au demi-point (0.5)", "Au quart (0.25)", "Au dixième (0.1)", "Aucun arrondi"};

    private Store store;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        content = Ui.column(this);
        int p = Ui.dp(this, 16);
        content.setPadding(p, p, p, p);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        store = Store.load(this);
        render();
    }

    private void render() {
        content.removeAllViews();
        content.addView(summaryCard());

        content.addView(Ui.header(this, "BRANCHES"));
        if (store.subjects.isEmpty()) {
            TextView empty = Ui.text(this,
                    "Aucune branche pour l'instant.\nAjoute tes branches (Français, Maths…) puis tes notes.",
                    15, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
            content.addView(empty);
        }
        for (int i = 0; i < store.subjects.size(); i++) content.addView(subjectRow(i));

        Button add = new Button(this);
        add.setText("+ Ajouter une branche");
        add.setOnClickListener(v -> editSubject(-1));
        content.addView(add);
    }

    private LinearLayout summaryCard() {
        LinearLayout card = Ui.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(Ui.text(this, "Moyenne générale", 15, Ui.MUTED, false));

        Double overall = store.overallAverage();
        TextView big = Ui.text(this, overall == null ? "–" : GradeCalc.format(GradeCalc.round(overall, 0.01)),
                48, overall == null ? Ui.MUTED : Ui.colorFor(overall), true);
        card.addView(big);

        if (overall == null) {
            card.addView(Ui.text(this, "Ajoute des notes pour voir ta moyenne", 13, Ui.MUTED, false));
            return card;
        }

        List<Double> avgs = store.reportAverages();
        double pos = GradeCalc.positivePoints(avgs);
        double neg = GradeCalc.negativePoints(avgs);
        int insufficient = GradeCalc.countInsufficient(avgs);

        card.addView(Ui.text(this, "Arrondie au demi-point : " + GradeCalc.format(GradeCalc.round(overall, 0.5)),
                14, Ui.TEXT, false));

        TextView stats = Ui.text(this,
                "Points positifs : +" + GradeCalc.format(pos)
                        + "   ·   négatifs : −" + GradeCalc.format(neg)
                        + "\nNotes insuffisantes : " + insufficient,
                13, Ui.MUTED, false);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(stats);

        boolean avgOk = overall >= GradeCalc.PASS;
        boolean compOk = pos >= 2 * neg;
        String status;
        int color;
        if (avgOk && compOk) {
            status = "✓ Moyenne ≥ 4 et points négatifs compensés à double";
            color = Ui.GOOD;
        } else if (avgOk) {
            status = "⚠ Moyenne ≥ 4, mais les points négatifs ne sont pas compensés à double";
            color = Ui.WARN;
        } else {
            status = "✗ Moyenne générale inférieure à 4";
            color = Ui.BAD;
        }
        TextView st = Ui.text(this, status, 13, color, true);
        st.setGravity(Gravity.CENTER);
        st.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(st);
        return card;
    }

    private LinearLayout subjectRow(int index) {
        Store.Subject s = store.subjects.get(index);
        LinearLayout card = Ui.card(this);
        LinearLayout row = Ui.row(this);
        card.addView(row);

        LinearLayout left = Ui.column(this);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        left.addView(Ui.text(this, s.name, 17, Ui.TEXT, true));
        int n = s.grades.size();
        left.addView(Ui.text(this, "Coef. " + GradeCalc.format(s.coef) + "  ·  " + n + (n > 1 ? " notes" : " note"),
                13, Ui.MUTED, false));

        LinearLayout right = Ui.column(this);
        right.setGravity(Gravity.END);
        row.addView(right);
        Double avg = s.average();
        if (avg == null) {
            right.addView(Ui.text(this, "–", 24, Ui.MUTED, true));
        } else {
            double rounded = store.roundedAverage(s);
            right.addView(Ui.text(this, GradeCalc.format(rounded), 24, Ui.colorFor(rounded), true));
            right.addView(Ui.text(this, "(" + GradeCalc.format(GradeCalc.round(avg, 0.01)) + ")", 12, Ui.MUTED, false));
        }

        card.setOnClickListener(v -> {
            Intent i = new Intent(this, SubjectActivity.class);
            i.putExtra(SubjectActivity.EXTRA_INDEX, index);
            startActivity(i);
        });
        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(s.name)
                    .setItems(new String[] {"Modifier", "Supprimer"}, (d, which) -> {
                        if (which == 0) editSubject(index);
                        else confirmDelete(index);
                    })
                    .show();
            return true;
        });
        return card;
    }

    private void editSubject(int index) {
        Store.Subject existing = index >= 0 ? store.subjects.get(index) : null;
        LinearLayout body = Ui.dialogBody(this);
        body.addView(Ui.label(this, "Nom de la branche"));
        EditText name = Ui.textField(this, "ex. Mathématiques", existing != null ? existing.name : null);
        body.addView(name);
        body.addView(Ui.label(this, "Coefficient (poids dans la moyenne générale)"));
        EditText coef = Ui.numberField(this, "1", existing != null ? GradeCalc.format(existing.coef) : "1");
        body.addView(coef);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing != null ? "Modifier la branche" : "Nouvelle branche")
                .setView(body)
                .setPositiveButton("OK", null)
                .setNegativeButton("Annuler", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            Double c = Ui.parse(coef);
            if (n.isEmpty()) {
                name.setError("Nom requis");
                return;
            }
            if (c == null || c <= 0) {
                coef.setError("Coefficient > 0 requis");
                return;
            }
            Store.Subject s = existing != null ? existing : new Store.Subject();
            s.name = n;
            s.coef = c;
            if (existing == null) store.subjects.add(s);
            store.save();
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    private void confirmDelete(int index) {
        Store.Subject s = store.subjects.get(index);
        new AlertDialog.Builder(this)
                .setTitle("Supprimer « " + s.name + " » ?")
                .setMessage("Toutes les notes de cette branche seront perdues.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    store.subjects.remove(index);
                    store.save();
                    render();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showSettings() {
        LinearLayout body = Ui.dialogBody(this);
        body.addView(Ui.label(this, "Arrondi de la moyenne de chaque branche (bulletin)"));
        RadioGroup subjectGroup = roundingGroup(store.subjectRounding);
        body.addView(subjectGroup);
        body.addView(Ui.label(this, "Arrondi d'une note calculée à partir des points"));
        RadioGroup testGroup = roundingGroup(store.testRounding);
        body.addView(testGroup);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("Réglages")
                .setView(scroll)
                .setPositiveButton("OK", (d, w) -> {
                    store.subjectRounding = ROUNDING_STEPS[subjectGroup.getCheckedRadioButtonId() - 1];
                    store.testRounding = ROUNDING_STEPS[testGroup.getCheckedRadioButtonId() - 1];
                    store.save();
                    Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show();
                    render();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private RadioGroup roundingGroup(double current) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < ROUNDING_STEPS.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(i + 1); // id - 1 indexes ROUNDING_STEPS
            b.setText(ROUNDING_LABELS[i]);
            g.addView(b);
            if (ROUNDING_STEPS[i] == current) g.check(i + 1);
        }
        if (g.getCheckedRadioButtonId() == -1) g.check(1);
        return g;
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Barème fédéral")
                .setMessage("Échelle suisse de 1 à 6 (4 = suffisant).\n\n"
                        + "Note = points obtenus ÷ points max × 5 + 1\n"
                        + "ex. 18 / 24 pts → 18 ÷ 24 × 5 + 1 = 4.75\n\n"
                        + "• Moyenne de branche : moyenne pondérée de tes notes, arrondie selon les réglages "
                        + "(par défaut au demi-point, comme sur le bulletin).\n"
                        + "• Moyenne générale : moyenne des branches arrondies, pondérée par leur coefficient.\n"
                        + "• Compensation : la somme des points au-dessus de 4 doit valoir au moins le double "
                        + "de la somme des points en dessous de 4.\n\n"
                        + "Les règles de promotion exactes varient selon l'école et le canton : vérifie ton règlement.\n\n"
                        + "Appui long sur une branche pour la modifier ou la supprimer.")
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SETTINGS, 0, "Réglages").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_HELP, 1, "Barème").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            showSettings();
            return true;
        }
        if (item.getItemId() == MENU_HELP) {
            showHelp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
