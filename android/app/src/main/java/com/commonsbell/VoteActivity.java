package com.commonsbell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Cast your vote" — a swipe deck of ballot cards. Each card shows one bill plus
 * a single benefit and a single downside, so you can decide Aye or No at a glance.
 * Swipe right = Aye, swipe left = No (buttons are a fallback). After the deck you
 * "load more" older votes or see your results: how you line up against each party,
 * plus share and reset.
 */
public class VoteActivity extends Activity {

    private static final String PREFS = "cast_votes";
    private static final int MODE_DECK = 0;
    private static final int MODE_END = 1;
    private static final int MODE_RESULTS = 2;

    private SharedPreferences prefs;
    private final List<DataFetcher.Vote> votes = new ArrayList<>(); // oldest -> newest
    private final Map<String, DataFetcher.Vote> voteMap = new HashMap<>();
    private int index = 0;
    private int mode = MODE_DECK;

    private LinearLayout body;
    private FrameLayout cardHost;
    private TextView progressView;
    private boolean busy = false;

    private int constituencyId = DataFetcher.DEFAULT_CONSTITUENCY_ID;
    private LocalDate pageEnd; // upper bound for the next "load more" window

    private static final int AYE_GREEN = Color.parseColor("#15803D");
    private static final int NO_RED = Color.parseColor("#B91C1C");
    private static final int AMBER = Color.parseColor("#B45309");

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
        double pct() { return comparable == 0 ? 0.0 : agree * 100.0 / comparable; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        constituencyId = getSharedPreferences("commonsbell", Context.MODE_PRIVATE)
                .getInt("constituency_id", DataFetcher.DEFAULT_CONSTITUENCY_ID);

        String json = getIntent().getStringExtra("snapshot");
        DataFetcher.Snapshot snap = json == null ? null : DataFetcher.snapshotFromJson(json);
        if (snap == null) snap = Cache.load(this);
        if (snap != null && snap.votes != null) {
            votes.addAll(snap.votes);
            Collections.reverse(votes); // oldest first
            for (DataFetcher.Vote v : votes) {
                if (v.documentId != null && !"null".equals(v.documentId) && !v.documentId.isEmpty()) {
                    voteMap.put(v.documentId, v);
                }
            }
        }
        pageEnd = LocalDate.now().minusDays(45);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F6F7F9"));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Ui.ACCENT);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView back = tv("‹ Back", Color.WHITE, 16f, Typeface.BOLD);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(back);
        TextView title = tv("Cast your vote", Color.WHITE, 17f, Typeface.BOLD);
        title.setPadding(dp(16), 0, 0, 0);
        header.addView(title);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(spacer);

        TextView resetBtn = tv("Reset", Color.WHITE, 14f, Typeface.BOLD);
        resetBtn.setPadding(dp(12), dp(8), dp(4), dp(8));
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reset(); }
        });
        header.addView(resetBtn);
        root.addView(header);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(body);

        setContentView(root);
        enterDeck();
    }

    // ---------------------------------------------------------------------
    // Screen modes
    // ---------------------------------------------------------------------
    private void enterDeck() {
        mode = MODE_DECK;
        body.removeAllViews();

        progressView = tv("", Ui.MUTED, 12f, Typeface.BOLD);
        progressView.setPadding(dp(16), dp(10), dp(16), 0);
        body.addView(progressView);

        cardHost = new FrameLayout(this);
        cardHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        cardHost.setClipToPadding(false);
        body.addView(cardHost);
        attachSwipe(cardHost);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(dp(20), dp(8), dp(20), dp(20));
        Button no = voteButton("NO", NO_RED);
        no.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { decide("NO"); }
        });
        Button aye = voteButton("AYE", AYE_GREEN);
        aye.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { decide("AYE"); }
        });
        buttons.addView(no);
        LinearLayout gap = new LinearLayout(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(16), 0));
        buttons.addView(gap);
        buttons.addView(aye);
        body.addView(buttons);

        if (votes.isEmpty()) {
            body.removeAllViews();
            TextView empty = tv("No votes to review yet.", Ui.MUTED, 15f, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(40), dp(24), dp(24));
            body.addView(empty);
            progressView = null;
            return;
        }
        resetCard();
        renderCard(votes.get(index));
        updateProgress();
    }

    private void enterEnd() {
        mode = MODE_END;
        body.removeAllViews();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView done = tv("🎉 You've swiped " + votes.size() + (votes.size() == 1 ? " bill" : " bills"),
                Ui.INK, 18f, Typeface.BOLD);
        done.setGravity(Gravity.CENTER);
        col.addView(done);

        TextView sub = tv("Load more older votes, or see how you line up with the parties.",
                Ui.MUTED, 13f, Typeface.NORMAL);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(20));
        col.addView(sub);

        Button loadMore = primaryButton("Load more");
        loadMore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadMore(); }
        });
        col.addView(loadMore);

        Button results = primaryButton("See results");
        results.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enterResults(); }
        });
        col.addView(results);

        body.addView(col);
    }

    private void enterResults() {
        mode = MODE_RESULTS;
        body.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(col);
        body.addView(scroll);

        col.addView(sectionLabel("YOUR VOTES"));
        List<Logged> entries = getLoggedEntries();
        TextView count = tv(entries.size() + (entries.size() == 1 ? " vote" : " votes") + " cast",
                Ui.INK, 20f, Typeface.BOLD);
        col.addView(count);

        // party comparison
        List<Logged> withData = new ArrayList<>();
        for (Logged e : entries) if (voteMap.containsKey(e.docId)) withData.add(e);
        col.addView(sectionLabel("HOW YOU LINE UP"));
        if (withData.size() < 3) {
            int need = 3 - withData.size();
            col.addView(tv("Cast " + need + " more vote" + (need == 1 ? "" : "s")
                    + " to compare against parties.", Ui.MUTED, 13f, Typeface.NORMAL));
        } else {
            List<PartyScore> ranked = computeScores(withData);
            if (ranked.isEmpty() || ranked.get(0).comparable == 0) {
                col.addView(tv("Not enough comparable votes yet.", Ui.MUTED, 13f, Typeface.NORMAL));
            } else {
                PartyScore best = ranked.get(0);
                if (best.pct() >= 50.0) {
                    col.addView(tv("Best match: " + best.party + "  ·  " + pctStr(best) + " agreement",
                            Ui.partyColor(best.party), 15f, Typeface.BOLD));
                } else {
                    col.addView(tv("No clear match", AMBER, 15f, Typeface.BOLD));
                    col.addView(tv("No party agrees with you a majority of the time — none currently matches how you vote.",
                            Ui.MUTED, 12f, Typeface.NORMAL));
                }
                for (PartyScore s : ranked) {
                    if (s.comparable == 0) continue;
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(6), 0, dp(2));
                    TextView name = tv(s.party, Ui.partyColor(s.party), 14f, Typeface.NORMAL);
                    name.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(name);
                    row.addView(attendancePill(pctStr(s) + " agreement"));
                    col.addView(row);
                }
            }
        }

        // logged votes + how your MLAs voted
        if (!entries.isEmpty()) {
            col.addView(sectionLabel("YOUR LOG"));
            col.addView(tv("Tap a vote to see how your MLAs voted.", Ui.MUTED, 11f, Typeface.NORMAL));
            for (Logged e : entries) col.addView(loggedCard(e));
        }

        // actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(20), 0, 0);
        Button share = secondaryButton("Share");
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareAllVotes(); }
        });
        Button reset = secondaryButton("Reset");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reset(); }
        });
        actions.addView(share);
        LinearLayout gap = new LinearLayout(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 0));
        actions.addView(gap);
        actions.addView(reset);
        col.addView(actions);
    }

    // ---------------------------------------------------------------------
    // Card rendering
    // ---------------------------------------------------------------------
    private void renderCard(DataFetcher.Vote v) {
        cardHost.removeAllViews();

        BillKnowledge.BillInfo bi = BillKnowledge.lookup(this, v.subject);
        String benefit = (bi == null || bi.benefits == null || bi.benefits.isEmpty())
                ? null : bi.benefits.get(0);
        String negative = (bi == null || bi.negatives == null || bi.negatives.isEmpty())
                ? null : bi.negatives.get(0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Ui.LINE);
        card.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), dp(8), dp(20), dp(8));
        lp.gravity = Gravity.CENTER;
        card.setLayoutParams(lp);

        card.addView(tv(v.subject, Ui.INK, 17f, Typeface.BOLD));
        String sub = v.date;
        if (v.type != null && !v.type.isEmpty()) sub = (sub == null ? "" : sub + "  ·  ") + v.type;
        if (sub != null && !sub.isEmpty()) card.addView(tv(sub, Ui.MUTED, 12f, Typeface.NORMAL));

        // previously cast?
        String prev = (v.documentId == null || "null".equals(v.documentId))
                ? null : prefs.getString(v.documentId, null);
        if (prev != null) {
            card.addView(badge("Previously: " + prev, prev.equals("AYE") ? AYE_GREEN : NO_RED));
        }

        if (benefit != null || negative != null) {
            if (benefit != null) card.addView(line("✓  " + benefit, AYE_GREEN));
            if (negative != null) card.addView(line("✗  " + negative, NO_RED));
        } else if (v.description != null && !v.description.isEmpty()) {
            card.addView(line(Ui.truncate(v.description, 180), Ui.MUTED));
        } else {
            card.addView(line("No summary available for this vote.", Ui.MUTED));
        }

        TextView hint = tv("←  Swipe left = No        Aye = Swipe right  →", Ui.MUTED, 11f, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(16), 0, 0);
        card.addView(hint);

        cardHost.addView(card);
    }

    private void resetCard() {
        cardHost.setTranslationX(0f);
        cardHost.setRotation(0f);
        cardHost.setAlpha(1f);
    }

    private void updateProgress() {
        if (progressView != null) {
            progressView.setText((index + 1) + " of " + votes.size());
        }
    }

    // ---------------------------------------------------------------------
    // Swipe + decision
    // ---------------------------------------------------------------------
    private void attachSwipe(final View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            float startX = 0f;

            @Override public boolean onTouch(View v, MotionEvent e) {
                if (busy || mode != MODE_DECK || votes.isEmpty() || index >= votes.size()) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getX();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - startX;
                        target.setTranslationX(dx);
                        target.setRotation(dx / 30f);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dx = target.getTranslationX();
                        float threshold = target.getWidth() * 0.28f;
                        if (Math.abs(dx) > threshold) {
                            decide(dx > 0 ? "AYE" : "NO");
                        } else {
                            target.animate().translationX(0f).rotation(0f).setDuration(140).start();
                        }
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void decide(String choice) {
        if (busy || mode != MODE_DECK || index >= votes.size()) return;
        busy = true;
        DataFetcher.Vote v = votes.get(index);
        record(v, choice);

        final boolean right = "AYE".equals(choice);
        float targetX = right ? cardHost.getWidth() * 1.6f : -cardHost.getWidth() * 1.6f;
        cardHost.animate().translationX(targetX).rotation(right ? 18f : -18f).alpha(0f)
                .setDuration(200)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        index++;
                        if (index >= votes.size()) {
                            enterEnd();
                        } else {
                            resetCard();
                            renderCard(votes.get(index));
                            updateProgress();
                        }
                    }
                }).start();
    }

    private void record(DataFetcher.Vote v, String choice) {
        String docId = (v.documentId == null || "null".equals(v.documentId)) ? "" : v.documentId;
        if (docId.isEmpty()) return;
        SharedPreferences.Editor ed = prefs.edit().putString(docId, choice);
        if (v.subject != null) ed.putString("subject_" + docId, v.subject);
        if (v.date != null) ed.putString("date_" + docId, v.date);
        ed.apply();
    }

    // ---------------------------------------------------------------------
    // Load more / reset / share
    // ---------------------------------------------------------------------
    private void loadMore() {
        mode = MODE_DECK;
        body.removeAllViews();
        LinearLayout loading = new LinearLayout(this);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(24), dp(40), dp(24), dp(24));
        loading.addView(tv("Loading more votes…", Ui.MUTED, 14f, Typeface.NORMAL));
        body.addView(loading);

        final LocalDate end = pageEnd;
        final LocalDate start = pageEnd.minusDays(60);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DataFetcher.Vote> more =
                            new DataFetcher().fetchMoreVotes(constituencyId, start, end);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (more == null || more.isEmpty()) {
                                enterEnd();
                                return;
                            }
                            Collections.reverse(more); // oldest first
                            for (DataFetcher.Vote v : more) {
                                if (v.documentId != null && !"null".equals(v.documentId)
                                        && !v.documentId.isEmpty()) {
                                    voteMap.put(v.documentId, v);
                                }
                            }
                            votes.addAll(0, more);
                            pageEnd = start;
                            index = 0;
                            enterDeck();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { enterEnd(); }
                    });
                }
            }
        }).start();
    }

    private void reset() {
        prefs.edit().clear().apply();
        index = 0;
        enterDeck();
    }

    private void share(String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception ignored) { }
    }

    private void shareAllVotes() {
        List<Logged> entries = getLoggedEntries();
        if (entries.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("My Commons Bell votes (").append(entries.size()).append("):\n");
        for (Logged e : entries) {
            String subject = (e.subject == null || e.subject.isEmpty())
                    ? "Division " + e.docId : Ui.truncate(e.subject, 70);
            sb.append("• ").append(subject).append(" — ").append(e.choice).append("\n");
        }
        sb.append("\nGet Commons Bell: https://github.com/donkza374/commons-bell/releases");
        share(sb.toString());
    }

    // ---------------------------------------------------------------------
    // Vote log + party comparison
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
        Collections.sort(out, new Comparator<Logged>() {
            @Override public int compare(Logged a, Logged b) {
                String da = a.date == null ? "" : a.date;
                String db = b.date == null ? "" : b.date;
                return db.compareTo(da); // newest first
            }
        });
        return out;
    }

    private List<PartyScore> computeScores(List<Logged> withData) {
        Map<String, PartyScore> byParty = new HashMap<>();
        for (Logged e : withData) {
            DataFetcher.Vote v = voteMap.get(e.docId);
            if (v == null || v.myVotes == null) continue;
            Map<String, Integer> stance = new HashMap<>();
            for (DataFetcher.MyVote mv : v.myVotes) {
                if (mv.party == null || mv.party.isEmpty()) continue;
                Integer cur = stance.get(mv.party);
                if (cur == null) cur = 0;
                stance.put(mv.party, cur + Ui.classifyVote(mv.vote));
            }
            int myChoice = "AYE".equals(e.choice) ? 1 : -1;
            for (Map.Entry<String, Integer> se : stance.entrySet()) {
                int pos = se.getValue() == null ? 0 : Integer.signum(se.getValue());
                if (pos == 0) continue;
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
                int c = Double.compare(b.pct(), a.pct());
                if (c != 0) return c;
                return Integer.compare(b.comparable, a.comparable);
            }
        });
        return ranked;
    }

    private String pctStr(PartyScore s) {
        return (int) Math.round(s.pct()) + "%";
    }

    private View loggedCard(final Logged e) {
        final DataFetcher.Vote v = voteMap.get(e.docId);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Ui.LINE);
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        String subject = (e.subject == null || e.subject.isEmpty()) ? e.docId : Ui.truncate(e.subject, 44);
        TextView sub = tv(subject, Ui.INK, 13f, Typeface.NORMAL);
        sub.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(sub);
        header.addView(chip(e.choice, "AYE".equals(e.choice) ? AYE_GREEN : NO_RED));

        if (v != null && v.myVotes != null && !v.myVotes.isEmpty()) {
            final TextView arrow = tv("▸", Ui.MUTED, 14f, Typeface.BOLD);
            arrow.setPadding(dp(6), 0, 0, 0);
            header.addView(arrow);
            card.addView(header);

            final LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setVisibility(View.GONE);
            body.setPadding(0, dp(6), 0, 0);
            for (DataFetcher.MyVote mv : v.myVotes) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(2), 0, dp(2));
                TextView name = tv(mv.name, Ui.partyColor(mv.party), 12f, Typeface.NORMAL);
                name.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(name);
                row.addView(chip(Ui.voteLabel(mv.vote), Ui.voteColor(mv.vote)));
                body.addView(row);
            }
            card.addView(body);

            final boolean[] open = {false};
            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View vv) {
                    open[0] = !open[0];
                    body.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                    arrow.setText(open[0] ? "▾" : "▸");
                }
            });
        } else {
            card.addView(header);
        }
        return card;
    }

    // ---------------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------------
    private TextView line(String text, int color) {
        TextView t = tv(text, color, 13f, Typeface.NORMAL);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private TextView badge(String label, int color) {
        TextView t = chip(label, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView sectionLabel(String text) {
        TextView t = tv(text, Ui.MUTED, 11f, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView attendancePill(String text) {
        return chip(text, Ui.MUTED);
    }

    private Button voteButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(56), 1f));
        return b;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.ACCENT);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(240), dp(52));
        lp.bottomMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Ui.ACCENT);
        b.setTextSize(14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(1), Ui.ACCENT);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private TextView chip(String label, int color) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        int padH = dp(8), padV = dp(2);
        t.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
        return t;
    }

    private TextView tv(String text, int color, float sp, int style) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
