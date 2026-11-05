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
    side: {}, fu: {}, plan: {}, ctx: {}, subs: {}, doneAt: {}, queued: {}, fbAsk: null, todoOpen: false, overdue: 0, kb: { tab: "memory", q: "", filter: "all", sort: "use" }, tasks: { q: "", owner: "", status: "open", sort: "" } };
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
    /* "SIGNIN:" = the mail session ended: never show the prefix, and let the toast open the sign-in page */
    if (/^SIGNIN:/.test(String(msg))) { msg = String(msg).replace(/^SIGNIN:\s*/, "") || T("Sign in to your mail", "Postada oturum açın"); if (!onClick) onClick = function () { signInMail(); }; }
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
  /* directory photos (the original's Photos): cached per address, fetched two at a time */
  var PHOTO = {}, PQ = [], PRUN = 0;
  function paintPhoto(el, d) { el.style.backgroundImage = 'url("' + d + '")'; el.classList.add("photo"); el.textContent = ""; }
  function photoNext() {
    while (PRUN < 2 && PQ.length) {
      var job = PQ.shift(); if (PHOTO[job.e] !== undefined && PHOTO[job.e] !== null) { if (PHOTO[job.e]) job.els.forEach(function (el) { paintPhoto(el, PHOTO[job.e]); }); continue; }
      PRUN++;
      (function (job) {
        call("mail.photo", { email: job.e }).then(function (d) { if (d !== null && d !== undefined) PHOTO[job.e] = d; if (d) job.els.forEach(function (el) { if (el.isConnected) paintPhoto(el, d); }); })
          .catch(function () {}).then(function () { PRUN--; photoNext(); });
      })(job);
    }
  }
  function wantPhoto(el, email) {
    var e = String(email || "").trim().toLowerCase(); if (!/@/.test(e)) return;
    if (PHOTO[e]) { paintPhoto(el, PHOTO[e]); return; }
    if (PHOTO[e] === "") return;
    var j = PQ.filter(function (x) { return x.e === e; })[0]; if (j) j.els.push(el); else PQ.push({ e: e, els: [el] });
    photoNext();
  }
  function avatar(name, email) { var s = String(email || name || ""), n = 0; for (var i = 0; i < s.length; i++) n = (n * 31 + s.charCodeAt(i)) >>> 0; var el = h("div", { class: "av", style: "background-color:" + AV[n % AV.length] }, initials(name || email)); if (email) wantPhoto(el, email); return el; }
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
      if (/^(https?:|data:|blob:)/.test(src)) return;
      /* a picture on the disk: ![](/workspace/plots/a.png), ![](plots/a.png), ![](~/skills/x/a.png) */
      im.setAttribute("src", wsUrl(src));
      im.addEventListener("click", function (e) { e.stopPropagation(); previewFile(src); });
    });
    box.querySelectorAll("pre").forEach(function (p) {
      p.appendChild(h("button", { class: "cp", "aria-label": T("Copy", "Kopyala"), onclick: function (e) { e.stopPropagation(); call("copy", { text: p.innerText }); toast(T("Copied", "Kopyalandı")); } }, icon("copy", "xs")));
    });
    mailChips(box);
    filePaths(box);
    box.querySelectorAll("a[href]").forEach(function (a) { if (isFileHref(a.getAttribute("href")) && !a.classList.contains("mlink")) a.classList.add("flink"); });
    box.addEventListener("click", function (e) {
      var a = e.target.closest("a"); if (!a) return;
      e.preventDefault();
      var href = a.getAttribute("href") || "";
      if (/^#?mail:/.test(href)) openMail(decodeURIComponent(href.replace(/^#?mail:/, "")));
      else if (/^task:/.test(href)) taskSheet(href.slice(5));
      else if (isFileHref(href)) previewFile(href);   /* a file on the disk: [rapor](/workspace/rapor.docx), [x](outputs/x.md) */
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
    session_search: ["history", "Searched past chats", "Eski sohbetlerde arandı", "query"],
    write_document: ["file-type", "Made a document", "Belge oluşturuldu", "path"],
    media: ["file-audio", "Audio / video", "Ses / video", "action"],
    vision_analyze: ["image", "Looked at a picture", "Resme bakıldı", "path"]
  };
  var BROWSER_STEPS = { navigate: ["open", "aç"], snapshot: ["look", "incele"], click: ["click", "tıkla"], type: ["type", "yaz"], back: ["back", "geri"], find: ["find", "bul"], download: ["download", "indir"], scroll: ["scroll", "kaydır"], press: ["key", "tuş"], select: ["choose", "seç"], wait: ["wait", "bekle"], screenshot: ["screenshot", "ekran görüntüsü"], close: ["close", "kapat"] };
  var ACTIONS = { list: ["list", "listele"], search: ["search", "ara"], read: ["read", "oku"], get_attachment: ["attachment", "ek"], draft: ["draft", "taslak"], send: ["send", "gönder"], send_now: ["send", "gönder"], mark_read: ["mark read", "okundu"], delete: ["delete", "sil"], move: ["move", "taşı"], archive: ["archive", "arşivle"], free: ["free time", "boş zaman"], create: ["create", "oluştur"], respond: ["respond", "yanıtla"], cancel: ["cancel", "iptal"], add: ["add", "ekle"], replace: ["update", "güncelle"], remove: ["remove", "kaldır"], calendars: ["calendars", "takvimler"], alarm: ["alarm", "alarm"], timer: ["timer", "zamanlayıcı"],
    open: ["open", "aç"], click: ["click", "tıkla"], type: ["type", "yaz"], select: ["choose", "seç"], check: ["tick", "işaretle"], press: ["key", "tuş"], scroll: ["scroll", "kaydır"], back: ["back", "geri"], forward: ["forward", "ileri"], reload: ["reload", "yenile"], wait: ["wait", "bekle"], text: ["read", "oku"], elements: ["look", "incele"], find: ["find", "bul"], screenshot: ["screenshot", "ekran görüntüsü"], user: ["your turn", "sizde"], close: ["close", "kapat"],
    set: ["set", "belirle"], update: ["update", "güncelle"], complete: ["complete", "tamamla"], block: ["blocked", "engellendi"], pause: ["pause", "duraklat"], resume: ["resume", "sürdür"], clear: ["clear", "temizle"], status: ["status", "durum"],
    enhance: ["clean up", "temizle"], to_wav: ["to WAV", "WAV'a çevir"], to_m4a: ["to m4a", "m4a'ya çevir"], info: ["info", "bilgi"],
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
  function stepEl(name, args, state, result, ms, prog) {
    var inf = stepInfo(name, args);
    prog = prog || [];
    var live = state === "run";
    var el = h("div", { class: "step " + state },
      icon(inf.icon, "s si"),
      h("div", { class: "st" }, h("b", null, inf.label), inf.target ? h("span", { class: "tg" }, inf.target) : null,
        ms != null && !live ? h("span", { class: "tms" }, ms >= 1000 ? (ms / 1000).toFixed(ms >= 10000 ? 0 : 1) + " s" : ms + " ms") : null,
        live && prog.length ? h("span", { class: "tg plast" }, prog[prog.length - 1]) : null,
        (result || live) && args && Object.keys(args).length ? h("details", { class: "targs", onclick: function (e) { e.stopPropagation(); } }, h("summary", null, T("Arguments", "Argümanlar")), h("pre", null, JSON.stringify(args, null, 2).slice(0, 4000))) : null,
        prog.length ? h("details", { class: "targs tplog", open: live ? true : null, onclick: function (e) { e.stopPropagation(); } }, h("summary", null, T("Progress", "İlerleme") + " (" + prog.length + ")"), h("pre", { class: "plog" }, prog.join("\n"))) : null,
        result ? h("div", { class: "res" }, String(result).slice(0, 6000)) : null));
    if (result) el.addEventListener("click", function () { el.classList.toggle("open"); });
    if (result && window.YVP && YVP.stepExtras) YVP.stepExtras(el, result, state === "ok");
    if (live) requestAnimationFrame(function () { var pl = el.querySelector(".plog"); if (pl) pl.scrollTop = pl.scrollHeight; });
    return el;
  }

  /* ------------------------------------------------------------ chat render */
  function parseArgs(s) { try { return JSON.parse(s || "{}"); } catch (e) { return {}; } }

  function renderThread() {
    if (!$("#thread")) return;
    var scroll = $("#scroll"), th = clear($("#thread"));
    var msgs = (S.convo && S.convo.messages) || [];
    renderPlanBar(); renderCtx(); if (window.YVP) YVP.shell();
    if (!msgs.length && !S.running[S.convoId] && !(S.side[S.convoId || "_new"] || []).length) { th.appendChild(helloView()); updateTitle(); return; }
    var calls = {}, trace = null, turn = null, lastText = null; S1SEEN = "";
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
      if (m.divider) {
        trace = null; turn = null;
        th.appendChild(h("div", { class: "ctxdiv" }, h("span", null, T("Context cleared", "Bağlam temizlendi") + (m.ts ? " · " + timeOf(m.ts) : ""))));
        if (window.YVP) YVP.dividerExtras(th.lastChild, idx, msgs);
        return;
      }
      if (m.role === "user") {
        if (isInternalUser(m)) { trace = null; ensureTurn().appendChild(h("div", { class: "chiprow" }, h("span", { class: "nchip" }, icon(/goal round/.test(m.content) ? "target" : /heartbeat/.test(m.content) ? "heart-pulse" : "sparkle", "xs"), h("span", null, String(m.display || m.content).replace(/^(\(Reply in [^)]*\) )?/, "").slice(0, 160))))); return; }
        trace = null; turn = null; lastText = null;
        if (m.label || m.internal) { var lbl = m.label || T("Automatic turn", "Otomatik tur") + " · " + m.internal; th.appendChild(h("div", { class: "note" }, icon(/heartbeat/i.test(lbl) ? "heart-pulse" : /goal|hedef/i.test(lbl) ? "target" : /sub-?agent|alt ajan/i.test(lbl) ? "users-round" : "clock", "xs"), lbl)); return; }
        var files = [].concat(m.images || [], m.files || []);
        th.appendChild(h("div", { class: "u" }, h("div", null,
          h("div", { class: "bubble" }, m.display != null && m.display !== "" ? m.display : (m.content || "")),
          m.queued ? h("div", { class: "qcap" }, icon("clock", "xs"), T("Queued — yaver reads it at its next step", "Sırada — yaver bir sonraki adımında okuyacak")) : null,
          m.steer && !m.queued ? h("div", { class: "qcap done" }, icon(S.delivered && S.delivered[m.ts] ? "check-check" : "check", "xs"), S.delivered && S.delivered[m.ts] ? T("Delivered mid-run", "Çalışırken iletildi") : T("Sent mid-run", "Çalışırken gönderildi")) : null,
          files.length ? h("div", { class: "files" }, files.map(function (f) {
            return /\.(png|jpe?g|gif|webp|bmp)$/i.test(f) ? h("img", { class: "thumb", src: wsUrl(f), onclick: function () { previewFile(f); } })
              : h("button", { class: "chipfile", onclick: function () { previewFile(f); } }, icon((FK[kindOf(f)] || FK.other)[0], "xs"), h("span", null, f.split("/").pop()));
          })) : null)));
        if (m.s1 && m.s1.length && window.YVP && YVP.s1Strip) { var s1el = YVP.s1Strip(m.s1); if (s1el) { ensureTurn().appendChild(s1el); S1SEEN = YVP.s1Key(m.s1); } }
      } else if (m.role === "assistant") {
        if (m.error) { ensureTurn().appendChild(h("div", { class: "err" }, icon("triangle-alert", "s"), h("div", null, String(m.content || "").replace(/^⚠️\s*/, "")))); trace = null; if (m.changes && window.YVP) ensureTurn().appendChild(YVP.changesCard(m.changes)); return; }
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
          if (window.YVP) YVP.answerExtras(lastText, box, m, idx, msgs, finals[idx]);
          if (finals[idx] && S.fbAsk && S.fbAsk.convoId === S.convoId && S.fbAsk.idx === idx) lastText.appendChild(fbNoteBox(idx));
        }
        (m.tool_calls || []).forEach(function (tc) { calls[tc.id] = { name: (tc["function"] || {}).name, args: parseArgs((tc["function"] || {}).arguments) }; });
        if (m.changes && window.YVP) { trace = null; ensureTurn().appendChild(YVP.changesCard(m.changes)); }
      } else if (m.role === "tool") {
        var c = calls[m.tool_call_id] || { name: m.name, args: {} };
        if (!trace) { trace = h("div", { class: "trace" }); ensureTurn().appendChild(trace); }
        trace.appendChild(stepEl(c.name, c.args, m.ok === false ? "bad" : "ok", m.content, m.ms, m.progress));
      }
    });
    flushSide(null);
    if (!running) { var fu = followupsEl(); if (fu) (turn || th).appendChild(fu); }
    document.body.classList.toggle("agent-busy", !!S.running[S.convoId]);
    if (S.running[S.convoId]) {
      var t = ensureTurn();
      var mk = t.querySelector(".who .mark"); if (mk) mk.classList.add("busy");
      if (S.liveTool) {
        if (!trace) { trace = h("div", { class: "trace" }); t.appendChild(trace); }
        trace.appendChild(stepEl(S.liveTool.name, S.liveTool.args, "run", null, null, S.liveTool.prog));
      }
      var live = h("div", { id: "live" });
      if (S.live) live.appendChild(md(S.live));
      t.appendChild(live);
      t.appendChild(h("div", { class: "status", id: "lstatus" }, h("div", { class: "spin" }), h("span", null, S.liveStatus || (S.liveTool ? stepInfo(S.liveTool.name, S.liveTool.args).label + "…" : (S.live ? T("Writing…", "Yazıyor…") : (waitText() || T("Thinking…", "Düşünüyor…")))))));
    }
    // collapse long traces
    var divs = th.querySelectorAll(".ctxdiv");
    if (divs.length) { var dv = divs[divs.length - 1], el = dv.previousElementSibling; while (el) { el.classList.add("forgotten"); el = el.previousElementSibling; } }
    if (divs.length && window.YVP) YVP.foldForgotten(th, divs[divs.length - 1]);
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

  var S1SEEN = "";
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
    if (window.YVP) ideas = YVP.shortcutIdeas(ideas);
    var pills = h("div", { class: "pills" },
      h("button", { class: "pill", onclick: function () { openSettings(); } }, h("span", { class: "dot " + ((st.llm || {}).ready ? "ok" : "bad") }), (st.llm || {}).model ? (st.llm.model.split("/").pop()) : T("No model", "Model yok")),
      h("button", { class: "pill", onclick: function () { openMailPage(); } }, h("span", { class: "dot " + (mail.connected ? "ok" : mail.needsSignIn ? "warn" : "") }), mail.connected ? T("Mail connected", "Posta bağlı") : mail.needsSignIn ? T("Mail: sign in", "Posta: oturum aç") : mail.configured ? T("Mail", "Posta") : T("Mail not set up", "Posta kurulmadı")));
    return h("div", { class: "hello" },
      h("div", { class: "mark big" }, "y"),
      h("h1", null, greeting() + "."),
      h("p", null, T("Ask anything — I can search the web, read your mail and calendar, work with files and set reminders.", "İstediğini sor — web'de arayabilir, postanı ve takvimini okuyabilir, dosyalarla çalışabilir, hatırlatma kurabilirim.")),
      h("div", { class: "ideas" }, ideas.map(function (it) {
        return h("button", { class: "idea", onclick: function () { if (it[2] === "plan") planDay(); else if (it[4] && window.YVP) YVP.openTarget(it[4]); else send(it[3] || it[1]); } }, icon(it[0], "s"), h("span", null, it[1]));
      })),
      window.YVP ? YVP.helloExtras(pills) : null,
      pills);
  }

  function updateTitle() {
    var t = $("#ctitle"); if (!t) return;
    var msgs = (S.convo && S.convo.messages) || [];
    t.textContent = msgs.length ? (S.convo.title || "yaver") : "yaver";
    /* the original's header line: N turns · N tool calls (the context meter is the button next to it) */
    if (msgs.length) {
      var turns = msgs.filter(function (m) { return m.role === "user" && !m.harness && !m.divider && !isInternalUser(m) && !m.label && !m.internal && !m.queued && !m.steer; }).length;
      var tools = msgs.filter(function (m) { return m.role === "tool"; }).length;
      t.appendChild(h("small", { class: "cmeta" }, T(turns + (turns === 1 ? " turn" : " turns"), turns + " tur") + " · " + T(tools + (tools === 1 ? " tool call" : " tool calls"), tools + " araç çağrısı")));
    }
  }

  /* ------------------------------------------------------------ chat actions */
  function loadConvo(id) {
    if (!id) { S.convo = null; S.convoId = ""; S.live = ""; renderThread(); syncSend(); return Promise.resolve(); }
    return call("convos.load", { id: id }).then(function (c) {
      S.convo = c; S.convoId = c.id;
      /* messages typed while yaver worked: shown as queued until the core adds them to the chat */
      var q = S.queued[c.id];
      if (q) {
        var users = (c.messages || []).filter(function (m) { return m.role === "user"; }).map(function (m) { return String(m.display || "") + "\n" + String(m.content || ""); });
        S.delivered = S.delivered || {};
        (c.messages || []).forEach(function (m) { if (m.steer && m.ts && q.some(function (t) { return String(m.display || m.content || "").indexOf(t) >= 0; })) S.delivered[m.ts] = 1; });
        q = q.filter(function (t) { return !users.some(function (u) { return u.indexOf(t) >= 0; }); });
        if (q.length && (c.running || Date.now() - (S.doneAt[c.id] || 0) < 4000)) { S.queued[c.id] = q; c.messages = (c.messages || []).concat(q.map(function (t) { return { role: "user", content: t, display: t, queued: true }; })); }
        else delete S.queued[c.id];
      }
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
    var sentAt = Date.now();
    call("chat.send", args).then(function (r) {
      r = r || {};
      if (r.steered || r.queued) {
        var lm = S.convo && S.convo.messages && S.convo.messages[S.convo.messages.length - 1];
        if (lm && lm.role === "user" && lm.content === t) { lm.queued = true; if (S.convoId === tmpId) renderThread(); }
        /* the core keeps it outside the chat until its next step: remember it so a reload does not hide it */
        var qid = r.convoId || tmpId; (S.queued[qid] = S.queued[qid] || []).push(t);
        if (r.convoId && S.convoId === "_new") { moveSide("_new", r.convoId); S.convoId = r.convoId; S.convo.id = r.convoId; }
        return;
      }
      delete S.running["_new"];
      /* the turn can end (e.g. an instant model error) before this answer arrives: then it is not running any more */
      var finished = r.convoId && S.doneAt[r.convoId] >= sentAt;
      if (r.convoId && !finished) S.running[r.convoId] = true;
      if (r.convoId && (S.convoId === "_new" || S.convoId === tmpId)) { moveSide(tmpId, r.convoId); S.convoId = r.convoId; S.convo.id = r.convoId; S.convo.title = r.title || S.convo.title; }
      if (finished && S.convoId === r.convoId) { S.live = ""; S.liveTool = null; S.liveStatus = ""; loadConvo(r.convoId); if (S.doneErr && S.doneErr[r.convoId]) toast(S.doneErr[r.convoId]); }
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
    ["/clear", "Clear the context: yaver forgets the earlier messages", "Bağlamı temizle: yaver önceki mesajları unutur", "eraser"],
    ["/new", "Start a new conversation", "Yeni sohbet başlat", "square-pen"],
    ["/refine", "Learn a lesson from this chat", "Bu sohbetten ders çıkar", "sparkles"],
    ["/harness", "Show what yaver learned", "yaver'in öğrendiklerini göster", "graduation-cap"],
    ["/heartbeat", "Repeat an instruction (e.g. 30m check mail)", "Bir talimatı tekrarla (ör. 30m postaya bak)", "heart-pulse"],
    ["/heartbeats", "List the repeating instructions", "Tekrarlanan talimatları listele", "repeat"],
    ["/subagents", "Helper agents of this chat", "Bu sohbetin yardımcı ajanları", "users-round"],
    ["/btw", "A quick side question, without derailing the work", "İşi bölmeden kısa bir yan soru", "message-circle-question"],
    ["/today", "What's on today", "Bugün neler var", "sun"]
  ];
  function runCommand(text) {
    if (/^\/new\b/.test(text)) { newChat(true); return; }
    var cid = S.convoId === "_new" ? "" : S.convoId;
    addSide(S.convoId || "_new", "cmd", { text: text });
    call("command", { convoId: cid, text: text }).then(function (r) {
      r = r || {};
      /* a command on a new chat (/plan, /goal …): the core made the chat, so it now has an id */
      if (r.convoId && (!S.convoId || S.convoId === "_new") && !S.running["_new"]) { moveSide("_new", r.convoId); S.convoId = r.convoId; if (!S.convo) S.convo = { id: r.convoId, title: "", messages: [] }; else S.convo.id = r.convoId; refreshConvos(); }
      if (r.planning && r.convoId) { S.plan[r.convoId] = r.planning; renderPlanBar(); }
      if (r.note) addSide(r.convoId || S.convoId, "note", { text: r.note });
      if (window.YVP) YVP.onCommand(r, text);
      if (r.started) { var sc = r.convoId || S.convoId; if (sc && sc !== "_new") { S.running[sc] = true; loadConvo(sc); refreshConvos(); } }
      else if (r.send) send(r.send, { label: r.label, internal: r.internal });
      if (/^\/(compact|clear)\b/.test(text)) { refreshCtx(S.convoId); if (/^\/clear\b/.test(text) && S.convoId && S.convoId !== "_new") { if (S.fu) delete S.fu[S.convoId]; loadConvo(S.convoId); } }
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
    call("files.pick").then(function (list) { (list || []).forEach(function (f) { S.atts.push(f); }); renderAtts(); syncSend(); if (window.YVP) YVP.afterAttach(list || []); }).catch(function (e) { toast(e.message); });
  }

  function renderAtts() {
    var box = $("#atts"); if (!box) return; clear(box);
    box.classList.toggle("hidden", !S.atts.length);
    S.atts.forEach(function (a, i) {
      box.appendChild(h("div", { class: "att" }, a.image ? h("img", { src: wsUrl(a.path) }) : icon((FK[kindOf(a.path)] || FK.other)[0], "s"), h("span", null, a.name || a.path.split("/").pop()),
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
    if (type === "mail.diag") { if (S.stDraw && !S.stDone) S.stDraw(d.rows); return; }
    d = d || {};
    if (type === "run") {
      var id = d.convoId;
      if (d.phase === "done") {
        delete S.running[id];
        S.doneAt[id] = Date.now(); S.doneErr = S.doneErr || {}; S.doneErr[id] = d.error || "";
        if (id === S.convoId) { S.live = ""; S.liveTool = null; S.liveStatus = ""; loadConvo(id); if (d.error) toast(d.error); }
        refreshConvos(); return;
      }
      if (d.phase === "start") {
        S.running[id] = true; delete S.doneAt[id];
        if (id === S.convoId) { S.live = ""; S.liveTool = null; S.liveStatus = d.label || ""; if (S.fu[id]) delete S.fu[id]; loadConvo(id); }
        refreshConvos(); return;
      }
      S.running[id] = true;
      if (id !== S.convoId) return;
      if (d.phase !== "delta" && d.phase !== "think" && d.phase !== "progress") { S.wait = { t0: Date.now(), info: null }; }
      if (d.phase === "think") { if (!S.wait) S.wait = { t0: Date.now(), info: null }; S.wait.info = d; waitTick(); return; }
      if (d.phase === "delta") { S.live += d.text; S.liveStatus = ""; renderLive(); }
      else if (d.phase === "step") { S.live = ""; loadConvo(id); }
      else if (d.phase === "tool") { S.liveTool = { name: d.name, args: d.args || {}, prog: [] }; S.liveStatus = ""; renderThread(); }
      else if (d.phase === "toolEnd") { S.liveTool = null; loadConvo(id); }
      else if (d.phase === "progress") {
        S.liveStatus = d.text; var st = $("#lstatus span"); if (st) st.textContent = d.text;
        /* the running tool card's Progress log (the original's toolProgress) */
        if (S.liveTool && d.text) {
          var pl = S.liveTool.prog || (S.liveTool.prog = []); var line = String(d.text).replace(/\s+/g, " ").trim().slice(0, 240);
          if (line && pl[pl.length - 1] !== line && pl.length < 200) pl.push(line);
          var rs = document.querySelector("#thread .step.run");
          if (rs && rs.querySelector(".plog")) { rs.querySelector(".plog").textContent = pl.join("\n"); rs.querySelector(".tplog summary").textContent = T("Progress", "İlerleme") + " (" + pl.length + ")"; var lst = rs.querySelector(".plast"); if (lst) lst.textContent = line; rs.querySelector(".plog").scrollTop = 1e9; }
          else renderThread();
        }
      }
    } else if (type === "approve") approveSheet(d);
    else if (type === "launch") onLaunch(d);
    else if (type === "toast") toast(d.text);
    else if (type === "refresh") { refreshState(); refreshConvos(); }
    else if (type === "resume") { S.state = d; if (d.lang) S.lang = d.lang; if (S.convoId) loadConvo(S.convoId); if (topPage() === "mail") loadMail(); if (topPage() === "settings") renderSettings(); }
    else if (type === "mail" || type === "mail.changed") { refreshState().then(function () { if (topPage() === "mail") loadMail(); }); }
    /* notes without a chat (Outlook backup, background jobs) are toasts, not lines in whatever chat happens to be open */
    else if (type === "note") { if (d.convoId) addSide(d.convoId, "note", { text: d.text }); else toast(stripEmoji(d.text)); }
    else if (type === "followups") {
      var fid = d.convoId; if (!fid) return;
      if (d.state === "none") delete S.fu[fid];
      else S.fu[fid] = { state: d.state, items: d.items || [], labels: d.labels || null, text: d.text };
      if (fid === S.convoId && !S.running[fid]) renderThread();
    }
    /* "memory" with a label = a chip for that chat (Agent); without one = the store changed (Outlook restore/merge) */
    else if (type === "memory") { if (d.label && d.convoId) addSide(d.convoId, "memory", d); kbChanged(); }
    else if (type === "skills") kbChanged();
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
    else if (type === "mail.triage" || type === "mail.index" || type === "mail.draft") onMailEvent(type, d);
  }

  /* ------------------------------------------------------------ chat extras: side items */
  var INTERNAL_RX = /^(\(Reply in [^)]*\) )?\[(harness|verify-on-stop|goal round|Context summary|heartbeat)/;
  function isInternalUser(m) { return m.role === "user" && !m.label && !m.display && !m.steer && INTERNAL_RX.test(String(m.content || "")); }

  function sideEl(it) {
    var d = it.d || {};
    switch (it.k) {
      case "cmd": return h("div", { class: "u cmd" }, h("div", null, h("div", { class: "bubble mono" }, d.text)));
      case "note": if (window.YVP && YVP.noteStrip) { var ns = YVP.noteStrip(d.text, S1SEEN); if (ns !== undefined) return ns; }
        return h("div", { class: "chiprow" }, h("span", { class: "nchip" + (d.bad ? " bad" : "") }, icon(noteIcon(d.text), "xs"), h("span", null, stripEmoji(d.text))));
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
  function stripEmoji(t) { return String(t || "").replace(/^\s*(?:\p{Extended_Pictographic}️?\s*)+/u, ""); }
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
        img ? h("img", { class: "dthumb", src: wsUrl(f.path), alt: "", onclick: function () { previewFile(f.path); } }) : h("div", { class: "di" }, icon((FK[kindOf(f.path)] || FK.other)[0], "s")),
        h("div", { class: "dt", onclick: function () { previewFile(f.path); } }, h("b", null, f.name || String(f.path || "").split("/").pop()), h("small", null, f.description || ((f.size != null ? bytes(f.size) + " · " : "") + vpOf(f.path)))),
        h("button", { class: "ib", "aria-label": T("Preview", "Önizle"), onclick: function () { previewFile(f.path); } }, icon("eye", "s")),
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
      if (a.action === "approve") call("plan.approve", { convoId: it.d.convoId || S.convoId, plan: a.plan || null }).then(function (r) { if (r && r.started === false) { it.done = false; toast(T("Could not start — check the model in Settings", "Başlatılamadı — Ayarlar'dan modeli kontrol edin")); } renderThread(); syncSend(); }).catch(function (e) { it.done = false; toast(e.message); renderThread(); });
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
        call("ctx.compact", { convoId: cid }).then(function (r) { if (r && (r.note || r.text || typeof r === "string")) toast(stripEmoji(r.note || r.text || r)); refreshCtx(cid); if (cid === S.convoId) loadConvo(cid); }).catch(function (e) { toast(e.message); });
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
    var rec = { close: close, onClose: o.onClose, el: sh };
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
          h("span", null, c.title || "—", c.snippet ? h("small", null, String(c.snippet).replace(/^\[(user|assistant)\]\s*/, "")) : null),
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
    var s = sheet({ title: c.title || T("Chat", "Sohbet"), body: h("div", null,
      h("button", { class: "opt", onclick: function () { s.close(); editSheet({ title: T("Rename chat", "Sohbeti yeniden adlandır"), value: c.title, save: function (v) {
        v = v.trim(); if (!v) { toast(T("Give it a name", "Bir ad verin")); return false; }
        call("convos.rename", { id: c.id, title: v }).then(function () { refreshConvos(); if (c.id === S.convoId && S.convo) { S.convo.title = v; updateTitle(); } }).catch(function (e) { toast(e.message); });
      } }); } }, icon("pencil", "s"), T("Rename", "Yeniden adlandır")),
      h("button", { class: "opt bad", onclick: function () { s.close();
        sheet({ title: T("Delete this chat?", "Bu sohbet silinsin mi?"), text: c.title, actions: [[T("Cancel", "İptal")], [T("Delete", "Sil"), "danger", function () {
          if (S.running[c.id]) call("chat.stop", { convoId: c.id }).catch(function () {});
          call("convos.delete", { id: c.id }).then(function () { if (c.id === S.convoId) newChat(); refreshConvos(); toast(T("Chat deleted", "Sohbet silindi")); }).catch(function (e) { toast(e.message); });
        }]] });
      } }, icon("trash-2", "s"), T("Delete", "Sil")),
      window.YVP ? YVP.convoMenuItems(c, function () { s.close(); }) : null) });
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

  /* ------------------------------------------------------------ files: yaver's virtual disk
     /workspace (the user's files), /.yaver (yaver's own: PERSONA.md, skills/, memories/ read-only, sessions/ read-only), /tmp.
     The same paths the agent's tools print; answers that mention them open them here. */
  var FK = { folder: ["folder", "fo"], image: ["file-image", "im"], pdf: ["file-text", "pd"], doc: ["file-type", "dc"], sheet: ["file-spreadsheet", "sh"], csv: ["file-spreadsheet", "sh"],
    slides: ["presentation", "sl"], md: ["file-pen-line", "tx"], text: ["file-text", "tx"], data: ["file-json", "cd"], code: ["file-code", "cd"], html: ["file-code", "cd"],
    archive: ["file-archive", "ar"], audio: ["file-audio", "md"], video: ["file-video", "md"], other: ["file-text", "ot"] };
  function kindOf(name, dir) {
    if (dir) return "folder";
    var e = String(name || "").split("/").pop().toLowerCase(); e = e.indexOf(".") >= 0 ? e.split(".").pop() : "";
    if (/^(png|jpe?g|gif|webp|bmp|svg|heic)$/.test(e)) return "image";
    if (e === "pdf") return "pdf";
    if (/^(docx?|docm|dotx|odt|rtf)$/.test(e)) return "doc";
    if (/^(xlsx?|xlsm|ods)$/.test(e)) return "sheet";
    if (/^(csv|tsv)$/.test(e)) return "csv";
    if (/^(pptx?|odp)$/.test(e)) return "slides";
    if (/^(md|markdown)$/.test(e)) return "md";
    if (/^(txt|log|ini|cfg|conf|env)$/.test(e)) return "text";
    if (/^(json|ya?ml|xml|toml)$/.test(e)) return "data";
    if (/^(m?js|ts|py|kt|java|c|cpp|h|css|sh|sql|go|rs|rb|php)$/.test(e)) return "code";
    if (/^html?$/.test(e)) return "html";
    if (/^(zip|tar|gz|tgz|7z|rar)$/.test(e)) return "archive";
    if (/^(mp3|wav|m4a|ogg|flac|aac)$/.test(e)) return "audio";
    if (/^(mp4|mov|webm|mkv|3gp)$/.test(e)) return "video";
    return "other";
  }
  function ftile(kind, path) {
    var k = FK[kind] || FK.other;
    var t = h("div", { class: "ftile k-" + k[1] }, icon(k[0], "s"));
    if (kind === "image" && path) { var im = h("img", { src: wsUrl(path), alt: "", loading: "lazy" }); im.addEventListener("error", function () { im.remove(); }); t.appendChild(im); }
    return t;
  }
  /* any path as the agent or the UI writes it → "/workspace/…" / "/.yaver/…" (mirrors VPath.normTool) */
  function vpOf(p) {
    p = String(p || "").trim().replace(/\\/g, "/");
    try { if (/%[0-9a-f]{2}/i.test(p)) p = decodeURIComponent(p); } catch (e) {}
    p = p.replace(/^file:\/\//i, "").replace(/^\/?ws\//, "/").replace(/[?#].*$/, "");
    if (/^~(\/|$)/.test(p)) p = "/.yaver" + p.slice(1);
    if (/^(workspace|\.yaver)(\/|$)/.test(p)) p = "/" + p;
    if (p.charAt(0) !== "/") p = "/workspace/" + p;
    var out = []; p.split("/").forEach(function (s) { if (!s || s === ".") return; if (s === "..") out.pop(); else out.push(s); });
    p = "/" + out.join("/");
    if (p !== "/" && !/^\/(workspace|\.yaver|tmp)(\/|$)/.test(p)) p = "/workspace" + p;
    return p;
  }
  /* the address the screen loads a disk file from (MainActivity serves /ws/…) */
  function wsUrl(p) { return "/ws" + vpOf(p).split("/").map(function (s) { return encodeURIComponent(s); }).join("/"); }
  /* a link in an answer that points at a file on the disk (not a web page, mail or task) */
  function isFileHref(href) {
    href = String(href || "").trim();
    if (!href || href.charAt(0) === "#") return false;
    if (/^file:/i.test(href)) return true;
    if (/^[a-z][a-z0-9+.-]*:/i.test(href)) return false;
    if (/^\/\//.test(href)) return false;
    return true;
  }
  var FPATH_RX = /(^|[\s(\[“"'«])((?:\/workspace|\/\.yaver|~)\/[^\s<>"'`)\]]*[^\s<>"'`)\].,;:!?…”»])/g;
  /* plain-text paths in an answer (/workspace/report.md, `~/skills/x/SKILL.md`) become taps that open the file */
  function filePaths(root) {
    root.querySelectorAll("code").forEach(function (c) {
      if (c.closest("pre,a")) return;
      var t = c.textContent.trim();
      if (/^(\/workspace|\/\.yaver|~)\/\S+$/.test(t) && !/[<>]/.test(t)) { c.classList.add("fpath"); c.setAttribute("role", "link"); c.addEventListener("click", function (e) { e.stopPropagation(); previewFile(t); }); }
    });
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), nodes = [], n;
    while ((n = walker.nextNode())) { FPATH_RX.lastIndex = 0; if (FPATH_RX.test(n.nodeValue) && !n.parentNode.closest("a,pre,code")) nodes.push(n); }
    nodes.forEach(function (tn) {
      var txt = tn.nodeValue, frag = document.createDocumentFragment(), last = 0, m;
      FPATH_RX.lastIndex = 0;
      while ((m = FPATH_RX.exec(txt))) {
        var st = m.index + m[1].length;
        frag.appendChild(document.createTextNode(txt.slice(last, st)));
        frag.appendChild(h("a", { class: "flink", href: m[2] }, m[2]));
        last = st + m[2].length;
      }
      frag.appendChild(document.createTextNode(txt.slice(last)));
      tn.parentNode.replaceChild(frag, tn);
    });
  }

  function fsub(e) {
    if (e.dir) return e.count != null ? (e.count === 0 ? T("Empty", "Boş") : e.count + T(e.count === 1 ? " item" : " items", " öğe")) : "";
    return bytes(e.size || 0) + (e.m ? " · " + shortWhen(e.m) : "");
  }
  var AREA = [["/workspace", "My files", "Dosyalarım", "folder"], ["/.yaver", "yaver's own", "yaver'in alanı", "brain"]];
  function areaOf(p) { return /^\/\.yaver(\/|$)/.test(p) ? "/.yaver" : "/workspace"; }
  function filesPage() { return S.pages.filter(function (x) { return x.name === "files"; })[0]; }

  function openFiles(path) {
    closeDrawer();
    S.fx = S.fx || { sort: "name", q: "", path: "/workspace" };
    var target = vpOf(path || S.fx.path || "/workspace");
    if (filesPage()) { while (topPage() !== "files") popPage(); filesGo(target); return; }
    var up = h("button", { class: "ib", id: "fx-up", "aria-label": T("Upload from the phone", "Telefondan yükle"), onclick: function () { filesUpload(); } }, icon("upload", "s"));
    var nf = h("button", { class: "ib", id: "fx-new", "aria-label": T("New", "Yeni"), onclick: function () { filesNew(); } }, icon("folder-plus", "s"));
    pushPage("files", T("Files", "Dosyalar"), function (body) {
      body.appendChild(h("div", { class: "seg", role: "tablist", id: "fx-seg" }, AREA.map(function (a) {
        return h("button", { role: "tab", "data-area": a[0], onclick: function () { S.fx.q = ""; filesGo(a[0]); } }, icon(a[3], "xs"), T(a[1], a[2]));
      })));
      body.appendChild(h("nav", { class: "fcrumbs", id: "fx-crumbs", "aria-label": T("Folder path", "Klasör yolu") }));
      var q = h("input", { type: "search", id: "fx-q", placeholder: T("Filter this folder", "Bu klasörde süz"), "aria-label": T("Filter this folder", "Bu klasörde süz") });
      q.addEventListener("input", function () { S.fx.q = q.value; renderFiles(); });
      body.appendChild(h("div", { class: "toolbar" }, h("label", { class: "search" }, icon("search", "xs"), q),
        h("button", { class: "select", id: "fx-sort", onclick: filesSortMenu }, icon("arrow-up-down", "xs"), h("span", null, ""))));
      body.appendChild(h("div", { id: "fx-note" }));
      body.appendChild(h("div", { class: "flist", id: "fx-list", role: "list" }));
      body.appendChild(h("div", { class: "meta fmeta", id: "fx-meta" }));
      filesGo(target);
    }, { actions: [up, nf] });
  }

  function filesGo(path) {
    var p = filesPage(); if (!p) return;
    return call("files.list", { path: path }).then(function (r) {
      var changed = !S.fx.data || S.fx.data.path !== r.path;
      S.fx.data = r; S.fx.path = r.path; if (changed) { S.fx.q = ""; var q = $("#fx-q", p.body); if (q) q.value = ""; }
      renderFiles(); if (changed) p.body.scrollTop = 0;
    }).catch(function (e) { toast(e.message); if (path !== "/workspace" && (!S.fx.data)) filesGo("/workspace"); });
  }
  function filesRefresh() { if (S.fx && S.fx.data) filesGo(S.fx.data.path); }

  var FSORT = [["name", "Name", "Ad"], ["date", "Newest first", "En yeni"], ["size", "Largest first", "En büyük"], ["type", "Type", "Tür"]];
  function filesSortMenu() {
    optionsSheet(T("Sort by", "Sırala"), FSORT.map(function (s) { return [s[0], T(s[1], s[2])]; }), S.fx.sort, function (v) { S.fx.sort = v; renderFiles(); });
  }
  function sorted(list) {
    var coll = new Intl.Collator(loc(), { numeric: true, sensitivity: "base" }), by = S.fx.sort;
    return list.slice().sort(function (a, b) {
      if (a.dir !== b.dir) return a.dir ? -1 : 1;
      if (by === "date") return (b.m || 0) - (a.m || 0) || coll.compare(a.name, b.name);
      if (by === "size") return (b.size || 0) - (a.size || 0) || coll.compare(a.name, b.name);
      if (by === "type") return coll.compare(a.kind || "", b.kind || "") || coll.compare(a.name, b.name);
      return coll.compare(a.name, b.name);
    });
  }

  function renderFiles() {
    var p = filesPage(); if (!p || !S.fx.data) return;
    var r = S.fx.data, path = r.path, area = areaOf(path);
    p.body.querySelectorAll("#fx-seg button").forEach(function (b) { var on = b.getAttribute("data-area") === area && path !== "/"; b.classList.toggle("on", on); b.setAttribute("aria-selected", on ? "true" : "false"); });
    var canAdd = !r.readOnly && path !== "/" && path !== "/.yaver";
    ["#fx-up", "#fx-new"].forEach(function (s) { var b = $(s, p.el); if (b) b.classList.toggle("hidden", !canAdd); });
    var sb = $("#fx-sort span", p.body); if (sb) sb.textContent = T.apply(null, FSORT.filter(function (s) { return s[0] === S.fx.sort; })[0].slice(1));
    // breadcrumbs: / › workspace › uploads
    var cr = clear($("#fx-crumbs", p.body));
    (r.crumbs || []).forEach(function (c, i) {
      if (i) cr.appendChild(icon("chevron-right", "xs sep"));
      var last = i === r.crumbs.length - 1;
      cr.appendChild(h("button", { class: "crumb" + (last ? " cur" : ""), "aria-current": last ? "page" : null, onclick: last ? null : function () { filesGo(c.path); } },
        c.path === "/" ? icon("hard-drive", "xs") : null, c.path === "/" ? h("span", { class: "vh" }, T("Disk", "Disk")) : c.name));
    });
    setTimeout(function () { cr.scrollLeft = cr.scrollWidth; }, 0);
    // what this folder is
    var note = clear($("#fx-note", p.body)), nt = null;
    if (path === "/.yaver/memories") nt = [icon("lock", "s"), h("div", null, h("b", null, T("Read-only view of yaver's memory", "yaver'in hafızasının salt okunur görünümü")), h("span", null, T("MEMORY.md and USER.md are generated from memory. Change entries on the Memory screen.", "MEMORY.md ve USER.md hafızadan üretilir. Kayıtları Hafıza ekranında değiştirin."))), h("button", { class: "btn sm", onclick: function () { openKnowledge(); } }, T("Memory", "Hafıza"))];
    else if (path === "/.yaver/sessions" || /^\/\.yaver\/sessions\//.test(path)) nt = [icon("lock", "s"), h("div", null, h("b", null, T("Saved conversations", "Kaydedilen sohbetler")), h("span", null, T("Read-only: yaver writes these itself.", "Salt okunur: bunları yaver kendisi yazar.")))];
    else if (path === "/.yaver/skills") nt = [icon("graduation-cap", "s"), h("div", null, h("b", null, T("Skills", "Yetenekler")), h("span", null, T("Each folder is a skill: SKILL.md with the steps, plus scripts and templates yaver runs by these paths.", "Her klasör bir yetenektir: adımları anlatan SKILL.md ile yaver'in bu yollardan çalıştırdığı betikler ve şablonlar.")))];
    else if (path === "/.yaver") nt = [icon("brain", "s"), h("div", null, h("b", null, T("yaver's own folder", "yaver'in kendi klasörü")), h("span", null, T("Its persona, skills and a view of its memory — the same paths yaver uses.", "Kişiliği, yetenekleri ve hafızasının görünümü — yaver'in kullandığı yollarla aynı.")))];
    else if (r.readOnly && path !== "/") nt = [icon("lock", "s"), h("div", null, h("b", null, T("Read-only", "Salt okunur")), h("span", null, r.why || ""))];
    if (nt) note.appendChild(h("div", { class: "fnote" }, nt));
    // the list
    var box = clear($("#fx-list", p.body));
    var q = String(S.fx.q || "").trim().toLocaleLowerCase(loc());
    var list = sorted((r.entries || []).filter(function (e) { return !q || e.name.toLocaleLowerCase(loc()).indexOf(q) >= 0; }));
    if (!list.length) {
      if (q) box.appendChild(h("div", { class: "fempty sm" }, T("Nothing here matches “" + S.fx.q + "”", "“" + S.fx.q + "” ile eşleşen bir şey yok")));
      else box.appendChild(h("div", { class: "empty fempty" }, h("div", { class: "ei" }, icon("folder-open")),
        h("h3", null, path === "/workspace" ? T("No files yet", "Henüz dosya yok") : T("This folder is empty", "Bu klasör boş")),
        h("p", null, path === "/workspace" ? T("Files you send yaver, mail attachments it saves and documents it makes appear here.", "yaver'e gönderdiğiniz dosyalar, kaydettiği e-posta ekleri ve hazırladığı belgeler burada görünür.") : T("Upload a file from the phone or ask yaver to put something here.", "Telefondan dosya yükleyin ya da yaver'den buraya bir şey koymasını isteyin.")),
        canAdd ? h("div", { class: "btnstack" }, h("button", { class: "btn p", onclick: function () { filesUpload(); } }, icon("upload", "s"), T("Upload from the phone", "Telefondan yükle"))) : null));
    }
    list.forEach(function (e) {
      var pressT = 0, long = false;
      var row = h("div", { class: "frow", role: "listitem", tabindex: "0", "data-path": e.path },
        ftile(e.kind, e.kind === "image" ? e.path : null),
        h("div", { class: "ft" }, h("b", null, e.name), h("span", null, fsub(e), e.ro && !/^\/(\.yaver)?$/.test(path) ? h("i", { class: "fro" }, icon("lock", "xs")) : null)),
        h("button", { class: "ib fmore", "aria-label": T("More for ", "Diğer: ") + e.name, onclick: function (ev) { ev.stopPropagation(); fileMenu(e); } }, icon("ellipsis-vertical", "s")));
      function open() { if (long) return; if (e.dir) filesGo(e.path); else previewFile(e.path); }
      row.addEventListener("click", open);
      row.addEventListener("keydown", function (ev) { if (ev.key === "Enter") open(); });
      row.addEventListener("touchstart", function () { long = false; pressT = setTimeout(function () { long = true; fileMenu(e); }, 520); }, { passive: true });
      row.addEventListener("touchend", function () { clearTimeout(pressT); });
      row.addEventListener("touchmove", function () { clearTimeout(pressT); }, { passive: true });
      row.addEventListener("contextmenu", function (ev) { ev.preventDefault(); fileMenu(e); });
      box.appendChild(row);
    });
    var nd = (r.entries || []).filter(function (e) { return e.dir; }).length, nfl = (r.entries || []).length - nd;
    var sz = (r.entries || []).reduce(function (s, e) { return s + (e.dir ? 0 : (e.size || 0)); }, 0);
    clear($("#fx-meta", p.body)).appendChild(document.createTextNode(
      (nd ? nd + T(nd === 1 ? " folder" : " folders", " klasör") : "") + (nd && nfl ? " · " : "") + (nfl ? nfl + T(nfl === 1 ? " file" : " files", " dosya") + " · " + bytes(sz) : "") + (r.readOnly && path !== "/" && path !== "/.yaver" ? " · " + T("read-only", "salt okunur") : "")));
    filesDisk(p);
  }
  /* the original's disk meter and document-index line */
  function filesDisk(p) {
    var el = $("#fx-disk", p.body) || p.body.appendChild(h("div", { class: "meta fdisk", id: "fx-disk" }));
    if (S.fx.disk && Date.now() - S.fx.diskAt < 15000) return drawDisk(el, S.fx.disk);
    call("files.stats").then(function (d) { S.fx.disk = d; S.fx.diskAt = Date.now(); drawDisk(el, d); }).catch(function () {});
  }
  function drawDisk(el, d) {
    clear(el);
    var used = d.appBytes || 0, tot = used + (d.free || 0), pct = tot ? Math.max(1, Math.round(used / tot * 100)) : 0;
    el.appendChild(h("div", { class: "fdbar", role: "meter", "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": String(pct), "aria-label": T("Storage used by yaver", "yaver'in kullandığı alan") }, h("i", { style: "width:" + pct + "%" })));
    el.appendChild(h("div", null, T("My files: ", "Dosyalarım: ") + (d.workspaceFiles || 0) + T(" files · ", " dosya · ") + bytes(d.workspaceBytes || 0) + " · " + T("yaver in all: ", "yaver toplam: ") + bytes(used) + " · " + bytes(d.free || 0) + T(" free on the phone", " telefonda boş")));
    el.appendChild(h("div", null, T("Document index: ", "Belge dizini: ") + (d.ragDocs || 0) + T(" files · ", " dosya · ") + (d.ragSections || 0) + T(" sections", " bölüm")));
  }

  function filesUpload() {
    var dir = S.fx && S.fx.data ? S.fx.data.path : "/workspace";
    call("files.pick", { dir: dir }).then(function (list) {
      list = list || []; if (!list.length) return;
      toast(list.length === 1 ? T("Uploaded ", "Yüklendi: ") + list[0].name : T(list.length + " files uploaded", list.length + " dosya yüklendi"));
      filesRefresh();
    }).catch(function (e) { toast(e.message); });
  }
  function filesNew() {
    var dir = S.fx.data.path;
    optionsSheet(T("New", "Yeni"), [["folder", T("Folder", "Klasör"), "folder-plus"], ["note", T("Text note (.md)", "Metin notu (.md)"), "file-pen-line"], ["upload", T("Upload from the phone", "Telefondan yükle"), "upload"]], null, function (v) {
      if (v === "upload") return filesUpload();
      if (v === "folder") editSheet({ title: T("New folder", "Yeni klasör"), placeholder: T("Folder name", "Klasör adı"), ok: T("Create", "Oluştur"), save: function (n) {
        if (!n.trim()) { toast(T("Give it a name", "Bir ad verin")); return false; }
        call("files.mkdir", { path: dir, name: n.trim() }).then(function () { filesRefresh(); }).catch(function (e) { toast(e.message); });
      } });
      if (v === "note") editSheet({ title: T("New note", "Yeni not"), value: T("note.md", "not.md"), ok: T("Create", "Oluştur"), save: function (n) {
        n = n.trim(); if (!n) { toast(T("Give it a name", "Bir ad verin")); return false; }
        if (!/\.[a-z0-9]{1,6}$/i.test(n)) n += ".md";
        call("files.new", { path: dir, name: n, text: "" }).then(function (r) { filesRefresh(); editFile({ path: r.path, name: n, text: "" }); }).catch(function (e) { toast(e.message); });
      } });
    });
  }

  /* "ask yaver about this file": the file goes into the composer as an attachment, the question is the user's */
  function askAboutFile(e) {
    closeAllPages(); closeSheets();
    if (e.dir) { $("#input").value = T("Look at the folder " + e.path + ": ", e.path + " klasörüne bak: "); }
    else { S.atts.push({ path: e.path, name: e.name || e.path.split("/").pop(), image: (e.kind || kindOf(e.path)) === "image", size: e.size }); renderAtts(); $("#input").value = T("About this file: ", "Bu dosya hakkında: "); }
    autosize(); syncSend(); setTimeout(function () { var i = $("#input"); i.focus(); i.setSelectionRange(i.value.length, i.value.length); }, 120);
  }
  /* "read with yaver": a new chat that reads the file and summarizes it */
  function readWithYaver(e) {
    closeSheets(); newChat();
    var k = e.kind || kindOf(e.path);
    send(T("Read " + e.path + (k === "image" ? " (look at the picture)" : "") + " and summarize it for me: what it is, the key points, numbers and anything I need to act on.",
      e.path + (k === "image" ? " resmine bak" : " dosyasını oku") + " ve benim için özetle: ne olduğu, ana noktalar, rakamlar ve harekete geçmem gereken bir şey varsa."),
      { display: T("Read and summarize ", "Oku ve özetle: ") + (e.name || e.path.split("/").pop()) });
  }
  function closeSheets() { while (sheets.length) sheets[sheets.length - 1].close(); }

  function fileDownload(e) {
    toast(e.dir ? T("Packing the folder…", "Klasör paketleniyor…") : T("Saving…", "Kaydediliyor…"));
    call("files.download", { path: e.path }).then(function (r) { toast(T("Saved to ", "Kaydedildi: ") + (r && r.saved || "Download/yaver")); }).catch(function (x) { toast(x.message); });
  }
  function fileViewPage(e) { call("files.view", { path: e.path }).catch(function (x) { toast(x.message); }); }
  /* the original's checkHtmlUI: run_html on the page and show its report */
  function fileRunHtml(e) {
    var box = h("div", null, h("div", { class: "status" }, h("div", { class: "spin" }), T("Running the page…", "Sayfa çalıştırılıyor…")));
    var s = sheet({ title: T("Run & check", "Çalıştır ve denetle") + " · " + (e.name || e.path.split("/").pop()), body: box, actions: [[T("Close", "Kapat")], [T("Ask yaver to fix it", "yaver'e düzelttir"), "p", function () {
      closeAllPages(); send(T("Run " + e.path + " with run_html and fix every problem it reports, then run it again until it is clean. Check it at width 390 too.", e.path + " dosyasını run_html ile çalıştır, bildirdiği her sorunu düzelt ve temiz çıkana kadar yeniden çalıştır. 390 genişlikte de denetle."), { display: T("Fix ", "Düzelt: ") + (e.name || e.path) });
    }]] });
    s.el.classList.add("tall");
    call("files.runHtml", { path: e.path, width: 390 }).then(function (r) { clear(box).appendChild(h("pre", { class: "fcode" }, r.report || "")); }).catch(function (x) { clear(box).appendChild(h("div", { class: "err" }, icon("triangle-alert", "s"), h("div", null, x.message))); });
  }
  function fileOpenApp(e) { call("open.file", { path: e.path }).catch(function (x) { toast(x.message); }); }
  function fileShare(e) { call("share.file", { path: e.path }).catch(function (x) { toast(x.message); }); }
  function fileRename(e) {
    editSheet({ title: T("Rename", "Yeniden adlandır"), value: e.name, ok: T("Rename", "Adlandır"), save: function (v) {
      v = v.trim(); if (!v) { toast(T("Give it a name", "Bir ad verin")); return false; }
      call("files.rename", { path: e.path, name: v }).then(function () { filesRefresh(); toast(T("Renamed", "Yeniden adlandırıldı")); }).catch(function (x) { toast(x.message); });
    } });
  }
  function filePickFolder(title, e, fn) {
    call("files.folders").then(function (list) {
      list = (list || []).filter(function (d) { return d !== e.path && d.indexOf(e.path + "/") !== 0; });
      var s = sheet({ title: title, text: e.name, body: h("div", { class: "fdest" }, list.map(function (d) {
        var depth = d.split("/").length - 2, here = S.fx && S.fx.data && S.fx.data.path === d;
        return h("button", { class: "opt", style: "padding-left:" + (4 + Math.min(depth, 6) * 14) + "px", onclick: function () { s.close(); fn(d); } },
          icon(d === "/.yaver/skills" ? "graduation-cap" : d === "/tmp" ? "clock" : "folder", "s"), h("div", null, d === "/workspace" ? T("My files", "Dosyalarım") : d.split("/").pop(), h("small", null, d)), here ? icon("check", "s c") : null);
      })), actions: [[T("Cancel", "İptal")]] });
    }).catch(function (x) { toast(x.message); });
  }
  function fileMove(e) { filePickFolder(T("Move to", "Şuraya taşı"), e, function (d) { call("files.move", { path: e.path, to: d }).then(function (r) { filesRefresh(); toast(T("Moved to ", "Taşındı: ") + d); }).catch(function (x) { toast(x.message); }); }); }
  function fileCopy(e) { filePickFolder(T("Copy to", "Şuraya kopyala"), e, function (d) { call("files.copy", { path: e.path, to: d }).then(function (r) { filesRefresh(); toast(T("Copied to ", "Kopyalandı: ") + d); }).catch(function (x) { toast(x.message); }); }); }
  function fileDelete(e) {
    sheet({ title: e.dir ? T("Delete this folder?", "Bu klasör silinsin mi?") : T("Delete this file?", "Bu dosya silinsin mi?"), text: e.path + (e.dir && e.count ? T(" — with everything in it", " — içindekilerle birlikte") : ""),
      body: h("div", { class: "danger-note" }, icon("triangle-alert", "s"), T("This cannot be undone.", "Bu işlem geri alınamaz.")),
      actions: [[T("Cancel", "İptal")], [T("Delete", "Sil"), "danger", function () {
        call("files.delete", { path: e.path }).then(function () { closeSheets(); filesRefresh(); toast(T("Deleted", "Silindi")); }).catch(function (x) { toast(x.message); });
      }]] });
  }
  var READABLE = { pdf: 1, doc: 1, sheet: 1, csv: 1, slides: 1, md: 1, text: 1, html: 1, image: 1, data: 1 };
  function fileMenu(e) {
    var k = e.kind || kindOf(e.name, e.dir), fixed = !!e.fixed, ro = !!e.ro;
    var opts = [];
    function o(ic, label, sub, fn, cls) { opts.push(h("button", { class: "opt" + (cls ? " " + cls : ""), onclick: function () { s.close(); fn(e); } }, icon(ic, "s"), h("div", null, label, sub ? h("small", null, sub) : null))); }
    if (e.dir) o("folder-open", T("Open", "Aç"), null, function () { filesGo(e.path); });
    else o("eye", T("Preview", "Önizle"), null, function () { previewFile(e.path); });
    if (!e.dir && (k === "html" || /\.svg$/i.test(e.name))) o("app-window", T("Open the page", "Sayfayı aç"), T("In yaver's browser, scripts run", "yaver'in tarayıcısında, betikler çalışır"), fileViewPage);
    if (!e.dir && k === "html" && /^\/workspace\//.test(e.path)) o("play", T("Run & check", "Çalıştır ve denetle"), T("Errors, missing files and overflow at phone width", "Hatalar, eksik dosyalar ve telefon genişliğinde taşma"), fileRunHtml);
    if (!e.dir) o("external-link", T("Open with another app", "Başka uygulamayla aç"), k === "doc" || k === "sheet" || k === "slides" ? T("Word, Excel, PowerPoint…", "Word, Excel, PowerPoint…") : null, fileOpenApp);
    o("message-square-text", T("Ask yaver about it", "yaver'e sor"), T("Adds it to your message", "Mesajınıza ekler"), askAboutFile);
    if (!e.dir && READABLE[k]) o("sparkles", T("Read and summarize", "Oku ve özetle"), T("yaver reads it in a new chat", "yaver yeni bir sohbette okur"), readWithYaver);
    if (!e.dir) o("share-2", T("Share", "Paylaş"), null, fileShare);
    o("download", T("Save to the phone", "Telefona kaydet"), e.dir ? T("As a .zip in Download/yaver", "Download/yaver içine .zip olarak") : "Download/yaver", fileDownload);
    if (!fixed && !ro) { o("pencil", T("Rename", "Yeniden adlandır"), null, fileRename); o("folder-input", T("Move to…", "Taşı…"), null, fileMove); }
    if (e.path !== "/" && e.path !== "/.yaver") o("copy", T("Copy to…", "Kopyala…"), null, fileCopy);
    if (!fixed && !ro) o("trash-2", T("Delete", "Sil"), null, fileDelete, "bad");
    var s = sheet({ body: h("div", null, h("div", { class: "fmhead" }, ftile(k, k === "image" ? e.path : null), h("div", { class: "ft" }, h("b", null, e.name), h("span", null, e.path))), opts) });
  }

  /* ---------------- preview */
  function previewFile(path) {
    var vp = vpOf(path);
    call("files.read", { path: vp }).then(showPreview).catch(function (err) {
      call("files.list", { path: vp }).then(function () { openFiles(vp); }).catch(function () { toast(err.message); });
    });
  }
  function csvTable(text, kind) {
    var sep = kind === "tsv" || /\t/.test(text.split("\n")[0]) ? "\t" : (text.split("\n")[0].split(";").length > text.split("\n")[0].split(",").length ? ";" : ",");
    var rows = [], row = [], cur = "", q = false;
    for (var i = 0; i < text.length && rows.length < 301; i++) {
      var c = text[i];
      if (q) { if (c === '"') { if (text[i + 1] === '"') { cur += '"'; i++; } else q = false; } else cur += c; continue; }
      if (c === '"') q = true; else if (c === sep) { row.push(cur); cur = ""; } else if (c === "\n") { row.push(cur.replace(/\r$/, "")); rows.push(row); row = []; cur = ""; } else cur += c;
    }
    if (cur || row.length) { row.push(cur); rows.push(row); }
    var head = rows.shift() || [];
    return h("div", { class: "tw" }, h("table", null, h("thead", null, h("tr", null, head.map(function (x) { return h("th", null, x); }))),
      h("tbody", null, rows.slice(0, 300).map(function (r) { return h("tr", null, r.map(function (x) { return h("td", null, x); })); }))));
  }
  function showPreview(f) {
    var k = f.kind || kindOf(f.name), view = h("div", { class: "fview" }), text = f.text;
    var s;
    function textView(raw) {
      clear(view);
      if (k === "md" && !raw) view.appendChild(md(text));
      else if (k === "csv" && !raw) view.appendChild(csvTable(text, f.name.split(".").pop().toLowerCase()));
      else {
        var shown = text;
        if (k === "data" && /\.json$/i.test(f.name)) { try { shown = JSON.stringify(JSON.parse(text), null, 2); } catch (x) {} }
        var lines = shown.replace(/\n$/, "").split("\n"), pre = h("pre", { class: "fcode" + (k === "code" || k === "data" || k === "html" ? " num" : "") });
        if (/ num$/.test(pre.className) && lines.length < 4000) lines.forEach(function (l) { pre.appendChild(h("span", { class: "ln" }, l)); pre.appendChild(document.createTextNode("\n")); });
        else pre.textContent = shown;   // text only: nothing in a file is ever run or parsed as HTML
        view.appendChild(pre);
      }
      if (f.truncated) view.appendChild(h("div", { class: "fnote sm" }, icon("info", "s"), h("span", null, T("Showing the first " + bytes(text.length) + " — open it in another app for the rest.", "İlk " + bytes(text.length) + " gösteriliyor — tamamı için başka uygulamada açın."))));
    }
    var toggle = null;
    if (!f.binary) {
      if (k === "md" || k === "csv") {
        var raw = /^\/\.yaver\/memories\//.test(f.path);   /* memory views read best as they are (entries split by §) */
        toggle = h("button", { class: "chip", onclick: function () { raw = !raw; toggle.textContent = raw ? T("Formatted", "Biçimli") : T("Source", "Kaynak"); textView(raw); } }, raw ? T("Formatted", "Biçimli") : T("Source", "Kaynak"));
      }
      textView(raw);
    } else if (k === "image") {
      var im = h("img", { class: "fimg", src: wsUrl(f.path), alt: f.name });
      im.addEventListener("click", function () { fileOpenApp(f); });
      view.appendChild(im);
    } else if (k === "pdf") {
      var pg = 0, pages = 0, img = h("img", { class: "fpdf", alt: f.name }), nav = h("div", { class: "fpnav" }), wait = h("div", { class: "fwait" }, h("i", { class: "spin" }), T("Rendering…", "Hazırlanıyor…"));
      view.appendChild(wait); view.appendChild(img); view.appendChild(nav);
      var w = Math.min(1600, Math.round((window.innerWidth || 360) * (window.devicePixelRatio || 2)));
      var showPage = function (n) {
        wait.classList.remove("hidden");
        call("files.pdf", { path: f.path, page: n, width: w }).then(function (r) {
          wait.classList.add("hidden"); pg = r.page; pages = r.pages; img.src = r.data;
          clear(nav);
          if (pages > 1) nav.appendChild(h("div", { class: "fpn" },
            h("button", { class: "ib", "aria-label": T("Previous page", "Önceki sayfa"), disabled: pg <= 0 || null, onclick: function () { showPage(pg - 1); } }, icon("chevron-left", "s")),
            h("span", null, T("Page ", "Sayfa ") + (pg + 1) + " / " + pages),
            h("button", { class: "ib", "aria-label": T("Next page", "Sonraki sayfa"), disabled: pg >= pages - 1 || null, onclick: function () { showPage(pg + 1); } }, icon("chevron-right", "s"))));
        }).catch(function (x) { wait.classList.add("hidden"); view.appendChild(h("div", { class: "fnote sm" }, icon("info", "s"), h("span", null, x.message))); });
      };
      showPage(0);
      toggle = docTextToggle();
    } else if (k === "audio" || k === "video") {
      var media = h(k, { class: k === "audio" ? "faudio" : "fvideo", controls: "", preload: "metadata", src: wsUrl(f.path) });
      media.addEventListener("error", function () { if (!view.querySelector(".fnote")) view.appendChild(h("div", { class: "fnote sm" }, icon("info", "s"), h("span", null, T("This format does not play here — open it in another app.", "Bu biçim burada çalmıyor — başka bir uygulamada açın.")))); });
      view.appendChild(h("div", { class: "fmedia" }, media));
    } else if (k === "doc" || k === "sheet" || k === "slides") {
      view.appendChild(h("div", { class: "fdoc" }, ftile(k), h("div", null, h("b", null, { doc: T("Word document", "Word belgesi"), sheet: T("Spreadsheet", "Elektronik tablo"), slides: T("Presentation", "Sunum") }[k]),
        h("span", null, T("Open it in Word, Excel or PowerPoint — or see its text here.", "Word, Excel veya PowerPoint ile açın — ya da metnini burada görün.")))));
      toggle = docTextToggle(true);
    } else {
      view.appendChild(h("div", { class: "fdoc" }, ftile(k), h("div", null, h("b", null, T("No preview for this file", "Bu dosya için önizleme yok")), h("span", null, f.mime || ""))));
    }
    function docTextToggle(auto) {
      var t = h("button", { class: "chip", onclick: function () {
        t.disabled = true; t.textContent = T("Reading…", "Okunuyor…");
        call("files.extract", { path: f.path }).then(function (r) {
          t.remove(); var pre = h("pre", { class: "fcode" }); pre.textContent = r.text || T("(no text found)", "(metin bulunamadı)"); view.appendChild(pre);
        }).catch(function (x) { t.disabled = false; t.textContent = T("Show text", "Metni göster"); toast(x.message); });
      } }, T("Show text", "Metni göster"));
      return t;
    }
    var e = { path: f.path, name: f.name, kind: k, size: f.size, dir: false };
    var acts = h("div", { class: "fpacts" },
      (k === "html" || /\.svg$/i.test(f.name) ? [["app-window", T("Page", "Sayfa"), fileViewPage]] : []).concat(k === "html" && /^\/workspace\//.test(f.path) ? [["play", T("Check", "Denetle"), fileRunHtml]] : [])
        .concat([["external-link", T("Open", "Aç"), fileOpenApp], ["message-square-text", T("Ask", "Sor"), askAboutFile], ["share-2", T("Share", "Paylaş"), fileShare], ["download", T("Save", "Kaydet"), fileDownload]])
        .concat(!f.binary && !f.readOnly ? [["pencil", T("Edit", "Düzenle"), function () { s.close(); editFile(f); }]] : [])
        .concat([["ellipsis-vertical", T("More", "Daha"), function () { fileMenu(Object.assign({}, e, { ro: f.readOnly, fixed: f.readOnly || f.path === "/.yaver/PERSONA.md" })); }]])
        .map(function (a) { return h("button", { onclick: function () { a[2](e); } }, icon(a[0], "s"), h("span", null, a[1])); }));
    s = sheet({ body: h("div", { class: "fprev" },
      h("div", { class: "fmhead" }, ftile(k, null), h("div", { class: "ft" }, h("b", null, f.name), h("span", null, bytes(f.size || 0) + (f.m ? " · " + shortWhen(f.m) : "") + (f.readOnly ? " · " + T("read-only", "salt okunur") : ""))), toggle),
      h("div", { class: "fpathl" }, f.path), acts, view) });
    s.el.classList.add("tall");
  }

  /* edit a text file (notes, skills, PERSONA.md) */
  function editFile(f) {
    var ta = h("textarea", { class: "fedit", spellcheck: "false", autocapitalize: "off", "aria-label": f.name });
    ta.value = f.text || "";
    var s = sheet({ title: f.name, text: f.path + (f.truncated ? T(" — only the first part was loaded; saving would cut the file", " — yalnızca ilk kısım yüklendi; kaydetmek dosyayı keser") : ""), body: ta,
      actions: [[T("Cancel", "İptal")], [T("Save", "Kaydet"), "p", function () {
        if (f.truncated) { toast(T("Too large to edit here", "Burada düzenlemek için çok büyük")); return false; }
        call("files.write", { path: f.path, text: ta.value }).then(function () { toast(T("Saved", "Kaydedildi")); filesRefresh(); if (f.path === "/.yaver/PERSONA.md") refreshState(); }).catch(function (x) { toast(x.message); });
      }]] });
    s.el.classList.add("tall");
    setTimeout(function () { ta.focus(); }, 260);
  }

  /* ------------------------------------------------------------ mail */
  /* Mail, the reading view and the calendar views, ported from the original's full Outlook-style view (Office, OF):
     server folders with counts, AI triage in the list, local index search with filters and AI search, the HTML body in a
     sandboxed frame (remote pictures blocked until "Show pictures", cid: pictures inline), move / archive / unread /
     permanent delete / empty folder with Undo, drafts edited and sent in place, Draft with AI, tasks from e-mail,
     and Agenda / Day / Work week / Week / Month with event details, new / edit / delete. */
  var FOLDERS = [["inbox", "Inbox", "Gelen Kutusu", "inbox"], ["sentitems", "Sent", "Gönderilenler", "send"], ["drafts", "Drafts", "Taslaklar", "file-pen"], ["archive", "Archive", "Arşiv", "archive"], ["junkemail", "Junk", "Gereksiz", "ban"], ["deleteditems", "Deleted", "Silinmiş", "trash-2"]];
  S.mail.pivot = "all"; S.mail.folders = {}; S.mail.total = 0; S.mail.results = null; S.mail.resHow = ""; S.mail.showFilters = false; S.mail.idx = null; S.mail.images = false;
  S.mail.sf = { when: "", from: "", to: "", has_attachments: null, attachment: "", folder: "all" };
  S.mail.cal = { view: "", day: null, events: [], key: "", busy: false, scrollKey: "" };
  var mailSeq = 0;
  /* the server's own folder name (it is localised) when known, else ours */
  function folderName(f) { var srv = S.mail.folders[f]; if (srv && srv.name && srv.name !== f) return srv.name; var x = FOLDERS.filter(function (q) { return q[0] === f; })[0]; return x ? T(x[1], x[2]) : f; }
  function folderIcon(f) { var x = FOLDERS.filter(function (q) { return q[0] === f; })[0]; return x ? x[3] : "folder"; }
  function mailPageRec() { return S.pages.filter(function (x) { return x.name === "mail"; })[0]; }
  function mailWho(b) { return b ? (typeof b === "string" ? b : (b.name || b.email || "?")) : "?"; }
  function errText(e) { return String((e && e.message) || e || "").replace(/^SIGNIN:\s*/, ""); }

  function openMailPage(tab) {
    closeDrawer();
    if (typeof tab === "string") S.mail.tab = tab;
    if (topPage() === "mail") { renderMailShell(); loadMail(); return; }
    var refresh = h("button", { class: "ib", "aria-label": T("Refresh", "Yenile"), onclick: function () { loadMail(true); loadFolders(); } }, icon("refresh-cw", "s"));
    var plus = h("button", { class: "ib", id: "mailplus", "aria-label": T("New e-mail", "Yeni e-posta"), onclick: function () { if (S.mail.tab === "cal") eventEditSheet(null); else composeSheet("new", null); } }, icon("square-pen", "s"));
    pushPage("mail", T("Mail", "Posta"), function (body) {
      body.appendChild(h("div", { class: "seg", role: "tablist" },
        h("button", { class: S.mail.tab === "mail" ? "on" : "", onclick: function () { S.mail.tab = "mail"; renderMailShell(); loadMail(); } }, icon("inbox", "xs"), T("Mail", "Posta")),
        h("button", { class: S.mail.tab === "cal" ? "on" : "", onclick: function () { S.mail.tab = "cal"; renderMailShell(); loadMail(); } }, icon("calendar", "xs"), T("Calendar", "Takvim"))));
      body.appendChild(h("div", { id: "mailbox" }));
      renderMailShell(); loadMail();
    }, { actions: [refresh, plus] });
    call("mailx.settings").then(function (x) {
      S.mail.images = !!(x && x.images);
      if (!S.mail.cal.view) { S.mail.cal.view = (x && x.calView) || "agenda"; if (S.mail.tab === "cal" && topPage() === "mail") { renderMailShell(); loadMail(); } }
    }).catch(function () {});
    loadFolders(); loadIndexState();
  }

  function renderMailShell() {
    var p = mailPageRec(); if (!p) return;
    p.body.querySelectorAll(".seg button").forEach(function (b, i) { b.classList.toggle("on", (i === 0) === (S.mail.tab === "mail")); });
    var plus = $("#mailplus", p.el);
    if (plus) { clear(plus).appendChild(icon(S.mail.tab === "cal" ? "plus" : "square-pen", "s")); plus.setAttribute("aria-label", S.mail.tab === "cal" ? T("New event", "Yeni etkinlik") : T("New e-mail", "Yeni e-posta")); }
    var box = clear($("#mailbox", p.body));
    if (S.mail.tab === "mail") {
      var q = h("input", { type: "search", placeholder: T("Search mail", "Postada ara"), value: S.mail.query, enterkeyhint: "search" });
      q.addEventListener("keydown", function (e) { if (e.key === "Enter") { S.mail.query = q.value.trim(); q.blur(); runMailSearch(false); } });
      /* clearing the box (its ×, or deleting the text) shows the folder again */
      q.addEventListener("input", function () { if (!q.value.trim() && (S.mail.query || S.mail.results)) { S.mail.query = ""; if (hasMailFilters()) runMailSearch(false); else { S.mail.results = null; renderMailShell(); loadMail(true); } } });
      var fbtn = h("button", { class: "sbtn" + (S.mail.showFilters || hasMailFilters() ? " on" : ""), "aria-label": T("Filters: date, sender, recipient, attachments, folder", "Filtreler: tarih, gönderen, alıcı, ekler, klasör"), onclick: function (e) { e.preventDefault(); S.mail.showFilters = !S.mail.showFilters; renderMailShell(); renderMailList(); } }, icon("filter", "xs"));
      var aibtn = h("button", { class: "sbtn ai", "aria-label": T("AI search: describe the e-mail in your own words", "Yapay zekâ ile ara: e-postayı kendi cümlelerinizle anlatın"), onclick: function (e) { e.preventDefault(); S.mail.query = q.value.trim(); if (!S.mail.query) { toast(T("Type what you are looking for", "Ne aradığınızı yazın")); q.focus(); return; } q.blur(); runMailSearch(true); } }, icon("sparkles", "xs"));
      var fsel = h("button", { class: "select", id: "mfolder", onclick: pickFolder });
      paintFolderButton(fsel);
      box.appendChild(h("div", { class: "toolbar" }, fsel, h("label", { class: "search msearch" }, icon("search", "xs"), q, fbtn, aibtn)));
      if (S.mail.showFilters || hasMailFilters()) box.appendChild(mailFilterBar());
      if (!S.mail.results && !S.mail.query) {
        var piv = [["all", T("All", "Tümü")], ["unread", T("Unread", "Okunmamış")]].concat(S.mail.folder === "inbox" ? [["reply", T("Needs reply", "Yanıt bekleyen")]] : []);
        if (S.mail.pivot === "reply" && S.mail.folder !== "inbox") S.mail.pivot = "all";
        var emptyBtn = (S.mail.folder === "deleteditems" || S.mail.folder === "junkemail") ? h("button", { class: "btn xs dline", onclick: emptyMailFolder }, icon("trash-2", "xs"), T("Empty folder", "Klasörü boşalt")) : null;
        box.appendChild(h("div", { class: "frow mpiv" }, filterChips(piv, S.mail.pivot, function (k) {
          var re = (S.mail.pivot === "unread") !== (k === "unread"); S.mail.pivot = k; renderMailShell(); if (re) loadMail(true); else renderMailList();
        }), emptyBtn));
      }
    } else {
      var v = calView();
      box.appendChild(h("div", { class: "calbar" },
        h("button", { class: "select sm", onclick: pickCalView }, icon("calendar", "xs"), calViewName(v), icon("chevron-down", "xs")),
        h("button", { class: "btn xs", onclick: function () { S.mail.cal.day = calSod(new Date()); loadCal(); } }, T("Today", "Bugün")),
        h("span", { class: "sp" }),
        h("button", { class: "ib sm", "aria-label": T("Previous", "Önceki"), onclick: function () { calGo(-1); } }, icon("chevron-left", "s")),
        h("button", { class: "ib sm", "aria-label": T("Next", "Sonraki"), onclick: function () { calGo(1); } }, icon("chevron-right", "s"))));
      box.appendChild(h("button", { class: "caltitle", onclick: miniMonthSheet }, h("span", null, calTitle()), icon("chevron-down", "xs")));
    }
    box.appendChild(h("div", { id: "mailmeta", class: "meta" }));
    box.appendChild(h("div", { id: "maillist" }));
    if (S.mail.tab === "mail") box.appendChild(h("div", { id: "mailidx", class: "idxline" }));
  }

  function paintFolderButton(b) {
    b = b || $("#mfolder"); if (!b) return;
    var f = S.mail.folders[S.mail.folder], n = f ? (S.mail.folder === "drafts" ? f.total : f.unread) : 0;
    clear(b); b.appendChild(h("span", { class: "fn" }, folderName(S.mail.folder))); if (n) b.appendChild(h("span", { class: "fc" }, n > 999 ? "999+" : String(n))); b.appendChild(icon("chevron-down", "xs"));
  }
  function visibleFolders() { var known = Object.keys(S.mail.folders).length > 0; return FOLDERS.filter(function (f) { return f[0] !== "archive" || !known || S.mail.folders.archive; }); }
  function pickFolder() {
    optionsSheet(T("Folder", "Klasör"), visibleFolders().map(function (f) {
      var x = S.mail.folders[f[0]], n = x ? (f[0] === "drafts" ? x.total : x.unread) : 0;
      return [f[0], folderName(f[0]), f[3], n ? (f[0] === "drafts" ? T(n + " items", n + " öğe") : T(n + " unread", n + " okunmamış")) : null];
    }), S.mail.folder, function (v) { S.mail.folder = v; S.mail.query = ""; S.mail.results = null; S.mail.pivot = "all"; S.mail.items = []; renderMailShell(); loadMail(true); });
  }
  function loadFolders() {
    return call("mail.folders").then(function (list) {
      var o = {}; (list || []).forEach(function (f) { o[f.id] = f; }); S.mail.folders = o; paintFolderButton();
    }).catch(function () {});
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
    call("mail.login").then(function (st) { if (S.state) S.state.mail = st; if (topPage() === "mail") { loadMail(); loadFolders(); } if (topPage() === "settings") renderSettings(); });
  }

  function loadMail(force, more) {
    var list = $("#maillist"); if (!list) return;
    if (S.mail.tab === "cal") { loadCal(force); return; }
    if (S.mail.results) { if (force && !S.mail.results.smart) runMailSearch(false); else renderMailList(); return; }
    if (!more && (!list.firstChild || force)) { clear(list).appendChild(h("div", { class: "status", style: "padding:24px 16px" }, h("div", { class: "spin" }), T("Loading…", "Yükleniyor…"))); }
    var my = ++mailSeq;
    call("mail.list", { folder: S.mail.folder, query: S.mail.query, unread: S.mail.pivot === "unread" && !S.mail.query, limit: 50, offset: more ? S.mail.items.length : 0 }).then(function (r) {
      if (my !== mailSeq || S.mail.tab !== "mail") return;
      var got = r.items || [];
      S.mail.items = more ? S.mail.items.concat(got.filter(function (m) { return !S.mail.items.some(function (x) { return x.id === m.id; }); })) : got;
      S.mail.total = r.total || 0;
      renderMailList();
    }).catch(function (e) { if (my === mailSeq) mailBlocked(e.message); });
  }

  /* the rows of the folder or of the index search, with the triage line and tags */
  function renderMailList() {
    var list = $("#maillist"); if (!list || S.mail.tab !== "mail") return;
    var meta = $("#mailmeta"), res = S.mail.results;
    var items = res ? res.items : S.mail.items;
    if (res && S.mail.pivot === "unread") items = items.filter(function (m) { return !m.read; });
    if (S.mail.pivot === "reply" && !res) items = items.filter(function (m) { return m.triage && m.triage.r; });
    if (meta) {
      clear(meta); meta.appendChild(h("span", { class: "dot ok" }));
      var unread = S.mail.items.filter(function (m) { return !m.read; }).length;
      meta.appendChild(document.createTextNode(res ? (res.busy ? T("Searching with AI…", "Yapay zekâ ile aranıyor…") : T(items.length + (items.length === 1 ? " result" : " results"), items.length + " sonuç") + (S.mail.resHow ? " · " + S.mail.resHow : ""))
        : S.mail.query ? T(items.length + " results", items.length + " sonuç") : (unread ? T(unread + " unread · ", unread + " okunmamış · ") : "") + T(S.mail.total + " in " + folderName(S.mail.folder), folderName(S.mail.folder) + " · " + S.mail.total)));
    }
    clear(list);
    if (res && res.busy) { list.appendChild(h("div", { class: "status", style: "padding:24px 16px" }, h("div", { class: "spin" }), T("Searching with AI…", "Yapay zekâ ile aranıyor…"))); renderIndexLine(); return; }
    if (!items.length) {
      list.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon(res ? "text-search" : "inbox")),
        h("h3", null, res ? T("No e-mails found in the search index.", "Arama dizininde e-posta bulunamadı.") : S.mail.query ? T("Nothing found", "Bir şey bulunamadı") : S.mail.pivot !== "all" ? T("Nothing here.", "Burada bir şey yok.") : T("No mail here", "Burada posta yok")),
        res && S.mail.query ? h("div", { class: "btnstack" }, h("button", { class: "btn", onclick: function () { S.mail.results = null; renderMailShell(); loadMail(true); } }, icon("search", "s"), T("Search the server instead", "Sunucuda ara"))) : null));
      renderIndexLine(); return;
    }
    items.forEach(function (m) { list.appendChild(mailRow(m, !!res)); });
    if (!res && !S.mail.query && S.mail.items.length < S.mail.total) {
      var more = h("button", { class: "btn sm mmore", onclick: function () { more.disabled = true; clear(more).appendChild(h("div", { class: "spin xs" })); loadMail(false, true); } }, T("Load more (" + (S.mail.total - S.mail.items.length) + ")", "Daha fazla (" + (S.mail.total - S.mail.items.length) + ")"));
      list.appendChild(h("div", { class: "mmorew" }, more));
    }
    renderIndexLine();
  }
  var FSHORT = { inbox: ["Inbox", "Gelen"], sentitems: ["Sent", "Giden"], archive: ["Archive", "Arşiv"] };
  function mailRow(m, fromIndex) {
    /* Sent Items / Drafts: who it went to matters, not me as the sender */
    var outbox = fromIndex ? m.folder === "sentitems" : (S.mail.folder === "sentitems" || S.mail.folder === "drafts");
    var from = outbox ? T("To: ", "Kime: ") + (String(m.displayTo || "").trim() || T("(no recipients)", "(alıcı yok)")) : boxName(m.from) || T("(unknown sender)", "(bilinmeyen gönderen)");
    var t = m.triage, fs = fromIndex && FSHORT[m.folder];
    var btn = h("button", { class: "mrow" + (m.read ? "" : " unread"), "data-id": m.id, onclick: function (e) {
      if (this.dataset.swiped) { e.preventDefault(); return; }
      var draft = !fromIndex && S.mail.folder === "drafts";
      if (!draft) { m.read = true; this.classList.remove("unread"); }
      openMail(m.id, m, { folder: fromIndex ? m.folder : S.mail.folder, edit: draft });
    } },
      outbox ? avatar(String(m.displayTo || "?").split(/[;,]/)[0]) : avatar(from, m.from && m.from.email),
      h("div", { class: "mc" },
        h("div", { class: "l1" }, h("span", { class: "from" }, from), h("span", { class: "time" }, m.importance === "High" ? icon("octagon-alert", "xs flag") : null, m.attachments ? icon("paperclip", "xs") : null, shortWhen(m.received))),
        h("div", { class: "l2" }, h("span", { class: "subj" }, m.subject || T("(no subject)", "(konu yok)")),
          t && t.p === "high" ? h("span", { class: "mtag hi" }, T("High", "Önemli")) : null, t && t.r ? h("span", { class: "mtag rp" }, T("Reply", "Yanıt")) : null,
          fs ? h("span", { class: "mtag fl" }, T(fs[0], fs[1])) : null),
        fromIndex && m.attNames && m.attNames.length ? h("div", { class: "prev attn" }, icon("paperclip", "xs"), " " + m.attNames.join(", ")) : null,
        t && t.s ? h("div", { class: "prev tri" }, "✦ " + t.s) : m.preview ? h("div", { class: "prev" }, m.preview.replace(/\s+/g, " ")) : null));
    return swipeRow(m, btn, fromIndex ? m.folder : S.mail.folder);
  }

  /* ---------- index search (filters, keywords, AI search) */
  function hasMailFilters() { var f = S.mail.sf; return !!(f.when || f.from || f.to || f.has_attachments != null || f.attachment || (f.folder && f.folder !== "all")); }
  /* the index search says how it searched (MailIndex: "AI search · from: …", "filters + keywords", "keywords", "filters") */
  function howText(s) { return String(s || "").replace(/^AI search/, T("AI search", "Yapay zekâ araması")).replace(/^filters \+ keywords/, T("filters + keywords", "filtreler + anahtar kelimeler")).replace(/^keywords/, T("keywords", "anahtar kelimeler")).replace(/^filters/, T("filters", "filtreler")); }
  function fromIndexEntry(x) {
    return { id: x.id, subject: x.s, from: x.fr, received: x.received, read: x.r, attachments: !!(x.a && x.a.length), attNames: x.a || [], importance: "Normal", preview: String(x.b || "").slice(0, 220),
      displayTo: (x.to || []).map(function (p) { return p.name || p.email; }).join("; "), folder: x.f, triage: null };
  }
  var filterT = 0;
  function runMailSearch(smart) {
    clearTimeout(filterT);
    var q = S.mail.query; smart = !!(smart && q);
    if (!q && !hasMailFilters()) { S.mail.results = null; renderMailShell(); loadMail(true); return; }
    function serverInstead(st) {
      S.mail.results = null;
      if (!st || !st.busy) call("mailx.index.sync", {}).then(setIdx).catch(function () {});
      toast(T("The search index is being built — using the server search meanwhile", "Arama dizini hazırlanıyor — bu sırada sunucuda aranıyor"));
      renderMailShell(); loadMail(true);
    }
    function go(st) {
      if (!st || !st.count) { serverInstead(st); return; }
      var my = ++mailSeq, wasRes = !!S.mail.results;
      S.mail.results = { busy: smart, items: [], smart: smart }; S.mail.resHow = smart ? T("AI search", "Yapay zekâ araması") : "";
      if (wasRes) refreshFilterChrome(); else renderMailShell();
      renderMailList();
      call("mailx.index.search", { q: q, filters: JSON.parse(JSON.stringify(S.mail.sf)), smart: smart }).then(function (r) {
        if (my !== mailSeq || !S.mail.results) return;
        if (r && r.building) { serverInstead(null); return; }
        S.mail.results = { items: ((r && r.items) || []).map(fromIndexEntry), smart: smart };
        S.mail.resHow = howText((r && r.how) || (q ? "keywords" : "filters"));
        renderMailList();
      }).catch(function (e) { if (my !== mailSeq) return; S.mail.results = { items: [], smart: smart }; renderMailList(); toast(e.message); });
    }
    if (S.mail.idx && S.mail.idx.count) go(S.mail.idx);
    else call("mailx.index.state").then(function (st) { setIdx(st); go(st); }).catch(function () { go(null); });
  }
  function mailFilterBar() {
    var f = S.mail.sf;
    /* later: a change fired by the blur of a field must not rebuild the bar while the browser is still moving focus */
    /* a tap on the AI button right after typing in a field runs the AI search with the new filter and cancels this one */
    function set(k, v) { if (f[k] === v) return; f[k] = v; clearTimeout(filterT); filterT = setTimeout(function () { runMailSearch(false); }, 250); }
    function sel(k, opts, cur, conv) { return h("select", { class: "fsel", "aria-label": opts[0][1], onchange: function (e) { set(k, conv ? conv(e.target.value) : e.target.value); } }, opts.map(function (o) { return h("option", { value: o[0], selected: cur === o[0] }, o[1]); })); }
    function txt(k, ph) { var i = h("input", { class: "finp", type: "search", placeholder: ph, "aria-label": ph, value: f[k] || "", autocapitalize: "off", spellcheck: "false" }); i.addEventListener("change", function () { set(k, i.value.trim()); }); return i; }
    return h("div", { class: "mfilters" },
      sel("when", [["", T("Any time", "Her zaman")], ["today", T("Today", "Bugün")], ["yesterday", T("Yesterday", "Dün")], ["week", T("Last 7 days", "Son 7 gün")], ["month", T("Last 30 days", "Son 30 gün")], ["quarter", T("Last 3 months", "Son 3 ay")], ["year", T("This year", "Bu yıl")]], f.when),
      sel("folder", [["all", T("All folders", "Tüm klasörler")], ["inbox", T("Inbox", "Gelen Kutusu")], ["sentitems", T("Sent", "Gönderilenler")], ["archive", T("Archive", "Arşiv")]], f.folder || "all"),
      txt("from", T("From…", "Kimden…")), txt("to", T("To / Cc…", "Kime / Bilgi…")),
      sel("has_attachments", [["", T("Attachments: any", "Ekler: hepsi")], ["1", T("With attachments", "Ekli")], ["0", T("Without attachments", "Eksiz")]], f.has_attachments == null ? "" : f.has_attachments ? "1" : "0", function (v) { return v === "" ? null : v === "1"; }),
      txt("attachment", T("File name / .xlsx…", "Dosya adı / .xlsx…")),
      hasMailFilters() ? h("button", { class: "lnk", onclick: function () { S.mail.sf = { when: "", from: "", to: "", has_attachments: null, attachment: "", folder: "all" }; runMailSearch(false); renderMailShell(); renderMailList(); } }, T("Clear filters", "Filtreleri temizle")) : null);
  }
  /* the filter button's state and the "Clear filters" link, without rebuilding the bar the user is typing in */
  function refreshFilterChrome() {
    var on = S.mail.showFilters || hasMailFilters(), fb = $(".msearch .sbtn:not(.ai)"); if (fb) fb.classList.toggle("on", on);
    var bar = $(".mfilters"); if (!bar) return;
    var lnk = $(".lnk", bar);
    if (hasMailFilters() && !lnk) bar.appendChild(h("button", { class: "lnk", onclick: function () { S.mail.sf = { when: "", from: "", to: "", has_attachments: null, attachment: "", folder: "all" }; runMailSearch(false); renderMailShell(); renderMailList(); } }, T("Clear filters", "Filtreleri temizle")));
    else if (!hasMailFilters() && lnk) lnk.remove();
  }
  function loadIndexState() { call("mailx.index.state").then(setIdx).catch(function () {}); }
  function setIdx(st) { if (st) S.mail.idx = st; renderIndexLine(); }
  function renderIndexLine() {
    var el = $("#mailidx"); if (!el) return; var st = S.mail.idx; clear(el); if (!st) return;
    if (st.busy) { el.appendChild(h("div", { class: "spin xs" })); el.appendChild(h("span", null, st.progress || T("Indexing…", "Dizinleniyor…"))); return; }
    el.appendChild(h("span", null, T("Search index: " + (st.count || 0) + " e-mails", "Arama dizini: " + (st.count || 0) + " e-posta") + (st.synced ? T(" · updated ", " · güncellendi ") + shortWhen(st.synced) : "")));
    if (st.error) el.appendChild(h("span", { class: "bad" }, " · " + st.error));
    el.appendChild(h("button", { class: "lnk", onclick: function () { call("mailx.index.sync", {}).then(setIdx).catch(function (e) { toast(e.message); }); } }, T("Update", "Güncelle")));
    el.appendChild(h("button", { class: "lnk", onclick: function () {
      confirmSheet(T("Rebuild the search index?", "Arama dizini yeniden oluşturulsun mu?"), T("Rebuild the mail search index from the server? This reads your recent mail again.", "Posta arama dizini sunucudan yeniden oluşturulsun mu? Son postalarınız yeniden okunur."), T("Rebuild", "Yeniden oluştur"), function () { call("mailx.index.sync", { full: true }).then(setIdx).catch(function (e) { toast(e.message); }); }, true);
    } }, T("Rebuild", "Yeniden oluştur")));
  }

  /* a yes/no sheet for destructive actions */
  function confirmSheet(title, text, ok, fn, safe) {
    sheet({ title: title, text: text, actions: [[T("Cancel", "İptal")], [ok, safe ? "p" : "danger", function () { fn(); }]] });
  }

  /* ---------- delete, move, archive, unread, empty folder, Undo */
  function dropMailRow(id) {
    var row = document.querySelector('.mswipe[data-id="' + CSS.escape(id) + '"]');
    S.mail.items = S.mail.items.filter(function (x) { return x.id !== id; });
    if (S.mail.results) S.mail.results.items = S.mail.results.items.filter(function (x) { return x.id !== id; });
    if (S.mail.total > 0) S.mail.total--;
    if (row) { row.style.height = row.offsetHeight + "px"; row.classList.add("gone"); requestAnimationFrame(function () { row.style.height = "0px"; }); setTimeout(function () { row.remove(); if (!$("#maillist .mrow")) renderMailList(); }, 260); }
  }
  function undoToast(text, newId, back) {
    if (!newId || !back) { toast(text); return; }
    toast(text + " — " + T("Undo", "Geri al"), function () {
      call("mail.move", { id: newId, folder: back }).then(function () { toast(T("Undone", "Geri alındı")); if (topPage() === "mail") loadMail(true); loadFolders(); }).catch(function (e) { toast(e.message); });
    }, 8000);
  }
  function leaveReading(id) { var top = S.pages[S.pages.length - 1]; if (top && top.name === "mailread" && top.mailId === id) popPage(); }
  /* Outlook behaviour: Delete moves to Deleted Items (with Undo); in Deleted Items, or "Delete permanently", it is gone for good after a confirm */
  function deleteMsg(m, from, hard) {
    var perm = hard || from === "deleteditems";
    function run() {
      leaveReading(m.id); dropMailRow(m.id);
      call("mail.remove", { id: m.id, permanent: perm }).then(function (r) {
        loadFolders();
        if (perm) toast(T("Deleted permanently", "Kalıcı olarak silindi")); else undoToast(T("Moved to Deleted Items", "Silinmiş Öğeler'e taşındı"), r && r.id, from);
      }).catch(function (e) { toast(e.message); if (topPage() === "mail") loadMail(true); });
    }
    if (perm) confirmSheet(T("Delete permanently?", "Kalıcı olarak silinsin mi?"), T("The selected item will be deleted permanently. This cannot be undone. Continue?", "Seçili öğe kalıcı olarak silinecek. Geri alınamaz. Devam edilsin mi?"), T("Delete permanently", "Kalıcı sil"), run);
    else run();
  }
  function moveMsg(m, to, from) {
    call("mail.move", { id: m.id, folder: to }).then(function (r) {
      leaveReading(m.id); dropMailRow(m.id); loadFolders();
      var nid = r && r.id; undoToast(T("Moved to ", "Taşındı: ") + folderName(to), nid && nid !== m.id ? nid : null, from);
    }).catch(function (e) {
      toast(to === "archive" && /folder|not found|ErrorFolderNotFound|archive/i.test(e.message) ? T("This mailbox has no Archive folder: ", "Bu posta kutusunda Arşiv klasörü yok: ") + errText(e) : e.message);
    });
  }
  function moveMenu(m, from) {
    var opts = [["inbox", "inbox"], ["archive", "archive"], ["junkemail", "ban"], ["deleteditems", "trash-2"]].filter(function (o) { return o[0] !== from && (o[0] !== "archive" || !Object.keys(S.mail.folders).length || S.mail.folders.archive); });
    optionsSheet(T("Move to", "Şuraya taşı"), opts.map(function (o) { return [o[0], folderName(o[0]), o[1]]; }), "", function (v) { moveMsg(m, v, from); });
  }
  function markUnread(m) {
    call("mail.markRead", { id: m.id, read: false }).then(function () {
      var row = S.mail.items.filter(function (x) { return x.id === m.id; })[0]; if (row) row.read = false;
      toast(T("Marked as unread", "Okunmadı olarak işaretlendi")); leaveReading(m.id); loadFolders(); renderMailList();
    }).catch(function (e) { toast(e.message); });
  }
  function emptyMailFolder() {
    var f = S.mail.folder, name = folderName(f);
    confirmSheet(T("Empty “" + name + "”?", "“" + name + "” boşaltılsın mı?"), T("Everything in “" + name + "” will be deleted permanently. This cannot be undone. Continue?", "“" + name + "” klasöründeki tüm öğeler kalıcı olarak silinecek. Geri alınamaz. Devam edilsin mi?"), T("Empty folder", "Klasörü boşalt"), function () {
      toast(T("Emptying the folder…", "Klasör boşaltılıyor…"), null, 20000);
      call("mail.emptyFolder", { folder: f }).then(function () { S.mail.items = []; S.mail.total = 0; toast(T("“" + name + "” emptied", "“" + name + "” boşaltıldı")); loadMail(true); loadFolders(); }).catch(function (e) { toast(e.message); });
    });
  }
  function mailMoreSheet(m, from) {
    var draft = isDraftMsg(m, from);
    var s = sheet({ title: m.subject || T("(no subject)", "(konu yok)"), body: h("div", null,
      from !== "archive" ? h("button", { class: "opt", onclick: function () { s.close(); moveMsg(m, "archive", from); } }, icon("archive", "s"), h("div", null, T("Archive", "Arşivle"), h("small", null, T("Move to Archive", "Arşive taşı")))) : null,
      h("button", { class: "opt", onclick: function () { s.close(); moveMenu(m, from); } }, icon("folder-input", "s"), h("div", null, T("Move to…", "Taşı…"), h("small", null, T("Move to another folder", "Başka klasöre taşı")))),
      draft ? null : h("button", { class: "opt", onclick: function () { s.close(); markUnread(m); } }, icon("mail", "s"), h("div", null, T("Mark as unread", "Okunmadı yap"))),
      h("button", { class: "opt danger", onclick: function () { s.close(); deleteMsg(m, from, true); } }, icon("trash-2", "s"), h("div", null, T("Delete permanently", "Kalıcı olarak sil"), h("small", null, T("Cannot be undone", "Geri alınamaz"))))) });
  }

  /* ---------- the reading view */
  /* a mailbox as the UI shows it: the name, else the address ("" when neither is there) */
  function boxName(x) { if (!x) return ""; if (typeof x === "string") return x.trim(); return String(x.name || "").trim() || String(x.email || "").trim(); }
  /* To / Cc of the reading view: names (address on long-press/tooltip), long lists folded; no row at all when empty */
  function rcptBlock(to, cc) {
    function line(label, list) {
      list = (Array.isArray(list) ? list : []).filter(function (x) { return boxName(x); });
      if (!list.length) return null;
      var MAX = 4, el = h("div", { class: "rl" }, h("b", null, label), " ");
      function draw(all) {
        while (el.childNodes.length > 2) el.removeChild(el.lastChild);
        (all ? list : list.slice(0, MAX)).forEach(function (x, i) {
          if (i) el.appendChild(document.createTextNode(", "));
          var mail = typeof x === "string" ? "" : String(x.email || "").trim();
          el.appendChild(h("span", { class: "rn", title: mail || null }, boxName(x)));
        });
        if (!all && list.length > MAX) { el.appendChild(document.createTextNode(" ")); el.appendChild(h("button", { class: "more", onclick: function () { draw(true); } }, T("+" + (list.length - MAX) + " more", "+" + (list.length - MAX) + " kişi daha"))); }
      }
      draw(false);
      return el;
    }
    var a = line(T("To", "Kime"), to), b = line("Cc", cc);
    return a || b ? h("div", { class: "rcpt" }, a, b) : null;
  }
  function isDraftMsg(m, from) { return !!(m && (m.isDraft || from === "drafts")); }

  /* opts: {folder: where the row was (Undo, permanent delete, drafts), edit: open the draft editor at once} */
  function openMail(id, head, opts) {
    opts = opts || {};
    closeDrawer();
    var cur = null, from = opts.folder || "";
    var footer = h("div", { class: "actbar" });
    function bar() {
      clear(footer);
      function b(ic, label, fn, cls) { footer.appendChild(h("button", { class: cls || null, onclick: function () { if (cur) fn(cur); } }, icon(ic, "s"), label)); }
      if (cur && isDraftMsg(cur, from)) {
        b("pencil", T("Edit", "Düzenle"), function (m) { composeSheet("draft", m); }, "prim");
        b("send", T("Send", "Gönder"), function (m) { sendDraftNow(m); });
      } else {
        b("reply", T("Reply", "Yanıtla"), function (m) { composeSheet("reply", m); });
        b("reply-all", T("Reply all", "Tümünü"), function (m) { composeSheet("replyall", m); });
        b("forward", T("Forward", "İlet"), function (m) { composeSheet("forward", m); });
      }
      b("trash-2", from === "deleteditems" ? T("Delete for good", "Kalıcı sil") : T("Delete", "Sil"), function (m) { deleteMsg(m, from); });
      b("ellipsis-vertical", T("More", "Diğer"), function (m) { mailMoreSheet(m, from); });
    }
    bar();
    var rec = pushPage("mailread", head && head.subject ? head.subject : T("E-mail", "E-posta"), function (body, rec) {
      rec.mailId = id;
      body.appendChild(h("div", { class: "status", style: "padding:24px 16px" }, h("div", { class: "spin" }), T("Opening…", "Açılıyor…")));
      call("mail.full", { id: id }).then(function (m) {
        cur = m; m.cid = {}; rec.mail = m; rec.mailId = m.id || id;
        var row = S.mail.items.filter(function (x) { return x.id === id; })[0]; if (row && !m.isDraft) row.read = true;
        if (m.subject) rec.title.firstChild.textContent = m.subject;
        renderRead(body, m, from);
        bar();
        loadInlinePictures(m, function () { if (rec.repaint) rec.repaint(); });
        if (opts.edit && isDraftMsg(m, from)) composeSheet("draft", m);
      }).catch(function (e) { clear(body); body.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("triangle-alert")), h("p", null, errText(e)), /^SIGNIN:/.test(e.message) ? h("button", { class: "btn p", onclick: signInMail }, T("Sign in", "Oturum aç")) : null)); });
    }, { footer: footer, line: true });
    return rec;
  }

  function renderRead(body, m, from) {
    var rec = S.pages.filter(function (p) { return p.body === body; })[0];
    clear(body);
    var draft = isDraftMsg(m, from);
    var fromName = boxName(m.from) || T("(unknown sender)", "(bilinmeyen gönderen)"), fromMail = m.from && m.from.email ? String(m.from.email).trim() : "";
    var when = m.received || m.sent;
    var toNames = (m.to || []).map(boxName).filter(Boolean).join("; ");
    body.appendChild(h("div", { class: "rhead" },
      h("h2", null, m.subject || T("(no subject)", "(konu yok)")),
      draft ? h("div", { class: "sender" }, avatar(toNames || T("Draft", "Taslak"), m.to && m.to[0] && m.to[0].email), h("div", { class: "sn" }, h("b", null, h("span", { class: "mtag dr" }, T("Draft", "Taslak")), " ", toNames ? T("To: ", "Kime: ") + toNames : h("span", { class: "muted" }, T("no recipients yet", "henüz alıcı yok"))), h("span", null, T("not sent", "gönderilmedi"))))
        : h("div", { class: "sender" }, avatar(fromName, fromMail), h("div", { class: "sn" }, h("b", null, fromName), h("span", null, (fromMail && fromMail !== fromName ? fromMail + " — " : "") + (when ? longWhen(when) : "")))),
      draft ? rcptBlock([], m.cc) : rcptBlock(m.to, m.cc)));
    if (m.importance === "High") body.appendChild(h("div", { class: "mnote warn" }, icon("octagon-alert", "s"), h("span", null, T("This message was sent with High importance.", "Bu ileti yüksek önemle gönderildi."))));
    var t = m.triage; if (t && t.s) body.appendChild(h("div", { class: "mnote ai" }, icon("sparkles", "s"), h("span", null, t.s, t.r ? T(" · needs your reply", " · yanıtınızı bekliyor") : "")));
    if (!draft) body.appendChild(h("div", { class: "fchips aichips" },
      h("button", { class: "chip sm on", onclick: function () { askAbout(m); } }, icon("sparkles", "xs"), T("Ask yaver", "yaver'e sor")),
      h("button", { class: "chip sm", onclick: function () { summarizeMail(m); } }, icon("text-search", "xs"), T("Summarize", "Özetle")),
      h("button", { class: "chip sm", onclick: function () { askYaver(taskFromMailPrompt(m), T("Task from e-mail: “" + (m.subject || "") + "”", "Mailden görev: “" + (m.subject || "") + "”")); } }, icon("list-todo", "xs"), T("Task from e-mail", "Mailden görev")),
      h("button", { class: "chip sm", onclick: function () { manualTaskFromMail(m); } }, icon("square-check-big", "xs"), T("Manual task", "Elle görev"))));
    if (m.invite && m.invite.start) body.appendChild(inviteCard(m));
    var files = (m.files || []).filter(function (a) { return !(a.inline && a.cid && m.html && m.html.indexOf(a.cid) >= 0); });
    if (files.length) body.appendChild(h("div", { class: "files" }, files.map(function (f) {
      return h("div", { class: "attw" },
        h("button", { class: "fileb", onclick: function () { toast(T("Opening ", "Açılıyor: ") + f.name); call("mail.attachment", { id: m.id, attId: f.id, name: f.name }).catch(function (e) { toast(e.message); }); } }, icon(f.item || /^message\//.test(f.type || "") ? "mail" : "paperclip", "xs"), h("span", null, f.name || T("attachment", "ek")), f.size > 0 ? h("small", null, bytes(f.size)) : null),
        f.item ? null : h("button", { class: "attask", "aria-label": T("Ask yaver about this attachment", "Bu ek hakkında yaver'e sor"), onclick: function () { askAboutAttachment(m, f); } }, icon("sparkles", "xs")));
    })));
    var wrap = h("div", { class: "mbodyw" });
    body.appendChild(wrap);
    function paint() {
      clear(wrap);
      if (m.html) {
        var allow = S.mail.images || m.showPics;
        var fr = mailFrame(m.html, { images: allow, cid: m.cid || {} });
        if (fr._blocked && !allow) wrap.appendChild(h("div", { class: "mnote" }, icon("shield-check", "s"), h("span", { class: "grow" }, T("To protect your privacy, pictures from the internet were blocked.", "Gizliliğinizi korumak için internetteki resimler engellendi."),
          h("span", { class: "nbtns" }, h("button", { class: "lnk", onclick: function () { m.showPics = true; paint(); } }, T("Show pictures", "Resimleri göster")),
            h("button", { class: "lnk", onclick: function () { S.mail.images = true; call("mailx.settings.set", { key: "images", value: true }).catch(function () {}); paint(); } }, T("Always show", "Her zaman göster"))))));
        wrap.appendChild(h("div", { class: "mframew" }, fr));
      } else { var b = h("div", { class: "mbody" }); linkify(b, m.body || ""); wrap.appendChild(b); }
    }
    paint();
    if (rec) rec.repaint = paint;
  }

  function inviteCard(m) {
    var inv = m.invite, st = Date.parse(inv.start), en = Date.parse(inv.end || ""), cancelled = /Cancellation/.test(m.kind || "");
    var whenTxt = isNaN(st) ? inv.start : inv.allDay ? new Date(st).toLocaleDateString(loc(), { weekday: "long", day: "numeric", month: "long" }) + " · " + T("All day", "Tüm gün") : longWhen(st) + (!isNaN(en) ? " – " + (sameDay(new Date(st), new Date(en)) ? timeOf(en) : longWhen(en)) : "");
    var myResp = { Accept: T("You accepted", "Kabul ettiniz"), Tentative: T("You answered maybe", "Belki dediniz"), Decline: T("You declined", "Reddettiniz") }[inv.response];
    var join = joinLink(inv.location || "", m.html || m.body || "");
    return h("div", { class: "invite" + (cancelled ? " cancelled" : "") },
      h("div", { class: "when" }, icon(cancelled ? "calendar-x" : "calendar", "s"), h("span", null, whenTxt)),
      cancelled ? h("div", { class: "where" }, T("This meeting was cancelled.", "Bu toplantı iptal edildi.")) : null,
      [inv.location, inv.organizer].filter(Boolean).length ? h("div", { class: "where" }, [inv.location, String(inv.organizer || "").replace(/^(.+?)\s*<[^>]*>$/, "$1")].filter(Boolean).join(" — ")) : null,
      myResp && !cancelled ? h("div", { class: "where" }, icon("check", "xs"), " " + myResp) : null,
      cancelled ? null : h("div", { class: "btnrow" },
        h("button", { class: "btn sm", onclick: function () { respond(m.id, "decline"); } }, T("Decline", "Reddet")),
        h("button", { class: "btn sm", onclick: function () { respond(m.id, "tentative"); } }, T("Maybe", "Belki")),
        h("button", { class: "btn sm p", onclick: function () { respond(m.id, "accept"); } }, T("Accept", "Kabul"))),
      h("div", { class: "btnrow inv2" },
        join && !cancelled ? h("button", { class: "btn sm ghost", onclick: function () { call("open.link", { url: join }); } }, icon("video", "xs"), T("Join meeting", "Toplantıya katıl")) : null,
        isNaN(st) || cancelled ? null : h("button", { class: "btn sm ghost", onclick: function () { showInCalendar(st); } }, icon("calendar", "xs"), T("Show in calendar", "Takvimde göster"))));
  }
  function showInCalendar(ms) {
    S.mail.tab = "cal"; S.mail.cal.day = calSod(new Date(ms)); S.mail.cal.view = "day";
    while (S.pages.length && topPage() !== "mail") popPage();
    if (topPage() === "mail") { renderMailShell(); loadMail(); } else openMailPage("cal");
  }
  /* Teams / Zoom / Webex / Meet link in a location or description */
  function joinLink() {
    var all = Array.prototype.join.call(arguments, " ");
    var m = all.match(/https:\/\/(?:teams\.microsoft\.com\/l\/meetup-join|teams\.live\.com\/meet|[\w.-]*zoom\.us\/[jw]\/|[\w.-]*webex\.com\/[\w.\/-]*(?:j\.php|meet)|meet\.google\.com\/)[^\s"'<>)]*/i);
    return m ? m[0].replace(/&amp;/g, "&") : null;
  }

  /* safe e-mail document for a sandboxed iframe (original OF.mailDoc): scripts, forms and handlers removed; remote content optional */
  function mailDoc(html, o) {
    o = o || {}; var images = !!o.images, cid = o.cid || {};
    var doc = new DOMParser().parseFromString(String(html || ""), "text/html"), blocked = 0;
    doc.querySelectorAll("script,iframe,frame,frameset,object,embed,applet,form,meta,base,link[rel]:not([rel~=stylesheet]),noscript,svg script").forEach(function (n) { n.remove(); });
    doc.querySelectorAll("*").forEach(function (el) {
      Array.prototype.slice.call(el.attributes).forEach(function (at) { var n = at.name.toLowerCase(), v = at.value; if (n.indexOf("on") === 0 || n === "srcdoc" || n === "formaction" || ((n === "href" || n === "src" || n === "action" || n === "xlink:href") && /^\s*(javascript|vbscript|data:text\/html)/i.test(v))) el.removeAttribute(at.name); });
      if (el.tagName === "IMG" || el.tagName === "IMAGE") {
        var s = el.getAttribute("src") || "";
        if (/^cid:/i.test(s)) { var k = s.slice(4).replace(/^<|>$/g, ""); if (cid[k]) el.setAttribute("src", cid[k]); else { el.setAttribute("alt", el.getAttribute("alt") || ""); el.removeAttribute("src"); } }
        else if (/^https?:/i.test(s) && !images) { el.removeAttribute("src"); el.removeAttribute("srcset"); blocked++; }
      }
      if (!images) {
        if (el.hasAttribute("background") && /^https?:/i.test(el.getAttribute("background"))) { el.removeAttribute("background"); blocked++; }
        var st = el.getAttribute("style"); if (st && /url\(\s*['"]?https?:/i.test(st)) { el.setAttribute("style", st.replace(/url\(\s*['"]?https?:[^)]*\)/gi, "none")); blocked++; }
      }
      if (el.tagName === "A") { el.setAttribute("target", "_blank"); el.setAttribute("rel", "noopener noreferrer"); }
    });
    if (!images) {
      doc.querySelectorAll("link[rel~=stylesheet]").forEach(function (n) { n.remove(); blocked++; });
      doc.querySelectorAll("style").forEach(function (st) { if (/url\(\s*['"]?https?:|@import/i.test(st.textContent)) { st.textContent = st.textContent.replace(/@import[^;]+;/gi, "").replace(/url\(\s*['"]?https?:[^)]*\)/gi, "none"); blocked++; } });
    }
    var styles = Array.prototype.map.call(doc.querySelectorAll("head style"), function (s) { return s.outerHTML; }).join("");
    var csp = "default-src 'none'; img-src data: blob:" + (images ? " https: http:" : "") + "; style-src 'unsafe-inline'" + (images ? " https: http:" : "") + "; font-src data:" + (images ? " https: http:" : "");
    return { blocked: blocked, html: '<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="' + csp + '"><base target="_blank"><style>html{color-scheme:light}body{margin:0;padding:14px 16px;font:15px/1.5 system-ui,Roboto,"Segoe UI",Arial,sans-serif;color:#242424;background:#fff;overflow-wrap:anywhere;overflow-x:auto}img{max-width:100%;height:auto}blockquote{margin:0 0 0 .8ex;border-left:2px solid #c8c6c4;padding-left:1ex}pre{white-space:pre-wrap}a{color:#0f6cbd}</style>' + styles + "</head><body>" + (doc.body ? doc.body.innerHTML : "") + "</body></html>" };
  }
  /* the frame: no scripts (sandbox without allow-scripts), sized to its content, wide mails zoomed to the screen, links open outside */
  function mailFrame(html, o) {
    var r = mailDoc(html, o);
    var f = h("iframe", { class: "mframe", sandbox: "allow-same-origin", referrerpolicy: "no-referrer", title: T("Message body", "İleti gövdesi") });
    function fit() {
      try {
        var d = f.contentDocument; if (!d || !d.documentElement || !d.body) return;
        var w = f.clientWidth, sw = Math.max(d.documentElement.scrollWidth, d.body.scrollWidth);
        if (w && sw > w + 4 && !d.body.dataset.zoomed) { var z = Math.max(0.5, w / sw); if (z > 0.55) { d.body.style.zoom = z.toFixed(3); d.body.dataset.zoomed = "1"; } }
        var hh = Math.max(d.documentElement.scrollHeight, d.body.scrollHeight);
        f.style.height = Math.min(Math.max(hh + 2, 60), 20000) + "px";
      } catch (e) { f.style.height = "70vh"; }
    }
    f.addEventListener("load", function () {
      fit();
      try {
        var d = f.contentDocument;
        d.addEventListener("click", function (e) {
          var a = e.target && e.target.closest ? e.target.closest("a[href]") : null; if (!a) return;
          var u = a.getAttribute("href") || ""; e.preventDefault();
          if (/^(https?:|mailto:|tel:)/i.test(u)) call("open.link", { url: u });
        });
        Array.prototype.forEach.call(d.images, function (img) { if (!img.complete) img.addEventListener("load", fit); });
        if (window.ResizeObserver) new ResizeObserver(fit).observe(d.body);
      } catch (e) {}
      setTimeout(fit, 400); setTimeout(fit, 1500);
    });
    f.srcdoc = r.html; f._blocked = r.blocked;
    return f;
  }
  /* inline pictures (cid:) come from the attachments, after the text is on screen */
  function loadInlinePictures(m, done) {
    if (!m.html) return;
    var need = (m.files || []).filter(function (a) { return a.cid && a.size < 4e6 && m.html.indexOf(a.cid) >= 0; }).slice(0, 20);
    if (!need.length) return;
    call("mail.inline", { id: m.id, atts: need.map(function (a) { return { id: a.id, cid: a.cid, size: a.size }; }) }).then(function (r) {
      if (r && Object.keys(r).length) { m.cid = r; done(); }
    }).catch(function () {});
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

  /* swipe delete: the row disappears now, the server catches up (with Undo; in Deleted Items it asks first) */
  function removeMailNow(id, from) {
    var m = S.mail.items.filter(function (x) { return x.id === id; })[0] || (S.mail.results && S.mail.results.items.filter(function (x) { return x.id === id; })[0]) || { id: id };
    deleteMsg(m, from || S.mail.folder);
  }
  function setMailRead(m, read, rowBtn) {
    m.read = read; if (rowBtn) rowBtn.classList.toggle("unread", !read);
    call("mail.markRead", { id: m.id, read: read }).then(function () { loadFolders(); }).catch(function (e) { m.read = !read; if (rowBtn) rowBtn.classList.toggle("unread", read); toast(e.message.replace(/^SIGNIN:/, "")); });
  }
  /* iPhone/Outlook-style swipe: left = delete (red), right = read/unread */
  function swipeRow(m, btn, from) {
    var wrap = h("div", { class: "mswipe", "data-id": m.id },
      h("div", { class: "mact left" }, icon(m.read ? "mail" : "mail-open", "s"), h("span", null, m.read ? T("Unread", "Okunmadı") : T("Read", "Okundu"))),
      h("button", { class: "mact right", "aria-label": T("Delete", "Sil"), onclick: function () { removeMailNow(m.id, from); } }, icon("trash-2", "s"), h("span", null, T("Delete", "Sil"))),
      btn);
    var x0 = 0, y0 = 0, dx = 0, mode = "", open = 0, W = 0, OPEN = 92;
    function setX(x, anim) { btn.style.transition = anim ? "transform .2s ease" : "none"; btn.style.transform = "translateX(" + x + "px)"; wrap.classList.toggle("l", x > 0); wrap.classList.toggle("r", x < 0); }
    btn.addEventListener("touchstart", function (e) { var t = e.touches[0]; x0 = t.clientX; y0 = t.clientY; dx = 0; mode = ""; W = wrap.offsetWidth; delete btn.dataset.swiped; }, { passive: true });
    btn.addEventListener("touchmove", function (e) {
      var t = e.touches[0], mx = t.clientX - x0, my = t.clientY - y0;
      if (!mode) { if (Math.abs(mx) > 10 && Math.abs(mx) > Math.abs(my) * 1.3) mode = "h"; else if (Math.abs(my) > 10) mode = "v"; }
      if (mode !== "h") return;
      e.preventDefault(); dx = open + mx; btn.dataset.swiped = "1";
      if (dx > W * 0.6) dx = W * 0.6;
      setX(dx, false);
      wrap.classList.toggle("far", dx < -W * 0.45);
    }, { passive: false });
    btn.addEventListener("touchend", function () {
      if (mode !== "h") { if (open) { open = 0; setX(0, true); } return; }
      setTimeout(function () { delete btn.dataset.swiped; }, 350);
      if (dx < -W * 0.45) { setX(from === "deleteditems" ? 0 : -W, true); removeMailNow(m.id, from); return; }
      if (dx < -60) { open = -OPEN; setX(-OPEN, true); return; }
      if (dx > W * 0.28) { setMailRead(m, !m.read, btn); wrap.querySelector(".mact.left span").textContent = m.read ? T("Unread", "Okunmadı") : T("Read", "Okundu"); }
      open = 0; setX(0, true);
    });
    return wrap;
  }

  function deleteMail(m) { deleteMsg(m, S.mail.folder); }

  /* ---------- yaver on a mail: ask, summarize, task from e-mail, manual task, attachment */
  function askYaver(text, display) { closeAllPages(); newChat(); send(text, display ? { display: display } : undefined); }
  function askAbout(m) {
    closeAllPages(); newChat();
    var who = m.from && (m.from.name || m.from.email) || "";
    send(T("Look at this e-mail (mail id=" + m.id + ", subject \"" + m.subject + "\" from " + (m.from && (m.from.name || m.from.email)) + "). Summarize it, tell me what is expected of me and suggest a reply.",
      "Şu e-postaya bak (mail id=" + m.id + ", konu \"" + m.subject + "\", gönderen " + (m.from && (m.from.name || m.from.email)) + "). Özetle, benden ne bekleniyor söyle ve bir yanıt öner."), { display: T("About “" + m.subject + "” from " + who + ": summarize it, what is expected of me, and suggest a reply.", "“" + m.subject + "” (" + who + ") e-postasını özetle, benden ne bekleniyor söyle ve bir yanıt öner.") });
  }
  function summarizeMail(m) {
    var hasAtt = (m.files || []).some(function (a) { return !a.inline; });
    askYaver(T("Summarize the email \"" + m.subject + "\" from " + mailWho(m.from) + " (mail id " + m.id + ") and list any actions or deadlines for me." + (hasAtt ? " Also read its attachments with the mail tool (get_attachment) and include what matters." : ""),
      mailWho(m.from) + " kişisinden gelen \"" + m.subject + "\" e-postasını (mail id " + m.id + ") özetle; benim için yapılacak işleri ve son tarihleri listele." + (hasAtt ? " Eklerini de mail aracıyla (get_attachment) oku ve önemli olanları ekle." : "")),
      T("Summarize “" + m.subject + "” from " + mailWho(m.from), "“" + m.subject + "” (" + mailWho(m.from) + ") e-postasını özetle"));
  }
  /* "Task from e-mail": the agent decides between updating an existing task and creating new ones (original OF.taskFromMailPrompt) */
  function taskFromMailPrompt(m) {
    var from = mailWho(m.from), d = m.received || m.sent, when = d && !isNaN(new Date(d)) ? new Date(d).toISOString().slice(0, 10) : "";
    var atts = (m.files || []).some(function (a) { return !a.inline; });
    return T(
      "Create or update tasks from the e-mail \"" + m.subject + "\" from " + from + (when ? " (" + when + ")" : "") + ", mail id " + m.id + ".\n" +
      "1. Read it with the mail tool (action read, id " + m.id + ")" + (atts ? ", and its attachments if they matter" : "") + ". Work out every action item: what must be done, by whom, by when, and what changed.\n" +
      "2. Look for tasks it relates to on my board with task_board (list with key words from the subject, sender, project names; include done tasks if it may reopen one).\n" +
      "3. If a matching task exists: update it (status, due, owner, description as the e-mail implies) and add a note with what this e-mail says (date, sender, the decision or request) — in the note, link the e-mail as [" + m.subject + "](mail:" + m.id + "). If a done task gets new work, reopen it. If nothing matches: create the task(s) with task_board add (clear title, owner, due date if given, description, source_mail_id " + m.id + ").\n" +
      "4. Do not create duplicates. Do not create tasks for FYI or newsletter mail — just tell me there is nothing to do.\n" +
      "Finish with a short list: which tasks you created or updated — each as a link like [title](task:T3) — and what you changed.",
      "\"" + m.subject + "\" e-postasından (" + from + (when ? ", " + when : "") + ", mail id " + m.id + ") görev oluştur veya mevcut görevi güncelle.\n" +
      "1. E-postayı mail aracıyla oku (action read, id " + m.id + ")" + (atts ? ", önemliyse eklerini de oku" : "") + ". Tüm yapılacak işleri çıkar: ne yapılmalı, kim yapacak, ne zamana kadar, ne değişti.\n" +
      "2. task_board ile panomda ilgili görevleri ara (konu, gönderen ve proje adlarından anahtar kelimelerle list; bir görevi yeniden açabilecekse tamamlananları da dahil et).\n" +
      "3. Eşleşen görev varsa: e-postanın gerektirdiği şekilde güncelle (durum, tarih, sorumlu, açıklama) ve bu e-postanın ne dediğini not olarak ekle (tarih, gönderen, karar veya talep) — notta e-postaya [" + m.subject + "](mail:" + m.id + ") biçiminde bağlantı ver. Tamamlanmış bir göreve yeni iş geldiyse yeniden aç. Eşleşen yoksa: task_board add ile görev(ler) oluştur (net başlık, sorumlu, varsa son tarih, açıklama, source_mail_id " + m.id + ").\n" +
      "4. Mükerrer görev oluşturma. Bilgi amaçlı veya bülten e-postaları için görev açma — yapılacak bir şey olmadığını söyle.\n" +
      "Sonunda kısa bir liste ver: hangi görevleri oluşturdun veya güncelledin — her birini [başlık](task:T3) biçiminde link olarak yaz — ve neyi değiştirdin.");
  }
  function manualTaskFromMail(m) {
    newTaskSheet({ title: m.subject || "", owner: boxName(m.from), source: { mailId: m.id, subject: m.subject || "" } });
  }
  function askAboutAttachment(m, a) {
    askYaver(T("Read the attachment \"" + a.name + "\" of the email \"" + m.subject + "\" from " + mailWho(m.from) + " (mail id " + m.id + ") with the mail tool (get_attachment) and summarize it for me: key points, numbers, dates and anything I need to act on.",
      mailWho(m.from) + " kişisinden gelen \"" + m.subject + "\" e-postasının (mail id " + m.id + ") \"" + a.name + "\" ekini mail aracıyla (get_attachment) oku ve benim için özetle: ana noktalar, sayılar, tarihler ve yapmam gereken her şey."),
      T("About the attachment “" + a.name + "” of “" + m.subject + "”", "“" + m.subject + "” e-postasının “" + a.name + "” eki hakkında"));
  }

  /* recipient suggestions from recent mail and the company address book */
  function suggestPeople(input) {
    var box = h("div", { class: "psug hidden" }), t = 0, seq = 0;
    function last() { var v = input.value, i = Math.max(v.lastIndexOf(","), v.lastIndexOf(";")); return { head: i >= 0 ? v.slice(0, i + 1) + " " : "", q: v.slice(i + 1).trim() }; }
    input.addEventListener("input", function () {
      clearTimeout(t); var L = last();
      if (L.q.length < 2 || /@.+\./.test(L.q)) { box.classList.add("hidden"); return; }
      t = setTimeout(function () {
        var my = ++seq;
        call("people.search", { q: L.q }).then(function (list) {
          if (my !== seq) return; clear(box);
          (list || []).slice(0, 6).forEach(function (p) {
            box.appendChild(h("button", { type: "button", class: "opt", onclick: function () { var L2 = last(); input.value = L2.head + p.email + ", "; box.classList.add("hidden"); input.dispatchEvent(new Event("input")); input.focus(); } },
              avatar(p.name, p.email), h("div", null, p.name, h("small", null, p.email + (p.extra ? " · " + p.extra : "")))));
          });
          box.classList.toggle("hidden", !box.firstChild);
        }).catch(function () { box.classList.add("hidden"); });
      }, 250);
    });
    return box;
  }

  /* ---------- compose, reply, forward, edit a draft; Draft with AI */
  /* a saved draft: the text the user wrote, and the quoted original below it (kept as it is) — original editDraft */
  function draftParts(m) {
    var html = m.html || "";
    if (!html) return { text: m.body || "", tail: "" };
    var cut = html.search(/<div[^>]*id=["']?(divRplyFwdMsg|appendonsend)|<hr[^>]*>|<div[^>]*class=["']?(gmail_quote|OutlookMessageHeader)/i);
    var mine = cut > 0 ? html.slice(0, cut) : html, tail = cut > 0 ? html.slice(cut) : "";
    var text = new DOMParser().parseFromString(mine.replace(/<br\s*\/?>/gi, "\n").replace(/<\/(p|div)>/gi, "\n"), "text/html").body.textContent.replace(/ /g, " ").replace(/\n{3,}/g, "\n\n").trim();
    return { text: text, tail: tail };
  }
  function boxAddr(b) { return b.name && b.email ? b.name + " <" + b.email + ">" : (b.email || b.name || ""); }
  function addrList(v) { return String(v || "").split(/[;,\n]/).map(function (x) { return x.trim(); }).filter(Boolean); }

  function composeSheet(kind, m) {
    var draft = kind === "draft", parts = draft ? draftParts(m) : null;
    var to = h("input", { type: "text", inputmode: "email", placeholder: T("Name or e-mail", "İsim ya da e-posta"), autocapitalize: "off", autocomplete: "off", spellcheck: "false" });
    var cc = h("input", { type: "text", inputmode: "email", placeholder: T("optional", "isteğe bağlı"), autocapitalize: "off", autocomplete: "off", spellcheck: "false" });
    var subj = h("input", { type: "text", placeholder: T("Add a subject", "Konu ekleyin") });
    var body = h("textarea", { rows: kind === "forward" ? 4 : 8, placeholder: kind === "forward" ? T("Add a note (optional) — the original e-mail goes below it", "Not ekleyin (isteğe bağlı) — orijinal e-posta altına eklenir") : kind === "new" ? T("Write your message, or type a few notes and tap Draft with AI", "Mesajınızı yazın ya da birkaç not yazıp Yapay zekâ ile yaz'a dokunun") : T("Write your reply, or type a few notes and tap Draft with AI", "Yanıtınızı yazın ya da birkaç not yazıp Yapay zekâ ile yaz'a dokunun") });
    if (draft) { to.value = (m.to || []).map(boxAddr).filter(Boolean).join("; "); cc.value = (m.cc || []).map(boxAddr).filter(Boolean).join("; "); subj.value = m.subject === "(no subject)" ? "" : (m.subject || ""); body.value = parts.text; }
    var title = { new: T("New e-mail", "Yeni e-posta"), reply: T("Reply", "Yanıtla"), replyall: T("Reply all", "Tümünü yanıtla"), forward: T("Forward", "İlet"), draft: T("Edit draft", "Taslağı düzenle") }[kind];
    /* reply / reply all: Exchange picks the recipients; show who they are (without me) so the user knows */
    var me = String(((S.state || {}).mail || {}).user || "").toLowerCase();
    var meU = me.split("\\").pop();
    var mine = function (x) { var e = String((x && x.email) || "").toLowerCase(); return !!e && !!me && (e === me || (me.indexOf("@") < 0 && e.split("@")[0] === meU)); };
    var replyTo = !m || draft ? [] : kind === "reply" ? [m.from] : kind === "replyall" ? [m.from].concat(m.to || [], m.cc || []) : [];
    var seen = {};
    replyTo = replyTo.filter(function (x) { var k = String((x && (x.email || x.name)) || "").toLowerCase(); if (!boxName(x) || seen[k] || mine(x)) return false; seen[k] = 1; return true; });
    var aiBtn = h("button", { type: "button", class: "btn sm aib", onclick: function () { aiDraft(); } });
    function aiLabel() { if (aiBusy) return; clear(aiBtn); aiBtn.appendChild(icon("sparkles", "xs")); aiBtn.appendChild(document.createTextNode(body.value.trim() ? T("Rewrite with AI", "Yapay zekâ ile yeniden yaz") : T("Draft with AI", "Yapay zekâ ile yaz"))); }
    body.addEventListener("input", aiLabel);
    var aiBusy = false; aiLabel();
    var fields = h("div", null,
      kind === "new" || kind === "forward" || draft ? h("div", { class: "field" }, h("span", null, T("To", "Kime")), to, suggestPeople(to)) : null,
      replyTo.length ? h("div", { class: "field" }, h("span", null, T("To", "Kime")), h("div", { class: "rto" }, replyTo.map(boxName).join(", "))) : null,
      h("div", { class: "field" }, h("span", null, "Cc"), cc, suggestPeople(cc)),
      kind === "new" || draft ? h("label", { class: "field" }, h("span", null, T("Subject", "Konu")), subj) : null,
      h("label", { class: "field" }, h("span", null, m && !draft ? (/^\s*(re|fw|fwd|ynt|ilt|i̇lt)\s*:/i.test(m.subject || "") && (kind === "forward") === /^\s*(fw|fwd|ilt|i̇lt)\s*:/i.test(m.subject || "") ? m.subject : (kind === "forward" ? "FW: " : "RE: ") + (m.subject || "")) : T("Message", "Mesaj")), body),
      h("div", { class: "airow" }, aiBtn, h("small", null, T("Your notes in the message are used as instructions.", "Mesajdaki notlarınız talimat olarak kullanılır."))),
      draft && parts.tail ? h("div", { class: "qnote" }, icon("info", "xs"), T("The quoted original message below your text is kept as it is.", "Metninizin altındaki alıntılanmış özgün mesaj olduğu gibi korunur.")) : null,
      m && !draft ? h("details", { class: "quoted", open: kind === "forward" ? true : null }, h("summary", null, (kind === "forward" ? T("Forwarded e-mail", "İletilen e-posta") : T("Original e-mail", "Orijinal e-posta")) + (m.files && m.files.length && kind === "forward" ? T(" · " + m.files.length + " attachment(s) included", " · " + m.files.length + " ek dahil") : "")),
        h("div", { class: "qh" }, (boxName(m.from)) + (m.received ? " — " + longWhen(m.received) : "")),
        h("div", { class: "qb" }, String(m.body || "").slice(0, 2500) + (String(m.body || "").length > 2500 ? "…" : ""))) : null);
    var busy = false, s = null;
    function aiDraft() {
      if (aiBusy || busy) return;
      aiBusy = true; aiBtn.disabled = true; clear(aiBtn).appendChild(h("div", { class: "spin xs" })); aiBtn.appendChild(document.createTextNode(T("Writing…", "Yazılıyor…")));
      var ref = m && !draft && kind !== "new" ? m.id : "";
      call("mail.aiDraft", { kind: draft ? "new" : kind, ref: ref, subject: draft || kind === "new" ? subj.value : (m && m.subject) || "", to: addrList(to.value).concat(replyTo.map(boxName)), notes: body.value }).then(function (t) {
        if (t) { body.value = String(t); body.dispatchEvent(new Event("input")); }
      }).catch(function (e) { toast(T("AI draft failed: ", "Yapay zekâ taslağı olmadı: ") + errText(e)); })
        .then(function () { aiBusy = false; aiBtn.disabled = false; aiLabel(); });
    }
    function go(sendIt) {
      if (busy || aiBusy) return false;
      var args = { kind: kind, ref: m ? m.id : "", to: addrList(to.value), cc: addrList(cc.value), subject: subj.value, body: body.value, send: sendIt };
      if ((kind === "new" || kind === "forward" || (draft && sendIt)) && !args.to.length) { toast(T("Add a recipient", "Alıcı ekleyin")); return false; }
      if ((kind === "new" || draft) && sendIt && !args.subject.trim() && !args.body.trim()) { toast(T("Write a subject or a message", "Bir konu ya da mesaj yazın")); return false; }
      /* the sheet stays open until the server took it — a failed send must not lose what was written */
      busy = true; s.el.classList.add("busy");
      toast(sendIt ? T("Sending…", "Gönderiliyor…") : T("Saving…", "Kaydediliyor…"));
      var p = draft ? call("mail.updateDraft", { id: m.id, to: args.to, cc: args.cc, subject: args.subject, body: args.body, tail: parts.tail, send: sendIt }) : call("mail.compose", args);
      p.then(function (r) {
        busy = false; s.close();
        toast(sendIt ? T("Sent", "Gönderildi") : draft ? T("Draft saved", "Taslak kaydedildi") : T("Saved in Drafts", "Taslaklara kaydedildi"));
        if (draft) {
          if (sendIt) { leaveReading(m.id); dropMailRow(m.id); }
          else { var rec = S.pages.filter(function (p2) { return p2.name === "mailread" && p2.mailId === m.id; })[0]; if (rec) { m.to = args.to.map(function (a) { var x = /^(.*)<([^>]+)>$/.exec(a); return x ? { name: x[1].trim(), email: x[2] } : { name: "", email: a }; }); m.cc = args.cc.map(function (a) { var x = /^(.*)<([^>]+)>$/.exec(a); return x ? { name: x[1].trim(), email: x[2] } : { name: "", email: a }; }); m.subject = args.subject; m.html = ""; m.body = args.body + (parts.tail ? "\n\n" + new DOMParser().parseFromString(parts.tail, "text/html").body.textContent.trim() : ""); if (r && r.id) { m.id = r.id; rec.mailId = r.id; } renderRead(rec.body, m, "drafts"); rec.title.firstChild.textContent = m.subject || T("(no subject)", "(konu yok)"); } }
        }
        loadFolders();
        if (topPage() === "mail" && (S.mail.folder === "drafts" || S.mail.folder === "sentitems")) loadMail(true);
        else if (S.mail.folder === "drafts" || S.mail.folder === "sentitems") S.mail.items = S.mail.items.filter(function (x) { return !(draft && sendIt && x.id === m.id); });
      }).catch(function (e) { busy = false; s.el.classList.remove("busy"); toast(e.message); });
      return false;
    }
    s = sheet({ title: title, body: fields, actions: [[T("Save draft", "Taslak"), "", function () { return go(false); }], [T("Send", "Gönder"), "p", function () { return go(true); }]] });
    setTimeout(function () { (kind === "new" || kind === "forward" ? to : body).focus(); }, 260);
    return s;
  }
  /* "Send" on a draft in the reading view */
  function sendDraftNow(m) {
    var to = (m.to || []).map(boxName).filter(Boolean);
    if (!to.length) { toast(T("Add a recipient", "Alıcı ekleyin")); composeSheet("draft", m); return; }
    confirmSheet(T("Send this draft?", "Bu taslak gönderilsin mi?"), T("To: ", "Kime: ") + to.join(", ") + " — " + (m.subject || T("(no subject)", "(konu yok)")), T("Send", "Gönder"), function () {
      toast(T("Sending…", "Gönderiliyor…"));
      call("mail.sendDraft", { id: m.id }).then(function () { toast(T("Sent", "Gönderildi")); leaveReading(m.id); dropMailRow(m.id); loadFolders(); }).catch(function (e) { toast(e.message); });
    }, true);
  }

  /* ---------- calendar: Agenda / Day / Work week / Week / Month */
  var CAL_VIEWS = [["agenda", "Agenda", "Ajanda", "list-todo"], ["day", "Day", "Gün", "calendar"], ["workweek", "Work week", "İş haftası", "calendar-check"], ["week", "Week", "Hafta", "calendar-clock"], ["month", "Month", "Ay", "calendar"]];
  var CAL_HH = 44;
  function calView() { return S.mail.cal.view || "agenda"; }
  function calViewName(v) { var x = CAL_VIEWS.filter(function (q) { return q[0] === v; })[0]; return x ? T(x[1], x[2]) : v; }
  function calSod(d) { var x = new Date(d); x.setHours(0, 0, 0, 0); return x; }
  function calAdd(d, n) { var x = new Date(d); x.setDate(x.getDate() + n); return x; }
  function calSow(d) { var x = calSod(d); return calAdd(x, -((x.getDay() + 6) % 7)); }   // weeks start on Monday
  function calDay() { return S.mail.cal.day || (S.mail.cal.day = calSod(new Date())); }
  function calRange() {
    var d = calDay(), v = calView(), s;
    if (v === "agenda") return [d, calAdd(d, 14)];
    if (v === "day") return [d, calAdd(d, 1)];
    if (v === "workweek") { s = calSow(d); return [s, calAdd(s, 5)]; }
    if (v === "week") { s = calSow(d); return [s, calAdd(s, 7)]; }
    s = calSow(new Date(d.getFullYear(), d.getMonth(), 1)); return [s, calAdd(s, 42)];
  }
  function calTitle() {
    var r = calRange(), s = r[0], l = calAdd(r[1], -1), v = calView(), d = calDay();
    if (v === "month") return d.toLocaleDateString(loc(), { month: "long", year: "numeric" });
    if (v === "day") return d.toLocaleDateString(loc(), { weekday: "long", day: "numeric", month: "long", year: "numeric" });
    if (v === "agenda" && sameDay(s, new Date())) return T("Next 14 days", "Önümüzdeki 14 gün");
    return s.getMonth() === l.getMonth() ? s.getDate() + "–" + l.getDate() + " " + l.toLocaleDateString(loc(), { month: "long", year: "numeric" })
      : s.toLocaleDateString(loc(), { day: "numeric", month: "short" }) + " – " + l.toLocaleDateString(loc(), { day: "numeric", month: "short", year: "numeric" });
  }
  function pickCalView() {
    optionsSheet(T("Calendar view", "Takvim görünümü"), CAL_VIEWS.map(function (v) { return [v[0], T(v[1], v[2]), v[3]]; }), calView(), function (v) {
      S.mail.cal.view = v; call("mailx.settings.set", { key: "calView", value: v }).catch(function () {});
      renderMailShell(); loadCal();
    });
  }
  function calGo(delta) {
    var v = calView(), d = calDay();
    S.mail.cal.day = v === "month" ? new Date(d.getFullYear(), d.getMonth() + delta, 1) : calAdd(d, delta * (v === "day" ? 1 : 7));
    renderMailShell(); loadCal();
  }
  function calDrill(d, view) { S.mail.cal.day = calSod(d); S.mail.cal.view = view || "day"; renderMailShell(); loadCal(); }
  function miniMonthSheet() {
    var base = new Date(calDay().getFullYear(), calDay().getMonth(), 1), s = null;
    var box = h("div", { class: "mini" });
    function draw() {
      clear(box);
      var st = calSow(base), today = new Date(), r = calRange();
      box.appendChild(h("div", { class: "mh" }, h("button", { class: "ib sm", "aria-label": T("Previous month", "Önceki ay"), onclick: function () { base = new Date(base.getFullYear(), base.getMonth() - 1, 1); draw(); } }, icon("chevron-left", "s")),
        h("b", null, base.toLocaleDateString(loc(), { month: "long", year: "numeric" })),
        h("button", { class: "ib sm", "aria-label": T("Next month", "Sonraki ay"), onclick: function () { base = new Date(base.getFullYear(), base.getMonth() + 1, 1); draw(); } }, icon("chevron-right", "s"))));
      var g = h("div", { class: "mg" });
      for (var i = 0; i < 7; i++) g.appendChild(h("div", { class: "dn" }, calAdd(calSow(today), i).toLocaleDateString(loc(), { weekday: "narrow" })));
      for (var k = 0; k < 42; k++) (function (d) {
        g.appendChild(h("button", { class: (d.getMonth() !== base.getMonth() ? "out " : "") + (sameDay(d, today) ? "td " : "") + (d >= r[0] && d < r[1] && calView() !== "month" && calView() !== "agenda" ? "inr" : ""), onclick: function () { s.close(); S.mail.cal.day = d; if (calView() === "month" && d.getMonth() !== calDay().getMonth()) S.mail.cal.day = d; renderMailShell(); loadCal(); } }, String(d.getDate())));
      })(calAdd(st, k));
      box.appendChild(g);
    }
    draw();
    s = sheet({ title: T("Go to date", "Tarihe git"), body: box, actions: [[T("Today", "Bugün"), "", function () { S.mail.cal.day = calSod(new Date()); renderMailShell(); loadCal(); }]] });
  }
  function loadCal(force) {
    var list = $("#maillist"); if (!list) return;
    var r = calRange(), key = r[0].getTime() + "-" + r[1].getTime();
    if (!force && key === S.mail.cal.key && S.mail.cal.loaded) { renderCal(); return; }
    S.mail.cal.key = key;
    if (!list.firstChild || force || !S.mail.cal.loaded) { clear(list).appendChild(h("div", { class: "status", style: "padding:24px 16px" }, h("div", { class: "spin" }), T("Loading…", "Yükleniyor…"))); }
    call("mail.range", { start: r[0].getTime(), end: r[1].getTime() }).then(function (evs) {
      if (S.mail.cal.key !== key || S.mail.tab !== "cal") return;
      S.mail.cal.events = (evs || []).slice().sort(function (a, b) { return a.start - b.start; }); S.mail.cal.loaded = true;
      renderCal();
    }).catch(function (e) { if (S.mail.cal.key === key) { S.mail.cal.key = ""; mailBlocked(e.message); } });
  }
  var EV_COLORS = ["#0f6cbd", "#c239b3", "#0b6a0b", "#ca5010", "#8764b8", "#038387", "#d13438", "#986f0b", "#4f6bed", "#e3008c", "#00b7c3", "#637cef"];
  function evColor(e) {
    if (e.categories && e.categories.length) { var s = "cat:" + String(e.categories[0]).toLowerCase(), x = 0; for (var i = 0; i < s.length; i++) x = (x * 31 + s.charCodeAt(i)) | 0; return EV_COLORS[Math.abs(x) % EV_COLORS.length]; }
    return e.freeBusy === "OOF" ? "var(--warn)" : "var(--violet)";
  }
  function evClass(e, base) { var now = Date.now(); return base + (e.response === "Tentative" || e.freeBusy === "Tentative" ? " tent" : "") + (e.freeBusy === "Free" ? " free" : "") + (e.cancelled ? " canc" : "") + (e.response === "Decline" ? " decl" : "") + (e.end < now ? " past" : ""); }
  function evIsAll(e) { return e.allDay || (e.end - e.start >= 864e5 - 60000); }
  function renderCal() {
    var list = $("#maillist"); if (!list || S.mail.tab !== "cal") return;
    var meta = $("#mailmeta"), v = calView(), r = calRange(), evs = S.mail.cal.events;
    if (meta) { clear(meta); meta.appendChild(h("span", { class: "dot ok" })); var n = evs.filter(function (e) { return !e.cancelled; }).length; meta.appendChild(document.createTextNode(T(n + (n === 1 ? " event" : " events"), n + " etkinlik"))); }
    clear(list);
    if (v === "agenda") renderAgenda(evs);
    else if (v === "month") list.appendChild(monthGrid(r[0]));
    else {
      var g = weekGrid(r[0], Math.round((r[1] - r[0]) / 864e5));
      list.appendChild(g);
      /* scroll to the working day once per range (now − 2 h when today is in view) */
      var p = mailPageRec(), sk = S.mail.cal.key + v;
      if (p && S.mail.cal.scrollKey !== sk) {
        S.mail.cal.scrollKey = sk;
        var now = new Date(), inView = now >= r[0] && now < r[1], hr = inView ? Math.max(7, now.getHours() - 2) : 8;
        requestAnimationFrame(function () { var body = $(".wkb", g); if (body) p.body.scrollTop = Math.max(0, body.offsetTop + hr * CAL_HH - 8); });
      }
    }
  }
  function renderAgenda(evs) {
    var list = $("#maillist");
    evs = (evs || []).filter(function (e) { return !e.cancelled; });
    if (!evs.length) { list.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("calendar")), h("h3", null, T("Nothing scheduled", "Planlanmış bir şey yok")), h("p", null, T("Your calendar is clear for these two weeks.", "Bu iki hafta takviminiz boş.")), h("div", { class: "btnstack" }, h("button", { class: "btn", onclick: function () { eventEditSheet(null); } }, icon("plus", "s"), T("New event", "Yeni etkinlik"))))); return; }
    var lastDay = "", now = Date.now();
    evs.forEach(function (e) {
      var dk = new Date(e.start).toDateString();
      if (dk !== lastDay) { lastDay = dk; list.appendChild(h("div", { class: "day" }, dayLabel(e.start), h("small", null, new Date(e.start).toLocaleDateString(loc(), { day: "numeric", month: "long" })))); }
      var resp = e.response || "";
      var cls = "ev" + (e.end < now ? " past" : "") + (resp === "Tentative" ? " tent" : "") + (e.meeting && (resp === "NoResponseReceived" || resp === "Unknown") ? " none" : "");
      list.appendChild(h("button", { class: cls, style: "--c:" + evColor(e), onclick: function () { eventSheet(e); } },
        h("div", { class: "tm" }, e.allDay ? h("b", null, T("All day", "Tüm gün")) : [h("b", null, timeOf(e.start)), timeOf(e.end)]),
        h("div", { class: "eb" }, h("b", null, e.recurring ? "↻ " : "", e.subject), (e.location || e.organizer) ? h("span", null, [e.location, e.organizer].filter(Boolean).join(" — ")) : null,
          e.meeting && (resp === "NoResponseReceived" || resp === "Unknown") ? h("span", { class: "tag warn" }, T("Not answered", "Yanıtlanmadı")) : resp === "Tentative" ? h("span", { class: "tag warn" }, T("Tentative", "Belki")) : null)));
    });
  }
  function weekGrid(s, n) {
    var days = []; for (var i = 0; i < n; i++) days.push(calAdd(s, i));
    var today = new Date(), HH = CAL_HH, cols = "40px repeat(" + n + ", minmax(0,1fr))", evs = S.mail.cal.events;
    var head = h("div", { class: "wkh", style: "grid-template-columns:" + cols }, h("div"), days.map(function (d) {
      return h("button", { class: "dh" + (sameDay(d, today) ? " td" : ""), onclick: function () { if (n > 1) calDrill(d, "day"); } }, h("span", { class: "wd" }, d.toLocaleDateString(loc(), { weekday: n > 5 ? "narrow" : n > 1 ? "short" : "long" })), h("span", { class: "num" }, String(d.getDate())));
    }));
    var anyAll = evs.some(function (e) { return evIsAll(e) && !e.cancelled; });
    var allDay = anyAll ? h("div", { class: "wka", style: "grid-template-columns:" + cols }, h("div", { class: "gut" }, T("all day", "tüm gün")), days.map(function (d) {
      return h("div", { class: "ac" }, evs.filter(function (e) { return evIsAll(e) && !e.cancelled && e.start < calAdd(d, 1).getTime() && e.end > d.getTime(); }).map(function (e) {
        return h("button", { class: evClass(e, "wev ad"), style: "--c:" + evColor(e), onclick: function () { eventSheet(e); } }, h("b", null, e.subject));
      }));
    })) : null;
    var gut = h("div", { class: "wgut", style: "height:" + 24 * HH + "px" });
    for (var hr = 1; hr < 24; hr++) gut.appendChild(h("div", { class: "hl", style: "top:" + hr * HH + "px" }, new Date(2000, 0, 1, hr).toLocaleTimeString(loc(), { hour: "2-digit", minute: "2-digit" })));
    var cells = days.map(function (d) {
      var col = h("div", { class: "wday" + (d.getDay() === 0 || d.getDay() === 6 ? " wkend" : ""), style: "height:" + 24 * HH + "px;--hh:" + HH + "px" });
      /* a tap on an empty slot starts a new event there (half-hour steps) */
      col.addEventListener("click", function (ev) { if (ev.target !== col && !ev.target.classList.contains("off")) return; var rc = col.getBoundingClientRect(); var mins = Math.floor(((ev.clientY - rc.top) / HH) * 2) * 30; var st = new Date(d); st.setMinutes(Math.max(0, Math.min(23 * 60 + 30, mins))); eventEditSheet(null, st); });
      if (!(d.getDay() === 0 || d.getDay() === 6)) { col.appendChild(h("div", { class: "off", style: "top:0;height:" + 8 * HH + "px" })); col.appendChild(h("div", { class: "off", style: "top:" + 18 * HH + "px;height:" + 6 * HH + "px" })); }
      var day0 = d.getTime(), day1 = calAdd(d, 1).getTime();
      var list = evs.filter(function (e) { return !evIsAll(e) && e.start < day1 && e.end > day0; }).map(function (e) { var st = Math.max(0, (e.start - day0) / 60000), en = Math.min(1440, (e.end - day0) / 60000); return { e: e, st: st, en: Math.max(en, st + 20) }; })
        .sort(function (a, b) { return a.st - b.st || b.en - a.en; });
      /* side-by-side layout for overlapping events (clusters → columns) */
      var cluster = [], cEnd = -1;
      function flush() { var ends = []; cluster.forEach(function (x) { var k = -1; for (var j = 0; j < ends.length; j++) if (ends[j] <= x.st) { k = j; break; } if (k < 0) { k = ends.length; ends.push(0); } ends[k] = x.en; x.k = k; }); cluster.forEach(function (x) { x.n = ends.length; }); cluster = []; }
      list.forEach(function (x) { if (x.st >= cEnd && cluster.length) flush(); cluster.push(x); cEnd = Math.max(cEnd, x.en); }); if (cluster.length) flush();
      list.forEach(function (x) {
        var e = x.e, hgt = (x.en - x.st) / 60 * HH;
        col.appendChild(h("button", { class: evClass(e, "wev"), style: "--c:" + evColor(e) + ";top:" + (x.st / 60 * HH + 1) + "px;height:" + Math.max(hgt - 2, 18) + "px;left:calc(" + (x.k / x.n * 100) + "% + 1px);width:calc(" + (100 / x.n) + "% - 3px)", "aria-label": e.subject + " " + timeOf(e.start) + "–" + timeOf(e.end), onclick: function (ev) { ev.stopPropagation(); eventSheet(e); } },
          h("b", { class: hgt < 36 || x.n > 1 && n > 1 ? "one" : "" }, e.recurring ? "↻ " : "", e.subject), hgt >= 40 ? h("span", null, timeOf(e.start) + "–" + timeOf(e.end)) : null, hgt >= 60 && e.location ? h("span", null, e.location) : null));
      });
      if (sameDay(d, today)) col.appendChild(h("div", { class: "wnow", style: "top:" + (today.getHours() * 60 + today.getMinutes()) / 60 * HH + "px" }));
      return col;
    });
    return h("div", { class: "wk" }, head, allDay, h("div", { class: "wkb", style: "grid-template-columns:" + cols }, gut, cells));
  }
  function monthGrid(s) {
    var base = calDay().getMonth(), today = new Date(), evs = S.mail.cal.events;
    var hd = h("div", { class: "monh" }); for (var i = 0; i < 7; i++) hd.appendChild(h("div", null, calAdd(s, i).toLocaleDateString(loc(), { weekday: "short" })));
    var grid = h("div", { class: "mon" }, hd);
    for (var r = 0; r < 6; r++) {
      var row = h("div", { class: "monr" });
      for (var c = 0; c < 7; c++) (function (d) {
        var d0 = d.getTime(), d1 = calAdd(d, 1).getTime();
        var list = evs.filter(function (e) { return !e.cancelled && e.start < d1 && e.end > d0 && e.end !== d0; });
        var MAX = 2;
        row.appendChild(h("button", { class: "monc" + (d.getMonth() !== base ? " out" : "") + (sameDay(d, today) ? " td" : ""), "aria-label": d.toLocaleDateString(loc(), { weekday: "long", day: "numeric", month: "long" }) + (list.length ? " · " + list.length : ""), onclick: function () { calDrill(d, "day"); } },
          h("span", { class: "dn" }, d.getDate() === 1 ? d.toLocaleDateString(loc(), { day: "numeric", month: "short" }) : String(d.getDate())),
          list.slice(0, MAX).map(function (e) { return h("span", { class: evClass(e, "me") + (e.allDay ? " adb" : ""), style: "--c:" + evColor(e) }, e.allDay ? null : h("i"), h("span", null, e.subject)); }),
          list.length > MAX ? h("span", { class: "more" }, "+" + (list.length - MAX)) : null));
      })(calAdd(s, r * 7 + c));
      grid.appendChild(row);
    }
    return grid;
  }

  /* event details (original popNode): when, where, join link, organizer and attendees with their answers, description; answer / prepare / edit / delete */
  var RS_LABEL = { Accept: ["Accepted", "Kabul etti"], Decline: ["Declined", "Reddetti"], Tentative: ["Tentative", "Belki"], NoResponseReceived: ["No response", "Yanıt yok"], Unknown: ["No response", "Yanıt yok"], Organizer: ["Organizer", "Düzenleyen"] };
  function rsLabel(r) { var x = RS_LABEL[r]; return x ? T(x[0], x[1]) : r; }
  function eventWhen(e) {
    var s = new Date(e.start), en = new Date(e.end);
    return e.allDay ? s.toLocaleDateString(loc(), { weekday: "long", day: "numeric", month: "long", year: "numeric" }) + (en - s > 864e5 ? " – " + calAdd(en, -1).toLocaleDateString(loc(), { day: "numeric", month: "long" }) : "") + T(" · all day", " · tüm gün")
      : s.toLocaleDateString(loc(), { weekday: "long", day: "numeric", month: "long", year: "numeric" }) + " · " + timeOf(s) + "–" + timeOf(en);
  }
  function eventSheet(e) {
    var body = h("div", { class: "evd" });
    var s = sheet({ title: e.subject, body: body });
    function kv(ic, kids) { return h("div", { class: "kv" }, icon(ic, "s"), h("div", null, kids)); }
    function draw(d, loadingMore) {
      clear(body);
      var org = d.response === "Organizer", meeting = !!d.meeting;
      var ppl = (d.required || []).map(function (p) { return { p: p, opt: false }; }).concat((d.optional || []).map(function (p) { return { p: p, opt: true }; }));
      var join = joinLink(d.location || "", d.html || "", d.text || "");
      if (d.cancelled) body.appendChild(h("div", { class: "tag bad" }, T("Canceled", "İptal edildi")));
      body.appendChild(kv("clock", [eventWhen(d), d.recurring ? h("small", null, T("↻ Recurring event", "↻ Yinelenen etkinlik")) : null]));
      if (d.location) body.appendChild(kv("map-pin", d.location));
      if (join) body.appendChild(kv("video", h("button", { class: "btn sm p", onclick: function () { call("open.link", { url: join }); } }, icon("video", "xs"), T("Join meeting", "Toplantıya katıl"))));
      var orgName = d.organizer ? (typeof d.organizer === "string" ? d.organizer : mailWho(d.organizer)) : "";
      if (meeting && orgName) body.appendChild(kv("users", h("div", { class: "ppl" },
        h("div", { class: "pr" }, avatar(orgName, d.organizer && d.organizer.email), h("span", { class: "pn" }, h("b", null, orgName), T(" · organizer", " · düzenleyen"))),
        ppl.map(function (x) { return h("div", { class: "pr" }, avatar(mailWho(x.p), x.p.email), h("span", { class: "pn" }, mailWho(x.p), x.opt ? h("small", null, T(" (optional)", " (isteğe bağlı)")) : null), x.p.response ? h("span", { class: "rs " + x.p.response }, rsLabel(x.p.response)) : null); }))));
      if (meeting && !org && d.response) body.appendChild(kv("check", [T("Your response: ", "Yanıtınız: "), h("b", null, rsLabel(d.response))]));
      if (loadingMore) body.appendChild(h("div", { class: "status" }, h("div", { class: "spin" }), T("Loading…", "Yükleniyor…")));
      if (d.html && d.text) body.appendChild(h("div", { class: "mframew" }, mailFrame(d.html, { images: S.mail.images })));
      else if (d.text) { var tb = h("div", { class: "mbody sm" }); linkify(tb, d.text); body.appendChild(tb); }
      if (meeting && !org && !d.cancelled) body.appendChild(h("div", { class: "btnrow evr" },
        h("button", { class: "btn sm", onclick: function () { evRespond(d, "decline", s); } }, T("Decline", "Reddet")),
        h("button", { class: "btn sm", onclick: function () { evRespond(d, "tentative", s); } }, T("Tentative", "Belki")),
        h("button", { class: "btn sm p", onclick: function () { evRespond(d, "accept", s); } }, T("Accept", "Kabul"))));
      var full = !loadingMore;
      body.appendChild(h("div", { class: "btnrow evt" },
        h("button", { class: "btn sm ghost", onclick: function () { s.close(); prepareMeeting(d); } }, icon("sparkles", "xs"), T("Prepare", "Hazırlan")),
        full && (!meeting || org) && !d.cancelled ? h("button", { class: "btn sm ghost", onclick: function () { s.close(); eventEditSheet(d); } }, icon("pencil", "xs"), T("Edit", "Düzenle")) : null,
        full ? h("button", { class: "btn sm ghost dtext", onclick: function () { removeEvent(d, s); } }, icon("trash-2", "xs"), org && meeting ? T("Cancel meeting", "Toplantıyı iptal et") : meeting ? T("Remove", "Kaldır") : T("Delete", "Sil")) : null));
    }
    draw(e, true);
    call("mail.event.get", { id: e.id }).then(function (d) { if (!s.closed) draw(d, false); }).catch(function (err) { draw(e, false); body.insertBefore(h("div", { class: "mnote warn" }, icon("triangle-alert", "s"), h("span", null, errText(err))), body.firstChild); });
    return s;
  }
  function evRespond(d, r, s) {
    call("mail.respond", { id: d.id, response: r }).then(function () {
      s.close(); toast({ accept: T("Accepted", "Kabul edildi"), tentative: T("Tentatively accepted", "Belki olarak yanıtlandı"), decline: T("Declined", "Reddedildi") }[r] + T(" — the organizer was notified", " — düzenleyene bildirildi"));
      loadCal(true);
    }).catch(function (e) { toast(e.message); });
  }
  function respond(id, r) {
    call("mail.respond", { id: id, response: r }).then(function () { toast(T("Response sent", "Yanıt gönderildi")); loadMail(); }).catch(function (e) { toast(e.message); });
  }
  function removeEvent(d, s) {
    var org = d.response === "Organizer" && d.meeting;
    confirmSheet(org ? T("Cancel this meeting?", "Toplantı iptal edilsin mi?") : T("Delete this event?", "Etkinlik silinsin mi?"), org ? T("Cancel this meeting and notify the attendees?", "Toplantı iptal edilip katılımcılara bildirilsin mi?") : T("Delete this event from your calendar?", "Bu etkinlik takviminizden silinsin mi?"), org ? T("Cancel meeting", "Toplantıyı iptal et") : T("Delete", "Sil"), function () {
      call("mail.event.delete", { id: d.id, cancel: org }).then(function () { if (s) s.close(); toast(org ? T("Meeting cancelled", "Toplantı iptal edildi") : T("Event deleted", "Etkinlik silindi")); loadCal(true); }).catch(function (e) { toast(e.message); });
    });
  }
  function prepareMeeting(e) {
    var when = eventWhen(e), org = e.organizer ? mailWho(e.organizer) : "";
    askYaver(T("Help me prepare for the meeting \"" + e.subject + "\" on " + when + (org ? ", organized by " + org : "") + ". Look for related emails with the mail tool (search) and give me a short briefing: purpose, open questions, what I should bring.",
      when + " tarihindeki \"" + e.subject + "\" toplantısına" + (org ? " (düzenleyen: " + org + ")" : "") + " hazırlanmama yardım et. mail aracıyla (search) ilgili e-postaları bul ve kısa bir brifing ver: amaç, açık sorular, yanımda ne getirmeliyim."),
      T("Prepare for “" + e.subject + "”", "“" + e.subject + "” toplantısına hazırlan"));
  }
  /* new / edit event (original dlgNode): title, attendees, optional attendees, date, start–end or all day, location, description */
  function eventEditSheet(d, start) {
    var pad = function (n) { return String(n).padStart(2, "0"); };
    var ds = function (x) { return x.getFullYear() + "-" + pad(x.getMonth() + 1) + "-" + pad(x.getDate()); }, ts = function (x) { return pad(x.getHours()) + ":" + pad(x.getMinutes()); };
    var s0 = d ? new Date(d.start) : start ? new Date(start) : (function () { var day = calDay(); if (!sameDay(day, new Date())) { var y = new Date(day); y.setHours(9, 0, 0, 0); return y; } var x = new Date(); x.setMinutes(x.getMinutes() < 30 ? 30 : 60, 0, 0); return x; })();
    var e0 = d ? new Date(d.end) : new Date(s0.getTime() + 30 * 60000);
    var spanDays = d && d.allDay ? Math.max(1, Math.round((e0 - s0) / 864e5)) : 1;
    var ti = h("input", { type: "text", placeholder: T("Add a title", "Başlık ekleyin") }); ti.value = d ? d.subject : "";
    var at = h("input", { type: "text", inputmode: "email", autocapitalize: "off", autocomplete: "off", spellcheck: "false", placeholder: T("Attendees (e-mails, separated by ;)", "Katılımcılar (e-postalar, ; ile)") });
    var op = h("input", { type: "text", inputmode: "email", autocapitalize: "off", autocomplete: "off", spellcheck: "false", placeholder: T("Optional attendees", "İsteğe bağlı katılımcılar") });
    if (d) { at.value = (d.required || []).map(function (p) { return p.email || p.name; }).filter(Boolean).join("; "); op.value = (d.optional || []).map(function (p) { return p.email || p.name; }).filter(Boolean).join("; "); }
    var da = h("input", { type: "date" }); da.value = ds(s0);
    var st = h("input", { type: "time", step: 900 }); st.value = ts(s0);
    var en = h("input", { type: "time", step: 900 }); en.value = ts(e0);
    var ad = h("input", { type: "checkbox" }); ad.checked = !!(d && d.allDay);
    var lo = h("input", { type: "text", placeholder: T("Add a location or a meeting link", "Yer ya da toplantı bağlantısı ekleyin") }); lo.value = d ? d.location || "" : "";
    var de = h("textarea", { rows: 3, class: "sm", placeholder: T("Add a description", "Açıklama ekleyin") }); de.value = d ? d.text || "" : "";
    var times = h("div", { class: "f2" }, h("label", { class: "field" }, h("span", null, T("Start", "Başlangıç")), st), h("label", { class: "field" }, h("span", null, T("End", "Bitiş")), en));
    function sync() { times.classList.toggle("hidden", ad.checked); }
    ad.addEventListener("change", sync); sync();
    /* keep the length when the start moves */
    st.addEventListener("change", function () { var a = st.value.split(":").map(Number), b = en.value.split(":").map(Number), dur = (e0 - s0) / 60000; if (isNaN(dur) || dur <= 0) dur = 30; var m2 = a[0] * 60 + a[1] + dur; if (m2 < 24 * 60) en.value = pad(Math.floor(m2 / 60)) + ":" + pad(m2 % 60); void b; });
    var s = null, busy = false;
    function label() { return addrList(at.value).length || addrList(op.value).length ? T("Send", "Gönder") : T("Save", "Kaydet"); }
    function save() {
      if (busy) return false;
      if (!ti.value.trim()) { toast(T("Add a title", "Bir başlık ekleyin")); ti.focus(); return false; }
      var p = da.value.split("-").map(Number); if (p.length < 3 || isNaN(p[0])) { toast(T("Pick a date", "Bir tarih seçin")); return false; }
      var sd, ed;
      if (ad.checked) { sd = new Date(p[0], p[1] - 1, p[2]); ed = calAdd(sd, spanDays); }
      else { var a = st.value.split(":").map(Number), b = en.value.split(":").map(Number); sd = new Date(p[0], p[1] - 1, p[2], a[0] || 0, a[1] || 0); ed = new Date(p[0], p[1] - 1, p[2], b[0] || 0, b[1] || 0); if (ed <= sd) ed = new Date(sd.getTime() + 30 * 60000); }
      var att = addrList(at.value), opt = addrList(op.value);
      busy = true; s.el.classList.add("busy");
      call(d ? "mail.event.update" : "mail.event.create", { id: d ? d.id : undefined, subject: ti.value.trim(), start: sd.getTime(), end: ed.getTime(), allDay: ad.checked, location: lo.value.trim(), body: de.value, attendees: att, optional: opt }).then(function () {
        busy = false; s.close();
        toast(d ? (att.length || opt.length ? T("Meeting updated — attendees were notified", "Toplantı güncellendi — katılımcılara bildirildi") : T("Event updated", "Etkinlik güncellendi")) : (att.length || opt.length ? T("Meeting created and invitations sent", "Toplantı oluşturuldu, davetler gönderildi") : T("Event saved", "Etkinlik kaydedildi")));
        S.mail.cal.day = calSod(sd);
        if (topPage() === "mail" && S.mail.tab === "cal") { renderMailShell(); loadCal(true); } else S.mail.cal.key = "";
      }).catch(function (e) { busy = false; s.el.classList.remove("busy"); toast(e.message); });
      return false;
    }
    var okBtn = null;
    s = sheet({ title: d ? T("Edit event", "Etkinliği düzenle") : T("New event", "Yeni etkinlik"), body: h("div", null,
      h("label", { class: "field" }, h("span", null, T("Title", "Başlık")), ti),
      h("div", { class: "field" }, h("span", null, T("Attendees", "Katılımcılar")), at, suggestPeople(at)),
      h("div", { class: "field" }, h("span", null, T("Optional", "İsteğe bağlı")), op, suggestPeople(op)),
      h("label", { class: "field" }, h("span", null, T("Date", "Tarih")), da),
      h("label", { class: "chkrow" }, ad, h("span", null, T("All day", "Tüm gün"))),
      times,
      h("label", { class: "field" }, h("span", null, T("Location", "Yer")), lo),
      h("label", { class: "field" }, h("span", null, T("Description", "Açıklama")), de),
      d && d.meeting ? h("div", { class: "qnote" }, icon("info", "xs"), T("Attendees get the updated invitation.", "Katılımcılara güncellenmiş davet gider.")) : null),
      actions: [[T("Cancel", "İptal")], [label(), "p", save]] });
    okBtn = s.el.querySelector(".sf .btn.p");
    function relabel() { if (okBtn) okBtn.textContent = label(); }
    at.addEventListener("input", relabel); op.addEventListener("input", relabel);
    if (!d) setTimeout(function () { ti.focus(); }, 260);
    return s;
  }

  /* agent and background events about mail */
  function onMailEvent(type, d) {
    if (type === "mail.draft" && d && d.id) {
      toast(T("Draft saved: ", "Taslak kaydedildi: ") + (d.subject || "") + " — " + T("Open", "Aç"), function () { openMail(d.id, { subject: d.subject }, { folder: "drafts", edit: true }); }, 8000);
      if (topPage() === "mail" && S.mail.folder === "drafts") loadMail(true);
    } else if (type === "mail.triage") {
      if (topPage() !== "mail" || S.mail.tab !== "mail" || S.mail.results) return;
      call("mailx.triage").then(function (tr) { var ch = false; S.mail.items.forEach(function (m) { if (tr && tr[m.id] && !m.triage) { m.triage = tr[m.id]; ch = true; } }); if (ch) renderMailList(); }).catch(function () {});
    } else if (type === "mail.index") loadIndexState();
  }

  /* ------------------------------------------------------------ settings */
  function refreshState() { return call("state").then(function (s) { S.state = s; S.lang = s.lang; return s; }); }
  function setCfg(key, value) { return call("cfg.set", { key: key, value: value }).then(function (s) { S.state = s; S.lang = s.lang; if (topPage() === "settings") renderSettings(); return s; }).catch(function (e) { toast(e.message); if (topPage() === "settings") renderSettings(); return null; }); }

  function openSettings(focus) {
    closeDrawer();
    if (topPage() === "settings") { if (focus === "learn") { S.settingsFocus = "learn"; renderExtras(); } return; }
    pushPage("settings", T("Settings", "Ayarlar"), function () { refreshState().then(function () { renderSettings(focus); }); });
  }

  function row(o) {
    var b = h(o.toggle != null ? "div" : "button", { class: "row", onclick: o.toggle != null ? function () { var on = !sw.classList.contains("on"); sw.classList.toggle("on", on); b.setAttribute("aria-checked", String(on)); o.onToggle(on); } : o.onclick, role: o.toggle != null ? "switch" : null, "aria-checked": o.toggle != null ? String(!!o.toggle) : null },
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
      row({ icon: "languages", label: T("Answer language", "Cevap dili"), value: { auto: T("Like my message", "Mesajım gibi"), tr: "Türkçe", en: "English" }[st.langSetting] || st.langSetting, onclick: function () { optionsSheet(T("Answer language", "Cevap dili"), [["auto", T("Same as my message", "Mesajımla aynı")], ["tr", "Türkçe"], ["en", "English"]], st.langSetting, function (v) { setCfg("lang", v).then(function (st2) { if (!st2) return; document.documentElement.lang = S.lang; renderShell(); S.pages.forEach(function (pg) { if (pg.name === "settings") pg.title.firstChild.textContent = T("Settings", "Ayarlar"); }); }); }); } }),
      row({ icon: "user", label: T("Personality", "Kişilik"), sub: st.persona ? st.persona : T("Default", "Varsayılan"), onclick: function () { editSheet({ title: T("Personality", "Kişilik"), text: T("Who yaver is and how it talks. Leave empty for the default.", "yaver kimdir ve nasıl konuşur. Varsayılan için boş bırakın."), multi: true, value: st.persona, placeholder: st.personaDefault, save: function (v) { setCfg("persona", v); } }); } }),
      window.YVP ? YVP.permRow() : row({ icon: "shield-check", label: T("Ask before actions", "İşlemlerden önce sor"), sub: T("Deleting, moving, overwriting. Sending mail is always confirmed.", "Silme, taşıma, üzerine yazma. E-posta gönderimi her zaman onaylatılır."), wrap: true, toggle: st.askRisky, onToggle: function (on) { setCfg("askRisky", on); } }),
      row({ icon: "brain", label: T("Memory", "Hafıza"), sub: T("What yaver knows about you", "yaver'in sizin hakkınızda bildikleri"), onclick: function () { openKnowledge(); } }),
      row({ icon: "square-kanban", label: T("Tasks", "Görevler"), sub: T("Your work task board", "İş görev panonuz"), value: S.overdue ? T(S.overdue + " overdue", S.overdue + " gecikmiş") : "", onclick: function () { openTasks(); } }),
      row({ icon: "clock", label: T("Reminders and scheduled tasks", "Hatırlatmalar ve zamanlanmış görevler"), onclick: openSchedule })
    ]));
    wrap.appendChild(h("div", { id: "s-extras" }));
    wrap.appendChild(h("div", { id: "s-parity" }));
    if (window.YVP) YVP.settings(wrap);

    var mailGroup = group(T("Company mail", "Kurum postası"), [
      row({ icon: "globe", label: T("Outlook web address", "Outlook web adresi"), sub: M.owa || T("e.g. https://mail.company.com/owa", "ör. https://eposta.sirket.com.tr/owa"), onclick: function () { editSheet({ title: T("Outlook web address", "Outlook web adresi"), value: M.owa, type: "url", placeholder: "https://eposta.sirket.com.tr/owa", help: T("The address you open Outlook on the web with.", "Outlook'u web'de açtığınız adres."), save: function (v) { setCfg("mailOwa", v.trim()); } }); } }),
      row({ icon: "user", label: T("User name", "Kullanıcı adı"), sub: M.user || T("DOMAIN\\user or e-mail address", "ETKİALANI\\kullanıcı veya e-posta"), onclick: function () { editSheet({ title: T("User name", "Kullanıcı adı"), value: M.user, placeholder: "ETKİALANI\\kullanici", save: function (v) { setCfg("mailUser", v.trim()); } }); } }),
      row({ icon: "lock", label: T("Password", "Şifre"), value: masked(M.hasPass), onclick: function () { editSheet({ title: T("Mail password", "Posta şifresi"), secret: true, help: T("Stored encrypted on this phone and filled into the sign-in page for you.", "Bu telefonda şifreli saklanır ve oturum açma sayfasına sizin yerinize doldurulur."), save: function (v) { if (v) setCfg("mailPass", v); } }); } }),
      row({ icon: "shield-check", label: T("Authenticator", "Doğrulayıcı"), sub: M.hasTotp ? T("Codes are filled in automatically", "Kodlar otomatik dolduruluyor") : T("Optional: let yaver fill in the 6-digit code", "İsteğe bağlı: 6 haneli kodu yaver doldursun"), onclick: totpSheet }),
      row({ icon: "zap", label: T("Sign in automatically", "Otomatik oturum aç"), sub: T("Fill in and submit the sign-in page — and sign in again by itself when the session ends", "Oturum açma sayfasını doldur ve gönder — oturum düşünce kendiliğinden yeniden aç"), wrap: true, toggle: M.autoSubmit, onToggle: function (on) { setCfg("mailAutoSubmit", on); } }),
      row({ icon: "bell", label: T("New mail notifications", "Yeni posta bildirimi"), sub: T("Checks every " + M.poll + " min — this also keeps the session open", "Her " + M.poll + " dakikada kontrol — bu oturumu da açık tutar"), toggle: M.notify, onToggle: function (on) { setCfg("mailNotify", on); } }),
      row({ icon: "clock", label: T("Check every", "Kontrol sıklığı"), value: M.poll + T(" min", " dk"), onclick: function () { optionsSheet(T("Check for new mail every", "Yeni postayı kontrol et"), [["1", "1"], ["3", "3"], ["5", "5"], ["10", "10"], ["15", "15"], ["30", "30"]].map(function (x) { return [x[0], x[1] + T(" minutes", " dakika")]; }), String(M.poll), function (v) { setCfg("mailPoll", v); }); } }),
      row({ icon: "server", label: T("EWS address", "EWS adresi"), sub: M.ews || T("Optional — only if IT gave you one", "İsteğe bağlı — BT verdiyse"), onclick: function () { editSheet({ title: T("EWS address", "EWS adresi"), value: M.ews, type: "url", placeholder: "https://mail.sirket.com.tr/EWS/Exchange.asmx", save: function (v) { setCfg("mailEws", v.trim()); } }); } }),
      row({ icon: "circle-check", label: T("Test the mail connection", "Posta bağlantısını sına"), sub: T("Tries every mail feature yaver uses once and shows what works — share the result if something fails", "yaver'in kullandığı her posta özelliğini bir kez dener ve neyin çalıştığını gösterir — bir şey başarısızsa sonucu paylaşın"), wrap: true, onclick: mailSelfTest }),
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
      row({ icon: "search", label: "Brave Search", value: masked(W.hasBrave), onclick: function () { keySheet("Brave Search API", "braveKey", W.hasBrave); } }),
      row({ icon: "search", label: "Tavily", value: masked(W.hasTavily), onclick: function () { keySheet("Tavily API", "tavilyKey", W.hasTavily); } }),
      row({ icon: "globe", label: "SearXNG", sub: W.searxng || T("Your own search server", "Kendi arama sunucunuz"), onclick: function () { editSheet({ title: "SearXNG", value: W.searxng, type: "url", save: function (v) { setCfg("searxng", v.trim()); } }); } })
    ], T("Search works without keys (DuckDuckGo, Bing, Mojeek from the phone). Keys make it faster and more reliable.", "Arama anahtarsız çalışır (telefondan DuckDuckGo, Bing, Mojeek). Anahtarlar daha hızlı ve güvenilir yapar.")));

    wrap.appendChild(group(T("Data", "Veri"), [
      window.YVP ? YVP.exportRow() : null,
      window.YVP ? YVP.importRow() : row({ icon: "upload", label: T("Import a yaver export", "yaver dışa aktarımını içe al"), sub: ".zip", onclick: function () { call("import.zip").then(function (m) { if (m) toast(m); refreshConvos(); }).catch(function (e) { toast(e.message); }); } }),
      row({ icon: "folder", label: T("Files", "Dosyalar"), sub: T("Attachments, downloads, files yaver made, its skills — browse, open, share", "Ekler, indirilenler, yaver'in oluşturduğu dosyalar, yetenekleri — göz atın, açın, paylaşın"), onclick: function () { openFiles("/workspace"); } }),
      row({ icon: "log-in", label: T("Sign out everywhere", "Her yerden çıkış yap"), sub: T("Clears the mail session and website sign-ins", "Posta oturumunu ve site girişlerini temizler"), onclick: function () { sheet({ title: T("Sign out everywhere?", "Her yerden çıkılsın mı?"), actions: [[T("Cancel", "İptal")], [T("Sign out", "Çıkış yap"), "danger", function () { call("cookies.clear").then(function () { refreshState().then(renderSettings); toast(T("Signed out", "Çıkış yapıldı")); }); }]] }); } }),
      row({ icon: "file-text", label: T("Diagnostic log", "Tanılama günlüğü"), sub: T("What yaver did: mail sign-in, connections, tools, errors. No passwords.", "yaver'in yaptıkları: posta oturumu, bağlantılar, araçlar, hatalar. Şifre içermez."), wrap: true, onclick: diagSheet }),
      row({ icon: "info", label: T("Last crash report", "Son çökme raporu"), onclick: function () { call("crash.last").then(function (t) { sheet({ title: T("Last crash report", "Son çökme raporu"), body: h("div", { class: "res", style: "display:block;white-space:pre-wrap;font-size:12px;max-height:50vh;overflow:auto" }, t || T("No crash recorded.", "Kayıtlı çökme yok.")), actions: t ? [[T("Copy", "Kopyala"), "p", function () { call("copy", { text: t }); }], [T("Close", "Kapat")]] : [[T("Close", "Kapat")]] }); }); } })
    ], "yaver " + st.version));
    if (focus === "mail") setTimeout(function () { var el = document.getElementById("s-mail"); if (el) el.scrollIntoView(); }, 300);
    if (focus === "learn") S.settingsFocus = "learn";
    if (S.x) renderExtras();
    loadExtras();
  }

  /* a secret: an empty Save keeps the stored key; "Remove" clears it */
  function keySheet(title, key, has) {
    editSheet({ title: title, secret: true, placeholder: has ? "••••••••" : "", help: T("Stored encrypted on this phone.", "Bu telefonda şifreli saklanır."),
      save: function (v) { if (v.trim()) setCfg(key, v.trim()).then(function (st) { if (st) toast(T("Saved", "Kaydedildi")); }); },
      extra: has ? [[T("Remove", "Kaldır"), "", function () { setCfg(key, ""); }]] : [] });
  }
  /* the mail self-test: every step live, then copy/share */
  function mailSelfTest() {
    var list = h("div", { class: "stest" }, h("div", { class: "status" }, h("div", { class: "spin" }), T("Starting…", "Başlıyor…")));
    function draw(rows) {
      clear(list);
      (rows || []).forEach(function (r, i) {
        var last = i === rows.length - 1 && !S.stDone;
        list.appendChild(h("div", { class: "strow " + (r.ok ? "ok" : (last && !r.note ? "run" : "bad")) },
          last && !r.note && !r.ok ? h("div", { class: "spin" }) : icon(r.ok ? "circle-check" : "circle-x", "s"),
          h("div", null, h("b", null, r.name), r.note ? h("small", null, r.note) : null)));
      });
    }
    S.stDone = false; S.stDraw = draw;
    var text = "";
    var sh = sheet({ title: T("Mail connection test", "Posta bağlantı testi"), text: T("This takes about half a minute. A test draft is created and deleted again.", "Yaklaşık yarım dakika sürer. Bir deneme taslağı oluşturulup yeniden silinir."), body: list,
      actions: [[T("Copy result", "Sonucu kopyala"), "", function () { if (text) { call("copy", { text: text }); toast(T("Copied", "Kopyalandı")); } return false; }], [T("Share log", "Günlüğü paylaş"), "p", function () { shareLog(); }]] });
    call("mailx.diag").then(function (r) { S.stDone = true; text = r.text || ""; draw(r.rows); var bad = (r.rows || []).filter(function (x) { return !x.ok; }).length; toast(bad ? T(bad + " step(s) failed", bad + " adım başarısız") : T("All steps work", "Tüm adımlar çalışıyor")); })
      .catch(function (e) { S.stDone = true; clear(list).appendChild(h("div", { class: "err" }, icon("triangle-alert", "s"), h("div", null, String(e.message || e).replace(/^SIGNIN:/, "")))); });
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
      h("button", { class: "opt", onclick: function () { s.close(); if (!S.convoId || S.convoId === "_new") { toast(T("The context is already empty", "Bağlam zaten boş")); return; } runCommand("/clear"); } }, icon("eraser", "s"), h("div", null, T("Clear the context", "Bağlamı temizle"), h("small", null, T("yaver forgets the earlier messages; they stay visible", "yaver önceki mesajları unutur; ekranda kalırlar")))),
      h("button", { class: "opt", onclick: function () { s.close(); exportConvo(); } }, icon("download", "s"), h("div", null, T("Export this chat", "Bu sohbeti dışa aktar"), h("small", null, T("Readable copy + full record for troubleshooting", "Okunabilir kopya + sorun giderme için tam kayıt")))),
      h("button", { class: "opt", onclick: function () { s.close(); diagSheet(); } }, icon("file-text", "s"), h("div", null, T("Diagnostic log", "Tanılama günlüğü"))),
      window.YVP ? YVP.chatMenuItems(function () { s.close(); }) : null,
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
    var q = h("input", { type: "search", placeholder: T("Filter: free, gpt, claude, gemini…", "Süz: free, gpt, claude, gemini…"), "aria-label": T("Filter models", "Modelleri süz") });
    var all = [], freeOnly = false;
    var count = h("small", { class: "muted mcount" });
    var fchip = h("button", { class: "cchip", "aria-pressed": "false", onclick: function () { freeOnly = !freeOnly; fchip.classList.toggle("on", freeOnly); fchip.setAttribute("aria-pressed", String(freeOnly)); draw(); } }, icon("badge-check", "xs"), h("span", null, T("Free only", "Yalnızca ücretsiz")));
    var s = sheet({ title: T("Choose a model", "Model seçin"), body: h("div", null, h("label", { class: "search" }, icon("search", "xs"), q), h("div", { class: "dbgbar" }, fchip, count), list), actions: allowEmpty ? [[T("Use the main model", "Ana modeli kullan"), "", function () { setCfg(key, ""); }]] : [] });
    function isFree(m) { return !!m.free || /:free$/i.test(m.id); }
    function ctxLabel(n) { return n >= 1e6 ? (n / 1e6).toFixed(n % 1e6 ? 1 : 0) + "M" : Math.round(n / 1000) + "k"; }
    function draw() {
      clear(list);
      var words = q.value.toLowerCase().trim().split(/\s+/).filter(Boolean);
      var cur = S.state.llm[key] || "";
      var match = function (m) { return (!freeOnly || isFree(m)) && words.every(function (w) { return w === "free" || w === "ücretsiz" ? isFree(m) : m.id.toLowerCase().indexOf(w) >= 0 || String(m.name || "").toLowerCase().indexOf(w) >= 0; }); };
      var hit = all.filter(match), shown = hit.slice(0, 300);
      var nf = all.filter(isFree).length;
      count.textContent = all.length ? (hit.length !== all.length ? hit.length + " / " : "") + all.length + T(" models", " model") + (nf ? " · " + nf + T(" free", " ücretsiz") : "") : "";
      if (cur && !hit.some(function (m) { return m.id === cur; }) && !words.length && !freeOnly) shown.unshift({ id: cur, current: true });
      if (!shown.length) list.appendChild(h("div", { class: "status" }, T("No model matches.", "Eşleşen model yok.")));
      shown.forEach(function (m) {
        var id = m.id, prov = id.indexOf("/") > 0 ? id.split("/")[0] : "";
        var meta = [prov, m.ctx ? ctxLabel(m.ctx) + T(" context", " bağlam") : "", m.name && m.name !== id ? m.name : ""].filter(Boolean).join(" · ");
        list.appendChild(h("button", { class: "opt", onclick: function () { s.close(); setCfg(key, id).then(function (st) { if (!st) return; toast(T("Model: ", "Model: ") + id); if (topPage() === "setup") renderSetup(); }); } },
          h("div", null, id.split("/").pop(), meta ? h("small", null, meta) : null, isFree(m) ? h("span", { class: "bdg ok mfree" }, T("free", "ücretsiz")) : null), (cur === id) ? icon("check", "s c") : null));
      });
    }
    q.addEventListener("input", draw);
    call("models.info").catch(function () { return call("models").then(function (ids) { return (ids || []).map(function (id) { return { id: id, ctx: 0, free: /:free$/i.test(id) }; }); }); }).then(function (m) { all = m || []; draw(); }).catch(function (e) {
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
    if (window.YVP) return YVP.skillsView(box, list);
    box.appendChild(h("p", { class: "kexpl" }, T("Procedures yaver saved to reuse: each skill is a folder with instructions (SKILL.md) and helper files. yaver opens the right one when a task matches.", "yaver'in tekrar kullanmak için kaydettiği yöntemler: her yetenek talimatlar (SKILL.md) ve yardımcı dosyalar içeren bir klasördür. Görev uyduğunda yaver doğru olanı açar.")));
    if (!list.length) { box.appendChild(h("div", { class: "kempty" }, icon("graduation-cap"), T("No skills yet. Ask yaver to “save this as a skill” after it did something you want repeated.", "Henüz yetenek yok. Tekrarlanmasını istediğiniz bir işten sonra yaver'e “bunu yetenek olarak kaydet” deyin."))); return; }
    var lb = h("div", { class: "klist" });
    list.forEach(function (s) {
      lb.appendChild(h("button", { class: "kcard", onclick: function () {
        sheet({ title: s.name, body: h("div", null, h("p", { class: "kquote" }, s.description || "—"), h("dl", { class: "dl" }, h("dt", null, T("Folder", "Klasör")), h("dd", null, s.dir || ""), h("dt", null, T("Files", "Dosyalar")), h("dd", null, (s.files || []).join("\n") || "SKILL.md"))),
          actions: [[T("Files", "Dosyalar"), "", function () { openFiles(s.path || "/.yaver/skills/" + (s.dir || s.name)); }], [T("Use it", "Kullan"), "p", function () { closeAllPages(); $("#input").value = T("Use the skill “" + s.name + "” to ", "“" + s.name + "” yeteneğini kullanarak "); autosize(); syncSend(); $("#input").focus(); }]] });
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
        h("button", { class: "ib sm", "aria-label": T("Move up", "Yukarı taşı"), disabled: i === 0 || null, onclick: function () { moveTask(t.id, all[i - 1].id); } }, icon("move-up", "xs")),
        h("button", { class: "ib sm", "aria-label": T("Move down", "Aşağı taşı"), disabled: i === all.length - 1 || null, onclick: function () { moveTask(t.id, all[i + 2] ? all[i + 2].id : ""); } }, icon("move-down", "xs"))) : null);
  }
  /* by neighbour id, not index: the board's order includes tasks this filter hides (done ones under "Open") */
  function moveTask(id, beforeId) { call("tasks.move", taskArgs({ id: id, before: beforeId || "" })).then(renderTasks).catch(function (e) { toast(e.message); }); }
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
    var de = h("textarea", { rows: 3, class: "sm", placeholder: T("Details (optional)", "Ayrıntılar (isteğe bağlı)") }); de.value = prefill.desc || "";
    sheet({ title: T("New task", "Yeni görev"), body: h("div", null, peopleList(), h("label", { class: "field" }, h("span", null, T("Title", "Başlık")), ti), h("div", { class: "f2" }, h("label", { class: "field" }, h("span", null, T("Owner", "Sorumlu")), ow), h("label", { class: "field" }, h("span", null, T("Due", "Son tarih")), du)), h("label", { class: "field" }, h("span", null, T("Description", "Açıklama")), de)),
      actions: [[T("Cancel", "İptal")], [T("Add task", "Görevi ekle"), "p", function () {
        if (!ti.value.trim()) { toast(T("Give it a title", "Bir başlık verin")); return false; }
        call("tasks.add", { title: ti.value.trim(), owner: ow.value.trim(), due: du.value, desc: de.value.trim(), status: "todo", source: prefill.source || null }).then(function (t) { toast(T("Task added", "Görev eklendi") + (t && t.id ? " · " + t.id : "")); loadTasks(); refreshOverdue(); }).catch(function (e) { toast(e.message); });
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
    return call(method, args).then(function (r) { if (after) after(r); return loadExtras(); }).catch(function (e) { toast(e.message); return loadExtras(); });
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
        row({ icon: "gauge", label: T("Context budget", "Bağlam bütçesi"), sub: T("Most tokens sent per request; the meter in the chat counts against this", "İstek başına gönderilen en fazla token; sohbetteki gösterge buna göre sayar"), wrap: true, value: (S.state.llm || {}).contextTokens ? k(S.state.llm.contextTokens) : T("Automatic", "Otomatik") + (C.budget ? " · " + k(C.budget) : ""), onclick: function () { optionsSheet(T("Context budget (tokens)", "Bağlam bütçesi (token)"), [[0, T("Automatic — from the model's window", "Otomatik — modelin penceresine göre")]].concat([32000, 64000, 96000, 128000, 200000, 400000].map(function (n) { return [n, k(n)]; })).map(function (x) { return [String(x[0]), x[1]]; }), String((S.state.llm || {}).contextTokens || 0), function (v) { setCfg("contextTokens", Number(v)).then(function () { renderSettings(); }); }); } })
      ]));

      var s1 = X.s1 || {}, cfg = s1.cfg || s1, be = cfg.backend || "off";
      var backends = (cfg.backends && cfg.backends.length) ? cfg.backends.map(function (b) { return [b.id, b.label]; }) : [["off", T("None — the LLM decides everything", "Yok — her kararı LLM verir")], ["jev", "TypeSafe Jev"], ["laya", "Laya"], ["llm", T("Emulated with the LLM (testing)", "LLM ile taklit (test için)")]];
      var beName = { off: T("Off", "Kapalı"), jev: "Jev", laya: "Laya", llm: T("Emulated", "Taklit") }[be] || be;
      var remote = be === "jev" || be === "laya";
      box.appendChild(group("System-1", [
        row({ icon: "cpu", label: T("Decision model", "Karar modeli"), sub: T("Fast model for routine decisions in the loop", "Döngüdeki rutin kararlar için hızlı model"), value: beName, onclick: function () { optionsSheet("System-1", backends, be, function (v) { setX("s1.set", { backend: v }); }); } }),
        remote ? row({ icon: "globe", label: T("Address", "Adres"), sub: cfg.url || "", onclick: function () { editSheet({ title: T("System-1 address", "System-1 adresi"), type: "url", value: cfg.url, save: function (v) { setX("s1.set", { url: v.trim() }); } }); } }) : null,
        remote ? row({ icon: "key-round", label: T("API key", "API anahtarı"), value: masked(cfg.keySet), onclick: function () { editSheet({ title: "System-1 API", secret: true, placeholder: cfg.keySet ? "••••••••" : "", help: T("Stored encrypted on this phone.", "Bu telefonda şifreli saklanır."), save: function (v) { if (v.trim()) setX("s1.set", { key: v.trim() }); }, extra: cfg.keySet ? [[T("Remove", "Kaldır"), "", function () { setX("s1.set", { key: "" }); }]] : [] }); } }) : null,
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
        row({ icon: "images", label: T("Always show pictures in e-mail", "E-postalardaki resimleri her zaman göster"), sub: T("Off: pictures from the internet stay blocked until you tap “Show pictures”", "Kapalı: internetteki resimler “Resimleri göster”e dokunana kadar engelli kalır"), wrap: true, toggle: !!MX.images, onToggle: function (on) { mset("images", on); S.mail.images = on; } }),
        row({ icon: "sun", label: T("Daily plan", "Günlük plan"), sub: T("Once a day, after this time", "Günde bir kez, bu saatten sonra"), value: brief || T("Off", "Kapalı"), onclick: function () {
          optionsSheet(T("Daily plan", "Günlük plan"), [["", T("Off", "Kapalı")], ["07:30", "07:30"], ["08:00", "08:00"], ["08:30", "08:30"], ["09:00", "09:00"], ["custom", T("Other time…", "Başka saat…")]], brief, function (v) {
            if (v === "custom") editSheet({ title: T("Daily plan time", "Günlük plan saati"), type: "time", value: brief || "08:00", save: function (x) { mset("briefAt", x); } });
            else mset("briefAt", v);
          });
        } }),
        row({ icon: "cloud", label: T("Outlook sync (shared memory)", "Outlook eşitleme (ortak hafıza)"), sub: osyncText(O.kind ? O : { kind: mode === "off" ? "off" : "idle", mode: mode }), value: { on: T("On", "Açık"), ask: T("Ask", "Sor"), off: T("Off", "Kapalı") }[mode], onclick: function () {
          optionsSheet(T("Outlook Tasks backup", "Outlook Görevler yedeği"), [["on", T("Keep a copy in Outlook", "Outlook'ta kopya tut")], ["ask", T("Ask me", "Bana sor")], ["off", T("Off", "Kapalı")]], mode, function (v) { setX("osync.mode", { mode: v }); });
        } }),
        row({ icon: "refresh-cw", label: T("Get it from Outlook now", "Outlook'tan şimdi al"), sub: T("Bring in the tasks, memory, lessons and skills saved in Outlook (also by the web version) and keep them in sync", "Outlook'a kaydedilmiş görevleri, hafızayı, dersleri ve becerileri (web sürümünün kaydettikleri dahil) al ve eşit tut"), wrap: true, onclick: function () {
          toast(T("Reading the Outlook “yaver” folder…", "Outlook “yaver” klasörü okunuyor…"));
          call("osync.pull").then(function (r) { if (r && r.note) sheet({ title: T("Outlook", "Outlook"), text: r.note, actions: [[T("OK", "Tamam"), "p"]] }); loadExtras(); }).catch(function (e) { toast(String(e.message || e).replace(/^SIGNIN:/, "")); });
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
    sheet({ title: T("Decision log", "Karar günlüğü"), body: box, actions: [[T("Close", "Kapat")], [T("Clear log", "Günlüğü temizle"), "", function () {
      call("gates.clearLog").then(function () { toast(T("Decision log cleared", "Karar günlüğü temizlendi")); gatesLogSheet(); }).catch(function (e) { toast(e.message); });
    }]] });
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
    closeDrawer();
    if (topPage() === "schedule") return;
    pushPage("schedule", T("Reminders and scheduled tasks", "Hatırlatmalar ve zamanlanmış görevler"), function (body) {
      body.appendChild(loading());
      function draw() {
        call("schedule.list").then(function (all) {
          clear(body); all = all || [];
          if (!all.length) { body.appendChild(h("div", { class: "empty" }, h("div", { class: "ei" }, icon("alarm-clock")), h("h3", null, T("Nothing scheduled", "Planlanmış bir şey yok")), h("p", null, T("Ask yaver, e.g. “every weekday at 8:30 summarize my unread mail” or “remind me at 4 pm to call Ayşe”.", "yaver'e söyleyin, ör. “hafta içi her gün 8:30'da okunmamış postalarımı özetle” ya da “16:00'da Ayşe'yi aramamı hatırlat”.")))); return; }
          body.appendChild(group("", all.sort(function (a, b) { return a.next - b.next; }).map(function (o) {
            var rep = o.kind === "task" ? (o.daily ? T("Every day at ", "Her gün ") + o.daily : T("Every " + o.every + " min", "Her " + o.every + " dakikada")) : T("Once", "Bir kez");
            return row({ icon: o.kind === "task" ? "refresh-cw" : "bell", label: o.title, sub: rep + " — " + T("next ", "sonraki ") + longWhen(o.next), onclick: function () {
              sheet({ title: o.title, body: h("dl", { class: "dl" }, h("dt", null, T("Next", "Sonraki")), h("dd", null, longWhen(o.next)), o.prompt ? [h("dt", null, T("yaver will", "yaver")), h("dd", null, o.prompt)] : null, o.text ? [h("dt", null, T("Note", "Not")), h("dd", null, o.text)] : null),
                actions: [[T("Close", "Kapat")], [T("Cancel it", "İptal et"), "danger", function () { call("schedule.cancel", { id: o.id }).then(function () { toast(T("Cancelled", "İptal edildi")); draw(); }).catch(function (e) { toast(e.message); }); }]] });
            } });
          })));
        }).catch(function (e) { errorView(clear(body), e); });
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
      h("div", { class: "title", id: "ctitle", onclick: function () { if (window.YVP) YVP.titleTap(); } }, "yaver"),
      h("button", { class: "ctxm hidden", id: "ctxm", onclick: ctxSheet }),
      h("button", { class: "ib", "aria-label": T("Mail", "Posta"), onclick: openMailPage }, icon("mail")),
      h("button", { class: "ib", "aria-label": T("New chat", "Yeni sohbet"), onclick: function () { newChat(true); } }, icon("square-pen")),
      h("button", { class: "ib", "aria-label": T("More", "Daha fazla"), onclick: chatMenu }, icon("ellipsis-vertical"))));
    app.appendChild(h("div", { class: "planbar hidden", id: "planbar" }));
    app.appendChild(h("div", { id: "scroll" }, h("div", { class: "thread", id: "thread" })));
    app.appendChild(h("div", { class: "composer" }, h("div", { class: "hints hidden", id: "hints", role: "listbox" }), h("div", { class: "cbox" },
      h("div", { class: "atts hidden", id: "atts" }),
      h("div", { class: "cchips", id: "cchips" }),
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
        h("button", { onclick: function () { openMailPage("mail"); } }, icon("mail"), T("Mail", "Posta")),
        h("button", { onclick: function () { openMailPage("cal"); } }, icon("calendar"), T("Calendar", "Takvim")),
        h("button", { onclick: function () { closeDrawer(); openTasks(); } }, h("span", { class: "nb" }, icon("square-kanban"), h("i", { class: "badge hidden", id: "odbadge" })), T("Tasks", "Görevler")),
        h("button", { onclick: function () { closeDrawer(); openKnowledge(); } }, icon("brain"), T("Memory", "Hafıza")),
        h("button", { onclick: function () { openFiles(); } }, icon("folder"), T("Files", "Dosyalar")),
        h("button", { onclick: function () { openSettings(); } }, icon("settings"), T("Settings", "Ayarlar")))));
    renderAtts(); syncSend(); renderThread(); refreshOverdue();
    if (window.YVP) YVP.shell();
  }

  /* ------------------------------------------------------------ internals for parity.js */
  window.YVI = { S: S, call: call, h: h, icon: icon, T: T, $: $, clear: clear, toast: toast, sheet: sheet, editSheet: editSheet, optionsSheet: optionsSheet, md: md,
    pushPage: pushPage, popPage: popPage, topPage: topPage, closeAllPages: closeAllPages, renderThread: renderThread, loadConvo: loadConvo, refreshState: refreshState, setCfg: setCfg,
    row: row, group: group, openSettings: openSettings, openMailPage: openMailPage, openTasks: openTasks, openKnowledge: openKnowledge, openFiles: openFiles, previewFile: previewFile,
    newChat: newChat, runCommand: runCommand, send: send, renderAtts: renderAtts, autosize: autosize, syncSend: syncSend, addSide: addSide, refreshConvos: refreshConvos,
    COMMANDS: COMMANDS, pickModel: pickModel, editFile: typeof editFile === "function" ? editFile : null, timeOf: timeOf, bytes: bytes, wsUrl: wsUrl, kbChanged: kbChanged,
    renderSettings: renderSettings, renderShell: renderShell, closeDrawer: closeDrawer, openDrawer: openDrawer, stop: stop,
    chatMenu: chatMenu, ctxSheet: ctxSheet, openSchedule: openSchedule, openSetup: openSetup, openMail: openMail, composeSheet: composeSheet, newTaskSheet: newTaskSheet, taskSheet: taskSheet, peopleSheet: peopleSheet, gatesLogSheet: gatesLogSheet, eventSheet: eventSheet, onEvent: function (t, d) { onEvent(t, d); }, renderPlanBar: renderPlanBar, sheets: sheets };

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
