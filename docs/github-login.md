# GitHub in Codriod

Each person connects their own GitHub account with their own access token, sees all
of their repositories, clones them with one tap, and pulls or pushes later. Codriod
has no GitHub app, account or server of its own.

## How it works

1. **Open GitHub.** Use the *GitHub* button on Home, the *GitHub* row in Add-ons, or
   *Connect* in Source Control → Remote.
2. **Create a token.** *Open GitHub token page* opens GitHub's own "New personal
   access token" page with the name "Codriod" and the `repo` scope already filled
   in. The person chooses the expiry, presses *Generate token* and copies it.
3. **Paste and connect.** Codriod checks the token with GitHub (`GET /user`), shows
   "@username" and saves the token encrypted on the phone.
4. **Your repositories.** The screen lists every repository the token can see:
   your own, ones you collaborate on, and your organizations' (`GET /user/repos`).
   Each one shows Public or Private and has a search box. *Clone* creates a project
   from the repository and opens it. *URL* clones any other address.
5. **Pull and push.** In Source Control → Remote, *Pull* and *Push* work with the
   saved token. Commit in the Changes tab first. A `git@github.com:` address is
   changed to its `https://github.com/` form automatically, so the token is used.

*Disconnect* removes the token from the phone. Cloned projects stay. The token can
also be deleted at `https://github.com/settings/tokens`.

## Security

- The token is checked with GitHub before it is saved.
- It is stored only on the phone, sealed with an Android Keystore key, and excluded
  from Android backups.
- It is sent only to `github.com` and `api.github.com`, and never shown again.
- The coding agent never receives the token. The agent is told never to ask for
  tokens in the chat; it can only open the GitHub screen for the person.
- Remote URLs that contain a password or token are rejected.

## Coding agent

"Configure GitHub login for my account" works through these tools
(`agent/GitAccountTools.kt`):

| Tool | What it does | Approval |
| --- | --- | --- |
| `git_account_status` | Reports whether GitHub is connected and as whom, plus this project's origin and author | none |
| `github_login_start` | Opens the GitHub screen and GitHub's token page; the person creates and pastes the token themselves | required |
| `github_login_verify` | Re-checks the saved token and reports the username | none |
| `git_configure` | Sets author name/email and origin | required |
| `git_test_access` | Lists remote branches without changing anything | none |
| `git_sync` | Fetch (no approval), pull or push (approval) | pull/push |

## Notes

- Git runs on Codriod's built-in engine (JGit). Stock Android has no `git` command,
  so Git is used from the app's screens rather than the terminal.
- In practice Git features need Android 8.0 or newer, because JGit uses Java APIs
  that older Android versions lack.
- Merge conflicts during pull are reported but not resolved in the app. There is no
  force push.
- SSH remote support (`git/SshIdentityStore.kt`, `git/HostKeys.kt`) stays in the
  code for non-GitHub servers. It is no longer part of the GitHub screens.
