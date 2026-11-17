#!/usr/bin/env python3
"""
NI Assembly open-data client + a consolidated snapshot builder.

Pure Python standard library (no dependencies). Talks to the NI Assembly's
public JSON web services at data.niassembly.gov.uk (Open Government Licence
v3.0). Each `_JSON` endpoint is a plain HTTP GET returning raw JSON.

Verified endpoints / shapes (checked against live data, Nov 2026):

  /members.asmx/GetAllConstituencies_JSON
      -> {"AllConstituencies":{"Constituency":[{"ConstituencyId","ConstituencyName",...}]}}

  /members.asmx/GetAllCurrentMembersByGivenConstituencyId_JSON?constituencyId=N
      -> {"AllMembersList":{"Member":[{"PersonId","AffiliationId","MemberFirstName",
             "MemberLastName","MemberFullDisplayName","PartyName","ConstituencyId",...}]}}

  /plenary.asmx/GetVotesOnDivision_JSON?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD
      -> {"DivisionList":{"Division":[{"DocumentID","DivisionSubject","DivisionDate",
             "DivisonType",...}]}}

  /plenary.asmx/GetDivisionMemberVoting_JSON?documentid=N
      -> {"MemberVoting":{"Member":[{"PersonID","MemberName","Vote":"AYE"|"NO",
             "Designation",...}]}}   (members who did not vote are absent)

  /hansard.asmx/GetAllHansardReports_JSON
      -> {"AllHansardComponentsList":{"HansardComponent":[{"ReportDocId","PlenaryDate",...}]}}

  /hansard.asmx/GetHansardComponentsByReportIdAndPersonId_JSON?reportId=N&personId=N
      -> {"AllHansardComponentsList":{"HansardComponent":[
             {"ComponentType":"Speaker (MlaName)","ComponentText":"Mr X:",
              "ParentComponentText":"<debate topic>", "RelatedItemId":"<personId>"},
             {"ComponentType":"Spoken Text","ComponentText":"<what they said>"},
             ...]}}

MLA profile deep-link (AIMS portal):
  https://aims.niassembly.gov.uk/mlas/details.aspx?aff=<AffiliationId>&per=<PersonId>&sel=1&ind=0&prv=0
"""
import json
import os
import re
import urllib.parse
import urllib.request
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))

BASE = "https://data.niassembly.gov.uk"
PROFILE_BASE = "https://aims.niassembly.gov.uk/mlas/details.aspx"
DIVISIONS_URL = "https://aims.niassembly.gov.uk/plenary/divisions.aspx"
HANSARD_URL = "https://aims.niassembly.gov.uk/officialreport/report.aspx"

_USER_AGENT = {"User-Agent": "Commons-Bell/0.1 (personal use)"}


def _get(url, params=None):
    """GET a `_JSON` endpoint and return the decoded object."""
    if params:
        url += "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=_USER_AGENT)
    with urllib.request.urlopen(req, timeout=40) as r:
        body = r.read().decode("utf-8", "replace")
    return json.loads(body)


def _as_list(x):
    """Normalise the ASMX habit of returning a bare object (not a list) when a
    result set contains exactly one item."""
    if x is None:
        return []
    if isinstance(x, list):
        return x
    return [x]


# ---------------------------------------------------------------------------
# Raw endpoint callers
# ---------------------------------------------------------------------------

def get_constituencies():
    data = _get(f"{BASE}/members.asmx/GetAllConstituencies_JSON")
    return _as_list(data.get("AllConstituencies", {}).get("Constituency"))


def get_members_by_constituency(constituency_id):
    data = _get(
        f"{BASE}/members.asmx/GetAllCurrentMembersByGivenConstituencyId_JSON",
        {"constituencyId": constituency_id},
    )
    return _as_list(data.get("AllMembersList", {}).get("Member"))


def get_votes(start_date, end_date):
    data = _get(
        f"{BASE}/plenary.asmx/GetVotesOnDivision_JSON",
        {"startDate": start_date, "endDate": end_date},
    )
    return _as_list(data.get("DivisionList", {}).get("Division"))


def get_division_member_voting(document_id):
    data = _get(
        f"{BASE}/plenary.asmx/GetDivisionMemberVoting_JSON",
        {"documentid": document_id},
    )
    return _as_list(data.get("MemberVoting", {}).get("Member"))


def get_hansard_reports():
    data = _get(f"{BASE}/hansard.asmx/GetAllHansardReports_JSON")
    return _as_list(data.get("AllHansardComponentsList", {}).get("HansardComponent"))


def get_hansard_by_report_person(report_id, person_id):
    data = _get(
        f"{BASE}/hansard.asmx/GetHansardComponentsByReportIdAndPersonId_JSON",
        {"reportId": report_id, "personId": person_id},
    )
    return _as_list(data.get("AllHansardComponentsList", {}).get("HansardComponent"))


# ---------------------------------------------------------------------------
# Helpers / shaping
# ---------------------------------------------------------------------------

def profile_url(affiliation_id, person_id):
    return f"{PROFILE_BASE}?aff={affiliation_id}&per={person_id}&sel=1&ind=0&prv=0"


def hansard_url(report_id, plenary_date):
    # matches the Official Report viewer URL pattern used by AIMS
    d = str(plenary_date)[:10].replace("-", "/")
    return f"{HANSARD_URL}?eveDate={d}&docID={report_id}"


def _clean(text):
    """Strip Hansard's inline `<BR />` / `\r` cruft into plain prose."""
    if text is None:
        return ""
    t = text.replace("\r", " ").replace("\n", " ")
    t = re.sub(r"<BR\s*/?>", " ", t, flags=re.IGNORECASE)
    t = re.sub(r"\s+", " ", t)
    return t.strip()


def _extract_statements(components, mla_name, party, report_id, plenary_date):
    """Group one MLA's report components into coherent speaking turns.

    Hansard splits a speech into many "Spoken Text" paragraphs; we merge the
    consecutive blocks under a single "Speaker (MlaName)" header into one
    statement, and use that header's ParentComponentText as the debate topic.
    """
    out = []
    current_topic = ""
    current_text = []

    def flush():
        if current_text:
            out.append({
                "mla": mla_name,
                "party": party,
                "topic": current_topic,
                "text": " ".join(current_text),
                "url": hansard_url(report_id, plenary_date),
                "date": str(plenary_date)[:10],
            })
        current_text.clear()

    for c in components:
        ctype = c.get("ComponentType", "")
        if ctype == "Speaker (MlaName)":
            flush()
            pt = c.get("ParentComponentText")
            if isinstance(pt, str):
                current_topic = _clean(pt)
        elif ctype in ("Spoken Text", "Spoken Text (Continuation)"):
            text = _clean(c.get("ComponentText", ""))
            if text:
                current_text.append(text)
    flush()
    return out


# ---------------------------------------------------------------------------
# Consolidated snapshot builder
# ---------------------------------------------------------------------------

def collect(constituency_id, cfg):
    """
    Build the full app snapshot for a constituency.

    Returns a dict shaped for the frontend (see README). This is what gets
    cached and what the UI renders.
    """
    votes_days = int(cfg.get("votes_days_back", 45))
    stmt_reports = int(cfg.get("statements_reports_back", 5))
    max_division_details = int(cfg.get("max_division_details", 20))

    # -- constituencies (needed for the selector / name resolution) -----------
    constituencies = get_constituencies()
    const = next(
        (x for x in constituencies if str(x.get("ConstituencyId")) == str(constituency_id)),
        None,
    )
    const_name = const.get("ConstituencyName", "Unknown") if const else "Unknown"

    # -- MLAs ------------------------------------------------------------------
    mlas = []
    raw_members = get_members_by_constituency(constituency_id)
    for m in raw_members:
        mlas.append({
            "personId": str(m.get("PersonId")),
            "affiliationId": str(m.get("AffiliationId")),
            "name": m.get("MemberFullDisplayName") or f"{m.get('MemberFirstName','')} {m.get('MemberLastName','')}".strip(),
            "party": m.get("PartyName", ""),
            "profileUrl": profile_url(m.get("AffiliationId"), m.get("PersonId")),
        })
    person_ids = {m["personId"] for m in mlas}

    # -- recent divisions + member voting -------------------------------------
    end = date.today()
    start = end - timedelta(days=votes_days)
    divisions = get_votes(start.isoformat(), end.isoformat())

    # newest first
    divisions.sort(key=lambda d: d.get("DivisionDate", ""), reverse=True)

    votes = []
    for idx, d in enumerate(divisions):
        doc_id = d.get("DocumentID")
        subject = d.get("DivisionSubject", "(no subject)")
        div_date = (d.get("DivisionDate") or "")[:16].replace("T", " ")
        div_type = d.get("DivisonType", "")
        member_votes = {}  # personId -> AYE/NO
        if idx < max_division_details and doc_id:
            try:
                for mv in get_division_member_voting(doc_id):
                    member_votes[str(mv.get("PersonID"))] = mv.get("Vote", "")
            except Exception:
                pass  # tolerate a missing breakdown; keep the division itself

        # how did *my* MLAs vote in this one?
        my_votes = []
        for m in mlas:
            v = member_votes.get(m["personId"])
            my_votes.append({
                "name": m["name"],
                "party": m["party"],
                "vote": v if v else ("absent" if member_votes else "unknown"),
                "personId": m["personId"],
            })

        votes.append({
            "documentId": str(doc_id),
            "subject": subject,
            "date": div_date,
            "type": div_type,
            "url": DIVISIONS_URL,
            "myVotes": my_votes,
        })

    # -- recent statements by my MLAs -----------------------------------------
    statements = []
    reports = get_hansard_reports()[:stmt_reports]
    for rep in reports:
        report_id = rep.get("ReportDocId")
        plenary_date = rep.get("PlenaryDate")
        if not report_id:
            continue
        for m in mlas:
            try:
                comps = get_hansard_by_report_person(report_id, m["personId"])
            except Exception:
                comps = []
            statements.extend(
                _extract_statements(comps, m["name"], m["party"], report_id, plenary_date)
            )

    # newest-first is already the report order (reports come back newest first).
    # Sort defensively by date descending, tie-break by insertion order.
    statements.sort(key=lambda s: s.get("date", ""), reverse=True)
    statements = statements[:int(cfg.get("max_statements", 60))]

    return {
        "constituency": {"id": str(constituency_id), "name": const_name},
        "mlas": mlas,
        "votes": votes,
        "statements": statements,
    }
