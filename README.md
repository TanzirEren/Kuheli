# Kuheli

A small, quiet Android chat app for any OpenAI-compatible AI provider, with Claude Code style
**skills** and optional reverse-engineering tools from **REA**.

Java + XML (interface) · Python (provider routing and the tool loop, via Chaquopy) ·
C++ (native layer, optional embedded Node.js) · Node.js (the REA bridge).

## Build it with GitHub Actions

1. Create a new GitHub repository and push this folder to it (the `main` branch).
2. Open the **Actions** tab. The workflow **Build Kuheli APK** runs on every push.
   You can also start it by hand with **Run workflow**.
3. When it finishes, open the run and download the **kuheli-apk** artifact. Unzip it and install
   `app-debug.apk` on your phone (allow installs from your browser or file manager).

The workflow first runs a self-test of the Python core and the Node bridge, then builds.
For a signed release APK, add these repository secrets: `KULI_KEYSTORE_BASE64` (your keystore,
base64 encoded), `KULI_KEYSTORE_PASSWORD`, `KULI_KEY_ALIAS`, `KULI_KEY_PASSWORD`.

## First run

1. Open **Providers**, pick one (Google Gemini, OpenRouter, Groq, OpenAI, OmniRoute, or Custom),
   paste your API key, tap **Fetch models** and choose a model, then **Save**.
   Default model names are only starting points, so always check them with Fetch models.
2. Go back and chat. Long-press a message for copy, edit, regenerate, delete.
3. Type `/` to see commands and skills.

ChatGPT subscriptions have no API. Use an OpenAI **API key** for OpenAI models.

## Features

Chat: streaming, Markdown (tables, code, task lists), edit and resend, regenerate, stop, select text,
share, text file attachments with a native SHA-256, slash commands, per-chat model and instructions,
token estimate, scroll-to-latest, send on Enter.
History: search, pinned, archived, rename, delete, save as Markdown, share.
Skills: SKILL.md files, enable or disable, write, import from file or URL, call with `/name`,
and the model can load them itself.
Providers: any number, per-provider headers, connection test, model list, default provider,
automatic fallback to the next provider, keys encrypted with the Android Keystore.
REA tools: bridge status, tool list with filter, tool filter and caps, approval before every tool call.
Settings: theme, text size (12 by default), haptics, keep awake, system prompt, temperature, max tokens,
streaming, auto titles, app lock (phone screen lock), block screenshots, backup and restore, erase data.

## REA, honestly

REA is an independent open source project. Use only the official sources: github.com/morluto/rea,
rea.tools, or the `rea-agents` npm package. Look-alike repositories have carried malware.

* REA needs a recent Node.js (22.23.4 or newer, 24.22.1+, or 26.12.1+), and, for native binaries,
  an analysis tool such as Ghidra, Hopper, IDA, Kuna, Rizin or objdump. **Ghidra and IDA do not run on
  Android.** Rizin and objdump are the realistic phone options (in Termux: `pkg install rizin`).
* The app talks to REA through `bridge.js`, which launches `npx -y rea-agents@latest mcp` and exposes it
  on `http://127.0.0.1:8765`. Which analysis tool REA uses is controlled with environment variables in
  **Settings > REA environment** (see REA's own installation guide for the variable names).
* **Embedded Node.js is experimental and off by default.** To try it, start the workflow by hand (**Run workflow**) and tick *Try to embed the nodejs-mobile runtime*; CI then downloads the latest `nodejs-mobile` Android build.
  That build may be older than REA requires, and mobile Node cannot always start child processes.
  If so, the REA tools page says why, and you can run the same `bridge.js` in **Termux** instead
  (the page has the steps and a Save bridge.js button). Chat and skills work without any of this.
* Tool calls ask for your approval by default. REA is not a sandbox. Analyse only software you own
  or are authorised to examine, and avoid opening untrusted files on a phone you care about.

## What was and was not tested

Tested here: the Python core (streaming, tool-call loop, approval, fallback, errors, skills) and the
Node bridge, end to end against mock servers; the C++ SHA-256 against `sha256sum`; the SKILL.md parser;
all XML is well formed and every resource reference resolves.

**Not tested: the Android build itself and the on-device UI.** The Android SDK could not be installed in the
environment where this was written, so `gradle assembleDebug` has not been run. If the first Actions run
fails, open the failed step and send the error text. Fixes are normally one line.

## Project layout

```
app/src/main/java/com/kuheli/app/   Java: ui/, core/ (engine, Node service, JNI, crypto), data/, util/
app/src/main/python/kuheli_core.py  Python: providers, streaming, tool loop, MCP client
app/src/main/cpp/                   C++: JNI, SHA-256, optional embedded Node
app/src/main/assets/nodejs-project/ Node: bridge.js
app/src/main/assets/skills/         Example skills
scripts/                            selftest.py, fake_mcp.js, fetch_nodejs_mobile.sh
```
