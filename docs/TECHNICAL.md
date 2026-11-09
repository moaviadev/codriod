# Codriod technical notes

## Build and verification

- Android package: `dev.moavia.codriod`; min SDK 21, target SDK 35, compile SDK 36.
- Build uses Gradle 9.2.1, Android Studio JBR/JDK 17, Android NDK 28.2.13676358.
- Build command: `:app:assembleDebug`.
- App JVM tests: 11 passed, covering workspace storage, session lifecycle, and local Git service behavior.
- The local Git service instrumentation test ran on a OnePlus LE2117 with Android 14 and passed (`OK (1 test)`).
- The PTY instrumentation test was also previously run on that device and passed (`OK (1 test)`).
- The app was installed and launched on device after the Git integration build. The Git UI has not yet had a separate hands-on interaction pass. GitHub remotes and SSH are not implemented.

## Architecture

- `app`: Jetpack Compose workspace, local Git integration, and Android lifecycle.
- `terminal-emulator`: Termux terminal emulator and native PTY JNI.
- `terminal-view`: Termux Android terminal view hosted in Compose through `AndroidView`.
- `ProjectStore`: app-private project files, path validation, atomic text writes, and bounded text search.
- `ProjectGitService`: JGit operations against the local project directory.
- `TerminalExecutionBackend`: separate local-shell and future SSH execution abstraction.

## Workspace behavior

- Projects live under the app-private `files/workspaces` directory.
- Editor supports tabs, autosave, undo/redo, file operations, search, and local HTML preview.
- The preview serves only project-owned files, blocks other hosts and local device paths, and exposes no JavaScript bridge to the app.
- Terminal runs Android's `/system/bin/sh` through a PTY. It is not a Linux distribution and does not provide Bash, Node.js, Python, Java, or package managers.
- Git supports local repository initialization, status, staged/unstaged text diffs, individual and all-file stage/unstage, commits, history, local branch creation, and branch checkout.
- Branch checkout requires a clean worktree to avoid overwriting changes. App metadata is excluded from commits and `.git` internals are hidden from the project explorer.
- GitHub login, push/pull, remote credentials, merging/conflict resolution, and SSH transport are not part of this phase.

## Android restrictions and runtime

The app does not install Linux executables, bootstrap a package distribution, or execute downloaded code. It uses its bundled native PTY libraries and the device's existing Android shell. `/dev/ptmx`, SELinux, background process limits, and OEM restrictions can vary by device. CLI compatibility beyond the Android shell is not claimed.

## Licenses

Termux-derived source attribution and licenses are preserved in `LICENSES-TERMUX.md`. JGit's Eclipse Distribution License 1.0 notice is in `LICENSES-THIRD-PARTY.md`.
