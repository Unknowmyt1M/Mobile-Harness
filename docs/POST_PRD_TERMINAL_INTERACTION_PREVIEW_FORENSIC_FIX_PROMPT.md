# Mobile Harness — Post-PRD Forensic Fix Prompt

## Status

**Implementation-ready.** This document is the implementation prompt for the next coding-agent pass after the Universal Model Provider / native agent runtime / PTY / permission-question work.

## Baseline — MUST use the latest commit

The repository has already implemented the Universal Model Provider and native model-agnostic agent runtime work. **Do not re-implement, redesign, or regress that system.**

Current `main` baseline at the time this prompt was written:

- Repository: `Unknowmyt1M/Mobile-Harness`
- Latest commit: `ed0f9fe894cb801e7b89128a57504ce25c25227c`
- Latest commit message: `feat: universal model provider, native streaming agent runtime, terminal engine, and permission/question managers`
- Parent commit: `2128f42e5549946066b66e9cfc02a5663f41df56`

**FIRST ACTION:** pull/read the current `main` and inspect the actual current implementation before changing anything. The SHA above is the known baseline, but if `main` has advanced, use the newer `main` as the authoritative source and explicitly audit the delta first.

The current implementation already contains substantial work for:

- Universal model/provider abstraction
- Native OpenAI Responses / OpenAI-compatible execution
- Claude Code runtime compatibility
- Native PTY terminal engine
- Terminal sessions/tabs
- Question manager
- Permission manager
- Agent task persistence
- Tool execution/policy infrastructure

The remaining task is **forensic debugging and completion of the user-visible behavior**, not another architecture rewrite.

---

# 1. Mission

Fix the following three groups of production issues completely:

1. **Agent Question + Permission requests are visible to the agent but do not produce a proper user-facing popup/dialog.**
2. **Terminal is only partially functional despite the new PTY implementation.**
3. **Preview is unnecessarily localhost-only and does not correctly support normal public web URLs/assets.**

The implementation must be robust enough for real Android device usage, not merely satisfy mocked unit tests.

Do not stop after making the UI look correct. Trace each problem from:

`Agent/Terminal/WebView event -> state -> ViewModel/service -> Compose UI/native layer -> actual user interaction -> response back to runtime`

and verify the complete round trip.

---

# 2. NON-GOALS / DO NOT REGRESS

## 2.1 Do NOT redo Universal Model Provider

The Universal Model Provider implementation is already present. Do not replace it with an OmniRoute-specific implementation or hardcode any particular gateway.

OmniRoute/OpenRouter/vLLM/Ollama/Kimi/etc. are only examples of compatible endpoints. The provider/runtime architecture must remain generic.

Do not change the semantics of:

- arbitrary provider/model IDs
- OpenAI Responses
- OpenAI Chat Completions
- generic OpenAI-compatible endpoints
- Claude Code runtime
- provider discovery/validation
- secure API-key storage
- normalized model/tool events

unless a directly related bug is discovered and proven to be part of one of the three target issues.

## 2.2 Do NOT replace the terminal with a heavyweight terminal app

The intended terminal is a **lightweight Termux-like terminal experience**, not a full Termux clone.

Keep:

- native PTY
- Android/Compose UI
- project-scoped Ubuntu PRoot shell
- multiple terminal tabs/sessions
- service-owned/persistent sessions where already implemented
- ANSI/VT-capable rendering
- compact extra-key tray

Do not add unnecessary shell-selection UI. The project/default shell remains the shell used by the terminal.

## 2.3 Preview is still only the project preview surface

Do not turn Preview into a general-purpose browser.

It should, however, correctly load:

- localhost/loopback development servers
- public HTTP URLs
- public HTTPS URLs
- normal external CSS
- JavaScript
- fonts
- images
- SVGs
- icons
- public CDN assets
- same-origin/subresource requests
- common redirects
- fetch/XHR requests required by the preview page

---

# 3. REQUIRED FIRST STEP — FORENSIC AUDIT

Before editing code, inspect the current implementation on the latest `main`.

At minimum inspect:

### Agent interaction

- `AgentQuestion`
- `AgentAnswer`
- `QuestionManager`
- `QuestionPolicyEngine` / question policy classes
- `PermissionManager`
- `PermissionDecision`
- `ToolRequest`
- `AgentTaskStore`
- `AgentOrchestrator`
- `NativeModelAgentRuntime`
- `ClaudeRuntimeBridge`
- `MainViewModel`
- `PocketDevApp`
- `AgentQuestionCard`
- permission UI/card/dialog/bottom-sheet composables

### Terminal

- `TerminalSessionManager.kt`
- `TerminalSession.kt`
- `PtyProcess.kt`
- `NativePty`
- `pocket_spawn.c`
- terminal composables in `PocketDevApp.kt`
- terminal-related methods in `MainViewModel.kt`
- terminal state models
- terminal input/keyboard/extra-key handling
- project-terminal lifecycle

### Preview

- Preview composable(s)
- WebView construction/configuration
- `WebViewClient`
- `WebChromeClient`
- URL validation/allow-list helpers
- localhost detection
- external navigation handling
- Android manifest/network-security configuration
- cleartext configuration if present
- WebView settings
- JavaScript/storage/DOM settings
- file/content access settings
- error callbacks
- preview URL state and persistence

Search the actual latest code rather than assuming names or behavior from this document.

For every reported symptom, identify the exact root cause before implementing the fix. If multiple bugs contribute to one symptom, fix all of them.

---

# 4. ISSUE A — QUESTION AND PERMISSION POPUPS DO NOT APPEAR

## 4.1 Observed behavior

The agent appears to know that `ask_question` and `request_permission` exist and can emit/request them, but the user does not get a proper popup/dialog when interaction is required.

This is a critical usability bug because the agent can become logically blocked while the UI gives no obvious way to unblock it.

## 4.2 Required behavior

When the active agent requests a question:

1. The request must enter durable application state.
2. The active project must observe that state.
3. The Compose UI must detect that a user-required question is pending.
4. A prominent user-facing interaction surface must appear automatically.
5. The popup must remain visible until the user answers or the request is otherwise resolved by policy.
6. Answering must update the durable state.
7. The answer must reach the correct active runtime/session/task.
8. The agent must resume exactly once.
9. Recomposition, rotation, Activity recreation, tab changes, or background/foreground transitions must not duplicate the request or lose it.

For permission requests:

1. The request must enter durable permission state.
2. Policy must be evaluated.
3. Safe requests may be auto-allowed according to the existing policy.
4. Requests requiring user review must surface an explicit popup/dialog.
5. The popup must clearly identify what capability/action is being requested.
6. The user must be able to choose the existing supported decisions:
   - Allow once
   - Allow for this project
   - Always allow
   - Deny once
7. The selected decision must reach the permission manager and active runtime.
8. The blocked tool call must resume only after the decision is actually committed.

Do not silently auto-approve a request that the policy says requires user interaction.

## 4.3 Critical lifecycle audit

Trace both paths end-to-end:

```text
MODEL
  -> normalized tool call
  -> ToolRegistry
  -> AgentPolicyEngine
  -> QuestionManager / PermissionManager
  -> durable state
  -> MainViewModel StateFlow
  -> PocketDevApp
  -> visible popup
  -> user action
  -> ViewModel callback
  -> Manager resolution
  -> AgentRuntime continuation
  -> next model turn
```

Find exactly where the chain currently breaks.

Potential failure classes to investigate include, but are not limited to:

- pending request stored in a manager but not exposed through ViewModel state
- state exposed but not collected by Compose
- popup composable exists but is never mounted
- popup mounted only on a different screen/tab
- request filtered because `activeProject`/`activeTask` IDs do not match
- stale request IDs
- request status changed before UI observes it
- a one-shot event was emitted through a non-replayable flow and lost
- dialog state is derived incorrectly from an empty/nullable list
- multiple managers hold different copies of the same request
- task recovery loads the request but UI ignores recovered WAITING state
- permission request is converted to a generic tool request and no UI handler recognizes it
- callback reaches ViewModel but not the runtime
- runtime resumes before state persistence completes

Do not patch only the final Compose layer if the actual bug is upstream.

## 4.4 Popup UX requirements

Use the existing application visual language. Do not redesign the entire chat UI.

Question popup should show at minimum:

- question text
- question type
- options when applicable
- text/number/path input when applicable
- selected values
- submit/confirm action
- optional countdown only when the existing policy says the question is auto-resolvable
- clear indication if user input is required

Permission popup should show:

- requested capability/category
- concise explanation of why it is needed
- relevant target/path/operation when safe to display
- risk level
- the four supported decisions
- clear Deny action

Do not display raw secrets, API keys, authorization headers, or sensitive environment values.

## 4.5 Popup must survive navigation

If a pending question/permission belongs to the active task, switching between Chat/Files/Terminal/Preview must not permanently hide it.

Preferred behavior:

- the pending interaction remains globally associated with the task/project
- if the user navigates away, a visible pending-interaction indicator remains available
- returning to Chat restores the popup
- if appropriate, the popup can remain modal while the user is expected to answer

Do not create a situation where the agent is waiting indefinitely with no visible recovery path.

## 4.6 Background/recreation behavior

The interaction state must survive:

- recomposition
- Activity recreation
- process/service lifecycle transitions where existing task persistence promises recovery

If the app is reopened while a task is waiting for user input, restore the pending interaction and display it.

Do not use a transient `mutableStateOf` as the only source of truth for durable agent questions/permissions.

## 4.7 Acceptance tests

### Question

Create a test agent/task that calls:

```text
ask_question
```

Verify:

- popup appears without manual refresh
- correct question is shown
- answer button works
- answer persists
- runtime receives answer
- agent continues
- popup disappears only after successful resolution

### Permission

Create a task that requests a REVIEW/HIGH_RISK capability.

Verify:

- popup appears
- Allow Once works
- Allow for Project works and affects subsequent matching requests
- Always Allow works according to policy
- Deny Once blocks the current operation
- denied operation does not execute
- runtime continues correctly after denial

### Recovery

Create a waiting interaction, kill/recreate Activity, reopen project, and verify the interaction remains actionable.

---

# 5. ISSUE B — TERMINAL IS NOT ACTUALLY INTERACTIVE/REAL-TIME

The new native PTY implementation exists, but the user-visible terminal still behaves incorrectly.

The screenshots show these concrete problems:

1. `+` tab button is decorative/non-functional.
2. Command output is not visible in real time; output/logs appear only after stopping the command.
3. Typing a command such as `clear` and pressing Enter does not execute correctly.
4. The terminal appears to echo/pre-fill the command on the next line rather than behaving like a real shell.
5. Terminal viewport becomes too small when Android keyboard opens.
6. The terminal must remain genuinely interactive, not a fake command-output console.

## 5.1 Core terminal invariant

Treat the terminal as a real bidirectional PTY:

```text
Android keyboard / extra keys
        ↓
UTF-8 bytes
        ↓
PTY master
        ↓
interactive shell
        ↓
PTY master
        ↓
incremental bytes
        ↓
terminal emulator / renderer
        ↓
Compose UI
```

Never implement Enter by merely adding a command to an application-side log.

Never implement output by waiting for process completion.

The PTY stream must be continuous while the process is alive.

## 5.2 Investigate the current native PTY implementation carefully

The latest commit adds native PTY creation using `/dev/ptmx`, `grantpt`, `unlockpt`, `ptsname_r`, `fork`, `setsid`, `TIOCSCTTY`, and window sizing.

Audit the interaction between:

- native master FD flags
- Java `FileInputStream`
- Java `FileOutputStream`
- coroutine reader
- PTY lifetime
- EOF/EIO handling
- process death detection

Pay special attention to the fact that the current native implementation sets the PTY master FD to `O_NONBLOCK` while `TerminalSession` uses blocking-style Java stream reads.

Do not assume that combination is correct. On Android/Linux, a non-blocking PTY read can return `EAGAIN` when no data is available. The current Kotlin reader must not interpret a temporary no-data condition as permanent EOF/process termination.

Choose one correct architecture:

### Option A — blocking PTY master

Keep the Java input stream blocking and let the dedicated IO coroutine block until bytes arrive or the descriptor closes.

### Option B — explicit non-blocking polling

If retaining `O_NONBLOCK`, use a proper native polling mechanism (`poll`/`select`/equivalent) and only report actual EOF/process termination as terminal closure.

Do not use `runCatching { read() }.getOrDefault(-1)` in a way that converts transient IO errors into EOF.

## 5.3 PTY FD ownership must be correct

Audit `ParcelFileDescriptor.adoptFd()` and the creation of both input/output streams from the same descriptor.

There must be exactly one well-defined owner/lifecycle for the master FD.

Avoid accidental double-close or use-after-close behavior from independently closing `FileInputStream`, `FileOutputStream`, and the native FD.

Recommended outcome:

- one lifecycle owner for the master descriptor
- separate Java input/output wrappers that do not unexpectedly destroy each other
- native close only after stream operations have stopped
- idempotent shutdown

Test repeated create/write/close cycles.

## 5.4 Shell launch must be a real interactive shell

The terminal session must launch a project shell suitable for interactive use.

Required examples:

```text
clear
pwd
ls
cd some-dir
python3
node
python3 -c "print('hello')"
cat
less
nano
vim
htop/top (when installed)
```

Interactive commands must receive stdin from the terminal and write stdout/stderr to the same PTY.

The shell should remain alive after a normal command completes.

The user should see a normal prompt such as:

```text
root@pocket:~/project#
```

or the project's configured prompt.

## 5.5 Enter key path

Trace the Android IME path completely.

Required:

```text
keyboard Enter
 -> terminal input callback
 -> '\r' / correct terminal newline byte sequence
 -> PTY master write
 -> shell stdin
 -> command execution
 -> shell output/prompt
```

Do not accidentally:

- update only a Compose text field
- insert the command into the rendered buffer without sending it
- send `"\n"` to a line editor that requires `\r` without verifying behavior
- maintain a second fake command buffer that fights the PTY

The visible cursor/input must be terminal-emulator state, not an ordinary Compose `TextField` masquerading as a terminal.

## 5.6 `clear` behavior

`clear` is an important diagnostic because it produces terminal control sequences rather than merely printing text.

The terminal renderer must preserve and interpret ANSI/VT control sequences instead of stripping them.

Verify:

```text
clear
```

actually clears the terminal viewport and leaves the shell prompt usable.

If a terminal emulator/parser exists, route PTY bytes through it. Do not concatenate raw strings and expect terminal applications to behave correctly.

## 5.7 Real-time output

The reader must emit incremental chunks as soon as they arrive.

For example:

```bash
python3 -c 'import time; [print(i, flush=True) or time.sleep(1) for i in range(5)]'
```

Expected UI behavior:

```text
0
(wait ~1s)
1
(wait ~1s)
2
...
```

Do NOT show all five lines only after the process exits.

Use a streaming event/channel/StateFlow architecture that does not require process completion.

Avoid excessive full-string copying on every byte/chunk if it causes lag. Keep scrollback bounded as already intended.

## 5.8 Terminal output and scrollback

Maintain bounded scrollback.

Do not repeatedly create enormous immutable strings with:

```text
oldBuffer + chunk
```

for every small chunk if that becomes a performance bottleneck.

A bounded buffer model is preferred. The exact implementation is up to the agent after profiling, but correctness comes first.

The UI should:

- auto-follow the bottom while the user is at the bottom
- avoid forcibly jumping to bottom if the user is scrolling old output
- allow selection/copy if already supported

## 5.9 `+` tab button

The `+` button must actually call `TerminalSessionManager.createSession(...)` through the proper ViewModel/service path.

Required behavior:

1. Tap `+`.
2. New terminal session is created.
3. New PTY shell starts.
4. New tab appears immediately.
5. New tab becomes active.
6. Old session remains alive.
7. Switching back restores old terminal state.
8. Multiple sessions work independently.

Example:

```text
T1 | +
```

Tap plus:

```text
T1 | T2 | +
```

Tap T1:

```text
T1 | T2 | +
```

T1's process and scrollback must still be there.

Do not merely append a visual tab.

## 5.10 Session close

If a session-close control already exists, verify that closing a tab:

- terminates its PTY/process group
- releases FD resources
- cancels reader jobs
- removes its state
- selects a sensible remaining session
- does not kill unrelated terminal sessions

If all sessions are closed, preserve the existing intended behavior of creating a fresh session.

## 5.11 Terminal resize

The PTY window size must track the actual rendered terminal viewport.

When dimensions change due to:

- keyboard opening
- keyboard closing
- rotation
- device/window resize
- tab switching

call `TIOCSWINSZ` with accurate rows/columns.

Interactive programs such as `vim`, `nano`, `less`, and `top` depend on correct dimensions.

Do not use a hardcoded 24x80 after initial startup.

## 5.12 SIGWINCH

When PTY window size changes, ensure the foreground process receives the expected terminal resize notification through the PTY mechanism.

Verify with an interactive terminal application whose layout visibly responds to resizing.

## 5.13 Extra keys

Keep the requested lightweight two-row layout:

```text
Row 1: ESC  /  -  HOME  ↑  END  PGUP
Row 2: TAB  CTRL  ALT  ←  ↓  →  PGDN
```

Do not reintroduce F1–F12.

Keys must send real terminal bytes/control sequences.

Examples:

- ESC -> `0x1B`
- TAB -> `0x09`
- arrows -> correct VT escape sequences
- HOME/END -> correct terminal sequences
- CTRL modifier -> correct control-byte mapping for printable keys
- ALT modifier -> correct escape-prefix behavior

Do not only change the visible text.

## 5.14 Keyboard-open layout

The screenshot shows the terminal viewport becoming unnecessarily tiny when the Android keyboard opens.

Fix the layout using actual available window/IME insets.

Desired structure:

```text
Top app bar
Project terminal card
Tabs
Terminal viewport  <-- flexible, gets remaining height
Extra keys
Android IME
```

The terminal viewport should use the available remaining height rather than a fixed height that becomes unusably small.

Requirements:

- no content hidden behind the keyboard
- terminal remains large enough to be usable
- extra-key tray stays visible above the keyboard
- terminal scrollback remains accessible
- resizing the PTY follows the actual viewport

Do not blindly stack `imePadding()` on every parent because that can double-count IME height.

Inspect the current Compose hierarchy and apply insets at the correct ownership level.

## 5.15 Terminal performance test

Run:

```bash
python3 -c 'import time; [print("tick", i, flush=True) or time.sleep(0.25) for i in range(20)]'
```

Verify incremental output.

Then run:

```bash
python3
```

and interactively type:

```python
print("hello")
```

Then test:

```bash
clear
pwd
ls
```

Then test an interactive UI program if available:

```bash
nano
```

or

```bash
vim
```

The terminal must not freeze, batch output, or duplicate typed commands.

---

# 6. ISSUE C — PREVIEW MUST SUPPORT PUBLIC URLs AND NORMAL WEB ASSETS

## 6.1 Observed behavior

Preview currently behaves as if only localhost URLs are valid.

This is too restrictive for modern web projects.

A project may use:

- Google Fonts or another font provider
- public CSS
- CDN JavaScript
- public image/logo URLs
- SVG assets
- public API endpoints
- remote icon libraries
- hosted scripts
- external redirects

If the main preview page loads but these subresources are blocked, the preview becomes visually/functionally inaccurate.

## 6.2 Required preview model

Preview should support two classes of URLs:

### Local development URLs

Examples:

```text
http://127.0.0.1:3000
http://localhost:3000
http://127.0.0.1:5173
http://localhost:8000
```

### Public web URLs

Examples:

```text
https://example.com
https://demo.example.com/app
http://example.com
```

The exact ports/hosts are not limited to the examples above.

Do not use a hardcoded localhost-only allow-list.

## 6.3 URL validation policy

Implement a clear preview URL policy instead of simply removing all validation.

Recommended logic:

- allow `http` and `https`
- allow loopback/local development hosts needed by the app
- allow normal public hostnames
- reject dangerous/non-web URI schemes such as `file:`, `content:`, `javascript:`, `data:` as top-level navigations unless there is a specific safe, documented reason
- normalize/validate URLs before loading
- avoid accidentally allowing arbitrary local filesystem access

Preview should not become an unrestricted privileged browser.

## 6.4 WebView configuration audit

Inspect and configure only the capabilities required for a normal modern web preview.

Evaluate:

- JavaScript
- DOM storage
- database/storage APIs as required
- mixed content behavior
- third-party cookies if required by legitimate preview flows
- media playback if already expected
- viewport support
- zoom/scale
- caching
- safe browsing
- WebView debugging only in appropriate debug builds

Do not disable security wholesale just to make one demo work.

## 6.5 External subresources

The main page being allowed is not sufficient.

Verify that normal subresources can load:

```text
HTML
 ├── CSS from public host
 ├── JS from CDN/public host
 ├── fonts from public host
 ├── images from public host
 ├── SVG/icon resources
 └── fetch/XHR to explicitly intended public endpoints
```

Inspect the current `WebViewClient` and any `shouldInterceptRequest` implementation.

If requests are being rejected because the code checks every URL against a localhost-only predicate, correct the policy.

Do not accidentally intercept every remote request and return an empty `WebResourceResponse`.

## 6.6 HTTP vs HTTPS

Android WebView/network security may treat cleartext HTTP differently from HTTPS.

If public **HTTP** URLs are intentionally supported, configure cleartext access deliberately and narrowly enough to avoid unnecessary security regression.

If the app's product requirement only needs public HTTPS plus local HTTP development servers, prefer that safer policy and document the behavior.

Do not silently claim HTTP support while Android blocks it.

Test both:

```text
http://127.0.0.1:<port>
https://public-domain.example
```

and, if product behavior explicitly supports it:

```text
http://public-domain.example
```

## 6.7 Redirects

A public page may redirect:

```text
http://example.com
 -> https://www.example.com
```

or

```text
https://example.com
 -> https://example.com/app
```

Do not break normal HTTP redirects.

The WebView should follow safe HTTP(S) redirects.

## 6.8 JavaScript and fetch/XHR

Test a page where a button executes JavaScript and performs a public HTTP(S) request.

Preview should not appear functional while silently blocking the request.

Capture WebView console/errors in debug builds so failures can be diagnosed without exposing secrets to release logs.

## 6.9 External navigation

Define a predictable policy for links:

- safe HTTP(S) links may remain in Preview when appropriate
- non-web schemes should not be blindly loaded
- potentially external app links can be handled intentionally

Do not break the existing back-navigation behavior.

## 6.10 Preview acceptance test

Use a simple test page containing all of the following:

- local HTML
- public CSS
- public web font
- public image/logo
- public SVG/icon
- JavaScript interaction
- a public HTTPS API call
- a redirect

Verify that all expected resources load.

Then test a normal local dev server and ensure localhost still works.

---

# 7. ARCHITECTURE GUIDELINES

Do not solve these issues by adding increasingly large blocks to `PocketDevApp.kt` or `MainViewModel.kt`.

If the current implementation has the logic in the wrong layer, extract small focused components/services.

Preferred boundaries:

```text
Agent interaction
    AgentOrchestrator
        -> Interaction/Question/Permission managers
        -> durable state
        -> ViewModel
        -> Compose modal UI

Terminal
    TerminalSessionManager
        -> TerminalSession
            -> PtyProcess
                -> NativePty
        -> terminal renderer/state
        -> Compose terminal UI

Preview
    Preview URL policy
        -> WebView configuration
        -> WebViewClient
        -> Preview UI
```

Do not introduce a second competing source of truth.

---

# 8. LOGGING AND DIAGNOSTICS

Add targeted debug diagnostics while fixing the issues.

Useful diagnostics include:

### Agent interaction

```text
question-created id=... task=...
question-presented id=...
question-answered id=...
permission-requested id=... capability=...
permission-presented id=...
permission-resolved id=... decision=...
agent-resumed task=...
```

### Terminal

```text
pty-created pid=... fd=...
pty-read bytes=...
pty-write bytes=...
pty-resize rows=... cols=...
pty-exit pid=...
pty-close pid=...
```

### Preview

```text
preview-load url=...
preview-navigation url=...
preview-resource-error url=... error=...
preview-console level=... message=...
```

Never log:

- API keys
- Authorization headers
- cookies
- raw secrets
- sensitive environment variables
- private credentials

Use debug-only logging where appropriate and keep release logging safe.

---

# 9. TEST MATRIX

## 9.1 Question

| Test | Expected |
|---|---|
| Agent asks SELECT_ONE | Popup appears |
| Agent asks TEXT | Text input appears |
| Agent asks YES_NO | Yes/No UI appears |
| User submits | Agent resumes |
| Activity recreation while waiting | Popup restored |
| Switch workspace tab | Request remains recoverable |
| Auto-resolve question | Only policy-permitted questions auto-resolve |

## 9.2 Permission

| Test | Expected |
|---|---|
| SAFE capability | Auto-allow if policy says so |
| REVIEW capability | Popup appears |
| HIGH_RISK capability | Popup appears / policy enforced |
| Allow Once | Current request executes |
| Allow Project | Matching project requests follow grant |
| Always Allow | Matching future requests follow policy |
| Deny Once | Current operation denied |
| Denied operation | Tool does not execute |
| Activity recreation | Pending request remains actionable |

## 9.3 Terminal

| Test | Expected |
|---|---|
| `echo hello` | Immediate output |
| long-running command | Output streams incrementally |
| `clear` | Screen clears and prompt remains usable |
| `pwd` | Correct project directory |
| `cd dir` | Shell cwd changes |
| `python3` REPL | Interactive input/output works |
| `node` REPL | Interactive input/output works if installed |
| `nano`/`vim` | Full-screen interaction works if installed |
| Ctrl+C | Interrupts foreground command |
| arrows | Shell history/cursor navigation works |
| Tab | Shell completion works |
| `+` | Creates real new session |
| switch T1/T2 | Processes and scrollback remain independent |
| keyboard opens | Terminal remains usable |
| keyboard closes | Terminal expands |
| resize | Interactive apps receive new dimensions |
| close tab | Only that session terminates |

## 9.4 Preview

| Test | Expected |
|---|---|
| localhost HTTP | Loads |
| loopback IP | Loads |
| public HTTPS | Loads |
| public CSS | Loads |
| public font | Loads |
| public image | Loads |
| CDN JS | Loads |
| SVG/icon | Loads |
| public fetch/XHR | Works when endpoint permits it |
| redirect | Safe HTTP(S) redirect works |
| invalid URI scheme | Safely rejected |
| public page with mixed assets | Behavior follows explicit security policy |

---

# 10. AUTOMATED TESTS

Add focused unit/instrumentation tests where practical.

At minimum:

### Interaction

- question state -> UI state mapping
- permission state -> UI state mapping
- answer/decision routing to correct task/session
- recovery of waiting interactions

### Terminal

- PTY creation failure handling
- PTY read/write lifecycle
- session create/select/close
- multiple session isolation
- window resize
- shutdown idempotency

### Preview

- URL policy tests
- localhost acceptance
- public HTTP/HTTPS acceptance according to chosen policy
- dangerous scheme rejection
- URL normalization

Run:

```powershell
.\gradlew testDebugUnitTest --no-daemon
```

Then run the appropriate Android instrumentation/device tests available in the repository.

Do not declare success from unit tests alone.

---

# 11. REAL DEVICE VERIFICATION — REQUIRED

Use a real Android device because the most important failures involve:

- Android IME/insets
- PTY file descriptors
- native process behavior
- WebView networking
- Activity lifecycle

Perform this exact smoke test after implementation.

## Step A — Agent question

Start an agent task that deliberately calls `ask_question`.

Expected:

```text
agent pauses
↓
visible popup
↓
answer
↓
popup closes
↓
agent continues
```

## Step B — Agent permission

Start an operation that requires REVIEW permission.

Expected:

```text
agent pauses
↓
permission popup
↓
choose Allow Once
↓
tool executes
↓
agent continues
```

Repeat with Deny Once and verify the tool does not execute.

## Step C — Terminal streaming

Run:

```bash
python3 -c 'import time; [print("tick", i, flush=True) or time.sleep(1) for i in range(5)]'
```

Expected: five separate incremental updates, not one final batch.

## Step D — Interactive terminal

Run:

```bash
python3
```

Type:

```python
print("interactive-ok")
```

Then:

```text
exit()
```

Verify every input/output step works.

## Step E — Terminal tabs

Create T2 with `+`.

Run a long-lived command in T1.

Switch to T2 and run another command.

Switch back to T1.

Both sessions must remain independent.

## Step F — Keyboard layout

Open Android keyboard.

Verify:

- terminal viewport is not crushed
- extra-key tray is above keyboard
- terminal remains interactive
- PTY dimensions update

Close keyboard and verify terminal expands.

## Step G — Preview

Load:

1. local project server
2. public HTTPS test page
3. page containing public fonts/images/CDN assets

Verify all render correctly.

---

# 12. DEFINITION OF DONE

This task is complete only when ALL of the following are true:

### Question / Permission

- [ ] Agent question requests create durable state.
- [ ] User-required questions automatically produce visible UI.
- [ ] Permission requests requiring review automatically produce visible UI.
- [ ] All four supported permission decisions work.
- [ ] User answers/decisions reach the correct agent task/session.
- [ ] Agent resumes exactly once after resolution.
- [ ] Pending interactions survive recomposition/navigation and supported recovery paths.
- [ ] No sensitive information is exposed in the UI/logs.

### Terminal

- [ ] `+` creates a real PTY-backed session.
- [ ] Terminal input reaches the actual shell.
- [ ] Enter executes commands.
- [ ] Output streams in real time.
- [ ] `clear` works through terminal control sequences.
- [ ] Ctrl+C works.
- [ ] Interactive REPLs work.
- [ ] ANSI/VT sequences are rendered correctly.
- [ ] Multiple tabs are independent.
- [ ] Session close is clean.
- [ ] PTY resize works.
- [ ] Keyboard-open layout remains usable.
- [ ] No output batching caused by incorrect PTY non-blocking handling.
- [ ] No FD leaks/double-close lifecycle bugs.

### Preview

- [ ] Localhost preview continues to work.
- [ ] Public HTTPS URLs work.
- [ ] Public HTTP behavior is explicitly supported or intentionally rejected/documented.
- [ ] Public CSS/fonts/images/SVG/JS assets work.
- [ ] Normal fetch/XHR behavior works where permitted.
- [ ] Redirects work.
- [ ] Safe URL policy remains enforced.
- [ ] Preview does not become an unrestricted browser or filesystem reader.

### Regression

- [ ] Universal Model Provider remains untouched except for proven related integration fixes.
- [ ] Claude Code runtime still works.
- [ ] OpenAI/compatible native runtime still works.
- [ ] Tool Registry still works.
- [ ] Checkpoints/diffs still work.
- [ ] Existing project/chat persistence still works.
- [ ] Existing terminal/project lifecycle does not regress.
- [ ] Gradle tests pass.
- [ ] Real-device smoke tests pass.

---

# 13. IMPLEMENTATION RULES FOR THE CODING AGENT

1. **Read before editing.** Do not guess the current architecture.
2. **Use the latest `main` as the source of truth.** The SHA in this document is the known baseline only.
3. **Find root causes, not symptoms.**
4. **Do not rewrite unrelated working systems.**
5. **Do not reimplement Universal Model Provider.**
6. **Do not make OmniRoute-specific code.**
7. **Do not hide failures behind fake UI state.**
8. **Do not turn the terminal into a fake command console.** It must remain a real PTY.
9. **Do not batch PTY output until process completion.**
10. **Do not treat `EAGAIN`/temporary non-blocking reads as EOF.**
11. **Do not create duplicate sources of truth for agent interactions.**
12. **Do not auto-approve user-required permissions.**
13. **Do not make Preview localhost-only.**
14. **Do not disable WebView security wholesale.**
15. **Do not log credentials or secrets.**
16. **Prefer small focused fixes over another giant ViewModel/UI rewrite.**
17. **Test each fix immediately after implementation.**
18. **Run the complete regression suite before declaring completion.**
19. **Perform real Android device testing for PTY/IME/WebView behavior.**
20. At the end, provide a concise engineering report containing:
    - root cause of each issue
    - files changed
    - exact fixes
    - tests run
    - real-device verification results
    - any remaining known limitations

## Final instruction

Implement this as a **production-quality bug-fix pass on top of the current Mobile Harness architecture**.

Do not return a superficial patch that merely makes the screenshot look better. The acceptance criterion is that the complete underlying systems actually work:

```text
Agent -> Question/Permission -> visible user interaction -> decision -> Agent resumes

Keyboard -> PTY -> real shell -> real-time PTY output -> terminal renderer

Preview URL -> WebView -> public page -> external assets/API/resources -> correct rendered result
```

Only mark the task complete when these end-to-end flows have been verified on the latest codebase.