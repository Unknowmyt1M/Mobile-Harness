# Mobile Harness — Final Post-PRD Implementation Prompt

**Status:** Implementation-ready
**Purpose:** Final engineering specification for the next post-PRD workstream.
**Baseline:** Existing Mobile-Harness repository after completion of the original Mobile Harness PRD phases 0–10.

> **IMPORTANT:** This document is the implementation prompt/specification. The original PRD remains the architectural baseline. Do not redo completed PRD work unless required to integrate or repair it.

---

# 1. Mission

Implement three tightly integrated capabilities in Mobile-Harness:

1. **Termux-grade lightweight terminal foundation**
2. **Dedicated Permission Tool / Permission Manager**
3. **Dedicated Question Tool / Question Manager**

These are separate interaction mechanisms.

The core product behavior must become:

```text
Agent wants to act
       │
       ├── Normal/safe action ───────→ execute automatically
       │
       ├── Permission required ──────→ Permission Tool
       │                                  │
       │                                  └→ allow / deny according to policy
       │
       └── Human decision required ───→ Question Tool
                                          │
                                          └→ user answer

After resolution
       ↓
Agent resumes
       ↓
Terminal/build/test/verification
       ↓
Result
```

The UI must not implement fake controls. Terminal buttons, permissions, and questions must be backed by real domain state and real runtime behavior.

---

# 2. First Step — Audit Before Editing

Before writing code:

1. Inspect the complete current repository.
2. Read `docs/MOBILE_HARNESS_PRD.md`.
3. Identify which PRD phases are already implemented.
4. Inspect the current terminal implementation.
5. Inspect runtime/process spawning and native JNI code.
6. Inspect existing approval/permission handling.
7. Inspect existing question/interaction handling, if any.
8. Inspect task/session persistence.
9. Inspect Compose screens and navigation.
10. Inspect existing tests.

Do not duplicate existing infrastructure.

Prefer extending/refactoring existing implementations over creating parallel systems.

If the current implementation differs from this specification, preserve working behavior and migrate it toward this contract safely.

---

# 3. Product Principles

## 3.1 Local-first

Mobile-Harness remains a phone-first Android development environment.

Remote compute is NOT part of this work.

## 3.2 Lightweight

Do not turn Mobile-Harness into full Termux.

Do NOT add:

- Termux package ecosystem
- Termux plugins
- Termux:API
- package repository UI
- floating terminal
- widgets
- unrelated Termux utilities
- shell-selection UI

We only want the robust terminal/session/emulator concepts necessary for Mobile-Harness.

## 3.3 Safety-first agent interaction

The agent can act autonomously where policy permits, but it must never bypass the permission system.

## 3.4 Questions are not permissions

A question asks the user to choose a behavior/requirement/decision.

A permission request asks the user to authorize a capability/action.

They must remain separate at the protocol, domain, persistence, and UI layers.

## 3.5 Durable state

Questions and permissions must survive Android Activity recreation and background/foreground transitions.

## 3.6 Agent must not control the user's visible terminal session

Agent execution sessions and user terminal sessions share infrastructure but remain logically separate.

---

# 4. Terminal — Target Architecture

Implement the terminal around a real PTY:

```text
TerminalScreen
      ↓
TerminalView / TerminalEmulator
      ↓
TerminalSessionManager
      ↓
TerminalSession
   ├── TerminalEmulator
   ├── PtyProcess
   ├── cwd
   ├── environment
   ├── rows/columns
   ├── scrollback
   └── lifecycle state
      ↓
Native PTY / JNI
      ↓
shell
      ↓
PRoot
      ↓
Ubuntu userspace
      ↓
project
```

Study Termux's open-source terminal architecture for concepts such as:

- `/dev/ptmx`
- `grantpt()`
- `unlockpt()`
- `ptsname()`
- `fork()`
- `exec()`
- termios
- PTY window size
- SIGWINCH
- terminal emulator/VT parsing
- alternate screen buffer
- ANSI escape sequences
- process/session ownership

Do not copy the entire Termux app.

Use permissively licensed terminal-emulator components where practical and review licenses before importing code.

---

# 5. Terminal UI

Use the attached/reference terminal screenshot as the visual direction.

The terminal should feel like a dedicated fullscreen terminal workspace.

## Top bar

- Back button
- `Terminal` title
- project name/path
- overflow menu

## Session tabs

Tabs are immediately below the top bar.

Requirements:

- horizontally scrollable
- active/inactive visual state
- each tab represents a real independent session
- compact project/cwd label
- `+` immediately after the last tab
- no fixed four-tab limit
- create a real session when `+` is pressed
- close a session cleanly

Do not add shell selection.

## Terminal viewport

The terminal gets most of the screen.

It must resize around the Android IME rather than being hidden behind it.

## Extra-key box

Use exactly **two rows**.

No F1–F12.

No large four-row utility panel.

Target layout:

```text
┌──────────────────────────────────────────────┐
│ ESC   /   -   HOME   ↑   END   PGUP          │
│ TAB  CTRL ALT   ←    ↓   →    PGDN           │
└──────────────────────────────────────────────┘
```

The two rows must be inside one compact rounded rectangular box.

The Android system keyboard remains below it and must remain fully usable.

The box is an app-owned terminal key tray, not a replacement keyboard.

---

# 6. Terminal Input Must Be Real

Never implement extra keys as decorative labels or ordinary text insertion.

Each key must send the correct PTY input.

Required controls:

- ESC
- `/`
- `-`
- HOME
- END
- PGUP
- PGDN
- UP
- DOWN
- LEFT
- RIGHT
- TAB
- CTRL
- ALT

The system keyboard supplies normal text, Enter, Backspace, Shift, etc.

CTRL and ALT must be implemented as real terminal modifiers.

Examples:

```text
CTRL + C → Ctrl-C control byte
CTRL + D → Ctrl-D control byte
CTRL + L → Ctrl-L control byte
ALT + X  → correct terminal Alt-X sequence
```

Do not simply insert the string `CTRL+C`.

Modifier state must be explicit and visible.

Suggested behavior:

```text
Tap CTRL
  ↓
CTRL active
  ↓
Tap C
  ↓
send Ctrl-C
  ↓
CTRL returns to inactive unless configured otherwise
```

Avoid unexpected sticky modifiers.

If a terminal application requires modifier state to remain active, provide a deliberate state model rather than relying on accidental UI behavior.

---

# 7. Terminal Emulator Requirements

Do not strip ANSI sequences.

Correctly handle:

- ANSI colors
- cursor movement
- cursor visibility
- line clearing
- screen clearing
- scrolling
- Unicode
- wide characters where supported
- alternate screen buffer
- carriage return/newline
- tabs
- terminal modes
- application cursor keys
- application keypad where relevant
- bracketed paste where supported

Interactive applications must work.

At minimum test:

- bash/sh
- Python REPL
- Node REPL
- vim
- nano
- less
- top/htop
- git interactive commands
- npm
- readline-based programs

---

# 8. Terminal Resize

When the visible terminal viewport changes:

1. calculate terminal rows/columns
2. update terminal emulator dimensions
3. update PTY window size using `TIOCSWINSZ` or equivalent
4. notify the child process via SIGWINCH when required
5. preserve process state

Test:

- keyboard open
- keyboard close
- rotation/configuration change
- Activity recreation
- tab switching
- foreground/background

---

# 9. Terminal Session Lifecycle

Create a real `TerminalSessionManager`.

Suggested contract:

```text
createSession(projectId)
getSession(sessionId)
listSessions(projectId)
switchSession(sessionId)
closeSession(sessionId)
writeInput(sessionId, bytes)
resize(sessionId, rows, columns)
interrupt(sessionId)
kill(sessionId)
```

Each session owns:

- stable session ID
- PTY
- process/group ID
- cwd
- environment
- terminal dimensions
- emulator state
- scrollback
- creation timestamp
- lifecycle status

Multiple sessions must run concurrently subject to device resource limits.

Do not impose an arbitrary four-session limit.

However, implement sensible resource protection so a user cannot accidentally create unlimited memory/CPU consumption.

---

# 10. Android Lifecycle

Terminal sessions must not die merely because Compose/Activity is recreated.

Use a service/session ownership model where appropriate.

Expected:

```text
Activity destroyed
      ↓
Terminal sessions remain alive
      ↓
Activity recreated
      ↓
UI reconnects to sessions
```

When a session is explicitly closed:

- send graceful termination
- wait briefly
- terminate process group if necessary
- release PTY resources
- remove session state

Prevent orphan PRoot/process trees.

Account for Android 12+ background/phantom process/resource restrictions. Do not promise survival beyond what Android permits.

---

# 11. User Terminal vs Agent Terminal

Use one underlying session infrastructure but separate ownership/context.

```text
TerminalSessionManager
├── USER:T1
├── USER:T2
├── USER:T3
└── AGENT:<task/session>
```

The agent must not hijack the user's currently visible T1/T2 session.

Agent command execution should use its own session or non-interactive execution session as appropriate.

The user can inspect an agent session later if product UX supports it, but agent execution must remain isolated from the active user input stream.

---

# 12. Scrollback and Performance

Implement bounded scrollback.

Do not keep unlimited output in memory.

Use a memory-conscious representation.

Support large output without freezing Compose.

Rendering must not recompose the entire terminal buffer for every byte.

Use incremental/batched updates where practical.

---

# 13. Permission Tool — Separate System

This is a first-class agent interaction mechanism.

The permission system must NOT be implemented as a generic question.

Suggested architecture:

```text
Agent Tool Request
       ↓
PermissionManager
       ↓
PolicyEngine
       ├── SAFE
       ├── LOW_RISK
       ├── REVIEW
       ├── HIGH_RISK
       └── BLOCKED
       ↓
Auto allow / User permission / Deny
       ↓
Agent resumes
```

## 13.1 Permission scopes

Permissions must be based on **capability/scope/category**, not raw exact command strings.

Avoid:

```text
allow command: "rm -rf /some/path"
```

Prefer capability-level concepts such as:

```text
filesystem.read
filesystem.write
filesystem.delete
process.execute
network.access
package.install
project.modify
secrets.use
external_tool.execute
```

Exact scope names may be adapted to the existing implementation, but they must be granular enough to avoid overly broad authorization.

## 13.2 Required user decisions

The permission UI must offer exactly these grant/deny choices:

1. **Allow once**
2. **Allow for this project**
3. **Always allow**
4. **Deny once**

Do NOT add an `Always deny` option.

The user must be able to grant the capability later.

## 13.3 Permission lifecycle

```text
REQUESTED
   ↓
POLICY_EVALUATION
   ├── SAFE → AUTO_ALLOWED
   ├── BLOCKED → DENIED
   └── USER_REQUIRED
          ↓
      WAITING_FOR_USER
          ↓
      ALLOWED / DENIED
          ↓
      RESUME_AGENT
```

Persist every user decision.

## 13.4 Permission duration semantics

### Allow once
Applies only to the current request/action instance.

### Allow for this project
Applies to the selected capability/scope within the current project.

### Always allow
Applies according to the global capability policy and remains until revoked/reset by the user.

### Deny once
Reject only the current request. Do not permanently block the capability.

Do not silently upgrade a one-time permission into a persistent permission.

## 13.5 Agent cannot weaken policy

The agent may request a capability.

The agent must not be able to:

- mark a dangerous action as safe
- change a required user approval to auto-allow
- grant itself permanent permission
- change global policy
- bypass the PermissionManager

Risk classification and permission policy are application-owned.

---

# 14. Permission Risk Policy

Implement an explicit policy evaluator.

### SAFE
Examples:

- read file
- list directory
- search source code
- inspect git status
- inspect metadata
- read-only test/report

Default: auto-allow.

### LOW_RISK
Examples:

- formatting a project file
- local non-destructive analysis
- creating temporary build artifacts

Default: policy-controlled; can be auto-allowed by safe project policy.

### REVIEW
Examples:

- installing packages
- modifying many files
- dependency updates
- starting a server
- network access
- external tool use

Default: request permission when project policy requires it.

### HIGH_RISK
Examples:

- deleting files
- destructive shell operations
- changing protected configuration
- credential/secret use
- destructive git operations
- broad filesystem access
- potentially irreversible changes

Default: explicit user permission.

### BLOCKED
Examples should include operations that the application policy explicitly refuses to expose.

Blocked actions cannot be overridden by an ordinary permission button.

The exact blocked list should be derived from the existing Mobile-Harness security model and documented.

---

# 15. Permission UI

Permission requests should appear in a dedicated, clearly recognizable permission card/surface.

Do not disguise them as ordinary agent questions.

Display:

- action/capability
- why it is needed
- project/context
- risk level where useful
- affected path/resource if applicable
- exact consequence
- decision buttons

Example:

```text
Permission required

Install project dependencies?

Capability: package.install
Project: ~/my-project

This will execute the package manager and modify
node_modules/package-lock files.

[ Allow once ]
[ Allow for this project ]
[ Always allow ]
[ Deny once ]
```

The wording should be human-readable rather than raw internal tool JSON.

---

# 16. Question Tool — Separate System

The Question Tool exists for decisions that are not authorization requests.

Examples:

- choosing architecture
- selecting database
- resolving ambiguous requirements
- choosing between materially different implementation options
- confirming desired product behavior
- requesting information the agent cannot infer safely

Suggested domain model:

```kotlin
sealed interface AgentQuestion {
    val id: String
    val taskId: String
    val title: String?
    val question: String
    val required: Boolean
}
```

Supported question types:

```text
SELECT_ONE
SELECT_MULTIPLE
YES_NO
TEXT
NUMBER
PATH
CONFIRMATION
```

---

# 17. Question Schema

A structured question should contain at least:

```text
id
 taskId
 type
 title
 question
 options?
 recommendedOption?
 timeout?
 autoResolvePolicy
 required
 createdAt
 expiresAt?
 status
 answer?
```

For options:

```text
option.id
option.label
option.description?
```

Example:

```json
{
  "id": "q_123",
  "taskId": "task_456",
  "type": "SELECT_ONE",
  "title": "Choose a database",
  "question": "Which database should I use?",
  "options": [
    {
      "id": "sqlite",
      "label": "SQLite",
      "description": "Simple local database"
    },
    {
      "id": "supabase",
      "label": "Supabase",
      "description": "Hosted Postgres"
    }
  ],
  "recommendedOption": "sqlite",
  "required": true,
  "autoResolvePolicy": "USER_REQUIRED"
}
```

---

# 18. Question Auto-Resolve / Timer

Questions may have a five-minute timer **only when the application policy determines that automatic resolution is safe**.

Do not allow the agent to decide that its own question is safe for auto-resolution.

Use:

```text
AutoResolvePolicy
├── AUTO_RESOLVE
└── USER_REQUIRED
```

Policy engine determines the value.

## AUTO_RESOLVE

Allowed only for simple, low-impact questions where the recommended option is safe and deterministic.

Example:

```text
Use standard formatting configuration?

Recommended: Yes

Auto-selecting Yes in 04:37
```

When timer reaches zero:

```text
selectedOption = recommendedOption
status = AUTO_RESOLVED
```

The event/history must clearly say that it was auto-resolved.

## USER_REQUIRED

No automatic selection.

Show:

```text
Your decision is required
```

The question remains pending until the user answers or the task is cancelled.

Never silently choose a high-impact architecture, destructive action, security decision, credential choice, or materially ambiguous requirement.

---

# 19. Question Lifecycle

```text
CREATED
   ↓
WAITING_FOR_USER
   ├── ANSWERED
   ├── AUTO_RESOLVED
   ├── CANCELLED
   └── EXPIRED (only if policy defines expiry without auto-answer)
        ↓
   RESUME_AGENT
```

Every transition must be durable.

The agent must pause while waiting.

After answer:

1. persist answer
2. emit `QUESTION_ANSWERED`
3. update task state
4. resume agent exactly once

Prevent double-submit/double-resume races.

---

# 20. Question UI

Questions should appear inline in the agent conversation/task surface.

Required:

- title
- question
- options where applicable
- recommended option indicator
- timer only when policy permits
- submit/confirm action
- disabled state after submission
- answer shown in history
- clear waiting state

Example:

```text
Choose a database

Which database should I use for this project?

○ SQLite
  Simple local database

○ Supabase
  Hosted Postgres

○ MongoDB
  Document database

Recommended: SQLite

[ Continue ]
```

For text/path/number questions, use appropriate Android input controls.

Do not force every question into a multiple-choice layout.

---

# 21. Question Quality Rules

The agent should ask only when needed.

Ask when:

- requirements are genuinely ambiguous
- there are materially different valid approaches
- user preference changes the outcome significantly
- destructive/irreversible behavior needs explicit confirmation
- security/credential decisions require human input
- the agent cannot safely infer intent

Do NOT ask about:

- trivial implementation details
- naming variables
- formatting choices that follow project conventions
- obvious dependency versions
- routine commands that policy already allows
- decisions that can be safely derived from project context

The goal is autonomous execution with targeted human intervention, not constant questioning.

---

# 22. Unified Agent Interaction Manager

Create or adapt a common application-level coordinator, for example:

```text
AgentInteractionManager
├── QuestionManager
├── PermissionManager
└── InteractionPersistence
```

The manager should coordinate lifecycle, but permission and question semantics remain separate.

Example:

```text
AgentInteractionManager

requestPermission(request)
requestQuestion(question)
answerQuestion(id, answer)
resolvePermission(id, decision)
restorePendingInteractions(taskId)
```

Do not expose Android Compose types to domain/runtime code.

---

# 23. Agent Task State Integration

Questions and permissions must integrate with durable task state.

Required task states should support at least:

```text
CREATED
QUEUED
RUNNING
WAITING_FOR_USER
WAITING_FOR_PERMISSION
WAITING_FOR_PROCESS
VERIFYING
COMPLETED
FAILED
CANCELLED
RECOVERABLE
```

Example:

```text
Agent running
   ↓
Question requested
   ↓
Task = WAITING_FOR_USER
   ↓
Android app backgrounded
   ↓
Process may be recreated
   ↓
Task restored
   ↓
Question restored
   ↓
User answers
   ↓
Task = RUNNING
   ↓
Agent resumes
```

Never depend only on an in-memory `pendingApproval` or `pendingQuestion` variable.

---

# 24. Event Protocol

Use structured events for both systems.

At minimum:

```text
PERMISSION_REQUESTED
PERMISSION_POLICY_EVALUATED
PERMISSION_ALLOWED
PERMISSION_DENIED
QUESTION_REQUESTED
QUESTION_ANSWERED
QUESTION_AUTO_RESOLVED
QUESTION_CANCELLED
```

Events should contain:

- event ID
- task ID
- session ID where applicable
- sequence number
- timestamp
- type
- payload version
- structured payload

This enables recovery and auditing.

---

# 25. Security Requirements

The old behavior where permission requests are automatically written as `allow` must not remain.

There must be exactly one authoritative permission decision path.

All agent tool execution that requires permission must pass through it.

Never log:

- API keys
- access tokens
- secrets
- secret-bearing environment variables
- raw credential payloads

Apply redaction before logs/history.

PRoot is not a security boundary.

Do not describe it as sandboxing the device.

Workspace/protected-path checks remain application-level controls.

---

# 26. Persistence

Use the existing persistence architecture if it can safely support these requirements; otherwise introduce a transactional local store.

Persist:

### Terminal metadata

- session ID
- project ID
- cwd
- state
- timestamps
- last terminal dimensions

Large scrollback should remain memory/file backed rather than being blindly inserted into a database.

### Permission state

- request ID
- task ID
- capability/scope
- resource/context
- risk classification
- decision
- duration/scope
- created/resolved timestamps

### Question state

- question ID
- task ID
- schema/type
- options
- recommended option
- auto-resolve policy
- timeout/expiresAt
- status
- answer
- timestamps

Persistence must be atomic enough that a crash cannot leave an interaction in an impossible state.

---

# 27. Race Conditions

Explicitly handle:

- user answers while agent cancellation occurs
- permission resolves while process exits
- question timer expires while user taps an option
- Activity recreation during submission
- duplicate UI events
- duplicate agent resume
- two permission requests for the same capability
- terminal session close while a command is running

Use stable IDs and idempotent resolution.

A resolved question/permission must never be resolved a second time.

---

# 28. Notifications

If an agent is waiting for a user decision while the app is backgrounded, the user should be able to discover that action is required through the existing notification infrastructure where appropriate.

Notification should distinguish:

- Question waiting
- Permission waiting

Tapping the notification should restore/open the correct task and interaction.

Do not put sensitive content into notification text.

---

# 29. Testing Requirements

Do not mark the feature complete based on visual inspection alone.

## Terminal unit/integration tests

Test:

- PTY creation
- PTY input/output
- shell startup
- process exit
- process group cleanup
- interrupt
- kill
- resize
- SIGWINCH
- ANSI parsing
- Unicode
- alternate screen
- scrollback

## Input tests

- ESC
- TAB
- CTRL+C
- CTRL+D
- CTRL+L
- ALT combinations
- arrows
- HOME
- END
- PGUP
- PGDN
- normal keyboard text
- Backspace
- Enter

## Session tests

- create T1
- create T2
- create many sessions within resource policy
- switch sessions
- independent cwd
- independent environment
- independent process
- close one session while another continues

## Lifecycle tests

- Activity recreation
- configuration changes
- background/foreground
- service restart/reconnect
- session cleanup

## Permission tests

Test all four decisions:

```text
ALLOW_ONCE
ALLOW_PROJECT
ALLOW_ALWAYS
DENY_ONCE
```

Verify:

- allow once does not persist
- project permission affects only that project
- always allow persists according to policy
- deny once does not permanently block future requests
- blocked operations cannot be overridden
- agent cannot self-grant permission
- dangerous actions cannot silently auto-allow

## Question tests

Test:

- SELECT_ONE
- SELECT_MULTIPLE
- YES_NO
- TEXT
- NUMBER
- PATH
- CONFIRMATION
- answer persistence
- required questions
- auto-resolvable questions
- USER_REQUIRED questions
- five-minute timer logic
- recommended option
- timeout auto-resolution
- no auto-resolution for USER_REQUIRED
- duplicate answer prevention
- lifecycle restoration

## Integration tests

At least test these flows:

```text
Agent → safe tool → automatic execution

Agent → permission-required tool → permission UI → Allow once → resume

Agent → permission-required tool → permission UI → Allow for project → later request works

Agent → question → user answer → resume

Agent → auto-resolvable question → timer → recommended answer → resume

Agent → USER_REQUIRED question → timeout → remains waiting

App backgrounded during question → app reopened → question still present

App backgrounded during permission → app reopened → permission still present
```

---

# 30. UI/UX Quality Bar

The UI should feel like one coherent mobile product.

Terminal:

- dark
- compact
- information-dense
- minimal chrome
- large usable terminal viewport
- two-row extra-key box
- Android keyboard remains visible

Permission:

- clearly authorization-focused
- concise
- consequence-oriented
- four explicit decisions

Question:

- clearly decision-focused
- easy to answer with one hand
- options readable
- timer unobtrusive when used

Do not use giant modal dialogs for routine interactions unless Android UX requires it.

Avoid visual clutter.

---

# 31. Architecture / Code Organization

Target separation:

```text
UI
├── TerminalScreen
├── TerminalTabBar
├── TerminalExtraKeys
├── PermissionCard
├── QuestionCard
└── InteractionHost

Application
├── TerminalSessionManager
├── AgentInteractionManager
├── PermissionManager
├── QuestionManager
├── PolicyEngine
└── TaskLifecycleManager

Domain
├── TerminalSession
├── PermissionRequest
├── PermissionDecision
├── AgentQuestion
├── QuestionAnswer
├── AgentEvent
└── TaskState

Infrastructure
├── PtyProcess
├── NativePtyBridge
├── TerminalEmulator
├── Persistence
├── Notifications
└── AgentRuntime
```

Names may differ if the existing codebase has established conventions, but responsibilities should remain separated.

Do not put native PTY management, permission policy, question state, and Compose rendering into one giant ViewModel.

---

# 32. Backward Compatibility

Do not break existing:

- projects
- chats
- provider configuration
- authentication
- Claude/agent runtime
- PRoot Ubuntu installation
- files/workspaces
- checkpoints/diffs
- existing MCP integration
- existing navigation

Migrate existing approval state safely.

If old persisted approval data exists, implement a migration strategy rather than silently discarding it.

---

# 33. Definition of Done

The work is complete only when all of the following are true:

### Terminal

- [ ] Real PTY is used.
- [ ] Terminal emulator handles ANSI/VT behavior.
- [ ] Interactive CLI applications work.
- [ ] Multiple independent terminal sessions work.
- [ ] Tabs are horizontally scrollable.
- [ ] `+` creates a real session.
- [ ] No shell-selection UI exists.
- [ ] F1–F12 are removed.
- [ ] Extra keys are exactly two rows.
- [ ] Extra keys are inside one compact rectangle.
- [ ] CTRL/ALT are real modifiers.
- [ ] CTRL+C/D/L work.
- [ ] Arrow/Home/End/Page keys work.
- [ ] Android keyboard remains usable.
- [ ] IME resizing updates PTY size.
- [ ] Sessions survive Activity recreation where Android process lifetime permits.
- [ ] Explicit session close cleans process groups.
- [ ] User and agent terminal sessions are separated.
- [ ] Scrollback is bounded.

### Permission

- [ ] Permission system is separate from Question system.
- [ ] Every permission-sensitive action passes through the policy engine.
- [ ] Risk classification is application-owned.
- [ ] Agent cannot downgrade risk or grant itself permission.
- [ ] Allow once works.
- [ ] Allow for this project works.
- [ ] Always allow works.
- [ ] Deny once works.
- [ ] No Always deny option is exposed.
- [ ] Dangerous actions require explicit approval.
- [ ] Blocked operations remain blocked.
- [ ] Decisions persist correctly.
- [ ] Permission state survives lifecycle events.

### Question

- [ ] Question is a separate first-class protocol.
- [ ] All required question types work.
- [ ] Questions are structured and persisted.
- [ ] Recommended option is supported.
- [ ] Five-minute timer is supported only where policy allows.
- [ ] AUTO_RESOLVE is policy-controlled.
- [ ] USER_REQUIRED cannot auto-resolve.
- [ ] Agent cannot force a question into AUTO_RESOLVE.
- [ ] Answer is persisted before agent resumes.
- [ ] Duplicate answers cannot resume the agent twice.
- [ ] Questions survive background/recreation.

### Security / reliability

- [ ] No unconditional permission auto-allow remains.
- [ ] Secrets are not logged.
- [ ] PRoot is not presented as a security boundary.
- [ ] Critical lifecycle/race tests pass.
- [ ] Existing Mobile-Harness functionality remains intact.

---

# 34. Implementation Strategy

Implement in this order unless the existing architecture requires a safer migration sequence:

## Phase A — Audit and contracts

- inspect existing implementation
- define domain models/interfaces
- define event/state contracts
- identify migration points

## Phase B — Terminal foundation

- native PTY
- emulator/input pipeline
- session manager
- resize
- lifecycle
- multiple sessions

## Phase C — Terminal UI

- fullscreen terminal workspace
- tab bar
- compact two-row key box
- IME integration
- modifier handling

## Phase D — Permission system

- policy engine
- capability scopes
- permission manager
- durable state
- UI
- runtime integration
- remove unconditional auto-approval

## Phase E — Question system

- structured question protocol
- question manager
- persistence
- timer/auto-resolution policy
- UI
- runtime pause/resume integration

## Phase F — Unified interaction + recovery

- AgentInteractionManager
- task state integration
- event replay/recovery
- notifications
- lifecycle/race handling

## Phase G — Testing and hardening

- unit tests
- integration tests
- interactive terminal tests
- permission/question lifecycle tests
- performance testing
- security audit

---

# 35. Final Agent Instruction

Do not stop at a UI implementation.

Do not create mock terminal buttons.

Do not create a fake permission dialog disconnected from the runtime.

Do not create a question card disconnected from task state.

Do not leave the old unconditional permission approval path in place.

Do not duplicate existing repositories/services unnecessarily.

Inspect first, design the migration, implement the real underlying behavior, test it, then integrate the UI.

The final result should feel like:

> **A lightweight Termux-grade terminal + a safe autonomous agent that knows when it can act, when it needs permission, and when it genuinely needs to ask the user.**

The product should maximize autonomous execution while preserving user control over meaningful decisions and capabilities.
