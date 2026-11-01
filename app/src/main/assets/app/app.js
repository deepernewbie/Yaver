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
  var S = { lang: "tr", state: null, convo: null, convoId: "", live: "", liveStatus: "", liveTool: null, running: {}, atts: [], convos: [], pages: [], voiceTurn: false, mail: { folder: "inbox", tab: "mail", items: [], query: "" } };
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
  function toast(msg) {
    var t = $(".toast") || document.body.appendChild(h("div", { class: "toast", role: "status" }));
    t.textContent = msg; t.classList.add("on");
    clearTimeout(toastT); toastT = setTimeout(function () { t.classList.remove("on"); }, 3200);
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
  function md(text) {
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
    box.addEventListener("click", function (e) {
      var a = e.target.closest("a"); if (!a) return;
      e.preventDefault();
      var href = a.getAttribute("href") || "";
      if (/^mail:/.test(href)) openMail(href.slice(5));
      else if (href) call("open.link", { url: href });
    });
    return box;
  }

  /* ------------------------------------------------------------ tool steps */
  var TOOLS = {
    web_search: ["search", "Searched the web", "Web'de arandı", "query"],
    read_page: ["book-open", "Read a page", "Sayfa okundu", "url"],
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
    phone_speak: ["volume-2", "Spoke aloud", "Sesli okundu", ""]
  };
  var ACTIONS = { list: ["list", "listele"], search: ["search", "ara"], read: ["read", "oku"], get_attachment: ["attachment", "ek"], draft: ["draft", "taslak"], send: ["send", "gönder"], send_now: ["send", "gönder"], mark_read: ["mark read", "okundu"], delete: ["delete", "sil"], move: ["move", "taşı"], archive: ["archive", "arşivle"], free: ["free time", "boş zaman"], create: ["create", "oluştur"], respond: ["respond", "yanıtla"], cancel: ["cancel", "iptal"], add: ["add", "ekle"], replace: ["update", "güncelle"], remove: ["remove", "kaldır"], calendars: ["calendars", "takvimler"], alarm: ["alarm", "alarm"], timer: ["timer", "zamanlayıcı"] };
  function stepInfo(name, args) {
    var d = TOOLS[name] || ["wrench", name, name, ""];
    var label = T(d[1], d[2]);
    var target = "";
    if (d[3] === "action" && args && args.action) { var a = ACTIONS[args.action]; label += " · " + (a ? T(a[0], a[1]) : args.action); target = args.query || args.subject || args.title || ""; }
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
    if (!msgs.length && !S.running[S.convoId]) { th.appendChild(helloView()); updateTitle(); return; }
    var calls = {}, trace = null, turn = null, lastText = null;
    function ensureTurn() { if (!turn) { turn = h("div", { class: "turn" }, h("div", { class: "who" }, h("div", { class: "mark" }, "y"), "yaver")); th.appendChild(turn); } return turn; }
    msgs.forEach(function (m, idx) {
      if (m.role === "user") {
        trace = null; turn = null; lastText = null;
        if (m.label) { th.appendChild(h("div", { class: "note" }, icon("clock", "xs"), m.label)); return; }
        var files = [].concat(m.images || [], m.files || []);
        th.appendChild(h("div", { class: "u" }, h("div", null,
          h("div", { class: "bubble" }, m.display || m.content || ""),
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
            h("button", { class: "ib", "aria-label": T("Share", "Paylaş"), onclick: function () { call("share.text", { text: text }); } }, icon("share-2", "s"))));
          ensureTurn().appendChild(lastText);
        }
        (m.tool_calls || []).forEach(function (tc) { calls[tc.id] = { name: (tc["function"] || {}).name, args: parseArgs((tc["function"] || {}).arguments) }; });
      } else if (m.role === "tool") {
        var c = calls[m.tool_call_id] || { name: m.name, args: {} };
        if (!trace) { trace = h("div", { class: "trace" }); ensureTurn().appendChild(trace); }
        trace.appendChild(stepEl(c.name, c.args, m.ok === false ? "bad" : "ok", m.content));
      }
    });
    if (S.running[S.convoId]) {
      var t = ensureTurn();
      if (S.liveTool) {
        if (!trace) { trace = h("div", { class: "trace" }); t.appendChild(trace); }
        trace.appendChild(stepEl(S.liveTool.name, S.liveTool.args, "run"));
      }
      var live = h("div", { id: "live" });
      if (S.live) live.appendChild(md(S.live));
      t.appendChild(live);
      t.appendChild(h("div", { class: "status", id: "lstatus" }, h("div", { class: "spin" }), h("span", null, S.liveStatus || (S.liveTool ? stepInfo(S.liveTool.name, S.liveTool.args).label + "…" : (S.live ? T("Writing…", "Yazıyor…") : T("Thinking…", "Düşünüyor…"))))));
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
      renderThread(); syncSend();
    }).catch(function () { S.convo = null; S.convoId = ""; renderThread(); });
  }

  function newChat(focus) {
    S.convo = null; S.convoId = ""; S.live = ""; S.liveTool = null; S.atts = [];
    renderAtts(); renderThread(); syncSend(); closeDrawer(); closeAllPages();
    if (focus) setTimeout(function () { $("#input").focus(); }, 80);
  }

  function send(text) {
    var ta = $("#input");
    var t = text != null ? text : ta.value;
    if (!String(t).trim() && !S.atts.length) return;
    if (S.running[S.convoId]) { toast(T("yaver is still working — tap ■ to stop.", "yaver hâlâ çalışıyor — durdurmak için ■'e dokunun.")); return; }
    if (!(S.state && S.state.llm && S.state.llm.ready)) { openSetup(); return; }
    var atts = S.atts.slice();
    var speak = S.voiceTurn; S.voiceTurn = false;
    ta.value = ""; autosize(); S.atts = []; renderAtts();
    // show it at once
    if (!S.convo) S.convo = { id: "", title: String(t).slice(0, 60), messages: [] };
    S.convo.messages = (S.convo.messages || []).concat([{ role: "user", content: t, display: t, images: atts.filter(function (a) { return a.image; }).map(function (a) { return a.path; }), files: atts.filter(function (a) { return !a.image; }).map(function (a) { return a.path; }) }]);
    var tmpId = S.convoId || "_new";
    S.running[tmpId] = true; S.convoId = tmpId; S.live = ""; S.liveTool = null; S.liveStatus = "";
    renderThread(); syncSend();
    call("chat.send", { convoId: tmpId === "_new" ? "" : tmpId, text: t, attachments: atts.map(function (a) { return a.path; }), speak: speak }).then(function (r) {
      delete S.running["_new"]; S.running[r.convoId] = true;
      if (S.convoId === "_new" || S.convoId === tmpId) { S.convoId = r.convoId; S.convo.id = r.convoId; S.convo.title = r.title; }
      updateTitle(); refreshConvos();
    }).catch(function (e) {
      delete S.running[tmpId]; renderThread(); syncSend(); toast(e.message);
    });
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
    if (running) { b.className = "send stop"; b.appendChild(icon("square", "xs")); b.setAttribute("aria-label", T("Stop", "Durdur")); b.disabled = false; }
    else if (has) { b.className = "send"; b.appendChild(icon("arrow-up")); b.setAttribute("aria-label", T("Send", "Gönder")); b.disabled = false; }
    else { b.className = "send"; b.appendChild(icon("mic")); b.setAttribute("aria-label", T("Speak", "Konuş")); b.disabled = false; }
  }

  /* ------------------------------------------------------------ events from the core */
  function onEvent(type, d) {
    if (type === "run") {
      var id = d.convoId;
      if (d.phase === "done") {
        delete S.running[id];
        if (id === S.convoId) { S.live = ""; S.liveTool = null; S.liveStatus = ""; loadConvo(id); if (d.error) toast(d.error); }
        refreshConvos(); return;
      }
      S.running[id] = true;
      if (id !== S.convoId) return;
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
    else if (type === "mail") { refreshState().then(function () { if (topPage() === "mail") loadMail(); }); }
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
        notSet ? null : h("button", { class: "btn", onclick: function () { loadMail(true); } }, icon("refresh-cw", "s"), T("Try again", "Yeniden dene")))));
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
    send(T("Look at this e-mail (mail id=" + m.id + ", subject \"" + m.subject + "\" from " + (m.from && (m.from.name || m.from.email)) + "). Summarize it, tell me what is expected of me and suggest a reply.",
      "Şu e-postaya bak (mail id=" + m.id + ", konu \"" + m.subject + "\", gönderen " + (m.from && (m.from.name || m.from.email)) + "). Özetle, benden ne bekleniyor söyle ve bir yanıt öner."));
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
    if (topPage() === "settings") return;
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
  function masked(has) { return has ? "••••••••" : T("Not set", "Ayarlanmadı"); }

  var PRESETS = [["https://openrouter.ai/api/v1", "OpenRouter", "One key, hundreds of models, many free", "Tek anahtar, yüzlerce model, birçoğu ücretsiz"], ["https://api.openai.com/v1", "OpenAI", "GPT models", "GPT modelleri"], ["https://generativelanguage.googleapis.com/v1beta/openai", "Google Gemini", "Gemini models", "Gemini modelleri"], ["https://api.groq.com/openai/v1", "Groq", "Very fast open models", "Çok hızlı açık modeller"], ["https://api.together.xyz/v1", "Together", "Open models", "Açık modeller"], ["http://localhost:11434/v1", "Ollama", "A model on your own computer", "Kendi bilgisayarınızdaki model"]];
  function serviceName(base) { var p = PRESETS.filter(function (x) { return x[0] === base; })[0]; return p ? p[1] : (base || "").replace(/^https?:\/\//, "").split("/")[0]; }

  function renderSettings(focus) {
    var p = S.pages.filter(function (x) { return x.name === "settings"; })[0]; if (!p) return;
    var st = S.state, L = st.llm, M = st.mail, Ph = st.phone, W = st.web;
    var body = clear(p.body);
    var wrap = h("div", { style: "padding-bottom:20px" });
    body.appendChild(wrap);

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
      row({ icon: "message-square", label: T("Show answers as they are written", "Cevapları yazılırken göster"), toggle: L.stream, onToggle: function (on) { setCfg("stream", on); } }),
      row({ icon: "check", label: T("Test the model", "Modeli dene"), onclick: function () { toast(T("Asking the model…", "Modele soruluyor…")); call("llm.test").then(function (r) { toast("✓ " + r); }).catch(function (e) { toast(e.message); }); } })
    ]));

    wrap.appendChild(group(T("Assistant", "Asistan"), [
      row({ icon: "languages", label: T("Answer language", "Cevap dili"), value: { auto: T("Like my message", "Mesajım gibi"), tr: "Türkçe", en: "English" }[st.langSetting] || st.langSetting, onclick: function () { optionsSheet(T("Answer language", "Cevap dili"), [["auto", T("Same as my message", "Mesajımla aynı")], ["tr", "Türkçe"], ["en", "English"]], st.langSetting, function (v) { setCfg("lang", v).then(function () { renderShell(); }); }); } }),
      row({ icon: "user", label: T("Personality", "Kişilik"), sub: st.persona ? st.persona : T("Default", "Varsayılan"), onclick: function () { editSheet({ title: T("Personality", "Kişilik"), text: T("Who yaver is and how it talks. Leave empty for the default.", "yaver kimdir ve nasıl konuşur. Varsayılan için boş bırakın."), multi: true, value: st.persona, placeholder: st.personaDefault, save: function (v) { setCfg("persona", v); } }); } }),
      row({ icon: "shield-check", label: T("Ask before actions", "İşlemlerden önce sor"), sub: T("Deleting, moving, overwriting. Sending mail is always confirmed.", "Silme, taşıma, üzerine yazma. E-posta gönderimi her zaman onaylatılır."), wrap: true, toggle: st.askRisky, onToggle: function (on) { setCfg("askRisky", on); } }),
      row({ icon: "brain", label: T("Memory", "Hafıza"), sub: T("What yaver knows about you", "yaver'in sizin hakkınızda bildikleri"), onclick: openMemory }),
      row({ icon: "clock", label: T("Reminders and scheduled tasks", "Hatırlatmalar ve zamanlanmış görevler"), onclick: openSchedule })
    ]));

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
      row({ icon: "info", label: T("Last crash report", "Son çökme raporu"), onclick: function () { call("crash.last").then(function (t) { sheet({ title: T("Last crash report", "Son çökme raporu"), body: h("div", { class: "res", style: "display:block;white-space:pre-wrap;font-size:12px;max-height:50vh;overflow:auto" }, t || T("No crash recorded.", "Kayıtlı çökme yok.")), actions: t ? [[T("Copy", "Kopyala"), "p", function () { call("copy", { text: t }); }], [T("Close", "Kapat")]] : [[T("Close", "Kapat")]] }); }); } })
    ], "yaver " + st.version));
    if (focus === "mail") setTimeout(function () { var el = document.getElementById("s-mail"); if (el) el.scrollIntoView(); }, 300);
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

  /* ------------------------------------------------------------ memory, schedule */
  function openMemory() {
    pushPage("memory", T("Memory", "Hafıza"), function (body) {
      function draw() {
        call("memory.get").then(function (mem) {
          clear(body);
          [["user", T("About you", "Hakkınızda"), T("Facts yaver keeps in mind in every chat.", "yaver'in her sohbette aklında tuttuğu bilgiler.")], ["notes", T("yaver's notes", "yaver'in notları"), T("Projects, people and standing instructions yaver saved.", "yaver'in kaydettiği projeler, kişiler ve kalıcı talimatlar.")]].forEach(function (g) {
            var items = mem[g[0]] || [];
            body.appendChild(group(g[1], items.map(function (t, i) {
              return row({ label: t.length > 140 ? t.slice(0, 140) + "…" : t, onclick: function () { editItem(g[0], items, i); } });
            }).concat([row({ icon: "plus", label: T("Add", "Ekle"), onclick: function () { editItem(g[0], items, -1); } })]), g[2]));
          });
        });
      }
      function editItem(target, items, i) {
        editSheet({ title: i < 0 ? T("Remember", "Hatırla") : T("Edit", "Düzenle"), multi: true, value: i < 0 ? "" : items[i],
          save: function (v) { var l = items.slice(); if (i < 0) { if (v.trim()) l.push(v.trim()); } else if (v.trim()) l[i] = v.trim(); else l.splice(i, 1); call("memory.set", { target: target, list: l }).then(draw); },
          extra: i < 0 ? [] : [[T("Delete", "Sil"), "", function () { var l = items.slice(); l.splice(i, 1); call("memory.set", { target: target, list: l }).then(draw); }]] });
      }
      draw();
    });
  }

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
    ta.addEventListener("input", function () { autosize(); syncSend(); });
    app.appendChild(h("div", { class: "bar" },
      h("button", { class: "ib", "aria-label": T("Chats", "Sohbetler"), onclick: openDrawer }, icon("menu")),
      h("div", { class: "title", id: "ctitle" }, "yaver"),
      h("button", { class: "ib", "aria-label": T("Mail", "Posta"), onclick: openMailPage }, icon("mail")),
      h("button", { class: "ib", "aria-label": T("New chat", "Yeni sohbet"), onclick: function () { newChat(true); } }, icon("square-pen"))));
    app.appendChild(h("div", { id: "scroll" }, h("div", { class: "thread", id: "thread" })));
    app.appendChild(h("div", { class: "composer" }, h("div", { class: "cbox" },
      h("div", { class: "atts hidden", id: "atts" }),
      h("div", { class: "crow" },
        h("button", { class: "ib", "aria-label": T("Attach", "Ekle"), onclick: pick }, icon("paperclip")),
        ta,
        h("button", { class: "send", id: "send", onclick: function () {
          if (S.running[S.convoId]) stop();
          else if (ta.value.trim() || S.atts.length) send();
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
        h("button", { onclick: function () { closeDrawer(); openMemory(); } }, icon("brain"), T("Memory", "Hafıza")),
        h("button", { onclick: function () { openSettings(); } }, icon("settings"), T("Settings", "Ayarlar")))));
    renderAtts(); syncSend(); renderThread();
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
