# Build Android apps on the phone

Codriod can build an Android app, install it and read its crash logs, all from the phone. Any agent can do it:
Codex, Copilot, Gemini, Claude Code, Qwen, opencode and Codriod AI.

## 1. Install the app: `codriod install`

| Command | What it does |
| --- | --- |
| `codriod install app.apk` | Installs the APK. It works in the phone shell and in Ubuntu. |
| `codriod uninstall com.example.app` | Uninstalls the app. Android asks you to confirm. |
| `codriod launch com.example.app` | Starts the app. |

- Android shows its own **Install** confirmation, because no app is allowed to install apps silently.
- The first time, Android asks you to allow "Install unknown apps" for Codriod.
- The command waits for your answer and prints the result: installed, cancelled, or why it failed, for example a
  different signature, an incompatible app, or an unsigned APK. Agents read that and act on it.
- In **Files**, tapping an `.apk` installs it.

## 2. Build on the phone: Android SDK add-on

**Add-ons → Linux & AI CLIs → Android apps → Android SDK.** Accept the Android SDK License, then tap Install. It
installs:

- JDK 17 and adb, from Ubuntu
- Google's command-line tools (SHA-1 checked), `platforms;android-35`, `build-tools;35.0.0` and `platform-tools`
- on ARM phones, ARM builds of **aapt2**, aidl and zipalign. Google ships these only for x86 Linux; the ARM builds come
  from github.com/lzhiyong/android-sdk-tools, and Gradle is pointed at them with `android.aapt2FromMavenOverride`.
- Gradle settings for a phone: memory, workers, cache

To build, run this in a project folder:

| Command | What it does |
| --- | --- |
| `android-build` | Debug APK (signed with the debug key) |
| `android-build --install` | Build, then install |
| `android-build test` | Any Gradle task |

Codriod Doctor knows the common Gradle problems on phones:

- running out of memory: it lowers the memory and turns off the daemon
- unaccepted licenses
- aapt2 not running
- SDK location not set

## 3. Wireless debugging (optional, for full automation)

**Add-ons → Linux & AI CLIs → Wireless debugging.**

1. Turn on Developer options and then Wireless debugging. Wi-Fi must be on.
2. Pair with the pairing code.
3. Tap Connect. Codriod looks for the port on its own; if it can't, type it in.

After that:

- `codriod install` installs **without a dialog**, over adb.
- `android-logs com.example.app` shows the app's logs and crashes, so an agent can see why the app crashed and fix it.
- `adb` works in Ubuntu.

If Wi-Fi changes or the phone restarts, tap Connect again.

## How agents learn this

Codriod writes the phone's commands and the "build, install, launch, read logs, fix" workflow into each CLI's global
instructions file in Ubuntu:

- `~/.codex/AGENTS.md`
- `~/.gemini/GEMINI.md`
- `~/.claude/CLAUDE.md`
- `~/.qwen/QWEN.md`
- `~/.copilot/copilot-instructions.md`
- `~/.config/opencode/AGENTS.md`
- `~/CODRIOD.md`

Codriod's text sits between `<!-- codriod:begin -->` and `<!-- codriod:end -->` markers, so anything you write there
yourself is kept. Codriod AI gets the same text in its prompt.

## Limits

- The first Gradle build downloads dependencies and can take 5–15 minutes. Later builds are much faster.
- The SDK and builds need about 3–4 GB of storage, and 6 GB of RAM or more helps.
- Building a project on shared storage (Internal storage/Codriod) is slower than on app storage.
- ARM build tools come from a community build of AOSP. If they can't be downloaded, Codriod falls back to Ubuntu's
  older `aapt` package, which can be too old for newer Android Gradle Plugin versions.
