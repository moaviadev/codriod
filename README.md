# Codriod Android Workspace Prototype

Codriod is an initial Android local-terminal prototype for package
`dev.moavia.codriod`.

## Product idea (reference)

The original [Moavia Code Studio / Codriod build plan](docs/moavia-code-studio-build-plan.md)
is saved unchanged as a product vision and planning reference. Its embedded
prompts are reference material, not instructions to start implementation.

The plan covers a mobile IDE with projects, an editor, terminal, preview, AI
agents, extensions, and local/remote workspaces. Most of that vision is not yet
implemented here. This repository already has a native Kotlin/Compose terminal
prototype; the plan's React web prototype is a separate proposed stage.

The native Projects, editor, local shell, preview, local Git tools and VS Code-inspired
dark/light theme are described in [workspace implementation notes](docs/workspace-implementation.md).
The strict UI architecture and design system rules are defined in [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).

## Build

Requirements:

- Android Studio/JDK 17 or compatible JDK 17
- Android SDK Platform 36
- Android NDK installed (project uses 28.2.13676358)
- A device or emulator with ABI `armeabi-v7a`, `arm64-v8a`, `x86`, or `x86_64`

Build with:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

The APK is emitted at `app\build\outputs\apk\debug\app-debug.apk`.

## Architecture

- `app`: Jetpack Compose UI and Android integration
- `terminal-emulator`: vendored Termux terminal emulator and `/dev/ptmx` PTY JNI
- `terminal-view`: vendored Termux Android terminal view
- `ProjectGitService`: local Git operations using Eclipse JGit; it stores each
  repository in the project's private workspace
- `git/`: user-owned Git remote access (`GitRemoteService`, `SshIdentityStore`,
  `CodriodHostKeys`): clone, fetch, pull and push over SSH with a key the user
  creates on the phone; see [GitHub login](docs/github-login.md)
- `TerminalExecutionBackend`: separate execution abstraction; `LocalShellBackend`
  is implemented and `SshBackend` is intentionally unsupported
- `SessionManager`: session creation, resize, input, termination, and cleanup

The workspace Git panel initializes a repository, reviews staged/unstaged changes,
previews text diffs, stages and unstages files, records commits, lists history, and
creates or checks out local branches. Branch checkout requires a clean working tree.
Its Remote tab signs in to the user's own GitHub account with an SSH key, sets the
origin remote, and fetches, pulls and pushes; Home can clone a repository. Codriod has
no GitHub OAuth app and never asks for passwords or tokens. Merge conflict resolution
is not implemented yet.

The local shell is `/system/bin/sh` with a pseudo-terminal, not an embedded
Linux distribution or Termux package bootstrap. Basic Android shell commands
are available according to the device firmware.

## License and attribution

The Termux repositories are GPLv3. Their license text is preserved in
`LICENSES-TERMUX.md`; the emulator/view modules are identified by Termux as
derived exceptions from Apache-licensed Terminal Emulator for Android code.
This project does not copy Termux application binaries or bootstrap packages.
JGit's Eclipse Distribution License attribution is in [LICENSES-THIRD-PARTY.md](LICENSES-THIRD-PARTY.md).
Review upstream source headers and license notices before redistribution.

The source directories under `terminal-view` and `terminal-emulator` remain
modular integrations of the upstream Termux libraries. Termux repository:
https://github.com/termux/termux-app

## Supported platforms and restrictions

- Prototype configuration: min SDK 21, target SDK 35, compile SDK 36.
- Native libraries are built for `armeabi-v7a`, `arm64-v8a`, `x86`, and
  `x86_64`; the APK must be built with the NDK ABI configuration intact.
- The app uses `/dev/ptmx` and a native fork/exec PTY implementation from the
  Termux terminal-emulator source. Android's process and executable policy is
  applied normally; this prototype does not install Linux executables or
  modify package signatures.
- SELinux, scoped storage, device-specific `/system/bin` availability, Android
  background-process limits, and OEM restrictions can vary by device.
- `/system/bin/sh` and its basic utilities are expected on Android, but a
  distribution-style CLI, package manager, or GNU toolchain is not provided or
  claimed. Device-specific behavior remains unverified.

## Downloadable runtime / Google Play considerations

No runtime download, package bootstrap, dynamic executable extraction, or
`exec` of downloaded binaries is implemented. Distribution of third-party
executables through an app-store package requires review of Google Play
program policies and current developer guidance, including executable-code
rules and restricted/permission-sensitive behavior. Devices may also disable
native execution or dynamic loading for sideloaded or policy-managed apps.
This prototype intentionally uses only the APK's native libraries and the
device's existing `/system/bin/sh`.

## Verification

Automated verification includes:

- JVM lifecycle tests for session state, positive resize dimensions,
  idempotent termination, and finish state.
- Android instrumentation `TerminalPtyInstrumentedTest` for PTY creation, shell
  input, resize updates, SIGKILL termination, and process-exit notification.

- JVM `ProjectGitServiceTest` for repository initialization, metadata exclusion,
  stage/unstage, commit history, branch creation/checkout, diffs, and dirty-tree
  checkout protection.

JGit repository operations also have a device instrumentation test. JVM tests in
`app/src/test/.../git` cover SSH keys, pinned GitHub host keys, and clone/push/pull
over a real local SSH server. The Git UI has not yet had a separate hands-on
interaction pass on a device.
