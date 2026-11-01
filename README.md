# yaver for Android

plusAI.yaver as a native Android app (built by VibeForge / GitHub Actions — no Android Studio).

## What changed compared with the HTML version
- **No CORS proxies.** Every web request (pages, search, LLM APIs, downloads) goes through the phone's own
  network stack. corsproxy.io, allorigins, codetabs and r.jina.ai are gone. Answers still stream.
- **Mail without bookmarklets.** Mail → Connect opens your Outlook-on-the-web / EWS page inside the app.
  Log in once; when the inbox (or the EWS page) is visible yaver connects by itself. Server logins
  (DOMAIN\user) are asked in a native dialog and can be remembered. Next start it reconnects silently.
- **Default assistant.** Settings → Apps → Default apps → Digital assistant app → yaver. Then long-press
  home / swipe from a bottom corner / hold power opens yaver ready to type (or listening — see settings).
- **Always on.** A quiet foreground service keeps yaver running (mail checks, heartbeats, background agents,
  reminder prompts) and restarts after reboot.
- **Phone tools for the agent:** phone calendar (list/create/delete), alarms and timers, reminders
  (notifications at a time, optionally with a task yaver runs then), notifications, open apps / maps /
  dialer / SMS / e-mail composer, clipboard, read aloud, battery & device info.
- **Notifications** when yaver finishes while you're away and for new mail.
- Microphone button, launcher shortcuts, Quick Settings tile, Share → yaver, "yaver" in the
  text-selection menu, real downloads to Download/yaver, printing, in-app browser.

## Files
- `app/src/main/assets/yaver.html` – yaver itself (proxy defaults removed, two native scripts added).
- `app/src/main/assets/yaver-native.js` / `yaver-native-post.js` – the bridge into Android.
- `app/src/main/assets/mail-bridge.js` – what the bookmarklet used to do, injected automatically.
- `app/src/main/java/com/plusai/yaver/` – the native app.

Raise `versionCode` in `app/build.gradle` for every build you install over an older one.
