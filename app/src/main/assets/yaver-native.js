/* yaver for Android — native bridge, loaded before yaver's own script.
   - fetch() to other websites goes through the phone's own HTTP stack (no CORS, no proxies, streaming kept)
   - the mail "bridge tab" becomes the app's built-in mail login screen (no bookmarklet)
   - downloads, "open file", share, clipboard and print are handed to Android */
(function () {
  "use strict";
  var A = window.YaverAndroid;
  if (!A) return;

  var origFetch = window.fetch.bind(window);
  var pend = new Map(), calls = new Map(), seq = 0, cseq = 0;
  /* static CDNs that already allow cross-origin use (Python packages, libraries) stay on the WebView's fast path */
  var PASS = /^https?:\/\/(appassets\.androidplatform\.net|cdn\.jsdelivr\.net|fastly\.jsdelivr\.net|cdnjs\.cloudflare\.com|unpkg\.com|esm\.sh|files\.pythonhosted\.org|pypi\.org)(:\d+)?(\/|$)/i;

  function b64ToBytes(b) { var s = atob(b), n = s.length, u = new Uint8Array(n); for (var i = 0; i < n; i++) u[i] = s.charCodeAt(i); return u; }
  function bytesToB64(u) { var s = ""; for (var i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s); }
  function abs(u) { try { return new URL(String(u), location.href).href; } catch (e) { return String(u || ""); } }
  function abortErr() { try { return new DOMException("The operation was aborted.", "AbortError"); } catch (e) { var x = new Error("aborted"); x.name = "AbortError"; return x; } }

  var yn = window.__yn = {
    native: true,
    origFetch: origFetch,
    b64ToBytes: b64ToBytes,
    bytesToB64: bytesToB64,
    launches: [],
    silentMail: false,

    /* ---- native fetch callbacks ---- */
    head: function (id, status, statusText, hdrs, url) {
      var p = pend.get(id); if (!p) return;
      var h = new Headers();
      for (var i = 0; i < hdrs.length; i++) { try { h.append(hdrs[i][0], hdrs[i][1]); } catch (e) {} }
      var noBody = p.method === "HEAD" || status === 204 || status === 205 || status === 304 || status < 200;
      var st = status < 200 || status > 599 ? 200 : status;
      var stream = null;
      if (!noBody) stream = new ReadableStream({
        start: function (c) { p.ctrl = c; },
        cancel: function () { try { A.fetchAbort(id); } catch (e) {} pend.delete(id); }
      });
      var resp, init = { status: st, statusText: /^[\x20-\x7e]*$/.test(statusText || "") ? (statusText || "") : "", headers: h };
      try { resp = new Response(stream, init); } catch (e) { resp = new Response(stream, { status: st, headers: h }); }
      try { Object.defineProperty(resp, "url", { value: url }); } catch (e) {}
      try { Object.defineProperty(resp, "redirected", { value: url !== p.url }); } catch (e) {}
      p.resolved = true;
      if (noBody) pend.delete(id);
      p.resolve(resp);
    },
    chunk: function (id, b64) { var p = pend.get(id); if (p && p.ctrl) { try { p.ctrl.enqueue(b64ToBytes(b64)); } catch (e) {} } },
    end: function (id) { var p = pend.get(id); if (!p) return; pend.delete(id); if (p.ctrl) { try { p.ctrl.close(); } catch (e) {} } },
    err: function (id, msg) {
      var p = pend.get(id); if (!p) return; pend.delete(id);
      var e = new TypeError("Failed to fetch (" + msg + ")");
      if (!p.resolved) p.reject(e); else if (p.ctrl) { try { p.ctrl.error(e); } catch (x) {} }
    },

    /* ---- async phone calls (calendar, alarms, speech…) ---- */
    call: function (method, args) {
      return new Promise(function (res, rej) {
        var id = ++cseq; calls.set(id, { res: res, rej: rej });
        try { A.call(id, method, JSON.stringify(args || {})); } catch (e) { calls.delete(id); rej(e); }
      });
    },
    ret: function (id, json) {
      var c = calls.get(id); if (!c) return; calls.delete(id);
      var r; try { r = JSON.parse(json); } catch (e) { c.rej(e); return; }
      if (r.ok) c.res(r.value); else c.rej(new Error(r.error || "failed"));
    },

    /* ---- launches from the assistant button, shortcuts, notifications, share ---- */
    launch: function (json) { yn.launches.push(json); if (yn.onLaunch) yn.onLaunch(); },

    /* ---- files ---- */
    nameFor: function (type) {
      var t = String(type || "").split(";")[0].trim().toLowerCase();
      var map = { "text/html": "page.html", "text/plain": "file.txt", "text/markdown": "file.md", "application/pdf": "file.pdf", "application/json": "file.json", "image/png": "image.png", "image/jpeg": "image.jpg", "image/gif": "image.gif", "image/webp": "image.webp", "image/svg+xml": "image.svg", "application/zip": "archive.zip", "text/csv": "table.csv" };
      return map[t] || ("file." + ((t.split("/")[1] || "bin").replace(/[^a-z0-9]+/g, "").slice(0, 6) || "bin"));
    },
    sendBlob: function (blob, name, target, text) {
      return blob.arrayBuffer().then(function (ab) {
        var u = new Uint8Array(ab), step = 768 * 1024;
        var id = A.fileBegin(name || yn.nameFor(blob.type), blob.type || "");
        for (var i = 0; i < u.length; i += step) A.fileChunk(id, bytesToB64(u.subarray(i, i + step)));
        return JSON.parse(A.fileEnd(id, target || "download", text || ""));
      });
    },
    say: function (msg, r) {
      try {
        if (typeof toast === "function") toast(msg, 6000, r && r.uri ? function () { A.viewUri(r.uri, r.mime || ""); } : undefined);
        else A.toast(msg);
      } catch (e) { A.toast(msg); }
    }
  };

  /* ---------- fetch(): other websites through the phone's network ---------- */
  window.fetch = function (input, init) {
    var url;
    try { url = typeof input === "string" ? input : (input instanceof URL ? input.href : (input && input.url)); } catch (e) { url = ""; }
    url = abs(url);
    if (!/^https?:/i.test(url) || PASS.test(url)) return origFetch(input, init);
    var opts = Object.assign({}, init || {});
    var signal = opts.signal || (input instanceof Request ? input.signal : null);
    delete opts.signal; delete opts.window; opts.mode = "cors";
    var req;
    try { req = new Request(input instanceof Request ? input : url, opts); } catch (e) { return Promise.reject(e); }
    if (signal && signal.aborted) return Promise.reject(abortErr());
    var method = String(req.method || "GET").toUpperCase();
    var bodyP = (method === "GET" || method === "HEAD") ? Promise.resolve(null)
      : req.arrayBuffer().then(function (b) { return b.byteLength ? new Uint8Array(b) : null; });
    return bodyP.then(function (body) {
      var hd = {}; req.headers.forEach(function (v, k) { hd[k] = v; });
      return new Promise(function (resolve, reject) {
        var id = ++seq;
        var p = { resolve: resolve, reject: reject, ctrl: null, resolved: false, url: url, method: method };
        if (signal && signal.aborted) { reject(abortErr()); return; }
        pend.set(id, p);
        if (signal) signal.addEventListener("abort", function () {
          if (!pend.has(id)) return; pend.delete(id);
          try { A.fetchAbort(id); } catch (e) {}
          var e = abortErr();
          if (!p.resolved) reject(e); else if (p.ctrl) { try { p.ctrl.error(e); } catch (x) {} }
        }, { once: true });
        try { A.fetchStart(id, method, url, JSON.stringify(hd), body ? bytesToB64(body) : ""); }
        catch (e) { pend.delete(id); reject(new TypeError("Failed to fetch (" + e.message + ")")); }
      });
    });
  };

  /* ---------- mail: EWS.connect() opens window "yaver-mailbridge" → built-in login screen ---------- */
  var mailWin = {
    closed: true, name: "yaver-mailbridge",
    postMessage: function (m) { try { A.mailPost(JSON.stringify(m)); } catch (e) {} },
    focus: function () { try { A.mailShow(); } catch (e) {} },
    close: function () { mailWin.closed = true; try { A.mailClose(); } catch (e) {} }
  };
  yn.mailWin = mailWin;
  yn.mailMsg = function (json) {
    var d; try { d = JSON.parse(json); } catch (e) { return; }
    try { if (typeof EWS !== "undefined") EWS.onMessage({ data: d, source: mailWin, origin: d.origin || "" }); } catch (e) { console.error(e); }
  };

  /* ---------- window.open: mail screen, in-app browser, files ---------- */
  function openAny(url) {
    url = String(url || "");
    if (!url || url === "about:blank") return;
    if (/^(blob:|data:)/i.test(url)) {
      origFetch(url).then(function (r) { return r.blob(); })
        .then(function (b) { return yn.sendBlob(b, yn.nameFor(b.type), "view"); })
        .then(function (r) { if (r && !r.ok) yn.say("Could not open: " + r.error); })
        .catch(function (e) { yn.say("Could not open: " + e.message); });
      return;
    }
    var u = abs(url);
    try { if (/^https?:/i.test(u)) A.openBrowser(u); else A.openExternal(u); } catch (e) {}
  }
  function fakeWin() {
    var w = { closed: false, opener: window, focus: function () {}, blur: function () {}, postMessage: function () {}, close: function () { w.closed = true; },
      document: { write: function () {}, writeln: function () {}, close: function () {}, open: function () {} } };
    var loc = {};
    Object.defineProperty(loc, "href", { get: function () { return "about:blank"; }, set: function (u) { openAny(u); } });
    loc.assign = loc.replace = function (u) { openAny(u); };
    Object.defineProperty(w, "location", { get: function () { return loc; }, set: function (u) { openAny(u); } });
    return w;
  }
  window.open = function (url, name) {
    if (name === "yaver-mailbridge") {
      mailWin.closed = false;
      var silent = !!yn.silentMail; yn.silentMail = false;
      try { A.mailOpen(abs(url), silent); } catch (e) { return null; }
      return mailWin;
    }
    /* "Open Outlook on the web": show the mail screen that is already logged in instead of a new browser */
    try {
      if (url && typeof EWS !== "undefined" && EWS.origin && mailWin && !mailWin.closed && new URL(abs(url)).host === new URL(EWS.origin).host) {
        A.mailShow(); return fakeWin();
      }
    } catch (e) {}
    if (url) openAny(url);
    return fakeWin();
  };

  /* ---------- downloads (a[download], blob: and data: links) ---------- */
  document.addEventListener("click", function (e) {
    var a = e.target && e.target.closest ? e.target.closest("a[href]") : null;
    if (!a) return;
    var href = a.getAttribute("href") || "";
    if (!/^(blob:|data:)/i.test(href)) return;
    e.preventDefault(); e.stopPropagation();
    var name = a.getAttribute("download") || "";
    var target = a.hasAttribute("download") ? "download" : "view";
    origFetch(href).then(function (r) { return r.blob(); }).then(function (b) {
      return yn.sendBlob(b, name || yn.nameFor(b.type), target);
    }).then(function (r) {
      if (r && r.ok && r.target === "download") yn.say("Saved to " + r.where + " — tap to open", r);
      else if (r && !r.ok) yn.say("Could not save: " + r.error);
    }).catch(function (err) { yn.say("Could not save: " + err.message); });
  }, true);

  /* ---------- share, clipboard, print ---------- */
  try {
    navigator.share = function (d) {
      d = d || {};
      var files = d.files ? Array.prototype.slice.call(d.files) : [];
      var text = [d.text, d.url].filter(Boolean).join("\n");
      if (!files.length) { A.shareText(d.title || "", text); return Promise.resolve(); }
      return Promise.all(files.map(function (f) { return yn.sendBlob(f, f.name || yn.nameFor(f.type), "stage"); }))
        .then(function (rs) { A.shareFiles(JSON.stringify(rs.filter(function (r) { return r && r.ok; }).map(function (r) { return r.path; })), text); });
    };
    navigator.canShare = function () { return true; };
  } catch (e) {}
  try { if (navigator.clipboard) navigator.clipboard.writeText = function (t) { A.copy(String(t)); return Promise.resolve(); }; } catch (e) {}
  try { window.print = function () { A.print(document.title || "yaver"); }; } catch (e) {}
})();
