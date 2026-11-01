# yaver for Android (native)

plusAI.yaver rewritten as a real Android app in Kotlin — every screen is native Android.

* **Chat** with any OpenAI-compatible model (OpenRouter, OpenAI, Groq, Together, Gemini, Ollama, LM Studio),
  streaming answers, Markdown, pictures and files as attachments, voice input, read-aloud.
* **Agent tools**: web search (DuckDuckGo → Bing → Mojeek → DuckDuckGo Lite, optional Brave/Tavily/SearXNG)
  and page reading straight from the phone; company mail, calendar and address book (Exchange); the phone's
  calendar, alarms, timers, reminders, notifications, apps, clipboard; memory; past chats; files; documents
  (PDF, Word, Excel, PowerPoint, OpenDocument — scanned PDFs through the vision model); Python and JavaScript;
  helper agents; scheduled tasks.
* **Company mail without a browser tab**: EWS with the Windows login (NTLMv2, implemented in the app) or Outlook on
  the web with forms login. The password is stored encrypted (Android keystore); when the server's session ends,
  yaver logs in again by itself. Native inbox, message view, reply/forward, attachments, calendar, meeting answers.
* **Default assistant**, launcher shortcuts, Quick Settings tile, Share → yaver, text-selection menu.
* **Always on**: reminders and scheduled tasks run in the background, new-mail notifications, restarts after reboot.
* **Import**: the first start brings over the model, API key, mail addresses, memory, persona and conversations
  of the previous (web-page) yaver on this phone; a yaver export .zip can be imported in Settings.

Python runs in an invisible engine (Pyodide, downloaded once on first use); nothing the user sees is a web page.

Raise `versionCode` in `app/build.gradle` for every build you install over an older one.
