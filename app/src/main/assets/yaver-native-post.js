/* yaver for Android — runs after yaver's own script. Adapts yaver to the phone:
   native web access (no proxies), the mail login screen instead of the bookmarklet, phone tools for the
   agent (calendar, alarms, reminders, notifications, apps), a microphone button, notifications while away,
   and the assistant / shortcut / share launches. */
(function () {
  "use strict";
  var A = window.YaverAndroid, yn = window.__yn;
  if (!A || !yn) return;

  function isTr() { try { return uiLang() === "tr"; } catch (e) { return /^tr/i.test(navigator.language || ""); } }
  function T(en, tr) { return isTr() ? tr : en; }
  function safe(f) { try { return f(); } catch (e) { console.error("[yaver-android]", e); } }
  function pref(k) { try { return A.getPref(k) || ""; } catch (e) { return ""; } }

  /* =========================== 1. web access =========================== */
  safe(function () { RAW_PROXIES.length = 0; });
  safe(function () {
    if (!CFG.web.proxy || /corsproxy|allorigins|codetabs|r\.jina\.ai/i.test(CFG.web.proxy)) { CFG.web.proxy = "{url}"; CFG.web.proxyHeaders = "{}"; saveCfg(); }
  });
  safe(function () {
    NET.fetch = async function (url, opts) {
      opts = opts || {};
      var method = opts.method || "GET";
      if (!/^https?:\/\//i.test(url)) throw new Error("Only http(s) URLs are supported: " + url);
      var h = Object.assign({}, opts.headers || {});
      delete h["X-Return-Format"];
      var has = function (n) { return Object.keys(h).some(function (k) { return k.toLowerCase() === n; }); };
      if (!has("accept")) h.Accept = opts.binary ? "*/*" : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
      if (!has("accept-language")) h["Accept-Language"] = (navigator.language || "en") + ",en;q=0.8";
      var r;
      try { r = await fetch(url, { method: method, headers: h, body: opts.body, signal: opts.signal }); }
      catch (e) { if (e && e.name === "AbortError") throw e; throw new Error("Could not open " + url + ": " + ((e && e.message) || e)); }
      if (!r.ok) {
        var why = "";
        try { why = (await r.clone().text()).replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim().slice(0, 160); } catch (e) {}
        throw new Error("HTTP " + r.status + " fetching " + url + (why ? " — " + why : ""));
      }
      return r;
    };
  });
  /* web search: DuckDuckGo as before, Bing as a fallback when DuckDuckGo answers with a challenge page */
  safe(function () {
    var orig = window.webSearch;
    if (typeof orig !== "function") return;
    async function bing(q, n) {
      var html = await (await NET.fetch("https://www.bing.com/search?q=" + encodeURIComponent(q) + "&setlang=" + (isTr() ? "tr" : "en"))).text();
      var d = new DOMParser().parseFromString(html, "text/html"), out = [];
      d.querySelectorAll("li.b_algo").forEach(function (li) {
        if (out.length >= n) return;
        var a = li.querySelector("h2 a"); if (!a) return;
        var href = a.getAttribute("href") || ""; if (!/^https?:/.test(href)) return;
        var sn = li.querySelector(".b_caption p, p");
        out.push({ title: a.textContent.trim(), url: href, snippet: (sn ? sn.textContent : "").replace(/\s+/g, " ").trim().slice(0, 300) });
      });
      return out;
    }
    window.webSearch = async function (query, n) {
      n = n || 8;
      var first = null;
      try { var r = await orig(query, n); if (r && r.length) return r; } catch (e) { first = e; }
      var b = []; try { b = await bing(query, n); } catch (e) { if (!first) first = e; }
      if (b.length) return b;
      throw first || new Error("no search results");
    };
  });

  /* =========================== 2. company mail =========================== */
  safe(function () {
    var _connect = EWS.connect.bind(EWS);
    EWS.connect = function (via) {
      try { A.setPref("mailVia", via === "owa" ? "owa" : "ews"); A.setPref("mailUrl", CFG.mail.url || ""); A.setPref("mailEws", CFG.mail.ews || ""); } catch (e) {}
      return _connect(via);
    };
    var _raw = EWS.raw.bind(EWS);
    EWS.raw = function (body, o) {
      if (!this.alive()) return Promise.reject(new Error(T("Mail is not connected. Open Mail and tap Connect: log in on the screen that opens and yaver connects by itself.", "E-posta bağlı değil. Posta'yı açıp Bağlan'a dokunun: açılan ekranda oturum açın, yaver kendiliğinden bağlanır.")));
      return _raw(body, o);
    };
    /* the built-in mail screen never falls asleep like a browser tab */
    MailWake.suspect = function () {};
    MailWake.show = function () {};
  });
  safe(function () {
    window.bridgeMobileNote = function () { return null; };
    window.bridgeHelpNode = function (open) {
      var d = h("details", { class: "bridge-help" },
        h("summary", null, T("Trouble connecting?", "Bağlanamıyor musunuz?")),
        h("ul", null,
          h("li", null, T("Tap “Show login screen”, log in and wait until your inbox (or the EWS page) is visible. yaver connects by itself; if not, tap “Connect” at the top of that screen.", "“Giriş ekranını göster”e dokunun, oturum açın ve gelen kutunuz (veya EWS sayfası) görünene kadar bekleyin. yaver kendiliğinden bağlanır; bağlanmazsa o ekranın üstündeki “Connect”e dokunun.")),
          h("li", null, T("Outside the company network use “Connect via web mail (OWA)”.", "Kurum ağı dışındaysanız “Web mail (OWA) ile bağlan”ı kullanın.")),
          h("li", null, T("If the server asks for a user name, use DOMAIN\\user or your e-mail address.", "Sunucu kullanıcı adı sorarsa ETKİALANI\\kullanıcı veya e-posta adresinizi yazın."))));
      if (open) d.open = true;
      return d;
    };
    Mail.setupNode = function () {
      var ewsIn = h("input", { value: CFG.mail.ews || "", placeholder: "https://mail.company.com/EWS/Exchange.asmx", spellcheck: "false", onchange: function (e) { CFG.mail.ews = e.target.value.trim(); saveCfg(); } });
      var urlIn = h("input", { value: CFG.mail.url || "", placeholder: "https://mail.company.com/owa/", spellcheck: "false", onchange: function (e) { CFG.mail.url = e.target.value.trim(); saveCfg(); } });
      var save = function () { CFG.mail.ews = ewsIn.value.trim(); CFG.mail.url = urlIn.value.trim(); saveCfg(); };
      var waiting = !!(EWS.win && !EWS.win.closed && !EWS.connected);
      return h("div", { class: "mail-setup" },
        h("h4", null, T("Connect your company mail", "Kurum e-postanızı bağlayın")),
        h("p", { class: "muted" }, T("yaver opens your mail server's login page inside the app. Log in once — yaver connects by itself and reconnects next time. No bookmark is needed and your password never passes through yaver.", "yaver, posta sunucunuzun giriş sayfasını uygulamanın içinde açar. Bir kez oturum açın — yaver kendiliğinden bağlanır ve sonraki açılışta yeniden bağlanır. Yer imi gerekmez; parolanız yaver'den geçmez.")),
        EWS.lastError || waiting ? bridgeHelpNode(!!EWS.lastError) : null,
        h("ol", null,
          h("li", null, h("b", null, T("EWS address", "EWS adresi")), " (Exchange Web Services)", ewsIn),
          h("li", null, T("Outlook web address (OWA) ", "Outlook web adresi (OWA) "), h("span", { class: "muted" }, T("(for connecting from outside the company)", "(kurum dışından bağlanmak için)")), urlIn),
          h("li", null,
            h("button", { class: "small primary", onclick: function () { try { save(); EWS.connect(); } catch (e) { toast(e.message, 6000); } } }, icon("globe"), T("Connect", "Bağlan")), " ",
            h("button", { class: "small owa-connect", onclick: function () { try { save(); EWS.connect("owa"); } catch (e) { toast(e.message, 6000); } } }, icon("mail"), T("Connect via web mail (OWA)", "Web mail (OWA) ile bağlan")))),
        waiting ? h("div", { class: "lic-no" }, icon("alert"), h("div", null, T("Waiting for you to log in… ", "Oturum açmanız bekleniyor… "), h("button", { class: "small", onclick: function () { A.mailShow(); } }, T("Show login screen", "Giriş ekranını göster")))) : null,
        EWS.lastError ? h("div", { class: "errbox" }, icon("alert"), h("div", null, EWS.lastError)) : null);
    };
    MailAsk.render = function () {
      var t = null; try { t = ewsTarget(); } catch (e) {}
      var owaOk = /^https?:\/\//i.test(CFG.mail.url || "");
      if (!t && !owaOk) return this.hide();
      var host = ""; try { host = new URL(t ? t.url : CFG.mail.url).host; } catch (e) {}
      var self = this, body;
      if (this.step === "ask") body = [
        h("div", { class: "oc-t" }, T("Mail (" + host + ") is not connected yet. Without it yaver cannot see your mail, calendar or Outlook tasks. Connect now? A login screen opens inside yaver.", "E-posta (" + host + ") henüz bağlı değil. Bağlanmazsa yaver postalarınızı, takviminizi ve Outlook görevlerinizi göremez. Şimdi bağlansın mı? yaver'in içinde bir giriş ekranı açılır.")),
        h("div", { class: "oc-a" },
          h("button", { class: "small primary", onclick: function () { self.go(); } }, icon("mail"), T("Connect mail", "E-postayı bağla")),
          owaOk ? h("button", { class: "small", onclick: function () { self.go("owa"); } }, icon("globe"), T("Outside: via web mail", "Kurum dışı: web mail ile")) : null,
          h("button", { class: "small ghost", onclick: function () { self.hide(); } }, T("Not now", "Şimdi değil")))];
      else body = [
        h("div", { class: "oc-t" }, T("Log in on the mail screen; this card closes by itself once mail is connected.", "Posta ekranında oturum açın; e-posta bağlanınca bu kart kendiliğinden kapanır.")),
        EWS.lastError ? h("div", { class: "oc-t", style: "color:var(--warn)" }, EWS.lastError) : null,
        h("div", { class: "oc-a" },
          h("button", { class: "small primary", onclick: function () { A.mailShow(); } }, icon("mail"), T("Show login screen", "Giriş ekranını göster")),
          h("button", { class: "small", onclick: function () { self.go(EWS.via); } }, icon("refresh"), T("Start again", "Yeniden başla")),
          h("button", { class: "small ghost", onclick: function () { self.hide(); } }, T("Close", "Kapat")))];
      var el = h.apply(null, ["div", { class: "osync-card mail-ask i18n-skip" }, h("div", { class: "oc-h" }, icon("mail"), h("b", null, T("Mail not connected", "E-posta bağlı değil")))].concat(body));
      if (this.el) this.el.replaceWith(el);
      this.el = el;
      if (!el.isConnected) StartAsk.add(el, 2); else { el.classList.add("ask-card"); el.style.order = 2; }
    };
  });
  /* Mail addresses are whatever you typed in Mail → setup; yaver keeps them until you change them.
     Only the filler example values that older builds stored as real settings are cleared. */
  safe(function () {
    var filler = /^https:\/\/eposta\.company\.com\.tr\/owa\/?$/i;
    if (CFG.mail.url && filler.test(CFG.mail.url.trim())) { CFG.mail.url = ""; saveCfg(); }
    /* the phone keeps a copy too, so the addresses survive if the page storage is ever reset */
    if (!CFG.mail.url && !CFG.mail.ews && (pref("mailUrl") || pref("mailEws"))) { CFG.mail.url = pref("mailUrl"); CFG.mail.ews = pref("mailEws"); saveCfg(); }
  });

  /* automatic reconnect at start: open the mail screen hidden; it connects if the login is still valid */
  var autoVia = "";
  safe(function () {
    if (pref("autoMail") === "0") return;
    var via = pref("mailVia"); if (!via) return;
    if (via === "owa" ? !/^https?:\/\//i.test(CFG.mail.url || "") : !ewsTarget()) return;
    autoVia = via; yn.mailAuto = Date.now();
  });
  safe(function () {
    var _maybe = MailAsk.maybe.bind(MailAsk);
    MailAsk.maybe = function () {
      var left = yn.mailAuto ? 40000 - (Date.now() - yn.mailAuto) : 0;
      if (left > 0) { setTimeout(function () { MailAsk.maybe(); }, left); return; }
      return _maybe();
    };
  });

  /* =========================== 3. notifications while away =========================== */
  var tcb = new Map(), tseq = 0;
  safe(function () {
    var _toast = window.toast;
    window.toast = function (msg, ms, onClick) {
      var r = _toast.apply(this, arguments);
      try {
        if ((onClick || (ms || 0) >= 9000) && !A.isForeground()) {
          var k = "";
          if (onClick) { k = String(++tseq); tcb.set(k, onClick); if (tcb.size > 50) tcb.delete(tcb.keys().next().value); }
          A.notify("yaver", String(msg), onClick ? "toast" : "open", k);
        }
      } catch (e) {}
      return r;
    };
  });
  function plain(t) { return String(t || "").replace(/```[\s\S]*?```/g, "[code]").replace(/!\[[^\]]*\]\([^)]*\)/g, "").replace(/\[([^\]]+)\]\([^)]*\)/g, "$1").replace(/[#*_`>|]/g, "").replace(/\n{3,}/g, "\n\n").trim(); }
  function lastReply(s) {
    var m = (s && s.messages) || [];
    for (var i = m.length - 1; i >= 0; i--) if (m[i].role === "assistant" && typeof m[i].content === "string" && m[i].content.trim()) return m[i].content;
    return "";
  }
  safe(function () {
    var _send = Agent.send.bind(Agent);
    Agent.send = async function (text, att, opts) {
      var wasBusy = Agent.busy;
      var r = await _send(text, att, opts);
      if (wasBusy) return r;
      try {
        var reply = plain(lastReply(Agent.session));
        if (reply && yn.speakNext) { yn.speakNext = false; yn.call("speak", { text: reply.slice(0, 1500), lang: isTr() ? "tr-TR" : "" }).catch(function () {}); }
        if (reply && pref("notifyReplies") !== "0" && !A.isForeground()) {
          var title = (Agent.session && Agent.session.title) || "";
          A.notify(T("yaver replied", "yaver yanıtladı") + (title && title !== "New conversation" ? " · " + title : ""), reply.slice(0, 900), "open", "");
        }
      } catch (e) {}
      return r;
    };
  });

  /* =========================== 4. phone tools for the agent =========================== */
  safe(function () {
    TOOLSETS.phone = "This phone: its own calendar, alarms and timers, reminders and notifications, opening apps / maps / dialer / SMS / e-mail compose, clipboard, reading aloud, battery and device info";
    if (CORE_TOOLSETS.indexOf("phone") < 0) CORE_TOOLSETS.push("phone");
    var ms = function (v, name) {
      if (v === undefined || v === null || v === "") return undefined;
      if (typeof v === "number") return v < 1e12 ? v * 1000 : v;
      var s = String(v).trim(); if (/^\d{4}-\d{2}-\d{2}$/.test(s)) s += "T00:00";
      var d = new Date(s); if (isNaN(d.getTime())) throw new Error("could not read the " + (name || "time") + " \"" + v + "\" — use local ISO like 2026-11-02T09:30");
      return d.getTime();
    };
    var clean = function (o) { var r = {}; for (var k in o) if (o[k] !== undefined && o[k] !== null && o[k] !== "") r[k] = o[k]; return r; };
    var loc = function () { return isTr() ? "tr-TR" : undefined; };
    var when = function (t) { return new Date(t).toLocaleString(loc(), { weekday: "short", day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }); };
    var hm = function (t) { return new Date(t).toLocaleTimeString(loc(), { hour: "2-digit", minute: "2-digit" }); };

    tool("phone_calendar", "phone",
      "The phone's own calendars (Google, Samsung, Outlook app… synced on this device). The company Exchange calendar is the separate mail/calendar tool. action=list: events from start to end (default today + 7 days), optional query text filter. action=calendars: list calendars. action=create: add an event (title, start; end defaults to +1 h; all_day; location; description; reminder_minutes; calendar_id). action=delete: remove event_id. Times are the phone's local time in ISO form like 2026-11-02T09:30.",
      { action: S("list | calendars | create | delete", { enum: ["list", "calendars", "create", "delete"] }), start: S("Start, local ISO date/time"), end: S("End, local ISO date/time"), query: S("Text filter for list"), title: S("Event title"), location: S("Location"), description: S("Notes"), all_day: B("All-day event"), reminder_minutes: N("Alert this many minutes before (create)"), calendar_id: N("Calendar id from action=calendars (default: the primary writable calendar)"), event_id: N("Event id (delete)") },
      ["action"], async function (a) {
        if (a.action === "calendars") {
          var cs = await yn.call("calendar.calendars", {});
          return cs.map(function (c) { return c.id + ". " + c.name + (c.account ? " (" + c.account + ")" : "") + (c.writable ? "" : " [read-only]") + (c.primary ? " [primary]" : ""); }).join("\n") || "(no calendars on this phone)";
        }
        if (a.action === "list") {
          var s = ms(a.start, "start"); if (s === undefined) { var d0 = new Date(); d0.setHours(0, 0, 0, 0); s = d0.getTime(); }
          var e = ms(a.end, "end"); if (e === undefined) e = s + 8 * 86400000;
          var evs = await yn.call("calendar.list", clean({ start: s, end: e, query: a.query }));
          return evs.length ? evs.map(function (v) {
            return "#" + v.id + " " + (v.allDay ? new Date(v.start).toLocaleDateString(loc()) + " (all day)" : when(v.start) + "–" + hm(v.end)) + " · " + v.title + (v.location ? " @ " + v.location : "") + (v.calendar ? " [" + v.calendar + "]" : "");
          }).join("\n") : "(no events on the phone calendar in that range)";
        }
        if (a.action === "create") {
          if (!a.title) throw new Error("title is required");
          var st = ms(a.start, "start"); if (st === undefined) throw new Error("start is required");
          var r = await yn.call("calendar.create", clean({ title: a.title, start: st, end: ms(a.end, "end"), allDay: !!a.all_day, location: a.location, description: a.description, reminderMinutes: a.reminder_minutes, calendarId: a.calendar_id }));
          return "Added to the phone calendar (event #" + r.id + "): " + a.title + " — " + (a.all_day ? new Date(st).toLocaleDateString(loc()) : when(st));
        }
        if (a.action === "delete") {
          if (!a.event_id) throw new Error("event_id is required");
          await yn.call("calendar.delete", { id: a.event_id });
          return "Deleted phone calendar event #" + a.event_id;
        }
        throw new Error("unknown action " + a.action);
      }, { risky: function (a) { return a.action === "delete" ? "deletes an event from the phone calendar" : null; } });

    var DAYS = { sun: 1, mon: 2, tue: 3, wed: 4, thu: 5, fri: 6, sat: 7, paz: 1, pzt: 2, sal: 3, "çar": 4, car: 4, per: 5, cum: 6, cmt: 7 };
    tool("phone_alarm", "phone",
      "The phone's Clock app. action=alarm: set an alarm at hour (0-23) and minute, optional label and repeat days like [\"mon\",\"fri\"]. action=timer: start a countdown of seconds or minutes, optional label. action=show: open the alarm list.",
      { action: S("alarm | timer | show", { enum: ["alarm", "timer", "show"] }), hour: N("Hour 0-23"), minute: N("Minute 0-59"), seconds: N("Timer length in seconds"), minutes: N("Timer length in minutes"), label: S("Label"), days: { type: "array", items: { type: "string" }, description: "Repeat days: sun mon tue wed thu fri sat" } },
      ["action"], async function (a) {
        if (a.action === "show") return await yn.call("alarm.show", {});
        if (a.action === "timer") {
          var sec = (+a.seconds || 0) + (+a.minutes || 0) * 60;
          return await yn.call("timer.set", clean({ seconds: Math.round(sec), label: a.label }));
        }
        var days = (a.days || []).map(function (d) { return DAYS[String(d).toLowerCase().slice(0, 3)]; }).filter(Boolean);
        return await yn.call("alarm.set", clean({ hour: a.hour | 0, minute: a.minute | 0, label: a.label, days: days.length ? days : undefined }));
      });

    tool("phone_reminder", "phone",
      "Reminders that pop up as phone notifications at a set time, even when yaver is closed. action=add: at (local ISO time) or in_minutes, title, optional text; optional prompt = a request yaver itself should carry out at that time (e.g. \"summarize my unread mail\"), its answer arrives as a notification. action=list. action=cancel: id.",
      { action: S("add | list | cancel", { enum: ["add", "list", "cancel"] }), at: S("When, local ISO date/time"), in_minutes: N("Minutes from now (instead of at)"), title: S("Notification title"), text: S("Notification text"), prompt: S("Optional: a request for yaver to run at that time"), id: N("Reminder id (cancel)") },
      ["action"], async function (a) {
        if (a.action === "list") {
          var rs = await yn.call("remind.list", {});
          return rs.length ? rs.sort(function (x, y) { return x.at - y.at; }).map(function (r) { return "#" + r.id + " " + when(r.at) + " · " + r.title + (r.text ? " — " + r.text : "") + (r.prompt ? " [yaver will: " + r.prompt + "]" : ""); }).join("\n") : "(no reminders)";
        }
        if (a.action === "cancel") return await yn.call("remind.cancel", { id: a.id | 0 });
        var at = a.in_minutes ? Date.now() + a.in_minutes * 60000 : ms(a.at, "time");
        if (!at) throw new Error("give at or in_minutes");
        var r = await yn.call("remind.add", clean({ at: at, title: a.title || T("Reminder", "Hatırlatma"), text: a.text, prompt: a.prompt }));
        return "Reminder #" + r.id + " set for " + when(r.at) + ": " + r.title + (r.prompt ? " (yaver will then: " + r.prompt + ")" : "");
      });

    tool("phone_notify", "phone", "Show a notification on the phone right now.",
      { title: S("Title"), text: S("Text") }, ["text"],
      async function (a) { return await yn.call("notify", { title: a.title || "yaver", text: a.text }); });

    tool("phone_open", "phone",
      "Open something on the phone. target: url (yaver's browser; set external=true for the phone's browser), dial (number, opens the dialer — the user presses call), sms (number + body, opens the SMS composer), email (address + subject + body, opens the mail composer), maps (place search), navigate (directions to a place), web_search, share (text to share), app (an installed app by name), settings (wifi | bluetooth | display | sound | battery | location | apps | assistant). Use target=app with value \"list\" to list installed apps.",
      { target: S("url | dial | sms | email | maps | navigate | web_search | share | app | settings"), value: S("The URL, number, address, place, app name or text"), subject: S("E-mail subject"), body: S("SMS / e-mail text"), external: B("Open URLs in the phone's own browser") },
      ["target"], async function (a) {
        if (a.target === "app" && /^(list|\*|)$/i.test(a.value || "")) { var apps = await yn.call("apps", {}); return apps.join(", "); }
        return await yn.call("open", clean({ target: a.target, value: a.value, subject: a.subject, body: a.body, inApp: !a.external }));
      });

    tool("phone_info", "phone", "Battery level and charging, network, device model, Android version, language, time zone, current local time, whether yaver is the default assistant and always on.",
      {}, [], async function () { var o = await yn.call("info", {}); return Object.keys(o).map(function (k) { return k + ": " + o[k]; }).join("\n"); });

    tool("phone_clipboard", "phone", "Read (action=get) or set (action=set, text) the phone's clipboard.",
      { action: S("get | set", { enum: ["get", "set"] }), text: S("Text to copy") }, ["action"],
      async function (a) { return a.action === "set" ? await yn.call("clipboard.set", { text: a.text || "" }) : await yn.call("clipboard.get", {}); });

    tool("phone_speak", "phone", "Read text aloud through the phone's speaker (text-to-speech). action=stop stops speaking.",
      { text: S("What to say"), action: S("say | stop", { enum: ["say", "stop"] }) }, [],
      async function (a) { if (a.action === "stop") return await yn.call("speak.stop", {}); return await yn.call("speak", { text: a.text || "", lang: isTr() ? "tr-TR" : "" }); });
  });

  /* =========================== 5. microphone, launches, back button =========================== */
  function input() { return document.getElementById("input"); }
  function setInput(t) { var i = input(); if (!i) return; i.value = t; i.dispatchEvent(new Event("input")); }
  function sendInput() { var b = document.getElementById("btnSend"); if (b) b.click(); }
  function listen(autoSend) {
    return yn.call("listen", { prompt: T("Speak to yaver", "yaver'e konuşun"), lang: isTr() ? "tr-TR" : "" }).then(function (t) {
      t = String(t || "").trim(); if (!t) return;
      var i = input(); setInput(i && i.value ? i.value.replace(/\s*$/, " ") + t : t);
      if (autoSend) { yn.speakNext = pref("speakReplies") !== "0"; sendInput(); }
    }).catch(function (e) { if (!/cancel/i.test(e.message)) toast(e.message, 5000); });
  }
  yn.listen = listen;
  function addButtons() {
    var send = document.getElementById("btnSend");
    if (!send || document.getElementById("btnMic")) return;
    var mic = document.createElement("button");
    mic.id = "btnMic"; mic.className = "iconbtn"; mic.title = T("Speak", "Konuş"); mic.type = "button";
    mic.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0"/><path d="M12 17v5"/><path d="M8 22h8"/></svg>';
    mic.onclick = function (e) { e.preventDefault(); listen(false); };
    send.parentNode.insertBefore(mic, send);
    var attach = document.getElementById("btnAttach");
    if (attach && attach.parentNode) {
      var ph = document.createElement("button");
      ph.id = "btnPhone"; ph.className = "iconbtn"; ph.title = T("Phone settings (assistant, always on, notifications)", "Telefon ayarları (asistan, sürekli açık, bildirimler)"); ph.type = "button";
      ph.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="2" width="12" height="20" rx="2"/><path d="M11 18h2"/></svg>';
      ph.onclick = function (e) { e.preventDefault(); A.openSettings(); };
      attach.parentNode.insertBefore(ph, attach.nextSibling);
    }
  }
  function runPrompt(text, label) {
    label = "⏰ " + (label || T("Reminder", "Hatırlatma"));
    if (Agent.busy) Agent.steer(text, [], label);
    else Agent.send(text, [], { internal: "reminder", label: label });
  }
  function focusInput() { var i = input(); if (i) { i.focus(); try { A.showKeyboard(); } catch (e) {} } }
  function handle(L) {
    switch (L.action) {
      case "assist": if (L.voice) listen(true); else focusInput(); break;
      case "voice": listen(true); break;
      case "new": safe(function () { Agent.newChat(); }); focusInput(); break;
      case "mail": safe(function () { if (EWS.alive()) Office.open("mail"); else { MailAsk.step = "ask"; MailAsk.render(); } }); break;
      case "plan": safe(function () { Mail.dailyPlan(); }); break;
      case "ask": if (L.text) { setInput(L.text); sendInput(); } break;
      case "run": if (L.text) runPrompt(L.text, L.label); break;
      case "share":
        if (L.text) { var i = input(); setInput((i && i.value ? i.value + "\n" : "") + L.text); }
        if (L.files && L.files.length) safe(function () { ingestFiles(L.files.map(function (f) { return new File([yn.b64ToBytes(f.b64)], f.name, { type: f.mime || "" }); })); });
        focusInput(); break;
      case "toast": var cb = tcb.get(String(L.text || "")); if (cb) { tcb.delete(String(L.text)); safe(cb); } break;
      default: break;
    }
  }
  function whenBooted(fn) {
    var n = 0;
    (function chk() {
      var ok = false; try { ok = !!(Agent.session && document.getElementById("btnSend")); } catch (e) {}
      if (ok) { try { fn(); } catch (e) { console.error(e); } }
      else if (n++ < 900) setTimeout(chk, 100);
    })();
  }
  yn.runPrompt = function (text, label) { whenBooted(function () { runPrompt(text, label); }); };
  yn.onLaunch = function () {
    whenBooted(function () {
      while (yn.launches.length) { var j = yn.launches.shift(); try { handle(JSON.parse(j)); } catch (e) { console.error(e); } }
    });
  };
  yn.back = function () {
    var dl = document.querySelector("dialog[open]"); if (dl) { try { dl.close(); } catch (e) {} return true; }
    var b = document.body;
    if (b.classList.contains("show-left") || b.classList.contains("show-right")) { b.classList.remove("show-left", "show-right"); return true; }
    var app = document.getElementById("app");
    if (app && (app.classList.contains("side-open") || app.classList.contains("insp-open"))) { app.classList.remove("side-open", "insp-open"); return true; }
    try { if (typeof Office !== "undefined" && Office.on) { Office.close(); return true; } } catch (e) {}
    try { if (typeof KB !== "undefined" && KB.on) { KB.close(); return true; } } catch (e) {}
    return false;
  };

  whenBooted(function () {
    addButtons();
    safe(function () { var s = Agent.session; if (s && s.toolsets && s.toolsets.indexOf("phone") < 0) s.toolsets.push("phone"); });
    safe(function () {
      if (!autoVia || EWS.alive() || (EWS.win && !EWS.win.closed)) return;
      yn.silentMail = true;
      EWS.connect(autoVia === "owa" ? "owa" : undefined);
    });
    try { A.ready(); } catch (e) {}
    yn.onLaunch();
  });
})();
