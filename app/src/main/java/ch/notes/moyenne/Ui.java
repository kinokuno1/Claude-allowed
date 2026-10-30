package ch.notes.moyenne;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers to build the UI in code, keeping the app free of extra libraries. */
final class Ui {

    static final int PRIMARY = Color.parseColor("#1565C0");
    static final int TEXT = Color.parseColor("#212121");
    static final int MUTED = Color.parseColor("#757575");
    static final int GOOD = Color.parseColor("#2E7D32");
    static final int WARN = Color.parseColor("#EF6C00");
    static final int BAD = Color.parseColor("#C62828");
    static final int BG = Color.parseColor("#F2F4F7");

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Green from 4, orange for 3.5–4, red below. */
    static int colorFor(double note) {
        if (note >= GradeCalc.PASS) return GOOD;
        if (note >= 3.5) return WARN;
        return BAD;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(c, 14));
        l.setBackground(bg);
        l.setElevation(dp(c, 2));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView header(Context c, String s) {
        TextView t = text(c, s, 14, MUTED, true);
        t.setPadding(dp(c, 4), dp(c, 8), 0, dp(c, 8));
        return t;
    }

    static EditText numberField(Context c, String hint, String initial) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        // Accept the comma typed on Swiss French keyboards.
        e.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
        if (initial != null) e.setText(initial);
        return e;
    }

    static EditText textField(Context c, String hint, String initial) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSingleLine(true);
        if (initial != null) e.setText(initial);
        return e;
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s, 13, MUTED, false);
        t.setPadding(0, dp(c, 10), 0, 0);
        return t;
    }

    /** Parses "4.5" or "4,5"; returns null when empty or invalid. */
    static Double parse(EditText e) {
        String s = e.getText().toString().trim().replace(',', '.');
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static LinearLayout dialogBody(Context c) {
        LinearLayout l = column(c);
        int p = dp(c, 20);
        l.setPadding(p, dp(c, 8), p, 0);
        return l;
    }
}
