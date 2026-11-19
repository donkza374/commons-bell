package com.commonsbell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Cast your vote" — go through recent divisions (older -> newer), give your
 * Aye/No verdict, then see how your 5 MLAs actually voted and what was said.
 * Verdicts are stored locally on-device (no server / login yet).
 */
public class VoteActivity extends Activity {

    private static final String PREFS = "cast_votes";

    private static final int C_ACCENT = Color.parseColor("#134E4A");
    private static final int C_INK = Color.parseColor("#1A2233");
    private static final int C_MUTED = Color.parseColor("#6B7280");
    private static final int C_LINE = Color.parseColor("#E5E7EB");

    private LinearLayout list;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F6F7F9"));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(C_ACCENT);
        header.setPadding(dp(16), dp(14), dp(8), dp(14));
        TextView back = tv("‹ Back", Color.WHITE, 16f, Typeface.BOLD);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(back);
        TextView title = tv("Cast your vote", Color.WHITE, 17f, Typeface.BOLD);
        title.setPadding(dp(16), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        TextView subtitle = tv("Older votes first — you say Aye or No, then see how your MLAs voted.",
                C_MUTED, 12f, Typeface.NORMAL);
        subtitle.setPadding(dp(16), dp(10), dp(16), dp(4));
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(16));
        scroll.addView(list);
        root.addView(scroll);

        setContentView(root);

        buildList();
    }

    private void buildList() {
        DataFetcher.Snapshot snap = null;
        String json = getIntent().getStringExtra("snapshot");
        if (json != null) snap = DataFetcher.snapshotFromJson(json);
        if (snap == null) snap = Cache.load(this);

        list.removeAllViews();
        if (snap == null || snap.votes == null || snap.votes.isEmpty()) {
            TextView msg = tv("No recent votes to review.", C_MUTED, 14f, Typeface.NORMAL);
            msg.setPadding(dp(8), dp(16), dp(8), dp(16));
            list.addView(msg);
            return;
        }

        // older -> newer
        List<DataFetcher.Vote> votes = new ArrayList<>(snap.votes);
        Collections.reverse(votes);

        for (DataFetcher.Vote v : votes) {
            list.addView(buildVoteCard(v));
        }
    }

    private LinearLayout buildVoteCard(final DataFetcher.Vote v) {
        final LinearLayout card = card(new LinearLayout(this));
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(tv(v.subject, C_INK, 14f, Typeface.BOLD));
        String sub = v.date;
        if (v.type != null && !v.type.isEmpty()) sub = (sub == null ? "" : sub + "  ·  ") + v.type;
        if (sub != null && !sub.isEmpty()) card.addView(tv(sub, C_MUTED, 12f, Typeface.NORMAL));

        // "What's this bill?" dropdown (fine print)
        final BillKnowledge.BillInfo bi = BillKnowledge.lookup(this, v.subject);
        if (bi != null || (v.description != null && !v.description.isEmpty())) {
            final LinearLayout fp = buildFinePrint(v, bi);
            fp.setVisibility(View.GONE);
            final TextView toggle = tv("▸ What's this bill?", C_ACCENT, 12f, Typeface.BOLD);
            toggle.setPadding(0, dp(6), 0, 0);
            card.addView(toggle);
            card.addView(fp);
            final boolean[] open = {false};
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) {
                    open[0] = !open[0];
                    fp.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                    toggle.setText(open[0] ? "▾ What's this bill?" : "▸ What's this bill?");
                }
            });
        }

        final String docId = (v.documentId == null || "null".equals(v.documentId)) ? "" : v.documentId;
        final String recorded = docId.isEmpty() ? null : prefs.getString(docId, null);

        // Aye / No buttons (or a reveal if already cast)
        final LinearLayout resultArea = new LinearLayout(this);
        resultArea.setOrientation(LinearLayout.VERTICAL);

        if (recorded == null) {
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, dp(10), 0, 0);

            Button aye = voteButton("AYE", Color.parseColor("#15803D"));
            aye.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) { cast(docId, "AYE", v, resultArea, card); }
            });
            Button no = voteButton("NO", Color.parseColor("#B91C1C"));
            no.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) { cast(docId, "NO", v, resultArea, card); }
            });
            btns.addView(aye);
            LinearLayout gap = new LinearLayout(this);
            gap.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 0));
            btns.addView(gap);
            btns.addView(no);
            card.addView(btns);
        } else {
            card.addView(tv("You voted " + (recorded.equals("AYE") ? "AYE" : "NO"),
                    recorded.equals("AYE") ? Color.parseColor("#15803D") : Color.parseColor("#B91C1C"),
                    13f, Typeface.BOLD));
        }

        card.addView(resultArea);

        if (recorded != null) {
            fillResults(v, resultArea);
        }

        return card;
    }

    private void cast(String docId, String choice, DataFetcher.Vote v,
                      LinearLayout resultArea, LinearLayout card) {
        if (!docId.isEmpty()) {
            prefs.edit().putString(docId, choice).apply();
        }
        resultArea.removeAllViews();
        resultArea.addView(tv("You voted " + choice,
                choice.equals("AYE") ? Color.parseColor("#15803D") : Color.parseColor("#B91C1C"),
                13f, Typeface.BOLD));
        fillResults(v, resultArea);
    }

    private void fillResults(DataFetcher.Vote v, LinearLayout resultArea) {
        TextView how = tv("How your MLAs voted", C_MUTED, 11f, Typeface.BOLD);
        how.setPadding(0, dp(10), 0, dp(4));
        resultArea.addView(how);

        if (v.myVotes == null || v.myVotes.isEmpty()) {
            resultArea.addView(tv("No per-MLA breakdown available.", C_MUTED, 12f, Typeface.NORMAL));
        } else {
            for (DataFetcher.MyVote mv : v.myVotes) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(3), 0, dp(3));
                TextView who = tv(mv.name, partyColor(mv.party), 13f, Typeface.NORMAL);
                who.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(who);
                row.addView(chip(voteLabel(mv.vote), voteColor(mv.vote)));
                resultArea.addView(row);
            }
        }

        TextView link = tv("Read the official report ↗", C_ACCENT, 12f, Typeface.BOLD);
        link.setPadding(0, dp(8), 0, 0);
        link.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View vv) {
                open("https://aims.niassembly.gov.uk/plenary/divisions.aspx");
            }
        });
        resultArea.addView(link);
    }

    private LinearLayout buildFinePrint(DataFetcher.Vote v, BillKnowledge.BillInfo bi) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F6F7F9"));
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);

        TextView h = tv("BILL FINE PRINT", C_MUTED, 11f, Typeface.BOLD);
        h.setPadding(0, 0, 0, dp(4));
        b.addView(h);

        if (bi != null) {
            if (bi.summary != null && !bi.summary.isEmpty()) {
                b.addView(tv(bi.summary, C_INK, 13f, Typeface.NORMAL));
            }
            if (bi.benefits != null) {
                for (String s : bi.benefits) {
                    if (s != null && !s.isEmpty()) {
                        TextView t = tv("• " + s, Color.parseColor("#15803D"), 12f, Typeface.NORMAL);
                        t.setPadding(0, dp(4), 0, 0);
                        b.addView(t);
                    }
                }
            }
            if (bi.negatives != null) {
                for (String s : bi.negatives) {
                    if (s != null && !s.isEmpty()) {
                        TextView t = tv("• " + s, Color.parseColor("#B91C1C"), 12f, Typeface.NORMAL);
                        t.setPadding(0, dp(4), 0, 0);
                        b.addView(t);
                    }
                }
            }
        }

        if (v.description != null && !v.description.isEmpty()) {
            TextView full = tv(truncate(v.description, 400), C_MUTED, 12f, Typeface.NORMAL);
            full.setPadding(0, dp(6), 0, 0);
            b.addView(full);
        }

        return b;
    }

    // ---------------------------------------------------------------------
    // Helpers (mirror MainActivity)
    // ---------------------------------------------------------------------
    private Button voteButton(String label, int color) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setTextColor(Color.WHITE);
        bt.setTextSize(15);
        bt.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        bt.setBackground(bg);
        bt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bt;
    }

    private LinearLayout card(LinearLayout ll) {
        ll.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), C_LINE);
        ll.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        ll.setLayoutParams(lp);
        return ll;
    }

    private TextView tv(String text, int color, float sp, int style) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private TextView chip(String label, int color) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(10);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        int padH = dp(7), padV = dp(2);
        t.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String voteLabel(String v) {
        if (v == null) return "—";
        String u = v.toUpperCase();
        if ("AYE".equals(u)) return "AYE";
        if ("NO".equals(u)) return "NO";
        if ("ABSTAINED".equals(u)) return "ABSTAINED";
        if ("ABSENT".equals(u)) return "ABSENT";
        return "—";
    }

    private static final String[] AYE_CODES = {
            "AYE", "AYE (PROXY)", "SIN", "SUPPORT", "TA", "FOR", "YES", "CONTENT"
    };
    private static final String[] NO_CODES = {
            "NO", "NO (PROXY)", "AGAINST", "NOES", "NOT CONTENT", "NIL"
    };

    private int voteColor(String v) {
        if (v == null) return C_MUTED;
        String u = v.toUpperCase();
        for (String c : AYE_CODES) if (u.contains(c)) return Color.parseColor("#15803D");
        for (String c : NO_CODES) if (u.contains(c)) return Color.parseColor("#B91C1C");
        if (u.contains("ABSTAIN")) return Color.parseColor("#B45309");
        if (u.contains("ABSENT")) return C_MUTED;
        return C_MUTED;
    }

    private int partyColor(String party) {
        if (party == null) return C_MUTED;
        String p = party.toLowerCase();
        if (p.contains("sinn")) return Color.parseColor("#008000");
        if (p.contains("dup")) return Color.parseColor("#D46A4A");
        if (p.contains("uup") || p.contains("ulster unionist")) return Color.parseColor("#48A5EE");
        if (p.contains("alliance")) return Color.parseColor("#D4A017");
        if (p.contains("tuv")) return Color.parseColor("#1F4E79");
        if (p.contains("sdlp")) return Color.parseColor("#2AA82A");
        if (p.equals("green") || p.contains("green party")) return Color.parseColor("#008000");
        return C_MUTED;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max).trim() + "…";
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }
}
