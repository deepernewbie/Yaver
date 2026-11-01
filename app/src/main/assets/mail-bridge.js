/* yaver mail bridge — injected by the Android app into its built-in mail login screen (replaces the
   "yaver bridge" bookmarklet). It forwards yaver's EWS / Outlook-on-the-web requests to this server with
   your own login, and only reaches /EWS/ and /owa/ on this server. */
(function () {
  var H = window.YaverMailHost;
  if (!H) return;
  function post(m) { try { H.post(JSON.stringify(m)); } catch (e) {} }
  function b64(ab) { var u = new Uint8Array(ab), t = "", i; for (i = 0; i < u.length; i += 8192) t += String.fromCharCode.apply(null, u.subarray(i, i + 8192)); return btoa(t); }
  if (!window.__ynb) {
    window.__ynb = 1;
    window.__ynbRecv = function (q) {
      if (!q) return;
      if (q.yaver === "ping") { post({ yaver: "pong" }); return; }
      if (q.yaver !== "req") return;
      var id = q.id, p = q.path || "/EWS/Exchange.asmx", hd = q.headers || { "Content-Type": "text/xml; charset=utf-8" };
      if (!/^\/(ews|owa)\//i.test(p)) { post({ yaver: "res", id: id, error: "path not allowed" }); return; }
      try {
        var m = document.cookie.match(/(?:^|;\s*)X-OWA-CANARY=([^;]+)/i), ck = m ? decodeURIComponent(m[1]) : "";
        for (var k in hd) if (hd[k] === "__CANARY__") hd[k] = ck;
      } catch (x) {}
      var opts = { method: q.method || "POST", credentials: "include", cache: "no-store", headers: hd };
      if (q.body != null && !/^(GET|HEAD)$/i.test(opts.method)) opts.body = q.body;
      /* a dropped connection (phone switching network, server keep-alive reset) is retried twice before giving up */
      var tries = 0;
      (function go() {
        tries++;
        fetch(p, opts).then(function (r) {
          if (q.binary) return r.arrayBuffer().then(function (ab) { post({ yaver: "res", id: id, status: r.status, b64: b64(ab), ct: r.headers.get("content-type") || "" }); });
          return r.text().then(function (t) { post({ yaver: "res", id: id, status: r.status, text: t }); });
        }).catch(function (err) {
          /* never repeat something that changes the mailbox (send, create, delete, move…): it may already have happened */
          var changes = /SendItem|CreateItem|DeleteItem|UpdateItem|MoveItem|CopyItem|CreateAttachment|DeleteAttachment|MarkAsJunk|action=(Send|Create|Delete|Update|Move|Copy)/i.test(String(q.body || "") + " " + p);
          if (tries < 3 && !changes) { setTimeout(go, tries * 1500); return; }
          post({ yaver: "res", id: id, error: "mail server " + location.host + " could not be reached (" + String((err && err.message) || err) + (navigator.onLine === false ? ", phone is offline" : "") + "). Open Mail → Show login screen and check that your inbox is visible." });
        });
      })();
    };
  }
  var ow = false;
  try { ow = /X-OWA-CANARY=/i.test(document.cookie) || /\/owa\//i.test(location.pathname); } catch (x) {}
  post({ yaver: "hello", v: 7, owa: ow, origin: location.origin, href: location.href });
})();
