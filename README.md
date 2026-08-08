# Yaver — Yᶻaver

A personal aide that lives on your phone. Native Android, no WebView.

It reads what you hand it, turns it into tasks and calendar entries, looks
things up, and remembers what matters. Nothing is sent anywhere without you.

## Setup

1. Push this folder with VibeForge, install the APK from Releases.
2. Open Settings, paste an OpenRouter API key (openrouter.ai/keys).
3. Pick a model. Small free models often ignore the tool format and loop —
   if the agent seems stuck repeating itself, that is why.
4. Grant calendar access when asked, or from Settings.

## What it does

**Tasks** with due times and reminders. The bar above the composer warns when
something is overdue or close.

**Calendar** — reads and writes the phone's real calendar through
CalendarContract, not an .ics file you import by hand. It can list your week,
add an event with an alert, move one, and delete one.

**Memory** that fades. Facts unused for 45 days are dropped unless pinned,
marked important, or recalled three times; the Memory panel shows what is
fading and when. Anything you write yourself is pinned automatically.

**Web search and reading** with no API key, through DuckDuckGo and Mojeek,
falling back to a reader proxy when a site blocks the phone directly.

**Share to Yaver** — share text from any app and it lands in the agent's inbox
as something handed over on purpose.

## Design notes

The tool protocol is prompt-based, not the provider's native function calling,
so any OpenRouter model works and switching is a setting rather than a rewrite.
The parser accepts three dialects because models disagree about how to write a
call, and rejecting two of them makes small models loop forever.

Times are local wall-clock everywhere. The system prompt carries an explicit
fourteen-day date table, because models get weekday arithmetic wrong and a
meeting on the wrong day is the most expensive mistake this app can make.

Memory writing is nudged rather than required: the loop counts exchanges and
reminds the model when it has gone too long without recording anything.

Crashes are written to a file and shown on the next launch. There is no logcat
on a phone, so an unhandled exception would otherwise just look like the app
closing.

## Not here yet

Sub-agents and deep research, PDF and document reading, voice, image search,
HTML report rendering, routines and background work, WhatsApp notification
capture. The web version has these; they come back a few at a time now that the
core runs.
