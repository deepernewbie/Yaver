# yaver: HTML app vs. native Android port, feature parity matrix

Audit date: 2026-11-04 · **final parity pass for v4.0 (versionCode 33): 2026-11-05.** Every row below was re-checked against the code; UI rows were exercised in Playwright (see "Verification").

- **Original (the reference):** `/tmp/yv/all.js` (8,203 lines, the JavaScript of `/mnt/user-data/uploads/plusai-yaver__1_.html`; the static markup is HTML lines 1298–1433). Line numbers below refer to `all.js` unless they are marked `html:`.
- **Port:** `/home/claude/yaver-native`. Kotlin files are in `app/src/main/java/com/plusai/yaver/app/` and are cited by file name only. The UI is `app/src/main/assets/app/app.js` + `parity.js` (cited as `app.js:<line>` / by function name).

**Status values**
- **DONE**: equivalent or better ("better" / "extra" = the port does more).
- **PARTIAL**: the cell says exactly what is missing.
- **MISSING**: not in the port.
- **N/A**: browser-only plumbing with no meaning on Android. A justification is given.

There are no PARTIAL, MISSING or WIP rows left.

**How deep the check went.** UI, settings, commands, tools and Bridge methods were compared line by line, and every `call("…")` in app.js/parity.js was matched by script to a Kotlin dispatch branch (Bridge, MailUi, MailPlus, Parity). Every tool named in Prompt.kt, tool descriptions and the toolset maps was matched by script to a registration. The agent loop, harness, gates and compactor were checked at the level of functions and strings and by the JVM suites.

> **Resolved since the audit:** `FilesUi` exists (FileTools.kt) and backs every `files.*` method; `write_document` is registered, in the "files" toolset and CORE_TOOLS, and shows a deliverable card. In the final pass: plan mode now uses the same side-effect test as the permission presets (it missed patch, terminal, execute_code, write_document…); the System-1 backend labels were English-only in Turkish (now localised); the tool card's Progress log had a CSS class clash (`.tprog`) that squashed it to 5 px — renamed `.tplog`.

---

## 1. Chat & UI

| # | Feature | JS (where) | Native (where) | Status | Notes / what is missing |
|---|---|---|---|---|---|
| 1.1 | Splash screen with progress and "ready →" | html:1211, all.js:57-77 | native launch | N/A | Android launches straight into the WebView. A splash adds nothing. |
| 1.2 | Nebula animated logo (NebulaFX canvas) | 1-55 | `app.js` renderThread → `.mark.busy`; parity.css `yv-neb` | DONE | The "y" mark carries a slow nebula (conic gradient) on the hello screen and spins/glows fast on the running turn while yaver works (`.mark.busy`, parity.css); honours reduced motion. |
| 1.3 | Welcome hero: title, lead text, EN/TR switch, "Connections" status panel (LLM / System-1 / web / mail) with change buttons | UI.welcome 4369 | helloView `app.js:366`: greeting, 5 ideas, model and mail pills | DONE | Hello screen has a System-1 status pill and an EN/TR switch (parity.js helloExtras). |
| 1.4 | **Editable welcome shortcuts** (add, edit title/prompt/icon, delete, reorder by drag or up/down, reset to defaults; stored in CFG.shortcuts; a shortcut can open Mail/Calendar) | Shortcuts 360-404, QUICK_ACTIONS 166 | YVP.shortcutIdeas / shortcutsPage (parity.js); Parity shortcuts.get/set | DONE | Editable shortcuts: add/edit title, prompt, icon, open Mail/Calendar/Tasks; reorder up/down; delete; reset. Stored as cfg.shortcuts (Parity shortcuts.get/set). |
| 1.5 | User bubble with attachment chips; image thumbnails | userTurn 4218 | renderThread `app.js:290-301` | DONE | Native also shows thumbnails. |
| 1.6 | Streaming answer with live status ("waiting for the model", "thinking · N chars", "preparing a tool call: X · N chars", seconds) | streamStart 4248 | onThinkInfo → `run` phase `think`; waitText/waitTick in app.js | DONE | |
| 1.7 | **Tool card**: icon, name, argument summary, duration, spinner/✓/✗, collapsible **Arguments** JSON, live **Progress** log, **Result** (6,000 chars), link to the last file made | toolCard/toolProgress/toolResult 4266-4290 | stepEl `app.js` + YVP.stepExtras (parity.js); Agent.runTools `progress` | DONE | Collapsible Arguments JSON, duration, results up to 6,000 chars, **per-tool Progress log** (ToolCtx.progress lines kept on the tool message as `progress`, shown live while the tool runs and after reloads), existing file paths in the result become links, and the **last file made** shows as an "👁 name" quick link in the card header (`files.exists` check). |
| 1.8 | Answer footer: time + "answered in N s" | stamp 4225 | YVP.answerExtras (parity.js) | DONE | Time + "answered in N s" under final answers (parity.js answerExtras). |
| 1.9 | 👍/👎 under the final answer; 👎 asks "what did I get wrong?" and feeds Learn | fbButtons 4234 | fbButtons / fbNoteBox `app.js` + `feedback` bridge | DONE | |
| 1.10 | Copy / read aloud / share an answer | n/a (copy via ctx menu) | `app.js:313-317` | DONE (better) | |
| 1.11 | System-1 decision chips under a turn ("System 1" strip) | UI.chip 4243 | YVP.s1Strip / noteStrip (parity.js); Agent `um.s1` | DONE | Route chips are saved on the user message (`s1`) and drawn as a "SYSTEM 1" strip under the turn (kinds s1/llm/warn/muted); live S1 notes (loop monitor, verify-on-stop, approval, +tools, memory/research rules, long input, passages) render as the same strip. Better than JS: the strip survives a reload. |
| 1.12 | Notes with icon mapping (🗜 🧠 🛡 ⏹ ❓ ✅ ⛔ ✨ 🤖 💓) | UI.note 4291, NOTE_ICON 4205 | sideEl/noteIcon `app.js:669` | DONE | |
| 1.13 | Error box | UI.error 4292 | `.err` in renderThread | DONE | |
| 1.14 | One send button: send / stop / "add to running task" | syncSendBtn 4294 | syncSend `app.js:572` (adds a mic state) | DONE (better) | |
| 1.15 | Queued (steer) message bubble: "queued → delivered mid-run" / "sent mid-run" badge | queuedTurn 4302, markDelivered, _renderMsg STEER_PREFIX | `app.js` renderThread (`m.queued`, `m.steer`, S.delivered) | DONE | Queued caption while waiting; once the core delivers it the bubble shows "✓✓ Delivered mid-run", and after a reload "✓ Sent mid-run" (`m.steer`). |
| 1.16 | Clarify card (question, choice buttons, free answer) | askClarify 4308 | askEl/answerAsk + `ask.answer` | DONE | |
| 1.17 | Approval card (tool, reason, args JSON; Allow/Deny) | askApproval 4321 | approveSheet + `approve.answer` (title + details) | DONE | |
| 1.18 | Context-cleared divider; **"Show earlier context (N messages)" toggle; "Undo" (restoreContext)** | renderChat 4336-4346, Agent.restoreContext 4076 | `.ctxdiv` divider; earlier messages dimmed (`forgotten`) `app.js:345-348` | DONE | Earlier messages fold behind "Show earlier context (N messages)"; Undo while the divider is last (`ctx.restore`, also `/undo`). |
| 1.19 | Header: title + "N turns · N tool calls · context meter" | header 4362, Compactor.meter | `app.js` updateTitle | DONE | Title line "N turns · N tool calls" under the chat title (`#ctitle small.cmeta`), next to the context meter button. |
| 1.20 | Goal bar (objective, status pill, rounds, pause/resume/clear) + PLAN MODE pill + AUTONOMOUS pill (gates, continuations, off) | renderGoal 4534, 8112 | renderPlanBar `app.js` (Plan/Exit, Goal pause/resume/clear, Autonomous off) | DONE | |
| 1.21 | **Plan-mode toggle button** in the composer | `#btnPlan` html:1396, 8166 | composer chip "Plan first" (parity.js renderChips) | DONE | "Plan first" chip in the composer toggles /plan. |
| 1.22 | Plan card "Proposed plan" with Approve / Keep planning | planCard 4548 | planCardEl + `plan.approve` | DONE | |
| 1.23 | Deliverables cards (present_files): thumbnail, open, download | deliverables 4555 | filesEl `app.js:760` (open + share) | DONE | |
| 1.24 | **Per-turn file-changes card**: "N files changed +a −d", each file created/modified/deleted, **View diff**, **Revert all** | changesCard 4563, showDiff 4571, Changes 2890-2920 | Changes.kt + YVP.changesCard/diffSheet; Bridge changes.diff/changes.revert | DONE | Changes.kt: per-turn scan of /workspace and skills with a content store; card "N files changed +a −d", diff sheet (LCS, ±3 context over 400 lines), Revert all (`changes.diff`/`changes.revert`); kept on the message. |
| 1.25 | Todo panel (progress meter, items) | renderTodo 4397 | plan bar todo section (`S.todoOpen`) | DONE | |
| 1.26 | Conversation list: title, relative time, turns; debug-dump and delete buttons | renderSessions 4404 | drawer list + convoMenu (rename, delete) `app.js:1060`; search box | DONE | Long-press menu has Debug dump. |
| 1.27 | Delete-conversation flow (keeps memory and global lessons, drops local harness notes) | Sessions.forgetFlow 1754 | Convos.delete `Store.kt:121` (ctx, state, harness, heartbeats, children, follow-ups) | DONE | |
| 1.28 | Follow-up suggestion chips | FollowUps 451 | followupsEl + FollowUps.kt | DONE | |
| 1.29 | "yaver learned something" refine card with Undo | refineCard 8083 | refineEl + `lessons.undo` | DONE | |
| 1.30 | /btw side-question card | sideCard/sideAnswer 8093 | SideQuestion (Research.kt) → note | DONE | |
| 1.31 | Goal / heartbeat / subagent automatic-round markers | goalTurn, systemTurn 8081 | `m.label`/internal note `app.js:288` | DONE | |
| 1.32 | **File paths in answers and tool results become links** (bare paths and `code` paths, uploads/ fallback) + **image previews row** under the answer | linkifyFiles 3212 | md(): only markdown links/images → open.link/open.file `app.js:150-160` | DONE | Relative `code` names checked with `files.exists` (as given, then uploads/ and outputs/) become links; picture row (≤6) under answers. |
| 1.33 | Mail-id chips (subject resolved) and task chips | linkifyMails 3195, mailChip 3181, taskChip 3190 | mailChip/mailChips `app.js:116-140`; `task:` links | DONE | |
| 1.34 | Markdown: safe md, tables, code copy | md 4159 section 15 | marked + DOMPurify, copy button on pre | DONE | |
| 1.35 | Theme: System / Light / Dark switch + toggle buttons | Settings theme row 4629, applyTheme 8124, `#btnTheme` | parity.js applyTheme/themeSheet; parity.css [data-theme] | DONE | System/Light/Dark in Settings → Appearance and the ⋮ menu ([data-theme] palettes in parity.css). |
| 1.36 | Quick model switcher and System-1 switcher in the top bar | pills 4578, `#qModel`/`#qS1` | titleTap + YVP.s1Switch (parity.js) | DONE | Tap the chat title → model picker; ⋮ → System-1 quick switch (backend sheet, `s1.set`). |
| 1.37 | Permission preset select in the composer | `#qPerm` 4579 | composer permission chip (parity.js permSheet) | DONE | Permission chip in the composer opens the 4 presets. |
| 1.38 | Attach files; drag & drop files/folders anywhere; paste screenshots; a dropped plain zip is extracted to uploads/; a dropped state zip opens Restore | 4776-4812, 8180-8190 | files.pick (`app.js:557`); share-to-yaver intents (Manifest SEND/SEND_MULTIPLE/PROCESS_TEXT) | DONE | Paste a picture into the composer (`files.upload`); an attached state zip opens the import preview, another zip offers "Extract" (`state.extract`). Folder attach: N/A on Android pickers. |
| 1.39 | Voice input / speak answers | n/a | listen(), Speaker, voiceOnAssist | DONE (extra) | |
| 1.40 | Custom right-click menu (Copy/Paste) and swallowing dev-tool shortcuts | CtxMenu 4870-4902 | n/a | N/A | Android long-press is the native equivalent; there are no dev tools to hide. |
| 1.41 | Keyboard: Enter send / Shift+Enter, Alt+N new, Alt+L clear, Esc closes pages | 8143, 8170 | Android back = goBack | DONE | Alt+N new chat, Alt+L /clear, Esc back; Enter sends with a physical keyboard (Shift+Enter new line). |
| 1.42 | Inspector "Browser" pane: live preview of the agent's page (sandboxed render) + Text/snapshot mode, URL bar, back/go | renderBrowser 4484 | browserPage (parity.js); Browser.live/userGo/userBack/userReload/userTakeOver | DONE | ⋮ → "The agent's browser" (also `/browser`) opens a live view of the headless WebView: picture refreshed every 1.5 s (`browser.live` → Browser.live draws the WebView to a JPEG), Text/snapshot mode (the agent's element list + page text), URL bar with Go (address or search), Back, Reload, "Take over" (opens the page in the visible browser; the agent continues from where the user leaves) and "Open outside". |
| 1.43 | Inspector "System 1" pane: mode bar, 6 stat cards, decision log with probability bars, Clear | renderS1 4503, html:1421 | Settings → System-1 → "Decision log" sheet gatesLogSheet `app.js:2203` | DONE | Decision log sheet with probability bars and a **Clear log** button (`gates.clearLog`); 6 stat cards in Settings → Usage; mode (backend) switch in Settings and in ⋮ → System-1. |
| 1.44 | Inspector "Harness" pane: each gate, who handles it, settled/escalated counts; **LLM calls by purpose** bars; **Reset counters** | renderHarness 4525 | gatesSheet `app.js:2188` (toggles + counts) | DONE | Settings → Usage: LLM calls by purpose (Trace.kt counters) + Reset counters. |
| 1.45 | Inspector "Mail" pane (setup, inbox with triage) | Mail.render 5524 | Mail page | DONE | See section 8. |
| 1.46 | Mobile bottom nav (Settings/Workspace/Chat/Inspector/Memory) | html:1428, 8191 | drawer `app.js:2300-2314` (Mail, Calendar, Tasks, Memory, Settings) | DONE | Drawer has Mail, Calendar, Tasks, Memory, **Files**, Settings. |
| 1.47 | "y" logo returns to the last conversation | 8133 | n/a | N/A | The native drawer covers this. |
| 1.48 | Version-stale warning (older HTML file opened) | Version 3239 | n/a | N/A | APK updates replace the app. |

## 2. Agent loop & harness

| # | Feature | JS | Native | Status | Notes |
|---|---|---|---|---|---|
| 2.1 | System prompt built once per session: soul + LANGUAGE/MEMORY/SKILLS/ENV/WORK guidance + skills index + memory block + global harness digest + "Conversation started" | buildSystemPrompt 3477 | Prompt.kt (envGuidance 87, DEFAULT_SOUL, MEMORY/SKILLS/LANGUAGE guidance), Wiring PromptHooks | DONE | The Android ENV text is rewritten for the phone (correct). |
| 2.2 | Standing rules (Rule:/Kural:, pinned) first in every request | Rules 3467, sysFor 3472 | Prompt.kt STANDING RULES | DONE | |
| 2.3 | Toolset gating (core toolsets + S1 toolsets gate + slimTools) | CORE_TOOLSETS 2240, Compactor.slimTools 3654 | Agent.TOOLSET_DESC/toolsetMembers `Agent.kt:81-107` | DONE | Adds mail and phone toolsets. |
| 2.4 | safeToolCall, fixAlternation, validateArgs | 3874-3904 | LoopUtil.kt 41/70/148 | DONE | |
| 2.5 | Truncated tool arguments: salvage / cut-off error | (in loop) | LoopUtil.salvage/cutOffError/isCutArgs | DONE (better) | |
| 2.6 | Spill of outputs over 16k to a file + preview | Spill 2874 | LoopUtil.kt Spill 297 (/workspace/spill) | DONE | |
| 2.7 | Repeat-call reminder (3rd/5th/every 5th identical call) | repeatReminder 2884 | LoopUtil.repeatReminder 282 | DONE | |
| 2.8 | Hard rules: the task board is edited only through task_board; plan mode blocks side effects | execTool 2859-2866 | Planning.kt:167 (plan mode) and Planning.kt (task-board guard string) | DONE | |
| 2.9 | **Permission presets** ask / auto / full / readonly applied in execTool (readonly refuses every side effect; ask confirms every side effect; full skips approvals except hard rules) | PERMISSIONS 2841, hasSideEffect 2848, execTool 2867-2869 | ToolCtx.approve `Agent.kt:36-60`: `Cfg.askRisky` on = user decides *risky* actions; off = smart approval | DONE | Permissions.kt (ask/auto/full/readonly, SIDE_EFFECT_TOOLS + action-based writes + terminal/code/fetch regexes); applied in Agent.runTools after Planning.gate; full/readonly/ask honoured in ToolCtx.approve; askRisky=1 migrates to ask. JVM decision table: 136 cases. |
| 2.10 | Smart approval: hardline → S1 → LLM safety check → user | gateApproval 3564, llmApproval 3553 | Gates.approval `Gates.kt:376-412` | DONE | |
| 2.11 | Gates: router, toolsets, skills, recall, approval, loop, stop, review, compress, find, research, rag | GATES 3495 | Gates.GATES 279; S1.enabled(...) for all 12 (+refine) | DONE | |
| 2.12 | Loop monitor (heuristic + S1) | loopHeuristic 3583, gateLoop 3598 | Gates.kt loopHeuristic | DONE | |
| 2.13 | Stop gate / verify-on-stop | gateStop 3609 | Gates.kt, Agent.kt "verify-on-stop" | DONE | |
| 2.14 | Nudges: empty reply ×2, "I don't know but didn't search", "memory may answer", wrong language, iteration budget exhausted, output cut off → continue | agentLoop 3929-4030 | Agent.kt (each string present), Lang.kt, MemoryPlus.kt | DONE | |
| 2.15 | Context compaction: model-aware window, staged/incremental summary, anchors, files list, shrinkOld, overflow learning, /compact focus | Compactor 3635-3835 | Compactor.kt (window 70, discover 222, shrink 300) | DONE | |
| 2.16 | Background review (memory delta / learning after a turn) | backgroundReview 3839 | Agent.kt backgroundReview, Learn.kt | DONE | |
| 2.17 | Steering: message typed while running is delivered at the next step | Agent.steer 4049, deliverPending 4053 | Turns.steer (Heartbeats.kt) + Bridge.send queued | DONE | |
| 2.18 | Goal mode (rounds, notes, complete/block, maxRounds) | Goal 2922, continueGoal 4084 | Planning.kt goal* + Turns.continueGoal | DONE | |
| 2.19 | Plan mode + present_plan approval | 2865, 4548 | Planning.kt | DONE | |
| 2.20 | Autonomous mode with limits and quality gates (flags --max-continuations/--max-turns/--max-tokens/--minutes/--gate/--gate-retries) | Autonomous 7860 | Planning.kt enableAutonomous/autonomousNext; Heartbeats.kt gates via run_code engine | DONE | |
| 2.21 | Heartbeats (every N, --follow-up, pause/resume/clear) | Heartbeats 7929 | Heartbeats.kt (alarms: keeps running in the background) | DONE (better) | The JS runs only while the tab is open. |
| 2.22 | Background subagents with messaging (list/message/stop/delete), specs from the harness | Children 7957, delegate_task, subagents 8030 | Subagents.kt (Children, delegate_task, subagents) | DONE | |
| 2.23 | Long user input stashed as a variable | stashLongInput 8004 | Research.kt Stashed 249 | DONE | |
| 2.24 | /btw side question | sideQuestion 7990 | SideQuestion (Research.kt) | DONE | |
| 2.25 | @file references resolved into the message | resolveRefs 2929 | Agent.resolveRefs `Agent.kt:126` | DONE | |
| 2.26 | Reply language detect/switch/fixed + LANGUAGE_GUIDANCE | LANG 3393-3446 | Lang.kt | DONE | |
| 2.27 | Clarify returns "no human input" in autonomous mode | 8041 | Heartbeats.kt ("No human input is available…") | DONE | |
| 2.28 | Work-action approval cards (task/calendar/mail show fields, also in Full auto) | WorkOK 2970-3017 | "Confirm work changes" setting; MailPlus/TaskBoard approve with details | DONE | |
| 2.29 | Retries: 429/5xx with waits 3/8/20/40 s + Retry-After; drops stream_options/reasoning on 400; 3-minute hang abort | LLM.chat 893-933 | Llm.kt 63-120 | DONE | |
| 2.30 | Reasoning effort "low" for side calls; empty-reply retry with a larger budget | 900-902 | Llm.text 227-241 | DONE (better) | |
| 2.31 | LLM extra headers (CFG.llm.extraHeaders JSON) | LLM.headers 876-881 | Llm.headers `Llm.kt:36` | DONE | Cfg extraHeaders (LlmX, JSON validated) merged in Llm.headers; Settings → Model — advanced. |
| 2.32 | Temperature setting honoured | 896 | Llm.kt (Cfg.temperature) + Parity.setCfg "temperature" + Settings → Model — advanced | DONE | `temperature` setCfg key (0–2) + Settings row. |
| 2.33 | Stats counters (llmCalls, tokens in/out, per-purpose, S1 calls…) persisted | STATS 872 | S1 stats in gates.log; no per-purpose LLM counters exposed | DONE | Trace.kt counts llmCalls, tokens in/out and per-purpose calls (Prefs stats.llm); `stats.get/reset`. |

## 3. Tools (the 44 JS tool registrations vs. native `Tools.ensure`)

Schemas were compared automatically: property names were extracted from every `tool(...)` call and every native `Tool(...)` registration. Action enums were compared for mail, calendar, task_board, memory, skill_manage, harness, goal, heartbeat and subagents. All match apart from the rows marked below.

| # | Tool | JS line | Native | Status | Notes |
|---|---|---|---|---|---|
| 3.1 | read_file | 2245 | FileTools.kt:245 | DONE | |
| 3.2 | write_file | 2251 | FileTools.kt:265 | DONE | |
| 3.3 | patch | 2253 | FileTools.kt:282 | DONE | |
| 3.4 | search_files | 2258 | FileTools.kt:301 | DONE | |
| 3.5 | terminal | 2266 | Shell.kt:1005 | DONE | Every JS command is present (ls … zip, python, pip, node, jq, sed, awk, tar). |
| 3.6 | execute_code (JS REPL + Python, PTC tools.*, llm, llm_batch, spawn, vars) | 2268, 2116-2225, PY 5022 | Shell.kt CodeEngine 1250 (Pyodide 0.28, persistent) | DONE | |
| 3.7 | web_search | 2272 | WebTools.kt:170 (DDG/Bing/Mojeek/Brave/Tavily/SearXNG) | DONE (better) | |
| 3.8 | web_extract | 2285 | WebPlus.kt:205 | DONE | |
| 3.9 | browser_navigate/snapshot/click/type/back/find/download | 2289-2297 | WebPlus.kt:302-329 aliases over Browser.kt | DONE (better) | A real engine: page JavaScript runs. |
| 3.10 | **vision_analyze** | 2298 | `look_at_image` Tools.kt:150 | DONE | `vision_analyze` registered as an alias of look_at_image (Parity.registerTools), in the vision toolset. |
| 3.11 | memory | 2304 | MemoryPlus.kt:830 | DONE | |
| 3.12 | session_search | 2307 | MemoryPlus.kt:845 | DONE | |
| 3.13 | skills_list / skill_view / skill_manage | 2312-2314 | Skills.kt:70-85 | DONE | |
| 3.14 | todo / clarify | 2323, 2327 | Planning.kt:476/491 | DONE | |
| 3.15 | delegate_task | 2328 | Subagents.kt:111 | DONE | |
| 3.16 | fetch | 2458 | WebPlus.kt:226 | DONE | |
| 3.17 | deep_research | 2462 | Research.kt:165 | DONE | |
| 3.18 | browser_use | 2464 | WebPlus.kt:338 | DONE | |
| 3.19 | read_document | 2630 | DocsPlus.kt:207 (overrides Tools.kt:139; OCR) | DONE | |
| 3.20 | search_documents | 2737 | Rag.kt:284 | DONE | |
| 3.21 | unpack_archive / pack_archive | 2824/2829 | Archive.kt:211/232 | DONE | |
| 3.22 | goal / present_plan / present_files | 2943/2957/2960 | Planning.kt:503/538/565 | DONE | |
| 3.23 | download_file / page_images | 3099/3103 | WebPlus.kt:264/273 | DONE | |
| 3.24 | task_board | 6602 | TaskBoard.kt:422 | DONE | |
| 3.25 | search_all | 6682 | Everything.kt:123 | DONE | |
| 3.26 | run_html (actions click/type/key/eval/wait, width) | 7112 | HtmlRun.kt:263 | DONE | |
| 3.27 | mail (13 actions incl. find/empty_folder) | 7417 | MailPlus.kt:225 (+send_now) | DONE | |
| 3.28 | calendar | 7492 | MailPlus.kt:244 | DONE | |
| 3.29 | harness / heartbeat / subagents | 8011/8021/8030 | Harness.kt:687, Heartbeats.kt:316, Subagents.kt:122 | DONE | |
| 3.30 | run_code (native legacy) vs execute_code | — | Tools.kt run_code → CodeEngine.runCode | DONE | run_code is the same engine as execute_code (language defaults to python, `files` argument, progress); both are in the "code" toolset (Agent.toolsetMembers, Subagents.TOOLSETS), Compactor LAST_RESORT_TOOLS, and plan-mode/permission side-effect tests. |
| 3.31 | Native extras: list_files, delete_file, export_file, read_page, http_request, open_in_browser, people, folder, phone_* (9), schedule_task | — | various | DONE (extra) | |
| 3.32 | write_document (docx/xlsx/pptx/pdf) | none in JS (the HTML made files via Python in execute_code) | Documents.kt | DONE (extra) | Registered (Tools.ensure), in the "files" toolset (Subagents.TOOLSETS → Agent.toolsetMembers) and Compactor CORE_TOOLS, a side effect for plan mode and the presets, a TOOLS icon in app.js, and its document appears as a deliverable card (Planning.presentFiles) plus the "file made" link on the tool card. |

## 4. Files / VFS / terminal / code

| # | Feature | JS | Native | Status | Notes |
|---|---|---|---|---|---|
| 4.1 | One virtual disk: /workspace (cwd), /.yaver (PERSONA.md, memories/, skills/, sessions/), /tmp; every tool sees the same paths | VFS 529, HOME/CWD/P 1357 | VPath (FileTools.kt:11-40), Workspace.resolve → VPath | DONE | One virtual disk (VPath) for every tool, the Files page and `/ws/` picture URLs (`/ws/.yaver/skills/…` resolves through VPath.normTool; VFS test). memories/ and sessions/ are read-only views (as JS for sessions); MEMORY.md/USER.md are edited as text in Settings → Memory limits (raw editor, §-separated). |
| 4.2 | **Files panel**: tree of `/` with expand/collapse, size, per-row **Run & check (HTML)**, **Open in new tab**, **Download** (folders as zip), **Delete** (protected dirs), Upload / New file / New folder, disk meter (quota, backend), RAG index stats | renderTree 4466, html:1311-1318, deletePath 3163, exportFolder 4745, 8159-8161 | openFiles `app.js`; FilesUi (FileTools.kt); Parity files.view/runHtml/stats | DONE | Files page (drawer, `/files`, Settings): areas My files / yaver's area, breadcrumbs from `/`, sort, filter, per-row menu: Preview, **Open the page** (html/svg in yaver's own browser, no bridge, local CSS/JS/images served read-only — LocalView), **Run & check** (run_html at 390 px, report sheet, "Ask yaver to fix it"), Open with another app, Ask yaver, Read and summarize, Share, Save to phone (**folders as .zip**), Rename, Move, Copy, Delete (protected dirs refused: /, /workspace, /.yaver, memories, skills, sessions). Upload / New folder / New note. Footer: **disk meter** (yaver's storage vs. free space) and **Document index: N files · M sections** (Rag.stats). Folder navigation replaces the desktop tree. |
| 4.3 | **File editor/viewer dialog**: text edit + Save (refreshes skills/memory), image view, binary info, Open in new tab, Download, Delete | Editor 4607-4620, html dlgEditor | editFile / showPreview `app.js` | DONE | Text editor sheet (Save → `files.write`, read-only and truncated files guarded, PERSONA.md refreshes the persona); images inline; binary files show kind/size with Open/Share/Save. |
| 4.4 | **In-app preview** ("open in new tab"): md rendered, docx/xlsx/pptx/csv as an md preview, json pretty, html/svg in a sandboxed iframe, pdf/image/audio/video native | FileOpen 3118-3160 | showPreview `app.js`; FilesUi.read/extract/pdfPage; LocalView (HtmlRun.kt) | DONE | In-app preview: md rendered (Source toggle), csv/tsv as a table, json pretty, code with line numbers, pdf pages (PdfRenderer), docx/xlsx/pptx "Show text" (`files.extract`), images; html/svg "Open the page" in yaver's browser (scripts run, no app bridge). Anything else: another app. |
| 4.5 | Diff view of a changed file | showDiff 4571 | Changes.diffOf + diffSheet (parity.js) | DONE | See 1.24. |
| 4.6 | Terminal (shell) | Shell 1776-2114 | Shell.kt | DONE | |
| 4.7 | Python: Pyodide, PyCache offline package cache, mirrors, pip, `python file.py` from the terminal, matplotlib → /workspace/plots | PY 4904-5108 | Shell.kt CodeEngine (pyDir cache.json 1042-1046) | DONE | |
| 4.8 | JS worker REPL with fs over the whole disk | WORKER_SRC 2118 | Shell.kt js() | DONE | |
| 4.9 | Connected real folder, two-way sync, conflict copies, mass-delete guard, .yaver-trash | FolderSync 7259 (File System Access API) | Folders.kt (SAF) + Settings "Connected folders" + pending-deletes card | DONE | |
| 4.10 | Archives (zip/tar/tgz/gz) | ARCHIVE 2744 | Archive.kt | DONE | |
| 4.11 | Binary downloads that survive CORS; sniffMime | 3044-3117 | WebPlus download_file (direct from the phone) | DONE | |
| 4.12 | Run HTML in a sandbox and report errors | HtmlRun 6970-7130 | HtmlRun.kt | DONE | |
| 4.13 | Disk backend notices (IndexedDB/localStorage/memory) | boot 8195-8199 | n/a | N/A | Native storage is the app's files dir. |

## 5. Documents

| # | Feature | JS | Native | Status | Notes |
|---|---|---|---|---|---|
| 5.1 | PDF text (pdf.js + basic fallback, page ranges, embedded-font warning) | DOCS.pdf 2562-2614 | Docs.kt / DocsPlus.kt | DONE | |
| 5.2 | Scanned PDF OCR via the vision model | — | read_document ocr=true | DONE (extra) | |
| 5.3 | DOCX → markdown (headings, lists, tables) | DOCS.docx 2484 | Docs.kt | DONE | |
| 5.4 | XLSX (sheet, maxRows) / CSV → md tables | 2504, 2530 | Docs.kt | DONE | |
| 5.5 | PPTX slide text | 2540 | Docs.kt | DONE | |
| 5.6 | Vectorless RAG: section trees, BM25, S1 rag gate, background indexing on upload | RAG 2645-2735, indexSoon 2833 | Rag.kt (indexLater 275) | DONE | |
| 5.7 | Creating Office/PDF files | HTML: model writes them with Python (python-docx/openpyxl in Pyodide) | Documents.kt write_document | DONE (extra) | write_document builds real .docx/.xlsx/.pptx/.pdf natively (doctest: 55 JVM checks + python-docx/openpyxl/python-pptx validation); the result opens in the in-app preview (pdf pages, Office text). The Python route still works (Pyodide packages load on import) but the prompt steers to write_document. |

## 6. Web & browser

| # | Feature | JS | Native | Status | Notes |
|---|---|---|---|---|---|
| 6.1 | CORS proxy presets/template/headers/key | NET 1151, PRESETS.proxy 854, Settings web 4682 | requests go directly from the phone | N/A | There is no CORS on Android. |
| 6.2 | Search backends DDG via proxy / SearXNG | webSearch 1329 | WebTools.kt (DDG, Bing, Mojeek, Brave, Tavily, SearXNG) | DONE (better) | |
| 6.3 | Text browser VB (navigate, snapshot with refs, click, type, select, form submit, back, find) | VB 1193-1315 | Browser.kt (real WebView, cookies) | DONE (better) | |
| 6.4 | Live browser preview for the user | 1.42 | browserPage (parity.js); Browser.live | DONE | See 1.42. |
| 6.5 | Deep research with a cited report, S1 research director | deepResearch 2379 | Research.kt | DONE | |
| 6.6 | Autonomous browser agent | runBrowserAgent 2442 | WebPlus browser_use | DONE | |
| 6.7 | fetch API tool | webFetch 2354 | WebPlus fetch | DONE | |

## 7. Memory, skills, learning

| # | Feature | JS | Native | Status | Notes |
|---|---|---|---|---|---|
| 7.1 | Tiered memory: active/draft with a trial in days of use, retirement, pins, rules, conflicts with pinned, curate, useRate | Memory/MemMeta 1409-1600 | MemoryPlus.kt | DONE | |
| 7.2 | MemRAG: topics, search keys, reasoning recall | MemRAG 1603-1728 | MemoryPlus.kt (s1Rag, index) | DONE | |
| 7.3 | KB page tabs: Memory / About you / Skills / Learned / Trash | KB 176-356 | openKnowledge/KB_TABS `app.js:1656` | DONE | |
| 7.4 | Memory tab: 4 stat cards, explainer, search, filters (all/active/draft/in prompt/pinned), sort (use/new/old/trial…), New note, card facts (uses, rate, trial days, saved, last used, by, source conversation link, search keys), pin, promote/demote, edit, delete → trash | memoryView 201-252 | memoryView/memCard/memMenu `app.js:1706-1780` | DONE | |
| 7.5 | "Rewrite search keys" (reindex) | 225 | KB "More" → memory.reindex `app.js:1663` | DONE | |
| 7.6 | Trash: kind, reason, replaced-by, Restore, Delete forever, Empty trash | trashView 255 | trashView `app.js:1872` | DONE | |
| 7.7 | Learned tab: stats, filters (all/global/local/suspended), search, Sleep pass now, lesson cards (✓/✗, source, updated, conversation link), suspend/enable, edit title+text, delete, standing rules, recent mistakes, sleep log | lessonsView 267-311 | lessonsView/lessonCard/lessonMenu/lessonEdit `app.js:1800-1870` | DONE | |
| 7.8 | **Skills tab: master/detail** with search, usage-sorted list, rendered SKILL.md, **New skill** (slug, template), **Edit** (name, description, body; Ctrl+S), **Copy skill**, **Delete skill**, **Files list** inside the skill with viewer, **Add file**, open a file in the editor | skillsView 313, skillDetail 326-348, newSkill 349 | skillsView `app.js:1786`: read-only list, sheet with description/folder/files and "Use it" | DONE | SkillsUi.kt + parity.js skills page: rendered SKILL.md, Edit (name/description/body), Duplicate, Delete (file count), files list with preview/editor, Add file, New skill (Turkish slug + Steps/Pitfalls template). |
| 7.9 | Skill usage counter (sort by last use) | KB.init wraps skill_view 182 | Parity.registerTools (skill_view wrapper) → SkillsUi.touch | DONE | skill_view calls counted (skills.use); list sorted by last use with "N× used". |
| 7.10 | Old side-pane memory editor: **Edit raw MEMORY.md/USER.md**, char meter | renderMemory 4412-4459 | rawMemory (parity.js); Parity memory.raw/rawSave | DONE | Settings → Memory limits → Edit MEMORY.md / USER.md as text (§-separated, char meter). |
| 7.11 | Harness: refine (manual/auto/learn/sleep), rollback, history, global/local scope, subagent specs, feedback counters, suspend when harmful | Harness 7614, Learn 7734 | Harness.kt, Learn.kt | DONE | |
| 7.12 | Persona (PERSONA.md) editable | P.soul 1358, editor | Settings → Personality (Cfg.persona) mirrored at /.yaver/PERSONA.md | DONE | |
| 7.13 | Seed skill "web-research" written on first boot | SEED_SKILL 1361, bootstrapDisk 1397 | SkillsUi.seed (called from Core.kt) | DONE | SkillsUi.seed writes web-research once on first run. |
| 7.14 | Outlook-backed backup of memory/skills/tasks | 19h 6691-6969 | MailPlus OSync + Settings rows | DONE | |

## 8. Mail, calendar, tasks

| # | Feature | JS | Native | Status | Notes |
|---|---|---|---|---|---|
| 8.1 | Connection: OWA bookmarklet bridge to EWS | EWS 5194, yaverBridge 5137, MOBILE 5127 | native EWS/OWA with NTLM, sign-in page autofill, TOTP, keep-alive (Exchange.kt, Ntlm.kt, Login.kt, MailKeepAlive.kt) | DONE (better) | The bookmarklet is browser-only plumbing; the native EWS/OWA connection replaces it. |
| 8.2 | Folders list (inbox, sent, drafts, archive, junk, deleted, plus server folders with counts) | loadFolders, renderFolders | `app.js` loadFolders/pickFolder; `MailUi.kt` mail.folders | DONE | Server folder names and unread/draft counts (`mail.folders` → MxEws.folderList, the same six distinguished folders as JS loadFolders); count badge on the folder button and in the folder sheet; Archive hidden when the mailbox has none. |
| 8.3 | Mail list: sender photo, unread, importance, attachment icon, time, preview; swipe read/unread & delete | renderList 6095 | `app.js:1170-1340` | DONE | |
| 8.4 | **AI triage**: one-line summary, "needs reply", priority shown in the list and reading pane; "All / needs reply" filter | Mail.runTriage 5467, renderList 6098, renderRead 6153 | `MailUi.headerJson`; `app.js` mailRow, renderMailList, renderRead | DONE | `triage {s,r,p}` in every list header (`MailUi.headerJson`, also `mail.full`); "✦ summary" line plus High / Reply tags in the list, a note in the reading view; All / Unread / Needs reply chips (Needs reply in Inbox, as JS); `mail.triage` event patches the open list. |
| 8.5 | Reading pane renders **HTML** in a sandboxed frame with CID inline images, "Show pictures" / "Always show" | OF.frame 5814, renderRead 6131 | `Exchange.getFull`, `MailUi` mail.full/mail.inline; `app.js` mailDoc, mailFrame, renderRead | DONE | `mail.full` (Exchange.getFull: BodyType HTML, IsDraft with fallback, ContentId) → port of OF.mailDoc + frame: sanitised, `sandbox="allow-same-origin"` (no scripts), the CSP of JS, links routed to `open.link`, sized to content, tables scroll; remote pictures blocked with "Show pictures" / "Always show" (`mailx` setting `images`); cid: pictures via `mail.inline` (data URLs). |
| 8.6 | Reading actions: Reply, Reply all, Forward, Delete, Ask yaver | 6131-6168 | `app.js:1255-1305` | DONE | |
| 8.7 | **Archive / Move to folder** menu | moveMenu 5933, moveTo 5927 | `app.js` mailMoreSheet, moveMenu, moveMsg, undoToast; Bridge `mail.move` → {id} | DONE | Reading view ⋮ → Archive, Move to… (Inbox/Archive/Junk/Deleted minus the current folder); `mail.move` returns the new id; Undo toast moves it back; "no Archive folder" message as JS. |
| 8.8 | Mark as unread from the reading pane | act 5939 | `app.js` markUnread, swipeRow | DONE | ⋮ → Mark as unread (`mail.markRead` read=false) plus swipe. |
| 8.9 | Delete permanently (from Deleted Items), Empty folder (Deleted/Junk), **Undo toast** after delete | deleteMsg 5887, emptyFolder 5906, undoToast 5901 | `app.js` deleteMsg, emptyMailFolder; Bridge `mail.remove {permanent}`; `MailUi` mail.emptyFolder | DONE | Delete moves to Deleted Items with an Undo toast (Exchange.remove now returns the new id); in Deleted Items, and ⋮ → Delete permanently, `mail.remove {permanent:true}` after a confirm; "Empty folder" button in Deleted/Junk (`mail.emptyFolder`, confirm). |
| 8.10 | **Drafts: open, edit and send a draft** | openDraft 5950, editDraft 5954 | `app.js` openMail(edit), composeSheet("draft"), draftParts, sendDraftNow; `Exchange.updateDraftFull`; `MailUi` mail.updateDraft/sendDraft | DONE | A draft (folder Drafts or IsDraft) opens with Edit / Send; the editor is prefilled (To, Cc, Subject, own text; the quoted original kept as `tail`, editDraft port); Save → `mail.updateDraft` (Exchange.updateDraftFull: recipients set or cleared), Send → update + SendItem, or `mail.sendDraft` from the reading view; the agent's `mail.draft` event shows a toast that opens the draft editor. |
| 8.11 | Compose: To/Cc people picker, subject, body, quoted original, Send / Save draft / Discard | compNode 6184 | composeSheet `app.js:1385` (+suggestPeople) | DONE | |
| 8.12 | **"Draft with AI" / "Rewrite with AI"** in compose | aiDraft 5974 | `app.js` composeSheet aiDraft; `MailUi` mail.aiDraft | DONE | "Draft with AI" / "Rewrite with AI" button in the compose and draft sheets → `mail.aiDraft` (system prompt of aiDraft 5978, original text ≤6,000 chars, main model, 900 tokens); failure toast as JS. |
| 8.13 | **Task from e-mail** (AI) and **Manual task** from the reading pane; **Summarize** button | taskFromMailPrompt 5751, renderRead | `app.js` renderRead chips, taskFromMailPrompt, summarizeMail, manualTaskFromMail | DONE | Chips under the header: Ask yaver, Summarize (JS prompt, attachments line when it has files), Task from e-mail (taskFromMailPrompt verbatim, EN/TR), Manual task (newTaskSheet with the subject, the sender as owner and `source {mailId, subject}` — the source link shows in the task sheet; the description is left empty as in JS). |
| 8.14 | Attachments: download, **save to workspace /mail**, "Ask yaver about this attachment" | saveAtt 5985 | `app.js` askAboutAttachment (attw chips) | DONE | Each attachment chip has a ✦ button that asks yaver about it (JS prompt with get_attachment); tapping the chip saves to /workspace/mail and opens it as before. Inline pictures shown in the body are no longer listed. |
| 8.15 | **Local mail index search UI**: filters (when, from, to/cc, attachments, file name, folder), keyword vs **AI search**, index status | runSearch 6460, filterBar 6469, renderIndexState 6457 | `app.js` runMailSearch, mailFilterBar, renderIndexLine; mailx.index.* | DONE | Filter button (when, from, to/cc, attachments, file name, folder; Clear filters) and ✦ AI search in the mail search box → `mailx.index.search` (keywords / filters / smart); results with folder tags and attachment names, "how" line, "Search the server instead"; empty index → server search + index build (as JS); index status line with Update / Rebuild. |
| 8.16 | Invite card in mail: accept/tentative/decline with message | inviteCard 6169, respondMsg | invite block + respond `app.js:1268` | DONE | |
| 8.17 | Calendar views: **day / work week / week / month**, mini-month navigator, agenda | renderCal 6204, miniNode 6222, weekNode 6234, monthNode 6254, agendaNode 6228 | `app.js` renderCal, weekGrid, monthGrid, miniMonthSheet, renderAgenda; `Exchange.eventsFull`; `MailUi` mail.range | DONE | Agenda / Day / Work week / Week / Month (view picker, saved as `mailx` setting `calView`), Today, ‹ ›, tappable title → month navigator sheet (miniNode); week grid with all-day row, overlap columns, now line, working-hours shading, tap a slot → new event; month cells with +N; `mail.range` (Exchange.eventsFull: recurring, free/busy, categories with fallback). The side "Rest of today" agenda is the Agenda view on a phone. |
| 8.18 | **New event / edit event dialog** (subject, start/end, location, attendees picker, body, invite) and **delete/cancel event** | newEvent 6019, saveEvent 6024, dlgNode 6286, removeEvent | `app.js` eventSheet, eventEditSheet, removeEvent; `Exchange` getEvent/createEventFull/updateEvent/deleteEvent; `MailUi` mail.event.* | DONE | "+" (and a tap in the grid) → event sheet: title, attendees + optional (people suggestions), date, all day, start/end, location, description; Save/Send → `mail.event.create`; event details (`mail.event.get`: attendees with answers, your response, join link, description frame) with Accept/Tentative/Decline, Prepare, Edit (organiser or appointment → `mail.event.update`, attendees notified) and Delete / Remove / Cancel meeting (`mail.event.delete`, cancel=organiser). Edit is extra (JS has none). |
| 8.19 | Daily plan (auto at a time + /today) | Mail.dailyPlan 5485 | MailPlus dailyPlanPrompt + "Daily plan" time | DONE | |
| 8.20 | Directory photos cache | Photos 7531 | MailPhotos.kt | DONE | |
| 8.21 | Pictures in mail described by vision | mailVision 5574 | "Look at pictures in mail" | DONE | |
| 8.22 | New-mail polling + notifications | Mail.tick 5446 | MailKeepAlive, mailNotify, mailPoll | DONE (better) | |
| 8.23 | Task board UI: filters (q/owner/status), sort (manual/due/owner/updated), drag reorder, task panel with notes (add/edit/delete, sorted), people manager, overdue | renderTasks 6492, renderTaskGrid 6512, taskPanel 6562, peopleDlg 6588 | tasksPage/renderTasks/taskCard/taskSheet/peopleSheet `app.js:1892-2080` | DONE | |
| 8.24 | Outlook Tasks backup + restore (EWS) | 19h 6691 | OSync (MailPlus.kt) + osync UI | DONE | |
| 8.25 | Mail connection self-test | — | mailx.diag sheet | DONE (extra) | |

## 9. Settings

| # | Setting | JS (CFG / Settings UI) | Native | Status | Notes |
|---|---|---|---|---|---|
| 9.1 | Appearance System/Light/Dark | CFG.ui.theme, 4629 | Settings → Appearance → Theme (parity.js) | DONE | See 1.35. |
| 9.2 | LLM preset, base URL, API key | 4657-4658 | Model → Service (PRESETS + custom), API key (encrypted) | DONE | |
| 9.3 | Discover models + filter + "free only" + ctx size label | discover/fillModels 4637-4653 | pickModel `app.js`; Parity.modelsInfo/modelInfo | DONE | Model picker uses `models.info` (id, context size from context_length / top_provider / other gateways, price-based free): rows show provider · "128k context" · name, a "free" badge, a **Free only** chip, "free" as a filter word, "N models · M free"; falls back to `models`. Discovered sizes also feed the compactor (`ctx.known`). |
| 9.4 | Main / fast / vision model | 4661 | Model, Fast model, Vision model | DONE | |
| 9.5 | **Max iterations per turn** | CFG.llm.maxIterations | Settings → Model — advanced → "Most steps per task" (setCfg maxSteps) | DONE | "Most steps per task" row. |
| 9.6 | Context budget | contextBudget | Context → Context budget | DONE | |
| 9.7 | Model context window override | ctxWindow | Context → Model context window | DONE | |
| 9.8 | Auto-compact at %, turns kept verbatim, auto-compaction on/off | compactAt/keepTurns/autoCompact | Context group | DONE | |
| 9.9 | Next-step suggestions on/off | CFG.llm.followups | Learning → Next-step suggestions | DONE | |
| 9.10 | Approval cards for work actions | CFG.workConfirm | Mail assistant → Confirm work changes | DONE | |
| 9.11 | **Temperature** | CFG.llm.temperature | Settings → Model — advanced → Temperature | DONE | See 2.32. |
| 9.12 | Max output tokens | maxTokens | Answer budget | DONE | |
| 9.13 | **Extra headers (JSON)** | extraHeaders | LlmX.extraHeaders → Llm.headers; Settings → Model — advanced | DONE | See 2.31. |
| 9.14 | Stream responses | stream | toggle | DONE | |
| 9.15 | System-1 backend / URL / key / model / state-char limit / threshold / per-gate switches | 4669-4675 | System-1 group + gatesSheet | DONE | System-1 state size row (stateChars). |
| 9.16 | Web proxy preset/template/headers/key | 4677-4681 | — | N/A | No CORS. |
| 9.17 | Search backend + SearXNG URL | 4681-4682 | Web search group (+Brave, Tavily keys) | DONE (better) | |
| 9.18 | **Memory: active max, draft max, draft trial days, notes per prompt, chars per note, memory search by reasoning, USER.md limit, review trigger floor** | 4685-4690 | Parity memx.get/set → MemoryXCfg (+ reviewFloor used by Gates.review); Settings → Memory limits | DONE | Settings → Memory limits: active/draft max, trial days, notes per prompt, chars per note, USER.md size, reasoning search, review trigger (used by background review). |
| 9.19 | Learn from experience + review every N turns | 4689 | Learning group | DONE | |
| 9.20 | **Permission preset (ask/auto/full/readonly)** | CFG.permissions, `#qPerm`, `/permissions` | "Ask before actions" toggle (askRisky) | DONE | See 2.9; Settings row + composer chip + /permissions. |
| 9.21 | Interface language (auto/en/tr) | CFG.uiLang 4693 | Answer language (auto/tr/en) drives both UI and answers | DONE | Settings → Appearance → Interface language (cfg.uiLang drives Y.tr), separate from the answer language. |
| 9.22 | State: include API keys in export, **Export state now**, **Erase everything** | 4695-4696 | Data → Import a yaver export; Sign out everywhere | DONE | Settings → Backup: Export everything (with "Include API keys and passwords"), Import (merge/replace), Erase everything (typed ERASE). |
| 9.23 | License section (device id, install code, load/replace/remove license, licensed endpoint, allowed models) | licenseBox 4823, Settings.open override 4839 | — | N/A (caveat) | Browser licensing binds a browser profile. If the vendor-managed endpoint + allowed-models feature is needed on Android, it must be re-implemented. |
| 9.24 | Mail settings (url, ews, triage, poll, briefAt, vision, indexMax, images, calView, taskSort) | CFG.mail 5115 | MailPlusCfg images, calView | DONE | `images` ("Always show pictures in e-mail" row in Mail assistant + "Always show" in the reading view) and `calView` are MailPlusCfg keys now. |
| 9.25 | Send images to the model | — | Settings → Model — advanced → "Send pictures to the model" | DONE | "Send pictures to the model" toggle. |
| 9.26 | Settings search | — | search box `app.js:1480` | DONE (extra) | |

## 10. Slash commands

| # | Command | JS | Native (`Bridge.command` `Bridge.kt:467`, COMMANDS `app.js:496`) | Status | Notes |
|---|---|---|---|---|---|
| 10.1 | /plan [req] \| off | 3021 | Planning.command | DONE | |
| 10.2 | /goal <obj> \| pause \| resume \| clear \| status | 3021 | Planning.command | DONE | |
| 10.3 | /compact [focus] | 3021 | Compactor.compactNow | DONE | |
| 10.4 | /new | 3021 | app.js newChat | DONE | |
| 10.5 | /clear | 3021 | clearContext | DONE | |
| 10.6 | **/permissions ask\|auto\|full\|readonly** | 3021 | Bridge.command → Parity.command → Permissions.command | DONE | Parity.command → Permissions.command; chip refreshes. |
| 10.7 | **/settings** | 3021 | Parity.command → {open:"settings"} → YVP.openTarget | DONE | Opens Settings. |
| 10.8 | **/debug** | 3021 | Parity.command → {open:"debug"} → debugSheet | DONE | Opens the debug dump. |
| 10.9 | **/help** | 3021 | Parity.command → {help:true} → note with COMMANDS | DONE | Note listing COMMANDS (all new commands added to the hints). |
| 10.10 | /refine [--global] \| history \| rollback <id\|last> \| auto <N\|off> | 8046 | Harness.command | DONE | |
| 10.11 | /harness | 8047 | Harness.command | DONE | |
| 10.12 | /autonomous on [flags] \| off \| status | 8048 | Planning.command | DONE | |
| 10.13 | /heartbeat every 10m … [--follow-up] \| status \| pause \| resume \| clear; /heartbeats | 8049, 8086 | Heartbeats.command/listNote | DONE | |
| 10.14 | /btw <q> | 8050 | SideQuestion.command | DONE | |
| 10.15 | /subagents [stop <name\|all>] | 8051 | Children.command | DONE | |
| 10.16 | /today | 7520 | MailPlus.dailyPlanPrompt | DONE | |
| 10.17 | **/mail, /calendar, /tasks** (open the views) | 7520-7521 | Parity.command → {open: mail, cal, tasks, files, memory, browser} | DONE | /mail, /calendar, /tasks (also /files, /memory, /browser, /undo) open the views. |
| 10.18 | Hints popup while typing "/" | — | updateHints `app.js:530` | DONE (extra) | It lists only the implemented commands. |

## 11. Diagnostics & export

| # | Feature | JS | Native | Status | Notes |
|---|---|---|---|---|---|
| 11.1 | TRACE timeline per session (LLM requests with message/tool counts, ms, errors), page errors | TRACE 3247-3260 | Diag.kt global log (`diag.get`) | DONE | Trace.kt: per-conversation timeline (LLM requests with messages/tools/ms/reply/tool calls/errors, tool runs) kept on disk with the conversation. |
| 11.2 | **Debug dump dialog**: markdown + JSON, full/short toggles, size, Copy / Share / Download; content: config summary, session flags (toolsets, plan, goal, todos), problems, **memory diagnostics** (notes, tiers, in-prompt, rules, keys, what each message got from memory), timeline, conversation, **LLM request details**, **S1 decisions**, **system prompt**, disk listing; secrets redacted | Debug 3314-3389, html dlgDebug | chatMenu → "Export this chat" (`convo.export` `Bridge.kt:264`): md transcript + JSON convo + last 80k of Diag log; "Diagnostic log" sheet | DONE | DebugDump.kt (`convo.debug`): config summary with keys-set flags, session flags, problems, memory diagnostics, timeline, conversation, LLM detail, S1 decisions, system prompt, disk list, redaction; Full/Short, Copy, Share .md/.json; from ⋮, the chat list and /debug. |
| 11.3 | **Export whole state (.zip)**: every VFS file + yaver-state.json (config, stats, optional secrets) | exportState 4737 | StateIO.export; Parity state.export; Settings → Backup | DONE | StateIO.export: fs/workspace (bytes), skills, PERSONA, MEMORY/USER/meta.json, sessions with tool calls + state, native/ stores, yaver-state.json (secrets only on request). |
| 11.4 | **Import state**: preview dialog (files, sessions, skills count), **Merge** vs **Replace everything**; restores the full disk, config, secrets, stats | handleZip 4748, applyImport 4766 | Migration.importZip `Migration.kt:61` | DONE | Preview sheet (files/sessions/skills/keys), Merge or Replace; bytes incl. binaries, sessions with all messages, todos/goal, memory meta, harness, task board, config, secrets; restart after a full restore. Reads HTML-app zips too. JVM round-trip test. |
| 11.5 | Per-folder zip download | exportFolder 4745 | FilesUi.download (folder → .zip) | DONE | Files → ⋮ on a folder → Save to the phone packs it as a .zip into Download/yaver (FilesUi.download → Archive). |
| 11.6 | Crash report | — | crash.last | DONE (extra) | |
| 11.7 | Stats persistence / reset | STATS, renderHarness reset | Trace.statsJson/resetStats; Settings → Usage | DONE | See 2.33 / 1.44. |
## 12. Mobile / phone / browser-only plumbing

| # | Item | JS | Native | Status | Notes |
|---|---|---|---|---|---|
| 12.1 | KV (localStorage + memory fallback), IndexedDB VFS backend | KV 499, VFS 529 | Prefs/Secure/files dir | N/A | |
| 12.2 | ZIP writer/reader (CompressionStream) | ZIP 713 | java.util.zip | N/A | |
| 12.3 | Icons sprite | ICONS 772 | window.ICONS (Lucide) | N/A | |
| 12.4 | License core (ECDSA), license gate | 1046-1146, renderLicenseGate 4860 | — | N/A | See 9.23. |
| 12.5 | MOBILE browser detection for bookmarklets, bridge note | MOBILE 5127, bridgeMobileNote 7141 | — | N/A | Native mail needs no bookmarklet. |
| 12.6 | I18N runtime dictionary (75 KB) | I18N 122 | T(en,tr) in every string | N/A | Equivalent. |
| 12.7 | Phone integration: default assistant, voice, always-on service, reply notifications, reminders/alarms/calendar, share intents, quick tile | — | PhoneTools.kt, AgentService, Notifier, Manifest | DONE (extra) | |

---

## 13. Beyond the original (4.1)
| # | Feature | Original | Native | Status |
|---|---|---|---|---|
| 13.1 | Audio/video decode + encode | none (browser Pyodide cannot decode m4a/mp3) | `media` tool: MediaExtractor/MediaCodec/MediaMuxer → WAV / m4a, start/duration cut (Media.kt) | DONE (extra) |
| 13.2 | One-step speech clean-up | none | `media action=enhance`: 80 Hz high-pass, spectral noise reduction, clarity EQ, level evening, look-ahead limiter (Dsp.kt, JVM-tested) | DONE (extra) |
| 13.3 | ffmpeg/ffprobe in the terminal | none | mapped onto the media tool (Shell.kt → ShellHooks.media) | DONE (extra) |
| 13.4 | Pages save files | browser download only | run_html: download links/clicks and `window.yaver.save` → /workspace/downloads; promise-aware eval | DONE (extra) |
| 13.5 | Browser opens disk files | n/a | `/workspace/…`, `file://…` served read-only (LocalView) | DONE (extra) |
| 13.6 | Audio/video preview | download only | in-app player with byte-range seeking | DONE (extra) |
| 13.7 | Broken tool call retry | none | one non-streamed re-ask for cut/empty-argument calls | DONE (extra) |
| 13.8 | Lesson from failed tasks | review may skip | budget exhausted / loop → forced refine with failure-specific instructions | DONE (extra) |

## Counts

Counted by script over the 237 matrix rows above, by primary status.

| Status | Rows |
|---|---|
| DONE (including "better" and "extra") | 223 (of which 22 "better"/"extra") |
| PARTIAL | 0 |
| MISSING | 0 |
| N/A | 14 |
| WIP | 0 |

**The N/A rows** are browser plumbing with no Android meaning: splash screen (1.1), the right-click menu and dev-tool shortcuts (1.40), the "y" logo as a back button (1.47 — the drawer does this), the stale-HTML-file warning (1.48 — APK updates replace the app), disk-backend notices (4.13), the CORS proxy (6.1, 9.16 — there is no CORS on Android), KV/IndexedDB/ZIP/icon-sprite/I18N runtime (12.1–12.3, 12.6), the browser license core and the bookmarklet detection (12.4, 12.5) and the license section (9.23). **Caveat on 9.23:** the HTML licence can also provision a vendor-managed LLM endpoint and an allowed-models list; the APK has no such provisioning (the user enters the service in Settings → Model). If a managed deployment is required on Android, it needs its own design (e.g. managed-configuration restrictions), not a port of the browser licence.

---

## Verification (final pass, 2026-11-05)

| Suite | Result |
|---|---|
| Kotlin compile of all 64 `*.kt` (kc.sh, android-34 stubs) | clean, no output |
| `node --check` app.js, parity.js, vendor/icons.js | OK |
| coretest (agent loop, harness, refine) | 34 passed, 0 failed |
| files/vfstest (virtual disk, Files bridge) | 92 passed, 0 failed |
| gap2test (permissions, changes/revert, export/import, debug dump, skills, + v4.0 checks: plan-mode side effects, models.info, progress log, browser.live, gates.clearLog, files.stats, /browser) | 270 passed, 0 failed |
| mailgap (EWS/OWA mail and calendar parity) | ALL OK |
| doctest run.sh + validate.py (write_document → python-docx/openpyxl/python-pptx) | 55 passed + ALL OK |
| Playwright uiaudit f_mail / f_settings / f_chat | no problems |
| Playwright files/ui f_files | ALL OK |
| Playwright mailui t_mail | no problems |
| Playwright gap2ui f_gap2 | ALL OK |
| **Smoke** `scratchpad/smoke/smoke.js`: 61 screens × light/dark at 360 px, Turkish; fails on console/page errors, unhandled rejections, unknown bridge methods, overflow, unnamed controls, "undefined"/"NaN", English UI words; a canary proves each detector fires | SMOKE OK |

**Needs a real device to confirm:** the live picture of the headless browser (WebView.draw of an off-screen WebView — the same path the agent's screenshot action already uses); "Take over" handing the page back; "Open the page" for html/svg in BrowserActivity (local files through shouldInterceptRequest); Run & check timing on a slow phone; the nebula animation cost on low-end GPUs; PDF page rendering of large files; mail/calendar against a real Exchange server.

---

## Gap list from the audit — all closed

| Item | Closed in |
|---|---|
| P1-0 FilesUi missing, write_document unregistered | 3.32, 4.2 |
| P1-1 Files page, editor, in-app preview | 4.2–4.4, 11.5 |
| P1-2 Clickable file paths, picture row | 1.32 |
| P1-3 Permission presets | 2.9, 9.20, 10.6 |
| P1-4 Files-changed card, diff, revert | 1.24, 4.5 |
| P1-5 Whole-state export/import | 11.3, 11.4, 9.22 |
| P1-6 Skills editor | 7.8, 7.9 |
| P1-7 Calendar views and event editing | 8.17, 8.18 |
| P1-8 Move/archive/unread/permanent delete/undo | 8.7–8.9 |
| P1-9 Drafts | 8.10 |
| P1-10 Draft with AI | 8.12 |
| P1-11 Task from e-mail, manual task, summarize | 8.13 |
| P2-1 … P2-16 (HTML mail, triage, index search, tool-step detail, debug dump, commands, memory/LLM settings, vision_analyze, shortcuts, undo /clear, folders, attachments, paste/zip, S1 stats) | 8.5, 8.4, 8.15, 1.7, 11.2, 10.x, 9.18, 9.11–9.13, 3.10, 1.4, 1.18, 8.2, 8.14, 1.38, 1.43/1.44 |
| P3-1 … P3-13 (theme, timestamps, plan/permission chips, model switch, browser live view, seed skill, raw memory editor, delivered badge, interface language, keyboard, model ctx/free, nebula logo, erase everything) | 1.35, 1.8, 1.21/1.37, 1.36, 1.42, 7.13, 7.10, 1.15, 9.21, 1.41, 9.3, 1.2, 9.22 |
