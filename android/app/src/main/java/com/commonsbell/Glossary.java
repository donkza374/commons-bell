package com.commonsbell;

import java.util.ArrayList;
import java.util.List;

/**
 * A small plain-English glossary of Stormont / parliamentary terms, so people
 * can decode what they're reading. Bundled as code (no asset) — easy to extend.
 */
public final class Glossary {

    private Glossary() { }

    public static class Term {
        public final String term;
        public final String meaning;
        Term(String term, String meaning) { this.term = term; this.meaning = meaning; }
    }

    public static List<Term> all() {
        List<Term> out = new ArrayList<>();
        out.add(new Term("Division", "A formal vote in the Chamber, where MLAs record whether they are for or against a motion."));
        out.add(new Term("Aye / No", "The words used to vote: \"Aye\" means yes (in favour); \"No\" means against. You may also see \"Abstained\" or \"Absent\"."));
        out.add(new Term("Abstained", "An MLA who was present but chose not to vote either way."));
        out.add(new Term("Absent", "An MLA who did not take part in the vote at all."));
        out.add(new Term("Motion", "A proposal put to the Assembly for a decision — the subject of a vote."));
        out.add(new Term("Amendment", "A change proposed to a motion or Bill, voted on separately."));
        out.add(new Term("Bill", "A proposed new law. It must pass through several stages before it becomes an Act."));
        out.add(new Term("First Stage", "The formal introduction of a Bill — its title is read out; usually no debate."));
        out.add(new Term("Second Stage", "The main debate on a Bill's general principles."));
        out.add(new Term("Committee Stage", "Detailed line-by-line examination of a Bill by a committee."));
        out.add(new Term("Consideration Stage", "Further chance to amend the Bill, often referred to as the 'amendment stage'."));
        out.add(new Term("Final Stage", "The last vote on a Bill before it is sent for Royal Assent."));
        out.add(new Term("Cross-community vote", "A vote that needs support from both unionists and nationalists, not just an overall majority — used for the most sensitive decisions."));
        out.add(new Term("Simple majority", "A vote decided by whoever gets the most votes, with no special cross-community rules."));
        out.add(new Term("Constituency", "Your local voting area. Northern Ireland has 18 constituencies, each with 5 MLAs."));
        out.add(new Term("MLA", "Member of the Legislative Assembly — one of 90 representatives elected to the Northern Ireland Assembly."));
        out.add(new Term("Hansard", "The official written record of everything said in the Assembly."));
        out.add(new Term("Order Paper", "The published agenda for an Assembly sitting, listing the business to be considered."));
        out.add(new Term("Plenary", "The full sitting of the Assembly, where all 90 MLAs meet in the Chamber."));
        out.add(new Term("Committee", "A smaller group of MLAs that examines a subject, a Bill, or a department in detail."));
        out.add(new Term("Sitting", "A day (or part of a day) when the Assembly meets."));
        out.add(new Term("Turnout", "How often an MLA actually records a vote, compared to how many they could have taken part in."));
        out.add(new Term("Whip", "An instruction from a party telling its members how to vote, and the person who organises that."));
        out.add(new Term("Speaker / Principal Deputy Speaker", "The presiding officer who keeps order in the Chamber and calls divisions."));
        return out;
    }
}
