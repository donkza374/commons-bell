package com.commonsbell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String PREFS = "commonsbell";
    private static final String KEY_CONSTITUENCY = "constituency_id";

    private TextView statusView;
    private ProgressBar progress;
    private Button updateBtn;
    private Spinner spinner;
    private SwipeRefreshLayout swipe;
    private LinearLayout mlasContainer;
    private LinearLayout votesContainer;
    private View homePage;
    private LinearLayout homeContainer;
    private LinearLayout homeFeed;
    private boolean bellRung = false;
    private List<DataFetcher.Business> homeBills = new ArrayList<>();
    private int billsShownCount = 0;
    private LinearLayout billsContainer;
    private static final int BILLS_PAGE = 12;
    private View areaPage;
    private TextView navHome;
    private TextView navArea;
    private Button castVoteBtn;
    private EditText postcodeInput;
    private Button postcodeBtn;
    private LinearLayout upcomingContainer;
    private Spinner upcomingSpinner;
    private boolean upcomingMode = true; // true = next 7 days, false = previous 7 days
    private String currentAreaName = "North Antrim";

    private DataFetcher.Snapshot currentSnapshot;
    private Map<String, MemberBio.Info> memberBio;
    private Map<String, int[]> attendance;

    private final List<DataFetcher.Constituency> constituencies = new ArrayList<>();
    private boolean populatingSpinner = false;
    private int currentConstituencyId = DataFetcher.DEFAULT_CONSTITUENCY_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        updateBtn = findViewById(R.id.update_btn);
        spinner = findViewById(R.id.constituency_spinner);
        swipe = findViewById(R.id.swipe);
        mlasContainer = findViewById(R.id.mlas_container);
        votesContainer = findViewById(R.id.votes_container);
        homePage = findViewById(R.id.home_page);
        homeContainer = findViewById(R.id.home_container);
        areaPage = findViewById(R.id.area_page);
        navHome = findViewById(R.id.nav_home);
        navArea = findViewById(R.id.nav_area);
        castVoteBtn = findViewById(R.id.cast_vote_btn);
        postcodeInput = findViewById(R.id.postcode_input);
        postcodeBtn = findViewById(R.id.postcode_btn);
        upcomingContainer = findViewById(R.id.upcoming_container);
        upcomingSpinner = findViewById(R.id.upcoming_spinner);

        memberBio = MemberBio.load(this);

        currentConstituencyId = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CONSTITUENCY, DataFetcher.DEFAULT_CONSTITUENCY_ID);

        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(false); loadUpcoming(); }
        });

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (populatingSpinner || pos < 0 || pos >= constituencies.size()) return;
                DataFetcher.Constituency c = constituencies.get(pos);
                int cid = parseIntSafe(c.id, DataFetcher.DEFAULT_CONSTITUENCY_ID);
                if (cid == currentConstituencyId) return;
                currentConstituencyId = cid;
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(KEY_CONSTITUENCY, cid).apply();
                refresh(false);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        swipe.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override public void onRefresh() { refresh(true); loadUpcoming(); }
        });

        scheduleBackgroundRefresh();
        requestNotificationPermission();

        // show cached data immediately (if it matches the selected area), then refresh
        DataFetcher.Snapshot cached = Cache.load(this);
        if (cached != null && !cached.mlas.isEmpty()) {
            render(cached);
            String ago = timeAgo(Cache.lastRefresh(this));
            setStatus("Cached · updated " + ago + " · refreshing…", false);
        }

        loadConstituencies();

        navHome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHome(); }
        });
        navArea.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showArea(); }
        });

        castVoteBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openCastVote(); }
        });

        postcodeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPostcodeLookup(); }
        });
        postcodeInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView tv, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    doPostcodeLookup();
                    return true;
                }
                return false;
            }
        });

        ArrayAdapter<String> upAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.coming_up_next), getString(R.string.coming_up_prev)});
        upAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        upcomingSpinner.setAdapter(upAdapter);
        upcomingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                boolean next = (pos == 0);
                if (next == upcomingMode) return;
                upcomingMode = next;
                loadUpcoming();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        showHome();
        loadHomeData();
        loadUpcoming();
    }

    // -------------------------------------------------------------------------
    // Constituency picker
    // -------------------------------------------------------------------------
    private void loadConstituencies() {
        setLoading(true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DataFetcher.Constituency> list =
                            new DataFetcher().getConstituencies();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            constituencies.clear();
                            constituencies.addAll(list);
                            populateSpinner();
                            refresh(false);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setLoading(false);
                            setStatus("Couldn't reach the NI Assembly. Check your connection and try again.",
                                    true);
                            populateSpinner();
                        }
                    });
                }
            }
        }).start();
    }

    private void populateSpinner() {
        populatingSpinner = true;
        List<String> names = new ArrayList<>();
        for (DataFetcher.Constituency c : constituencies) names.add(c.name);
        if (names.isEmpty()) {
            names.add("North Antrim");
            DataFetcher.Constituency d = new DataFetcher.Constituency();
            d.id = String.valueOf(DataFetcher.DEFAULT_CONSTITUENCY_ID);
            d.name = "North Antrim";
            constituencies.add(d);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        int idx = 0;
        for (int i = 0; i < constituencies.size(); i++) {
            if (parseIntSafe(constituencies.get(i).id, -1) == currentConstituencyId) {
                idx = i;
                break;
            }
        }
        spinner.setSelection(idx, false);
        populatingSpinner = false;
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------
    private void refresh(final boolean fromPull) {
        setLoading(true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final DataFetcher.Snapshot s =
                            new DataFetcher().fetch(currentConstituencyId);
                    Cache.save(MainActivity.this, s);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            render(s);
                            setStatus("Updated just now", false);
                            setLoading(false);
                            if (fromPull) swipe.setRefreshing(false);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setStatus("Couldn't reach the NI Assembly. Check your connection and try again.",
                                    true);
                            setLoading(false);
                            if (fromPull) swipe.setRefreshing(false);
                        }
                    });
                }
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        updateBtn.setEnabled(!loading);
        if (loading) {
            updateBtn.setText("Updating…");
            setStatus("Refreshing…", false);
        } else {
            updateBtn.setText(R.string.update);
        }
    }

    private void setStatus(String msg, boolean error) {
        statusView.setText(msg);
        statusView.setTextColor(error ? Color.parseColor("#B91C1C") : Ui.ACCENT);
        statusView.setBackgroundColor(error ? Color.parseColor("#FDECEC") : Color.parseColor("#E6F0EF"));
    }

    // -------------------------------------------------------------------------
    // Schedule background refresh (every 3 hours)
    // -------------------------------------------------------------------------
    private void scheduleBackgroundRefresh() {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                RefreshWorker.class, 3, TimeUnit.HOURS).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "refresh", ExistingPeriodicWorkPolicy.KEEP, req);

        PeriodicWorkRequest watch = new PeriodicWorkRequest.Builder(
                DivisionWatchWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "division_watch", ExistingPeriodicWorkPolicy.KEEP, watch);
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Navigation (Home / My Area)
    // -------------------------------------------------------------------------
    private void showHome() {
        homePage.setVisibility(View.VISIBLE);
        areaPage.setVisibility(View.GONE);
        setNav(true);
    }

    private void showArea() {
        homePage.setVisibility(View.GONE);
        areaPage.setVisibility(View.VISIBLE);
        setNav(false);
    }

    private void setNav(boolean homeActive) {
        navHome.setTextColor(homeActive ? Ui.ACCENT : Ui.MUTED);
        navHome.setTypeface(Typeface.DEFAULT, homeActive ? Typeface.BOLD : Typeface.NORMAL);
        navArea.setTextColor(homeActive ? Ui.MUTED : Ui.ACCENT);
        navArea.setTypeface(Typeface.DEFAULT, homeActive ? Typeface.NORMAL : Typeface.BOLD);
    }

    private void openCastVote() {
        DataFetcher.Snapshot s = currentSnapshot;
        if (s == null) s = Cache.load(this);
        Intent i = new Intent(this, VoteActivity.class);
        if (s != null) {
            i.putExtra("snapshot", DataFetcher.snapshotToJson(s).toString());
        }
        startActivity(i);
    }

    private void doPostcodeLookup() {
        String pc = postcodeInput.getText() == null ? "" : postcodeInput.getText().toString().trim();
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(postcodeInput.getWindowToken(), 0);
        } catch (Exception ignored) { }

        if (pc.isEmpty()) {
            setStatus("Enter a postcode first (e.g. BT53 6QP).", true);
            return;
        }
        String name = PostcodeLookup.constituencyFor(this, pc);
        if (name == null) {
            setStatus("Couldn't match that postcode. Pick your area from the list above.", true);
            return;
        }
        int idx = -1;
        for (int i = 0; i < constituencies.size(); i++) {
            DataFetcher.Constituency c = constituencies.get(i);
            if (name.equalsIgnoreCase(c.name)) { idx = i; break; }
        }
        if (idx < 0) {
            setStatus("Area found (" + name + ") but not in the picker list. Choose it from the dropdown.", true);
            return;
        }
        spinner.setSelection(idx, true);
        setStatus("Area set to " + name + " (best guess — confirm above).", false);
    }

    // -------------------------------------------------------------------------
    // Upcoming business (My Area) — Assembly-wide, "big votes" pinned on top
    // -------------------------------------------------------------------------
    private void loadUpcoming() {
        upcomingContainer.removeAllViews();
        upcomingContainer.addView(tv("Loading…", Ui.MUTED, 14f, Typeface.NORMAL));
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DataFetcher.Business> items = upcomingMode
                            ? new DataFetcher().getUpcomingBusiness(7)
                            : new DataFetcher().getRecentBusiness(7);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { renderUpcoming(items); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            upcomingContainer.removeAllViews();
                            upcomingContainer.addView(tv("Couldn't load upcoming business.",
                                    Ui.MUTED, 14f, Typeface.NORMAL));
                        }
                    });
                }
            }
        }).start();
    }

    private void renderUpcoming(List<DataFetcher.Business> items) {
        upcomingContainer.removeAllViews();
        if (items == null || items.isEmpty()) {
            upcomingContainer.addView(tv(upcomingMode
                    ? "Nothing tabled for the coming days yet."
                    : "No business in the last 7 days.", Ui.MUTED, 14f, Typeface.NORMAL));
            return;
        }
        final List<DataFetcher.Business> list = new ArrayList<>(items);
        Collections.sort(list, new java.util.Comparator<DataFetcher.Business>() {
            @Override public int compare(DataFetcher.Business a, DataFetcher.Business b) {
                boolean ba = Ui.isBigVote(a.title, a.type,
                        BillKnowledge.lookup(MainActivity.this, a.title));
                boolean bb = Ui.isBigVote(b.title, b.type,
                        BillKnowledge.lookup(MainActivity.this, b.title));
                if (ba != bb) return ba ? -1 : 1;
                String da = a.date == null ? "" : a.date;
                String db = b.date == null ? "" : b.date;
                return da.compareTo(db);
            }
        });
        for (DataFetcher.Business b : list) {
            upcomingContainer.addView(buildBusinessCard(b, true));
        }
    }

    // -------------------------------------------------------------------------
    // Home page
    // -------------------------------------------------------------------------
    private void loadHomeData() {
        showHomeLoading();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<DataFetcher.DiaryItem> diary = new DataFetcher().getBusinessDiary(7);
                    final List<DataFetcher.Business> bills = new DataFetcher().getBillsInProgress();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { renderHome(diary, bills); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            renderHomeError("Couldn't reach the Assembly.");
                        }
                    });
                }
            }
        }).start();
    }

    private void showHomeLoading() {
        homeContainer.removeAllViews();
        homeContainer.addView(buildBell());
        ensureHomeFeed();
        homeFeed.addView(tv("Loading…", Ui.MUTED, 14f, Typeface.NORMAL));
    }

    private void renderHome(List<DataFetcher.DiaryItem> diary, List<DataFetcher.Business> bills) {
        homeContainer.removeAllViews();
        homeContainer.addView(buildBell());
        ensureHomeFeed();

        homeFeed.addView(sectionHeader("WHAT'S ON TODAY"));
        if (diary == null || diary.isEmpty()) {
            homeFeed.addView(tv("Nothing scheduled in the coming days.", Ui.MUTED, 14f, Typeface.NORMAL));
        } else {
            int shown = 0;
            String lastDay = "";
            for (DataFetcher.DiaryItem d : diary) {
                if (shown++ >= 15) break;
                String day = d.eventDate == null ? "" : d.eventDate;
                if (!day.isEmpty() && !day.equals(lastDay)) {
                    lastDay = day;
                    homeFeed.addView(dateHeader(day, d.weekday));
                }
                homeFeed.addView(buildDiaryCard(d));
            }
        }

        homeFeed.addView(sectionHeader("BILLS IN PROGRESS"));
        billsContainer = new LinearLayout(this);
        billsContainer.setOrientation(LinearLayout.VERTICAL);
        homeFeed.addView(billsContainer);
        homeBills = (bills == null) ? new ArrayList<DataFetcher.Business>() : new ArrayList<>(bills);
        billsShownCount = Math.min(BILLS_PAGE, homeBills.size());
        fillBillsContainer();
    }

    private void fillBillsContainer() {
        billsContainer.removeAllViews();
        if (homeBills.isEmpty()) {
            billsContainer.addView(tv("No bills in progress found.", Ui.MUTED, 14f, Typeface.NORMAL));
            return;
        }
        for (int i = 0; i < billsShownCount && i < homeBills.size(); i++) {
            billsContainer.addView(buildBusinessCard(homeBills.get(i), false));
        }
        if (billsShownCount < homeBills.size()) {
            final int remaining = homeBills.size() - billsShownCount;
            Button more = new Button(this);
            more.setText("Load more bills (" + remaining + " more)");
            more.setTextColor(Ui.ACCENT);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), Ui.ACCENT);
            bg.setCornerRadius(dp(10));
            more.setBackground(bg);
            more.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    billsShownCount = Math.min(billsShownCount + BILLS_PAGE, homeBills.size());
                    fillBillsContainer();
                }
            });
            billsContainer.addView(more);
        }
    }

    private void renderHomeError(String msg) {
        homeContainer.removeAllViews();
        homeContainer.addView(buildBell());
        ensureHomeFeed();
        homeFeed.addView(tv(msg, Ui.MUTED, 14f, Typeface.NORMAL));
        TextView retry = tv("Tap to retry", Ui.ACCENT, 13f, Typeface.BOLD);
        retry.setPadding(0, dp(10), 0, 0);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadHomeData(); }
        });
        homeFeed.addView(retry);
    }

    private void ensureHomeFeed() {
        homeFeed = new LinearLayout(this);
        homeFeed.setOrientation(LinearLayout.VERTICAL);
        homeFeed.setVisibility(bellRung ? View.VISIBLE : View.GONE);
        homeContainer.addView(homeFeed);
    }

    private LinearLayout buildBell() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(dp(8), dp(28), dp(8), dp(24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        wrap.setLayoutParams(lp);

        TextView bell = tv("🔔", Ui.ACCENT, 84f, Typeface.NORMAL);
        bell.setGravity(Gravity.CENTER);
        bell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ringBell(); }
        });
        wrap.addView(bell);
        return wrap;
    }

    private void ringBell() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignore) { }
        playDong();
        bellRung = true;
        if (homeFeed != null) homeFeed.setVisibility(View.VISIBLE);
    }

    /** Synthesise a low, decaying bell "dong" (no audio asset needed). */
    private void playDong() {
        try {
            int sampleRate = 44100;
            double duration = 1.4;
            int n = (int) (sampleRate * duration);
            short[] buf = new short[n];
            double[] freqs = {196.0, 294.0, 392.0, 494.0};
            double[] amps = {1.0, 0.55, 0.35, 0.2};
            for (int i = 0; i < n; i++) {
                double t = (double) i / sampleRate;
                double env = Math.exp(-3.0 * t);
                double s = 0.0;
                for (int k = 0; k < freqs.length; k++) {
                    s += amps[k] * Math.sin(2.0 * Math.PI * freqs[k] * t);
                }
                buf[i] = (short) (s * env * 12000.0);
            }
            final AudioTrack track = new AudioTrack(
                    AudioManager.STREAM_NOTIFICATION, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    n * 2, AudioTrack.MODE_STATIC);
            track.write(buf, 0, n);
            track.play();
            new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(1600); track.release(); } catch (Exception ignored) { }
                }
            }).start();
        } catch (Exception ignore) { }
    }

    private TextView sectionHeader(String text) {
        TextView t = tv(text, Ui.MUTED, 12f, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout buildDiaryCard(DataFetcher.DiaryItem d) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = tv((d.startTime == null || d.startTime.isEmpty()) ? "--:--" : d.startTime,
                Ui.INK, 15f, Typeface.BOLD);
        time.setPadding(0, 0, dp(12), 0);
        row.addView(time);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(tv(d.organisationName, Ui.INK, 14f, Typeface.BOLD));
        String sub = d.eventType == null ? "" : d.eventType;
        if (d.locationRoom != null && !d.locationRoom.isEmpty()) sub += "  ·  " + d.locationRoom;
        if (d.weekday != null && !d.weekday.isEmpty()) sub += "  ·  " + d.weekday;
        col.addView(tv(sub, Ui.MUTED, 12f, Typeface.NORMAL));
        row.addView(col);

        String et = d.eventType == null ? "" : d.eventType.toLowerCase();
        if (et.contains("sitting") || et.contains("plenary")) {
            row.addView(tag("Plenary", Ui.ACCENT));
        } else if (et.contains("committee")) {
            row.addView(tag("Committee", Color.parseColor("#0E7490")));
        }
        card.addView(row);
        return card;
    }

    private LinearLayout buildBusinessCard(final DataFetcher.Business b, boolean highlight) {
        LinearLayout card = card();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final BillKnowledge.BillInfo bi = BillKnowledge.lookup(this,
                (b.title != null && !b.title.isEmpty()) ? b.title : b.text);

        left.addView(tv(b.title, Ui.INK, 14f, Typeface.BOLD));
        String stage = Ui.billStage(b.title);
        String sub = (b.type == null || b.type.isEmpty()) ? "" : b.type;
        if (stage != null) {
            sub = sub.isEmpty() ? stage : stage + "  ·  " + sub;
        }
        if (b.date != null && !b.date.isEmpty()) {
            sub = sub.isEmpty() ? Ui.formatDate(b.date) : sub + "  ·  " + Ui.formatDate(b.date);
        }
        if (!sub.isEmpty()) left.addView(tv(sub, Ui.MUTED, 12f, Typeface.NORMAL));
        if (highlight && Ui.isBigVote(
                (b.title != null && !b.title.isEmpty()) ? b.title : "", b.type, bi)) {
            left.addView(badge("★ Big vote", Color.parseColor("#B45309")));
        }
        header.addView(left);

        boolean hasBody = (b.text != null && !b.text.isEmpty()) || bi != null;

        if (hasBody) {
            final TextView arrow = tv("▸", Ui.MUTED, 16f, Typeface.BOLD);
            header.addView(arrow);
            card.addView(header);
            final LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setVisibility(View.GONE);
            body.setPadding(dp(4), dp(8), dp(4), 0);

            if (bi != null) {
                TextView h = tv("BILL FINE PRINT", Ui.MUTED, 11f, Typeface.BOLD);
                h.setPadding(0, 0, 0, dp(4));
                body.addView(h);
                if (bi.summary != null && !bi.summary.isEmpty()) {
                    body.addView(tv(bi.summary, Ui.INK, 13f, Typeface.NORMAL));
                }
                if (bi.benefits != null) {
                    for (String s : bi.benefits) {
                        if (s != null && !s.isEmpty()) {
                            TextView t = tv("• " + s, Color.parseColor("#15803D"), 12f, Typeface.NORMAL);
                            t.setPadding(0, dp(4), 0, 0);
                            body.addView(t);
                        }
                    }
                }
                if (bi.negatives != null) {
                    for (String s : bi.negatives) {
                        if (s != null && !s.isEmpty()) {
                            TextView t = tv("• " + s, Color.parseColor("#B91C1C"), 12f, Typeface.NORMAL);
                            t.setPadding(0, dp(4), 0, 0);
                            body.addView(t);
                        }
                    }
                }
            }
            if (b.text != null && !b.text.isEmpty()) {
                TextView raw = tv(Ui.truncate(b.text, 400), Ui.MUTED, 12f, Typeface.NORMAL);
                raw.setPadding(0, dp(6), 0, 0);
                body.addView(raw);
            }
            card.addView(body);

            final boolean[] open = {false};
            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
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

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------
    private void render(DataFetcher.Snapshot s) {
        currentSnapshot = s;
        if (s.constituencyName != null && !s.constituencyName.isEmpty()) {
            currentAreaName = s.constituencyName;
        }
        attendance = computeAttendance(s.votes);
        Map<String, List<DataFetcher.Statement>> byName = groupStatements(s.statements);
        renderMlas(s.mlas, byName);
        renderVotes(s.votes);
    }

    /** name -> {voted, total} across all divisions with per-member data. */
    private Map<String, int[]> computeAttendance(List<DataFetcher.Vote> votes) {
        Map<String, int[]> m = new HashMap<>();
        if (votes == null) return m;
        for (DataFetcher.Vote v : votes) {
            for (DataFetcher.MyVote mv : v.myVotes) {
                if (mv.vote == null) continue;
                String u = mv.vote.toUpperCase();
                if (u.contains("UNKNOWN")) continue;
                int[] a = m.get(mv.name);
                if (a == null) { a = new int[2]; m.put(mv.name, a); }
                a[1]++;
                if (!u.contains("ABSENT")) a[0]++;
            }
        }
        return m;
    }

    // -------------------------------------------------------------------------
    // MLAs (with their statements collapsed into each card)
    // -------------------------------------------------------------------------
    private Map<String, List<DataFetcher.Statement>> groupStatements(List<DataFetcher.Statement> stmts) {
        Map<String, List<DataFetcher.Statement>> m = new HashMap<>();
        if (stmts == null) return m;
        for (DataFetcher.Statement s : stmts) {
            String key = s.mla == null ? "" : s.mla;
            List<DataFetcher.Statement> list = m.get(key);
            if (list == null) {
                list = new ArrayList<>();
                m.put(key, list);
            }
            list.add(s);
        }
        for (List<DataFetcher.Statement> list : m.values()) {
            Collections.sort(list, new java.util.Comparator<DataFetcher.Statement>() {
                @Override public int compare(DataFetcher.Statement a, DataFetcher.Statement b) {
                    String da = a.date == null ? "" : a.date;
                    String db = b.date == null ? "" : b.date;
                    return db.compareTo(da); // newest first
                }
            });
        }
        return m;
    }

    private void renderMlas(List<DataFetcher.Mla> mlas,
                            Map<String, List<DataFetcher.Statement>> byName) {
        mlasContainer.removeAllViews();
        if (mlas == null || mlas.isEmpty()) {
            mlasContainer.addView(tv("No MLAs found for this area.", Ui.MUTED, 14f, Typeface.NORMAL));
            return;
        }
        mlasContainer.addView(buildPartyLegend());
        for (final DataFetcher.Mla m : mlas) {
            List<DataFetcher.Statement> stmts = byName.get(m.name);
            if (stmts == null) stmts = Collections.emptyList();
            mlasContainer.addView(buildMlaCard(m, stmts));
        }
    }

    private LinearLayout buildMlaCard(final DataFetcher.Mla m,
                                      final List<DataFetcher.Statement> stmts) {
        LinearLayout card = card();
        int pc = Ui.partyColor(m.party);
        final MemberBio.Info bio = (memberBio == null) ? null : memberBio.get(m.personId);

        // header row (tap to expand/collapse)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        if (m.photoUrl != null && !m.photoUrl.isEmpty()) {
            ImageView photoView = photo(m.photoUrl);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(54), dp(54));
            plp.rightMargin = dp(12);
            photoView.setLayoutParams(plp);
            header.addView(photoView);
        }

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tv(m.name, pc, 16f, Typeface.BOLD));
        left.addView(tv(m.party, pc, 12f, Typeface.BOLD));
        if (bio != null && bio.memberSince != null && !bio.memberSince.isEmpty()) {
            left.addView(tv(bio.memberSince, Ui.MUTED, 11f, Typeface.NORMAL));
        }
        if (bio != null && bio.roleCurrent != null && !bio.roleCurrent.isEmpty()) {
            left.addView(tv(bio.roleCurrent, Ui.MUTED, 11f, Typeface.NORMAL));
        }
        int[] att = (attendance == null) ? null : attendance.get(m.name);
        if (att != null && att[1] > 0) {
            int pct = (int) Math.round(att[0] * 100.0 / att[1]);
            left.addView(attendanceBar(pct, att[0], att[1]));
        }
        String last = mostRecentDate(stmts);
        if (!last.isEmpty()) {
            left.addView(tv("Last spoke " + Ui.formatDate(last), Ui.MUTED, 12f, Typeface.NORMAL));
        }
        header.addView(left);

        final TextView arrow = tv("▸", Ui.MUTED, 18f, Typeface.BOLD);
        header.addView(arrow);
        card.addView(header);

        // collapsible body: contact + bio + statements + profile link
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setPadding(dp(4), dp(8), dp(4), dp(2));

        if (bio != null && bio.career != null && !bio.career.isEmpty()) {
            body.addView(tv("Before politics: " + bio.career, Ui.INK, 12f, Typeface.NORMAL));
        }
        if (bio != null && bio.previousRole != null && !bio.previousRole.isEmpty()) {
            body.addView(tv("Previously: " + bio.previousRole, Ui.MUTED, 12f, Typeface.NORMAL));
        }

        if (m.email != null && !m.email.isEmpty()) {
            TextView em = tv("✉  " + m.email, Ui.ACCENT, 12f, Typeface.BOLD);
            em.setPadding(0, dp(6), 0, 0);
            em.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_SENDTO,
                                Uri.parse("mailto:" + m.email)));
                    } catch (Exception ignored) { }
                }
            });
            body.addView(em);
        }
        if (m.phone != null && !m.phone.isEmpty()) {
            TextView ph = tv("☎  " + m.phone, Ui.ACCENT, 12f, Typeface.BOLD);
            ph.setPadding(0, dp(6), 0, 0);
            ph.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_DIAL,
                                Uri.parse("tel:" + m.phone.replaceAll("[^0-9+]", ""))));
                    } catch (Exception ignored) { }
                }
            });
            body.addView(ph);
        }

        if (m.profileUrl != null && !m.profileUrl.isEmpty()) {
            TextView prof = tv("Official profile ↗", Ui.ACCENT, 12f, Typeface.BOLD);
            prof.setPadding(0, dp(6), 0, dp(4));
            prof.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(m.profileUrl); }
            });
            body.addView(prof);
        }

        if (!stmts.isEmpty()) {
            for (int i = 0; i < stmts.size(); i++) {
                if (i > 0) body.addView(divider());
                body.addView(buildStatementEntry(stmts.get(i)));
            }
        } else {
            body.addView(tv("No recent statements in Hansard.", Ui.MUTED, 13f, Typeface.NORMAL));
        }
        card.addView(body);

        final boolean[] expanded = {false};
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expanded[0] = !expanded[0];
                body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                arrow.setText(expanded[0] ? "▾" : "▸");
            }
        });

        return card;
    }

    private LinearLayout buildStatementEntry(final DataFetcher.Statement s) {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setPadding(0, dp(6), 0, dp(6));
        if (s.date != null && !s.date.isEmpty()) {
            e.addView(tv(Ui.formatDate(s.date), Ui.MUTED, 11f, Typeface.NORMAL));
        }
        if (s.topic != null && !s.topic.isEmpty()) {
            e.addView(tv(s.topic, Ui.ACCENT, 12f, Typeface.BOLD));
        }
        if (s.text != null && !s.text.isEmpty()) {
            e.addView(tv(Ui.truncate(s.text, 260), Ui.INK, 13f, Typeface.NORMAL));
        }
        if (s.url != null && !s.url.isEmpty()) {
            TextView link = tv("Official Report ↗", Ui.ACCENT, 12f, Typeface.BOLD);
            link.setPadding(0, dp(4), 0, 0);
            link.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(s.url); }
            });
            e.addView(link);
        }
        return e;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(Ui.LINE);
        return v;
    }

    private String mostRecentDate(List<DataFetcher.Statement> stmts) {
        String latest = "";
        for (DataFetcher.Statement s : stmts) {
            if (s.date != null && s.date.compareTo(latest) > 0) latest = s.date;
        }
        return latest;
    }

    // -------------------------------------------------------------------------
    // Votes (party-coloured names + collapsible "more info")
    // -------------------------------------------------------------------------
    private void renderVotes(List<DataFetcher.Vote> votes) {
        votesContainer.removeAllViews();
        if (votes == null || votes.isEmpty()) {
            votesContainer.addView(tv("No recent divisions found.", Ui.MUTED, 14f, Typeface.NORMAL));
            return;
        }
        votesContainer.addView(buildVoteLegend());
        String lastDate = "";
        for (DataFetcher.Vote v : votes) {
            String d = (v.date == null) ? ""
                    : v.date.length() >= 10 ? v.date.substring(0, 10) : v.date;
            if (!d.isEmpty() && !d.equals(lastDate)) {
                lastDate = d;
                votesContainer.addView(dateHeader(d));
            }
            LinearLayout card = card();
            card.addView(tv(v.subject, Ui.INK, 14f, Typeface.BOLD));
            if (hasTotals(v)) card.addView(resultBar(v));
            card.addView(tv(v.date + (v.type != null && !v.type.isEmpty()
                    ? "  ·  " + v.type : ""), Ui.MUTED, 12f, Typeface.NORMAL));
            if (Ui.isHighTurnout(v)) {
                card.addView(badge("★ High turnout", Color.parseColor("#1F4E79")));
            }

            Map<String, List<DataFetcher.MyVote>> groups = new HashMap<>();
            for (DataFetcher.MyVote mv : v.myVotes) {
                String g = Ui.voteGroup(mv.vote);
                List<DataFetcher.MyVote> list = groups.get(g);
                if (list == null) { list = new ArrayList<>(); groups.put(g, list); }
                list.add(mv);
            }
            String[] order = {"AYE", "NO", "ABSTAINED", "ABSENT", "OTHER"};
            for (String g : order) {
                List<DataFetcher.MyVote> list = groups.get(g);
                if (list == null || list.isEmpty()) continue;
                card.addView(groupHeader(g, list.size()));
                for (DataFetcher.MyVote mv : list) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(12), dp(2), 0, dp(2));
                    row.addView(tv(mv.name, Ui.partyColor(mv.party), 13f, Typeface.NORMAL));
                    card.addView(row);
                }
            }

            if (hasVoteInfo(v)) {
                final LinearLayout info = buildVoteInfo(v);
                info.setVisibility(View.GONE);
                final TextView toggle = tv("▸ More info", Ui.ACCENT, 12f, Typeface.BOLD);
                toggle.setPadding(0, dp(6), 0, 0);
                card.addView(toggle);
                card.addView(info);

                final boolean[] open = {false};
                toggle.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vv) {
                        open[0] = !open[0];
                        info.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                        toggle.setText(open[0] ? "▾ Less info" : "▸ More info");
                    }
                });
            }
            votesContainer.addView(card);
        }
    }

    private boolean hasVoteInfo(DataFetcher.Vote v) {
        return (v.description != null && !v.description.isEmpty())
                || (v.outcome != null && !v.outcome.isEmpty())
                || (v.totalAyes != null && !v.totalAyes.isEmpty())
                || (v.totalNoes != null && !v.totalNoes.isEmpty())
                || (v.totalAbstentions != null && !v.totalAbstentions.isEmpty())
                || BillKnowledge.lookup(this, v.subject) != null;
    }

    private LinearLayout buildVoteInfo(DataFetcher.Vote v) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F6F7F9"));
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);

        BillKnowledge.BillInfo bi = BillKnowledge.lookup(this, v.subject);
        if (bi != null) {
            TextView h = tv("BILL FINE PRINT", Ui.MUTED, 11f, Typeface.BOLD);
            h.setPadding(0, 0, 0, dp(4));
            b.addView(h);
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
            if (v.description != null && !v.description.isEmpty()) {
                TextView full = tv(Ui.truncate(v.description, 400), Ui.MUTED, 12f, Typeface.NORMAL);
                full.setPadding(0, dp(6), 0, 0);
                b.addView(full);
            }
        } else if (v.description != null && !v.description.isEmpty()) {
            b.addView(tv(Ui.truncate(v.description, 420), Ui.INK, 13f, Typeface.NORMAL));
        }
        if (v.outcome != null && !v.outcome.isEmpty()) {
            TextView o = tv(v.outcome, Ui.ACCENT, 13f, Typeface.BOLD);
            o.setPadding(0, dp(6), 0, 0);
            b.addView(o);
        }
        StringBuilder sb = new StringBuilder();
        if (v.totalAyes != null && !v.totalAyes.isEmpty()) sb.append("Ayes ").append(v.totalAyes);
        if (v.totalNoes != null && !v.totalNoes.isEmpty()) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append("Noes ").append(v.totalNoes);
        }
        if (v.totalAbstentions != null && !v.totalAbstentions.isEmpty()) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append("Abstained ").append(v.totalAbstentions);
        }
        if (sb.length() > 0) {
            b.addView(tv(sb.toString(), Ui.MUTED, 12f, Typeface.NORMAL));
        }
        return b;
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------
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

    private TextView badge(String label, int color) {
        TextView t = chip(label, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView tag(String label, int color) {
        TextView t = chip(label, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout buildVoteLegend() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(4));
        row.addView(legendDot("Aye", Color.parseColor("#15803D")));
        row.addView(legendDot("No", Color.parseColor("#B91C1C")));
        row.addView(legendDot("Abstain", Color.parseColor("#B45309")));
        row.addView(legendDot("Absent", Ui.MUTED));
        return row;
    }

    private LinearLayout buildPartyLegend() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(4));
        row.addView(legendDot("SF", Color.parseColor("#008000")));
        row.addView(legendDot("DUP", Color.parseColor("#D46A4A")));
        row.addView(legendDot("UUP", Color.parseColor("#48A5EE")));
        row.addView(legendDot("Alliance", Color.parseColor("#D4A017")));
        row.addView(legendDot("SDLP", Color.parseColor("#2AA82A")));
        row.addView(legendDot("TUV", Color.parseColor("#1F4E79")));
        return row;
    }

    private TextView legendDot(String label, int color) {
        TextView t = tv("● " + label, color, 10f, Typeface.NORMAL);
        t.setPadding(0, 0, dp(8), 0);
        return t;
    }

    private TextView dateHeader(String iso) {
        return dateHeader(iso, null);
    }

    private TextView dateHeader(String iso, String weekday) {
        String txt = Ui.formatDate(iso);
        if (weekday != null && !weekday.isEmpty()) txt = weekday + " · " + txt;
        TextView t = tv(txt, Ui.MUTED, 12f, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView groupHeader(String group, int count) {
        int color = "AYE".equals(group) ? Color.parseColor("#15803D")
                : "NO".equals(group) ? Color.parseColor("#B91C1C")
                : "ABSTAINED".equals(group) ? Color.parseColor("#B45309")
                : Ui.MUTED;
        TextView t = tv(groupLabel(group) + " · " + count, color, 12f, Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private String groupLabel(String g) {
        if ("AYE".equals(g)) return "Aye";
        if ("NO".equals(g)) return "No";
        if ("ABSTAINED".equals(g)) return "Abstained";
        if ("ABSENT".equals(g)) return "Absent";
        return "Other";
    }

    private boolean hasTotals(DataFetcher.Vote v) {
        return !empty(v.totalAyes) || !empty(v.totalNoes) || !empty(v.totalAbstentions);
    }

    private boolean empty(String s) { return s == null || s.trim().isEmpty(); }

    private View resultBar(DataFetcher.Vote v) {
        int a = parseIntSafe(v.totalAyes, 0);
        int n = parseIntSafe(v.totalNoes, 0);
        int ab = parseIntSafe(v.totalAbstentions, 0);
        int total = a + n + ab;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        blp.topMargin = dp(6);
        bar.setLayoutParams(blp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E5E7EB"));
        bg.setCornerRadius(dp(4));
        bar.setBackground(bg);
        bar.setClipToOutline(true);
        if (total > 0) {
            if (a > 0) bar.addView(seg(a, Color.parseColor("#15803D")));
            if (n > 0) bar.addView(seg(n, Color.parseColor("#B91C1C")));
            if (ab > 0) bar.addView(seg(ab, Color.parseColor("#B45309")));
        }
        return bar;
    }

    private View seg(int weight, int color) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
        v.setBackgroundColor(color);
        return v;
    }

    private View attendanceBar(int pct, int voted, int total) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(4), 0, 0);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(Color.parseColor("#E5E7EB"));
        tb.setCornerRadius(dp(3));
        track.setBackground(tb);
        track.setClipToOutline(true);
        if (pct > 0) {
            View fill = new View(this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            fill.setBackgroundColor(Color.parseColor("#15803D"));
            track.addView(fill);
            View rest = new View(this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
            rest.setBackgroundColor(Color.TRANSPARENT);
            track.addView(rest);
        }
        wrap.addView(track);
        wrap.addView(tv("Attended " + voted + " of " + total + " votes · " + pct + "%",
                Ui.MUTED, 10f, Typeface.NORMAL));
        return wrap;
    }

    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
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

    /** Circular MLA portrait, loaded from the API on a background thread. */
    private ImageView photo(String url) {
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E5E7EB"));
        iv.setBackground(bg);
        iv.setClipToOutline(true);
        loadPhoto(url, iv);
        return iv;
    }

    private void loadPhoto(final String url, final ImageView iv) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Bitmap bmp = BitmapFactory.decodeStream(new URL(url).openStream());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (bmp != null) iv.setImageBitmap(bmp);
                        }
                    });
                } catch (Exception ignored) { }
            }
        }).start();
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String timeAgo(long epochMillis) {
        if (epochMillis <= 0) return "—";
        long s = (System.currentTimeMillis() - epochMillis) / 1000;
        if (s < 60) return "just now";
        long m = s / 60;
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " hr ago";
        return (h / 24) + " days ago";
    }
}
