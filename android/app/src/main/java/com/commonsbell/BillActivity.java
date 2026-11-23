package com.commonsbell;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-page "Bill detail" view — title, stage/type/date, plain-English summary,
 * benefits, negatives and the raw motion text. Opened when a bill card is tapped.
 */
public class BillActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bill);

        Ui.setDark(getSharedPreferences("commonsbell", Context.MODE_PRIVATE)
                .getBoolean("dark", false));
        findViewById(R.id.bill_root).setBackgroundColor(Ui.BG);

        final View header = findViewById(R.id.bill_header);
        findViewById(R.id.bill_root).setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(
                    View v, android.view.WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                header.setPadding(dp(12), dp(12) + top + dp(Ui.topSpacing(BillActivity.this)), dp(12), dp(12));
                return insets;
            }
        });

        String title = getIntent().getStringExtra("title");
        final String type = getIntent().getStringExtra("type");
        final String date = getIntent().getStringExtra("date");
        final String text = getIntent().getStringExtra("text");
        if (title == null || title.trim().isEmpty()) title = "Bill";

        ((TextView) findViewById(R.id.bill_title_header)).setText(title);
        findViewById(R.id.bill_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        LinearLayout c = findViewById(R.id.bill_container);
        BillKnowledge.BillInfo bi = BillKnowledge.lookup(this,
                (title != null && !title.trim().isEmpty()) ? title : text);

        // title + meta card
        LinearLayout top = card();
        top.addView(tv(title, Ui.INK, 18f, Typeface.BOLD));
        String stage = Ui.billStage(title);
        StringBuilder sub = new StringBuilder();
        if (type != null && !type.isEmpty()) sub.append(type);
        if (stage != null) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(stage);
        }
        if (date != null && !date.isEmpty()) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(Ui.formatDate(date));
        }
        if (sub.length() > 0) top.addView(tv(sub.toString(), Ui.MUTED, 13f, Typeface.NORMAL));
        c.addView(top);

        int sections = 0;

        if (bi != null && bi.summary != null && !bi.summary.isEmpty()) {
            c.addView(section("WHAT IT'S ABOUT"));
            LinearLayout about = card();
            about.addView(tv(bi.summary, Ui.INK, 14f, Typeface.NORMAL));
            c.addView(about);
            sections++;
        }

        if (bi != null && bi.benefits != null && !bi.benefits.isEmpty()) {
            c.addView(section("SUPPORTERS SAY (BENEFITS)"));
            LinearLayout bCard = card();
            for (String s : bi.benefits) {
                if (s != null && !s.isEmpty()) bCard.addView(bullet(s, Ui.AYE));
            }
            c.addView(bCard);
            sections++;
        }

        if (bi != null && bi.negatives != null && !bi.negatives.isEmpty()) {
            c.addView(section("CRITICS SAY (NEGATIVES)"));
            LinearLayout nCard = card();
            for (String s : bi.negatives) {
                if (s != null && !s.isEmpty()) nCard.addView(bullet(s, Ui.NO));
            }
            c.addView(nCard);
            sections++;
        }

        if (text != null && !text.isEmpty()) {
            c.addView(section("FULL MOTION TEXT"));
            LinearLayout tCard = card();
            tCard.addView(tv(text, Ui.MUTED, 13f, Typeface.NORMAL));
            c.addView(tCard);
            sections++;
        }

        if (sections == 0) {
            c.addView(tv("No additional detail is available for this item yet.",
                    Ui.MUTED, 14f, Typeface.NORMAL));
        }
    }

    private TextView section(String t) {
        TextView v = tv(t, Ui.MUTED, 12f, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView bullet(String s, int color) {
        TextView t = tv("•  " + s, color, 14f, Typeface.NORMAL);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Ui.LINE);
        ll.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        ll.setLayoutParams(lp);
        return ll;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView tv(String text, int color, float sp, int style) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }
}
