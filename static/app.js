// NI Politics Tracker frontend
(function () {
  "use strict";

  var el = function (id) { return document.getElementById(id); };

  var state = {
    data: null,
    constituencies: [],
    currentConstituency: null,
  };

  // -------------------------------------------------------------------------
  // helpers
  // -------------------------------------------------------------------------

  function escapeHtml(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function timeAgo(epoch) {
    if (!epoch) return "—";
    var s = Math.floor(Date.now() / 1000 - epoch);
    if (s < 60) return "just now";
    var m = Math.floor(s / 60);
    if (m < 60) return m + " min ago";
    var h = Math.floor(m / 60);
    if (h < 24) return h + " hr ago";
    return Math.floor(h / 24) + " day" + (Math.floor(h / 24) === 1 ? "" : "s") + " ago";
  }

  function minsUntil(epoch) {
    if (!epoch) return "—";
    var m = Math.floor((epoch - Date.now() / 1000) / 60);
    if (m <= 0) return "now";
    if (m < 60) return m + " min";
    return Math.floor(m / 60) + " hr";
  }

  function initials(name) {
    var parts = String(name || "?").replace(/^(Mr|Ms|Mrs|Miss|Dr)\.?\s+/i, "").split(/\s+/);
    if (!parts.length) return "?";
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  function badgedVote(vote) {
    var label, cls;
    var v = String(vote || "").toUpperCase();
    if (v === "AYE") { label = "Aye"; cls = "aye"; }
    else if (v === "NO") { label = "No"; cls = "no"; }
    else if (v === "ABSTAINED") { label = "Abstained"; cls = "abstained"; }
    else if (v === "ABSENT") { label = "Absent"; cls = "absent"; }
    else { label = "—"; cls = "unknown"; }
    return '<span class="badge ' + cls + '">' + label + "</span>";
  }

  function $json(path, opts) {
    return fetch(path, opts).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
  }

  // -------------------------------------------------------------------------
  // rendering
  // -------------------------------------------------------------------------

  function renderConstituencies() {
    var sel = el("constituency");
    if (!sel) return;
    var list = state.constituencies;
    if (!list.length) return;
    sel.innerHTML = list.map(function (c) {
      var id = c.ConstituencyId;
      var name = c.ConstituencyName;
      var cur = state.currentConstituency;
      var marked = String(cur) === String(id) ? " selected" : "";
      return '<option value="' + id + '"' + marked + ">" + escapeHtml(name) + "</option>";
    }).join("");
  }

  function renderStatus() {
    var d = state.data || {};
    var box = el("status");
    if (!box) return;
    var err = d.last_error;
    if (err) {
      box.className = "status error";
      box.innerHTML = '<div class="inner"><span class="dot"></span>' +
        "Could not refresh: " + escapeHtml(err) + "</div>";
      return;
    }
    var last = d.last_refresh;
    var next = d.next_refresh;
    var interval = d.refresh_interval_hours;
    box.className = "status";
    box.innerHTML = '<div class="inner"><span class="dot"></span>' +
      "Updated " + timeAgo(last) +
      " · auto-refresh every " + interval + " hr" +
      (next ? " · next in " + minsUntil(next) : "") +
      "</div>";
  }

  function renderMlas() {
    var wrap = el("mlas");
    if (!wrap) return;
    var mlas = (state.data || {}).mlas || [];
    if (!mlas.length) {
      wrap.innerHTML = '<div class="empty">No MLAs loaded yet.</div>';
      return;
    }
    wrap.innerHTML = mlas.map(function (m) {
      return '<div class="card mla">' +
        '<div class="avatar">' + escapeHtml(initials(m.name)) + "</div>" +
        '<div style="flex:1">' +
          '<div class="name">' + escapeHtml(m.name) + "</div>" +
          '<div class="party">' + escapeHtml(m.party) + "</div>" +
        "</div>" +
        '<a href="' + escapeHtml(m.profileUrl) + '" target="_blank" rel="noopener">Profile ↗</a>' +
      "</div>";
    }).join("");
  }

  function renderVotes() {
    var wrap = el("votes");
    if (!wrap) return;
    var votes = (state.data || {}).votes || [];
    if (!votes.length) {
      wrap.innerHTML = '<div class="empty">No recent divisions found.</div>';
      return;
    }
    wrap.innerHTML = votes.map(function (v) {
      var rows = (v.myVotes || []).map(function (mv) {
        return '<div class="vote-row">' +
          '<span class="who">' + escapeHtml(mv.name) + " <span style='color:#9ca3af'>· " +
            escapeHtml(mv.party) + "</span></span>" +
          badgedVote(mv.vote) +
        "</div>";
      }).join("");
      return '<div class="card vote">' +
        '<div class="subject">' + escapeHtml(v.subject) + "</div>" +
        '<div class="meta">' + escapeHtml(v.date) +
          (v.type ? ' &nbsp;·&nbsp; <span class="type">' + escapeHtml(v.type) + "</span>" : "") +
        "</div>" +
        '<div class="vote-grid">' + rows + "</div>" +
        '<div style="margin-top:10px"><a href="' + escapeHtml(v.url) +
          '" target="_blank" rel="noopener">View all division results ↗</a></div>' +
      "</div>";
    }).join("");
  }

  function renderStatements() {
    var wrap = el("statements");
    if (!wrap) return;
    var stmts = (state.data || {}).statements || [];
    if (!stmts.length) {
      wrap.innerHTML = '<div class="empty">No recent statements from your MLAs.</div>';
      return;
    }
    wrap.innerHTML = stmts.map(function (s, i) {
      var id = "stmt-" + i;
      return '<div class="card stmt">' +
        '<div class="head"><span class="name">' + escapeHtml(s.mla) +
          ' <span class="party">' + escapeHtml(s.party) + "</span></span>" +
          '<span class="date">' + escapeHtml(s.date) + "</span></div>" +
        (s.topic ? '<div class="topic">' + escapeHtml(s.topic) + "</div>" : "") +
        '<div class="text collapsed" id="' + id + '">' + escapeHtml(s.text) + "</div>" +
        '<div style="margin-top:6px"><button class="link" data-expand="' + id + '">Read more</button>' +
        ' — <a href="' + escapeHtml(s.url) + '" target="_blank" rel="noopener">Official Report ↗</a></div>' +
      "</div>";
    }).join("");
  }

  function render() {
    renderStatus();
    renderMlas();
    renderVotes();
    renderStatements();
    var title = (state.data || {}).constituency || {};
    el("areaName").textContent = title.name || "Your area";
  }

  // -------------------------------------------------------------------------
  // actions
  // -------------------------------------------------------------------------

  function loadState(initial) {
    return $json("/api/state").then(function (d) {
      state.data = d;
      render();
      // If we're mid first-fetch, poll until data arrives.
      if (initial && !d.mlas.length && !d.last_error && !d.last_refresh) {
        setTimeout(function () { loadState(true); }, 3000);
      }
    }).catch(function () {
      if (initial) setTimeout(function () { loadState(true); }, 5000);
    });
  }

  function refresh() {
    var btn = el("refreshBtn");
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = '<span class="spin">↻</span> Refreshing…';
    }
    $json("/api/refresh", { method: "POST" })
      .then(function (d) { state.data = d; render(); })
      .catch(function (e) { console.error(e); })
      .finally(function () {
        if (btn) { btn.disabled = false; btn.textContent = "↻ Refresh"; }
      });
  }

  function changeConstituency() {
    var sel = el("constituency");
    if (!sel) return;
    var id = sel.value;
    state.currentConstituency = id;
    var btn = el("refreshBtn");
    if (btn) { btn.disabled = true; btn.textContent = "Loading…"; }
    $json("/api/constituency", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ constituency_id: id }),
    }).then(function (d) { state.data = d; render(); })
      .catch(function (e) { console.error(e); })
      .finally(function () {
        if (btn) { btn.disabled = false; btn.textContent = "↻ Refresh"; }
      });
  }

  // -------------------------------------------------------------------------
  // wiring
  // -------------------------------------------------------------------------

  document.addEventListener("click", function (e) {
    var t = e.target;
    if (t && t.dataset && t.dataset.expand) {
      var node = document.getElementById(t.dataset.expand);
      if (node) {
        node.classList.toggle("collapsed");
        t.textContent = node.classList.contains("collapsed") ? "Read more" : "Show less";
      }
    }
  });

  el("refreshBtn").addEventListener("click", refresh);
  el("constituency").addEventListener("change", changeConstituency);

  function init() {
    $json("/api/constituencies").then(function (d) {
      state.constituencies = d.constituencies || [];
      state.currentConstituency = d.current;
      renderConstituencies();
    }).catch(function () {});
    loadState(true);
  }

  init();
})();
