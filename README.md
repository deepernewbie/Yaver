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

## Reminders and routines

A task with a due time schedules a real alarm. It fires with the app closed,
with the phone asleep, and again after a reboot — the thing a web app could
never do, and most of the reason this is native.

Routines are recurring prompts: "every weekday at 08:00, summarise what's on".
At that time a notification appears; tapping it opens Yaver and runs the prompt.
It does not run silently in the background, because waking up to find an agent
has spent your tokens unasked is not a pleasant surprise.

Exact alarms are used when Android allows them and approximate ones otherwise.
Being a few minutes late beats refusing to remind you at all.

## Conversations

Every conversation is kept and searchable. ☰ in the top bar lists them, New
starts a fresh one, and `search_history` lets the agent find something you
discussed weeks ago rather than claiming not to remember.

## How it learns

Three mechanisms, and they only work together.

**Memory** is scattered facts, each carrying the names and topics it mentions
and how much it matters. Recalling one keeps it alive; anything unused for 45
days is dropped unless pinned, marked important, or recalled three times. The
Memory panel shows what is fading and when, and lets you search, pin, add and
delete by hand.

**The profile** is the coherent picture rather than the scattered facts, and it
is loaded into every conversation.

**Skills** are procedural: how you like a particular job done. Written after
finishing something non-trivial, read before doing it again.

None of this happens on its own, which is the part most agents get wrong.
Writing memory is optional and answering is urgent, so a model always picks
answering. The loop counts exchanges and nudges it when too many have passed
without anything being recorded — a nudge rather than a requirement, because
forced it would write noise every turn. Facts are also harvested automatically
every few exchanges and when you leave the app.

`consolidate_memory` merges duplicates and corrects what has gone stale.
`memory_status` reports what is about to be forgotten.

## Messages

Yaver can read incoming messages from WhatsApp, Telegram, Signal and SMS by
listening to their notifications. Turn it on under More → Messages; it needs
notification access, granted once in Android settings.

Be clear about the limits, because they are not obvious:

- Only messages **sent to you** are seen. Never your own — WhatsApp raises no
  notification for those, so a chat containing only you captures nothing.
- Muted chats raise no notification and are invisible.
- Anything arriving while the app is open in the foreground is missed.
- Long messages arrive truncated to whatever the notification showed.

For "what came in today and what needs me" this is enough. For an archive of a
conversation it is not.

Replies are drafted, never sent: `draft_message` shows the text with a button
that opens WhatsApp with it already typed.

This is the feature that makes Play Protect refuse the first install. Turn off
Play Protect scanning once, install, turn it back on.

## Why the prompt is shaped the way it is

One tool per subject, not one per verb. `calendar` with an action of read, add,
update or delete — the way a person would describe it — rather than four
separate tools. Nineteen subjects cover fifty-one underlying operations.

This went through a wrong turn worth recording. The first attempt put every
tool behind a menu the agent had to open first: it made the prompt small, and
made a weak model worse, because a model that struggles to take one step does
not manage two. Size was never really the problem — choosing between fifty
near-identical names was. Merging them fixes both at once, and the schema fell
from about four thousand tokens to under a thousand without adding a round
trip.

The old names still work. A model that guesses `calendar_add` instead of
`calendar` with action=add gets what it asked for rather than an error it has
to recover from.

The prompt is also split in two: a fixed half — identity, rules, the whole tool
surface — identical on every turn and therefore cacheable, and a small changing
half that rides with the user's message carrying the date, the profile, goal
titles and a few relevant memories.

## Goals, and changing its own instructions

Two ideas taken from Prime Agent's continual harness.

**Goals** are for work that spans weeks and many conversations — finding a
flat, planning a trip. A task gets done; a goal gets progressed. Each carries
its own notes, written as evidence rather than status updates, and is loaded
into every conversation so the next one starts informed instead of asking the
same questions again.

**Evidence-backed self-edits.** When the agent rewrites its profile or writes a
skill, it must say what prompted the change, and the change is logged. An agent
that edits its own instructions with no trail is impossible to debug when it
starts behaving oddly, and requiring a reason discourages pointless churn. The
log sits at the top of the Debug panel.

What was not adapted: Prime Agent's RLM runtime is a persistent Python
interpreter the model programs against. There is no Python on Android, and the
prompt-based tool protocol here works with any model on OpenRouter, which
matters more on a phone.

## Location

`where_am_i` gives a rough position, and `show_places` can search around it.
Coarse permission only — street-level precision buys nothing for finding a
pharmacy, and asking for less is the difference between a permission people
grant and one they think about.

## Not here yet

Sending images to the model — a photographed menu, receipt or sign is the most
useful thing still missing. Contacts, so a draft can be addressed by name
rather than number. A warning when Android has denied exact alarms and
reminders are running approximate.
