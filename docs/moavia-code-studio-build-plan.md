# Codriod — Build Plan

*Phase-by-phase prompts. Paste one phase at a time, test it, then move on.*

## 0. پہلے ایک اہم حقیقت

Google AI Studio کا Build موڈ (جتنا مجھے معلوم ہے) **ویب ایپس** بناتا ہے (React/TypeScript)، نیٹیو Android Kotlin ایپ یا اصلی Linux ٹرمینل نہیں۔ اس لیے منصوبہ دو مرحلوں میں ہے:

- **Stage A (AI Studio):** موبائل فرسٹ، مکمل انٹرایکٹو **پروٹو ٹائپ** (ایڈیٹر، ٹرمینل، پریویو، AI پینل، ایکسٹینشنز، رن ٹائمز)۔ ٹرمینل، رن ٹائمز اور بعض ایجنٹس نقلی (simulated) ہوں گے۔ مقصد UX کو پرکھنا اور ڈیمو بنانا ہے۔
- **Stage B (Android Studio):** اسی پروٹو ٹائپ کو اسپیک بنا کر اصلی Kotlin + Jetpack Compose ایپ۔

AI Studio کی موجودہ صلاحیتیں آپ ایک بار خود دیکھ لیں، کیونکہ یہ ٹول تیزی سے بدلتا ہے۔

## طریقہ کار

1. پہلے **Master Context** پرامپٹ پیسٹ کریں (صرف ایک بار)۔
2. پھر **Phase 1** کا پرامپٹ پیسٹ کریں۔ جب "Done when" کی فہرست پوری ہو جائے تو اگلا فیز۔
3. کچھ ٹوٹ جائے تو آخر میں دیا گیا **Fix Prompt** استعمال کریں۔
4. ایک پرامپٹ میں کبھی دو فیز نہ ملائیں۔

## Master Context (ایک بار پیسٹ کریں)

```
You are helping me build "Moavia Code Studio": a mobile-first IDE web app (Android-phone-sized viewport, 390x844 baseline) that feels like VS Code redesigned for phones. Tagline: "Code. Build. Run. Anywhere."

PRODUCT IDEA
- Workspace-first: the app opens on a Home screen listing all projects, then opens a Workspace where the code editor is the main area.
- Workspace layout: compact top bar (back, project name, branch badge, undo, redo, find, run); file tabs; code editor fills the screen; a slim vertical "AI" handle on the right edge opens an AI agents panel; a collapsed "Terminal" bar at the bottom slides up into a bottom sheet; a slim bottom icon bar (Files, Search, Git, Preview, Add-ons).
- Built-in Preview browser for localhost with viewports: Phone, Tablet, Web, Compare, plus Split view and a console.
- AI Agents panel: one tab per installed extension (e.g. Claude Code, Codex, Cline). The user signs in with their own account inside each extension. Each agent has folder access (project or any folder, read or edit). Any file change shows a diff to Review/Apply, and any terminal command asks permission first.
- Add-ons screen with two tabs: Extensions (install, sign in, permissions) and Runtimes (one-click Python, Node.js, Java, Kotlin/Gradle, library packs; a banner suggests what the open project is missing).
- Local and remote (SSH) workspaces are shown with Local / SSH badges everywhere.

DESIGN LANGUAGE (compact, modern, dark)
- Colors: background #0F0F14, surface #1A1A22, border #2A2A36, text #E6E6EF, muted #9A9AAE, accent purple #8B7CF6 (light accent #B3A8FF), success #7EE0A0, error #FF7B8A.
- Compact controls: buttons 30px tall, icon buttons 36px, top bar 44px, bottom icon bar 48px, tab row 32px, 12px body text, 8-10px radius, 8px gaps. The code area must get as much screen as possible.
- Keep tap areas comfortable by adding invisible padding, not by making controls larger.
- Use real <button> elements, aria-labels on icon-only buttons, no emoji as icons (use an icon library such as lucide-react).

TECH RULES
- React + TypeScript + Tailwind. One global store (Zustand) for projects, files, tabs, terminal sessions, extensions, runtimes, settings. Persist in localStorage.
- Put every external capability behind a small interface with a mock implementation (FileSystemService, TerminalService, AgentService, RuntimeService, PreviewService) so the real Android versions can replace them later.
- Mobile-first; on wide screens show a centered phone-width frame or a docked layout, never a stretched mobile UI.
- Anything simulated must be labeled "simulated" in a subtle way in the UI.

WORKING RULES
- Build ONLY the phase I give you. Do not remove or break earlier phases.
- At the end of every phase reply with: what was built, what is simulated, and the "Done when" checklist with pass/fail.
```

## Phase 1 — App shell, theme and Home

```
PHASE 1: App shell, design tokens and Home screen.

Build:
1. Design tokens (colors, spacing, radii, font sizes) from the Master Context as Tailwind theme + CSS variables, and a small set of shared components: Button (primary, secondary, icon), Chip, Badge, Card, ListRow, SegmentedTabs, BottomSheet, IconBar. All compact as specified.
2. Simple screen router: Home, Workspace, Add-ons (Workspace and Add-ons can be empty placeholders for now).
3. Home screen: top bar with app name, search and settings icons; filter chips (All, This phone, SSH servers); a list of all projects (name, type, Local/SSH badge, last opened); a collapsed "Explore" row (templates, tips, new extensions) that is CLOSED by default and expands on tap; bottom action row: New project (primary), Open, Clone, Server.
4. New project flow (bottom sheet): name + template (Python, Node.js, Web, Android Kotlin). Creates a project with starter files in the virtual file system. Seed 4-5 sample projects.
5. Tapping a project opens the Workspace placeholder with that project name.

Done when: projects persist after reload; Explore is collapsed by default; creating a project adds it to the list; all controls follow the compact sizes.
```

## Phase 2 — Workspace and code editor

```
PHASE 2: Workspace screen with the code editor.

Build:
1. Workspace layout: compact top bar (back to Home, project name, branch badge, undo, redo, find, run), file tab row (unsaved dot, close, + new file), editor filling all remaining space, slim bottom icon bar (Files, Search, Git, Preview, Add-ons). Leave a reserved slim vertical "AI" handle on the right edge and a collapsed "Terminal" bar above the icon bar (wired up in later phases).
2. Editor: use CodeMirror 6 (touch friendly). Syntax highlighting for Python, JavaScript/TypeScript, HTML, CSS, JSON, Markdown, Kotlin, Java; line numbers; autocomplete; bracket matching; find and replace; undo/redo; code folding; error underline support (a simple lint for obvious syntax errors is enough).
3. Extra coding keys row above the keyboard: Tab, { }, ( ), [ ], quotes, =, :, #, arrows.
4. Files icon opens a file explorer as a bottom sheet or left drawer: tree view, create / rename / delete file and folder, long-press menu.
5. Search icon: search across project files with results list that jumps to the line.
6. Autosave to the virtual file system. Git and Preview icons can show a "coming soon" sheet.
7. Make the editor area as large as possible: no extra padding or decorative headers.

Done when: I can edit multiple files in tabs, create/rename/delete files, search the project, and everything survives reload.
```

## Phase 3 — Terminal

```
PHASE 3: Terminal bottom sheet (simulated shell).

Build:
1. Collapsed state: a slim 30px bar labeled "Terminal" with the active session name on the right (default state, closed).
2. Tap or drag up: the terminal slides up as a bottom sheet (about 40% of the screen, resizable by dragging, with a collapse button). The code editor stays visible above it.
3. Session tabs (e.g. "bash - local", "my-vps - ssh"), a + button to open a new session, close session.
4. Use xterm.js with a simulated shell backed by the project's virtual file system. Support: help, ls, cd, pwd, cat, echo, mkdir, touch, rm, mv, cp, clear, history, and git status/add/commit (simulated). For python <file> and node <file> run the file in a safe in-browser simulation (print output, show errors). For npm/pip install show a believable simulated progress output. Label simulated output subtly.
5. Command history (up/down), command suggestion chips (git status, npm install, ls) above the keyboard, and an extra keys row: Ctrl, Alt, Esc, Tab, arrows.
6. The Run button in the top bar executes the active file in a terminal session.
7. Local vs SSH sessions have different prompts and badges (SSH is also simulated).

Done when: the terminal opens and collapses smoothly, commands change the virtual files, Run prints output, and multiple sessions work.
```

## Phase 4 — Preview browser

```
PHASE 4: Built-in Preview browser for localhost.

Build:
1. The Preview icon in the bottom bar opens a Preview screen: top bar (back, title, reload, settings); an address bar showing localhost:3000 with a green dot when the simulated dev server is running; a Split button.
2. Render the project's HTML, CSS and JS from the virtual file system inside a sandboxed iframe (srcdoc / blob). Reload re-renders. Changes in the editor refresh the preview automatically (live reload toggle).
3. Viewport switcher: Phone (390x844), Tablet (768x1024), Web (1280x800), and Compare (all three side by side, scaled to fit). Show the size label and scale. Add a rotate button.
4. Split view: editor on top and preview below on the Workspace.
5. A collapsible Console strip at the bottom that captures console.log, warnings and errors from the iframe, with an error count.
6. A start/stop dev server control (simulated) tied to the terminal commands npm run dev and python -m http.server.

Done when: editing HTML/CSS/JS updates the preview, the four viewport modes work, and console messages appear.
```

## Phase 5 — AI agents panel

```
PHASE 5: AI agents panel with extension tabs.

Build:
1. A slim vertical "AI" handle on the right edge of the Workspace (small, accent color). Tap it to slide in an AI panel from the right (about 320px wide, rest of the code dimmed). Also add an AI button in the top bar overflow so the panel does not depend only on edge swipes. Close button inside.
2. Agent tabs: one tab per installed AI extension (Claude Code, Codex, Cline, and Gemini), a signed-in dot, and a + to open Add-ons. Each tab keeps its own chat history.
3. Folder access card per agent: the current project (edit) plus extra folders the user adds from the file explorer, each with a read or edit permission that can be changed or revoked.
4. Chat: mode chips (Ask, Edit, Agent), a command input, send. The Gemini tab must really work through the Gemini API using the project's built-in key and answer with project context (open file + selected files). Claude Code, Codex and Cline are mock agents that show a "Sign in" state first and then give scripted sample answers, clearly marked as demo.
5. File changes proposed by an agent appear as a diff card (+/- lines) with Review and Apply. Review opens a full diff view. Apply writes to the virtual file system and is undoable.
6. Permission cards: when an agent wants to run a terminal command it asks Allow once / Always for this project / Deny, then runs in the terminal sheet.
7. A "Fix with AI" action on editor errors and "Explain" on selected code that opens the panel with that context.

Done when: Gemini answers using my open file, changes can be reviewed and applied, commands need permission, and each agent tab keeps separate history and folder access.
```

## Phase 6 — Add-ons: Extensions and Runtimes

```
PHASE 6: Add-ons screen with Extensions and Runtimes.

Build:
1. Add-ons screen opened from the bottom bar, with two segmented tabs: Extensions | Runtimes.
2. Extensions: search, category chips (All, AI agents, Languages, Themes), Installed and Available lists. Each extension is defined by a manifest JSON (id, name, type, version, permissions: files, terminal, network, entry). Install / uninstall, Sign in / Manage (mock login screen inside the extension), and a permissions sheet before first use. Installing an AI agent extension adds its tab to the AI panel.
3. Runtimes: a banner such as "This project needs Python and Node.js - Install missing" (detected from project files). Runtime list: Python, Node.js, Java (JDK), Kotlin and Gradle with states: not installed, installing (progress bar, cancel), installed (Manage, uninstall). Library packs: Web basics (npm, Vite, Express), Data science (numpy, pandas), Android tools. Installing is simulated, but a runtime must change what the terminal accepts (python works only after Python is installed).
4. A note that installs go to this device and remote servers keep their own tools, with a "Use remote server" alternative for heavy runtimes.
5. Settings screen: theme (dark/light), font size, editor options, privacy (where logins are stored), about.

Done when: installing/uninstalling extensions updates the AI panel tabs, runtimes gate terminal commands, and the banner reflects the open project.
```

## Phase 7 — Polish, tablet layout and QA

```
PHASE 7: Polish and quality.

Build:
1. Tablet/large layout: docked AI panel and docked file explorer instead of overlays; on very wide screens keep a centered phone frame option.
2. Light theme with the same compact sizes; theme switch in Settings.
3. Empty states, loading states, error states, toasts, confirmations for destructive actions.
4. Keyboard shortcuts for external keyboards (save, find, run, toggle terminal, toggle AI).
5. Accessibility: visible focus, aria-labels, contrast at least 4.5:1, invisible hit-area padding so compact controls are still easy to tap.
6. Performance: lazy-load the editor, terminal and preview; keep first paint fast.
7. Add a README documenting the services/interfaces that must be replaced by real implementations on Android (FileSystem, Terminal/PTY, Runtime installer, Preview/WebView, Agents).

Done when: layouts work on phone and tablet, light and dark themes both look right, and the README lists every simulated part.
```

## Fix Prompt (جب کچھ ٹوٹ جائے)

```
Something from the current phase is broken: [describe what you tapped, what you expected, what happened]. Fix only this problem. Do not redesign anything, do not change other phases, keep the compact sizes, and tell me which files you changed.
```

## Stage B — اصلی Android ایپ (Android Studio، Kotlin + Compose)

یہ مرحلہ Stage A مکمل ہونے کے بعد ہے۔ ہر فیز کے لیے الگ تفصیلی پرامپٹ میں بنا سکتا ہوں۔

- **Phase 8 — Native skeleton:** Compose + Material 3، وہی ڈیزائن ٹوکنز، نیویگیشن، Home اور Workspace کا ڈھانچہ، Room میں پروجیکٹ میٹا ڈیٹا۔
- **Phase 9 — Editor اور فائلیں:** اصلی فائل سسٹم، ایڈیٹر انجن (Sora Editor جیسی لائبریری پر غور)، ٹیبز، سرچ۔
- **Phase 10 — Terminal runtime:** PTY سیشنز؛ یہاں Android 10+ کی ایگزیکیوٹ ایبل پابندیوں اور Play Store پالیسی کی تحقیق سب سے پہلے کریں۔
- **Phase 11 — Git اور SSH:** JGit اور SSH لائبریری، ریموٹ ورک اسپیس۔
- **Phase 12 — Preview:** WebView اور مقامی ڈیو سرور، ویو پورٹ موڈز۔
- **Phase 13 — Extensions اور AI ایجنٹس:** manifest، اجازتیں، سینڈباکس، ہر ایکسٹینشن میں یوزر کا اپنا لاگ ان، Keystore میں محفوظ کیز۔
- **Phase 14 — Runtimes، ریلیز اور پالیسی:** ون کلک انسٹالرز، بلڈ سروسز، Play Store ریویو کی تیاری۔

## ڈیزائن کا حوالہ

کمپیکٹ پروٹو ٹائپ (ساتوں اسکرینیں): https://claude.ai/artifact/WGE6KhAHaFAX8uetHAeG9e

## MEGA PROMPT — پورا ایپ ایک ہی پرامپٹ میں

اسے ایک بار AI Studio میں پیسٹ کریں۔ یہ اپنے آپ نو فیزز کا منصوبہ بنائے گا اور آپ کے کہنے پر ("Start Phase 1"، "Next") ایک ایک کر کے بنائے گا۔ اضافی پرو فیچرز میں نے خود شامل کیے ہیں (سیکشن 7)۔ جو نا چاہیں وہ پیسٹ کرنے سے پہلے مٹا دیں۔

```
ROLE
You are a senior product designer, full-stack engineer and Android architect. Build "Moavia Code Studio" (tagline: "Code. Build. Run. Anywhere."): a mobile-first IDE that turns a phone into a real development workstation. It is a VS Code-inspired experience redesigned for phones, not a shrunk desktop UI.

1. VISION AND USERS
- For students, independent developers and professionals who want to write, run, preview, debug, version and ship software from a phone.
- Principles: mobile-first, offline-first for local work, fast startup and low resource use, transparent local-vs-remote execution, secure permissions for terminal/files/AI, no forced subscription for basic tools, modular architecture, compatible with Android security rules and Google Play policy.
- Free tier: local editor, local terminal, supported languages, Git, basic project management. Pro tier: managed remote environments, hosted builds, advanced AI agent features, cloud sync. Users can always connect their own servers for free.

2. HOW YOU MUST WORK
- First reply: restate the product in 8 lines, show the phase plan (section 9), list assumptions and anything that will be simulated. Then WAIT.
- Build ONLY the phase I name ("Start Phase N" / "Next"). Never skip ahead. Never break earlier phases.
- After each phase reply with: (a) what was built, (b) what is simulated, (c) the Done-when checklist with pass/fail, (d) 3 suggested improvements. Then wait.
- Do not ask unnecessary questions; decide, state the assumption in one line, continue.
- Anything simulated must be labeled subtly in the UI ("simulated").
- When I report a bug, fix only that bug and list the files changed.

3. PLATFORM STRATEGY
- Build a mobile-first web app (React + TypeScript + Tailwind, 390x844 baseline, centered phone frame on large screens). It is the high-fidelity prototype and spec for a later native Android app (Kotlin, Jetpack Compose, Material 3, Room, Coroutines/Flow).
- Put every real-world capability behind an interface with a mock implementation so the Android version can replace it: FileSystemService, TerminalService (PTY), RuntimeService (installers), PreviewService (WebView + dev server), GitService, SshService, AgentService (extensions), SecretsService (Keystore), SyncService, BillingService.
- Use one global store (Zustand) persisted to localStorage. Lazy-load heavy parts (editor, terminal, preview).

4. DESIGN LANGUAGE (compact, modern, dark first)
- Colors: background #0F0F14, surface #1A1A22, raised #23232E, border #2A2A36, text #E6E6EF, muted #9A9AAE, accent #8B7CF6, accent-light #B3A8FF, success #7EE0A0, warning #FFB86B, error #FF7B8A. Optional light theme with the same structure.
- Compact sizing: buttons 30px, icon buttons 36px, top bar 44px, bottom icon bar 48px, tab row 32px, body text 12px, radius 8-10px, gaps 8px. The editor must get the maximum screen area. Keep real tap comfort with invisible hit-area padding.
- Icons: lucide-react, no emoji. Real <button>, aria-labels on icon-only buttons, contrast 4.5:1, visible focus.
- Support RTL and Urdu/Arabic UI text (UI strings in a translation file), English as default. Code always LTR.
- Fonts: a monospace with ligatures option (JetBrains Mono / Fira Code), adjustable size.
- One-handed ergonomics: primary actions at the bottom, swipe between tabs, long-press menus, haptic-style micro animations (subtle).

5. SCREENS AND NAVIGATION
- Home (all projects) -> Workspace (default working screen) -> overlays/sheets: Files, Search, Git, Preview, Terminal sheet, AI panel, Add-ons (Extensions | Runtimes), Settings, Command Palette, Upgrade.
- Workspace layout: top bar (back, project name, branch badge, undo, redo, find, run); file tabs; editor filling the screen; slim vertical "AI" handle on the right edge (plus an AI button in the overflow menu so it never depends only on edge swipes); slim collapsed "Terminal" bar above the bottom icon bar that slides up as a resizable bottom sheet; bottom icon bar: Files, Search, Git, Preview, Add-ons.

6. FEATURE SPEC
A. Home: all projects with Local/SSH/Cloud badges, last opened, search, filters, favorites/pin, archive, multi-select delete; collapsed-by-default Explore (templates, tips, new extensions, learning); actions New / Open / Clone / Server; workspace switcher; storage usage; import/export ZIP.
B. Editor: CodeMirror 6; syntax highlighting (Python, JS/TS, HTML, CSS, JSON, Markdown, Kotlin, Java, C/C++, SQL, YAML, Shell); autocomplete; snippets; bracket matching; multi-cursor; find/replace with regex and project-wide search; code folding; format on save (Prettier-like); error and warning underlines; Problems panel; split editor (two files); extra coding-keys row (Tab, { }, ( ), [ ], quotes, =, :, #, arrows); sticky scroll; large-file safe mode; autosave; file explorer (tree, create/rename/delete/move, long-press menu, recent files); breadcrumbs; go to line; hardware keyboard shortcuts.
C. Terminal: collapsed 30px bar by default; slides up to ~40% (resizable, collapsible); xterm.js; multiple sessions (local, SSH); simulated shell on the virtual file system (help, ls, cd, pwd, cat, echo, mkdir, touch, rm, mv, cp, clear, history, grep, git, python, node, npm, pip); history, suggestion chips, extra keys row (Ctrl, Alt, Esc, Tab, arrows); runtime-gated commands (python only after Python is installed); Run button runs the active file; run configurations auto-detected from package.json scripts and common entry files; long-running tasks keep running with a notification; output search and copy.
D. Preview browser: built-in browser for localhost with address bar (localhost:PORT, running dot), reload, live reload; viewports Phone / Tablet / Web / Compare (side by side) with rotate and size labels; Split view with the editor; sandboxed iframe rendering project files; console strip capturing log/warn/error; network and storage inspector (lite); screenshot and share; start/stop dev server tied to terminal commands; open in external browser; remote port forwarding for SSH workspaces.
E. AI Agents panel: slides in from the right; one tab per installed AI extension (Claude Code, Codex, Cline, Gemini, custom); each extension has its OWN login done by the user (nothing shared, keys and tokens in a secrets vault); per-agent chat history, model chooser, mode chips (Ask / Edit / Agent / Plan); folder access card per agent (current project, extra folders, read or edit, revocable); every file change is a diff card with Review / Apply / Reject and is undoable via checkpoints; every terminal command asks Allow once / Always for this project / Deny; audit log of all agent actions; Fix with AI on errors, Explain on selection, inline AI suggestions, AI commit messages, AI code review, project rules file (like CLAUDE.md / AGENTS.md) used as persistent instructions, token/cost meter, task queue for long tasks, voice input for prompts. Gemini tab works for real through the Gemini API; other agents are scripted demos marked as demo.
F. Add-ons: Extensions tab (search, categories, install/uninstall, Sign in / Manage, manifest JSON with id, name, type, version, permissions files/terminal/network, entry; permission sheet on first use; sandboxed execution) and Runtimes tab (banner "This project needs X - Install missing"; Python, Node.js, Java JDK, Kotlin/Gradle, Git, SQLite; library packs: Web basics, Data science, Android tools, Backend; one-click install with progress, cancel, update, uninstall; storage size shown; heavy runtimes offer "Use remote server").
G. Git: status, stage/unstage, commit, branches, merge, stash, history graph, diff viewer, conflict resolver, clone, push/pull, GitHub sign-in, pull requests list, SSH keys manager.
H. Remote: SSH workspaces, key and host manager, SFTP file browser, persistent sessions that survive disconnects, port forwarding to Preview, container-isolated build option, clear Local/Remote switch with the execution location always visible.
I. Settings: theme, font, editor options, language and RTL, keyboard shortcuts, storage manager, privacy and permissions, backups, about.

7. EXTRA FEATURES I WANT (add them, mark each Core or Pro)
- Command Palette: one search box for files, commands, symbols, projects, settings (Core).
- Snippet library and project templates (Python CLI, Flask/FastAPI, Express, React/Vite, static site, Android Compose, Discord/Telegram bot, data science notebook-style) (Core).
- Secrets vault: env vars, API keys, SSH keys, protected by biometrics; never sent to AI agents unless explicitly allowed (Core).
- Workspace Trust: ask before running a cloned project's scripts (Core).
- Debugger-lite: breakpoints and variable view for JS in Preview, console REPL (Core); full debug adapters later (Pro).
- Checkpoints and rollback for every agent change and every build (Core).
- Offline indicator, sync status, battery/performance mode that lowers animations and background work (Core).
- Learning mode for students: guided lessons, challenges, "explain this code in simple words", Urdu/English explanations (Core).
- Share: export ZIP, share a project as a GitHub repo or gist, read-only share link, build artifacts share (Core); live collaboration with presence and comments (Pro).
- Cloud sync and backup of projects and settings with end-to-end encryption (Pro).
- Managed remote dev environments with one-tap start/stop and usage meter (Pro).
- Hosted builds for Gradle/APK and Docker images with queue and logs (Pro).
- Advanced agent features: multi-agent parallel tasks, long-running background agents, larger context, team rules (Pro).
- Team workspaces and shared extension sets (Pro).
- Crash reports and analytics: opt-in only (Core).

8. BUSINESS MODEL IN THE UI
- Plans screen: Free vs Pro comparison, honest copy, no dark patterns, usage meters for managed resources, restore purchase. Basic development is never gated. Connecting your own server is always free. BillingService is mocked.

9. PHASES
Phase 1: tokens, shared components, router, Home, New project flow, sample projects.
Done when: projects persist; Explore is collapsed by default; compact sizes everywhere.
Phase 2: Workspace layout, editor, tabs, file explorer, search, extra keys, autosave, command palette skeleton.
Done when: multi-file editing, create/rename/delete, project search work and survive reload.
Phase 3: Terminal sheet, sessions, simulated shell, run button, run configurations.
Done when: terminal opens/collapses smoothly, commands change files, Run prints output.
Phase 4: Preview browser, viewports, compare, split view, console, simulated dev server.
Done when: editing HTML/CSS/JS updates the preview in all four viewport modes and console logs show.
Phase 5: AI Agents panel, agent tabs, folder access, diff review/apply, command permissions, checkpoints, audit log, real Gemini tab, demo agents.
Done when: Gemini answers with my open file as context, changes are reviewable and undoable, commands need permission.
Phase 6: Add-ons: Extensions (manifests, install, sign-in mock, permissions) and Runtimes (banner, installs, library packs, gating terminal commands).
Done when: installing an AI extension adds its tab; runtimes gate terminal commands.
Phase 7: Git and Remote (SSH, SFTP, port forwarding), all simulated with believable data.
Done when: stage/commit/branch/diff and an SSH workspace flow work in the UI with clear Local/Remote switching.
Phase 8: Pro and security features from section 7: secrets vault, workspace trust, debugger-lite, learning mode, share/export, sync status, plans and upgrade screen.
Done when: each listed Core item is reachable, each Pro item is visibly marked and mocked.
Phase 9: Polish: tablet layout (docked AI panel and explorer), light theme, RTL/Urdu check, empty/loading/error states, accessibility pass, performance pass, README listing every simulated service and what the Android implementation must replace it with.
Done when: phone and tablet layouts both pass, README is complete.

10. QUALITY BAR
- Compact, professional, consistent; no lorem ipsum; realistic sample data; smooth sheet animations; every button does something visible; every empty state explains the next step; no feature silently fails.

Now reply with the 8-line restatement, the phase plan and your assumptions. Then wait for "Start Phase 1".
```
