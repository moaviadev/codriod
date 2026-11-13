# Linux & AI CLIs

**Add-ons → Linux & AI CLIs** installs Ubuntu 24.04 on the phone with one tap, along with git, curl, python, Node.js 22
and the official AI coding CLIs. Each CLI signs in with **your own account**, in your browser. Logins stay on the
phone in Ubuntu's `/root` folder and are never sent to Codriod.

| CLI | Command | Sign in |
| --- | --- | --- |
| Claude Code (Anthropic) | `claude` | Claude Pro/Max account or API key |
| Codex CLI (OpenAI) | `codex` | ChatGPT Plus/Pro account or API key |
| Gemini CLI (Google) | `gemini` | Google account (free tier) or API key |
| GitHub Copilot CLI | `copilot` | GitHub account with Copilot |
| Qwen Code | `qwen` | Qwen account (free) or API key |
| opencode | `opencode` | Your provider keys |
| Kilo Code, Crush, Amp (beta) | `kilocode`, `crush`, `amp` | Their own accounts or keys |
| GitHub CLI | `gh` | `gh auth login` |

Requirements:

- A 64-bit phone (arm64), which almost all phones are. Most CLIs are not built for 32-bit phones.
- About 1–2 GB of free space.
- Internet while installing.

## While it installs

- The screen shows the percentage, **the time left** (for example "about 6 min left"), how long it has been running, and
  the usual total for what you picked (for example "8–15 min").
- Each running step has its own bar. The numbers come from real progress:
  - apt's status lines (packages downloaded and installed)
  - Node.js download stages
  - the Ubuntu download and unpack counters
- The estimate adapts to your measured internet speed.
- The install keeps going if you switch apps. A notification shows the progress and the time left.

## Using a CLI

- Every installed CLI gets a tab on the editor's right edge, under **Codriod**. Tapping the tab opens the terminal in
  Ubuntu, inside your project, with the CLI running. You can hide a tab or show it again from the CLI's ⋯ menu.
- You can drag the tabs up or down so they don't cover your code.
- **Sign in** runs the CLI's login (for example `codex login` or `gh auth login`). Login pages open in your phone's
  browser, and you return to the terminal when you're done.
- In the terminal, **Phone / Ubuntu** switches between the Android shell and Ubuntu.
- The open project is at `/root/projects/<name>` in Ubuntu. These are the same files the editor shows.
- `xdg-open`, `termux-open-url` and the other `codriod` phone commands work in Ubuntu too.

## Codriod Doctor (automatic repair, no AI)

While installing, Codriod reads every error. When it recognizes the error, it repairs it and tries again, up to four
times:

| Problem | What Codriod does |
| --- | --- |
| DNS doesn't work | Switches DNS servers |
| Network drops | Waits and retries; downloads resume where they stopped |
| Damaged download | Downloads it again (checked with SHA-256) |
| apt is locked or interrupted | Removes the stale locks and runs `dpkg --configure -a` |
| Package lists are broken or have wrong dates | Refreshes the lists |
| Half-installed packages | `apt-get -f install` |
| apt sandbox errors under proot | Fixes apt's settings |
| SSL certificate errors | Reinstalls the certificates. If the phone's date is wrong, asks you to fix it |
| npm cache damaged (EINTEGRITY) | Cleans the cache |
| A half-done npm install (ENOTEMPTY) | Removes the leftover folders |
| Permission problems | Fixes them |
| Node.js missing or too old (EBADENGINE) | Installs Node.js 22 |
| Storage full | Cleans the caches, then tells you if more space is needed |
| Out of memory | Retries; asks you to close other apps if needed |
| proot seccomp problems | Turns off seccomp acceleration |
| Ubuntu files damaged | Unpacks Ubuntu again and keeps your logins |

**Check health** tests:

- the runtime
- Ubuntu
- internet and DNS
- apt
- Node.js
- every CLI
- free storage

**Repair all** fixes whatever it can. If the Doctor can't fix something, **Ask Codriod AI** sends the problem and the log
to the agent.

## Codriod AI and Linux

The agent knows the Ubuntu environment and has these tools:

| Tool | What it does |
| --- | --- |
| `linux_run` | Runs bash commands in Ubuntu: apt, node/npm, python, builds and tests. It can also call CLIs non-interactively, for example `claude -p "…"`, `codex exec "…"` or `gemini -p "…"`. It asks for approval like other commands. |
| `linux_install` | Installs `ubuntu`, `node` or any CLI with the one-tap installer. |
| `linux_doctor` | Runs the health check, and repairs with `repair=true`. |
| `linux_status` | Shows what is installed. |

## How it works

- Android 10 and later don't let apps run programs from their data folder. So proot, Termux's Android build, ships
  inside the APK as `libproot.so`, in the native library folder Android allows. Every build fetches it: locally the
  Gradle `fetchProot` task runs before each build (any OS, no Linux tools needed); the GitHub build uses
  `scripts/fetch-proot.sh`.
- proot runs the Ubuntu base system unpacked in the app's private storage. Codriod writes these files for it:
  - fake `/proc` files where Android hides the real ones
  - DNS and apt settings
  - the `codriod` and `xdg-open` commands
- Node.js comes from nodejs.org (latest 22.x, SHA-256 checked). The CLIs come from npm (`npm install -g`).
- Licenses: proot is GPL-2.0 (https://github.com/termux/proot) and talloc is LGPL-3.0.

## CLI panels (chat UI for the official CLIs)

Tapping a CLI's tab on the editor edge opens its **panel**, a chat UI like Codriod AI's, while the real CLI runs in
Ubuntu. A **long press** on the tab opens the CLI in the terminal instead.

### Codex (full panel)

The panel talks to `codex app-server`, the protocol OpenAI's own IDE extension uses. It has:

- **Sign in:**
  - with ChatGPT (OpenAI's page opens in your browser)
  - with a device code
  - with an API key
- **Account and limits:** your plan, and the **5-hour and weekly limits**. Each shows the percent used and when it
  resets, live under the header. Tapping it shows the details.
- **Tokens:** how many this chat used, and how much context is left. **Compact** frees context.
- **Model and effort:** the model list comes from Codex, and **reasoning effort** offers the levels that model supports.
- **Messages:** thinking, the plan, and every command with **live output** and exit code. Every file change shows as
  a **diff**.
- **Approvals:** Run / Always / Deny. You can also type or say yes / no.
- **Chats:** open, fork and archive earlier chats. While Codex works, you can type to **steer** it.
- **Review:** `/review` reviews uncommitted changes.
- **Images:** you can attach images.

### GitHub Copilot and the others (ACP)

Copilot (`copilot --acp`), Gemini CLI, Qwen Code, opencode, Claude Code (through `claude-code-acp`) and Kilo use the
Agent Client Protocol. The panel shows:

- messages, thinking and tool calls
- diffs and command output
- the plan
- the agent's modes and models, and effort where offered
- slash commands
- context used, and cost where reported
- earlier sessions, if the CLI supports them

For Copilot it also counts the **premium requests** used in the session (one per prompt) and links to your monthly
usage on GitHub. Sign in uses the CLI's own login, either through the agent or in the terminal.

### Permissions

The CLIs' own sandboxes need Linux kernel features that proot doesn't have, so Codriod decides what runs:

| Level | What happens |
| --- | --- |
| **Read only** | Every change and command waits for you. |
| **Auto** | File edits inside the project and read-only commands (`ls`, `cat`, `grep`…) run by themselves. Everything else asks. |
| **Full access / Autopilot** | Everything runs, except dangerous commands (`rm -rf /`, `mkfs`, …), which always ask. |

A CLI without a chat mode (Crush, Amp, GitHub CLI) opens in the terminal.
