package com.commonsbell;

import android.graphics.Color;

/**
 * Shared UI constants + pure formatting/classification helpers, so
 * MainActivity and VoteActivity don't each carry their own copies.
 */
public final class Ui {

    private Ui() { }

    public static final int ACCENT = Color.parseColor("#134E4A");
    public static final int INK = Color.parseColor("#1A2233");
    public static final int MUTED = Color.parseColor("#6B7280");
    public static final int LINE = Color.parseColor("#E5E7EB");

    public static final int AYE = Color.parseColor("#15803D");   // green
    public static final int NO = Color.parseColor("#B91C1C");    // red
    public static final int ABSTAIN = Color.parseColor("#B45309"); // amber

    private static final String[] AYE_CODES = {
            "AYE", "AYE (PROXY)", "SIN", "SUPPORT", "TA", "FOR", "YES", "CONTENT"
    };
    private static final String[] NO_CODES = {
            "NO", "NO (PROXY)", "AGAINST", "NOES", "NOT CONTENT", "NIL"
    };

    public static String voteLabel(String v) {
        if (v == null) return "—";
        String u = v.toUpperCase();
        if ("AYE".equals(u)) return "AYE";
        if ("NO".equals(u)) return "NO";
        if ("ABSTAINED".equals(u)) return "ABSTAINED";
        if ("ABSENT".equals(u)) return "ABSENT";
        return "—";
    }

    /** Green for aye, red for no, amber for abstain, grey otherwise. */
    public static int voteColor(String v) {
        if (v == null) return MUTED;
        String u = v.toUpperCase();
        for (String c : AYE_CODES) if (u.contains(c)) return AYE;
        for (String c : NO_CODES) if (u.contains(c)) return NO;
        if (u.contains("ABSTAIN")) return ABSTAIN;
        if (u.contains("ABSENT")) return MUTED;
        return MUTED;
    }

    /** NI party brand colour, falling back to grey for anything unknown. */
    public static int partyColor(String party) {
        if (party == null) return MUTED;
        String p = party.toLowerCase();
        if (p.contains("sinn")) return Color.parseColor("#008000");   // Sinn Féin
        if (p.contains("dup")) return Color.parseColor("#D46A4A");    // DUP
        if (p.contains("uup") || p.contains("ulster unionist")) return Color.parseColor("#48A5EE");
        if (p.contains("alliance")) return Color.parseColor("#D4A017");
        if (p.contains("tuv")) return Color.parseColor("#1F4E79");
        if (p.contains("sdlp")) return Color.parseColor("#2AA82A");
        if (p.equals("green") || p.contains("green party")) return Color.parseColor("#008000");
        return MUTED;
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max).trim() + "…";
    }

    /** +1 = Aye, -1 = No, 0 = absent / abstained / unknown. */
    public static int classifyVote(String vote) {
        if (vote == null) return 0;
        String u = vote.toUpperCase();
        for (String c : AYE_CODES) if (u.contains(c)) return 1;
        for (String c : NO_CODES) if (u.contains(c)) return -1;
        return 0;
    }

    /** "2026-11-10" -> "10 Nov 2026". Returns the input unchanged if not ISO. */
    public static String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        String[] p = iso.trim().split("-");
        if (p.length != 3) return iso;
        String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
        try {
            int mm = Integer.parseInt(p[1]);
            int dd = Integer.parseInt(p[2]);
            String mon = (mm >= 1 && mm <= 12) ? months[mm - 1] : p[1];
            return dd + " " + mon + " " + p[0];
        } catch (NumberFormatException e) {
            return iso;
        }
    }

    // ---- "big vote" / turnout classification ---------------------------------

    private static final String[] BUDGET_TERMS = {
            "budget", "appropriation", "public expenditure", "fiscal"
    };

    public static boolean isBudget(String title) {
        if (title == null) return false;
        String t = title.toLowerCase();
        for (String s : BUDGET_TERMS) if (t.contains(s)) return true;
        return false;
    }

    /** A "big" upcoming item: a Bill, budget-related, or curated high-impact. */
    public static boolean isBigVote(String title, String type, BillKnowledge.BillInfo bi) {
        if (bi != null && bi.big) return true;
        if (type != null && type.toLowerCase().contains("bill")) return true;
        return isBudget(title);
    }

    /** High turnout = roughly 90%+ of the 90 MLAs recorded a vote (not absent). */
    public static boolean isHighTurnout(DataFetcher.Vote v) {
        if (v == null) return false;
        int participated = parseInt(v.totalAyes) + parseInt(v.totalNoes) + parseInt(v.totalAbstentions);
        return participated >= 81;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
