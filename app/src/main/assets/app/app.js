/* yaver — interface. Everything it shows comes from the native core (window.YaverHost); nothing runs here but the screen. */
(function () {
  "use strict";

  /* ------------------------------------------------------------ host bridge */
  var Host = window.YaverHost;
  var waits = {}, seq = 0;
  function call(method, args) {
    return new Promise(function (res, rej) {
      var id = ++seq; waits[id] = { res: res, rej: rej };
      try { Host.call(id, method, JSON.stringify(args || {})); } catch (e) { delete waits[id]; rej(e); }
    });
  }
  window.YV = {
    ret: function (id, ok, v) { var w = waits[id]; if (!w) return; delete waits[id]; ok ? w.res(v) : w.rej(new Error(String(v))); },
    on: function (type, data) { try { onEvent(type, data); } catch (e) { console.error(e); } },
    back: function () { return goBack(); }
  };

  /* ------------------------------------------------------------ state */
  var S = { lang: "tr", state: null, convo: null, convoId: "", live: "", liveStatus: "", liveTool: null, running: {}, atts: [], convos: [], pages: [], voiceTurn: false, mail: { folder: "inbox", tab: "mail", items: [], query: "" },
    side: {}, fu: {}, plan: {}, ctx: {}, subs: {}, fbAsk: null, todoOpen: false, overdue: 0, kb: { tab: "memory", q: "", filter: "all", sort: "use" }, tasks: { q: "", owner: "", status: "open", sort: "" } };
  function T(en, tr) { return S.lang === "tr" ? tr : en; }

  /* ------------------------------------------------------------ dom helpers */
  function h(tag, attrs) {
    var el = document.createElement(tag);
    if (attrs) for (var k in attrs) {
      var v = attrs[k];
      if (v == null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "html") el.innerHTML = v;
      else if (k.slice(0, 2) === "on") el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? "" : v);
    }
    for (var i = 2; i < arguments.length; i++) add(el, arguments[i]);
    return el;
  }
  function add(el, c) {
    if (c == null || c === false) return;
    if (Array.isArray(c)) { c.forEach(function (x) { add(el, x); }); return; }
    el.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
  }
  function icon(name, cls) {
    var s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    s.setAttribute("viewBox", "0 0 24 24");
    s.setAttribute("class", "i" + (cls ? " " + cls : ""));
    s.innerHTML = (window.ICONS || {})[name] || "";
    return s;
  }
  function $(sel, root) { return (root || document).querySelector(sel); }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

  var toastT = 0;
  function toast(msg, onClick, ms) {
    if (!msg) return;
    var t = $(".toast") || document.body.appendChild(h("div", { class: "toast", role: "status" }));
    t.textContent = String(msg); t.classList.add("on");
    t.classList.toggle("act", !!onClick);
    if (onClick) t.appendChild(icon("chevron-right", "xs"));
    t.onclick = onClick ? function () { t.classList.remove("on"); onClick(); } : null;
    clearTimeout(toastT); toastT = setTimeout(function () { t.classList.remove("on"); }, ms || (onClick ? 5000 : 3200));
  }

  /* ------------------------------------------------------------ formatting */
  function loc() { return S.lang === "tr" ? "tr-TR" : undefined; }
  function sameDay(a, b) { return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate(); }
  function timeOf(ms) { return new Date(ms).toLocaleTimeString(loc(), { hour: "2-digit", minute: "2-digit" }); }
  function shortWhen(ms) {
    if (!ms) return "";
    var d = new Date(ms), now = new Date(), y = new Date(now.getTime() - 864e5);
    if (sameDay(d, now)) return timeOf(ms);
    if (sameDay(d, y)) return T("Yesterday", "Dün");
    if (now - d < 6 * 864e5) return d.toLocaleDateString(loc(), { weekday: "short" });
    return d.toLocaleDateString(loc(), { day: "numeric", month: "short" });
  }
  function longWhen(ms) { return new Date(ms).toLocaleString(loc(), { weekday: "long", day: "numeric", month: "long", hour: "2-digit", minute: "2-digit" }); }
  function dayLabel(ms) {
    var d = new Date(ms), now = new Date(), tm = new Date(now.getTime() + 864e5);
    if (sameDay(d, now)) return T("Today", "Bugün");
    if (sameDay(d, tm)) return T("Tomorrow", "Yarın");
    return d.toLocaleDateString(loc(), { weekday: "long" });
  }
  function initials(name) { var p = String(name || "?").replace(/[<>"]/g, "").trim().split(/\s+/); return ((p[0] || "?")[0] + (p.length > 1 ? p[p.length - 1][0] : "")).toUpperCase(); }
  var AV = ["#5B4FE0", "#0F8B8D", "#C2410C", "#2563EB", "#9333EA", "#15803D", "#B45309", "#BE185D", "#0E7490", "#4D7C0F"];
  function avatar(name, email) { var s = String(email || name || ""), n = 0; for (var i = 0; i < s.length; i++) n = (n * 31 + s.charCodeAt(i)) >>> 0; return h("div", { class: "av", style: "background:" + AV[n % AV.length] }, initials(name || email)); }
  function bytes(n) { return n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(0) + " KB" : (n / 1048576).toFixed(1) + " MB"; }

  /* ------------------------------------------------------------ markdown */
  if (window.marked) marked.setOptions({ gfm: true, breaks: false });
  /* e-mail references: [subject](mail:id) links and bare Exchange item ids (AAMk…/AQMk…) become chips with the subject */
  var MAIL_ID = /(?:AAMk|AQMk|AAQk)[A-Za-z0-9+\/_-]{30,}={0,2}/g, MAIL_SUBJ = {};
  function isMailId(t) { return /^(?:AAMk|AQMk|AAQk)[A-Za-z0-9+\/_-]{30,}={0,2}$/.test(String(t || "").trim()); }
  function mailChip(id, label) {
    var generic = !label || isMailId(label) || /^(e-?mail|mail|link|here|burada|bu e-posta|e-posta)$/i.test(label);
    var span = h("span", null, generic ? (MAIL_SUBJ[id] || T("e-mail", "e-posta")) : label);
    var a = h("a", { class: "mlink", href: "mail:" + id, "data-mail": id }, icon("mail", "xs"), span);
    if (generic && !MAIL_SUBJ[id]) {
      call("mail.title", { id: id }).then(function (r) {
        if (r && r.subject) { MAIL_SUBJ[id] = r.subject + (r.from ? " — " + r.from : ""); document.querySelectorAll('a.mlink[data-mail="' + id.replace(/"/g, "") + '"] span').forEach(function (el) { if (!el.dataset.fixed) el.textContent = MAIL_SUBJ[id]; }); }
      }).catch(function () {});
    } else span.dataset.fixed = generic ? "" : "1";
    return a;
  }
  function mailChips(root) {
    root.querySelectorAll('a[href^="mail:"],a[href^="#mail:"]').forEach(function (a) {
      var id = decodeURIComponent(a.getAttribute("href").replace(/^#?mail:/, "")); a.parentNode.replaceChild(mailChip(id, a.textContent.trim()), a);
    });
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), nodes = [], n;
    while ((n = walker.nextNode())) if (MAIL_ID.test(n.nodeValue) && !n.parentNode.closest("a,pre,code")) nodes.push(n), MAIL_ID.lastIndex = 0; else MAIL_ID.lastIndex = 0;
    nodes.forEach(function (tn) {
      var txt = tn.nodeValue, frag = document.createDocumentFragment(), last = 0, m;
      MAIL_ID.lastIndex = 0;
      while ((m = MAIL_ID.exec(txt))) { frag.appendChild(document.createTextNode(txt.slice(last, m.index).replace(/\(?\s*(mail\s*)?id\s*[:=]\s*$/i, ""))); frag.appendChild(mailChip(m[0])); last = MAIL_ID.lastIndex; }
      frag.appendChild(document.createTextNode(txt.slice(last).replace(/^\s*\)/, "")));
      tn.parentNode.replaceChild(frag, tn);
    });
  }

  function md(text) {
    text = String(text || "").replace(/\]\(\s*mail:/g, "](#mail:");
    var html = window.marked ? marked.parse(String(text || "")) : String(text || "").replace(/[&<]/g, function (c) { return c === "&" ? "&amp;" : "&lt;"; });
    if (window.DOMPurify) html = DOMPurify.sanitize(html, { ADD_ATTR: ["target"] });
    var box = h("div", { class: "md", html: html });
    box.querySelectorAll("table").forEach(function (t) { var w = h("div", { class: "tw" }); t.parentNode.insertBefore(w, t); w.appendChild(t); });
    box.querySelectorAll("img").forEach(function (im) {
      var src = im.getAttribute("src") || "";
      if (!/^(https?:|data:)/.test(src)) im.setAttribute("src", "/ws/" + src.replace(/^\/?(ws\/|workspace\/|file:)?/, ""));
      im.addEventListener("click", function () { call("open.file", { path: src.replace(/^\/?(ws\/|workspace\/|file:)?/, "") }); });
    });
    box.querySelectorAll("pre").forEach(function (p) {
      p.appendChild(h("button", { class: "cp", "aria-label": T("Copy", "Kopyala"), onclick: function (e) { e.stopPropagation(); call("copy", { text: p.innerText }); toast(T("Copied", "Kopyalandı")); } }, icon("copy", "xs")));
    });
    mailChips(box);
    box.addEventListener("click", function (e) {
      var a = e.target.closest("a"); if (!a) return;
      e.preventDefault();
      var href = a.getAttribute("href") || "";
      if (/^#?mail:/.test(href)) openMail(decodeURIComponent(href.replace(/^#?mail:/, "")));
      else if (/^task:/.test(href)) taskSheet(href.slice(5));
      else if (href) call("open.link", { url: href });
    });
    return box;
  }

  /* ------------------------------------------------------------ tool steps */
  var TOOLS = {
    web_search: ["search", "Searched the web", "Web'de arandı", "query"],
    read_page: ["book-open", "Read a page", "Sayfa okundu", "url"],
    browser: ["globe", "Browser", "Tarayıcı", "action"],
    http_request: ["globe", "Called a web service", "Web servisi çağrıldı", "url"],
    open_in_browser: ["external-link", "Opened a page", "Sayfa açıldı", "url"],
    mail: ["mail", "Mail", "Posta", "action"],
    calendar: ["calendar", "Calendar", "Takvim", "action"],
    people: ["users", "Address book", "Rehber", "query"],
    memory: ["brain", "Memory", "Hafıza", "action"],
    search_conversations: ["history", "Searched past chats", "Eski sohbetlerde arandı", "query"],
    list_files: ["folder", "Listed files", "Dosyalar listelendi", "path"],
    read_file: ["file-text", "Read a file", "Dosya okundu", "path"],
    write_file: ["file-pen", "Wrote a file", "Dosya yazıldı", "path"],
    delete_file: ["trash-2", "Deleted a file", "Dosya silindi", "path"],
    read_document: ["file-text", "Read a document", "Belge okundu", "path"],
    look_at_image: ["image", "Looked at a picture", "Resme bakıldı", "path"],
    export_file: ["download", "Saved a file", "Dosya kaydedildi", "path"],
    run_code: ["terminal", "Ran code", "Kod çalıştırıldı", "language"],
    delegate_task: ["bot", "Helper agent", "Yardımcı ajan", "goal"],
    phone_calendar: ["calendar-check", "Phone calendar", "Telefon takvimi", "action"],
    phone_alarm: ["alarm-clock", "Clock", "Saat", "action"],
    phone_reminder: ["bell", "Reminder", "Hatırlatma", "title"],
    schedule_task: ["clock", "Scheduled task", "Zamanlanmış görev", "title"],
    phone_notify: ["bell", "Notification", "Bildirim", "title"],
    phone_open: ["smartphone", "Opened on the phone", "Telefonda açıldı", "target"],
    phone_info: ["smartphone", "Checked the phone", "Telefon bilgisi", ""],
    phone_clipboard: ["copy", "Clipboard", "Pano", "action"],
    phone_speak: ["volume-2", "Spoke aloud", "Sesli okundu", ""],
    terminal: ["square-terminal", "Ran a command", "Komut çalıştırıldı", "command"],
    execute_code: ["code", "Ran code", "Kod çalıştırıldı", "language"],
    patch: ["file-diff", "Edited a file", "Dosya düzenlendi", "path"],
    search_files: ["file-search", "Searched files", "Dosyalarda arandı", "pattern"],
    web_extract: ["book-open", "Read pages", "Sayfalar okundu", "urls"],
    fetch: ["globe", "Fetched from the web", "Web'den alındı", "url"],
    download_file: ["file-down", "Downloaded a file", "Dosya indirildi", "url"],
    page_images: ["images", "Looked at a page's pictures", "Sayfadaki resimlere bakıldı", "url"],
    browser_use: ["globe", "Browser agent", "Tarayıcı ajanı", "task"],
    run_html: ["app-window", "Ran a web page", "Web sayfası çalıştırıldı", "path"],
    search_documents: ["text-search", "Searched your documents", "Belgelerinizde arandı", "question"],
    unpack_archive: ["package", "Unpacked an archive", "Arşiv açıldı", "path"],
    pack_archive: ["file-archive", "Packed an archive", "Arşiv oluşturuldu", "out"],
    folder: ["folder-sync", "Connected folder", "Bağlı klasör", "action"],
    todo: ["list-checks", "Updated the to-do list", "Yapılacaklar güncellendi", ""],
    clarify: ["message-circle-question", "Asked you", "Size soruldu", "question"],
    goal: ["target", "Goal", "Hedef", "action"],
    present_plan: ["map", "Presented a plan", "Plan sunuldu", ""],
    present_files: ["package", "Presented files", "Dosyalar sunuldu", ""],
    skills_list: ["graduation-cap", "Listed skills", "Yetenekler listelendi", ""],
    skill_view: ["graduation-cap", "Opened a skill", "Yetenek açıldı", "name"],
    skill_manage: ["graduation-cap", "Skill", "Yetenek", "action"],
    harness: ["sparkles", "Lessons", "Dersler", "action"],
    deep_research: ["telescope", "Deep research", "Derin araştırma", "query"],
    subagents: ["users-round", "Sub-agents", "Alt ajanlar", "action"],
    heartbeat: ["heart-pulse", "Heartbeat", "Periyodik görev", "action"],
    task_board: ["square-kanban", "Task board", "Görev panosu", "action"],
    search_all: ["search", "Searched everywhere", "Her yerde arandı", "query"],
    session_search: ["history", "Searched past chats", "Eski sohbetlerde arandı", "query"]
  };
  var BROWSER_STEPS = { navigate: ["open", "aç"], snapshot: ["look", "incele"], click: ["click", "tıkla"], type: ["type", "yaz"], back: ["back", "geri"], find: ["find", "bul"], download: ["download", "indir"], scroll: ["scroll", "kaydır"], press: ["key", "tuş"], select: ["choose", "seç"], wait: ["wait", "bekle"], screenshot: ["screenshot", "ekran görüntüsü"], close: ["close", "kapat"] };
  var ACTIONS = { list: ["list", "listele"], search: ["search", "ara"], read: ["read", "oku"], get_attachment: ["attachment", "ek"], draft: ["draft", "taslak"], send: ["send", "gönder"], send_now: ["send", "gönder"], mark_read: ["mark read", "okundu"], delete: ["delete", "sil"], move: ["move", "taşı"], archive: ["archive", "arşivle"], free: ["free time", "boş zaman"], create: ["create", "oluştur"], respond: ["respond", "yanıtla"], cancel: ["cancel", "iptal"], add: ["add", "ekle"], replace: ["update", "güncelle"], remove: ["remove", "kaldır"], calendars: ["calendars", "takvimler"], alarm: ["alarm", "alarm"], timer: ["timer", "zamanlayıcı"],
    open: ["open", "aç"], click: ["click", "tıkla"], type: ["type", "yaz"], select: ["choose", "seç"], check: ["tick", "işaretle"], press: ["key", "tuş"], scroll: ["scroll", "kaydır"], back: ["back", "geri"], forward: ["forward", "ileri"], reload: ["reload", "yenile"], wait: ["wait", "bekle"], text: ["read", "oku"], elements: ["look", "incele"], find: ["find", "bul"], screenshot: ["screenshot", "ekran görüntüsü"], user: ["your turn", "sizde"], close: ["close", "kapat"],
    set: ["set", "belirle"], update: ["update", "güncelle"], complete: ["complete", "tamamla"], block: ["blocked", "engellendi"], pause: ["pause", "duraklat"], resume: ["resume", "sürdür"], clear: ["clear", "temizle"], status: ["status", "durum"],
    view: ["view", "görüntüle"], edit: ["edit", "düzenle"], patch: ["edit", "düzenle"], reopen: ["reopen", "yeniden aç"], note: ["note", "not"], sync: ["sync", "eşitle"], connect: ["connect", "bağla"], spawn: ["start", "başlat"], message: ["message", "mesaj"], stop: ["stop", "durdur"], refine: ["refine", "iyileştir"], rollback: ["undo", "geri al"] };
  function stepInfo(name, args) {
    var bm = /^browser_(\w+)$/.exec(name || "");
    if (bm && !TOOLS[name]) { var ba = BROWSER_STEPS[bm[1]]; return { icon: "globe", label: T("Browser", "Tarayıcı") + " · " + (ba ? T(ba[0], ba[1]) : bm[1]), target: String((args && (args.url || args.text || args.description || args.ref)) || "").slice(0, 90) }; }
    var d = TOOLS[name] || ["wrench", name, name, ""];
    var label = T(d[1], d[2]);
    var target = "";
    if (d[3] === "action" && args && args.action) { var a = ACTIONS[args.action]; label += " · " + (a ? T(a[0], a[1]) : args.action); target = args.query || args.subject || args.title || args.url || args.text || args.why || (args.id && String(args.id).length < 12 ? "#" + args.id : "") || ""; }
    else if (d[3] && args) target = args[d[3]] || "";
    if (name === "read_page" && target) { try { target = new URL(target).host.replace(/^www\./, ""); } catch (e) {} }
    if (name === "read_page" && !target && args && args.link) target = "#" + args.link;
    return { icon: d[0], label: label, target: String(target).slice(0, 90) };
  }
  function stepEl(name, args, state, result) {
    var inf = stepInfo(name, args);
    var el = h("div", { class: "step " + state },
      icon(inf.icon, "s si"),
      h("div", { class: "st" }, h("b", null, inf.label), inf.target ? h("span", { class: "tg" }, inf.target) : null,
        result ? h("div", { class: "res" }, String(result).slice(0, 2500)) : null));
    if (result) el.addEventListener("click", function () { el.classList.toggle("open"); });
    return el;
  }

  /* ------------------------------------------------------------ chat render */
  function parseArgs(s) { try { return JSON.parse(s || "{}"); } catch (e) { return {}; } }

  function renderThread() {
    var scroll = $("#scroll"), th = clear($("#thread"));
    var msgs = (S.convo && S.convo.messages) || [];
    renderPlanBar(); renderCtx();
    if (!msgs.length && !S.running[S.convoId] && !(S.side[S.convoId || "_new"] || []).length) { th.appendChild(helloView()); updateTitle(); return; }
    var calls = {}, trace = null, turn = null, lastText = null;
    var running = !!S.running[S.convoId];
    function ensureTurn() { if (!turn) { turn = h("div", { class: "turn" }, h("div", { class: "who" }, h("div", { class: "mark" }, "y"), "yaver")); th.appendChild(turn); } return turn; }
    // which assistant texts end their turn (they get 👍/👎)
    var finals = {}, lastAns = -1;
    msgs.forEach(function (m, idx) {
      if (m.harness) return;
      if (m.role === "user" && !isInternalUser(m)) { if (lastAns >= 0) finals[lastAns] = true; lastAns = -1; }
      else if (m.role === "assistant" && !m.error && !m.local && m.content && String(m.content).trim()) lastAns = idx;
    });
    if (lastAns >= 0 && !running) finals[lastAns] = true;
    var side = (S.side[S.convoId || "_new"] || []).slice(), sp = 0;
    function flushSide(upto) {
      while (sp < side.length && (upto == null || side[sp].at <= upto)) {
        var el = sideEl(side[sp]); sp++;
        if (el) (turn || th).appendChild(el);
      }
    }
    msgs.forEach(function (m, idx) {
      flushSide(idx);
      if (m.harness) return;
      if (m.role === "user") {
        if (isInternalUser(m)) { trace = null; ensureTurn().appendChild(h("div", { class: "chiprow" }, h("span", { class: "nchip" }, icon(/goal round/.test(m.content) ? "target" : /heartbeat/.test(m.content) ? "heart-pulse" : "sparkle", "xs"), h("span", null, String(m.display || m.content).replace(/^(\(Reply in [^)]*\) )?/, "").slice(0, 160))))); return; }
        trace = null; turn = null; lastText = null;
        if (m.label || m.internal) { var lbl = m.label || T("Automatic turn", "Otomatik tur") + " · " + m.internal; th.appendChild(h("div", { class: "note" }, icon(/heartbeat/i.test(lbl) ? "heart-pulse" : /goal|hedef/i.test(lbl) ? "target" : /sub-?agent|alt ajan/i.test(lbl) ? "users-round" : "clock", "xs"), lbl)); return; }
        var files = [].concat(m.images || [], m.files || []);
        th.appendChild(h("div", { class: "u" }, h("div", null,
          h("div", { class: "bubble" }, m.display != null && m.display !== "" ? m.display : (m.content || "")),
          m.queued ? h("div", { class: "qcap" }, icon("clock", "xs"), T("Queued — yaver reads it at its next step", "Sırada — yaver bir sonraki adımında okuyacak")) : null,
          files.length ? h("div", { class: "files" }, files.map(function (f) {
            return /\.(png|jpe?g|gif|webp|bmp)$/i.test(f) ? h("img", { class: "thumb", src: "/ws/" + f, onclick: function () { call("open.file", { path: f }); } })
              : h("div", { class: "chipfile", onclick: function () { call("open.file", { path: f }); } }, icon("file-text", "xs"), h("span", null, f.split("/").pop()));
          })) : null)));
      } else if (m.role === "assistant") {
        if (m.error) { ensureTurn().appendChild(h("div", { class: "err" }, icon("triangle-alert", "s"), h("div", null, String(m.content || "").replace(/^⚠️\s*/, "")))); trace = null; return; }
        if (m.content && String(m.content).trim()) {
          trace = null;
          var box = md(m.content);
          var text = m.content;
          lastText = h("div", null, box, m.local ? null : h("div", { class: "acts" },
            h("button", { class: "ib", "aria-label": T("Copy", "Kopyala"), onclick: function () { call("copy", { text: text }); toast(T("Copied", "Kopyalandı")); } }, icon("copy", "s")),
            h("button", { class: "ib", "aria-label": T("Read aloud", "Sesli oku"), onclick: function () { call("speak", { text: text }); } }, icon("volume-2", "s")),
            h("button", { class: "ib", "aria-label": T("Share", "Paylaş"), onclick: function () { call("share.text", { text: text }); } }, icon("share-2", "s")),
            finals[idx] ? fbButtons(m, idx) : null));
          ensureTurn().appendChild(lastText);
          if (finals[idx] && S.fbAsk && S.fbAsk.convoId === S.convoId && S.fbAsk.idx === idx) lastText.appendChild(fbNoteBox(idx));
        }
        (m.tool_calls || []).forEach(function (tc) { calls[tc.id] = { name: (tc["function"] || {}).name, args: parseArgs((tc["function"] || {}).arguments) }; });
      } else if (m.role === "tool") {
        var c = calls[m.tool_call_id] || { name: m.name, args: {} };
        if (!trace) { trace = h("div", { class: "trace" }); ensureTurn().appendChild(trace); }
        trace.appendChild(stepEl(c.name, c.args, m.ok === false ? "bad" : "ok", m.content));
      }
    });
    flushSide(null);
    if (!running) { var fu = followupsEl(); if (fu) (turn || th).appendChild(fu); }
    if (S.running[S.convoId]) {
      var t = ensureTurn();
      if (S.liveTool) {
        if (!trace) { trace = h("div", { class: "trace" }); t.appendChild(trace); }
        trace.appendChild(stepEl(S.liveTool.name, S.liveTool.args, "run"));
      }
      var live = h("div", { id: "live" });
      if (S.live) live.appendChild(md(S.live));
      t.appendChild(live);
      t.appendChild(h("div", { class: "status", id: "lstatus" }, h("div", { class: "spin" }), h("span", null, S.liveStatus || (S.liveTool ? stepInfo(S.liveTool.name, S.liveTool.args).label + "…" : (S.live ? T("Writing…", "Yazıyor…") : (waitText() || T("Thinking…", "Düşünüyor…")))))));
    }
    // collapse long traces
    th.querySelectorAll(".trace").forEach(function (tr) {
      var steps = tr.querySelectorAll(".step:not(.run)");
      if (steps.length > 4) {
        for (var i = 0; i < steps.length - 2; i++) steps[i].classList.add("hidden");
        var more = h("button", { class: "more", onclick: function () { tr.querySelectorAll(".step.hidden").forEach(function (s) { s.classList.remove("hidden"); }); more.remove(); } }, T("Show all " + steps.length + " steps", steps.length + " adımın tümünü göster"));
        tr.insertBefore(more, tr.firstChild);
      }
    });
    updateTitle();
    requestAnimationFrame(function () { scroll.scrollTop = scroll.scrollHeight; });
  }

  var liveT = 0;
  function renderLive() {
    if (liveT) return;
    liveT = setTimeout(function () {
      liveT = 0;
      var live = $("#live");
      if (!live) { renderThread(); return; }
      clear(live).appendChild(md(S.live));
      var st = $("#lstatus span"); if (st) st.textContent = T("Writing…", "Yazıyor…");
      var sc = $("#scroll"); if (sc.scrollHeight - sc.scrollTop - sc.clientHeight < 240) sc.scrollTop = sc.scrollHeight;
    }, 60);
  }

  function greeting() { var hr = new Date().getHours(); return hr < 5 ? T("Good night", "İyi geceler") : hr < 12 ? T("Good morning", "Günaydın") : hr < 18 ? T("Good afternoon", "İyi günler") : T("Good evening", "İyi akşamlar"); }

  function helloView() {
    var st = S.state || {}, mail = st.mail || {};
    var ideas = [
      ["sun", T("Plan my day", "Günümü planla"), "plan"],
      ["inbox", T("What needs my reply in my mail?", "Postalarımda yanıt bekleyenler neler?")],
      ["calendar", T("What's on my calendar this week?", "Bu hafta takvimimde neler var?")],
      ["search", T("Brief me on today's news in my field", "Bugün alanımdaki önemli haberleri özetle")],
      ["alarm-clock", T("Remind me tomorrow at 9 to call the supplier", "Yarın 9'da tedarikçiyi aramamı hatırlat")]
    ];
    var pills = h("div", { class: "pills" },
      h("button", { class: "pill", onclick: function () { openSettings(); } }, h("span", { class: "dot " + ((st.llm || {}).ready ? "ok" : "bad") }), (st.llm || {}).model ? (st.llm.model.split("/").pop()) : T("No model", "Model yok")),
      h("button", { class: "pill", onclick: function () { openMailPage(); } }, h("span", { class: "dot " + (mail.connected ? "ok" : mail.needsSignIn ? "warn" : "") }), mail.connected ? T("Mail connected", "Posta bağlı") : mail.needsSignIn ? T("Mail: sign in", "Posta: oturum aç") : mail.configured ? T("Mail", "Posta") : T("Mail not set up", "Posta kurulmadı")));
    return h("div", { class: "hello" },
      h("div", { class: "mark big" }, "y"),
      h("h1", null, greeting() + "."),
      h("p", null, T("Ask anything — I can search the web, read your mail and calendar, work with files and set reminders.", "İstediğini sor — web'de arayabilir, postanı ve takvimini okuyabilir, dosyalarla çalışabilir, hatırlatma kurabilirim.")),
      h("div", { class: "ideas" }, ideas.map(function (it) {
        return h("button", { class: "idea", onclick: function () { if (it[2] === "plan") planDay(); else send(it[1]); } }, icon(it[0], "s"), h("span", null, it[1]));
      })),
      pills);
  }

  function updateTitle() {
    var t = $("#ctitle"); if (!t) return;
    var msgs = (S.convo && S.convo.messages) || [];
    t.textContent = msgs.length ? (S.convo.title || "yaver") : "yaver";
  }

  /* ------------------------------------------------------------ chat actions */
  function loadConvo(id) {
    if (!id) { S.convo = null; S.convoId = ""; S.live = ""; renderThread(); syncSend(); return Promise.resolve(); }
    return call("convos.load", { id: id }).then(function (c) {
      S.convo = c; S.convoId = c.id;
      if (c.running) { S.running[c.id] = true; S.live = c.live || ""; S.liveStatus = c.status || ""; } else delete S.running[c.id];
      var fuItems = Array.isArray(c.followups) ? c.followups : (c.followups && c.followups.items) || null;
      if (fuItems && fuItems.length && !(S.fu[c.id] && S.fu[c.id].state === "loading")) S.fu[c.id] = { state: "ready", items: fuItems, labels: (c.followups && c.followups.labels) || null };
      else if (fuItems && !fuItems.length && S.fu[c.id] && S.fu[c.id].state === "ready") delete S.fu[c.id];
      if (c.planning) S.plan[c.id] = c.planning;
      renderThread(); syncSend();
      if (!c.running) refreshCtx(c.id);
    }).catch(function () { S.convo = null; S.convoId = ""; renderThread(); });
  }

  function newChat(focus) {
    S.convo = null; S.convoId = ""; S.live = ""; S.liveTool = null; S.atts = []; S.fbAsk = null; S.todoOpen = false;
    delete S.side["_new"]; delete S.side[""];
    renderAtts(); renderThread(); syncSend(); closeDrawer(); closeAllPages();
    if (focus) setTimeout(function () { $("#input").focus(); }, 80);
  }

  /* things shown in the thread that are not messages: notes, cards, chips. They sit after the message count they arrived at. */
  function addSide(convoId, kind, data, key) {
    convoId = convoId || S.convoId || "_new";
    var list = S.side[convoId] || (S.side[convoId] = []);
    var isCur = convoId === (S.convoId || "_new");
    if (key) { for (var i = 0; i < list.length; i++) if (list[i].key === key) { list[i].d = data; if (isCur) renderThread(); return list[i]; } }
    var at = isCur && S.convo && S.convo.messages ? S.convo.messages.length : 1e9;
    var it = { k: kind, d: data, at: at, key: key };
    list.push(it);
    if (list.length > 60) list.shift();
    if (isCur) renderThread();
    return it;
  }
  function moveSide(from, to) {
    if (from === to) return;
    ["side", "plan", "fu", "subs"].forEach(function (k) { if (S[k][from]) { S[k][to] = S[k][from]; delete S[k][from]; } });
  }

  function send(text, opts) {
    opts = opts || {};
    var ta = $("#input");
    var t = text != null ? text : ta.value;
    if (!String(t).trim() && !S.atts.length) return;
    if (!(S.state && S.state.llm && S.state.llm.ready)) { openSetup(); return; }
    // an open clarifying question takes the typed text as its answer
    var pendingAsk = openAsk(S.convoId);
    if (text == null && pendingAsk && pendingAsk.d.kind === "clarify" && !S.atts.length) { ta.value = ""; autosize(); syncSend(); answerAsk(pendingAsk, { text: String(t).trim() }, String(t).trim()); return; }
    if (text == null && /^\/\w/.test(String(t).trim()) && !S.atts.length) { ta.value = ""; autosize(); hideHints(); syncSend(); runCommand(String(t).trim()); return; }
    var steering = !!S.running[S.convoId];
    if (steering && S.atts.length) { toast(T("yaver is still working — attach files after it finishes.", "yaver hâlâ çalışıyor — dosyaları bittikten sonra ekleyin.")); return; }
    var atts = S.atts.slice();
    var speak = S.voiceTurn; S.voiceTurn = false;
    ta.value = ""; autosize(); S.atts = []; renderAtts(); hideHints();
    if (S.fu[S.convoId]) delete S.fu[S.convoId];
    S.fbAsk = null;
    // show it at once
    if (!S.convo) S.convo = { id: "", title: String(t).slice(0, 60), messages: [] };
    S.convo.messages = (S.convo.messages || []).concat([opts.label ? { role: "user", content: t, label: opts.label } : { role: "user", content: t, display: opts.display || t, images: atts.filter(function (a) { return a.image; }).map(function (a) { return a.path; }), files: atts.filter(function (a) { return !a.image; }).map(function (a) { return a.path; }) }]);
    var tmpId = S.convoId || "_new";
    if (tmpId === "_new") moveSide("", "_new");
    S.running[tmpId] = true; S.convoId = tmpId; S.wait = { t0: Date.now(), info: null };
    if (!steering) { S.live = ""; S.liveTool = null; S.liveStatus = ""; }
    renderThread(); syncSend();
    var args = { convoId: tmpId === "_new" ? "" : tmpId, text: t, attachments: atts.map(function (a) { return a.path; }), speak: speak };
    if (opts.label) args.label = opts.label;
    if (opts.internal) args.internal = true;
    if (opts.display) args.display = opts.display;
    call("chat.send", args).then(function (r) {
      r = r || {};
      if (r.steered || r.queued) {
        var lm = S.convo && S.convo.messages && S.convo.messages[S.convo.messages.length - 1];
        if (lm && lm.role === "user" && lm.content === t) { lm.queued = true; if (S.convoId === tmpId) renderThread(); }
        if (r.convoId && S.convoId === "_new") { moveSide("_new", r.convoId); S.convoId = r.convoId; S.convo.id = r.convoId; }
        return;
      }
      delete S.running["_new"]; if (r.convoId) S.running[r.convoId] = true;
      if (r.convoId && (S.convoId === "_new" || S.convoId === tmpId)) { moveSide(tmpId, r.convoId); S.convoId = r.convoId; S.convo.id = r.convoId; S.convo.title = r.title || S.convo.title; }
      updateTitle(); refreshConvos();
    }).catch(function (e) {
      if (!steering) delete S.running[tmpId];
      renderThread(); syncSend(); toast(e.message);
    });
  }

  /* ------------------------------------------------------------ slash commands */
  var COMMANDS = [
    ["/plan", "Plan first, ask before acting (/plan off)", "Önce planla, uygulamadan önce sor (/plan off)", "map"],
    ["/goal", "Keep working on an objective in rounds (pause | resume | clear)", "Bir hedef üzerinde turlarla çalış (pause | resume | clear)", "target"],
    ["/autonomous", "Work without asking (on [limits] | off | status)", "Sormadan çalış (on [sınırlar] | off | status)", "rocket"],
    ["/compact", "Compact the context now", "Bağlamı şimdi sıkıştır", "minimize-2"],
    ["/refine", "Learn a lesson from this chat", "Bu sohbetten ders çıkar", "sparkles"],
    ["/harness", "Show what yaver learned", "yaver'in öğrendiklerini göster", "graduation-cap"],
    ["/heartbeat", "Repeat an instruction (e.g. 30m check mail)", "Bir talimatı tekrarla (ör. 30m postaya bak)", "heart-pulse"],
    ["/heartbeats", "List the repeating instructions", "Tekrarlanan talimatları listele", "repeat"],
    ["/subagents", "Helper agents of this chat", "Bu sohbetin yardımcı ajanları", "users-round"],
    ["/btw", "A quick side question, without derailing the work", "İşi bölmeden kısa bir yan soru", "message-circle-question"],
    ["/today", "What's on today", "Bugün neler var", "sun"]
  ];
  function runCommand(text) {
    var cid = S.convoId === "_new" ? "" : S.convoId;
    addSide(S.convoId || "_new", "cmd", { text: text });
    call("command", { convoId: cid, text: text }).then(function (r) {
      r = r || {};
      if (r.convoId && !S.convoId) { moveSide("_new", r.convoId); S.convoId = r.convoId; if (!S.convo) S.convo = { id: r.convoId, title: "", messages: [] }; }
      if (r.note) addSide(r.convoId || S.convoId, "note", { text: r.note });
      if (r.started) { var sc = r.convoId || S.convoId; if (sc && sc !== "_new") { S.running[sc] = true; loadConvo(sc); refreshConvos(); } }
      else if (r.send) send(r.send, { label: r.label, internal: r.internal });
      if (/^\/compact\b/.test(text)) refreshCtx(S.convoId);
    }).catch(function (e) { addSide(S.convoId, "note", { text: e.message, bad: true }); });
  }
  function hideHints() { var b = $("#hints"); if (b) b.classList.add("hidden"); }
  function updateHints() {
    var box = $("#hints"), ta = $("#input"); if (!box || !ta) return;
    var v = ta.value;
    if (!/^\/\S*$/.test(v)) { box.classList.add("hidden"); return; }
    var f = v.toLowerCase();
    var list = COMMANDS.filter(function (c) { return c[0].indexOf(f) === 0; });
    clear(box);
    if (!list.length) { box.classList.add("hidden"); return; }
    list.forEach(function (c) {
      box.appendChild(h("button", { class: "hint", onclick: function () { ta.value = c[0] + " "; autosize(); syncSend(); hideHints(); ta.focus(); } }, icon(c[3], "s"), h("div", null, h("b", null, c[0]), h("small", null, T(c[1], c[2])))));
    });
    box.classList.remove("hidden");
  }

  function stop() { if (S.convoId) call("chat.stop", { convoId: S.convoId }); }

  function planDay() {
    newChat();
    send(T("Plan my day: check today's company calendar and my unread or important e-mail. Give me today's meetings in order with what to prepare, the e-mails that need my answer (most important first), free blocks for focused work, and a suggested order for the day.",
      "Günümü planla: bugünkü kurum takvimime ve okunmamış ya da önemli e-postalarıma bak. Bugünkü toplantıları sırayla ve neye hazırlanmam gerektiğiyle, yanıt bekleyen e-postaları (en önemlisi önce), odaklı çalışma için boş zamanları ve gün için önerilen sırayı ver."));
  }

  function listen(auto) {
    call("voice.listen").then(function (t) {
      if (!t) return;
      var ta = $("#input"); ta.value = ta.value ? ta.value.replace(/\s*$/, " ") + t : t; autosize(); syncSend();
      if (auto) { S.voiceTurn = !!(S.state && S.state.phone && S.state.phone.speakVoice); send(); }
    }).catch(function (e) { toast(e.message); });
  }

  function pick() {
    call("files.pick").then(function (list) { (list || []).forEach(function (f) { S.atts.push(f); }); renderAtts(); syncSend(); }).catch(function (e) { toast(e.message); });
  }

  function renderAtts() {
    var box = $("#atts"); if (!box) return; clear(box);
    box.classList.toggle("hidden", !S.atts.length);
    S.atts.forEach(function (a, i) {
      box.appendChild(h("div", { class: "att" }, a.image ? h("img", { src: "/ws/" + a.path }) : icon("file-text", "s"), h("span", null, a.name || a.path.split("/").pop()),
        h("button", { class: "x", "aria-label": T("Remove", "Kaldır"), onclick: function () { S.atts.splice(i, 1); renderAtts(); syncSend(); } }, icon("x", "xs"))));
    });
  }

  function autosize() { var ta = $("#input"); if (!ta) return; ta.style.height = "auto"; ta.style.height = Math.min(160, ta.scrollHeight) + "px"; }

  function syncSend() {
    var b = $("#send"); if (!b) return;
    var running = !!S.running[S.convoId];
    var has = ($("#input").value.trim().length > 0) || S.atts.length > 0;
    clear(b);
    if (running && !has) { b.className = "send stop"; b.appendChild(icon("square", "xs")); b.setAttribute("aria-label", T("Stop", "Durdur")); b.disabled = false; }
    else if (has) { b.className = "send"; b.appendChild(icon("arrow-up")); b.setAttribute("aria-label", T("Send", "Gönder")); b.disabled = false; }
    else { b.className = "send"; b.appendChild(icon("mic")); b.setAttribute("aria-label", T("Speak", "Konuş")); b.disabled = false; }
  }

  /* ------------------------------------------------------------ events from the core */
  /* live status while the model works, as in the original: hidden reasoning characters, the tool call being written, seconds */
  function fmtK(n) { return n >= 1000 ? (n / 1000).toFixed(1).replace(".", S.lang === "tr" ? "," : ".") + (S.lang === "tr" ? " bin" : "k") : String(n); }
  function waitText() {
    var w = S.wait; if (!w) return "";
    var sec = Math.round((Date.now() - w.t0) / 1000), i = w.info, t = "";
    if (i && i.tool) t = T("preparing a tool call: ", "araç çağrısı hazırlanıyor: ") + i.tool + (i.calls > 1 ? " (+" + (i.calls - 1) + ")" : "") + (i.args ? " · " + fmtK(i.args) + T(" chars", " karakter") : "") + (i.reasoning ? T(" · thinking ", " · düşünme ") + fmtK(i.reasoning) : "");
    else if (i && i.reasoning) t = T("thinking · ", "düşünüyor · ") + fmtK(i.reasoning) + T(" chars", " karakter");
    else t = T("waiting for the model", "model yanıtı bekleniyor");
    return t + (sec >= 2 ? " · " + sec + T(" s", " sn") : "");
  }
  function waitTick() {
    if (!S.running[S.convoId] || S.live || S.liveStatus || S.liveTool) return;
    var st = $("#lstatus span"); if (st) st.textContent = waitText() || T("Thinking…", "Düşünüyor…");
  }
  setInterval(waitTick, 1000);

  function onEvent(type, d) {
    d = d || {};
    if (type === "run") {
      var id = d.convoId;
      if (d.phase === "done") {
        delete S.running[id];
        if (id === S.convoId) { S.live = ""; S.liveTool = null; S.liveStatus = ""; loadConvo(id); if (d.error) toast(d.error); }
        refreshConvos(); return;
      }
      if (d.phase === "start") {
        S.running[id] = true;
        if (id === S.convoId) { S.live = ""; S.liveTool = null; S.liveStatus = d.label || ""; if (S.fu[id]) delete S.fu[id]; loadConvo(id); }
        refreshConvos(); return;
      }
      S.running[id] = true;
      if (id !== S.convoId) return;
      if (d.phase !== "delta" && d.phase !== "think" && d.phase !== "progress") { S.wait = { t0: Date.now(), info: null }; }
      if (d.phase === "think") { if (!S.wait) S.wait = { t0: Date.now(), info: null }; S.wait.info = d; waitTick(); return; }
      if (d.phase === "delta") { S.live += d.text; S.liveStatus = ""; renderLive(); }
      else if (d.phase === "step") { S.live = ""; loadConvo(id); }
      else if (d.phase === "tool") { S.liveTool = { name: d.name, args: d.args || {} }; S.liveStatus = ""; renderThread(); }
      else if (d.phase === "toolEnd") { S.liveTool = null; loadConvo(id); }
      else if (d.phase === "progress") { S.liveStatus = d.text; var st = $("#lstatus span"); if (st) st.textContent = d.text; }
    } else if (type === "approve") approveSheet(d);
    else if (type === "launch") onLaunch(d);
    else if (type === "toast") toast(d.text);
    else if (type === "refresh") { refreshState(); refreshConvos(); }
    else if (type === "resume") { S.state = d; if (S.convoId) loadConvo(S.convoId); if (topPage() === "mail") loadMail(); if (topPage() === "settings") renderSettings(); }
    else if (type === "mail" || type === "mail.changed") { refreshState().then(function () { if (topPage() === "mail") loadMail(); }); }
    else if (type === "note") addSide(d.convoId, "note", { text: d.text });
    else if (type === "followups") {
      var fid = d.convoId; if (!fid) return;
      if (d.state === "none") delete S.fu[fid];
      else S.fu[fid] = { state: d.state, items: d.items || [], labels: d.labels || null, text: d.text };
      if (fid === S.convoId && !S.running[fid]) renderThread();
    }
    else if (type === "memory") addSide(d.convoId, "memory", d);
    else if (type === "refine") {
      if (d.convoId || S.convoId) addSide(d.convoId || S.convoId, "refine", d, d.id ? "refine:" + d.id : null);
      else toast(d.title || "", function () { openKnowledge("lessons"); });
      kbChanged();
    }
    else if (type === "lesson_suspended" || type === "sleep") { toast(d.text || "", d.open ? function () { openKnowledge(d.open); } : null, 5000); kbChanged(); }
    else if (type === "ask") onAsk(d);
    else if (type === "planning") onPlanning(d);
    else if (type === "subagent") {
      var sid = d.convoId; if (!sid) return;
      var subs = S.subs[sid] || (S.subs[sid] = {});
      subs[d.name] = d;
      addSide(sid, "sub", d, "sub:" + d.name);
    }
    else if (type === "btw") addSide(d.convoId, "btw", d, "btw:" + d.id);
    else if (type === "folders") { S.folders = d.folders || d; if (topPage() === "settings") renderExtras(); }
    else if (type === "folders.note") toast(d.text);
    else if (type === "tasks") { refreshOverdue(); if (topPage() === "tasks") loadTasks(); }
    else if (type === "osync") { if (topPage() === "tasks") loadOsync(); }
    else if (type === "memory.changed") kbChanged();
    else if (type === "s1") { /* System-1 stats changed; the log sheet loads fresh when opened */ }
    else if (type === "mail.triage" || type === "mail.index" || type === "mail.draft") { /* mail page refreshes on demand */ }
  }

  /* ------------------------------------------------------------ chat extras: side items */
  var INTERNAL_RX = /^(\(Reply in [^)]*\) )?\[(harness|verify-on-stop|goal round|Context summary|heartbeat)/;
  function isInternalUser(m) { return m.role === "user" && !m.label && !m.display && !m.steer && INTERNAL_RX.test(String(m.content || "")); }

  function sideEl(it) {
    var d = it.d || {};
    switch (it.k) {
      case "cmd": return h("div", { class: "u cmd" }, h("div", null, h("div", { class: "bubble mono" }, d.text)));
      case "note": return h("div", { class: "chiprow" }, h("span", { class: "nchip" + (d.bad ? " bad" : "") }, icon(noteIcon(d.text), "xs"), h("span", null, stripEmoji(d.text))));
      case "memory": return h("div", { class: "chiprow" }, h("button", { class: "nchip act", onclick: function () {
        sheet({ title: d.label, body: h("div", { class: "res", style: "display:block;white-space:pre-wrap" }, d.tip || ""), actions: [[T("Close", "Kapat")], [d.open === "lessons" ? T("Open lessons", "Dersleri aç") : T("Open memory", "Hafızayı aç"), "p", function () { openKnowledge(d.open === "lessons" ? "lessons" : "memory"); }]] });
      } }, icon("brain", "xs"), h("span", null, d.label)));
      case "refine": return refineEl(d);
      case "ask": return askEl(it);
      case "plan": return planCardEl(it);
      case "files": return filesEl(d.files || []);
      case "sub": return h("div", { class: "chiprow" }, h("button", { class: "nchip act sub " + (d.status || ""), onclick: function () { if (d.childConvoId) loadConvo(d.childConvoId); } },
        d.status === "running" || d.status === "starting" ? h("span", { class: "spin xs" }) : icon(d.status === "error" ? "circle-alert" : d.status === "stopped" ? "circle-pause" : "users-round", "xs"),
        h("span", null, T("Helper ", "Yardımcı ") + d.name + " · " + subStatus(d.status) + (d.runs ? " · " + d.runs + T(" runs", " tur") : ""))));
      case "btw": return h("div", { class: "card btw" }, h("div", { class: "ch" }, icon("message-circle-question", "s"), h("b", null, "btw"), h("span", null, d.question)),
        d.phase === "answer" ? h("div", { class: d.error ? "err" : "" }, md(d.answer || "")) : h("div", { class: "status" }, h("div", { class: "spin" }), T("Thinking…", "Düşünüyor…")));
    }
    return null;
  }
  function subStatus(s) { return { starting: T("starting", "başlıyor"), running: T("working", "çalışıyor"), idle: T("done", "bitti"), stopped: T("stopped", "durduruldu"), error: T("error", "hata") }[s] || s || ""; }
  function stripEmoji(t) { return String(t || "").replace(/^\s*(🗜|🧠|✨|🤖|💓|✅|🌙|⚠️|📋|🎯)\s*/u, ""); }
  function noteIcon(t) {
    t = String(t || "");
    if (/^\s*🗜/u.test(t)) return "minimize-2"; if (/^\s*🧠/u.test(t)) return "brain"; if (/^\s*✨/u.test(t)) return "sparkles"; if (/^\s*🤖/u.test(t)) return "rocket";
    if (/^\s*💓/u.test(t)) return "heart-pulse"; if (/^\s*✅/u.test(t)) return "circle-check"; if (/^\s*🌙/u.test(t)) return "moon"; if (/^\s*⚠️/u.test(t)) return "triangle-alert";
    if (/plan/i.test(t)) return "map"; if (/goal|hedef/i.test(t)) return "target";
    return "info";
  }

  /* 👍/👎 under a final answer */
  function fbButtons(m, idx) {
    var wrap = h("span", { class: "fbb" });
    [[1, "thumbs-up", T("Good answer", "İyi yanıt")], [-1, "thumbs-down", T("Bad answer — yaver learns from it", "Kötü yanıt — yaver ders çıkarır")]].forEach(function (b) {
      wrap.appendChild(h("button", { class: "ib" + ((m.fb || 0) === b[0] ? " on" + (b[0] < 0 ? " neg" : "") : ""), "aria-label": b[2], "aria-pressed": String((m.fb || 0) === b[0]), onclick: function () {
        var cid = S.convoId;
        call("feedback", { convoId: cid, idx: idx, vote: b[0] }).then(function (r) {
          r = r || {}; m.fb = r.fb != null ? r.fb : ((m.fb || 0) === b[0] ? 0 : b[0]);
          S.fbAsk = r.askNote ? { convoId: cid, idx: idx, placeholder: r.placeholder } : null;
          if (r.note) toast(r.note);
          if (cid === S.convoId) renderThreadKeep();
        }).catch(function (e) { toast(e.message); });
      } }, icon(b[1], "s")));
    });
    return wrap;
  }
  function fbNoteBox(idx) {
    var inp = h("input", { type: "text", maxlength: "400", placeholder: (S.fbAsk && S.fbAsk.placeholder) || T("What did I get wrong? (optional)", "Neyi yanlış yaptım? (isteğe bağlı)") });
    function done() { S.fbAsk = null; renderThreadKeep(); }
    function go() {
      var v = inp.value.trim(); if (!v) { done(); return; }
      call("feedback", { convoId: S.convoId, idx: idx, vote: -1, comment: v }).then(function (r) { toast((r && r.note) || T("Thanks — yaver will learn from this", "Teşekkürler — yaver bundan ders çıkaracak")); }).catch(function (e) { toast(e.message); });
      done();
    }
    inp.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); go(); } });
    if (S.fbAsk && !S.fbAsk.focused) { S.fbAsk.focused = true; setTimeout(function () { inp.focus({ preventScroll: true }); var f = inp.closest(".fbnote"); if (f) f.scrollIntoView({ block: "nearest" }); }, 60); }
    if (S.fbAsk && S.fbAsk.draft) inp.value = S.fbAsk.draft;
    inp.addEventListener("input", function () { if (S.fbAsk) S.fbAsk.draft = inp.value; });
    return h("div", { class: "fbnote" }, inp, h("div", { class: "btnrow" }, h("button", { class: "btn sm ghost", onclick: done }, T("Skip", "Geç")), h("button", { class: "btn sm p", onclick: go }, T("Send", "Gönder"))));
  }
  function renderThreadKeep() { var sc = $("#scroll"), top = sc ? sc.scrollTop : 0; renderThread(); requestAnimationFrame(function () { if (sc) sc.scrollTop = top; }); }

  /* follow-up suggestions under the last answer */
  function followupsEl() {
    var f = S.fu[S.convoId]; if (!f) return null;
    if (f.state === "loading") return h("div", { class: "followups loading" }, h("div", { class: "spin xs" }), h("span", null, f.text || T("Preparing next-step suggestions…", "Sonraki adım önerileri hazırlanıyor…")));
    if (!f.items || !f.items.length) return null;
    var L = f.labels || {};
    return h("div", { class: "followups" },
      h("div", { class: "fuh" }, icon("sparkles", "xs"), h("span", null, L.title || T("Next steps", "Sonraki adımlar")),
        h("button", { class: "ib fux", "aria-label": L.hide || T("Hide suggestions", "Önerileri gizle"), onclick: function () { var cid = S.convoId; delete S.fu[cid]; call("followups.clear", { convoId: cid }).catch(function () {}); renderThreadKeep(); } }, icon("x", "xs"))),
      f.items.map(function (t) { return h("button", { class: "fu", onclick: function () { send(t); } }, h("span", null, t), icon("arrow-right", "xs")); }));
  }

  /* "yaver learned something" */
  function refineEl(d) {
    var undone = false;
    var btn = d.canUndo ? h("button", { class: "btn sm", onclick: function () {
      if (undone) return; undone = true; btn.disabled = true;
      call("lessons.undo", { id: d.id, convoId: d.convoId || S.convoId }).then(function () { btn.textContent = T("Rolled back", "Geri alındı"); }).catch(function (e) { undone = false; btn.disabled = false; toast(e.message); });
    } }, icon("undo-2", "xs"), d.undoLabel || T("Undo", "Geri al")) : null;
    return h("div", { class: "card refine" },
      h("div", { class: "ch" }, icon("sparkles", "s"), h("b", null, d.title), d.scopeLabel ? h("span", null, "· " + d.scopeLabel) : null),
      d.summary ? h("p", null, d.summary) : null,
      d.items && d.items.length ? h("ul", null, d.items.map(function (a) { return h("li", null, h("b", null, a.kindLabel || a.kind), a.text || "", a.detail ? h("small", null, a.detail) : null); })) : (d.empty ? h("p", { class: "mut" }, d.empty) : null),
      d.rejected ? h("p", { class: "mut" }, d.rejected) : null,
      d.expected ? h("p", { class: "mut" }, d.expected) : null,
      h("div", { class: "cf" }, btn, h("button", { class: "btn sm ghost", onclick: function () { openKnowledge("lessons"); } }, T("All lessons", "Tüm dersler"))));
  }

  /* deliverables (present_files) */
  function filesEl(files) {
    return h("div", { class: "deliv" }, files.map(function (f) {
      var img = f.image || /\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(f.path || "");
      return h("div", { class: "dcard" },
        img ? h("img", { class: "dthumb", src: "/ws/" + f.path, alt: "", onclick: function () { call("open.file", { path: f.path }); } }) : h("div", { class: "di" }, icon(/\.(zip|tar|gz|7z)$/i.test(f.path || "") ? "file-archive" : "file-text", "s")),
        h("div", { class: "dt", onclick: function () { call("open.file", { path: f.path }); } }, h("b", null, f.name || String(f.path || "").split("/").pop()), h("small", null, f.description || ((f.size != null ? bytes(f.size) + " · " : "") + f.path))),
        h("button", { class: "ib", "aria-label": T("Open", "Aç"), onclick: function () { call("open.file", { path: f.path }).catch(function (e) { toast(e.message); }); } }, icon("eye", "s")),
        h("button", { class: "ib", "aria-label": T("Share", "Paylaş"), onclick: function () { call("share.file", { path: f.path }).catch(function (e) { toast(e.message); }); } }, icon("share-2", "s")));
    }));
  }

  /* clarify / plan questions (blocking: the tool waits for ask.answer) */
  function openAsk(cid) {
    var l = S.side[cid || "_new"] || [];
    for (var i = l.length - 1; i >= 0; i--) if (l[i].k === "ask" && !l[i].done) return l[i];
    return null;
  }
  function onAsk(d) {
    var it = addSide(d.convoId, "ask", d, "ask:" + d.reqId);
    if (d.convoId !== S.convoId || S.pages.length) {
      toast(d.kind === "plan" ? T("yaver has a plan for you to approve", "yaver onayınız için bir plan hazırladı") : T("yaver has a question for you", "yaver size bir soru soruyor"), function () { closeAllPages(); if (d.convoId !== S.convoId) loadConvo(d.convoId); }, 6000);
    }
    return it;
  }
  function answerAsk(it, answer, shown) {
    if (it.done) return;
    it.done = true; it.answer = shown || "";
    call("ask.answer", { reqId: it.d.reqId, answer: answer }).catch(function (e) { toast(e.message); });
    renderThread();
  }
  function askEl(it) {
    var d = it.d;
    if (d.kind === "plan") return planBody(it, function (a, shown) { answerAsk(it, a, shown); });
    var head = h("div", { class: "ch" }, icon("message-circle-question", "s"), h("b", null, T("yaver asks", "yaver soruyor")));
    if (it.done) return h("div", { class: "card ask done" }, head, h("p", null, d.question), h("div", { class: "answered" }, icon("check", "xs"), it.answer ? it.answer : T("Skipped — yaver will assume", "Geçildi — yaver bir varsayım yapacak")));
    var inp = h("input", { type: "text", placeholder: T("Your answer", "Yanıtınız") });
    function go() { var v = inp.value.trim(); if (v) answerAsk(it, { text: v }, v); }
    inp.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); go(); } });
    return h("div", { class: "card ask" }, head, h("div", { class: "q" }, md(d.question || "")),
      (d.choices || []).length ? h("div", { class: "choices" }, d.choices.map(function (c) { return h("button", { class: "choice", onclick: function () { answerAsk(it, { text: c }, c); } }, c); })) : null,
      h("div", { class: "arow" }, inp, h("button", { class: "send sm", "aria-label": T("Answer", "Yanıtla"), onclick: go }, icon("arrow-up", "s"))),
      h("button", { class: "btn sm ghost skip", onclick: function () { answerAsk(it, null, ""); } }, T("Skip — let yaver decide", "Geç — yaver karar versin")));
  }
  /* the plan card: used for the blocking ask (kind plan) and for the non-blocking planning event (type plan → plan.approve) */
  function planBody(it, answer) {
    var d = it.d;
    var head = h("div", { class: "ch" }, icon("map", "s"), h("b", null, T("Proposed plan", "Önerilen plan")));
    var box = h("div", { class: "planmd" }, md(d.plan || ""));
    if (it.done) return h("div", { class: "card plan done" }, head, box, h("div", { class: "answered" }, icon("check", "xs"), it.answer));
    return h("div", { class: "card plan" }, head, box,
      h("div", { class: "cf" },
        h("button", { class: "btn sm p", onclick: function () { answer({ action: "approve" }, T("Approved — executing", "Onaylandı — uygulanıyor")); } }, icon("check", "xs"), T("Approve and run", "Onayla ve çalıştır")),
        h("button", { class: "btn sm", onclick: function () { editSheet({ title: T("Edit the plan", "Planı düzenle"), multi: true, value: d.plan, ok: T("Approve", "Onayla"), save: function (v) { answer({ action: "approve", plan: v }, T("Approved with your edits", "Düzenlemelerinizle onaylandı")); } }); } }, icon("pencil", "xs"), T("Edit", "Düzenle")),
        h("button", { class: "btn sm ghost", onclick: function () { editSheet({ title: T("Keep planning", "Planlamaya devam"), text: T("What should change in the plan?", "Planda ne değişmeli?"), multi: true, ok: T("Send", "Gönder"), save: function (v) { answer({ action: "revise", text: v.trim() }, T("Keep planning", "Planlamaya devam") + (v.trim() ? ": " + v.trim() : "")); } }); } }, T("Keep planning", "Planlamaya devam"))));
  }
  function planCardEl(it) {
    return planBody(it, function (a, shown) {
      if (it.done) return; it.done = true; it.answer = shown;
      if (a.action === "approve") call("plan.approve", { convoId: it.d.convoId || S.convoId, plan: a.plan || null }).then(function () { S.running[S.convoId] = true; renderThread(); syncSend(); }).catch(function (e) { it.done = false; toast(e.message); renderThread(); });
      else { renderThread(); send(a.text || T("Keep planning: revise the plan.", "Planlamaya devam: planı gözden geçir.")); }
      renderThread();
    });
  }

  /* planning: todos, goal, plan mode, autonomous */
  function onPlanning(d) {
    var cid = d.convoId;
    if (d.type === "state") { S.plan[cid] = d; if (cid === S.convoId) renderPlanBar(); }
    else if (d.type === "note") addSide(cid, "note", { text: d.text });
    else if (d.type === "files") addSide(cid, "files", d);
    else if (d.type === "plan") addSide(cid, "plan", d);
  }
  function planCmd(text) {
    call("command", { convoId: S.convoId, text: text }).then(function (r) {
      r = r || {};
      if (r.note) addSide(S.convoId, "note", { text: r.note });
      if (r.started) { S.running[S.convoId] = true; loadConvo(S.convoId); }
      else if (r.send) send(r.send, { label: r.label, internal: r.internal });
      call("planning.state", { convoId: S.convoId }).then(function (st) { if (st) { S.plan[S.convoId] = st; renderPlanBar(); } }).catch(function () {});
    }).catch(function (e) { toast(e.message); });
  }
  function renderPlanBar() {
    var bar = $("#planbar"); if (!bar) return;
    clear(bar);
    var p = S.convoId && S.plan[S.convoId];
    if (!p) { bar.classList.add("hidden"); return; }
    var todos = p.todos || [], g = p.goal, a = p.autonomous && p.autonomous.enabled ? p.autonomous : null;
    var rows = [];
    if (p.planMode) rows.push(h("div", { class: "pb-row" }, h("span", { class: "gpill plan" }, T("PLAN", "PLAN")), h("span", { class: "gt" }, T("yaver plans first; nothing changes until you approve.", "yaver önce planlar; onaylayana kadar hiçbir şey değişmez.")),
      h("button", { class: "btn xs", onclick: function () { planCmd("/plan off"); } }, T("Exit", "Çık"))));
    if (g) {
      var st = g.status || "active";
      rows.push(h("div", { class: "pb-row" }, icon("target", "xs gi"),
        h("button", { class: "gt", onclick: function () { sheet({ title: T("Goal", "Hedef"), body: h("dl", { class: "dl" }, h("dt", null, T("Objective", "Hedef")), h("dd", null, g.objective), h("dt", null, T("Status", "Durum")), h("dd", null, goalStatus(st) + " · " + (g.round || 0) + "/" + (g.maxRounds || 0)), g.notes ? [h("dt", null, T("Notes", "Notlar")), h("dd", null, g.notes)] : null, g.summary ? [h("dt", null, T("Summary", "Özet")), h("dd", null, g.summary)] : null), actions: [[T("Close", "Kapat")]] }); } }, g.objective),
        h("span", { class: "gpill " + st }, goalStatus(st)), h("span", { class: "gn" }, (g.round || 0) + "/" + (g.maxRounds || 0)),
        st === "active" ? h("button", { class: "ib sm", "aria-label": T("Pause goal", "Hedefi duraklat"), onclick: function () { planCmd("/goal pause"); } }, icon("circle-pause", "s"))
          : st !== "complete" ? h("button", { class: "ib sm", "aria-label": T("Resume goal", "Hedefi sürdür"), onclick: function () { planCmd("/goal resume"); } }, icon("circle-play", "s")) : null,
        h("button", { class: "ib sm", "aria-label": T("Clear goal", "Hedefi kaldır"), onclick: function () { planCmd("/goal clear"); } }, icon("x", "xs"))));
    }
    if (a) {
      var u = a.used || {}, L = a.limits || {};
      rows.push(h("div", { class: "pb-row" }, icon("rocket", "xs gi"),
        h("button", { class: "gt", onclick: function () { sheet({ title: T("Autonomous mode", "Otonom mod"), text: a.status || "", actions: [[T("Close", "Kapat")], [T("Turn off", "Kapat"), "danger", function () { planCmd("/autonomous off"); }]] }); } }, h("b", null, T("Autonomous ", "Otonom ")), (a.gates || []).length ? T("gates: ", "kontroller: ") + a.gates.join(", ") : T("no gates", "kontrol yok")),
        h("span", { class: "gpill active" }, "AUTO"), h("span", { class: "gn" }, (u.continuations || 0) + "/" + (L.maxContinuations === "unlimited" || L.maxContinuations == null ? "∞" : L.maxContinuations)),
        h("button", { class: "ib sm", "aria-label": T("Turn autonomous mode off", "Otonom modu kapat"), onclick: function () { planCmd("/autonomous off"); } }, icon("x", "xs"))));
    }
    if (todos.length) {
      var done = todos.filter(function (t) { return t.status === "completed"; }).length;
      var cur = todos.filter(function (t) { return t.status === "in_progress"; })[0] || todos.filter(function (t) { return t.status === "pending"; })[0];
      rows.push(h("button", { class: "pb-row todo-h", "aria-expanded": String(S.todoOpen), onclick: function () { S.todoOpen = !S.todoOpen; renderPlanBar(); } },
        icon("list-checks", "xs gi"), h("span", { class: "gt" }, h("b", null, done + "/" + todos.length), " ", cur && done < todos.length ? cur.content : T("All done", "Hepsi bitti")),
        h("span", { class: "tprog" }, h("i", { style: "width:" + Math.round(done * 100 / todos.length) + "%" })),
        icon(S.todoOpen ? "chevron-down" : "chevron-right", "xs")));
      if (S.todoOpen) rows.push(h("div", { class: "todos" }, todos.map(function (t) {
        var ic = { completed: "circle-check", in_progress: "circle-dot", cancelled: "circle-x" }[t.status] || "circle";
        return h("div", { class: "todo " + t.status }, icon(ic, "xs"), h("span", null, t.content));
      })));
    }
    if (!rows.length) { bar.classList.add("hidden"); return; }
    rows.forEach(function (r) { bar.appendChild(r); });
    bar.classList.remove("hidden");
  }
  function goalStatus(s) { return { active: T("active", "etkin"), paused: T("paused", "duraklatıldı"), complete: T("done", "tamam"), blocked: T("blocked", "engellendi") }[s] || s; }

  /* context meter */
  var ctxT = 0;
  function refreshCtx(cid) {
    if (!cid || cid === "_new") return;
    clearTimeout(ctxT);
    ctxT = setTimeout(function () { call("ctx.stats", { convoId: cid }).then(function (st) { if (st) { S.ctx[cid] = st; if (cid === S.convoId) renderCtx(); } }).catch(function () {}); }, 120);
  }
  function renderCtx() {
    var b = $("#ctxm"); if (!b) return;
    var st = S.convoId && S.ctx[S.convoId];
    var msgs = (S.convo && S.convo.messages) || [];
    if (!st || !msgs.length) { b.classList.add("hidden"); return; }
    b.classList.remove("hidden");
    b.className = "ctxm " + (st.level || "");
    var pct = Math.max(0, Math.min(100, st.pct || 0)), r = 7, c = 2 * Math.PI * r;
    clear(b);
    var svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 18 18"); svg.setAttribute("class", "ring");
    svg.innerHTML = '<circle cx="9" cy="9" r="' + r + '" class="bg"/><circle cx="9" cy="9" r="' + r + '" class="fg" stroke-dasharray="' + (c * pct / 100).toFixed(1) + " " + c.toFixed(1) + '" transform="rotate(-90 9 9)"/>';
    b.appendChild(svg);
    b.appendChild(h("span", null, pct + "%"));
    b.setAttribute("aria-label", st.title || T("Context", "Bağlam"));
  }
  function k(n) { return n >= 1000 ? (n / 1000).toFixed(n >= 10000 ? 0 : 1) + "k" : String(n || 0); }
  function ctxSheet() {
    var cid = S.convoId, st = S.ctx[cid]; if (!st) return;
    var log = (st.log || []).slice(0, 5);
    var winSrc = { set: T("set by you", "sizin ayarınız"), learned: T("learned", "öğrenildi"), known: T("known", "bilinen") }[st.windowSource] || "";
    var sumBox = st.summary ? h("details", { class: "sum" }, h("summary", null, T("Summary of older messages", "Eski mesajların özeti")), h("div", { class: "res", style: "display:block;white-space:pre-wrap;max-height:30vh;overflow:auto" }, st.summary)) : null;
    sheet({ title: T("Context", "Bağlam"), text: T("What is sent to the model with every request in this chat.", "Bu sohbette her istekte modele gönderilen."),
      body: h("div", null,
        h("div", { class: "kstat big" }, h("b", null, k(st.est)), h("span", null, " / " + k(st.budget) + T(" tokens", " token")), h("em", { class: st.level || "" }, (st.pct || 0) + "%")),
        h("div", { class: "meter " + (st.level || "") }, h("i", { style: "width:" + Math.min(100, st.pct || 0) + "%" }), h("u", { style: "left:" + (st.compactAt || 90) + "%" })),
        h("dl", { class: "dl", style: "margin-top:14px" },
          h("dt", null, T("System prompt", "Sistem istemi")), h("dd", null, "≈ " + k(st.system)),
          h("dt", null, T("Tool schemas", "Araç tanımları")), h("dd", null, "≈ " + k(st.tools)),
          h("dt", null, T("Messages", "Mesajlar")), h("dd", null, "≈ " + k(st.messages)),
          h("dt", null, T("Model window", "Model penceresi")), h("dd", null, st.window ? k(st.window) + (winSrc ? " · " + winSrc : "") : T("unknown", "bilinmiyor")),
          h("dt", null, T("Auto-compaction", "Otomatik sıkıştırma")), h("dd", null, st.autoCompact === false ? T("off", "kapalı") : T("at " + (st.compactAt || 90) + "%", "%" + (st.compactAt || 90) + " dolunca") + " · " + T("last " + (st.keepTurns || 3) + " turns kept word for word", "son " + (st.keepTurns || 3) + " tur aynen korunur")),
          h("dt", null, T("Compactions", "Sıkıştırma")), h("dd", null, String(st.compactions || 0))),
        sumBox,
        log.length ? h("div", { class: "clog" }, h("h4", null, T("Recent compactions", "Son sıkıştırmalar")), log.map(function (l) { return h("div", null, h("b", null, k(l.before) + " → " + k(l.after)), " · " + shortWhen(l.at) + (l.reason ? " · " + l.reason : ""), (l.steps || []).length ? h("small", null, l.steps.join(" · ")) : null); })) : null),
      actions: [[T("Close", "Kapat")], [T("Compact now", "Şimdi sıkıştır"), "p", function () {
        if (S.running[cid]) { toast(T("Wait until yaver finishes", "yaver bitirene kadar bekleyin")); return false; }
        toast(T("Compacting…", "Sıkıştırılıyor…"));
        call("ctx.compact", { convoId: cid }).then(function (r) { if (r && (r.note || r.text || typeof r === "string")) toast(r.note || r.text || r); refreshCtx(cid); if (cid === S.convoId) loadConvo(cid); }).catch(function (e) { toast(e.message); });
      }]] });
  }

  function onLaunch(d) {
    var a = d.action;
    if (a === "assist") { newChat(true); }
    else if (a === "voice") { newChat(); listen(true); }
    else if (a === "new") newChat(true);
    else if (a === "plan") planDay();
    else if (a === "ask" && d.text) { newChat(); send(d.text); }
    else if (a === "run" && d.text) { newChat(); send(d.text); }
    else if (a === "share") { closeAllPages(); if (d.text) { $("#input").value = d.text; autosize(); } (d.files || []).forEach(function (f) { S.atts.push(f); }); renderAtts(); syncSend(); $("#input").focus(); }
    else if (a === "open_convo" && d.text) { closeAllPages(); loadConvo(d.text); }
    else if (a === "mail") openMailPage();
    else if (a === "mail_open" && d.text) openMail(d.text);
    else if (a === "mail_ask" && d.text) {
      toast(T("Opening the e-mail…", "E-posta açılıyor…"));
      call("mail.get", { id: d.text }).then(function (m) { askAbout(m); }).catch(function () { askAbout({ id: d.text, subject: "", from: null }); });
    }
    else if (a === "mail_summary" && d.text) {
      closeAllPages(); newChat();
      var ids = d.text.split(",");
      send(T("Summarize my new e-mails (mail ids: " + ids.join(", ") + "): for each, who sent it, what it is about and whether I need to do something. Most important first.",
        "Yeni e-postalarımı özetle (mail id'leri: " + ids.join(", ") + "): her biri için kimden geldi, ne hakkında ve benim bir şey yapmam gerekiyor mu. En önemlisi önce."),
        { display: T("Summarize my " + ids.length + " new e-mails", ids.length + " yeni e-postamı özetle") });
    }
    else if (a === "crash") sheet({ title: T("yaver closed unexpectedly last time", "yaver geçen sefer beklenmedik şekilde kapandı"), text: T("This report helps fix it. Copy it and send it along.", "Bu rapor sorunun çözülmesine yardım eder. Kopyalayıp iletin."), body: h("div", { class: "res", style: "display:block;white-space:pre-wrap;font-size:12px;max-height:40vh;overflow:auto" }, d.text), actions: [[T("Copy report", "Raporu kopyala"), "p", function () { call("copy", { text: d.text }); toast(T("Copied", "Kopyalandı")); }], [T("Close", "Kapat")]] });
  }

  /* ------------------------------------------------------------ sheets */
  var sheets = [];
  function sheet(o) {
    var bg = h("div", { class: "sheetbg" });
    var sh = h("div", { class: "sheet", role: "dialog", "aria-modal": "true" }, h("div", { class: "grab" }),
      (o.title || o.text) ? h("div", { class: "sh" }, o.title ? h("h3", null, o.title) : null, o.text ? h("p", null, o.text) : null) : null,
      o.body ? h("div", { class: "sb" }, o.body) : null,
      o.actions && o.actions.length ? h("div", { class: "sf" }, o.actions.map(function (a) {
        return h("button", { class: "btn " + (a[1] || ""), onclick: function () { var keep = a[2] && a[2]() === false; if (!keep) close(); } }, a[0]);
      })) : null);
    var rec = { close: close, onClose: o.onClose };
    function close() {
      if (rec.closed) return; rec.closed = true;
      sh.classList.remove("on"); bg.classList.remove("on");
      setTimeout(function () { sh.remove(); bg.remove(); }, 240);
      sheets.splice(sheets.indexOf(rec), 1);
      if (o.onClose) o.onClose();
    }
    bg.addEventListener("click", function () { if (!o.modal) close(); });
    document.body.appendChild(bg); document.body.appendChild(sh);
    requestAnimationFrame(function () { bg.classList.add("on"); sh.classList.add("on"); });
    sheets.push(rec);
    return rec;
  }

  function approveSheet(d) {
    var dl = h("dl", { class: "dl" });
    String(d.details || "").split("\n").forEach(function (line) {
      var i = line.indexOf(":"); if (i < 0) { dl.appendChild(h("dd", { style: "grid-column:1/-1" }, line)); return; }
      dl.appendChild(h("dt", null, line.slice(0, i))); dl.appendChild(h("dd", null, line.slice(i + 1).trim()));
    });
    var answered = false;
    function ans(ok) { if (answered) return; answered = true; call("approve.answer", { reqId: d.reqId, ok: ok }); }
    sheet({
      title: d.title, text: T("yaver wants to do this. Allow it?", "yaver bunu yapmak istiyor. İzin veriyor musunuz?"), modal: true,
      body: h("div", null, dl, d.danger ? h("div", { class: "danger-note" }, icon("triangle-alert", "s"), T("This cannot be undone.", "Bu işlem geri alınamaz.")) : null),
      actions: [[T("Don't", "Yapma"), "", function () { ans(false); }], [T("Allow", "İzin ver"), d.danger ? "danger" : "p", function () { ans(true); }]],
      onClose: function () { ans(false); }
    });
  }

  function editSheet(o) {
    var input = o.multi ? h("textarea", { rows: 7 }) : h("input", { type: o.secret ? "password" : (o.type || "text"), autocomplete: "off", autocapitalize: "off", spellcheck: "false", inputmode: o.inputmode || null });
    input.value = o.value || "";
    if (o.placeholder) input.placeholder = o.placeholder;
    var s = sheet({
      title: o.title, text: o.text,
      body: h("label", { class: "field" }, o.label ? h("span", null, o.label) : null, input, o.help ? h("small", null, o.help) : null),
      actions: [[T("Cancel", "İptal")], [o.ok || T("Save", "Kaydet"), "p", function () { return o.save(input.value); }]].concat(o.extra || [])
    });
    setTimeout(function () { input.focus(); }, 260);
    return s;
  }

  function optionsSheet(title, options, current, pickFn) {
    var s = sheet({ title: title, body: h("div", null, options.map(function (op) {
      return h("button", { class: "opt", onclick: function () { s.close(); pickFn(op[0]); } }, op[2] ? icon(op[2], "s") : null, h("div", null, op[1], op[3] ? h("small", null, op[3]) : null), op[0] === current ? icon("check", "s c") : null);
    })) });
  }

  /* ------------------------------------------------------------ drawer */
  function openDrawer() { refreshConvos(); $(".drawer").classList.add("on"); $(".scrim").classList.add("on"); }
  function closeDrawer() { var d = $(".drawer"); if (d) d.classList.remove("on"); var s = $(".scrim"); if (s) s.classList.remove("on"); }

  function refreshConvos(q) {
    var p = q && q.length > 1 ? call("convos.search", { q: q }) : call("convos.list");
    return p.then(function (list) { S.convos = list || []; renderConvos(!!(q && q.length > 1)); }).catch(function () {});
  }

  function renderConvos(isSearch) {
    var box = $(".dlist"); if (!box) return; clear(box);
    if (!S.convos.length) { box.appendChild(h("div", { class: "dgroup" }, isSearch ? T("No matches", "Eşleşme yok") : T("No conversations yet", "Henüz sohbet yok"))); return; }
    var now = new Date(), groups = {}, order = [];
    S.convos.forEach(function (c) {
      var d = new Date(c.updated), g;
      if (isSearch) g = T("Results", "Sonuçlar");
      else if (sameDay(d, now)) g = T("Today", "Bugün");
      else if (sameDay(d, new Date(now - 864e5))) g = T("Yesterday", "Dün");
      else if (now - d < 7 * 864e5) g = T("Previous 7 days", "Son 7 gün");
      else g = d.toLocaleDateString(loc(), { month: "long", year: "numeric" });
      if (!groups[g]) { groups[g] = []; order.push(g); }
      groups[g].push(c);
    });
    order.forEach(function (g) {
      box.appendChild(h("div", { class: "dgroup" }, g));
      groups[g].forEach(function (c) {
        var pressT = 0, long = false;
        var b = h("button", { class: "citem" + (c.id === S.convoId ? " cur" : "") },
          c.background ? icon("clock", "xs") : icon("message-square", "xs"),
          h("span", null, c.title || "—", c.snippet ? h("small", null, c.snippet) : null),
          S.running[c.id] ? h("i", { class: "run" }) : null);
        b.addEventListener("click", function () { if (long) return; closeDrawer(); loadConvo(c.id); });
        b.addEventListener("touchstart", function () { long = false; pressT = setTimeout(function () { long = true; convoMenu(c); }, 520); }, { passive: true });
        b.addEventListener("touchend", function () { clearTimeout(pressT); });
        b.addEventListener("touchmove", function () { clearTimeout(pressT); }, { passive: true });
        b.addEventListener("contextmenu", function (e) { e.preventDefault(); convoMenu(c); });
        box.appendChild(b);
      });
    });
  }

  function convoMenu(c) {
    var s = sheet({ title: c.title, body: h("div", null,
      h("button", { class: "opt", onclick: function () { s.close(); editSheet({ title: T("Rename chat", "Sohbeti yeniden adlandır"), value: c.title, save: function (v) { call("convos.rename", { id: c.id, title: v }).then(function () { refreshConvos(); if (c.id === S.convoId) { S.convo.title = v; updateTitle(); } }); } }); } }, icon("pencil", "s"), T("Rename", "Yeniden adlandır")),
      h("button", { class: "opt", style: "color:var(--bad)", onclick: function () { s.close(); call("convos.delete", { id: c.id }).then(function () { if (c.id === S.convoId) newChat(); refreshConvos(); toast(T("Chat deleted", "Sohbet silindi")); }); } }, icon("trash-2", "s"), T("Delete", "Sil"))) });
  }

  /* ------------------------------------------------------------ pages */
  function topPage() { return S.pages.length ? S.pages[S.pages.length - 1].name : ""; }
  function pushPage(name, title, build, opts) {
    opts = opts || {};
    var titleEl = h("div", { class: "title" }, title, opts.sub ? h("small", null, opts.sub) : null);
    var bar = h("div", { class: "bar" + (opts.line ? " line" : "") }, h("button", { class: "ib", "aria-label": T("Back", "Geri"), onclick: function () { popPage(); } }, icon("chevron-left")), titleEl);
    (opts.actions || []).forEach(function (a) { bar.appendChild(a); });
    var body = h("div", { class: "pbody" });
    var page = h("div", { class: "page" }, bar, body);
    if (opts.footer) page.appendChild(opts.footer);
    document.body.appendChild(page);
    var rec = { name: name, el: page, body: body, title: titleEl, bar: bar };
    S.pages.push(rec);
    build(body, rec);
    requestAnimationFrame(function () { page.classList.add("on"); });
    return rec;
  }
  function popPage() {
    var p = S.pages.pop(); if (!p) return;
    p.el.classList.remove("on"); setTimeout(function () { p.el.remove(); }, 260);
    if (!S.pages.length) { refreshState(); renderThread(); }
  }
  function closeAllPages() { while (S.pages.length) popPage(); }

  function goBack() {
    if (sheets.length) { sheets[sheets.length - 1].close(); return true; }
    if ($(".drawer.on")) { closeDrawer(); return true; }
    if (S.pages.length) { popPage(); return true; }
    if (S.convo && S.convo.messages && S.convo.messages.length && !S.running[S.convoId]) { newChat(); return true; }
    return false;
  }

  /* ------------------------------------------------------------ mail */
  var FOLDERS = [["inbox", "Inbox", "Gelen Kutusu", "inbox"], ["sentitems", "Sent", "Gönderilenler", "send"], ["drafts", "Drafts", "Taslaklar", "file-pen"], ["archive", "Archive", "Arşiv", "archive"], ["junkemail", "Junk", "Gereksiz", "ban"], ["deleteditems", "Deleted", "Silinmiş", "trash-2"]];
  function folderName(f) { var x = FOLDERS.filter(function (q) { return q[0] === f; })[0]; return x ? T(x[1], x[2]) : f; }

  function openMailPage() {
    closeDrawer();
    if (topPage() === "mail") return;
    var refresh = h("button", { class: "ib", "aria-label": T("Refresh", "Yenile"), onclick: function () { loadMail(true); } }, icon("refresh-cw", "s"));
    var compose = h("button", { class: "ib", "aria-label": T("New e-mail", "Yeni e-posta"), onclick: function () { composeSheet("new", null); } }, icon("square-pen", "s"));
    pushPage("mail", T("Mail", "Posta"), function (body) {
      body.appendChild(h("div", { class: "seg", role: "tablist" },
        h("button", { class: S.mail.tab === "mail" ? "on" : "", onclick: function () { S.mail.tab = "mail"; renderMailShell(); loadMail(); } }, icon("inbox", "xs"), T("Mail", "Posta")),
        h("button", { class: S.mail.tab === "cal" ? "on" : "", onclick: function () { S.mail.tab = "cal"; renderMailShell(); loadMail(); } }, icon("calendar", "xs"), T("Calendar", "Takvim"))));
      body.appendChild(h("div", { id: "mailbox" }));
      renderMailShell(); loadMail();
    }, { actions: [refresh, compose] });
  }

  function renderMailShell() {
    var p = S.pages.filter(function (x) { return x.name === "mail"; })[0]; if (!p) return;
    p.body.querySelectorAll(".seg button").forEach(function (b, i) { b.classList.toggle("on", (i === 0) === (S.mail.tab === "mail")); });
    var box = clear($("#mailbox", p.body));
    if (S.mail.tab === "mail") {
      var q = h("input", { type: "search", placeholder: T("Search mail", "Postalarda ara"), value: S.mail.query });
      q.addEventListener("keydown", function (e) { if (e.key === "Enter") { S.mail.query = q.value.trim(); loadMail(); q.blur(); } });
      box.appendChild(h("div", { class: "toolbar" },
        h("button", { class: "select", onclick: function () { optionsSheet(T("Folder", "Klasör"), FOLDERS.map(function (f) { return [f[0], T(f[1], f[2]), f[3]]; }), S.mail.folder, function (v) { S.mail.folder = v; S.mail.query = ""; renderMailShell(); loadMail(); }); } }, folderName(S.mail.folder), icon("chevron-down", "xs")),
        h("label", { class: "search" }, icon("search", "xs"), q)));
    }
    box.appendChild(h("div", { id: "mailmeta", class: "meta" }));
    box.appendChild(h("div", { id: "maillist" }));
  }

  function mailBlocked(err) {
    var st = (S.state && S.state.mail) || {};
    var list = clear($("#maillist")); var meta = $("#mailmeta"); if (meta) meta.textContent = "";
    var signIn = /^SIGNIN:/.test(err || "") || st.needsSignIn;
    var notSet = !st.configured;
    list.appendChild(h("div", { class: "empty" },
      h("div", { class: "ei" }, icon(notSet ? "mail" : signIn ? "lock" : "triangle-alert")),
      h("h3", null, notSet ? T("Connect your company mail", "Kurum postanızı bağlayın") : signIn ? T("Sign in to your mail", "Postada oturum açın") : T("Mail is not reachable", "Postaya ulaşılamıyor")),
      h("p", null, notSet ? T("Add your Outlook web address and sign in once. yaver keeps the session open and reads your mail and calendar natively.", "Outlook web adresinizi ekleyip bir kez oturum açın. yaver oturumu açık tutar, postanızı ve takviminizi doğrudan okur.")
        : signIn ? T("Your mail server asks you to sign in on its own page. Your password is filled in for you — enter the authenticator code if it asks.", "Posta sunucunuz kendi sayfasında oturum açmanızı istiyor. Şifreniz otomatik doldurulur — istenirse doğrulama kodunu girin.")
        : String(err || st.error || "").replace(/^SIGNIN:/, "")),
      h("div", { class: "btnstack" },
        notSet ? h("button", { class: "btn p", onclick: function () { openSettings("mail"); } }, icon("settings", "s"), T("Set up mail", "Postayı ayarla"))
          : h("button", { class: "btn p", onclick: function () { signInMail(); } }, icon("log-in", "s"), T("Sign in", "Oturum aç")),
        notSet ? null : h("button", { class: "btn", onclick: function () { loadMail(true); } }, icon("refresh-cw", "s"), T("Try again", "Yeniden dene")),
        notSet ? null : h("button", { class: "btn ghost", onclick: shareLog }, icon("share-2", "s"), T("Share diagnostic log", "Tanılama günlüğünü paylaş")))));
  }

  function signInMail() {
    call("mail.login").then(function (st) { if (S.state) S.state.mail = st; if (topPage() === "mail") loadMail(); if (topPage() === "settings") renderSettings(); });
  }

  function loadMail(force) {
    var list = $("#maillist"); if (!list) return;
    if (!list.firstChild || force) { clear(list).appendChild(h("div", { class: "status", style: "padding:24px 16px" }, h("div", { class: "spin" }), T("Loading…", "Yükleniyor…"))); }
    if (S.mail.tab === "cal") {
      call("mail.events", { days: 14 }).then(renderAgenda).catch(function (e) { mailBlocked(e.message); });
      return;
    }
    call("mail.list", { folder: S.mail.folder, query: S.mail.query, limit: 50 }).then(function (r) {
      S.mail.items = r.items || [];
      var unread = S.mail.items.filter(function (m) { return !m.read; }).length;
      var meta = $("#mailmeta"); if (meta) { clear(meta); meta.appendChild(h("span", { class: "dot ok" })); meta.appendChild(document.createTextNode(S.mail.query ? T(S.mail.items.length + " results", S.mail.items.length + " sonuç") : (unread ? T(unread + " unread · ", unread + " okunmamış · ") : "") + T(r.total + " in " + folderName(S.mail.folder), folderName(S.mail.folder) + " · " + r.total))); }
      var list2 = clear($("#maillist"));
      if (!S.mail.items.length) { list2.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("inbox")), h("h3", null, S.mail.query ? T("Nothing found", "Bir şey bulunamadı") : T("No mail here", "Burada posta yok")))); return; }
      S.mail.items.forEach(function (m) {
        var from = m.from ? (m.from.name || m.from.email) : "?";
        list2.appendChild(h("button", { class: "mrow" + (m.read ? "" : " unread"), onclick: function () { openMail(m.id, m); } },
          avatar(from, m.from && m.from.email),
          h("div", { class: "mc" },
            h("div", { class: "l1" }, h("span", { class: "from" }, from), h("span", { class: "time" }, m.importance === "High" ? icon("octagon-alert", "xs flag") : null, m.attachments ? icon("paperclip", "xs") : null, shortWhen(m.received))),
            h("div", { class: "subj" }, m.subject),
            m.preview ? h("div", { class: "prev" }, m.preview.replace(/\s+/g, " ")) : null)));
      });
    }).catch(function (e) { mailBlocked(e.message); });
  }

  function renderAgenda(evs) {
    var list = clear($("#maillist")); var meta = $("#mailmeta"); if (meta) { clear(meta); meta.appendChild(h("span", { class: "dot ok" })); meta.appendChild(document.createTextNode(T("Next 14 days", "Önümüzdeki 14 gün"))); }
    evs = (evs || []).filter(function (e) { return !e.cancelled; });
    if (!evs.length) { list.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("calendar")), h("h3", null, T("Nothing scheduled", "Planlanmış bir şey yok")), h("p", null, T("Your calendar is clear for the next two weeks.", "Önümüzdeki iki hafta takviminiz boş.")))); return; }
    var lastDay = "";
    var now = Date.now();
    evs.forEach(function (e) {
      var dk = new Date(e.start).toDateString();
      if (dk !== lastDay) { lastDay = dk; list.appendChild(h("div", { class: "day" }, dayLabel(e.start), h("small", null, new Date(e.start).toLocaleDateString(loc(), { day: "numeric", month: "long" })))); }
      var resp = e.response || "";
      var cls = "ev" + (e.end < now ? " past" : "") + (resp === "Tentative" ? " tent" : "") + (e.meeting && (resp === "NoResponseReceived" || resp === "Unknown") ? " none" : "");
      list.appendChild(h("button", { class: cls, onclick: function () { eventSheet(e); } },
        h("div", { class: "tm" }, e.allDay ? h("b", null, T("All day", "Tüm gün")) : [h("b", null, timeOf(e.start)), timeOf(e.end)]),
        h("div", { class: "eb" }, h("b", null, e.subject), (e.location || e.organizer) ? h("span", null, [e.location, e.organizer].filter(Boolean).join(" — ")) : null,
          e.meeting && (resp === "NoResponseReceived" || resp === "Unknown") ? h("span", { class: "tag warn" }, T("Not answered", "Yanıtlanmadı")) : resp === "Tentative" ? h("span", { class: "tag warn" }, T("Tentative", "Belki")) : null)));
    });
  }

  function eventSheet(e) {
    var acts = [];
    if (e.meeting && e.response !== "Organizer") acts = [[T("Decline", "Reddet"), "", function () { respond(e.id, "decline"); }], [T("Maybe", "Belki"), "", function () { respond(e.id, "tentative"); }], [T("Accept", "Kabul"), "p", function () { respond(e.id, "accept"); }]];
    sheet({ title: e.subject, body: h("dl", { class: "dl" },
      h("dt", null, T("When", "Zaman")), h("dd", null, e.allDay ? new Date(e.start).toLocaleDateString(loc(), { weekday: "long", day: "numeric", month: "long" }) : longWhen(e.start) + " – " + timeOf(e.end)),
      e.location ? [h("dt", null, T("Where", "Yer")), h("dd", null, e.location)] : null,
      e.organizer ? [h("dt", null, T("Organizer", "Düzenleyen")), h("dd", null, e.organizer)] : null),
      actions: acts.length ? acts : [[T("Close", "Kapat")]] });
  }

  function respond(id, r) {
    call("mail.respond", { id: id, response: r }).then(function () { toast(T("Response sent", "Yanıt gönderildi")); loadMail(); }).catch(function (e) { toast(e.message); });
  }

  function openMail(id, head) {
    closeDrawer();
    var cur = null;
    var footer = h("div", { class: "actbar" },
      h("button", { onclick: function () { if (cur) composeSheet("reply", cur); } }, icon("reply", "s"), T("Reply", "Yanıtla")),
      h("button", { onclick: function () { if (cur) composeSheet("replyall", cur); } }, icon("reply-all", "s"), T("Reply all", "Tümünü")),
      h("button", { onclick: function () { if (cur) composeSheet("forward", cur); } }, icon("forward", "s"), T("Forward", "İlet")),
      h("button", { onclick: function () { if (cur) deleteMail(cur); } }, icon("trash-2", "s"), T("Delete", "Sil")),
      h("button", { class: "prim", onclick: function () { if (cur) askAbout(cur); } }, icon("sparkles", "s"), T("Ask yaver", "yaver'e sor")));
    pushPage("mailread", head ? head.subject : T("E-mail", "E-posta"), function (body) {
      body.appendChild(h("div", { class: "status", style: "padding:24px 16px" }, h("div", { class: "spin" }), T("Opening…", "Açılıyor…")));
      call("mail.get", { id: id }).then(function (m) {
        cur = m;
        clear(body);
        var from = m.from ? (m.from.name || m.from.email) : "?";
        body.appendChild(h("div", { class: "rhead" },
          h("h2", null, m.subject),
          h("div", { class: "sender" }, avatar(from, m.from && m.from.email), h("div", { class: "sn" }, h("b", null, from), h("span", null, (m.from && m.from.email ? m.from.email + " — " : "") + longWhen(m.received)))),
          h("div", { class: "rcpt" }, h("b", null, T("To ", "Kime ")), (m.to || []).map(function (x) { return x.name || x.email; }).join(", "),
            m.cc && m.cc.length ? [h("br"), h("b", null, "Cc "), m.cc.map(function (x) { return x.name || x.email; }).join(", ")] : null)));
        if (m.invite && m.invite.start) {
          var st = new Date(m.invite.start).getTime ? Date.parse(m.invite.start) : 0;
          body.appendChild(h("div", { class: "invite" },
            h("div", { class: "when" }, icon("calendar", "s"), isNaN(st) ? m.invite.start : longWhen(st)),
            h("div", { class: "where" }, [m.invite.location, m.invite.organizer].filter(Boolean).join(" — ")),
            /Cancellation/.test(m.kind) ? null : h("div", { class: "btnrow" },
              h("button", { class: "btn sm", onclick: function () { respond(m.id, "decline"); } }, T("Decline", "Reddet")),
              h("button", { class: "btn sm", onclick: function () { respond(m.id, "tentative"); } }, T("Maybe", "Belki")),
              h("button", { class: "btn sm p", onclick: function () { respond(m.id, "accept"); } }, T("Accept", "Kabul")))));
        }
        if (m.files && m.files.length) body.appendChild(h("div", { class: "files" }, m.files.map(function (f) {
          return h("button", { class: "fileb", onclick: function () { toast(T("Opening ", "Açılıyor: ") + f.name); call("mail.attachment", { id: m.id, attId: f.id, name: f.name }).catch(function (e) { toast(e.message); }); } }, icon("paperclip", "xs"), h("span", null, f.name), h("small", null, bytes(f.size)));
        })));
        var bodyEl = h("div", { class: "mbody" });
        linkify(bodyEl, m.body || "");
        body.appendChild(bodyEl);
      }).catch(function (e) { clear(body); body.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("triangle-alert")), h("p", null, e.message.replace(/^SIGNIN:/, "")), h("button", { class: "btn p", onclick: signInMail }, T("Sign in", "Oturum aç")))); });
    }, { footer: footer, line: true });
  }

  function linkify(el, text) {
    var re = /(https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"])|([\w.+-]+@[\w-]+\.[\w.-]+)/g, last = 0, m;
    while ((m = re.exec(text))) {
      if (m.index > last) el.appendChild(document.createTextNode(text.slice(last, m.index)));
      var url = m[1] ? m[1] : "mailto:" + m[2];
      (function (u) { el.appendChild(h("a", { href: "#", onclick: function (e) { e.preventDefault(); call("open.link", { url: u }); } }, m[0])); })(url);
      last = re.lastIndex;
    }
    el.appendChild(document.createTextNode(text.slice(last)));
  }

  function deleteMail(m) {
    sheet({ title: T("Delete this e-mail?", "Bu e-posta silinsin mi?"), text: T("It moves to Deleted Items.", "Silinmiş Öğeler'e taşınır."),
      actions: [[T("Cancel", "İptal")], [T("Delete", "Sil"), "danger", function () { call("mail.remove", { id: m.id }).then(function () { toast(T("Moved to Deleted Items", "Silinmiş Öğeler'e taşındı")); popPage(); loadMail(); }).catch(function (e) { toast(e.message); }); }]] });
  }

  function askAbout(m) {
    closeAllPages(); newChat();
    var who = m.from && (m.from.name || m.from.email) || "";
    send(T("Look at this e-mail (mail id=" + m.id + ", subject \"" + m.subject + "\" from " + (m.from && (m.from.name || m.from.email)) + "). Summarize it, tell me what is expected of me and suggest a reply.",
      "Şu e-postaya bak (mail id=" + m.id + ", konu \"" + m.subject + "\", gönderen " + (m.from && (m.from.name || m.from.email)) + "). Özetle, benden ne bekleniyor söyle ve bir yanıt öner."), { display: T("About “" + m.subject + "” from " + who + ": summarize it, what is expected of me, and suggest a reply.", "“" + m.subject + "” (" + who + ") e-postasını özetle, benden ne bekleniyor söyle ve bir yanıt öner.") });
  }

  function composeSheet(kind, m) {
    var to = h("input", { type: "email", placeholder: "ad@sirket.com.tr", autocapitalize: "off" });
    var cc = h("input", { type: "email", placeholder: T("optional", "isteğe bağlı"), autocapitalize: "off" });
    var subj = h("input", { type: "text" });
    var body = h("textarea", { rows: 8, placeholder: T("Write your message", "Mesajınızı yazın") });
    var title = { new: T("New e-mail", "Yeni e-posta"), reply: T("Reply", "Yanıtla"), replyall: T("Reply all", "Tümünü yanıtla"), forward: T("Forward", "İlet") }[kind];
    var fields = h("div", null,
      kind === "new" || kind === "forward" ? h("label", { class: "field" }, h("span", null, T("To", "Kime")), to) : null,
      kind === "new" ? h("label", { class: "field" }, h("span", null, "Cc"), cc) : null,
      kind === "new" ? h("label", { class: "field" }, h("span", null, T("Subject", "Konu")), subj) : null,
      h("label", { class: "field" }, h("span", null, m ? (kind === "forward" ? "FW: " : "RE: ") + m.subject : T("Message", "Mesaj")), body));
    function go(sendIt) {
      var args = { kind: kind, ref: m ? m.id : "", to: to.value.split(/[;,]/).map(function (s) { return s.trim(); }).filter(Boolean), cc: cc.value.split(/[;,]/).map(function (s) { return s.trim(); }).filter(Boolean), subject: subj.value, body: body.value, send: sendIt };
      if ((kind === "new" || kind === "forward") && !args.to.length) { toast(T("Add a recipient", "Alıcı ekleyin")); return false; }
      call("mail.compose", args).then(function () { toast(sendIt ? T("Sent", "Gönderildi") : T("Saved in Drafts", "Taslaklara kaydedildi")); }).catch(function (e) { toast(e.message); });
    }
    sheet({ title: title, body: fields, actions: [[T("Save draft", "Taslak"), "", function () { return go(false); }], [T("Send", "Gönder"), "p", function () { return go(true); }]] });
    setTimeout(function () { (kind === "new" || kind === "forward" ? to : body).focus(); }, 260);
  }

  /* ------------------------------------------------------------ settings */
  function refreshState() { return call("state").then(function (s) { S.state = s; S.lang = s.lang; return s; }); }
  function setCfg(key, value) { return call("cfg.set", { key: key, value: value }).then(function (s) { S.state = s; S.lang = s.lang; if (topPage() === "settings") renderSettings(); return s; }).catch(function (e) { toast(e.message); }); }

  function openSettings(focus) {
    closeDrawer();
    if (topPage() === "settings") { if (focus === "learn") { S.settingsFocus = "learn"; renderExtras(); } return; }
    pushPage("settings", T("Settings", "Ayarlar"), function () { refreshState().then(function () { renderSettings(focus); }); });
  }

  function row(o) {
    var b = h(o.toggle != null ? "div" : "button", { class: "row", onclick: o.toggle != null ? function () { var on = !sw.classList.contains("on"); sw.classList.toggle("on", on); o.onToggle(on); } : o.onclick, role: o.toggle != null ? "switch" : null, "aria-checked": o.toggle != null ? String(!!o.toggle) : null },
      o.icon ? h("div", { class: "tile", style: o.tint ? "background:" + o.tint + ";color:#fff" : null }, icon(o.icon)) : null,
      h("div", { class: "rt" }, h("b", null, o.label), o.sub ? h("span", { class: o.wrap ? "wrap" : "" }, o.sub) : null),
      o.check != null ? h("div", { class: "check " + (o.check ? "ok" : "no") }, icon(o.check ? "check" : "circle-help", "xs")) : null,
      o.value ? h("span", { class: "val" }, o.value) : null,
      o.toggle == null && o.onclick ? icon("chevron-right", "s chev") : null);
    var sw = null;
    if (o.toggle != null) { sw = h("div", { class: "sw" + (o.toggle ? " on" : "") }); b.appendChild(sw); }
    return b;
  }
  function group(title, rows, hint) { return h("div", { class: "sgroup" }, title ? h("h4", null, title) : null, h("div", { class: "list" }, rows), hint ? h("div", { class: "hint" }, hint) : null); }
  /* Settings: every titled group folds open/closed (remembered), and the search box filters rows across all groups */
  var SET_OPEN = (function () { try { return JSON.parse(localStorage.getItem("yv.setOpen") || "null") || {}; } catch (e) { return {}; } })();
  function settingsEnhance(root) {
    root.querySelectorAll(".sgroup").forEach(function (g) {
      var h4 = g.querySelector(":scope > h4"); if (!h4 || g.dataset.fold) return;
      var key = h4.textContent.trim(); g.dataset.fold = key;
      var first = g === root.querySelector(".sgroup");
      var open = SET_OPEN[key] != null ? SET_OPEN[key] : first;
      g.classList.add("fold"); g.classList.toggle("open", !!open);
      var n = g.querySelectorAll(":scope > .list > .row").length;
      h4.textContent = ""; h4.appendChild(h("span", null, key)); if (n) h4.appendChild(h("small", null, String(n))); h4.appendChild(icon("chevron-down", "xs chev"));
      h4.setAttribute("role", "button"); h4.setAttribute("tabindex", "0"); h4.setAttribute("aria-expanded", String(!!open));
      var toggle = function () { var o = !g.classList.contains("open"); g.classList.toggle("open", o); h4.setAttribute("aria-expanded", String(o)); SET_OPEN[key] = o; try { localStorage.setItem("yv.setOpen", JSON.stringify(SET_OPEN)); } catch (e) {} };
      h4.addEventListener("click", toggle); h4.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle(); } });
    });
    settingsFilter(root);
  }
  function settingsFilter(root) {
    var q = (S.setQ || "").trim().toLocaleLowerCase(S.lang === "tr" ? "tr" : undefined);
    root.classList.toggle("searching", !!q);
    var any = false;
    root.querySelectorAll(".sgroup").forEach(function (g) {
      if (!q) { g.style.display = ""; g.querySelectorAll(".row").forEach(function (r) { r.style.display = ""; }); return; }
      var title = (g.dataset.fold || "").toLocaleLowerCase(), hit = 0;
      g.querySelectorAll(".row").forEach(function (r) { var m = title.indexOf(q) >= 0 || r.textContent.toLocaleLowerCase().indexOf(q) >= 0; r.style.display = m ? "" : "none"; if (m) hit++; });
      g.style.display = hit ? "" : "none"; if (hit) any = true;
    });
    var none = root.querySelector(".snone");
    if (q && !any) { if (!none) root.appendChild(h("div", { class: "snone empty" }, h("p", null, T("No setting matches.", "Eşleşen ayar yok.")))); }
    else if (none) none.remove();
  }

  function masked(has) { return has ? "••••••••" : T("Not set", "Ayarlanmadı"); }

  var PRESETS = [["https://openrouter.ai/api/v1", "OpenRouter", "One key, hundreds of models, many free", "Tek anahtar, yüzlerce model, birçoğu ücretsiz"], ["https://api.openai.com/v1", "OpenAI", "GPT models", "GPT modelleri"], ["https://generativelanguage.googleapis.com/v1beta/openai", "Google Gemini", "Gemini models", "Gemini modelleri"], ["https://api.groq.com/openai/v1", "Groq", "Very fast open models", "Çok hızlı açık modeller"], ["https://api.together.xyz/v1", "Together", "Open models", "Açık modeller"], ["http://localhost:11434/v1", "Ollama", "A model on your own computer", "Kendi bilgisayarınızdaki model"]];
  function serviceName(base) { var p = PRESETS.filter(function (x) { return x[0] === base; })[0]; return p ? p[1] : (base || "").replace(/^https?:\/\//, "").split("/")[0]; }

  function renderSettings(focus) {
    var p = S.pages.filter(function (x) { return x.name === "settings"; })[0]; if (!p) return;
    var st = S.state, L = st.llm, M = st.mail, Ph = st.phone, W = st.web;
    var body = clear(p.body);
    var sq = h("input", { type: "search", placeholder: T("Search settings", "Ayarlarda ara"), value: S.setQ || "", "aria-label": T("Search settings", "Ayarlarda ara") });
    sq.addEventListener("input", function () { S.setQ = sq.value; settingsFilter(p.body); });
    body.appendChild(h("div", { class: "ssearch" }, h("label", { class: "search" }, icon("search", "xs"), sq)));
    var wrap = h("div", { style: "padding-bottom:20px" });
    body.appendChild(wrap);
    if (!p.obs) { var obsT = 0; p.obs = new MutationObserver(function () { clearTimeout(obsT); obsT = setTimeout(function () { settingsEnhance(p.body); }, 30); }); p.obs.observe(p.body, { childList: true, subtree: true }); }

    wrap.appendChild(group(T("Status", "Durum"), [
      row({ icon: "cpu", label: T("Language model", "Dil modeli"), sub: L.ready ? L.model : T("Choose a service, key and model", "Hizmet, anahtar ve model seçin"), check: L.ready, onclick: function () { if (!L.ready) openSetup(); else pickModel("model"); } }),
      row({ icon: "mail", label: T("Company mail", "Kurum postası"), sub: M.connected ? M.status : M.needsSignIn ? T("Sign in needed", "Oturum açmanız gerekiyor") : M.configured ? T("Set up — not connected yet", "Ayarlı — henüz bağlı değil") : T("Not set up", "Ayarlanmadı"), check: M.connected, onclick: function () { if (M.configured) signInMail(); else document.getElementById("s-mail").scrollIntoView({ behavior: "smooth" }); } }),
      row({ icon: "sparkles", label: T("Phone's assistant", "Telefonun asistanı"), sub: Ph.isAssistant ? T("yaver opens from the home button", "yaver ana ekran tuşundan açılır") : T("Make yaver the default assistant", "yaver'i varsayılan asistan yapın"), check: Ph.isAssistant, onclick: function () { call("system.open", { what: "assistant" }); toast(T("Choose “Digital assistant app” → yaver", "“Dijital asistan uygulaması” → yaver seçin")); } }),
      row({ icon: "zap", label: T("Runs in the background", "Arka planda çalışıyor"), sub: Ph.unrestricted ? T("No battery limits", "Pil kısıtlaması yok") : T("Allow it to run without battery limits", "Pil kısıtlaması olmadan çalışmasına izin verin"), check: Ph.unrestricted && Ph.serviceRunning, onclick: function () { call("system.open", { what: "battery" }); } })
    ]));

    wrap.appendChild(group(T("Model", "Model"), [
      row({ icon: "globe", label: T("Service", "Hizmet"), value: serviceName(L.base), onclick: function () {
        optionsSheet(T("Model service", "Model hizmeti"), PRESETS.map(function (x) { return [x[0], x[1], null, T(x[2], x[3])]; }).concat([["custom", T("Other address…", "Başka adres…"), null, T("Any OpenAI-compatible service", "OpenAI uyumlu herhangi bir hizmet")]]), L.base, function (v) {
          if (v === "custom") editSheet({ title: T("Service address", "Hizmet adresi"), value: L.base, placeholder: "https://…/v1", save: function (x) { setCfg("llmBase", x.trim()); } });
          else setCfg("llmBase", v);
        });
      } }),
      row({ icon: "key-round", label: T("API key", "API anahtarı"), value: masked(L.hasKey), onclick: function () { editSheet({ title: T("API key", "API anahtarı"), secret: true, placeholder: "sk-…", help: T("Stored encrypted on this phone.", "Bu telefonda şifreli saklanır."), save: function (v) { if (v.trim()) setCfg("llmKey", v.trim()); } }); } }),
      row({ icon: "cpu", label: T("Model", "Model"), value: L.model ? L.model.split("/").pop() : T("Choose", "Seç"), onclick: function () { pickModel("model"); } }),
      row({ icon: "zap", label: T("Fast model", "Hızlı model"), sub: T("For summaries; empty = the main model", "Özetler için; boş = ana model"), value: L.fastModel ? L.fastModel.split("/").pop() : "", onclick: function () { pickModel("fastModel", true); } }),
      row({ icon: "image", label: T("Vision model", "Görsel model"), sub: T("Pictures and scanned PDFs; empty = the main model", "Resimler ve taranmış PDF; boş = ana model"), value: L.visionModel ? L.visionModel.split("/").pop() : "", onclick: function () { pickModel("visionModel", true); } }),
      row({ icon: "file-pen", label: T("Answer budget", "Cevap bütçesi"), sub: T("Most tokens the model may write in one reply (thinking included for reasoning models)", "Modelin bir cevapta yazabileceği en fazla token (düşünen modellerde düşünme dahil)"), wrap: true, value: L.maxOut ? (L.maxOut >= 1000 ? Math.round(L.maxOut / 1000) + "k" : String(L.maxOut)) : T("Automatic", "Otomatik"), onclick: function () {
        optionsSheet(T("Answer budget (tokens)", "Cevap bütçesi (token)"), [["0", T("Automatic — the service decides", "Otomatik — hizmet belirler")], ["2000", "2k"], ["4000", "4k"], ["8000", "8k"], ["16000", "16k"], ["32000", "32k"], ["64000", "64k"]], String(L.maxOut || 0), function (v) { setCfg("maxOut", v); });
      } }),
      row({ icon: "message-square", label: T("Show answers as they are written", "Cevapları yazılırken göster"), toggle: L.stream, onToggle: function (on) { setCfg("stream", on); } }),
      row({ icon: "check", label: T("Test the model", "Modeli dene"), onclick: function () { toast(T("Asking the model…", "Modele soruluyor…")); call("llm.test").then(function (r) { toast("✓ " + r); }).catch(function (e) { toast(e.message); }); } })
    ]));

    wrap.appendChild(group(T("Assistant", "Asistan"), [
      row({ icon: "languages", label: T("Answer language", "Cevap dili"), value: { auto: T("Like my message", "Mesajım gibi"), tr: "Türkçe", en: "English" }[st.langSetting] || st.langSetting, onclick: function () { optionsSheet(T("Answer language", "Cevap dili"), [["auto", T("Same as my message", "Mesajımla aynı")], ["tr", "Türkçe"], ["en", "English"]], st.langSetting, function (v) { setCfg("lang", v).then(function () { renderShell(); }); }); } }),
      row({ icon: "user", label: T("Personality", "Kişilik"), sub: st.persona ? st.persona : T("Default", "Varsayılan"), onclick: function () { editSheet({ title: T("Personality", "Kişilik"), text: T("Who yaver is and how it talks. Leave empty for the default.", "yaver kimdir ve nasıl konuşur. Varsayılan için boş bırakın."), multi: true, value: st.persona, placeholder: st.personaDefault, save: function (v) { setCfg("persona", v); } }); } }),
      row({ icon: "shield-check", label: T("Ask before actions", "İşlemlerden önce sor"), sub: T("Deleting, moving, overwriting. Sending mail is always confirmed.", "Silme, taşıma, üzerine yazma. E-posta gönderimi her zaman onaylatılır."), wrap: true, toggle: st.askRisky, onToggle: function (on) { setCfg("askRisky", on); } }),
      row({ icon: "brain", label: T("Memory", "Hafıza"), sub: T("What yaver knows about you", "yaver'in sizin hakkınızda bildikleri"), onclick: function () { openKnowledge(); } }),
      row({ icon: "square-kanban", label: T("Tasks", "Görevler"), sub: T("Your work task board", "İş görev panonuz"), value: S.overdue ? T(S.overdue + " overdue", S.overdue + " gecikmiş") : "", onclick: function () { openTasks(); } }),
      row({ icon: "clock", label: T("Reminders and scheduled tasks", "Hatırlatmalar ve zamanlanmış görevler"), onclick: openSchedule })
    ]));
    wrap.appendChild(h("div", { id: "s-extras" }));

    var mailGroup = group(T("Company mail", "Kurum postası"), [
      row({ icon: "globe", label: T("Outlook web address", "Outlook web adresi"), sub: M.owa || T("e.g. https://mail.company.com/owa", "ör. https://eposta.sirket.com.tr/owa"), onclick: function () { editSheet({ title: T("Outlook web address", "Outlook web adresi"), value: M.owa, type: "url", placeholder: "https://eposta.sirket.com.tr/owa", help: T("The address you open Outlook on the web with.", "Outlook'u web'de açtığınız adres."), save: function (v) { setCfg("mailOwa", v.trim()); } }); } }),
      row({ icon: "user", label: T("User name", "Kullanıcı adı"), sub: M.user || T("DOMAIN\\user or e-mail address", "ETKİALANI\\kullanıcı veya e-posta"), onclick: function () { editSheet({ title: T("User name", "Kullanıcı adı"), value: M.user, placeholder: "ETKİALANI\\kullanici", save: function (v) { setCfg("mailUser", v.trim()); } }); } }),
      row({ icon: "lock", label: T("Password", "Şifre"), value: masked(M.hasPass), onclick: function () { editSheet({ title: T("Mail password", "Posta şifresi"), secret: true, help: T("Stored encrypted on this phone and filled into the sign-in page for you.", "Bu telefonda şifreli saklanır ve oturum açma sayfasına sizin yerinize doldurulur."), save: function (v) { if (v) setCfg("mailPass", v); } }); } }),
      row({ icon: "shield-check", label: T("Authenticator", "Doğrulayıcı"), sub: M.hasTotp ? T("Codes are filled in automatically", "Kodlar otomatik dolduruluyor") : T("Optional: let yaver fill in the 6-digit code", "İsteğe bağlı: 6 haneli kodu yaver doldursun"), onclick: totpSheet }),
      row({ icon: "zap", label: T("Sign in automatically", "Otomatik oturum aç"), sub: T("Fill in and submit the sign-in page", "Oturum açma sayfasını doldur ve gönder"), toggle: M.autoSubmit, onToggle: function (on) { setCfg("mailAutoSubmit", on); } }),
      row({ icon: "bell", label: T("New mail notifications", "Yeni posta bildirimi"), sub: T("Checks every " + M.poll + " min — this also keeps the session open", "Her " + M.poll + " dakikada kontrol — bu oturumu da açık tutar"), toggle: M.notify, onToggle: function (on) { setCfg("mailNotify", on); } }),
      row({ icon: "clock", label: T("Check every", "Kontrol sıklığı"), value: M.poll + T(" min", " dk"), onclick: function () { optionsSheet(T("Check for new mail every", "Yeni postayı kontrol et"), [["1", "1"], ["3", "3"], ["5", "5"], ["10", "10"], ["15", "15"], ["30", "30"]].map(function (x) { return [x[0], x[1] + T(" minutes", " dakika")]; }), String(M.poll), function (v) { setCfg("mailPoll", v); }); } }),
      row({ icon: "server", label: T("EWS address", "EWS adresi"), sub: M.ews || T("Optional — only if IT gave you one", "İsteğe bağlı — BT verdiyse"), onclick: function () { editSheet({ title: T("EWS address", "EWS adresi"), value: M.ews, type: "url", placeholder: "https://mail.sirket.com.tr/EWS/Exchange.asmx", save: function (v) { setCfg("mailEws", v.trim()); } }); } }),
      row({ icon: "log-in", label: M.connected ? T("Sign in again", "Yeniden oturum aç") : T("Sign in now", "Şimdi oturum aç"), sub: M.connected ? M.status : (M.error ? M.error.replace(/^SIGNIN:/, "") : ""), onclick: signInMail })
    ], T("Works with company sign-in pages and authenticators: you sign in on your mail server's own page, yaver fills in what it knows and keeps the session open.", "Kurumsal oturum açma sayfaları ve doğrulayıcılarla çalışır: posta sunucunuzun kendi sayfasında oturum açarsınız, yaver bildiklerini doldurur ve oturumu açık tutar."));
    mailGroup.id = "s-mail";
    wrap.appendChild(mailGroup);
    wrap.appendChild(h("div", { id: "s-mailx" }));
    wrap.appendChild(h("div", { id: "s-folders" }));

    wrap.appendChild(group(T("Phone", "Telefon"), [
      row({ icon: "sparkles", label: T("Default assistant", "Varsayılan asistan"), sub: T("Long-press home / swipe from a corner opens yaver", "Ana ekran tuşuna uzun basmak / köşeden kaydırmak yaver'i açar"), check: Ph.isAssistant, onclick: function () { call("system.open", { what: "assistant" }); } }),
      row({ icon: "mic", label: T("Listen when opened as assistant", "Asistan olarak açılınca dinle"), toggle: Ph.voiceOnAssist, onToggle: function (on) { setCfg("voiceOnAssist", on); } }),
      row({ icon: "volume-2", label: T("Read answers to voice questions aloud", "Sesli sorulara cevabı sesli oku"), toggle: Ph.speakVoice, onToggle: function (on) { setCfg("speakVoice", on); } }),
      row({ icon: "zap", label: T("Always on", "Sürekli açık"), sub: T("Reminders, tasks and mail checks in the background", "Arka planda hatırlatma, görev ve posta kontrolü"), toggle: Ph.alwaysOn, onToggle: function (on) { setCfg("alwaysOn", on); } }),
      row({ icon: "bell", label: T("Tell me when an answer is ready", "Cevap hazır olunca haber ver"), sub: T("When yaver finishes while you're elsewhere", "Siz başka yerdeyken yaver bitirince"), toggle: Ph.notifyReplies, onToggle: function (on) { setCfg("notifyReplies", on); } }),
      row({ icon: "alarm-clock", label: T("Run reminder requests automatically", "Hatırlatma isteklerini otomatik çalıştır"), toggle: Ph.runReminders, onToggle: function (on) { setCfg("runReminders", on); } }),
      row({ icon: "calendar-check", label: T("Phone calendar", "Telefon takvimi"), check: Ph.calendar, onclick: function () { call("system.open", { what: "calendar" }); } }),
      row({ icon: "bell", label: T("Notifications", "Bildirimler"), check: Ph.notifications, onclick: function () { call("system.open", { what: "notifications" }); } }),
      row({ icon: "zap", label: T("Battery", "Pil"), check: Ph.unrestricted, sub: T("Let yaver run without limits", "yaver kısıtlamasız çalışsın"), onclick: function () { call("system.open", { what: "battery" }); } })
    ]));

    wrap.appendChild(group(T("Web search", "Web arama"), [
      row({ icon: "search", label: "Brave Search", value: masked(W.hasBrave), onclick: function () { editSheet({ title: "Brave Search API", secret: true, save: function (v) { setCfg("braveKey", v.trim()); } }); } }),
      row({ icon: "search", label: "Tavily", value: masked(W.hasTavily), onclick: function () { editSheet({ title: "Tavily API", secret: true, save: function (v) { setCfg("tavilyKey", v.trim()); } }); } }),
      row({ icon: "globe", label: "SearXNG", sub: W.searxng || T("Your own search server", "Kendi arama sunucunuz"), onclick: function () { editSheet({ title: "SearXNG", value: W.searxng, type: "url", save: function (v) { setCfg("searxng", v.trim()); } }); } })
    ], T("Search works without keys (DuckDuckGo, Bing, Mojeek from the phone). Keys make it faster and more reliable.", "Arama anahtarsız çalışır (telefondan DuckDuckGo, Bing, Mojeek). Anahtarlar daha hızlı ve güvenilir yapar.")));

    wrap.appendChild(group(T("Data", "Veri"), [
      row({ icon: "upload", label: T("Import a yaver export", "yaver dışa aktarımını içe al"), sub: ".zip", onclick: function () { call("import.zip").then(function (m) { if (m) toast(m); refreshConvos(); }).catch(function (e) { toast(e.message); }); } }),
      row({ icon: "folder", label: T("Files", "Dosyalar"), sub: T("Attachments, downloads and files yaver made", "Ekler, indirilenler ve yaver'in oluşturduğu dosyalar"), onclick: function () { call("workspace.list").then(function (t) { sheet({ title: T("Files", "Dosyalar"), body: h("div", { class: "res", style: "display:block;white-space:pre;font-size:12.5px;overflow:auto;max-height:55vh" }, t || T("(empty)", "(boş)")), actions: [[T("Close", "Kapat")]] }); }); } }),
      row({ icon: "log-in", label: T("Sign out everywhere", "Her yerden çıkış yap"), sub: T("Clears the mail session and website sign-ins", "Posta oturumunu ve site girişlerini temizler"), onclick: function () { sheet({ title: T("Sign out everywhere?", "Her yerden çıkılsın mı?"), actions: [[T("Cancel", "İptal")], [T("Sign out", "Çıkış yap"), "danger", function () { call("cookies.clear").then(function () { refreshState().then(renderSettings); toast(T("Signed out", "Çıkış yapıldı")); }); }]] }); } }),
      row({ icon: "file-text", label: T("Diagnostic log", "Tanılama günlüğü"), sub: T("What yaver did: mail sign-in, connections, tools, errors. No passwords.", "yaver'in yaptıkları: posta oturumu, bağlantılar, araçlar, hatalar. Şifre içermez."), wrap: true, onclick: diagSheet }),
      row({ icon: "info", label: T("Last crash report", "Son çökme raporu"), onclick: function () { call("crash.last").then(function (t) { sheet({ title: T("Last crash report", "Son çökme raporu"), body: h("div", { class: "res", style: "display:block;white-space:pre-wrap;font-size:12px;max-height:50vh;overflow:auto" }, t || T("No crash recorded.", "Kayıtlı çökme yok.")), actions: t ? [[T("Copy", "Kopyala"), "p", function () { call("copy", { text: t }); }], [T("Close", "Kapat")]] : [[T("Close", "Kapat")]] }); }); } })
    ], "yaver " + st.version));
    if (focus === "mail") setTimeout(function () { var el = document.getElementById("s-mail"); if (el) el.scrollIntoView(); }, 300);
    if (focus === "learn") S.settingsFocus = "learn";
    if (S.x) renderExtras();
    loadExtras();
  }

  function diagSheet() {
    var box = h("div", { class: "res", style: "display:block;white-space:pre-wrap;font-size:11.5px;line-height:1.45;max-height:52vh;overflow:auto" }, T("Loading…", "Yükleniyor…"));
    sheet({ title: T("Diagnostic log", "Tanılama günlüğü"), text: T("Share it to show what happened. Passwords, codes and keys are never written to it.", "Ne olduğunu göstermek için paylaşın. Şifreler, kodlar ve anahtarlar asla yazılmaz."), body: box,
      actions: [[T("Clear", "Temizle"), "", function () { call("diag.clear").then(function () { toast(T("Log cleared", "Günlük temizlendi")); }); }],
        [T("Copy", "Kopyala"), "", function () { call("diag.get").then(function (t) { call("copy", { text: t }); toast(T("Copied", "Kopyalandı")); }); return false; }],
        [T("Share", "Paylaş"), "p", function () { shareLog(); }]] });
    call("diag.get").then(function (t) { box.textContent = t || T("(empty)", "(boş)"); box.scrollTop = box.scrollHeight; });
  }
  function shareLog() { call("diag.save").then(function (p) { toast(T("Saved to Downloads/yaver", "İndirilenler/yaver klasörüne kaydedildi")); return call("share.file", { path: p }); }).catch(function (e) { toast(e.message); }); }
  function exportConvo() {
    if (!S.convoId || S.convoId === "_new") { toast(T("Nothing to export yet", "Henüz dışa aktarılacak bir şey yok")); return; }
    call("convo.export", { id: S.convoId }).then(function (r) {
      sheet({ title: T("Chat exported", "Sohbet dışa aktarıldı"), text: T("Saved to Downloads/yaver: a readable copy (.md) and a full record with tool calls and the diagnostic log (.json).", "İndirilenler/yaver klasörüne kaydedildi: okunabilir kopya (.md) ve araç çağrıları ile tanılama günlüğünü içeren tam kayıt (.json)."),
        actions: [[T("Share .md", ".md paylaş"), "", function () { call("share.file", { path: r.md }); }], [T("Share full record", "Tam kaydı paylaş"), "p", function () { call("share.file", { path: r.json }); }]] });
    }).catch(function (e) { toast(e.message); });
  }
  function chatMenu() {
    var s = sheet({ body: h("div", null,
      h("button", { class: "opt", onclick: function () { s.close(); exportConvo(); } }, icon("download", "s"), h("div", null, T("Export this chat", "Bu sohbeti dışa aktar"), h("small", null, T("Readable copy + full record for troubleshooting", "Okunabilir kopya + sorun giderme için tam kayıt")))),
      h("button", { class: "opt", onclick: function () { s.close(); diagSheet(); } }, icon("file-text", "s"), h("div", null, T("Diagnostic log", "Tanılama günlüğü"))),
      h("button", { class: "opt", onclick: function () { s.close(); newChat(true); } }, icon("square-pen", "s"), h("div", null, T("New chat", "Yeni sohbet")))) });
  }

  function totpSheet() {
    var M = S.state.mail;
    editSheet({
      title: T("Authenticator", "Doğrulayıcı"),
      text: T("If your company uses 6-digit codes from an authenticator app, yaver can produce them too. Paste the secret key (or the otpauth:// link) you got when you set up the authenticator — IT can give you a new one. Leave empty to type codes yourself.",
        "Kurumunuz doğrulayıcı uygulamadan 6 haneli kod kullanıyorsa, yaver de bu kodu üretebilir. Doğrulayıcıyı kurarken aldığınız gizli anahtarı (veya otpauth:// bağlantısını) yapıştırın — BT yenisini verebilir. Kodları kendiniz girmek için boş bırakın."),
      secret: true, label: T("Secret key", "Gizli anahtar"), placeholder: M.hasTotp ? "••••••••" : "JBSWY3DPEHPK3PXP",
      help: T("Stored encrypted. Push approvals (tap “Approve” on your phone) need no setup.", "Şifreli saklanır. Telefondan “Onayla” ile yapılan doğrulamalar için ayar gerekmez."),
      save: function (v) { setCfg("mailTotp", v.trim()); },
      extra: M.hasTotp ? [[T("Remove", "Kaldır"), "", function () { setCfg("mailTotp", ""); }]] : []
    });
  }

  function pickModel(key, allowEmpty) {
    var list = h("div", { class: "mlist" }, h("div", { class: "status" }, h("div", { class: "spin" }), T("Loading models…", "Modeller yükleniyor…")));
    var q = h("input", { type: "search", placeholder: T("Filter: free, gpt, claude, gemini…", "Süz: free, gpt, claude, gemini…") });
    var all = [];
    var s = sheet({ title: T("Choose a model", "Model seçin"), body: h("div", null, h("label", { class: "search" }, icon("search", "xs"), q), list), actions: allowEmpty ? [[T("Use the main model", "Ana modeli kullan"), "", function () { setCfg(key, ""); }]] : [] });
    function draw() {
      clear(list);
      var f = q.value.toLowerCase().trim();
      var shown = all.filter(function (m) { return !f || m.toLowerCase().indexOf(f) >= 0; }).slice(0, 300);
      if (!shown.length) list.appendChild(h("div", { class: "status" }, T("No model matches.", "Eşleşen model yok.")));
      shown.forEach(function (m) {
        list.appendChild(h("button", { class: "opt", onclick: function () { s.close(); setCfg(key, m).then(function () { toast(T("Model: ", "Model: ") + m); if (topPage() === "setup") renderSetup(); }); } },
          h("div", null, m.split("/").pop(), h("small", null, m.indexOf("/") > 0 ? m.split("/")[0] + (/:free$/.test(m) ? T(" · free", " · ücretsiz") : "") : "")), (S.state.llm[key] === m) ? icon("check", "s c") : null));
      });
    }
    q.addEventListener("input", draw);
    call("models").then(function (m) { all = m || []; draw(); }).catch(function (e) {
      clear(list).appendChild(h("div", { class: "err" }, icon("triangle-alert", "s"), h("div", null, e.message)));
      list.appendChild(h("button", { class: "btn", style: "width:100%;margin-top:8px", onclick: function () { s.close(); editSheet({ title: T("Model name", "Model adı"), value: S.state.llm[key], placeholder: "openai/gpt-4o-mini", save: function (v) { setCfg(key, v.trim()); } }); } }, T("Type the name instead", "Adını yazın")));
    });
  }

  /* ------------------------------------------------------------ knowledge: memory, about you, skills, lessons, trash */
  var KB_TABS = [["memory", "brain", "Memory", "Hafıza"], ["user", "user", "About you", "Hakkınızda"], ["skills", "graduation-cap", "Skills", "Yetenekler"], ["lessons", "sparkles", "Learned", "Öğrenilenler"], ["trash", "trash-2", "Trash", "Çöp kutusu"]];
  function openKnowledge(tab) {
    closeDrawer();
    if (tab) { if (tab !== S.kb.tab) { S.kb.q = ""; S.kb.filter = "all"; } S.kb.tab = tab; }
    if (topPage() === "knowledge") { renderKb(); return; }
    var more = h("button", { class: "ib", "aria-label": T("More", "Daha fazla"), onclick: function () {
      var s = sheet({ body: h("div", null,
        h("button", { class: "opt", onclick: function () { s.close(); toast(T("Rewriting search keys…", "Arama anahtarları yeniden yazılıyor…")); call("memory.reindex").then(function (r) { toast(okMsg(r) || T("Done", "Tamam")); renderKb(); }).catch(function (e) { toast(e.message); }); } }, icon("refresh-cw", "s"), h("div", null, T("Rewrite search keys", "Arama anahtarlarını yeniden yaz"), h("small", null, T("One model call per 30 notes", "30 notta bir model çağrısı")))),
        h("button", { class: "opt", onclick: function () { s.close(); openSettings("learn"); } }, icon("settings", "s"), h("div", null, T("Learning settings", "Öğrenme ayarları")))) });
    } }, icon("ellipsis-vertical"));
    pushPage("knowledge", T("Memory & lessons", "Hafıza ve dersler"), function (body) {
      body.appendChild(h("div", { class: "tabs", role: "tablist" }, KB_TABS.map(function (t) {
        return h("button", { class: "tab" + (S.kb.tab === t[0] ? " on" : ""), role: "tab", "data-tab": t[0], onclick: function () { if (S.kb.tab === t[0]) return; S.kb.tab = t[0]; S.kb.q = ""; S.kb.filter = "all"; renderKb(); } }, icon(t[1], "xs"), T(t[2], t[3]));
      })));
      body.appendChild(h("div", { id: "kbbody" }));
      renderKb();
    }, { actions: [more] });
  }
  function okMsg(r) { return r && typeof r === "object" ? (r.ok === false ? r.error : r.message) : (typeof r === "string" ? r : ""); }
  function kbDo(p, quiet) { return p.then(function (r) { if (r && r.ok === false) toast(r.error || T("Not done", "Yapılmadı")); else if (!quiet && okMsg(r)) toast(okMsg(r)); renderKb(); return r; }).catch(function (e) { toast(e.message); }); }
  var kbT = 0;
  function kbChanged() { if (S.pages.some(function (p) { return p.name === "knowledge"; })) { clearTimeout(kbT); kbT = setTimeout(renderKb, 300); } }
  function kbPage() { return S.pages.filter(function (p) { return p.name === "knowledge"; })[0]; }
  function renderKb() {
    var p = kbPage(); if (!p) return;
    p.body.querySelectorAll(".tabs .tab").forEach(function (b) { var on = b.getAttribute("data-tab") === S.kb.tab; b.classList.toggle("on", on); if (on) { var tb = b.parentNode; tb.scrollLeft = Math.max(0, b.offsetLeft - (tb.clientWidth - b.offsetWidth) / 2); } });
    var box = $("#kbbody", p.body); if (!box) return;
    var tab = S.kb.tab;
    if (!box.firstChild) box.appendChild(loading());
    if (tab === "memory" || tab === "user") call("memory.list", { target: tab }).then(function (r) { if (S.kb.tab === tab) memoryView(clear(box), tab, r || {}); }).catch(function (e) { errorView(clear(box), e); });
    else if (tab === "skills") call("skills.list").then(function (r) { if (S.kb.tab === tab) skillsView(clear(box), r || []); }).catch(function (e) { errorView(clear(box), e); });
    else if (tab === "lessons") call("lessons.screen", { convoId: S.convoId || null, filter: S.kb.filter, q: "" }).then(function (r) { if (S.kb.tab === tab) lessonsView(clear(box), r || {}); }).catch(function (e) { errorView(clear(box), e); });
    else call("memory.trash").then(function (r) { if (S.kb.tab === tab) trashView(clear(box), r || []); }).catch(function (e) { errorView(clear(box), e); });
  }
  function loading() { return h("div", { class: "status", style: "padding:24px 16px" }, h("div", { class: "spin" }), T("Loading…", "Yükleniyor…")); }
  function errorView(box, e) { box.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("triangle-alert")), h("p", null, e.message || String(e)))); }
  function kstat(label, big, small, frac, cls) {
    return h("div", { class: "kst " + (cls || "") }, h("span", null, label), h("div", null, h("b", null, big), small ? h("small", null, small) : null),
      frac != null ? h("div", { class: "meter" }, h("i", { style: "width:" + Math.max(2, Math.min(100, Math.round(frac * 100))) + "%" })) : null);
  }
  function searchBox(ph, value, onInput) {
    var q = h("input", { type: "search", placeholder: ph, value: value || "" });
    var t = 0; q.addEventListener("input", function () { clearTimeout(t); t = setTimeout(function () { onInput(q.value); }, 150); });
    return h("label", { class: "search" }, icon("search", "xs"), q);
  }
  function filterChips(opts, cur, pickFn) {
    return h("div", { class: "fchips" }, opts.map(function (o) { return h("button", { class: "chip sm" + (cur === o[0] ? " on" : ""), onclick: function () { pickFn(o[0]); } }, o[1], o[2] != null ? h("small", null, " " + o[2]) : null); }));
  }
  function foldTxt(s) { return String(s || "").toLocaleLowerCase("tr").normalize("NFKD").replace(/[̀-ͯ]/g, "").replace(/ı/g, "i"); }

  function memoryView(box, target, r) {
    var tiered = target === "memory", st = r.stats || {}, all = r.entries || [];
    if (tiered) box.appendChild(h("div", { class: "kstats" },
      kstat(T("Active", "Aktif"), String(st.active || 0), "/ " + (st.activeMax || "—"), st.activeMax ? (st.active || 0) / st.activeMax : null, "ac"),
      kstat(T("Drafts on trial", "Denemedeki taslaklar"), String(st.drafts || 0), "/ " + (st.draftMax || "—"), st.draftMax ? (st.drafts || 0) / st.draftMax : null, "dr"),
      kstat(T("In every prompt", "Her istemde"), String(st.inPrompt || 0), "/ " + (st.promptMax || "—"), st.promptMax ? (st.inPrompt || 0) / st.promptMax : null, "pr"),
      kstat(T("Days of use", "Kullanım günü"), String(st.days || 0), T(" days", " gün"))));
    else box.appendChild(h("div", { class: "kstats" },
      kstat(T("Profile size", "Profil boyutu"), String(st.chars || 0), "/ " + (st.charsMax || "—") + T(" chars", " karakter"), st.charsMax ? (st.chars || 0) / st.charsMax : null, "pr"),
      kstat(T("Entries", "Kayıt"), String(st.entries != null ? st.entries : all.length), "")));
    box.appendChild(h("p", { class: "kexpl" }, tiered
      ? T("New notes from yaver start as drafts on trial (counted in days you use yaver). Used drafts become active; unused ones retire to the trash. Pinned notes, rules and the most used active notes go into every prompt.",
        "yaver'in yeni notları deneme süreli taslak olarak başlar (yaver'i kullandığınız günlerle sayılır). Kullanılan taslak aktif olur, kullanılmayan çöp kutusuna emekli edilir. Sabit notlar, kurallar ve en çok kullanılan aktif notlar her isteme girer.")
      : T("Who you are and how you like to work. yaver keeps all of this in mind in every chat.", "Kim olduğunuz ve nasıl çalışmayı sevdiğiniz. yaver bunların hepsini her sohbette aklında tutar.")));
    var listBox = h("div", { class: "klist" });
    box.appendChild(h("div", { class: "ktools" },
      searchBox(T("Search notes…", "Notlarda ara…"), S.kb.q, function (v) { S.kb.q = v; drawList(); }),
      h("button", { class: "btn sm p", onclick: function () { addMemory(target); } }, icon("plus", "xs"), T("New", "Yeni"))));
    if (tiered) {
      var counts = { all: all.length, active: all.filter(function (e) { return e.tier === "active"; }).length, draft: all.filter(function (e) { return e.tier === "draft"; }).length, prompt: all.filter(function (e) { return e.inPrompt; }).length, pinned: all.filter(function (e) { return e.pinned; }).length };
      var SORTS = [["use", T("Most used", "En çok kullanılan")], ["new", T("Newest", "En yeni")], ["old", T("Oldest", "En eski")], ["trial", T("Trial ending soon", "Deneme süresi bitmek üzere")], ["az", "A → Z"]];
      box.appendChild(h("div", { class: "frow" },
        filterChips([["all", T("All", "Tümü"), counts.all], ["active", T("Active", "Aktif"), counts.active], ["draft", T("Drafts", "Taslak"), counts.draft], ["prompt", T("In prompt", "İstemde"), counts.prompt], ["pinned", T("Pinned", "Sabit"), counts.pinned]], S.kb.filter, function (v) { S.kb.filter = v; renderKbLocal(); }),
        h("button", { class: "select sm", onclick: function () { optionsSheet(T("Sort", "Sırala"), SORTS, S.kb.sort, function (v) { S.kb.sort = v; renderKbLocal(); }); } }, icon("arrow-up-down", "xs"), (SORTS.filter(function (x) { return x[0] === S.kb.sort; })[0] || SORTS[0])[1])));
    }
    box.appendChild(listBox);
    function renderKbLocal() { memoryView(clear(box), target, r); }
    function drawList() {
      clear(listBox);
      var q = foldTxt(S.kb.q);
      var shown = all.filter(function (e) {
        if (q && foldTxt(e.text + " " + (e.topicLabel || "")).indexOf(q) < 0) return false;
        if (!tiered || S.kb.filter === "all") return true;
        return S.kb.filter === "pinned" ? e.pinned : S.kb.filter === "prompt" ? e.inPrompt : e.tier === S.kb.filter;
      });
      var SO = { use: function (a, b) { return (b.rate || 0) - (a.rate || 0) || (b.uses || 0) - (a.uses || 0); }, new: function (a, b) { return (b.created || 0) - (a.created || 0); }, old: function (a, b) { return (a.created || 0) - (b.created || 0); },
        trial: function (a, b) { return (a.tier === "draft" ? a.daysLeft || 0 : 1e9) - (b.tier === "draft" ? b.daysLeft || 0 : 1e9); }, az: function (a, b) { return String(a.text).localeCompare(String(b.text), "tr"); } };
      shown.sort(function (a, b) { return (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (SO[S.kb.sort] || SO.use)(a, b); });
      if (!shown.length) { listBox.appendChild(h("div", { class: "kempty" }, icon(tiered ? "brain" : "user"), q ? T("No note matches your search.", "Aramanıza uyan not yok.") : T("Nothing here yet.", "Henüz bir şey yok."))); return; }
      shown.forEach(function (e) { listBox.appendChild(memCard(target, e, tiered)); });
    }
    drawList();
  }
  function memCard(target, e, tiered) {
    var facts = [];
    if (tiered) {
      facts.push(T("used ", "kullanım ") + (e.uses || 0) + (e.rate ? " · " + e.rate + T("/day", "/gün") : ""));
      facts.push(e.tier === "draft" ? T((e.daysLeft || 0) + " trial days left", "deneme: " + (e.daysLeft || 0) + " gün kaldı") : T("active " + (e.activeDays || 1) + " days", (e.activeDays || 1) + " gündür aktif"));
      facts.push(T("last used ", "son kullanım ") + (e.lastUsedText || "—"));
    }
    facts.push(e.by === "user" ? T("by you", "sizin eklediğiniz") : T("by yaver", "yaver ekledi"));
    return h("button", { class: "kcard" + (e.pinned ? " pinned" : "") + (e.tier === "draft" ? " draft" : ""), onclick: function () { memMenu(target, e, tiered); } },
      h("div", { class: "kbad" },
        e.pinned ? h("span", { class: "bdg pin" }, icon("pin", "xs"), T("Pinned", "Sabit")) : null,
        tiered ? h("span", { class: "bdg " + (e.tier === "draft" ? "dr" : "ac") }, e.tier === "draft" ? T("Draft", "Taslak") : T("Active", "Aktif")) : null,
        e.inPrompt ? h("span", { class: "bdg pr" }, T("In prompt", "İstemde")) : null,
        e.rule ? h("span", { class: "bdg rl" }, T("Rule", "Kural")) : null,
        e.conflict ? h("span", { class: "bdg warn" }, T("Conflict", "Çelişki")) : null,
        e.topicLabel ? h("span", { class: "bdg tp" }, e.topicLabel) : null),
      h("div", { class: "ktext" }, e.text),
      h("div", { class: "kfacts" }, facts.join(" · ")));
  }
  function memMenu(target, e, tiered) {
    var s = sheet({ title: e.pinned ? T("Pinned note", "Sabit not") : tiered ? (e.tier === "draft" ? T("Draft note", "Taslak not") : T("Active note", "Aktif not")) : T("About you", "Hakkınızda"),
      body: h("div", null,
        h("div", { class: "kquote" }, e.text),
        (e.keys || []).length ? h("p", { class: "kmeta" }, T("Search keys: ", "Arama anahtarları: ") + e.keys.join(", ")) : null,
        e.sourceTitle ? h("p", { class: "kmeta" }, T("From: ", "Kaynak: "), h("a", { href: "#", onclick: function (ev) { ev.preventDefault(); s.close(); closeAllPages(); loadConvo(e.sid); } }, e.sourceTitle)) : null,
        h("button", { class: "opt", onclick: function () { s.close(); editSheet({ title: T("Edit note", "Notu düzenle"), multi: true, value: e.text, help: T("Empty text moves it to the trash.", "Boş metin notu çöp kutusuna taşır."), save: function (v) { kbDo(call("memory.edit", { target: target, entry: e.text, id: e.text, text: v })); } }); } }, icon("pencil", "s"), T("Edit", "Düzenle")),
        h("button", { class: "opt", onclick: function () { s.close(); kbDo(call("memory.pin", { target: target, entry: e.text, text: e.text, on: !e.pinned }), true); } }, icon(e.pinned ? "pin-off" : "pin", "s"), h("div", null, e.pinned ? T("Unpin", "Sabitlemeyi kaldır") : T("Pin", "Sabitle"), e.pinned ? null : h("small", null, T("yaver never changes or removes it", "yaver asla değiştirmez veya silmez")))),
        tiered && !e.pinned ? h("button", { class: "opt", onclick: function () { s.close(); kbDo(call("memory.tier", { target: target, entry: e.text, text: e.text }), true); } }, icon(e.tier === "draft" ? "circle-check" : "rotate-ccw", "s"), e.tier === "draft" ? T("Make active now", "Hemen aktif yap") : T("Back to drafts (new trial)", "Taslağa geri al (yeni deneme)")) : null,
        h("button", { class: "opt bad", disabled: e.pinned || null, onclick: function () { s.close(); kbDo(call("memory.delete", { target: target, entry: e.text, text: e.text })); } }, icon("trash-2", "s"), h("div", null, T("Delete", "Sil"), h("small", null, e.pinned ? T("Unpin before deleting", "Silmeden önce sabitlemeyi kaldırın") : T("Moves to the trash", "Çöp kutusuna taşınır"))))) });
  }
  function addMemory(target) {
    editSheet({ title: target === "memory" ? T("New note", "Yeni not") : T("New entry", "Yeni kayıt"), multi: true,
      placeholder: target === "memory" ? T("One concise fact, e.g. 'Reports go to finance on Fridays'", "Tek ve kısa bir bilgi, ör. 'Raporlar cuma günü finansa gider'") : T("e.g. 'Prefers short bullet points in Turkish'", "ör. 'Türkçe ve kısa maddeler halinde ister'"),
      help: T("Hand-written notes are active right away.", "Elle yazılan notlar hemen aktif olur."),
      save: function (v) { if (!v.trim()) return; kbDo(call("memory.add", { target: target, text: v.trim() })); } });
  }

  function skillsView(box, list) {
    box.appendChild(h("p", { class: "kexpl" }, T("Procedures yaver saved to reuse: each skill is a folder with instructions (SKILL.md) and helper files. yaver opens the right one when a task matches.", "yaver'in tekrar kullanmak için kaydettiği yöntemler: her yetenek talimatlar (SKILL.md) ve yardımcı dosyalar içeren bir klasördür. Görev uyduğunda yaver doğru olanı açar.")));
    if (!list.length) { box.appendChild(h("div", { class: "kempty" }, icon("graduation-cap"), T("No skills yet. Ask yaver to “save this as a skill” after it did something you want repeated.", "Henüz yetenek yok. Tekrarlanmasını istediğiniz bir işten sonra yaver'e “bunu yetenek olarak kaydet” deyin."))); return; }
    var lb = h("div", { class: "klist" });
    list.forEach(function (s) {
      lb.appendChild(h("button", { class: "kcard", onclick: function () {
        sheet({ title: s.name, body: h("div", null, h("p", { class: "kquote" }, s.description || "—"), h("dl", { class: "dl" }, h("dt", null, T("Folder", "Klasör")), h("dd", null, s.dir || ""), h("dt", null, T("Files", "Dosyalar")), h("dd", null, (s.files || []).join("\n") || "SKILL.md"))),
          actions: [[T("Close", "Kapat")], [T("Use it", "Kullan"), "p", function () { closeAllPages(); $("#input").value = T("Use the skill “" + s.name + "” to ", "“" + s.name + "” yeteneğini kullanarak "); autosize(); syncSend(); $("#input").focus(); }]] });
      } }, h("div", { class: "kbad" }, h("span", { class: "bdg tp" }, icon("graduation-cap", "xs"), s.dir || s.name), (s.files || []).length > 1 ? h("span", { class: "bdg" }, (s.files.length) + T(" files", " dosya")) : null),
        h("div", { class: "ktext" }, h("b", null, s.name)), s.description ? h("div", { class: "kfacts clamp" }, s.description) : null));
    });
    box.appendChild(lb);
  }

  function lessonsView(box, r) {
    var st = r.stats || {};
    if (r.learn === false) box.appendChild(h("div", { class: "warncard" }, icon("info", "s"), h("div", null, h("b", null, T("Learning is off", "Öğrenme kapalı")), h("span", null, T("yaver keeps using what it learned but adds nothing new.", "yaver öğrendiklerini kullanır ama yenisini eklemez."))), h("button", { class: "btn sm", onclick: function () { call("learn.set", { learn: true, every: r.every || 6 }).then(renderKb); } }, T("Turn on", "Aç"))));
    var vH = st.helpful || 0, vB = st.harmful || 0;
    box.appendChild(h("div", { class: "kstats" },
      kstat(T("Active lessons", "Etkin dersler"), String(st.active || 0), T(" · " + (st.activeGlobal || 0) + " everywhere", " · " + (st.activeGlobal || 0) + " her yerde"), null, "ac"),
      kstat(T("Suspended", "Askıda"), String(st.suspended || 0), T(" hurt more than helped", " zarar verdi"), null, "dr"),
      kstat(T("Feedback", "Geri bildirim"), "✓" + vH + "  ✗" + vB, "", vH + vB ? vH / (vH + vB) : null, "pr"),
      kstat(T("Last sleep pass", "Son uyku aşaması"), st.lastSleep ? shortWhen(st.lastSleep) : T("not yet", "henüz yok"), "")));
    if (r.explanation) box.appendChild(h("details", { class: "kexpl" }, h("summary", null, T("How yaver learns", "yaver nasıl öğrenir")), h("div", null, r.explanation)));
    var lb = h("div", { class: "klist" });
    var tidy = h("button", { class: "btn sm", onclick: function () {
      tidy.disabled = true; toast(T("Tidying lessons…", "Dersler düzenleniyor…"));
      call("lessons.sleep").then(function () { setTimeout(renderKb, 800); }).catch(function (e) { tidy.disabled = false; toast(e.message); });
    } }, icon("moon", "xs"), T("Tidy now", "Şimdi düzenle"));
    box.appendChild(h("div", { class: "ktools" }, searchBox(T("Search lessons…", "Derslerde ara…"), S.kb.q, function (v) { S.kb.q = v; draw(); }), tidy));
    box.appendChild(h("div", { class: "frow" }, filterChips([["all", T("All", "Tümü")], ["global", T("Everywhere", "Her yerde")], ["local", T("One chat", "Tek sohbet")], ["suspended", T("Suspended", "Askıda")]], S.kb.filter, function (v) { S.kb.filter = v; renderKb(); })));
    box.appendChild(lb);
    function draw() {
      clear(lb);
      var q = foldTxt(S.kb.q);
      var es = (r.entries || []).filter(function (e) { return !q || foldTxt((e.title || "") + " " + (e.content || "")).indexOf(q) >= 0; });
      if (!es.length) { lb.appendChild(h("div", { class: "kempty" }, icon("sparkles"), q ? T("No lesson matches your search.", "Aramanıza uyan ders yok.") : (r.empty || T("No lessons yet.", "Henüz ders yok.")))); return; }
      es.forEach(function (e) { lb.appendChild(lessonCard(e)); });
    }
    draw();
    var rules = r.rules || [];
    if (rules.length) box.appendChild(group(T("Standing rules", "Kalıcı kurallar"), rules.map(function (x) {
      return h("div", { class: "row" }, h("div", { class: "rt" }, h("b", { class: "wrapb" }, x.text), h("span", null, x.target === "user" ? T("About you", "Hakkınızda") : T("Memory", "Hafıza"))), h("span", { class: "votes" }, h("i", { class: "ok" }, "✓" + (x.helpful || 0)), h("i", { class: "bad" }, "✗" + (x.harmful || 0))));
    }), T("Rules from your memory, with how often they helped (✓) or led to a wrong answer (✗).", "Hafızanızdaki kurallar; kaç kez işe yaradığı (✓) ve kaç kez yanlış yanıta yol açtığı (✗).")));
    var fails = r.failures || [];
    if (fails.length) box.appendChild(group(T("Recent mistakes", "Son hatalar"), fails.map(function (f) {
      return h("div", { class: "row" }, h("div", { class: "tile" }, icon(f.kind === "thumbs_down" ? "thumbs-down" : "circle-alert")), h("div", { class: "rt" }, h("b", { class: "wrapb" }, f.q || "—"), h("span", { class: "wrap" }, (f.complaint ? "“" + f.complaint + "” · " : "") + shortWhen(f.at))));
    })));
    var sl = r.sleepLog || [];
    if (sl.length) box.appendChild(group(T("Sleep passes", "Uyku aşamaları"), sl.map(function (l) {
      var bits = [];
      if (l.merged) bits.push(T(l.merged + " merged", l.merged + " birleştirildi"));
      if (l.purged) bits.push(T(l.purged + " removed", l.purged + " kaldırıldı"));
      if (l.refined) bits.push(T("new lesson: ", "yeni ders: ") + l.refined);
      if (l.error) bits.push(l.error);
      return h("div", { class: "row" }, h("div", { class: "tile" }, icon("moon")), h("div", { class: "rt" }, h("b", null, l.at ? longWhen(l.at) : "—"), h("span", { class: "wrap" }, bits.join(" · ") || T("nothing to tidy", "düzenlenecek bir şey yok"))));
    })));
  }
  function lessonCard(e) {
    var sus = !!e.suspended || e.status === "suspended";
    return h("button", { class: "kcard" + (sus ? " susp" : "") + (e.current ? " cur" : ""), onclick: function () { lessonMenu(e); } },
      h("div", { class: "kbad" }, h("span", { class: "bdg tp" }, e.kindLabel || e.kind), h("span", { class: "bdg " + (e.scope === "global" ? "ac" : "") }, e.scopeLabel || e.scope), sus ? h("span", { class: "bdg warn" }, T("Suspended", "Askıda")) : null, e.current ? h("span", { class: "bdg pr" }, T("This chat", "Bu sohbet")) : null),
      h("div", { class: "ktext" }, h("b", null, e.title)),
      e.content ? h("div", { class: "kfacts clamp" }, e.content) : null,
      h("div", { class: "kfoot" }, h("span", { class: "votes" }, h("i", { class: "ok" }, "✓" + (e.helpful || 0)), h("i", { class: "bad" }, "✗" + (e.harmful || 0))), h("span", null, [e.sourceLabel, e.convoTitle].filter(Boolean).join(" · "))));
  }
  function lessonMenu(e) {
    var sus = !!e.suspended || e.status === "suspended", sid = e.session || null;
    var s = sheet({ title: e.title, body: h("div", null,
      h("div", { class: "kquote md-s" }, md(e.content || "")),
      h("p", { class: "kmeta" }, [e.kindLabel, e.scopeLabel, e.sourceLabel].filter(Boolean).join(" · "), " · ", h("span", { class: "votes" }, h("i", { class: "ok" }, "✓" + (e.helpful || 0)), h("i", { class: "bad" }, "✗" + (e.harmful || 0)))),
      sid && e.convoTitle ? h("p", { class: "kmeta" }, T("From: ", "Kaynak: "), h("a", { href: "#", onclick: function (ev) { ev.preventDefault(); s.close(); closeAllPages(); loadConvo(sid); } }, e.convoTitle)) : null,
      h("button", { class: "opt", onclick: function () { s.close(); lessonEdit(e); } }, icon("pencil", "s"), T("Edit", "Düzenle")),
      h("button", { class: "opt", onclick: function () { s.close(); kbDo(call("lessons.enable", { kind: e.kind, id: e.id, on: sus }).then(function (ok) { toast(sus ? T("Switched back on with a fresh trial", "Yeni bir denemeyle yeniden açıldı") : T("Suspended", "Askıya alındı")); return ok; }), true); } }, icon(sus ? "play" : "pause", "s"), h("div", null, sus ? T("Switch back on", "Yeniden aç") : T("Suspend", "Askıya al"), h("small", null, sus ? T("Starts a fresh trial", "Yeni bir deneme başlar") : T("yaver stops using it", "yaver kullanmayı bırakır")))),
      h("button", { class: "opt bad", onclick: function () { s.close(); kbDo(call("lessons.delete", { kind: e.kind, id: e.id })); } }, icon("trash-2", "s"), T("Delete", "Sil"))) });
  }
  function lessonEdit(e) {
    var ti = h("input", { type: "text" }); ti.value = e.title || "";
    var ta = h("textarea", { rows: 8 }); ta.value = e.content || "";
    sheet({ title: T("Edit lesson", "Dersi düzenle"), body: h("div", null, h("label", { class: "field" }, h("span", null, T("Title", "Başlık")), ti), h("label", { class: "field" }, h("span", null, T("Text", "Metin")), ta)),
      actions: [[T("Cancel", "İptal")], [T("Save", "Kaydet"), "p", function () {
        if (!ti.value.trim() || !ta.value.trim()) { toast(T("Title and text are required", "Başlık ve metin gerekli")); return false; }
        kbDo(call("lessons.edit", { kind: e.kind, id: e.id, title: ti.value.trim(), content: ta.value.trim() }), true);
      }]] });
  }

  function trashView(box, list) {
    box.appendChild(h("p", { class: "kexpl" }, T("Notes that were replaced, found outdated, retired after their trial, or deleted. Restore brings a note back as active.", "Yenisiyle değiştirilen, geçersiz bulunan, deneme süresi sonunda emekli edilen veya silinen notlar. Geri yükle, notu aktif olarak geri getirir.")));
    box.appendChild(h("div", { class: "ktools" }, h("span", { class: "kcount" }, T(list.length + " items", list.length + " öğe")),
      list.length ? h("button", { class: "btn sm ghost danger-t", onclick: function () {
        sheet({ title: T("Empty the trash?", "Çöp kutusu boşaltılsın mı?"), text: T("This cannot be undone.", "Bu geri alınamaz."), actions: [[T("Cancel", "İptal")], [T("Empty", "Boşalt"), "danger", function () { kbDo(call("memory.emptyTrash"), true); }]] });
      } }, icon("trash-2", "xs"), T("Empty trash", "Çöpü boşalt")) : null));
    if (!list.length) { box.appendChild(h("div", { class: "kempty" }, icon("trash-2"), T("The trash is empty.", "Çöp kutusu boş."))); return; }
    var lb = h("div", { class: "klist" });
    list.forEach(function (x) {
      lb.appendChild(h("div", { class: "kcard trash" },
        h("div", { class: "kbad" }, h("span", { class: "bdg tr-" + x.kind }, x.kindLabel || x.kind), h("span", { class: "kmeta" }, (x.target === "user" ? T("About you", "Hakkınızda") : T("Memory", "Hafıza")) + " · " + (x.when || shortWhen(x.at)))),
        h("div", { class: "ktext" }, x.text),
        x.reason ? h("div", { class: "kfacts" }, x.reason + (x.replacedBy ? " → “" + String(x.replacedBy).slice(0, 120) + "”" : "")) : null,
        h("div", { class: "cf" },
          h("button", { class: "btn sm", onclick: function () { kbDo(call("memory.restore", { id: x.id })); } }, icon("undo-2", "xs"), T("Restore", "Geri yükle")),
          h("button", { class: "btn sm ghost danger-t", onclick: function () { kbDo(call("memory.forget", { id: x.id }), true); } }, T("Delete forever", "Kalıcı sil")))));
    });
    box.appendChild(lb);
  }

  /* ------------------------------------------------------------ tasks board */
  var TSTAT = [["todo", "To do", "Yapılacak", "circle"], ["doing", "In progress", "Sürüyor", "circle-dot"], ["done", "Done", "Bitti", "circle-check"]];
  function tstat(s) { var x = TSTAT.filter(function (q) { return q[0] === s; })[0] || TSTAT[0]; return { id: x[0], label: T(x[1], x[2]), icon: x[3] }; }
  function dueText(d) { if (!d) return ""; var p = d.split("-"); var dt = new Date(+p[0], +p[1] - 1, +p[2]); var now = new Date(); if (sameDay(dt, now)) return T("Today", "Bugün"); if (sameDay(dt, new Date(now.getTime() + 864e5))) return T("Tomorrow", "Yarın"); if (sameDay(dt, new Date(now.getTime() - 864e5))) return T("Yesterday", "Dün"); return dt.toLocaleDateString(loc(), { day: "numeric", month: "short" }); }
  function refreshOverdue() {
    call("tasks.overdue").then(function (n) {
      S.overdue = +n || 0;
      var b = $("#odbadge"); if (b) { b.textContent = S.overdue > 9 ? "9+" : String(S.overdue); b.classList.toggle("hidden", !S.overdue); }
    }).catch(function () {});
  }
  function tasksPage() { return S.pages.filter(function (p) { return p.name === "tasks"; })[0]; }
  function openTasks() {
    closeDrawer();
    if (topPage() === "tasks") return;
    var people = h("button", { class: "ib", "aria-label": T("People", "Kişiler"), onclick: peopleSheet }, icon("users"));
    var add = h("button", { class: "ib", "aria-label": T("New task", "Yeni görev"), onclick: function () { newTaskSheet(); } }, icon("plus"));
    pushPage("tasks", T("Tasks", "Görevler"), function (body) {
      body.appendChild(h("div", { id: "osync" }));
      body.appendChild(h("div", { id: "ttools" }));
      body.appendChild(h("div", { id: "tlist" }, loading()));
      loadTasks(); loadOsync();
    }, { actions: [people, add] });
  }
  function taskArgs(extra) { var a = { q: S.tasks.q, owner: S.tasks.owner, status: S.tasks.status }; if (S.tasks.sort) a.sort = S.tasks.sort; for (var k2 in extra || {}) a[k2] = extra[k2]; return a; }
  function loadTasks() {
    if (!tasksPage()) return Promise.resolve();
    return call("tasks.state", taskArgs()).then(renderTasks).catch(function (e) { var l = $("#tlist"); if (l) errorView(clear(l), e); });
  }
  function renderTasks(st) {
    var p = tasksPage(); if (!p || !st) return;
    S.tasks.data = st; S.tasks.sort = st.sort || S.tasks.sort;
    S.overdue = st.overdue || 0; refreshOverdue();
    clear(p.title).appendChild(document.createTextNode(T("Tasks", "Görevler")));
    if (st.overdue) p.title.appendChild(h("small", { class: "odsub" }, T(st.overdue + " overdue", st.overdue + " gecikmiş")));
    var c = st.counts || {};
    var SORTS = [["manual", T("My order", "Benim sıram")], ["due", T("Due date", "Son tarih")], ["owner", T("Owner", "Sorumlu")], ["updated", T("Recently updated", "Son güncellenen")]];
    var tools = clear($("#ttools", p.body));
    tools.appendChild(h("div", { class: "toolbar" }, searchBox(T("Search tasks", "Görevlerde ara"), S.tasks.q, function (v) { S.tasks.q = v; loadTasks(); })));
    tools.appendChild(h("div", { class: "frow" }, filterChips([["open", T("Open", "Açık"), (c.todo || 0) + (c.doing || 0)], ["all", T("All", "Tümü"), c.all || 0], ["todo", T("To do", "Yapılacak"), c.todo || 0], ["doing", T("In progress", "Sürüyor"), c.doing || 0], ["done", T("Done", "Bitti"), c.done || 0]], S.tasks.status, function (v) { S.tasks.status = v; loadTasks(); })));
    tools.appendChild(h("div", { class: "toolbar" },
      h("button", { class: "select sm" + (S.tasks.owner ? " on" : ""), onclick: function () { optionsSheet(T("Owner", "Sorumlu"), [["", T("Everyone", "Herkes")]].concat((st.people || []).map(function (x) { return [x.name, x.name, "user", T(x.open + " open", x.open + " açık")]; })), S.tasks.owner, function (v) { S.tasks.owner = v; loadTasks(); }); } }, icon("user", "xs"), S.tasks.owner || T("Everyone", "Herkes"), icon("chevron-down", "xs")),
      h("button", { class: "select sm", onclick: function () { optionsSheet(T("Sort", "Sırala"), SORTS, S.tasks.sort, function (v) { S.tasks.sort = v; call("tasks.sort", taskArgs({ sort: v })).then(renderTasks).catch(function (e) { toast(e.message); }); }); } }, icon("arrow-up-down", "xs"), (SORTS.filter(function (x) { return x[0] === S.tasks.sort; })[0] || SORTS[0])[1], icon("chevron-down", "xs"))));
    var list = clear($("#tlist", p.body));
    var tasks = st.tasks || [];
    if (!tasks.length) {
      list.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("square-kanban")), h("h3", null, S.tasks.q || S.tasks.owner ? T("No task matches", "Eşleşen görev yok") : T("No open tasks", "Açık görev yok")),
        h("p", null, T("Add one here, or ask yaver: “make a task from this e-mail for Ayşe, due Friday”.", "Buradan ekleyin ya da yaver'e söyleyin: “bu e-postadan Ayşe için cuma teslimli bir görev oluştur”.")),
        h("div", { class: "btnstack" }, h("button", { class: "btn p", onclick: function () { newTaskSheet(); } }, icon("plus", "s"), T("New task", "Yeni görev")))));
    } else tasks.forEach(function (t, i) { list.appendChild(taskCard(t, i, tasks, st.manual)); });
    list.appendChild(h("button", { class: "askrow", onclick: askTasks }, icon("sparkles", "s"), h("span", null, T("Ask yaver about my tasks…", "Görevlerim hakkında yaver'e sor…"))));
  }
  function taskCard(t, i, all, manual) {
    var s = tstat(t.status);
    var notes = (t.notes || []).slice().sort(function (a, b) { return (b.at || 0) - (a.at || 0); });
    var last = notes[0];
    return h("div", { class: "tcard " + t.status + (t.overdue ? " od" : "") },
      h("button", { class: "tst " + t.status, "aria-label": s.label, onclick: function (e) { e.stopPropagation(); optionsSheet(T("Status", "Durum"), TSTAT.map(function (x) { return [x[0], T(x[1], x[2]), x[3]]; }), t.status, function (v) { updTask(t.id, { status: v }); }); } }, icon(s.icon, "s")),
      h("button", { class: "tmain", onclick: function () { taskSheet(t.id, t); } },
        h("div", { class: "tl1" }, h("span", { class: "tid" }, t.id), h("b", null, t.title)),
        last ? h("div", { class: "tnote" }, String(last.text || "").replace(/\[([^\]]+)\]\((mail|task):[^)]+\)/g, "$1").replace(/\s+/g, " ")) : (t.desc ? h("div", { class: "tnote" }, t.desc.replace(/\s+/g, " ")) : null),
        h("div", { class: "tmeta" },
          h("span", { class: "tchip" + (t.owner ? "" : " none") }, icon("user", "xs"), t.owner || T("Unassigned", "Atanmadı")),
          t.due ? h("span", { class: "tchip" + (t.overdue ? " od" : t.soon ? " soon" : "") }, icon(t.overdue ? "triangle-alert" : "calendar", "xs"), dueText(t.due)) : null,
          notes.length ? h("span", { class: "tchip none" }, icon("message-square", "xs"), String(notes.length)) : null,
          t.source && t.source.mailId ? h("span", { class: "tchip none" }, icon("mail", "xs")) : null),
        t.updated ? h("div", { class: "tupd" }, T("Updated ", "Güncellendi ") + shortWhen(t.updated) + (t.lastBy === "yaver" || (!t.lastBy && t.by === "yaver") ? T(" by yaver", " · yaver") : "")) : null),
      manual ? h("div", { class: "tmove" },
        h("button", { class: "ib sm", "aria-label": T("Move up", "Yukarı taşı"), disabled: i === 0 || null, onclick: function () { moveTask(t.id, i - 1); } }, icon("move-up", "xs")),
        h("button", { class: "ib sm", "aria-label": T("Move down", "Aşağı taşı"), disabled: i === all.length - 1 || null, onclick: function () { moveTask(t.id, i + 1); } }, icon("move-down", "xs"))) : null);
  }
  function moveTask(id, index) { call("tasks.move", taskArgs({ id: id, index: index })).then(renderTasks).catch(function (e) { toast(e.message); }); }
  function updTask(id, patch) {
    var a = { id: id }; for (var k2 in patch) a[k2] = patch[k2];
    return call("tasks.update", a).then(function (t) { loadTasks(); refreshOverdue(); return t; }).catch(function (e) { toast(e.message); });
  }
  function askTasks() {
    editSheet({ title: T("Ask yaver about your tasks", "Görevleriniz hakkında yaver'e sorun"), multi: true, placeholder: T("e.g. What is overdue and who should I chase?", "ör. Neler gecikti, kimi takip etmeliyim?"), ok: T("Ask", "Sor"),
      save: function (v) { if (!v.trim()) return; closeAllPages(); newChat(); send(v.trim() + "\n\n" + T("(Answer from my task board with the task_board tool.)", "(Görev panomdaki bilgilerle, task_board aracını kullanarak yanıtla.)")); } });
  }
  function newTaskSheet(prefill) {
    prefill = prefill || {};
    var ti = h("input", { type: "text", placeholder: T("What needs doing", "Ne yapılacak") }); ti.value = prefill.title || "";
    var ow = h("input", { type: "text", list: "tpeople", placeholder: T("optional", "isteğe bağlı") }); ow.value = prefill.owner || S.tasks.owner || "";
    var du = h("input", { type: "date" });
    var de = h("textarea", { rows: 3, class: "sm", placeholder: T("Details (optional)", "Ayrıntılar (isteğe bağlı)") });
    sheet({ title: T("New task", "Yeni görev"), body: h("div", null, peopleList(), h("label", { class: "field" }, h("span", null, T("Title", "Başlık")), ti), h("div", { class: "f2" }, h("label", { class: "field" }, h("span", null, T("Owner", "Sorumlu")), ow), h("label", { class: "field" }, h("span", null, T("Due", "Son tarih")), du)), h("label", { class: "field" }, h("span", null, T("Description", "Açıklama")), de)),
      actions: [[T("Cancel", "İptal")], [T("Add task", "Görevi ekle"), "p", function () {
        if (!ti.value.trim()) { toast(T("Give it a title", "Bir başlık verin")); return false; }
        call("tasks.add", { title: ti.value.trim(), owner: ow.value.trim(), due: du.value, desc: de.value.trim(), status: "todo" }).then(function (t) { toast(T("Task added", "Görev eklendi") + (t && t.id ? " · " + t.id : "")); loadTasks(); refreshOverdue(); }).catch(function (e) { toast(e.message); });
      }]] });
    setTimeout(function () { ti.focus(); }, 260);
  }
  function peopleList() { var dl = h("datalist", { id: "tpeople" }); ((S.tasks.data && S.tasks.data.people) || []).forEach(function (p) { dl.appendChild(h("option", { value: p.name })); }); return dl; }
  function taskSheet(id, cached) {
    var p = cached ? Promise.resolve(cached) : call("tasks.get", { id: id });
    p.then(function (t) {
      if (!t) return;
      var ti = h("input", { type: "text" }); ti.value = t.title || "";
      var ow = h("input", { type: "text", list: "tpeople", placeholder: T("Unassigned", "Atanmadı") }); ow.value = t.owner || "";
      var du = h("input", { type: "date" }); du.value = t.due || "";
      var de = h("textarea", { rows: 3, class: "sm", placeholder: T("Optional", "İsteğe bağlı") }); de.value = t.desc || "";
      var status = t.status;
      var seg = h("div", { class: "seg in" }, TSTAT.map(function (x) {
        return h("button", { class: x[0] === status ? "on" : "", onclick: function () { status = x[0]; seg.querySelectorAll("button").forEach(function (b, j) { b.classList.toggle("on", TSTAT[j][0] === status); }); } }, icon(x[3], "xs"), T(x[1], x[2]));
      }));
      var notesBox = h("div", { class: "tnotes" });
      function drawNotes(tt) {
        clear(notesBox);
        var ns = (tt.notes || []).slice().sort(function (a, b) { return (b.at || 0) - (a.at || 0); });
        if (!ns.length) notesBox.appendChild(h("div", { class: "kmeta" }, T("No notes yet.", "Henüz not yok.")));
        ns.forEach(function (n) {
          notesBox.appendChild(h("button", { class: "tn", onclick: function () {
            editSheet({ title: T("Edit note", "Notu düzenle"), multi: true, value: n.text, save: function (v) { call("tasks.note.edit", { id: t.id, noteId: n.id, text: v }).then(function (x) { drawNotes(x); loadTasks(); }).catch(function (e) { toast(e.message); }); },
              extra: [[T("Delete", "Sil"), "", function () { call("tasks.note.delete", { id: t.id, noteId: n.id }).then(function (x) { drawNotes(x); loadTasks(); }).catch(function (e) { toast(e.message); }); }]] });
          } }, h("div", { class: "tnh" }, h("b", null, n.by === "yaver" ? "yaver" : n.by === "user" ? T("You", "Siz") : n.by), h("span", null, shortWhen(n.at) + (n.edited ? T(" · edited", " · düzenlendi") : ""))), md(n.text)));
        });
      }
      drawNotes(t);
      var ni = h("input", { type: "text", placeholder: T("Add a note…", "Not ekle…") });
      function addNote() { var v = ni.value.trim(); if (!v) return; ni.value = ""; call("tasks.note.add", { id: t.id, text: v }).then(function (x) { drawNotes(x); loadTasks(); }).catch(function (e) { toast(e.message); }); }
      ni.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); addNote(); } });
      var sh = sheet({ title: t.id + (t.overdue ? " · " + T("overdue", "gecikmiş") : ""), body: h("div", null, peopleList(),
          h("label", { class: "field" }, h("span", null, T("Title", "Başlık")), ti), seg,
          h("div", { class: "f2" }, h("label", { class: "field" }, h("span", null, T("Owner", "Sorumlu")), ow), h("label", { class: "field" + (t.overdue ? " od" : "") }, h("span", null, T("Due", "Son tarih")), du)),
          h("label", { class: "field" }, h("span", null, T("Description", "Açıklama")), de),
          t.source && t.source.mailId ? h("button", { class: "fileb", onclick: function () { sh.close(); openMail(t.source.mailId); } }, icon("mail", "xs"), h("span", null, t.source.subject || T("Source e-mail", "Kaynak e-posta"))) : null,
          h("h4", { class: "sublbl" }, T("Notes", "Notlar")),
          h("div", { class: "arow" }, ni, h("button", { class: "send sm", "aria-label": T("Add note", "Not ekle"), onclick: addNote }, icon("plus", "s"))),
          notesBox,
          h("div", { class: "tsfoot" },
            h("button", { class: "btn sm ghost", onclick: function () { sh.close(); closeAllPages(); newChat(); send(T("Help me with task " + t.id + ": " + t.title + ". (Use the task_board tool.)", t.id + " numaralı görevde bana yardım et: " + t.title + ". (task_board aracını kullan.)")); } }, icon("sparkles", "xs"), T("Ask yaver", "yaver'e sor")),
            h("button", { class: "btn sm ghost danger-t", onclick: function () { sheet({ title: T("Delete " + t.id + "?", t.id + " silinsin mi?"), text: t.title, actions: [[T("Cancel", "İptal")], [T("Delete", "Sil"), "danger", function () { sh.close(); call("tasks.delete", taskArgs({ id: t.id })).then(function (st) { renderTasks(st); refreshOverdue(); toast(T("Task deleted", "Görev silindi")); }).catch(function (e) { toast(e.message); }); }]] }); } }, icon("trash-2", "xs"), T("Delete", "Sil")))),
        actions: [[T("Close", "Kapat")], [T("Save", "Kaydet"), "p", function () {
          if (!ti.value.trim()) { toast(T("Give it a title", "Bir başlık verin")); return false; }
          updTask(t.id, { title: ti.value.trim(), owner: ow.value.trim(), due: du.value, desc: de.value, status: status }).then(function () { toast(T("Saved", "Kaydedildi")); });
        }]] });
    }).catch(function (e) { toast(e.message); });
  }
  function peopleSheet() {
    var st = S.tasks.data || {};
    var inp = h("input", { type: "text", placeholder: T("Name", "Ad") });
    var s = sheet({ title: T("People", "Kişiler"), text: T("Who tasks can be assigned to. Tap a name to see their tasks.", "Görev atanabilecek kişiler. Görevlerini görmek için bir ada dokunun."),
      body: h("div", null,
        h("div", { class: "arow" }, inp, h("button", { class: "send sm", "aria-label": T("Add", "Ekle"), onclick: function () { var v = inp.value.trim(); if (!v) return; s.close(); call("tasks.people.add", taskArgs({ name: v })).then(function (x) { renderTasks(x); peopleSheet(); }).catch(function (e) { toast(e.message); }); } }, icon("plus", "s"))),
        (st.people || []).length ? (st.people || []).map(function (p) {
          return h("div", { class: "opt" }, h("button", { class: "pp", onclick: function () { s.close(); S.tasks.owner = p.name; loadTasks(); } }, avatar(p.name), h("div", null, p.name, h("small", null, T(p.open + " open", p.open + " açık")))),
            h("button", { class: "ib sm", "aria-label": T("Remove", "Kaldır"), onclick: function () { s.close(); call("tasks.people.remove", taskArgs({ name: p.name })).then(function (x) { renderTasks(x); peopleSheet(); }).catch(function (e) { toast(e.message); }); } }, icon("x", "xs")));
        }) : h("p", { class: "kmeta" }, T("No people yet.", "Henüz kişi yok."))) });
  }
  /* Outlook Tasks backup chip + question card */
  function loadOsync() {
    var p = tasksPage(); if (!p) return;
    call("osync.state").then(function (o) { renderOsync(o); }).catch(function () { var b = $("#osync", p.body); if (b) clear(b); });
  }
  function renderOsync(o) {
    var p = tasksPage(); if (!p || !o) return;
    var box = clear($("#osync", p.body));
    var dot = { ok: "ok", busy: "warn", warn: "warn", err: "bad", idle: "", off: "" }[o.kind] || "";
    box.appendChild(h("div", { class: "toolbar" }, h("button", { class: "pill osc", onclick: function () { osyncMenu(o); } }, o.kind === "busy" ? h("span", { class: "spin xs" }) : h("span", { class: "dot " + dot }), icon(o.kind === "off" ? "cloud-off" : "cloud", "xs"), osyncText(o))));
    var c = o.card;
    if (c) {
      var opts = { tasks: true, memory: true, skills: true };
      var acts = { restore: [["restore", T("Restore", "Geri yükle"), "p"], ["later", T("Later", "Sonra"), ""], ["nothing", T("Keep this phone's copy", "Bu telefondakini koru"), "ghost"]], import: [["import", T("Import", "İçe al"), "p"], ["later", T("Later", "Sonra"), ""]], enable: [["on", T("Turn on", "Aç"), "p"], ["later", T("Later", "Sonra"), ""], ["never", T("Never", "Asla"), "ghost"]] }[c.kind] || [["later", T("Close", "Kapat"), ""]];
      box.appendChild(h("div", { class: "warncard " + (c.tone || "") }, icon(c.kind === "enable" ? "cloud" : "archive-restore", "s"),
        h("div", null, h("b", null, c.title), h("span", null, c.text),
          c.kind === "restore" ? h("div", { class: "fchips" }, [["tasks", T("Tasks", "Görevler")], ["memory", T("Memory", "Hafıza")], ["skills", T("Skills", "Yetenekler")]].map(function (x) {
            var b = h("button", { class: "chip sm on", onclick: function () { opts[x[0]] = !opts[x[0]]; b.classList.toggle("on", opts[x[0]]); } }, x[1]); return b;
          })) : null,
          h("div", { class: "btnrow wrapr" }, acts.map(function (a) { return h("button", { class: "btn sm " + a[2], onclick: function () { call("osync.answer", { kind: c.kind, action: a[0], options: opts }).then(function (x) { renderOsync(x); loadTasks(); }).catch(function (e) { toast(e.message); }); } }, a[1]); })))));
    }
  }
  function osyncText(o) {
    if (S.lang !== "tr") return o.text || "Outlook backup";
    return ({ off: "Outlook yedeği kapalı", warn: "Outlook: geri yükleme bekliyor", busy: "Outlook: eşitleniyor…", err: "Outlook yedeği hatası" }[o.kind]) ||
      (o.kind === "idle" ? (o.mode === "on" ? "Outlook yedeği: postayı bağlayın" : "Outlook yedeği") : o.kind === "ok" ? (o.lastSync ? "Outlook'a yedeklendi · " + shortWhen(o.lastSync) : "Outlook yedeği açık") : o.text);
  }
  function osyncMenu(o) {
    var s = sheet({ title: T("Outlook Tasks backup", "Outlook Görevler yedeği"), text: T("Keeps a copy of your board, memory and skills in a “yaver” folder under Outlook Tasks, so nothing is lost with the phone.", "Panonuzun, hafızanızın ve yeteneklerinizin bir kopyasını Outlook Görevler altındaki “yaver” klasöründe tutar; telefonla birlikte hiçbir şey kaybolmaz."),
      body: h("div", null,
        o.error ? h("div", { class: "err" }, icon("triangle-alert", "s"), h("div", null, o.error)) : null,
        o.message ? h("p", { class: "kmeta" }, o.message) : null,
        [["on", T("Keep a copy in Outlook", "Outlook'ta kopya tut")], ["ask", T("Ask me", "Bana sor")], ["off", T("Off", "Kapalı")]].map(function (m) {
          return h("button", { class: "opt", onclick: function () { s.close(); call("osync.mode", { mode: m[0] }).then(renderOsync).catch(function (e) { toast(e.message); }); } }, icon(m[0] === "off" ? "cloud-off" : "cloud", "s"), m[1], o.mode === m[0] ? icon("check", "s c") : null);
        }),
        h("button", { class: "opt", onclick: function () { s.close(); toast(T("Backing up…", "Yedekleniyor…")); call("osync.push").then(renderOsync).catch(function (e) { toast(e.message); }); } }, icon("cloud", "s"), h("div", null, T("Back up now", "Şimdi yedekle"), o.tasksInOutlook ? h("small", null, T(o.tasksInOutlook + " tasks in Outlook", "Outlook'ta " + o.tasksInOutlook + " görev")) : null)),
        h("button", { class: "opt", onclick: function () { s.close(); call("osync.check").then(function (x) { renderOsync(x); if (!x.found) toast(T("Nothing new in Outlook", "Outlook'ta yeni bir şey yok")); }).catch(function (e) { toast(e.message); }); } }, icon("search", "s"), T("Check Outlook", "Outlook'u kontrol et")),
        h("button", { class: "opt", onclick: function () { s.close(); call("osync.restoreOffer").then(function (x) { renderOsync(x); if (x.none) toast(T("No backup found in Outlook", "Outlook'ta yedek bulunamadı")); }).catch(function (e) { toast(e.message); }); } }, icon("archive-restore", "s"), T("Restore from Outlook…", "Outlook'tan geri yükle…"))) });
  }

  /* ------------------------------------------------------------ settings: learning, context, System-1, folders, mail assistant */
  function nz(p) { return p.then(function (x) { return x; }, function () { return null; }); }
  function loadExtras() {
    return Promise.all([nz(call("learn.state")), nz(call("ctx.stats", { convoId: S.convoId && S.convoId !== "_new" ? S.convoId : "" })), nz(call("s1.state")), nz(call("folders.state")), nz(call("mailx.settings")), nz(call("osync.state"))]).then(function (r) {
      S.x = { learn: r[0], ctx: r[1], s1: r[2], mailx: r[4], osync: r[5] };
      if (r[3]) S.folders = r[3];
      renderExtras();
    });
  }
  function setX(method, args, after) {
    return call(method, args).then(function (r) { if (after) after(r); return loadExtras(); }).catch(function (e) { toast(e.message); });
  }
  function renderExtras() {
    var p = S.pages.filter(function (x) { return x.name === "settings"; })[0]; if (!p) return;
    var box = $("#s-extras", p.body), fbox = $("#s-folders", p.body), mbox = $("#s-mailx", p.body);
    var X = S.x || {};
    if (box) {
      clear(box);
      var Lr = X.learn || {}, learnOn = Lr.learn !== false, every = Lr.every || 6, fu = Lr.followups != null ? Lr.followups : (S.state.followups != null ? S.state.followups : true);
      var lg = group(T("Learning", "Öğrenme"), [
        row({ icon: "sparkles", label: T("Learn from experience", "Deneyimden öğren"), sub: T("Lessons from your corrections, 👍/👎 and reviews", "Düzeltmelerinizden, 👍/👎 ve gözden geçirmelerden dersler"), toggle: learnOn, onToggle: function (on) { setX("learn.set", { learn: on, every: every }); } }),
        learnOn ? row({ icon: "repeat", label: T("Review the conversation every", "Sohbeti gözden geçir"), value: T(every + " turns", "her " + every + " turda"), onclick: function () { optionsSheet(T("Review every", "Gözden geçirme sıklığı"), [3, 6, 10, 15, 20].map(function (n) { return [String(n), T(n + " turns", n + " turda bir")]; }), String(every), function (v) { setX("learn.set", { learn: true, every: +v }); }); } }) : null,
        row({ icon: "lightbulb", label: T("Next-step suggestions", "Sonraki adım önerileri"), sub: T("After each answer, 4 likely follow-ups (one small call to the fast model)", "Her yanıttan sonra 4 olası devam (hızlı modele küçük bir çağrı)"), wrap: true, toggle: fu, onToggle: function (on) { setX("followups.set", { on: on }, function () { S.state.followups = on; }); } }),
        row({ icon: "brain", label: T("Memory and lessons", "Hafıza ve dersler"), sub: T("What yaver knows and learned", "yaver'in bildikleri ve öğrendikleri"), onclick: function () { openKnowledge(); } })
      ]);
      lg.id = "s-learn"; box.appendChild(lg);

      var C = X.ctx || {}, autoC = C.autoCompact !== false, at = C.compactAt || 90, keep = C.keepTurns || 3, win = C.windowSource === "set" ? C.window || 0 : 0;
      box.appendChild(group(T("Context", "Bağlam"), [
        row({ icon: "minimize-2", label: T("Compact automatically", "Otomatik sıkıştır"), sub: T("Summarize older messages when the chat gets long", "Sohbet uzayınca eski mesajları özetle"), toggle: autoC, onToggle: function (on) { setX("ctx.set", { key: "autoCompact", value: on }); } }),
        row({ icon: "gauge", label: T("Compact at", "Sıkıştırma eşiği"), value: "%" + at, onclick: function () { optionsSheet(T("Compact when the context is", "Bağlam şu orana gelince sıkıştır"), [60, 70, 80, 85, 90, 95].map(function (n) { return [String(n), "%" + n]; }), String(at), function (v) { setX("ctx.set", { key: "compactAt", value: +v }); }); } }),
        row({ icon: "messages-square", label: T("Turns kept word for word", "Aynen korunan turlar"), value: String(keep), onclick: function () { optionsSheet(T("Recent turns kept word for word", "Aynen korunan son turlar"), [1, 2, 3, 4, 5, 8, 12].map(function (n) { return [String(n), String(n)]; }), String(keep), function (v) { setX("ctx.set", { key: "keepTurns", value: +v }); }); } }),
        row({ icon: "layers", label: T("Model context window", "Model bağlam penceresi"), sub: win ? T("Set by you", "Sizin ayarınız") : T("Automatic: from the service or learned", "Otomatik: hizmetten veya öğrenilir"), value: win ? k(win) : (C.window ? T("auto · ", "otomatik · ") + k(C.window) : T("auto", "otomatik")), onclick: function () { editSheet({ title: T("Model context window", "Model bağlam penceresi"), type: "number", inputmode: "numeric", value: win ? String(win) : "", placeholder: T("auto", "otomatik"), help: T("Tokens. Leave empty for automatic — set it only if compaction starts too late.", "Token. Otomatik için boş bırakın — yalnızca sıkıştırma geç başlıyorsa ayarlayın."), save: function (v) { var n = Math.max(0, parseInt(String(v).replace(/[^0-9]/g, ""), 10) || 0); setX("ctx.set", { key: "window", value: n }, function () { refreshState().then(function () { renderSettings(); toast(n ? T("Context window: " + k(n), "Bağlam penceresi: " + k(n)) : T("Context window: automatic", "Bağlam penceresi: otomatik")); }); }); } }); } }),
        row({ icon: "gauge", label: T("Context budget", "Bağlam bütçesi"), sub: T("Most tokens sent per request; the meter in the chat counts against this", "İstek başına gönderilen en fazla token; sohbetteki gösterge buna göre sayar"), wrap: true, value: k((S.state.llm || {}).contextTokens || 48000), onclick: function () { optionsSheet(T("Context budget (tokens)", "Bağlam bütçesi (token)"), [16000, 32000, 48000, 64000, 96000, 128000, 200000].map(function (n) { return [String(n), k(n)]; }), String((S.state.llm || {}).contextTokens || 48000), function (v) { setCfg("contextTokens", Number(v)).then(function () { renderSettings(); }); }); } })
      ]));

      var s1 = X.s1 || {}, cfg = s1.cfg || s1, be = cfg.backend || "off";
      var backends = (cfg.backends && cfg.backends.length) ? cfg.backends.map(function (b) { return [b.id, b.label]; }) : [["off", T("None — the LLM decides everything", "Yok — her kararı LLM verir")], ["jev", "TypeSafe Jev"], ["laya", "Laya"], ["llm", T("Emulated with the LLM (testing)", "LLM ile taklit (test için)")]];
      var beName = { off: T("Off", "Kapalı"), jev: "Jev", laya: "Laya", llm: T("Emulated", "Taklit") }[be] || be;
      var remote = be === "jev" || be === "laya";
      box.appendChild(group("System-1", [
        row({ icon: "cpu", label: T("Decision model", "Karar modeli"), sub: T("Fast model for routine decisions in the loop", "Döngüdeki rutin kararlar için hızlı model"), value: beName, onclick: function () { optionsSheet("System-1", backends, be, function (v) { setX("s1.set", { backend: v }); }); } }),
        remote ? row({ icon: "globe", label: T("Address", "Adres"), sub: cfg.url || "", onclick: function () { editSheet({ title: T("System-1 address", "System-1 adresi"), type: "url", value: cfg.url, save: function (v) { setX("s1.set", { url: v.trim() }); } }); } }) : null,
        remote ? row({ icon: "key-round", label: T("API key", "API anahtarı"), value: masked(cfg.keySet), onclick: function () { editSheet({ title: "System-1 API", secret: true, help: T("Stored encrypted on this phone.", "Bu telefonda şifreli saklanır."), save: function (v) { setX("s1.set", { key: v.trim() }); } }); } }) : null,
        remote ? row({ icon: "cpu", label: T("Model", "Model"), value: cfg.model || "", onclick: function () { editSheet({ title: T("System-1 model", "System-1 modeli"), value: cfg.model, save: function (v) { setX("s1.set", { model: v.trim() }); } }); } }) : null,
        be !== "off" ? row({ icon: "gauge", label: T("Confidence needed", "Gereken güven"), sub: T("Below this, the decision goes to the LLM or you", "Altında karar LLM'e veya size gider"), value: String(cfg.threshold || 0.7), onclick: function () { optionsSheet(T("Confidence threshold", "Güven eşiği"), ["0.5", "0.6", "0.7", "0.8", "0.9"].map(function (n) { return [n, n]; }), String(cfg.threshold || 0.7), function (v) { setX("s1.set", { threshold: +v }); }); } }) : null,
        row({ icon: "sliders-horizontal", label: T("Decision points", "Karar noktaları"), sub: T("Which gates System-1 handles", "System-1'in üstlendiği kararlar"), onclick: gatesSheet }),
        row({ icon: "activity", label: T("Decision log", "Karar günlüğü"), sub: T("Recent System-1 decisions", "Son System-1 kararları"), onclick: gatesLogSheet })
      ], be === "off" ? T("Off: the language model makes every decision. A System-1 model takes routine yes/no and choice decisions off the LLM.", "Kapalı: her kararı dil modeli verir. Bir System-1 modeli rutin evet/hayır ve seçim kararlarını LLM'den alır.") : null));
    }
    if (fbox) {
      clear(fbox);
      var F = S.folders || [];
      F.forEach(function (f) {
        var pd = f.pendingDeletes; if (!pd || !pd.n) return;
        fbox.appendChild(h("div", { class: "warncard bad" }, icon("triangle-alert", "s"), h("div", null,
          h("b", null, T(pd.n + " files look deleted in “" + f.name + "”", "“" + f.name + "” içinde " + pd.n + " dosya silinmiş görünüyor")),
          h("span", null, T("yaver paused the sync so nothing is lost. Delete them on both sides, or bring them back?", "Hiçbir şey kaybolmasın diye yaver eşitlemeyi durdurdu. Her iki tarafta silinsin mi, yoksa geri mi getirilsin?")),
          (pd.here || []).concat(pd.there || []).length ? h("div", { class: "res", style: "display:block;white-space:pre-wrap;font-size:12px;margin:8px 0 0;max-height:100px;overflow:auto;padding:8px 10px;border-radius:8px;background:var(--surface)" }, (pd.here || []).concat(pd.there || []).join("\n")) : null,
          h("div", { class: "btnrow" }, h("button", { class: "btn sm", onclick: function () { setX("folders.deletes", { name: f.name, delete: false }, function (m) { if (m) toast(m); }); } }, icon("undo-2", "xs"), T("Bring back", "Geri getir")), h("button", { class: "btn sm danger", onclick: function () { setX("folders.deletes", { name: f.name, delete: true }, function (m) { if (m) toast(m); }); } }, T("Delete", "Sil"))))));
      });
      fbox.appendChild(group(T("Connected folders", "Bağlı klasörler"), F.map(function (f) {
        var sub = f.busy ? T("Syncing…", "Eşitleniyor…") : f.lastErr ? f.lastErr : f.lastSync ? T("Synced ", "Eşitlendi ") + shortWhen(f.lastSync) : T("Not synced yet", "Henüz eşitlenmedi");
        return row({ icon: "folder-sync", label: f.name, sub: sub, check: f.lastErr || (f.pendingDeletes && f.pendingDeletes.n) ? false : (f.lastSync ? true : null), onclick: function () { folderSheet(f); } });
      }).concat([row({ icon: "plus", label: T("Connect a folder", "Klasör bağla"), sub: T("Pick a phone folder to sync with yaver's files", "yaver'in dosyalarıyla eşitlenecek bir telefon klasörü seçin"), onclick: function () {
        call("folder.connect").then(function (m) { if (m) toast(typeof m === "string" ? m : T("Folder connected", "Klasör bağlandı")); loadExtras(); }).catch(function (e) { toast(e.message); });
      } })]), T("Two-way sync: files you change on the phone appear in yaver's workspace and the other way round.", "İki yönlü eşitleme: telefonda değiştirdiğiniz dosyalar yaver'in çalışma alanında görünür, tersi de geçerli.")));
    }
    if (mbox) {
      clear(mbox);
      var MX = X.mailx || {}, O = X.osync || {}, mode = O.mode || MX.outlookSync || "ask";
      var brief = MX.briefAt || "";
      function mset(key, value) { setX("mailx.settings.set", { key: key, value: value }); }
      mbox.appendChild(group(T("Mail assistant", "Posta asistanı"), [
        row({ icon: "inbox", label: T("Sort new mail", "Yeni postayı ayıkla"), sub: T("Short summary and a “needs reply” flag for each new e-mail", "Her yeni e-posta için kısa özet ve “yanıt bekliyor” işareti"), wrap: true, toggle: MX.triage !== false, onToggle: function (on) { mset("triage", on); } }),
        row({ icon: "image", label: T("Look at pictures in mail", "Postadaki resimlere bak"), sub: T("Uses the vision model", "Görsel modeli kullanır"), toggle: MX.vision !== false, onToggle: function (on) { mset("vision", on); } }),
        row({ icon: "sun", label: T("Daily plan", "Günlük plan"), sub: T("Once a day, after this time", "Günde bir kez, bu saatten sonra"), value: brief || T("Off", "Kapalı"), onclick: function () {
          optionsSheet(T("Daily plan", "Günlük plan"), [["", T("Off", "Kapalı")], ["07:30", "07:30"], ["08:00", "08:00"], ["08:30", "08:30"], ["09:00", "09:00"], ["custom", T("Other time…", "Başka saat…")]], brief, function (v) {
            if (v === "custom") editSheet({ title: T("Daily plan time", "Günlük plan saati"), type: "time", value: brief || "08:00", save: function (x) { mset("briefAt", x); } });
            else mset("briefAt", v);
          });
        } }),
        row({ icon: "cloud", label: T("Outlook Tasks backup", "Outlook Görevler yedeği"), sub: osyncText(O.kind ? O : { kind: mode === "off" ? "off" : "idle", mode: mode }), value: { on: T("On", "Açık"), ask: T("Ask", "Sor"), off: T("Off", "Kapalı") }[mode], onclick: function () {
          optionsSheet(T("Outlook Tasks backup", "Outlook Görevler yedeği"), [["on", T("Keep a copy in Outlook", "Outlook'ta kopya tut")], ["ask", T("Ask me", "Bana sor")], ["off", T("Off", "Kapalı")]], mode, function (v) { setX("osync.mode", { mode: v }); });
        } }),
        row({ icon: "shield-check", label: T("Confirm work changes", "İş değişikliklerini onaylat"), sub: T("Approval cards for task, calendar and mail changes", "Görev, takvim ve posta değişiklikleri için onay kartı"), wrap: true, toggle: MX.workConfirm !== false, onToggle: function (on) { mset("workConfirm", on); } }),
        row({ icon: "search", label: T("Mail search index", "Posta arama dizini"), value: T((MX.indexMax || 1500) + " per folder", "klasör başına " + (MX.indexMax || 1500)), onclick: function () { optionsSheet(T("Mails kept in the search index", "Arama dizininde tutulan posta"), ["500", "1500", "3000", "5000"].map(function (n) { return [n, T(n + " per folder", "klasör başına " + n)]; }), String(MX.indexMax || 1500), function (v) { mset("indexMax", +v); }); } })
      ]));
    }
    if (S.settingsFocus === "learn") { S.settingsFocus = null; setTimeout(function () { var el = document.getElementById("s-learn"); if (el) el.scrollIntoView(); }, 50); }
  }
  function folderSheet(f) {
    var st = f.stats || {};
    sheet({ title: f.name, body: h("div", null, h("dl", { class: "dl" },
        h("dt", null, T("In yaver", "yaver'de")), h("dd", null, f.path || ""),
        h("dt", null, T("Last sync", "Son eşitleme")), h("dd", null, f.lastSync ? longWhen(f.lastSync) : "—"),
        f.stats ? [h("dt", null, T("Last run", "Son çalışma")), h("dd", null, T("↓ " + (st.fromDisk || 0) + " in · ↑ " + (st.toDisk || 0) + " out · " + (st.deleted || 0) + " deleted · " + (st.conflicts || 0) + " conflicts", "↓ " + (st.fromDisk || 0) + " gelen · ↑ " + (st.toDisk || 0) + " giden · " + (st.deleted || 0) + " silinen · " + (st.conflicts || 0) + " çakışma"))] : null,
        f.lastErr ? [h("dt", null, T("Error", "Hata")), h("dd", { style: "color:var(--bad)" }, f.lastErr)] : null,
        (f.conflicts || []).length ? [h("dt", null, T("Conflicts", "Çakışmalar")), h("dd", null, f.conflicts.join("\n"))] : null)),
      actions: [[T("Disconnect", "Bağlantıyı kes"), "", function () { sheet({ title: T("Disconnect “" + f.name + "”?", "“" + f.name + "” bağlantısı kesilsin mi?"), text: T("Files stay where they are on both sides.", "Dosyalar iki tarafta da yerinde kalır."), actions: [[T("Cancel", "İptal")], [T("Disconnect", "Bağlantıyı kes"), "danger", function () { setX("folders.remove", { name: f.name }); }]] }); }],
        [T("Sync now", "Şimdi eşitle"), "p", function () { toast(T("Syncing…", "Eşitleniyor…")); setX("folders.sync", { name: f.name }, function (m) { if (typeof m === "string" && m) toast(m); }); }]] });
  }
  function gatesSheet() {
    call("gates.log", { limit: 1 }).then(function (r) {
      var gates = (r && r.gates) || [];
      var body = h("div", null, gates.map(function (g) {
        var stt = g.stats || {};
        var b = h("div", { class: "opt", role: "switch", "aria-checked": String(!!g.on) },
          h("div", { style: "flex:1;min-width:0" }, g.label, h("small", null, (g.active ? g.how : T("LLM: ", "LLM: ") + g.replaces) + (stt.calls ? T(" · " + (stt.confident || 0) + " settled, " + (stt.escalated || 0) + " escalated", " · " + (stt.confident || 0) + " karar, " + (stt.escalated || 0) + " devredildi") : ""))),
          h("div", { class: "sw" + (g.on ? " on" : "") }));
        b.addEventListener("click", function () { g.on = !g.on; b.lastChild.classList.toggle("on", g.on); var o = {}; o[g.key] = g.on; call("s1.set", { gates: o }).catch(function (e) { toast(e.message); }); });
        return b;
      }));
      if (!gates.length) body.appendChild(h("p", { class: "kmeta" }, T("No decision points reported.", "Karar noktası bildirilmedi.")));
      sheet({ title: T("Decision points", "Karar noktaları"), text: T("With a System-1 model set, each switched-on gate is decided by it; the rest stay with the LLM.", "Bir System-1 modeli seçiliyken açık olan her kararı o verir; diğerleri LLM'de kalır."), body: body, actions: [[T("Done", "Tamam"), "p"]] });
    }).catch(function (e) { toast(e.message); });
  }
  function gatesLogSheet() {
    var box = h("div", null, loading());
    sheet({ title: T("Decision log", "Karar günlüğü"), body: box, actions: [[T("Close", "Kapat")]] });
    call("gates.log", { limit: 60 }).then(function (r) {
      clear(box);
      var st = (r && r.stats) || {}, log = (r && r.log) || [], labels = {};
      ((r && r.gates) || []).forEach(function (g) { labels[g.key] = g.label; });
      box.appendChild(h("div", { class: "kstats" },
        kstat(T("System-1 calls", "System-1 çağrısı"), String(st.s1Calls || 0), ""), kstat(T("Settled by System-1", "System-1 karar verdi"), String(st.s1Confident || 0), ""),
        kstat(T("Escalated", "Devredildi"), String(st.s1Escalated || 0), ""), kstat(T("LLM calls saved", "Kazanılan LLM çağrısı"), String(st.auxReplaced || 0), "")));
      if (!log.length) { box.appendChild(h("div", { class: "kempty" }, icon("cpu"), T("No decisions yet. They appear here as yaver works.", "Henüz karar yok. yaver çalıştıkça burada görünür."))); return; }
      log.forEach(function (e) {
        var rows = [];
        Object.keys(e.answers || {}).forEach(function (q) {
          var a = e.answers[q];
          if (a.type === "choice") { var ps = Object.keys(a.probabilities || {}).map(function (o) { return [o, a.probabilities[o]]; }).sort(function (x, y) { return y[1] - x[1]; }).slice(0, 3); rows.push(h("div", { class: "qk" }, q)); ps.forEach(function (x) { rows.push(pbar(x[0], x[1])); }); }
          else if (a.type === "noul") rows.push(pbar(q, a.noul));
          else if (a.type === "score") rows.push(pbar(q + " /3", (a.score || 0) / 3));
        });
        box.appendChild(h("div", { class: "logent" }, h("div", { class: "hd" }, h("b", null, labels[e.gate] || e.gate), e.error ? h("span", { class: "bdg warn" }, T("error", "hata")) : null, h("span", null, (e.ms ? Math.round(e.ms) + " ms · " : "") + shortWhen(e.t))),
          e.error ? h("div", { class: "kfacts", style: "color:var(--bad)" }, e.error) : rows));
      });
    }).catch(function (e) { clear(box); errorView(box, e); });
  }
  function pbar(t, p) { p = +p || 0; return h("div", { class: "pbar" }, h("span", { class: "t" }, t), h("span", { class: "b" }, h("i", { style: "width:" + Math.max(2, p * 100).toFixed(0) + "%" })), h("span", { class: "v" }, p.toFixed(2))); }

  /* ------------------------------------------------------------ memory, schedule */
  function openSchedule() {
    pushPage("schedule", T("Reminders and tasks", "Hatırlatmalar ve görevler"), function (body) {
      function draw() {
        call("schedule.list").then(function (all) {
          clear(body);
          if (!all.length) { body.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("alarm-clock")), h("h3", null, T("Nothing scheduled", "Planlanmış bir şey yok")), h("p", null, T("Ask yaver, e.g. “every weekday at 8:30 summarize my unread mail” or “remind me at 4 pm to call Ayşe”.", "yaver'e söyleyin, ör. “hafta içi her gün 8:30'da okunmamış postalarımı özetle” ya da “16:00'da Ayşe'yi aramamı hatırlat”.")))); return; }
          body.appendChild(group("", all.sort(function (a, b) { return a.next - b.next; }).map(function (o) {
            var rep = o.kind === "task" ? (o.daily ? T("Every day at ", "Her gün ") + o.daily : T("Every " + o.every + " min", "Her " + o.every + " dakikada")) : T("Once", "Bir kez");
            return row({ icon: o.kind === "task" ? "refresh-cw" : "bell", label: o.title, sub: rep + " — " + T("next ", "sonraki ") + longWhen(o.next), onclick: function () {
              sheet({ title: o.title, body: h("dl", { class: "dl" }, h("dt", null, T("Next", "Sonraki")), h("dd", null, longWhen(o.next)), o.prompt ? [h("dt", null, T("yaver will", "yaver")), h("dd", null, o.prompt)] : null, o.text ? [h("dt", null, T("Note", "Not")), h("dd", null, o.text)] : null),
                actions: [[T("Close", "Kapat")], [T("Cancel it", "İptal et"), "danger", function () { call("schedule.cancel", { id: o.id }).then(draw); }]] });
            } });
          })));
        });
      }
      draw();
    });
  }

  /* ------------------------------------------------------------ first-run setup */
  function openSetup() { if (topPage() !== "setup") pushPage("setup", "", function () { renderSetup(); }); }
  function renderSetup() {
    var p = S.pages.filter(function (x) { return x.name === "setup"; })[0]; if (!p) return;
    refreshState().then(function (st) {
      var L = st.llm, body = clear(p.body);
      var svc = PRESETS.filter(function (x) { return x[0] === L.base; })[0];
      body.appendChild(h("div", { class: "setup" },
        h("div", { class: "mark big" }, "y"),
        h("h1", null, T("Let's set up yaver", "yaver'i kuralım")),
        h("p", { class: "lead" }, T("yaver thinks with a language model you choose. Three steps, once.", "yaver seçtiğiniz bir dil modeliyle düşünür. Üç adım, bir kez.")),
        h("div", { class: "stepper" },
          h("div", { class: "sstep done" }, h("div", { class: "num" }, "1"), h("div", { class: "sc" }, h("b", null, T("Service", "Hizmet")), h("span", null, svc ? T(svc[2], svc[3]) : L.base),
            h("div", { class: "chips" }, PRESETS.slice(0, 4).map(function (x) { return h("button", { class: "chip" + (x[0] === L.base ? " on" : ""), onclick: function () { setCfg("llmBase", x[0]).then(renderSetup); } }, x[1]); })))),
          h("div", { class: "sstep" + (L.hasKey ? " done" : "") }, h("div", { class: "num" }, "2"), h("div", { class: "sc" }, h("b", null, T("API key", "API anahtarı")),
            h("span", null, L.hasKey ? T("Saved on this phone, encrypted.", "Bu telefonda şifreli kayıtlı.") : (svc && svc[1] === "OpenRouter" ? T("Create one free at openrouter.ai/keys and paste it here.", "openrouter.ai/keys adresinde ücretsiz oluşturup buraya yapıştırın.") : T("Paste the key from your service.", "Hizmetinizden aldığınız anahtarı yapıştırın."))),
            h("div", { class: "chips" }, h("button", { class: "chip" + (L.hasKey ? "" : " on"), onclick: function () { editSheet({ title: T("API key", "API anahtarı"), secret: true, placeholder: "sk-or-…", save: function (v) { if (v.trim()) setCfg("llmKey", v.trim()).then(renderSetup); } }); } }, L.hasKey ? T("Change key", "Anahtarı değiştir") : T("Paste key", "Anahtarı yapıştır")),
              svc && svc[1] === "OpenRouter" ? h("button", { class: "chip", onclick: function () { call("open.link", { url: "https://openrouter.ai/keys" }); } }, T("Get a key", "Anahtar al")) : null))),
          h("div", { class: "sstep" + (L.model ? " done" : "") }, h("div", { class: "num" }, "3"), h("div", { class: "sc" }, h("b", null, T("Model", "Model")),
            h("span", null, L.model || T("Pick from the service's list. Models marked free cost nothing.", "Hizmetin listesinden seçin. “free” olanlar ücretsizdir.")),
            h("div", { class: "chips" }, h("button", { class: "chip" + (L.model ? "" : " on"), onclick: function () { pickModel("model"); } }, L.model ? T("Change model", "Modeli değiştir") : T("Choose model", "Model seç")))))),
        h("div", { style: "margin-top:26px;display:flex;flex-direction:column;gap:10px" },
          h("button", { class: "btn p", disabled: !L.ready, onclick: function () { popPage(); newChat(true); } }, T("Start using yaver", "yaver'i kullanmaya başla")),
          h("button", { class: "btn ghost", onclick: function () { popPage(); } }, T("Later", "Sonra")))));
    });
  }

  /* ------------------------------------------------------------ shell */
  function renderShell() {
    var app = clear($("#app"));
    var ta = h("textarea", { id: "input", rows: 1, placeholder: T("Ask yaver", "yaver'e sor"), "aria-label": T("Message", "Mesaj") });
    ta.addEventListener("input", function () { autosize(); syncSend(); updateHints(); });
    app.appendChild(h("div", { class: "bar" },
      h("button", { class: "ib", "aria-label": T("Chats", "Sohbetler"), onclick: openDrawer }, icon("menu")),
      h("div", { class: "title", id: "ctitle" }, "yaver"),
      h("button", { class: "ctxm hidden", id: "ctxm", onclick: ctxSheet }),
      h("button", { class: "ib", "aria-label": T("Mail", "Posta"), onclick: openMailPage }, icon("mail")),
      h("button", { class: "ib", "aria-label": T("New chat", "Yeni sohbet"), onclick: function () { newChat(true); } }, icon("square-pen")),
      h("button", { class: "ib", "aria-label": T("More", "Daha fazla"), onclick: chatMenu }, icon("ellipsis-vertical"))));
    app.appendChild(h("div", { class: "planbar hidden", id: "planbar" }));
    app.appendChild(h("div", { id: "scroll" }, h("div", { class: "thread", id: "thread" })));
    app.appendChild(h("div", { class: "composer" }, h("div", { class: "hints hidden", id: "hints", role: "listbox" }), h("div", { class: "cbox" },
      h("div", { class: "atts hidden", id: "atts" }),
      h("div", { class: "crow" },
        h("button", { class: "ib", "aria-label": T("Attach", "Ekle"), onclick: pick }, icon("paperclip")),
        ta,
        h("button", { class: "send", id: "send", onclick: function () {
          if (ta.value.trim() || S.atts.length) send();
          else if (S.running[S.convoId]) stop();
          else listen(true);
        } })))));
    // drawer
    var ds = h("input", { type: "search", placeholder: T("Search chats", "Sohbetlerde ara") });
    var dsT = 0; ds.addEventListener("input", function () { clearTimeout(dsT); dsT = setTimeout(function () { refreshConvos(ds.value.trim()); }, 250); });
    document.querySelectorAll(".drawer,.scrim").forEach(function (x) { x.remove(); });
    document.body.appendChild(h("div", { class: "scrim", onclick: closeDrawer }));
    document.body.appendChild(h("nav", { class: "drawer", "aria-label": T("Chats", "Sohbetler") },
      h("div", { class: "dhead" }, h("div", { class: "mark" }, "y"), h("div", { class: "name" }, "yaver")),
      h("button", { class: "newchat", onclick: function () { newChat(true); } }, icon("square-pen", "s"), T("New chat", "Yeni sohbet")),
      h("label", { class: "dsearch" }, icon("search", "xs"), ds),
      h("div", { class: "dlist" }),
      h("div", { class: "dnav" },
        h("button", { onclick: openMailPage }, icon("mail"), T("Mail", "Posta")),
        h("button", { onclick: function () { closeDrawer(); S.mail.tab = "cal"; openMailPage(); } }, icon("calendar"), T("Calendar", "Takvim")),
        h("button", { onclick: function () { closeDrawer(); openTasks(); } }, h("span", { class: "nb" }, icon("square-kanban"), h("i", { class: "badge hidden", id: "odbadge" })), T("Tasks", "Görevler")),
        h("button", { onclick: function () { closeDrawer(); openKnowledge(); } }, icon("brain"), T("Memory", "Hafıza")),
        h("button", { onclick: function () { openSettings(); } }, icon("settings"), T("Settings", "Ayarlar")))));
    renderAtts(); syncSend(); renderThread(); refreshOverdue();
  }

  /* ------------------------------------------------------------ start */
  function start() {
    call("ready").then(function (st) {
      S.state = st; S.lang = st.lang;
      document.documentElement.lang = S.lang;
      renderShell();
      if (st.current) loadConvo(st.current);
      refreshConvos();
      if (!st.llm.ready) openSetup();
    }).catch(function (e) { document.body.textContent = "yaver: " + e.message; });
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();
})();
