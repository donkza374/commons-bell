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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Cast your vote" — go through recent divisions (older -> newer), give your
 * Aye/No verdict, then see how your 5 MLAs actually voted and what was said.
 * Verdicts are stored locally on-device (no server / login yet).
 *
 * Also keeps a "vote log" of your previous verdicts and, once you've cast at
 * least 3 votes, recommends the party whose MLAs most often voted the same
 * way as you (recomputed live on every new vote). If no party matches you a
 * majority of the time it flags "no clear match" instead.
 */
public class VoteActivity extends Activity {

    private static final String PREFS = "cast_votes";

    private LinearLayout list;
    private LinearLayout recCard;
    private SharedPreferences prefs;

    private DataFetcher.Snapshot snap;
    private final Map<String, DataFetcher.Vote> voteMap = new HashMap<>();

    private static class Logged {
        String docId;
        String subject;
        String date;
        String choice;
    }

    private static class PartyScore {
        String party;
        int agree;
        int comparable;

        double pct() {
            return comparable == 0 ? 0.0 : agree * 100.0 / comparable;
        }
    }

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
        header.setBackgroundColor(Ui.ACCENT);
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
                Ui.MUTED, 12f, Typeface.NORMAL);
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
        snap = null;
        String json = getIntent().getStringExtra("snapshot");
        if (json != null) snap = DataFetcher.snapshotFromJson(json);
        if (snap == null) snap = Cache.load(this);

        voteMap.clear();
        if (snap != null && snap.votes != null) {
            for (DataFetcher.Vote v : snap.votes) {
                if (v.documentId != null && !"null".equals(v.documentId) && !v.documentId.isEmpty()) {
                    voteMap.put(v.documentId, v);
                }
            }
        }

        list.removeAllViews();

        // vote log + recommendation (rebuilt live as you cast)
        recCard = card(new LinearLayout(this));
        recCard.setOrientation(LinearLayout.VERTICAL);
        list.addView(recCard);
        renderRecommendation();

        if (snap == null || snap.votes == null || snap.votes.isEmpty()) {
            TextView msg = tv("No recent votes to review.", Ui.MUTED, 14f, Typeface.NORMAL);
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

    // ---------------------------------------------------------------------
    // Vote log + party recommendation
    // ---------------------------------------------------------------------
    private List<Logged> getLoggedEntries() {
        List<Logged> out = new ArrayList<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("subject_") || key.startsWith("date_")) continue;
            if (e.getValue() == null) continue;
            String choice = e.getValue().toString();
            if (!"AYE".equals(choice) && !"NO".equals(choice)) continue;
            Logged l = new Logged();
            l.docId = key;
            l.choice = choice;
            l.subject = prefs.getString("subject_" + key, "");
            l.date = prefs.getString("date_" + key, "");
            out.add(l);
        }
        // newest first
        Collections.sort(out, new Comparator<Logged>() {
            @Override public int compare(Logged a, Logged b) {
                String da = a.date == null ? "" : a.date;
                String db = b.date == null ? "" : b.date;
                return db.compareTo(da);
            }
        });
        return out;
    }

    private void renderRecommendation() {
        if (recCard == null) return;
        recCard.removeAllViews();

        recCard.addView(tv("YOUR VOTE LOG", Ui.MUTED, 11f, Typeface.BOLD));

        final List<Logged> entries = getLoggedEntries();
        int total = entries.size();
        recCard.addView(tv("You've cast " + total + (total == 1 ? " vote" : " votes"),
                Ui.INK, 15f, Typeface.BOLD));

        // votes with live MLA breakdown (needed to compare against parties)
        List<Logged> withData = new ArrayList<>();
        for (Logged e : entries) {
            if (voteMap.containsKey(e.docId)) withData.add(e);
        }

        if (withData.size() >= 3) {
            List<PartyScore> ranked = computeScores(withData);
            if (ranked.isEmpty() || ranked.get(0).comparable == 0) {
                recCard.addView(tv("Not enough comparable votes yet.", Ui.MUTED, 13f, Typeface.NORMAL));
            } else {
                PartyScore best = ranked.get(0);
                if (best.pct() >= 50.0) {
                    TextView rec = tv("Best match: " + best.party + "  ·  "
                            + pctStr(best) + " agreement", Ui.partyColor(best.party), 14f, Typeface.BOLD);
                    rec.setPadding(0, dp(6), 0, 0);
                    recCard.addView(rec);
                } else {
                    TextView rec = tv("No clear match", Color.parseColor("#B45309"), 14f, Typeface.BOLD);
                    rec.setPadding(0, dp(6), 0, 0);
                    recCard.addView(rec);
                    recCard.addView(tv("No party agrees with you a majority of the time "
                            + "— none currently matches how you vote.", Ui.MUTED, 12f, Typeface.NORMAL));
                }

                // ranked breakdown (top 5)
                TextView hdr = tv("How you line up", Ui.MUTED, 11f, Typeface.BOLD);
                hdr.setPadding(0, dp(8), 0, dp(4));
                recCard.addView(hdr);
                for (PartyScore s : ranked) {
                    if (s.comparable == 0) continue;
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(2), 0, dp(2));
                    TextView name = tv(s.party, Ui.partyColor(s.party), 13f, Typeface.NORMAL);
                    name.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(name);
                    TextView pct = tv(pctStr(s) + " agreement", Ui.MUTED, 12f, Typeface.NORMAL);
                    row.addView(pct);
                    recCard.addView(row);
                }
            }
        } else {
            int need = 3 - withData.size();
            recCard.addView(tv("Cast " + need + " more vote" + (need == 1 ? "" : "s")
                    + " to get a party recommendation.", Ui.MUTED, 13f, Typeface.NORMAL));
        }

        // collapsible list of logged votes
        if (total > 0) {
            final LinearLayout log = new LinearLayout(this);
            log.setOrientation(LinearLayout.VERTICAL);
            log.setPadding(dp(4), dp(4), dp(4), 0);
            for (Logged e : entries) {
                log.addView(buildLoggedRow(e));
            }
            log.setVisibility(View.GONE);
            final TextView toggle = tv("▸ See your logged votes", Ui.ACCENT, 12f, Typeface.BOLD);
            toggle.setPadding(0, dp(8), 0, 0);
            recCard.addView(toggle);
            recCard.addView(log);
            final boolean[] open = {false};
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) {
                    open[0] = !open[0];
                    log.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                    toggle.setText(open[0] ? "▾ Hide logged votes" : "▸ See your logged votes");
                }
            });
        }
    }

    private View buildLoggedRow(Logged e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        String subject = (e.subject == null || e.subject.isEmpty()) ? e.docId : e.subject;
        TextView sub = tv(Ui.truncate(subject, 60), Ui.INK, 13f, Typeface.NORMAL);
        sub.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sub);
        int col = "AYE".equals(e.choice) ? Color.parseColor("#15803D") : Color.parseColor("#B91C1C");
        row.addView(chip(e.choice, col));
        return row;
    }

    private List<PartyScore> computeScores(List<Logged> withData) {
        Map<String, PartyScore> byParty = new HashMap<>();
        for (Logged e : withData) {
            DataFetcher.Vote v = voteMap.get(e.docId);
            if (v == null || v.myVotes == null) continue;
            // tally each party's stance on this division (majority of its MLAs)
            Map<String, Integer> stance = new HashMap<>(); // party -> +1 AYE, -1 NO, 0 none
            for (DataFetcher.MyVote mv : v.myVotes) {
                if (mv.party == null || mv.party.isEmpty()) continue;
                Integer cur = stance.get(mv.party);
                if (cur == null) cur = 0;
                stance.put(mv.party, cur + Ui.classifyVote(mv.vote));
            }
            int myChoice = "AYE".equals(e.choice) ? 1 : -1;
            for (Map.Entry<String, Integer> se : stance.entrySet()) {
                int pos = Integer.signum(se.getValue() == null ? 0 : se.getValue());
                if (pos == 0) continue; // split/absent => not comparable
                PartyScore ps = byParty.get(se.getKey());
                if (ps == null) {
                    ps = new PartyScore();
                    ps.party = se.getKey();
                    byParty.put(se.getKey(), ps);
                }
                ps.comparable++;
                if (pos == myChoice) ps.agree++;
            }
        }

        List<PartyScore> ranked = new ArrayList<>(byParty.values());
        Collections.sort(ranked, new Comparator<PartyScore>() {
            @Override public int compare(PartyScore a, PartyScore b) {
                int c = Double.compare(b.pct(), a.pct()); // higher agreement first
                if (c != 0) return c;
                return Integer.compare(b.comparable, a.comparable); // tie: more data first
            }
        });
        return ranked;
    }

    private String pctStr(PartyScore s) {
        return (int) Math.round(s.pct()) + "%";
    }

    // ---------------------------------------------------------------------
    // Vote cards
    // ---------------------------------------------------------------------
    private LinearLayout buildVoteCard(final DataFetcher.Vote v) {
        final LinearLayout card = card(new LinearLayout(this));
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(tv(v.subject, Ui.INK, 14f, Typeface.BOLD));
        String sub = v.date;
        if (v.type != null && !v.type.isEmpty()) sub = (sub == null ? "" : sub + "  ·  ") + v.type;
        if (sub != null && !sub.isEmpty()) card.addView(tv(sub, Ui.MUTED, 12f, Typeface.NORMAL));

        // "What's this bill?" dropdown (fine print)
        final BillKnowledge.BillInfo bi = BillKnowledge.lookup(this, v.subject);
        if (bi != null || (v.description != null && !v.description.isEmpty())) {
            final LinearLayout fp = buildFinePrint(v, bi);
            fp.setVisibility(View.GONE);
            final TextView toggle = tv("▸ What's this bill?", Ui.ACCENT, 12f, Typeface.BOLD);
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
                @Override public void onClick(View vv) { cast(docId, "AYE", v, resultArea); }
            });
            Button no = voteButton("NO", Color.parseColor("#B91C1C"));
            no.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) { cast(docId, "NO", v, resultArea); }
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
                      LinearLayout resultArea) {
        if (!docId.isEmpty()) {
            SharedPreferences.Editor ed = prefs.edit().putString(docId, choice);
            if (v.subject != null) ed.putString("subject_" + docId, v.subject);
            if (v.date != null) ed.putString("date_" + docId, v.date);
            ed.apply();
        }
        resultArea.removeAllViews();
        resultArea.addView(tv("You voted " + choice,
                choice.equals("AYE") ? Color.parseColor("#15803D") : Color.parseColor("#B91C1C"),
                13f, Typeface.BOLD));
        fillResults(v, resultArea);
        renderRecommendation(); // keep the log/recommendation live
    }

    private void fillResults(DataFetcher.Vote v, LinearLayout resultArea) {
        TextView how = tv("How your MLAs voted", Ui.MUTED, 11f, Typeface.BOLD);
        how.setPadding(0, dp(10), 0, dp(4));
        resultArea.addView(how);

        if (v.myVotes == null || v.myVotes.isEmpty()) {
            resultArea.addView(tv("No per-MLA breakdown available.", Ui.MUTED, 12f, Typeface.NORMAL));
        } else {
            for (DataFetcher.MyVote mv : v.myVotes) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(3), 0, dp(3));
                TextView who = tv(mv.name, Ui.partyColor(mv.party), 13f, Typeface.NORMAL);
                who.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(who);
                row.addView(chip(Ui.voteLabel(mv.vote), Ui.voteColor(mv.vote)));
                resultArea.addView(row);
            }
        }

        TextView link = tv("Read the official report ↗", Ui.ACCENT, 12f, Typeface.BOLD);
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

        TextView h = tv("BILL FINE PRINT", Ui.MUTED, 11f, Typeface.BOLD);
        h.setPadding(0, 0, 0, dp(4));
        b.addView(h);

        if (bi != null) {
            if (bi.summary != null && !bi.summary.isEmpty()) {
                b.addView(tv(bi.summary, Ui.INK, 13f, Typeface.NORMAL));
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
            TextView full = tv(Ui.truncate(v.description, 400), Ui.MUTED, 12f, Typeface.NORMAL);
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
        bg.setStroke(dp(1), Ui.LINE);
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

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }
}
