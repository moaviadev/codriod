# Codriod Agent

Codriod Agent is the built-in coding agent. You can open it in two ways:

- From the editor, tap or swipe the purple **AI** tab on the right edge. The agent
  panel slides in over your code. Make it full screen with the expand button.
- From **Add-ons → Codriod Agent**, where it opens full screen.

## Modes

| Mode | What it does |
| --- | --- |
| Ask | Reads and searches the project and answers. Never changes anything. |
| Plan | Investigates with read-only tools and writes a plan (`update_plan`). You get *Approve & build*, *Approve & run on Autopilot*, or *Edit plan*. |
| Agent | Plans, edits files, runs commands, uses the browser and Git. Risky steps ask first. |
| Autopilot | Works end to end without asking, with a higher step limit. Dangerous commands and Git push still ask. |

## Approvals

| Action | When it asks |
| --- | --- |
| File edits | Each edit shows a diff with *Apply* or *Reject*, unless *Apply edits automatically* is on, or the mode is Autopilot. |
| Terminal commands | Depends on the policy: *Ask every time*; *Auto-run safe commands* (read-only commands such as `ls`, `cat`, `grep`, `find`, with no redirection or chaining); or *Run all commands*. *Always* on a command remembers the choice for that project. |
| Dangerous commands | Always ask, even on Autopilot. Examples: `rm -r` on `/`, `~`, `*`, `.` or the sdcard (however the flags are written), `mkfs`, `dd of=/dev/…`, fork bombs, `reboot`, `su`, `chmod 777 /`, `pm uninstall`, `setprop`. The card turns red. |
| Undo | After a run, a *N files changed* card lists the edits. *Undo all* restores every changed file and deletes the files the agent created. |

## Tools

| Group | Tools |
| --- | --- |
| Files | `list_files`, `read_file`, `search_files`, `view_image`, `write_file`, `edit_file` (with `replace_all`), `rename_file`, `delete_file` |
| Terminal | `run_command`: the phone's `/system/bin/sh` with a timeout of up to 600 s. Codriod phone commands are available (see below). |
| Plan | `update_plan`: a live to-do checklist shown in the chat |
| Browser | `browser_open` (Mobile/Tablet/PC), `browser_snapshot` (numbered elements), `browser_click`, `browser_type`, `browser_scroll`, `browser_back`, `browser_eval`, `browser_console`, `browser_network`, `browser_screenshot`. The agent uses its own *Agent* tab. A live card shows its screenshots; *Take over* opens that tab for you. |
| Web | `fetch_url` reads pages and docs as text |
| Git | `git_account_status`, `github_login_start`, `github_login_verify`, `git_configure`, `git_test_access`, `git_sync` |

## Images and context

- **＋** attaches a photo from the camera or gallery, a screenshot of Codriod's
  browser, or a file. Images are scaled down to 1600 px.
- When the selected model can see images, a **🖼** button appears in the composer,
  and the model chip shows 👁.
- Codriod decides which models can see images in this order:
  1. Your own choice. Long-press a model in the list and pick *Can see images*,
     *Text only* or *Automatic*.
  2. The provider's model list, where it says so. OpenRouter, Hugging Face, GitHub
     Models, Mistral and others do.
  3. A guess from the model name. Examples: GPT-4o/4.1/5, Claude, Gemini, Llama 4,
     Qwen-VL, Pixtral, Grok 4, Gemma 3.
- If you attach an image to a model that cannot see it, a bar offers to switch
  to a 👁 model.
- With **@** (Context) you can attach open files, a folder, the browser page with
  its console, the terminal output, or Git changes.
- *Send the open file* adds the editor's file to every request.
- `AGENTS.md` or `CODRIOD.md` in the project is followed as project rules.

## Voice

| Feature | How it works |
| --- | --- |
| Dictation | 🎤 in the composer types what you say, live. Tap ■ to finish. |
| Voice chat | When the message box is empty, the round purple button opens voice chat. You speak, the agent works, and the answer is read aloud. With *Hands-free* on, it listens again after each answer. |
| During voice chat | Tap the circle to interrupt the voice or to finish speaking. Use ⏸ to pause, ■ to stop the task, and 🔇 to mute the voice. |
| Approvals by voice | When a step needs approval, the agent asks out loud. Say *yes*, *no* or *always* (ہاں / نہیں / ہمیشہ). For a plan, say *build* or *autopilot*, or say what to change. Say *stop* (رکو) to cancel. |
| 🔊 Read | On any answer, reads it aloud. *Read replies in text chat* speaks every answer. |
| Spoken answers | The model is asked to answer briefly in the language you spoke. Code goes into files and is not read out. |

Voice settings (**Agent settings → Voice**):

- **Language:** *Auto* uses the phone's language for listening. Spoken replies use
  the language of the text: Urdu, Arabic, Hindi and others are detected
  automatically.
- **Speech recognition:**
  - The phone's recognizer.
  - *Whisper on Groq*, with your free Groq key.
  - *OpenAI transcribe*, with your OpenAI key.

  Whisper is best for Urdu and for mixed Urdu and English.
- **Reply voice:** the phone's text-to-speech, or OpenAI voices with your key.
  If the phone has no voice for a language, Codriod offers to install one.
- **Speed**, **Hands-free** and **Read replies in text chat**.

The microphone permission is asked only when you first tap a mic button.

## Models

- **Model switcher:** the chip at the top lists only providers you added keys for,
  with search and filters for vision, tools and free. Recent models are listed
  first.
- **First run:** three steps. Pick a provider, paste the key (it is checked and the
  models are fetched), then choose a default model and the permissions.
- **Fast model** (optional, in Settings): names each chat.
- **Streaming:** answers appear as they are written, for both OpenAI-style and
  Anthropic providers. If a server rejects streaming, Codriod falls back to a normal
  request.
- **Long chats** are compacted automatically: old tool output is shortened, old
  images are dropped, and the oldest turns go first, without breaking tool-call
  pairs.

## Chats

Every project keeps several chats in `files/agent/chats/<project>/`. **History**
lists them by project, with their mode, files changed and status. You can search
them, open one, start a new chat or delete one.

## Terminal phone commands

Codriod adds shell functions to its terminal and to the agent's shell:

| Command | What it does |
| --- | --- |
| `codriod open-url URL` | Open a link in another app |
| `codriod browse URL` | Open a page in Codriod's browser |
| `codriod open FILE\|URL` | Open a file in the editor or another app |
| `codriod share FILE\|TEXT [--app whatsapp\|telegram\|gmail]` | Android share sheet |
| `codriod clipboard get` / `codriod clipboard set [TEXT]` | Phone clipboard (`set` also reads stdin) |
| `codriod notify [-t TITLE] TEXT` | Notification |
| `codriod toast TEXT` | Short on-screen message |
| `codriod vibrate [-d MS]` | Vibrate |
| `codriod screenshot [FILE]` | Save a screenshot of the browser |
| `codriod help` | List the commands |

The Termux names work too: `termux-open-url`, `termux-share`,
`termux-clipboard-get`/`-set`, `termux-toast`, `termux-notification`,
`termux-vibrate`, `termux-open` and `xdg-open`.

Android 10 and later do not let apps execute files from their data folder, so
these are shell functions. They pass requests to the app through a private folder
(`files/codriod/ipc`).

## Not possible on Android

The agent cannot control other apps. For example, it cannot place a WhatsApp call or
take a screenshot of WhatsApp. To build a screen like another app's, attach a
screenshot. The agent can open links and share files to other apps.
