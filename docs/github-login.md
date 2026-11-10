# GitHub login in Codriod

Codriod provides the environment. It is not a GitHub account provider. Each person
signs in to their own GitHub account and controls their own repositories.

## What Codriod does not do

- It has no Codriod GitHub OAuth app, client ID or client secret.
- It never asks for GitHub passwords or personal access tokens, and rejects
  remote URLs that contain them.
- It has no server and no central authentication service. Nothing about a user's
  Git login leaves their phone except the normal SSH handshake with their Git host.

## How login works

1. **Create a key.** The Remote tab in Source Control (or the Clone dialog) has a
   *Set up GitHub login* button. It creates an ECDSA P-256 SSH key on the phone.
2. **Add it on GitHub.** Codriod copies the *public* key and opens GitHub's own
   page, `https://github.com/settings/ssh/new`. The user signs in to github.com
   (including GitHub's own password, passkey or 2FA prompts), pastes the key and
   approves it. Codriod sees none of this.
3. **Verify.** *Verify* runs the equivalent of `ssh -T git@github.com`. GitHub
   answers with the account name ("Signed in as @user"), which Codriod shows. It
   does not read anything else.
4. **Use it.** Clone, fetch, pull and push use that key. A repository can use a
   different key: several keys can be kept for several accounts, with one as the
   default. The choice is stored in the repository's own `.git/config` as
   `codriod.sshIdentity`.

Removing a key in Codriod deletes it from the phone. The user should also delete it
at `https://github.com/settings/keys`.

## Security properties

- **Private keys stay on the device.** They are sealed with a non-exportable AES-GCM
  key in Android Keystore (Android 6+). On Android 5.x, or when a device's Keystore
  is broken, they are stored only in app-private storage. They are excluded from
  Android backups and device transfer (`res/xml/backup_rules.xml`,
  `res/xml/data_extraction_rules.xml`).
- **Isolation between users and accounts.** App-private storage is separate for
  each Android user and profile. Each key is a separate identity.
- **Pinned GitHub host keys.** GitHub's published host keys are built in
  (`CodriodHostKeys.GITHUB`), so a man-in-the-middle cannot pose as github.com and a
  changed key is always refused. Any other Git server must be confirmed once by its
  fingerprint (*Trust this server?*) before Codriod connects.
- **No secrets for the agent.** The UI, the coding agent and logs only ever receive
  public keys, fingerprints and the verified account name.

## HTTPS remotes

Public repositories clone and fetch anonymously over HTTPS. Private repositories,
and pushing, need a login. Over HTTPS that would mean a password, a token or an
OAuth app. Codriod deliberately has none of these. Instead, it offers to switch a
`https://github.com/owner/repo` origin to `git@github.com:owner/repo.git`, which
uses the user's SSH key.

## Android compatibility decisions

- **Git engine.** Stock Android has no `git`, `gh` or `ssh` binary. Codriod's
  terminal is `/system/bin/sh`, not a Linux distribution. Codriod already uses
  Eclipse JGit, so it does not install a second Git engine. Remote operations use
  JGit's JSch transport (`org.eclipse.jgit.ssh.jsch`).
- **SSH library.** The transport runs on the maintained JSch fork
  (`com.github.mwiede:jsch`), which supports the `rsa-sha2`, ECDSA and `curve25519`
  algorithms GitHub requires. It removes algorithms the device's crypto provider
  lacks. JGit's Apache MINA transport was not used because it brings a much larger
  dependency set and reads `~/.ssh` configuration, which Codriod does not want.
- **Android versions.** JGit 7 relies on Java 8+ APIs such as `java.nio.file` and
  `java.time`, which Android provides from 8.0 (API 26). Git features, local and
  remote, should therefore be treated as Android 8.0+ even though the app's
  `minSdk` is 21.
- **Key type.** ECDSA P-256 was chosen because every supported Android version can
  generate it and sign with it, and GitHub accepts it. Ed25519 needs Android 13+.
- **Tool detection.** `GitRemoteService.detectTools()` looks for `git`, `gh` and
  `ssh` on `PATH` and reports them. Codriod does not need them.
- **GitHub CLI device login.** This was not used. It would mean signing in through
  another product's OAuth app, which this design rules out.

## Coding agent

The agent toolset (`agent/GitAccountTools.kt`) handles "Configure GitHub login for
my account":

| Tool | What it does | Approval |
| --- | --- | --- |
| `git_account_status` | Reports available tools, login method, key storage, public fingerprints, the verified account, origin and author | none (read-only) |
| `github_login_start` | Creates a key if needed, copies the public key and opens GitHub's Add SSH key page | required |
| `github_login_verify` | Confirms GitHub accepts the key and returns the username | none (read-only) |
| `git_configure` | Sets author name and email, the origin URL, and HTTPS→SSH | required |
| `git_test_access` | Lists remote branches without changing anything | none (read-only) |
| `git_sync` | Fetch (no approval), pull or push (approval) | pull/push |

The person always completes the GitHub sign-in and approval themselves in the
browser. `WorkspaceModel.gitAccountBridge` implements these tools. They become
available as soon as the agent chat UI is connected.

## Limitations

- Merge conflicts during pull are reported but not resolved in the app.
- There is no force push.
- HTTPS login for private repositories is not provided (see above).
