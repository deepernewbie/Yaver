/* yaver — the screens that close the gaps to the original app (PARITY.md): permission presets, the per-turn
   "files changed" card with diff and revert, whole-state export / import, the skills editor, the debug dump, slash
   commands that open pages, model / memory / appearance settings, editable shortcuts, undo of /clear, clickable
   file names and a picture row under answers, pasted pictures, keyboard shortcuts and the theme switch.
   It uses app.js's helpers through window.YVI and is called from small hooks there (window.YVP). */
(function () {
  "use strict";
  var I = window.YVI; if (!I) return;
  var S = I.S, call = I.call, h = I.h, icon = I.icon, T = I.T, $ = I.$, clear = I.clear, toast = I.toast, sheet = I.sheet;
  var P = { shortcuts: null, perm: null, llmx: null };

  function cid() { return S.convoId && S.convoId !== "_new" ? S.convoId : ""; }
  function fail(e) { toast(e && e.message ? e.message : String(e)); }
  function opt(ic, label, sub, fn, cls) { return h("button", { class: "opt" + (cls ? " " + cls : ""), onclick: fn }, icon(ic, "s"), h("div", null, label, sub ? h("small", null, sub) : null)); }
  function base(p) { return String(p || "").split("/").pop(); }

  /* ================================================================ theme (System / Light / Dark) */
  function applyTheme(t) {
    var root = document.documentElement;
    /* parity.css carries both palettes under :root[data-theme=…] */
    if (t === "light" || t === "dark") root.setAttribute("data-theme", t);
    else root.removeAttribute("data-theme");
    try { localStorage.setItem("yv.theme", t || "system"); } catch (e) {}
  }
  function curTheme() { return (S.state && S.state.llmx && S.state.llmx.theme) || (function () { try { return localStorage.getItem("yv.theme") || "system"; } catch (e) { return "system"; } })(); }
  function isDark() { var t = curTheme(); return t === "dark" || (t === "system" && window.matchMedia && matchMedia("(prefers-color-scheme: dark)").matches); }
  function themeSheet() {
    I.optionsSheet(T("Appearance", "Görünüm"), [["system", T("Like the phone", "Telefon gibi"), "smartphone"], ["light", T("Light", "Açık"), "sun"], ["dark", T("Dark", "Koyu"), "moon"]], curTheme(), function (v) {
      applyTheme(v); I.setCfg("theme", v);
    });
  }

  /* ================================================================ permission presets */
  var PERM_ICON = { ask: "hand", auto: "shield-check", full: "zap", readonly: "eye" };
  function permMode() { return (S.state && S.state.perm) || "auto"; }
  function permLabel(m) { return { ask: T("Ask first", "Önce sor"), auto: T("Auto", "Otomatik"), full: T("Full auto", "Tam otomatik"), readonly: T("Read-only", "Salt okunur") }[m] || m; }
  function permHelp(m) {
    return { ask: T("Every change or side effect asks you first", "Her değişiklik veya yan etki önce size sorulur"),
      auto: T("Risky actions go through the safety check (System-1 or the model), then you", "Riskli işlemler güvenlik kontrolünden (System-1 veya model), sonra sizden geçer"),
      full: T("No approvals, except sending mail and invitations", "Onay yok; e-posta ve davet gönderimi hariç"),
      readonly: T("Nothing on the disk, the phone or the web is changed", "Diskte, telefonda veya web'de hiçbir şey değiştirilmez") }[m] || "";
  }
  function permSheet() {
    I.optionsSheet(T("Permissions", "İzinler"), ["ask", "auto", "full", "readonly"].map(function (m) { return [m, permLabel(m), PERM_ICON[m], permHelp(m)]; }), permMode(), function (v) {
      I.setCfg("permissions", v).then(function (st) { if (st) { renderChips(); toast("🛡 " + permLabel(v)); } });
    });
  }
  function permRow() { return I.row({ icon: PERM_ICON[permMode()] || "shield-check", label: T("Permissions", "İzinler"), sub: permHelp(permMode()), wrap: true, value: permLabel(permMode()), onclick: permSheet }); }

  /* composer chips: permission preset and plan mode */
  function renderChips() {
    var box = $("#cchips"); if (!box) return; clear(box);
    var m = permMode(), plan = !!((S.plan[cid()] || {}).planMode);
    box.appendChild(h("button", { class: "cchip" + (m !== "auto" ? " on " + m : ""), "aria-label": T("Permissions", "İzinler") + ": " + permLabel(m), onclick: permSheet }, icon(PERM_ICON[m] || "shield-check", "xs"), h("span", null, permLabel(m))));
    box.appendChild(h("button", { class: "cchip" + (plan ? " on plan" : ""), "aria-pressed": String(plan), onclick: function () { I.runCommand(plan ? "/plan off" : "/plan"); setTimeout(renderChips, 600); } }, icon("map", "xs"), h("span", null, plan ? T("Plan mode on", "Plan modu açık") : T("Plan first", "Önce planla"))));
  }

  /* ================================================================ files changed in a turn (diff + revert) */
  var KIND = { created: ["file-down", T("created", "oluşturuldu")], modified: ["file-pen", T("modified", "değişti")], deleted: ["trash-2", T("deleted", "silindi")] };
  function changesCard(ch) {
    var files = ch.files || [];
    var card = h("div", { class: "card chg" + (ch.reverted ? " reverted" : "") });
    function draw() {
      clear(card);
      card.appendChild(h("div", { class: "ch" }, icon("file-diff", "s"), h("b", null, files.length === 1 ? T("1 file changed", "1 dosya değişti") : T(files.length + " files changed", files.length + " dosya değişti")),
        h("span", { class: "add" }, "+" + (ch.add || 0)), h("span", { class: "del" }, "−" + (ch.del || 0)), ch.reverted ? h("span", { class: "bdg" }, T("reverted", "geri alındı")) : null));
      var list = h("div", { class: "chl" });
      files.slice(0, 30).forEach(function (f) {
        var k = KIND[f.kind] || KIND.modified;
        list.appendChild(h("button", { class: "chf " + f.kind, onclick: function () { diffSheet(ch, f); } }, icon(k[0], "xs"), h("span", { class: "p" }, f.path.replace(/^\/workspace\//, "")),
          f.binary ? h("small", null, T("binary", "ikili")) : h("small", null, h("i", { class: "add" }, "+" + f.add), " ", h("i", { class: "del" }, "−" + f.del))));
      });
      if (files.length > 30) list.appendChild(h("div", { class: "muted" }, T("… and " + (files.length - 30) + " more", "… ve " + (files.length - 30) + " tane daha")));
      card.appendChild(list);
      if (!ch.reverted) card.appendChild(h("div", { class: "chb" },
        h("button", { class: "btn xs", onclick: function () { var f = files.filter(function (x) { return !x.binary; })[0] || files[0]; if (f) diffSheet(ch, f); } }, icon("file-diff", "xs"), T("View diff", "Farkı göster")),
        h("button", { class: "btn xs", onclick: function () {
          var lost = files.filter(function (x) { return x.noRevert; }).length;
          sheet({ title: T("Revert all changes of this turn?", "Bu turun tüm değişiklikleri geri alınsın mı?"), text: T("Created files are removed, changed and deleted ones come back as they were before this turn.", "Oluşturulan dosyalar silinir; değişen ve silinenler bu turdan önceki hâline döner.") + (lost ? " " + T(lost + " large file(s) cannot be put back.", lost + " büyük dosya geri getirilemez.") : ""),
            actions: [[T("Cancel", "İptal")], [T("Revert all", "Tümünü geri al"), "danger", function () {
              call("changes.revert", { id: ch.id, convoId: cid() }).then(function (r) { ch.reverted = true; draw(); toast(T("Reverted " + r.reverted + " file(s)", r.reverted + " dosya geri alındı") + (r.skipped && r.skipped.length ? " · " + T(r.skipped.length + " skipped", r.skipped.length + " atlandı") : "")); }).catch(fail);
            }]] });
        } }, icon("undo-2", "xs"), T("Revert all", "Tümünü geri al"))));
    }
    draw();
    return card;
  }
  function diffSheet(ch, f) {
    var body = h("div", { class: "diff" }, h("div", { class: "status" }, h("div", { class: "spin" }), T("Loading…", "Yükleniyor…")));
    var files = ch.files || [], i = files.indexOf(f);
    var s = sheet({ title: base(f.path), text: f.path + " · " + (KIND[f.kind] || KIND.modified)[1] + (f.binary ? "" : " · +" + f.add + " −" + f.del), body: body,
      actions: [[T("Close", "Kapat")]].concat(files.length > 1 ? [[T("Next file", "Sonraki dosya"), "", function () { s.close(); diffSheet(ch, files[(i + 1) % files.length]); }]] : []).concat(f.kind !== "deleted" ? [[T("Open", "Aç"), "p", function () { I.previewFile(f.path); }]] : []) });
    s.el.classList.add("tall");
    call("changes.diff", { id: ch.id, path: f.path }).then(function (d) {
      clear(body);
      if (d.binary) { body.appendChild(h("p", { class: "muted" }, T("A binary file: no line diff.", "İkili dosya: satır farkı yok."))); return; }
      if (d.missing) { body.appendChild(h("p", { class: "muted" }, T("The stored copy of this file is gone.", "Bu dosyanın saklanan kopyası artık yok."))); return; }
      var L = d.lines || [], keep = new Array(L.length);
      /* long diffs: only changed lines with 3 lines of context */
      var collapse = L.length > 400;
      for (var k = 0; k < L.length; k++) if (L[k][0] !== " ") for (var q = Math.max(0, k - 3); q <= Math.min(L.length - 1, k + 3); q++) keep[q] = true;
      var pre = h("div", { class: "dl" + "x" }), skipped = 0;
      for (var n = 0; n < L.length; n++) {
        if (collapse && !keep[n]) { skipped++; continue; }
        if (skipped) { pre.appendChild(h("div", { class: "dsk" }, "⋯ " + T(skipped + " unchanged lines", skipped + " değişmeyen satır"))); skipped = 0; }
        pre.appendChild(h("div", { class: "dln " + (L[n][0] === "+" ? "a" : L[n][0] === "-" ? "d" : "c") }, h("i", null, L[n][0] === " " ? "" : L[n][0]), h("span", null, L[n][1] || " ")));
      }
      if (skipped) pre.appendChild(h("div", { class: "dsk" }, "⋯ " + T(skipped + " unchanged lines", skipped + " değişmeyen satır")));
      body.appendChild(pre);
    }).catch(function (e) { clear(body).appendChild(h("p", { class: "muted" }, e.message)); });
  }

  /* ================================================================ answers: file names, picture row, time */
  var NAME_RX = /^(?:\.{0,2}\/)?(?:[\p{L}\p{N}_.+-]+\/)*[\p{L}\p{N}_.+-]+\.[A-Za-z0-9]{1,6}$/u;
  var IMG_RX = /\.(png|jpe?g|gif|webp|bmp|svg)$/i;
  function answerExtras(wrap, box, m, idx, msgs, isFinal) {
    /* `report.md`, `outputs/x.docx`: relative names become links when the file exists (tried as given, then uploads/) */
    var cands = [];
    box.querySelectorAll("code").forEach(function (c) {
      if (c.closest("pre,a") || c.classList.contains("fpath")) return;
      var t = c.textContent.trim();
      if (NAME_RX.test(t) && !/^\d+(\.\d+)+$/.test(t) && !/^https?:/i.test(t)) cands.push([c, t]);
    });
    if (cands.length) call("files.exists", { paths: cands.map(function (x) { return x[1]; }) }).then(function (hit) {
      cands.forEach(function (x) { var p = hit && hit[x[1]]; if (!p) return; x[0].classList.add("fpath"); x[0].setAttribute("role", "link"); x[0].addEventListener("click", function (e) { e.stopPropagation(); I.previewFile(p); }); });
      pictures();
    }).catch(function () {}); else pictures();
    function pictures() {
      /* pictures the answer links to, as a row of thumbnails (at most 6), unless the answer already shows them */
      var seen = {}, paths = [];
      box.querySelectorAll("a.flink, code.fpath").forEach(function (a) {
        var p = a.tagName === "A" ? a.getAttribute("href") : a.textContent.trim();
        if (p && IMG_RX.test(p) && !seen[p] && !box.querySelector('img[src="' + I.wsUrl(p).replace(/"/g, "") + '"]')) { seen[p] = 1; paths.push(p); }
      });
      if (!paths.length || wrap.querySelector(".imgrow")) return;
      var row = h("div", { class: "imgrow" }, paths.slice(0, 6).map(function (p) { return h("img", { class: "thumb", src: I.wsUrl(p), alt: base(p), onclick: function () { I.previewFile(p); } }); }));
      wrap.insertBefore(row, box.nextSibling);
    }
    /* the time and how long the answer took (from the user's message) */
    if (isFinal && m.ts) {
      var u = null; for (var k = idx - 1; k >= 0; k--) { var x = msgs[k]; if (x.role === "user" && !x.harness && !(x.content && /^\[(harness|goal round|heartbeat)/.test(x.content))) { u = x; break; } }
      var secs = u && u.ts ? Math.max(1, Math.round((m.ts - u.ts) / 1000)) : 0;
      var acts = wrap.querySelector(".acts");
      var stamp = h("span", { class: "stamp" }, I.timeOf(m.ts) + (secs ? " · " + T("answered in ", "yanıt: ") + (secs >= 90 ? Math.round(secs / 60) + T(" min", " dk") : secs + " s") : ""));
      if (acts) acts.appendChild(stamp); else wrap.appendChild(stamp);
    }
  }

  /* ================================================================ /clear: undo and folding the earlier messages */
  function dividerExtras(el, idx, msgs) {
    if (!el || idx !== msgs.length - 1 || S.running[S.convoId]) return;
    el.appendChild(h("button", { class: "undo", onclick: function () {
      call("ctx.restore", { convoId: cid() }).then(function (r) { toast(r.note); I.loadConvo(cid()); }).catch(fail);
    } }, T("Undo", "Geri al")));
  }
  var foldOpen = {};
  function foldForgotten(th, dv) {
    var els = [], el = dv.previousElementSibling;
    while (el) { if (el.classList.contains("forgotten")) els.push(el); el = el.previousElementSibling; }
    if (!els.length) return;
    var n = 0; (S.convo && S.convo.messages || []).some(function (m) { if (m.divider) return true; if ((m.role === "user" && !m.harness) || (m.role === "assistant" && m.content)) n++; return false; });
    var key = cid(), open = !!foldOpen[key];
    els.forEach(function (e) { e.classList.toggle("fhide", !open); });
    var b = h("button", { class: "more fold", onclick: function () { foldOpen[key] = !foldOpen[key]; I.renderThread(); } }, icon(open ? "chevrons-up-down" : "chevron-down", "xs"),
      open ? T("Hide earlier context", "Önceki bağlamı gizle") : T("Show earlier context (" + n + " messages)", "Önceki bağlamı göster (" + n + " mesaj)"));
    th.insertBefore(b, th.firstChild);
  }

  /* ================================================================ slash commands */
  var MORE_COMMANDS = [
    ["/permissions", "Permission preset: ask | auto | full | readonly", "İzin ayarı: ask | auto | full | readonly", "shield-check"],
    ["/debug", "Debug dump of this chat", "Bu sohbetin hata ayıklama dökümü", "file-search"],
    ["/settings", "Open the settings", "Ayarları aç", "settings"],
    ["/mail", "Open the mail", "Postayı aç", "mail"],
    ["/calendar", "Open the calendar", "Takvimi aç", "calendar"],
    ["/tasks", "Open the task board", "Görev panosunu aç", "square-kanban"],
    ["/files", "Open the files", "Dosyaları aç", "folder"],
    ["/browser", "Watch the agent's browser", "Ajanın tarayıcısını izle", "app-window"],
    ["/memory", "Open memory and skills", "Hafıza ve yetenekleri aç", "brain"],
    ["/undo", "Undo the last /clear", "Son /clear'ı geri al", "undo-2"],
    ["/help", "List the commands", "Komutları listele", "circle-help"]
  ];
  MORE_COMMANDS.forEach(function (c) { if (!I.COMMANDS.some(function (x) { return x[0] === c[0]; })) I.COMMANDS.push(c); });
  function openTarget(t) {
    if (t === "settings") I.openSettings();
    else if (t === "mail") { S.mail.tab = "mail"; I.openMailPage(); }
    else if (t === "cal") { S.mail.tab = "cal"; I.openMailPage(); }
    else if (t === "tasks") I.openTasks();
    else if (t === "files") I.openFiles();
    else if (t === "memory") I.openKnowledge();
    else if (t === "skills") I.openKnowledge("skills");
    else if (t === "debug") debugSheet(cid());
    else if (t === "browser") browserPage();
  }
  function onCommand(r, text) {
    if (r.perm && S.state) { S.state.perm = r.perm; renderChips(); }
    if (r.open) openTarget(r.open);
    if (r.help) I.addSide(S.convoId || "_new", "note", { text: "❓ " + T("Commands", "Komutlar") + ":\n" + I.COMMANDS.map(function (c) { return c[0] + " — " + T(c[1], c[2]); }).join("\n") });
    if (/^\/(undo)\b/.test(text) && cid()) I.loadConvo(cid());
  }

  /* ================================================================ debug dump */
  function debugSheet(id) {
    if (!id) { toast(T("Start a conversation first", "Önce bir sohbet başlatın")); return; }
    var full = false, short = false, last = null;
    var ta = h("textarea", { class: "fedit dbg", readonly: true, spellcheck: "false", "aria-label": T("Debug dump", "Hata ayıklama dökümü") });
    var size = h("small", { class: "muted" }, T("Building…", "Hazırlanıyor…"));
    function chk(label, get, set) { var b = h("button", { class: "cchip", "aria-pressed": "false", onclick: function () { set(!get()); render(); } }, label); return b; }
    var bFull = chk(T("Full", "Tam"), function () { return full; }, function (v) { full = v; if (v) short = false; });
    var bShort = chk(T("Short", "Kısa"), function () { return short; }, function (v) { short = v; if (v) full = false; });
    function render() {
      bFull.setAttribute("aria-pressed", String(full)); bFull.classList.toggle("on", full); bShort.setAttribute("aria-pressed", String(short)); bShort.classList.toggle("on", short);
      size.textContent = T("Building…", "Hazırlanıyor…");
      call("convo.debug", { id: id, full: full, short: short }).then(function (d) { last = d; ta.value = d.md; size.textContent = d.chars.toLocaleString() + T(" characters · ", " karakter · ") + d.messages + T(" messages · ", " mesaj · ") + d.events + T(" trace events · ", " iz olayı · ") + d.problems + T(" problems", " sorun"); }).catch(function (e) { size.textContent = e.message; });
    }
    function saved(kind) {
      call("convo.debug", { id: id, full: full, short: short, save: true }).then(function (d) { return call("share.file", { path: kind === "json" ? d.jsonPath : d.mdPath }); }).catch(fail);
    }
    var s = sheet({ title: T("Debug dump", "Hata ayıklama dökümü"), text: T("System prompt, memory, model requests, System-1 decisions, tools and errors of this chat. Keys are removed.", "Bu sohbetin sistem istemi, hafızası, model istekleri, System-1 kararları, araçları ve hataları. Anahtarlar çıkarılır."),
      body: h("div", null, h("div", { class: "dbgbar" }, bFull, bShort, size), ta),
      actions: [[T("Copy", "Kopyala"), "", function () { if (last) { call("copy", { text: last.md }); toast(T("Copied — paste it into your message", "Kopyalandı — mesajınıza yapıştırın")); } return false; }], [".json", "", function () { saved("json"); return false; }], [T("Share", "Paylaş"), "p", function () { saved("md"); return false; }]] });
    s.el.classList.add("tall");
    render();
  }

  function chatMenuItems(close) {
    return h("div", null,
      opt("file-search", T("Debug dump", "Hata ayıklama dökümü"), T("System prompt, memory, requests, System-1 — for troubleshooting", "Sistem istemi, hafıza, istekler, System-1 — sorun giderme için"), function () { close(); debugSheet(cid()); }),
      opt(PERM_ICON[permMode()] || "shield-check", T("Permissions", "İzinler") + ": " + permLabel(permMode()), permHelp(permMode()), function () { close(); permSheet(); }),
      opt(isDark() ? "sun" : "moon", T("Appearance", "Görünüm"), { system: T("Like the phone", "Telefon gibi"), light: T("Light", "Açık"), dark: T("Dark", "Koyu") }[curTheme()], function () { close(); themeSheet(); }),
      opt("cpu", "System-1", T("Switch the decision model quickly", "Karar modelini hızlıca değiştirin"), function () { close(); s1Switch(); }),
      opt("globe", T("The agent's browser", "Ajanın tarayıcısı"), T("Watch the page yaver works on, live", "yaver'in çalıştığı sayfayı canlı izleyin"), function () { close(); browserPage(); }));
  }
  /* the original's quick System-1 switcher (#qS1) */
  function s1Switch() {
    call("s1.state").then(function (r) {
      var cfg = (r && r.cfg) || {}, be = cfg.backend || "off";
      var list = (cfg.backends && cfg.backends.length) ? cfg.backends.map(function (b) { return [b.id, b.label]; }) : [["off", T("None — the LLM decides everything", "Yok — her kararı LLM verir")], ["jev", "TypeSafe Jev"], ["laya", "Laya"], ["llm", T("Emulated with the LLM (testing)", "LLM ile taklit (test için)")]];
      I.optionsSheet("System-1", list, be, function (v) { call("s1.set", { backend: v }).then(function () { toast("System-1 · " + (list.filter(function (x) { return x[0] === v; })[0] || [v, v])[1]); }).catch(fail); });
    }).catch(fail);
  }
  function convoMenuItems(c, close) {
    return opt("file-search", T("Debug dump", "Hata ayıklama dökümü"), null, function () { close(); debugSheet(c.id); });
  }

  /* ================================================================ settings: model, memory, appearance, statistics, backup */
  function num(title, value, help, save, o) {
    o = o || {};
    I.editSheet({ title: title, value: String(value == null ? "" : value), type: "text", inputmode: o.decimal ? "decimal" : "numeric", help: help, save: function (v) {
      var n = o.decimal ? parseFloat(String(v).replace(",", ".")) : parseInt(v, 10);
      if (isNaN(n)) { toast(T("Enter a number", "Bir sayı girin")); return false; }
      if (o.min != null && n < o.min || o.max != null && n > o.max) { toast(T("Between " + o.min + " and " + o.max, o.min + " ile " + o.max + " arası")); return false; }
      save(n);
    } });
  }
  function settings(wrap) {
    Promise.all([call("llmx.get").catch(function () { return null; }), call("memx.get").catch(function () { return null; }), call("stats.get").catch(function () { return null; })]).then(function (r) {
      var box = $("#s-parity", wrap); if (!box) return; clear(box);
      var X = r[0] || {}, M = r[1] || {}, St = r[2] || {}, L = (S.state && S.state.llm) || {};
      P.llmx = X;
      function setX(k, v) { return I.call("cfg.set", { key: k, value: v }).then(function (st) { S.state = st; settings(wrap); return st; }).catch(function (e) { fail(e); settings(wrap); }); }
      function setM(k, v) { return call("memx.set", { key: k, value: v }).then(function () { settings(wrap); }).catch(fail); }
      var hdrs = X.extraHeaders ? Object.keys(JSON.parse(X.extraHeaders || "{}")).join(", ") : "";
      box.appendChild(I.group(T("Model — advanced", "Model — gelişmiş"), [
        I.row({ icon: "gauge", label: T("Temperature", "Sıcaklık"), sub: T("0 = focused and repeatable, 1+ = more varied", "0 = odaklı ve tekrarlanabilir, 1+ = daha çeşitli"), value: String(X.temperature != null ? X.temperature : 0.3), onclick: function () { num(T("Temperature", "Sıcaklık"), X.temperature, "0 – 2", function (n) { setX("temperature", n); }, { decimal: true, min: 0, max: 2 }); } }),
        I.row({ icon: "repeat", label: T("Most steps per task", "Görev başına en fazla adım"), sub: T("Model calls in one turn before yaver wraps up", "yaver toparlamadan önce bir turdaki model çağrısı"), value: String(L.maxSteps || X.maxSteps || 30), onclick: function () { num(T("Most steps per task", "Görev başına en fazla adım"), L.maxSteps || X.maxSteps, "3 – 100", function (n) { setX("maxSteps", n); }, { min: 3, max: 100 }); } }),
        I.row({ icon: "image", label: T("Send pictures to the model", "Resimleri modele gönder"), sub: T("Attached pictures go with the message (needs a model that sees)", "Eklenen resimler mesajla gider (görebilen model gerekir)"), wrap: true, toggle: L.sendImages !== false, onToggle: function (on) { setX("sendImages", on); } }),
        I.row({ icon: "code", label: T("Extra request headers", "Ek istek başlıkları"), sub: hdrs || T("JSON, for gateways that want their own headers", "JSON; kendi başlıklarını isteyen ağ geçitleri için"), onclick: function () {
          I.editSheet({ title: T("Extra request headers", "Ek istek başlıkları"), multi: true, value: X.extraHeaders ? JSON.stringify(JSON.parse(X.extraHeaders), null, 2) : "", placeholder: '{"X-Org-Id": "…"}', help: T("A JSON object. Sent with every model request. Empty = none.", "Bir JSON nesnesi. Her model isteğiyle gönderilir. Boş = yok."), save: function (v) {
            v = v.trim(); if (v) { try { var o = JSON.parse(v); if (!o || typeof o !== "object" || Array.isArray(o)) throw 0; } catch (e) { toast(T("Not a JSON object", "JSON nesnesi değil")); return false; } }
            setX("extraHeaders", v);
          } });
        } }),
        I.row({ icon: "cpu", label: T("System-1 state size", "System-1 durum boyutu"), sub: T("Characters of the conversation System-1 sees per decision", "System-1'in karar başına gördüğü konuşma karakteri"), value: String(X.stateChars || 2000), onclick: function () { num(T("System-1 state size", "System-1 durum boyutu"), X.stateChars, "200 – 20000", function (n) { setX("stateChars", n); }, { min: 200, max: 20000 }); } })
      ]));
      box.appendChild(I.group(T("Memory limits", "Hafıza sınırları"), [
        I.row({ icon: "brain", label: T("Active notes (most)", "Etkin not (en fazla)"), value: String(M.activeMax), onclick: function () { num(T("Active notes (most)", "Etkin not (en fazla)"), M.activeMax, null, function (n) { setM("activeMax", n); }, { min: 10, max: 5000 }); } }),
        I.row({ icon: "file-pen", label: T("Draft notes (most)", "Taslak not (en fazla)"), value: String(M.draftMax), onclick: function () { num(T("Draft notes (most)", "Taslak not (en fazla)"), M.draftMax, null, function (n) { setM("draftMax", n); }, { min: 5, max: 5000 }); } }),
        I.row({ icon: "clock", label: T("Draft trial", "Taslak deneme süresi"), sub: T("Days of use before a draft becomes active or retires", "Taslağın etkinleşmesi ya da emekli olması için kullanım günü"), wrap: true, value: M.draftDays + T(" days", " gün"), onclick: function () { num(T("Draft trial (days)", "Taslak deneme süresi (gün)"), M.draftDays, null, function (n) { setM("draftDays", n); }, { min: 1, max: 365 }); } }),
        I.row({ icon: "message-square", label: T("Notes in every prompt", "Her istemdeki not"), value: String(M.promptNotes), onclick: function () { num(T("Notes in every prompt", "Her istemdeki not"), M.promptNotes, "5 – 400", function (n) { setM("promptNotes", n); }, { min: 5, max: 400 }); } }),
        I.row({ icon: "scroll-text", label: T("Characters per note", "Not başına karakter"), value: String(M.entryChars), onclick: function () { num(T("Characters per note", "Not başına karakter"), M.entryChars, "100 – 2000", function (n) { setM("entryChars", n); }, { min: 100, max: 2000 }); } }),
        I.row({ icon: "user", label: T("“About you” size", "“Hakkınızda” boyutu"), value: String(M.userChars), onclick: function () { num(T("“About you” size (characters)", "“Hakkınızda” boyutu (karakter)"), M.userChars, null, function (n) { setM("userChars", n); }, { min: 20000, max: 200000 }); } }),
        I.row({ icon: "sparkles", label: T("Search memory by reasoning", "Hafızada akıl yürüterek ara"), sub: T("When keywords find nothing, the model reads the topic outline", "Anahtar kelimeler bir şey bulamazsa model konu taslağını okur"), wrap: true, toggle: !!M.reason, onToggle: function (on) { setM("reason", on); } }),
        I.row({ icon: "file-pen", label: T("Edit MEMORY.md as text", "MEMORY.md'yi metin olarak düzenle"), sub: T("All notes at once, separated by §", "Tüm notlar bir arada, § ile ayrılmış"), wrap: true, onclick: function () { rawMemory("memory"); } }),
        I.row({ icon: "user", label: T("Edit USER.md as text", "USER.md'yi metin olarak düzenle"), onclick: function () { rawMemory("user"); } }),
        I.row({ icon: "target", label: T("Review trigger", "İnceleme eşiği"), sub: T("With System-1: how likely a turn must be worth saving before the review runs", "System-1 ile: incelemenin çalışması için bir turun kaydetmeye değer olma olasılığı"), wrap: true, value: String(M.reviewFloor), onclick: function () { num(T("Review trigger", "İnceleme eşiği"), M.reviewFloor, "0 – 1", function (n) { setM("reviewFloor", n); }, { decimal: true, min: 0, max: 1 }); } })
      ]));
      var langV = X.uiLang || "";
      box.appendChild(I.group(T("Appearance", "Görünüm"), [
        I.row({ icon: isDark() ? "moon" : "sun", label: T("Theme", "Tema"), value: { system: T("Like the phone", "Telefon gibi"), light: T("Light", "Açık"), dark: T("Dark", "Koyu") }[curTheme()], onclick: themeSheet }),
        I.row({ icon: "languages", label: T("Interface language", "Arayüz dili"), sub: T("Separate from the answer language", "Cevap dilinden ayrı"), value: { "": T("Like the answers", "Cevaplar gibi"), tr: "Türkçe", en: "English" }[langV], onclick: function () {
          I.optionsSheet(T("Interface language", "Arayüz dili"), [["", T("Like the answer language", "Cevap dili gibi")], ["tr", "Türkçe"], ["en", "English"]], langV, function (v) { I.call("cfg.set", { key: "uiLang", value: v }).then(function (st) { S.state = st; S.lang = st.lang; document.documentElement.lang = S.lang; I.renderShell(); I.renderSettings(); }).catch(fail); });
        } }),
        I.row({ icon: "square-pen", label: T("Welcome shortcuts", "Karşılama kısayolları"), sub: T("The suggestions on a new chat", "Yeni sohbetteki öneriler"), onclick: shortcutsPage })
      ]));
      var by = St.byPurpose || {}, keys = Object.keys(by).sort(function (a, b) { return by[b] - by[a]; });
      box.appendChild(I.group(T("Usage", "Kullanım"), [
        I.row({ icon: "activity", label: T("Model calls", "Model çağrıları"), sub: (St.llmCalls || 0) + T(" calls · ", " çağrı · ") + fmtTok(St.tokensIn) + T(" in · ", " giden · ") + fmtTok(St.tokensOut) + T(" out", " gelen") + (keys.length ? " · " + keys.slice(0, 4).map(function (k) { return k + " " + by[k]; }).join(", ") : ""), wrap: true, onclick: function () { statsSheet(St); } })
      ]));
      box.appendChild(I.group(T("Backup", "Yedek"), [exportRow(), importRow(),
        I.row({ icon: "trash-2", label: T("Erase everything", "Her şeyi sil"), sub: T("Files, memory, skills, chats, settings and keys on this phone", "Bu telefondaki dosyalar, hafıza, yetenekler, sohbetler, ayarlar ve anahtarlar"), wrap: true, onclick: eraseFlow })
      ], T("An export holds everything: files (pictures and documents too), memory, skills, lessons, the task board and every chat with its tool calls. API keys only when you switch them on.", "Dışa aktarım her şeyi içerir: dosyalar (resim ve belgeler dahil), hafıza, yetenekler, dersler, görev panosu ve araç çağrılarıyla tüm sohbetler. API anahtarları yalnızca açarsanız.")));
    });
  }
  /* the original's raw MEMORY.md / USER.md editor (entries separated by §), with a character meter */
  function rawMemory(target) {
    call("memory.raw", { target: target }).then(function (r) {
      var ta = h("textarea", { class: "fedit", spellcheck: "false" }); ta.value = r.text || "";
      var meter = h("small", { class: "muted" });
      function upd() { meter.textContent = ta.value.length.toLocaleString() + (r.limit ? " / " + r.limit.toLocaleString() : "") + T(" characters", " karakter"); }
      ta.addEventListener("input", upd); upd();
      var sh = sheet({ title: target === "user" ? "USER.md" : "MEMORY.md", text: T("One entry per block; put a line with § between entries.", "Her blok bir kayıt; kayıtların arasına § içeren bir satır koyun."), body: h("div", null, ta, meter),
        actions: [[T("Cancel", "İptal")], [T("Save", "Kaydet"), "p", function () { call("memory.rawSave", { target: target, text: ta.value }).then(function (x) { toast(T("Saved · " + x.entries + " entries", "Kaydedildi · " + x.entries + " kayıt")); I.kbChanged(); }).catch(fail); }]] });
      sh.el.classList.add("tall");
    }).catch(fail);
  }
  function fmtTok(n) { n = n || 0; return n >= 1e6 ? (n / 1e6).toFixed(1) + "M" : n >= 1e3 ? Math.round(n / 1e3) + "k" : String(n); }
  function statsSheet(St) {
    var by = St.byPurpose || {}, keys = Object.keys(by).sort(function (a, b) { return by[b] - by[a]; }), max = Math.max.apply(null, keys.map(function (k) { return by[k]; }).concat([1]));
    Promise.resolve(call("s1.state").catch(function () { return null; })).then(function (s1) {
      var ss = (s1 && s1.stats) || {};
      var cards = h("div", { class: "kstats" }, [[T("Model calls", "Model çağrısı"), St.llmCalls || 0], [T("Tokens in", "Giden token"), fmtTok(St.tokensIn)], [T("Tokens out", "Gelen token"), fmtTok(St.tokensOut)],
        [T("System-1 calls", "System-1 çağrısı"), ss.calls || ss.s1Calls || 0], [T("Settled", "Karara bağlanan"), ss.settled || 0], [T("Escalated", "Üste aktarılan"), ss.escalated || 0]].map(function (c) { return h("div", { class: "kstat" }, h("b", null, String(c[1])), h("span", null, c[0])); }));
      var bars = h("div", { class: "pbars" }, keys.map(function (k) { return h("div", { class: "pbar" }, h("span", null, k), h("i", { style: "width:" + Math.max(4, Math.round(by[k] / max * 100)) + "%" }), h("b", null, String(by[k]))); }));
      sheet({ title: T("Usage", "Kullanım"), text: St.since ? T("Since ", "Şu tarihten beri: ") + new Date(St.since).toLocaleString() : null, body: h("div", null, cards, h("h4", { class: "sub" }, T("Model calls by purpose", "Amaca göre model çağrıları")), keys.length ? bars : h("p", { class: "muted" }, T("No calls yet.", "Henüz çağrı yok."))),
        actions: [[T("Close", "Kapat")], [T("Reset counters", "Sayaçları sıfırla"), "", function () { call("stats.reset").then(function () { call("s1.set", { resetStats: true }).catch(function () {}); toast(T("Counters reset", "Sayaçlar sıfırlandı")); if (I.topPage() === "settings") I.renderSettings(); }).catch(fail); }]] });
    });
  }

  /* ================================================================ export / import / erase */
  function exportRow() {
    return I.row({ icon: "download", label: T("Export everything (.zip)", "Her şeyi dışa aktar (.zip)"), sub: T("A full backup, to keep or to move to another phone", "Saklamak ya da başka telefona taşımak için tam yedek"), wrap: true, onclick: function () {
      var keys = !!(P.llmx && P.llmx.exportKeys);
      var sw = I.row({ icon: "key-round", label: T("Include API keys and passwords", "API anahtarlarını ve şifreleri ekle"), sub: T("Stored readable in the file — keep it safe", "Dosyada okunur biçimde durur — güvende tutun"), wrap: true, toggle: keys, onToggle: function (on) { keys = on; } });
      sheet({ title: T("Export everything", "Her şeyi dışa aktar"), text: T("Files, memory, skills, lessons, the task board, chats with tool calls and settings go into one .zip in Download/yaver.", "Dosyalar, hafıza, yetenekler, dersler, görev panosu, araç çağrılarıyla sohbetler ve ayarlar Download/yaver içinde tek bir .zip'e gider."), body: h("div", { class: "list" }, sw),
        actions: [[T("Cancel", "İptal")], [T("Export", "Dışa aktar"), "p", function () {
          toast(T("Exporting…", "Dışa aktarılıyor…"));
          call("state.export", { keys: keys, share: false }).then(function (r) {
            sheet({ title: T("Exported", "Dışa aktarıldı"), text: (r.saved || "") + " · " + r.files + T(" files, ", " dosya, ") + r.sessions + T(" chats, ", " sohbet, ") + r.skills + T(" skills · ", " yetenek · ") + I.bytes(r.bytes) + (r.keys ? " · " + T("with keys", "anahtarlarla") : ""),
              actions: [[T("Close", "Kapat")], [T("Share", "Paylaş"), "p", function () { call("state.export", { keys: keys, share: true }).catch(fail); }]] });
          }).catch(fail);
        }]] });
    } });
  }
  function importRow() {
    return I.row({ icon: "upload", label: T("Import a yaver export", "yaver dışa aktarımını içe al"), sub: T(".zip from this app or the web yaver — merge or replace", "Bu uygulamadan ya da web yaver'den .zip — birleştir veya değiştir"), wrap: true, onclick: function () {
      call("import.zip", { preview: true }).then(function (pv) { if (pv && pv.token) importSheet(pv); }).catch(fail);
    } });
  }
  function importSheet(pv) {
    if (!pv.state && !pv.files) { toast(T("This is not a yaver export", "Bu bir yaver dışa aktarımı değil")); return; }
    var when = pv.exported ? new Date(pv.exported).toLocaleString() : T("an unknown time", "bilinmeyen bir zaman");
    function go(mode) {
      toast(T("Importing…", "İçe alınıyor…"));
      call("state.importApply", { token: pv.token, mode: mode }).then(function (r) {
        I.refreshConvos(); I.kbChanged();
        if (r.restart) sheet({ title: T("Restored", "Geri yüklendi"), text: r.message + ". " + T("yaver starts again to load everything.", "Her şeyi yüklemek için yaver yeniden başlıyor."), modal: true, actions: [[T("Restart now", "Şimdi yeniden başlat"), "p", function () { call("app.restart").catch(fail); }]] });
        else toast(r.message);
      }).catch(fail);
    }
    sheet({ title: T("Import", "İçe al") + " · " + (pv.name || ""), text: T("Exported ", "Dışa aktarma: ") + when + (pv.app === "html" ? " · " + T("from the web yaver", "web yaver'den") : ""),
      body: h("div", null, h("p", null, pv.files + T(" files, including ", " dosya; içinde ") + pv.sessions + T(" chats and ", " sohbet ve ") + pv.skills + T(" skills", " yetenek") + (pv.native ? T(", plus lessons, the task board and chat state", ", ayrıca dersler, görev panosu ve sohbet durumu") : "") + (pv.secrets ? T(", plus API keys", ", ayrıca API anahtarları") : "") + "."),
        h("p", { class: "muted" }, T("Merge: writes the export's files over this phone's and keeps everything else (memories are united). Replace everything: wipes files, skills, chats and yaver's stores first, then loads the export.", "Birleştir: dışa aktarımın dosyalarını bu telefondakilerin üzerine yazar, geri kalan her şeyi korur (hafıza birleştirilir). Her şeyi değiştir: önce dosyaları, yetenekleri, sohbetleri ve yaver'in kayıtlarını siler, sonra dışa aktarımı yükler."))),
      actions: [[T("Cancel", "İptal")], [T("Replace everything", "Her şeyi değiştir"), "danger", function () {
        sheet({ title: T("Replace everything?", "Her şey değiştirilsin mi?"), text: T("This phone's files, skills, chats and memory are deleted first. This cannot be undone.", "Bu telefondaki dosyalar, yetenekler, sohbetler ve hafıza önce silinir. Geri alınamaz."), actions: [[T("Cancel", "İptal")], [T("Replace", "Değiştir"), "danger", function () { go("replace"); }]] });
      }], [T("Merge", "Birleştir"), "p", function () { go("merge"); }]] });
  }
  function eraseFlow() {
    sheet({ title: T("Erase everything?", "Her şey silinsin mi?"), text: T("Files, memory, skills, lessons, chats, settings, API keys and sign-ins on this phone are deleted. Export first if you want to keep them.", "Bu telefondaki dosyalar, hafıza, yetenekler, dersler, sohbetler, ayarlar, API anahtarları ve oturumlar silinir. Saklamak istiyorsanız önce dışa aktarın."),
      actions: [[T("Cancel", "İptal")], [T("Continue", "Devam"), "danger", function () {
        I.editSheet({ title: T("Type ERASE to confirm", "Onaylamak için ERASE yazın"), placeholder: "ERASE", ok: T("Erase", "Sil"), save: function (v) {
          if (v.trim() !== "ERASE") { toast(T("Type ERASE in capitals", "Büyük harflerle ERASE yazın")); return false; }
          call("state.erase", { confirm: "ERASE" }).then(function () { toast(T("Erased — yaver starts again", "Silindi — yaver yeniden başlıyor")); }).catch(fail);
        } });
      }]] });
  }
  /* a .zip attached in the composer: an export opens the import, another zip can be extracted into uploads */
  function afterAttach(list) {
    list.filter(function (f) { return /\.zip$/i.test(f.path || ""); }).forEach(function (f) {
      call("state.stagePath", { path: f.path }).then(function (pv) {
        if (pv.state) { importSheet(pv); return; }
        sheet({ title: base(f.path), text: T("Extract this archive into ", "Bu arşiv şuraya açılsın: ") + f.path.replace(/\.zip$/i, "/") + "?", actions: [[T("Keep the zip", "Zip kalsın")], [T("Extract", "Aç"), "p", function () {
          call("state.extract", { path: f.path }).then(function (r) {
            var i = S.atts.indexOf(f); if (i >= 0) S.atts[i] = { path: r.dir, name: base(r.dir) + "/", image: false };
            I.renderAtts(); I.syncSend(); toast(T("Extracted " + r.files + " files", r.files + " dosya açıldı"));
          }).catch(fail);
        }]] });
      }).catch(function () {});
    });
  }

  /* ================================================================ skills editor (Knowledge → Skills) */
  function skillsView(box, list) {
    box.appendChild(h("p", { class: "kexpl" }, T("Procedures yaver saved to reuse: each skill is a folder with instructions (SKILL.md) and helper files. yaver opens the right one when a task matches.", "yaver'in tekrar kullanmak için kaydettiği yöntemler: her yetenek talimatlar (SKILL.md) ve yardımcı dosyalar içeren bir klasördür. Görev uyduğunda yaver doğru olanı açar.")));
    box.appendChild(h("div", { class: "kbar2" }, h("button", { class: "btn sm p", onclick: newSkill }, icon("plus", "s"), T("New skill", "Yeni yetenek"))));
    var lb = h("div", { class: "klist" });
    box.appendChild(lb);
    function draw(l) {
      clear(lb);
      if (!l.length) { lb.appendChild(h("div", { class: "kempty" }, icon("graduation-cap"), T("No skills yet. Ask yaver to “save this as a skill”, or make one with New skill.", "Henüz yetenek yok. yaver'e “bunu yetenek olarak kaydet” deyin ya da Yeni yetenek ile oluşturun."))); return; }
      l.forEach(function (s) {
        lb.appendChild(h("button", { class: "kcard", onclick: function () { skillPage(s.dir); } },
          h("div", { class: "kbad" }, h("span", { class: "bdg tp" }, icon("graduation-cap", "xs"), s.dir || s.name), (s.files || []).length > 1 ? h("span", { class: "bdg" }, (s.files.length) + T(" files", " dosya")) : null,
            s.uses ? h("span", { class: "bdg" }, T(s.uses + "× used", s.uses + " kez")) : null),
          h("div", { class: "ktext" }, h("b", null, s.name)), s.description ? h("div", { class: "kfacts clamp" }, s.description) : h("div", { class: "kfacts" }, T("(no description — yaver cannot tell when to use it)", "(açıklama yok — yaver ne zaman kullanacağını bilemez)"))));
      });
    }
    draw(list || []);
    call("skills.list2").then(draw).catch(function () {});
  }
  function newSkill() {
    I.editSheet({ title: T("New skill", "Yeni yetenek"), placeholder: T("e.g. weekly-report", "ör. haftalik-rapor"), help: T("A short name; the folder name is made from it.", "Kısa bir ad; klasör adı bundan oluşturulur."), ok: T("Create", "Oluştur"), save: function (v) {
      if (!v.trim()) { toast(T("Give it a name", "Bir ad verin")); return false; }
      call("skills.new", { name: v }).then(function (s) { I.kbChanged(); if (s.existed) toast(T("A skill with this name exists", "Bu adda bir yetenek var")); skillPage(s.dir, !s.existed); }).catch(fail);
    } });
  }
  function skillPage(dir, edit) {
    I.pushPage("skill", T("Skill", "Yetenek"), function (body, rec) {
      function load() { call("skills.get", { dir: dir }).then(function (s) { render(s); }).catch(function (e) { clear(body).appendChild(h("div", { class: "kempty" }, e.message)); }); }
      function render(s) {
        rec.title.firstChild.textContent = s.name;
        clear(body);
        var others = (s.files || []).filter(function (f) { return f.name !== "SKILL.md"; });
        body.appendChild(h("div", { class: "skp" },
          h("div", { class: "skh" }, h("div", { class: "kfacts" }, s.description || T("(no description — yaver cannot tell when to use it)", "(açıklama yok — yaver ne zaman kullanacağını bilemez)")),
            h("dl", { class: "dl" }, h("dt", null, T("Folder", "Klasör")), h("dd", null, s.path), h("dt", null, T("Used", "Kullanım")), h("dd", null, s.uses ? T(s.uses + "× · last " + new Date(s.last).toLocaleDateString(), s.uses + " kez · son " + new Date(s.last).toLocaleDateString()) : T("not yet", "henüz yok")))),
          h("div", { class: "skb" },
            h("button", { class: "btn sm p", onclick: function () { editSkill(s, load); } }, icon("pencil", "s"), T("Edit", "Düzenle")),
            h("button", { class: "btn sm", onclick: function () { call("skills.copy", { dir: s.dir }).then(function (c) { toast(T("Copied as ", "Kopyalandı: ") + c.dir); I.kbChanged(); I.popPage(); skillPage(c.dir); }).catch(fail); } }, icon("copy", "s"), T("Duplicate", "Kopyala")),
            h("button", { class: "btn sm ghost", onclick: function () {
              sheet({ title: T("Delete the skill “" + s.name + "”?", "“" + s.name + "” yeteneği silinsin mi?"), text: T("Its " + s.files.length + " file(s) are deleted. This cannot be undone.", s.files.length + " dosyası silinir. Geri alınamaz."),
                actions: [[T("Cancel", "İptal")], [T("Delete", "Sil"), "danger", function () { call("skills.delete", { dir: s.dir }).then(function () { toast(T("Skill deleted", "Yetenek silindi")); I.kbChanged(); I.popPage(); }).catch(fail); }]] });
            } }, icon("trash-2", "s"), T("Delete", "Sil")),
            h("button", { class: "btn sm ghost", onclick: function () { I.closeAllPages(); var ta = $("#input"); ta.value = T("Use the skill “" + s.name + "” to ", "“" + s.name + "” yeteneğini kullanarak "); I.autosize(); I.syncSend(); ta.focus(); } }, icon("play", "s"), T("Use it", "Kullan"))),
          h("div", { class: "skmd" }, I.md(s.body || "")),
          h("div", { class: "skf" }, h("div", { class: "skfh" }, h("b", null, T("Files", "Dosyalar") + " (" + s.files.length + ")"),
              h("button", { class: "btn xs", onclick: function () {
                I.editSheet({ title: T("Add a file", "Dosya ekle"), placeholder: "references/checklist.md", help: T("A path inside the skill folder", "Yetenek klasörü içinde bir yol"), ok: T("Add", "Ekle"), save: function (v) {
                  if (!v.trim()) return false;
                  call("skills.addFile", { dir: s.dir, name: v }).then(function (r) { load(); return call("files.read", { path: r.path }); }).then(function (f) { if (f && I.editFile) I.editFile(f); }).catch(fail);
                } });
              } }, icon("plus", "xs"), T("Add file", "Dosya ekle"))),
            h("div", { class: "list" }, [{ name: "SKILL.md", path: s.path + "/SKILL.md", size: (s.raw || "").length, text: true }].concat(others).map(function (f) {
              return h("div", { class: "row skrow" }, h("div", { class: "tile" }, icon(f.text ? "file-text" : "file-archive", "s")),
                h("button", { class: "rt", onclick: function () { I.previewFile(f.path); } }, h("b", null, f.name), h("span", null, I.bytes(f.size || 0))),
                f.text ? h("button", { class: "ib", "aria-label": T("Edit", "Düzenle") + " " + f.name, onclick: function () { if (f.name === "SKILL.md") { editSkill(s, load); return; } call("files.read", { path: f.path }).then(function (x) { if (I.editFile) I.editFile(x); }).catch(fail); } }, icon("pencil", "s")) : null);
            })))));
      }
      load();
      if (edit) call("skills.get", { dir: dir }).then(function (s) { editSkill(s, load); }).catch(function () {});
      rec.reload = load;
    });
  }
  function editSkill(s, after) {
    var nm = h("input", { type: "text", value: s.name, autocapitalize: "off" }), ds = h("input", { type: "text", value: s.description || "", placeholder: T("e.g. Use when the user asks for the weekly status report", "ör. Kullanıcı haftalık durum raporu istediğinde kullan") });
    var ta = h("textarea", { class: "fedit", spellcheck: "false" }); ta.value = s.body || "";
    var sh = sheet({ title: T("Edit skill", "Yeteneği düzenle"), body: h("div", null,
        h("label", { class: "field" }, h("span", null, T("Name", "Ad")), nm),
        h("label", { class: "field" }, h("span", null, T("When to use it (yaver picks skills by this)", "Ne zaman kullanılır (yaver yetenekleri buna göre seçer)")), ds),
        h("label", { class: "field" }, h("span", null, T("Instructions (markdown)", "Talimatlar (markdown)")), ta)),
      actions: [[T("Cancel", "İptal")], [T("Save", "Kaydet"), "p", function () {
        call("skills.save", { dir: s.dir, name: nm.value, description: ds.value, body: ta.value }).then(function () { toast(T("Skill saved", "Yetenek kaydedildi")); I.kbChanged(); if (after) after(); }).catch(fail);
      }]] });
    sh.el.classList.add("tall");
  }

  /* ================================================================ welcome shortcuts (editable) */
  var SC_ICONS = ["sun", "inbox", "calendar", "search", "alarm-clock", "mail", "file-text", "square-kanban", "globe", "sparkles", "map", "brain", "clock", "target", "message-square", "folder"];
  function shortcutIdeas(ideas) {
    if (!P.shortcuts) return ideas;
    return P.shortcuts.map(function (x) { return [x.icon || "sparkles", x.t, x.def === "plan" ? "plan" : null, x.p || x.t, x.open || null]; });
  }
  function defaultsFrom(ideas) { return ideas.map(function (it, i) { return { id: "d" + i, icon: it[0], t: it[1], p: it[1], def: it[2] || null }; }); }
  function saveShortcuts(l) { return call("shortcuts.set", { items: l }).then(function (r) { P.shortcuts = r.items || null; if (!S.convo || !(S.convo.messages || []).length) I.renderThread(); }); }
  function shortcutsPage() {
    I.pushPage("shortcuts", T("Welcome shortcuts", "Karşılama kısayolları"), function (body) {
      var defaults = null;
      function list() { return P.shortcuts ? P.shortcuts.slice() : defaults; }
      function draw() {
        clear(body);
        var l = list();
        body.appendChild(h("p", { class: "kexpl" }, T("What a new chat suggests. Tap to edit; a shortcut sends its prompt, or opens Mail, the calendar or the task board.", "Yeni sohbetin önerdikleri. Düzenlemek için dokunun; kısayol istemini gönderir ya da Posta, takvim veya görev panosunu açar.")));
        body.appendChild(h("div", { class: "list" }, l.map(function (x, i) {
          return h("div", { class: "row skrow" }, h("div", { class: "tile" }, icon(x.icon || "sparkles", "s")),
            h("button", { class: "rt", onclick: function () { editShortcut(l, i, draw); } }, h("b", null, x.t), h("span", null, x.open ? T("Opens ", "Açar: ") + x.open : x.def === "plan" ? T("Plans your day", "Gününüzü planlar") : (x.p || ""))),
            h("button", { class: "ib", "aria-label": T("Move up", "Yukarı taşı"), disabled: i === 0 ? true : null, onclick: function () { var m = l.splice(i, 1)[0]; l.splice(i - 1, 0, m); saveShortcuts(l).then(draw); } }, icon("move-up", "s")),
            h("button", { class: "ib", "aria-label": T("Move down", "Aşağı taşı"), disabled: i === l.length - 1 ? true : null, onclick: function () { var m = l.splice(i, 1)[0]; l.splice(i + 1, 0, m); saveShortcuts(l).then(draw); } }, icon("move-down", "s")),
            h("button", { class: "ib", "aria-label": T("Delete", "Sil"), onclick: function () { l.splice(i, 1); saveShortcuts(l).then(draw); } }, icon("trash-2", "s")));
        })));
        body.appendChild(h("div", { class: "skb" },
          h("button", { class: "btn sm p", onclick: function () { l.push({ id: "s" + Date.now().toString(36), icon: "sparkles", t: "", p: "" }); editShortcut(l, l.length - 1, draw, true); } }, icon("plus", "s"), T("Add", "Ekle")),
          h("button", { class: "btn sm ghost", onclick: function () { call("shortcuts.set", {}).then(function () { P.shortcuts = null; draw(); I.renderThread(); toast(T("Back to the defaults", "Varsayılanlara dönüldü")); }).catch(fail); } }, icon("rotate-ccw", "s"), T("Reset to defaults", "Varsayılanlara dön"))));
      }
      /* the defaults are the hello screen's built-in ideas */
      defaults = defaultsFrom(DEFAULT_IDEAS());
      draw();
    });
  }
  function DEFAULT_IDEAS() {
    return [["sun", T("Plan my day", "Günümü planla"), "plan"], ["inbox", T("What needs my reply in my mail?", "Postalarımda yanıt bekleyenler neler?")], ["calendar", T("What's on my calendar this week?", "Bu hafta takvimimde neler var?")],
      ["search", T("Brief me on today's news in my field", "Bugün alanımdaki önemli haberleri özetle")], ["alarm-clock", T("Remind me tomorrow at 9 to call the supplier", "Yarın 9'da tedarikçiyi aramamı hatırlat")]];
  }
  function editShortcut(l, i, after, isNew) {
    var x = l[i];
    var t = h("input", { type: "text", value: x.t || "" }), p = h("textarea", { rows: 3 }); p.value = x.p || "";
    var ic = x.icon || "sparkles", openT = x.open || "";
    var icons = h("div", { class: "icpick" }, SC_ICONS.map(function (n) { var b = h("button", { class: "ib" + (n === ic ? " on" : ""), "aria-label": n, onclick: function () { ic = n; icons.querySelectorAll(".ib").forEach(function (e) { e.classList.remove("on"); }); b.classList.add("on"); } }, icon(n, "s")); return b; }));
    var targets = h("div", { class: "dbgbar" }, [["", T("Send the prompt", "İstemi gönder")], ["mail", T("Mail", "Posta")], ["cal", T("Calendar", "Takvim")], ["tasks", T("Tasks", "Görevler")]].map(function (o) {
      var b = h("button", { class: "cchip" + (o[0] === openT ? " on" : ""), onclick: function () { openT = o[0]; targets.querySelectorAll(".cchip").forEach(function (e) { e.classList.remove("on"); }); b.classList.add("on"); } }, o[1]); return b; }));
    sheet({ title: isNew ? T("New shortcut", "Yeni kısayol") : T("Edit shortcut", "Kısayolu düzenle"), body: h("div", null,
        h("label", { class: "field" }, h("span", null, T("Title", "Başlık")), t), h("label", { class: "field" }, h("span", null, T("Prompt (empty = the title)", "İstem (boş = başlık)")), p),
        h("div", { class: "field" }, h("span", null, T("Icon", "Simge")), icons), h("div", { class: "field" }, h("span", null, T("Action", "Eylem")), targets)),
      actions: [[T("Cancel", "İptal"), "", function () { if (isNew) l.splice(i, 1); }], [T("Save", "Kaydet"), "p", function () {
        if (!t.value.trim()) { toast(T("Give it a title", "Bir başlık verin")); return false; }
        l[i] = { id: x.id || "s" + Date.now().toString(36), icon: ic, t: t.value.trim(), p: p.value.trim() || t.value.trim(), open: openT || null, def: openT || p.value.trim() !== (x.p || "") ? null : x.def || null };
        saveShortcuts(l).then(after).catch(fail);
      }]] });
  }
  function helloExtras(pills) {
    /* System-1 status and the language switch, as the original's welcome panel */
    var s1 = h("button", { class: "pill", onclick: function () { I.openSettings("learn"); } }, h("span", { class: "dot" }), "System-1");
    call("s1.state").then(function (r) { var b = r && r.cfg && r.cfg.backend; var on = b && b !== "off"; s1.lastChild.textContent = on ? "System-1 · " + b : T("System-1 off", "System-1 kapalı"); s1.firstChild.className = "dot " + (on ? "ok" : ""); }).catch(function () {});
    var lang = h("button", { class: "pill", "aria-label": T("Language", "Dil"), onclick: function () {
      var next = S.lang === "tr" ? "en" : "tr";
      I.setCfg("lang", next).then(function (st) { if (!st) return; document.documentElement.lang = S.lang; I.renderShell(); });
    } }, icon("languages", "xs"), S.lang === "tr" ? "TR" : "EN");
    pills.appendChild(s1); pills.appendChild(lang);
    return h("button", { class: "more editsc", onclick: shortcutsPage }, icon("pencil", "xs"), T("Edit shortcuts", "Kısayolları düzenle"));
  }

  /* ================================================================ shell: chips, title, keyboard, paste */
  function shell() { renderChips(); }
  function titleTap() { if (I.topPage()) return; I.pickModel("model"); }
  document.addEventListener("keydown", function (e) {
    if (e.altKey && !e.ctrlKey && (e.key === "n" || e.key === "N")) { e.preventDefault(); I.newChat(true); }
    else if (e.altKey && !e.ctrlKey && (e.key === "l" || e.key === "L")) { e.preventDefault(); if (cid()) I.runCommand("/clear"); }
    else if (e.key === "Escape") { if (window.YV && YV.back()) e.preventDefault(); }
    else if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.target && e.target.id === "input" && window.matchMedia && matchMedia("(hover: hover) and (pointer: fine)").matches) {
      /* a physical keyboard and mouse: Enter sends, Shift+Enter is a new line (the soft keyboard keeps Enter = new line) */
      if (e.target.value.trim() || S.atts.length) { e.preventDefault(); var b = $("#send"); if (b) b.click(); }
    }
  });
  document.addEventListener("paste", function (e) {
    if (!e.target || e.target.id !== "input" || !e.clipboardData) return;
    var items = [].slice.call(e.clipboardData.items || []).filter(function (it) { return it.kind === "file"; });
    if (!items.length) return;
    e.preventDefault();
    items.forEach(function (it) {
      var f = it.getAsFile(); if (!f) return;
      var rd = new FileReader();
      rd.onload = function () {
        var ext = (f.type.split("/")[1] || "png").replace("jpeg", "jpg");
        call("files.upload", { name: f.name && f.name !== "image." + ext ? f.name : "pasted-" + new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-") + "." + ext, b64: String(rd.result) }).then(function (r) {
          S.atts.push(r); I.renderAtts(); I.syncSend(); afterAttach([r]);
        }).catch(fail);
      };
      rd.readAsDataURL(f);
    });
  });

  /* ================================================================ System-1 strip (the original's UI.chip "dstrip") */
  function s1Key(items) { return (items || []).map(function (x) { return Array.isArray(x) ? x[1] : String(x); }).join(" · "); }
  function strip(items, key) {
    return h("div", { class: "dstrip", "data-key": key || s1Key(items) }, h("span", { class: "lbl" }, icon("cpu", "xs"), "System 1"),
      items.map(function (x) { var k = Array.isArray(x) ? x[0] : "s1", t = Array.isArray(x) ? x[1] : String(x); return k === "muted" ? h("span", { class: "dmut" }, t) : h("span", { class: "dchip " + k }, t); }));
  }
  /* route chips saved on the user message: the strip of that turn (a live copy from the run's note is dropped) */
  function s1Strip(items) {
    var key = s1Key(items), th = $("#thread");
    if (th) th.querySelectorAll(".dstrip").forEach(function (e) { if (e.getAttribute("data-key") === key) { var row = e.closest(".chiprow") || e; row.remove(); } });
    return strip(items, key);
  }
  var LABELLED = /^(loop monitor|verify-on-stop|long input → variable|approval: .*|\d+ passages? attached.*|memory search|hafıza araması)$/i;
  /* a note from the run: System-1 / harness decisions become a strip; undefined = an ordinary note */
  function noteStrip(text, seen) {
    var t = String(text || ""), m;
    if ((m = /^S1 · ([\s\S]+)$/.exec(t))) {
      var rest = m[1], parts = rest.split(" · ");
      if (seen && rest === seen) return null;
      var items = parts.length > 1 && LABELLED.test(parts[0]) ? [["s1", parts[0]], ["muted", parts.slice(1).join(" · ")]]
        : parts.map(function (p) { return [/unavailable|kullanılamıyor/i.test(p) ? "warn" : /LLM decides|unsure|LLM karar/i.test(p) ? "llm" : "s1", p]; });
      return strip(items, rest);
    }
    if ((m = /^(\+(?:tools|araçlar): [^·]+?) · ([\s\S]+)$/.exec(t))) return strip([["warn", m[1]], ["muted", m[2]]]);
    if ((m = /^(memory rule|hafıza kuralı|research rule|araştırma kuralı) · ([\s\S]+)$/.exec(t))) return strip([["s1", m[1]], ["muted", m[2]]]);
    return undefined;
  }

  /* ================================================================ tool card: file paths in the result, "file made" link (the original's toolResult) */
  var PATH_RX = /(?:^|[\s"'`(\[:=])((?:\/workspace|\/\.yaver|\/tmp|~)\/[^\s"'`)\]<>,;|]*[^\s"'`)\]<>,;|.]\.[A-Za-z0-9]{1,6})(?=$|[\s"'`)\]<>,;:|.])/g;
  var EXISTS = {};
  function stepExtras(el, result, ok) {
    var txt = String(result || "").slice(0, 6000), found = [], m;
    PATH_RX.lastIndex = 0;
    while ((m = PATH_RX.exec(txt)) && found.length < 40) if (found.indexOf(m[1]) < 0) found.push(m[1]);
    if (!found.length) return;
    var ask = found.filter(function (p) { return !(p in EXISTS); });
    (ask.length ? call("files.exists", { paths: ask }) : Promise.resolve({})).then(function (hit) {
      ask.forEach(function (p) { EXISTS[p] = (hit && hit[p]) || ""; });
      var real = found.filter(function (p) { return EXISTS[p]; });
      if (!real.length || !el.isConnected && !el.parentNode) return;
      var res = el.querySelector(".res");
      if (res) {
        /* the result text with its existing paths as links */
        var frag = document.createDocumentFragment(), last = 0, mm; PATH_RX.lastIndex = 0;
        var src = res.textContent;
        while ((mm = PATH_RX.exec(src))) {
          var p = mm[1]; if (!EXISTS[p]) continue;
          var at = mm.index + mm[0].lastIndexOf(p);
          frag.appendChild(document.createTextNode(src.slice(last, at)));
          frag.appendChild((function (vp) { return h("a", { class: "flink", href: "#", "data-path": vp, onclick: function (e) { e.preventDefault(); e.stopPropagation(); I.previewFile(vp); } }, p); })(EXISTS[p]));
          last = at + p.length;
        }
        frag.appendChild(document.createTextNode(src.slice(last)));
        clear(res).appendChild(frag);
      }
      if (ok) {
        var made = real[real.length - 1], st = el.querySelector(".st"), vp = EXISTS[made];
        if (st && !st.querySelector(".stepopen")) st.insertBefore(h("button", { class: "stepopen", "aria-label": T("Open ", "Aç: ") + base(made), onclick: function (e) { e.stopPropagation(); I.previewFile(vp); } }, icon("eye", "xs"), h("span", null, base(made))), st.querySelector(".tms") || st.querySelector(".targs") || null);
      }
    }).catch(function () {});
  }

  /* ================================================================ the agent's browser, live (the original's Browser pane) */
  function browserPage() {
    if (I.topPage() === "browser") return;
    I.closeDrawer();
    var mode = "picture", timer = 0, busy = false, lastUrl = "";
    var url = h("input", { type: "url", class: "burl", placeholder: T("Address or search", "Adres veya arama"), "aria-label": T("Address", "Adres"), autocapitalize: "off", spellcheck: "false" });
    var view = h("div", { class: "bview" }), info = h("div", { class: "binfo muted" });
    var bMode = h("button", { class: "cchip", "aria-pressed": "false", onclick: function () { mode = mode === "text" ? "picture" : "text"; bMode.classList.toggle("on", mode === "text"); bMode.setAttribute("aria-pressed", String(mode === "text")); bMode.lastChild.textContent = mode === "text" ? T("Text", "Metin") : T("Picture", "Resim"); tick(true); } }, icon("text", "xs"), h("span", null, T("Picture", "Resim")));
    function go() { var v = url.value.trim(); if (!v) return; call("browser.go", { url: v }).then(function (r) { url.value = r.url; url.blur(); setTimeout(function () { tick(true); }, 700); }).catch(fail); }
    url.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); go(); } });
    var rec = I.pushPage("browser", T("The agent's browser", "Ajanın tarayıcısı"), function (body) {
      body.appendChild(h("div", { class: "bpage" },
        h("div", { class: "bbar" },
          h("button", { class: "ib", "aria-label": T("Back", "Geri"), onclick: function () { call("browser.back").then(function () { setTimeout(function () { tick(true); }, 600); }).catch(fail); } }, icon("arrow-left", "s")),
          h("button", { class: "ib", "aria-label": T("Reload", "Yenile"), onclick: function () { call("browser.reload").then(function () { setTimeout(function () { tick(true); }, 600); }).catch(fail); } }, icon("rotate-cw", "s")),
          url, h("button", { class: "btn sm p", onclick: go }, T("Go", "Git"))),
        h("div", { class: "dbgbar" }, bMode,
          h("button", { class: "cchip", onclick: function () { call("browser.takeover").catch(fail); } }, icon("mouse-pointer-click", "xs"), h("span", null, T("Take over", "Devral"))),
          h("button", { class: "cchip", onclick: function () { if (lastUrl) call("open.link", { url: lastUrl }).catch(fail); } }, icon("external-link", "xs"), h("span", null, T("Open outside", "Dışarıda aç"))), info),
        view));
    });
    function tick(now) {
      clearTimeout(timer);
      if (!rec.el.isConnected || S.pages.indexOf(rec) < 0) return;
      if (busy && !now) { timer = setTimeout(tick, 1500); return; }
      busy = true;
      call("browser.live", { mode: mode }).then(function (r) {
        busy = false;
        if (!r || !r.open || !r.url) {
          clear(view).appendChild(h("div", { class: "kempty" }, icon("app-window"), T("yaver's browser has no page open. It opens one when a task needs a website — or type an address above.", "yaver'in tarayıcısında açık sayfa yok. Bir iş web sitesi gerektirince açar — ya da yukarıya bir adres yazın.")));
          info.textContent = ""; lastUrl = "";
        } else {
          lastUrl = r.url;
          if (document.activeElement !== url) url.value = r.url;
          info.textContent = (r.loading ? T("Loading… · ", "Yükleniyor… · ") : "") + (r.title || "");
          if (mode === "text") { var pre = view.querySelector("pre.btext") || clear(view).appendChild(h("pre", { class: "btext" })); if (pre.textContent !== (r.text || "")) pre.textContent = r.text || ""; }
          else if (r.image) { var im = view.querySelector("img.bshot") || clear(view).appendChild(h("img", { class: "bshot", alt: r.title || r.url })); if (im.getAttribute("src") !== r.image) im.setAttribute("src", r.image); }
          else if (r.error) clear(view).appendChild(h("p", { class: "muted" }, r.error));
        }
        timer = setTimeout(tick, document.hidden ? 4000 : 1500);
      }).catch(function (e) { busy = false; info.textContent = e.message; timer = setTimeout(tick, 4000); });
    }
    tick(true);
  }

  window.YVP = { changesCard: changesCard, answerExtras: answerExtras, dividerExtras: dividerExtras, foldForgotten: foldForgotten, onCommand: onCommand, chatMenuItems: chatMenuItems, convoMenuItems: convoMenuItems,
    permRow: permRow, settings: settings, exportRow: exportRow, importRow: importRow, skillsView: skillsView, shortcutIdeas: shortcutIdeas, openTarget: openTarget, helloExtras: helloExtras,
    shell: shell, titleTap: titleTap, afterAttach: afterAttach, debugSheet: debugSheet, applyTheme: applyTheme,
    s1Strip: s1Strip, s1Key: s1Key, noteStrip: noteStrip, stepExtras: stepExtras, browserPage: browserPage };

  /* start: theme, shortcuts, chips (app.js has already drawn the shell) */
  try { applyTheme(localStorage.getItem("yv.theme") || "system"); } catch (e) {}
  call("shortcuts.get").then(function (r) { P.shortcuts = r && r.items || null; if (P.shortcuts && (!S.convo || !(S.convo.messages || []).length)) I.renderThread(); }).catch(function () {});
  var tries = 0; (function waitState() { if (S.state) { applyTheme(curTheme()); renderChips(); return; } if (tries++ < 50) setTimeout(waitState, 100); })();
})();
