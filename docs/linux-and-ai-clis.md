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
  inside the APK as `libproot.so`, in the native library folder Android allows. The GitHub build downloads it with
  `scripts/fetch-proot.sh`.
- proot runs the Ubuntu base system unpacked in the app's private storage. Codriod writes these files for it:
  - fake `/proc` files where Android hides the real ones
  - DNS and apt settings
  - the `codriod` and `xdg-open` commands
- Node.js comes from nodejs.org (latest 22.x, SHA-256 checked). The CLIs come from npm (`npm install -g`).
- Licenses: proot is GPL-2.0 (https://github.com/termux/proot) and talloc is LGPL-3.0.
