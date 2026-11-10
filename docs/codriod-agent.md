# Codriod Agent

Codriod Agent is the built-in AI chat and coding agent. It is the first row in
**Add-ons** (bottom bar → Add-ons → Codriod Agent). It has three tabs:

- **Chat:** one conversation per project, saved on the phone.
- **Providers:** API keys, server URLs and model lists.
- **Settings:** agent behaviour.

## Bring your own keys

Codriod ships with no API keys and has no account or server of its own. Each user
enters their own key for each provider they use. Every provider row has a *Get key*
link to that provider's own key page.

- Keys are stored in this app's private storage on the phone (the `agent`
  SharedPreferences, not encrypted, as decided for this app). They are excluded
  from Android backups.
- A key is only sent to the provider it belongs to.
- *Fetch models* loads the live model list for your key from the provider's API.
  Before that, a short suggested list is shown. Any model ID can also be typed
  manually. OpenRouter models that cost nothing are marked **free**.

## Providers

| Group | Providers |
| --- | --- |
| Free / free tier | Google Gemini, Groq, OpenRouter, Cerebras, Mistral, GitHub Models, Hugging Face, NVIDIA NIM, SambaNova, Cohere |
| Pro (paid) | OpenAI, Anthropic Claude, DeepSeek, xAI Grok, Together AI, Fireworks AI, Perplexity |
| Local & custom | Ollama, LM Studio (localhost), any OpenAI-compatible server (Custom) |

Anthropic uses its native Messages API. All other providers use their
OpenAI-compatible `/models` and `/chat/completions` endpoints (`agent/Providers.kt`).
Perplexity has no tool calling, so it works in chat-only mode. Plain `http://` is
allowed only for `localhost` and `127.0.0.1` (`res/xml/network_security_config.xml`);
any other server needs HTTPS.

## Modes and tools

- **Ask:** the agent can only read: `list_files`, `read_file`, `search_files`, and
  Git status/verify/test.
- **Agent:** it can also use `write_file`, `edit_file`, `delete_file`,
  `run_command`, and the Git actions.

The project's file tree, the open file (if *Open file* is on), `AGENTS.md` or
`CODRIOD.md`, and your custom instructions are sent as context.

## Approvals

- **File changes** show a line diff with *Apply* or *Reject*. *Settings → Apply file
  changes without asking* turns this off.
- **Deleting a file** always asks.
- **Shell commands** run in the project folder with Android's `/system/bin/sh`,
  with a 120-second limit and capped output. Each asks *Run once*, *Always
  (project)* or *Deny*. Settings can reset the "always" choice.
- **Git actions** (start GitHub login, configure, pull, push) always ask. See
  [GitHub login](github-login.md). Git credentials are never given to the agent.

*Stop* cancels the current request. *Max steps* (default 25) limits how many tool
rounds one request can take.

## Code map

| File | Role |
| --- | --- |
| `agent/Providers.kt` | Provider catalogue |
| `agent/LlmClient.kt` | HTTP client, OpenAI and Anthropic wire formats, model list parsing |
| `agent/AgentTools.kt`, `agent/GitAccountTools.kt` | Tools and approval requests |
| `agent/AgentEngine.kt` | Agent loop, system prompt, chat JSON, shell runner |
| `agent/AgentController.kt` | Settings, keys, chat state, project workspace adapter |
| `agent/AgentScreens.kt` | Add-ons screen and the Agent's Chat, Providers and Settings tabs |
| `agent/LineDiff.kt` | Diffs shown in approval cards |

## Not yet

- Answers are not streamed. The full reply appears when the model finishes.
- Images and file attachments are not supported.
- There is no voice input.
- Running Python, Node.js or Java needs the runtime add-ons that are listed as
  *Coming soon*.
