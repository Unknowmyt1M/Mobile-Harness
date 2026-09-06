# Mobile Harness — Product Requirements & Engineering Plan

**Document status:** Proposed / implementation-ready  
**Baseline:** `main` at audit time (September 2026)  
**Scope:** Phone-first Android development environment; remote compute is explicitly out of scope for this roadmap.

---

## 1. Executive Summary

Mobile Harness is a phone-first Android coding environment built around a Linux userspace runtime, native process spawning, Claude Code, project workspaces, chat, terminal, diffs/checkpoints, provider configuration, and web preview.

The next evolution is **not** to turn it into a generic remote IDE. The product should become a **reliable, mobile-native autonomous software engineering environment** where the agent can:

1. understand the project,
2. plan and execute multi-step work,
3. safely modify files and run commands,
4. recover from failures,
5. ask the user structured questions when a decision genuinely requires human input,
6. persist state across app/background/process interruptions,
7. review and roll back changes,
8. use an extensible tool/MCP ecosystem,
9. test its own work, and
10. finish with a verifiable result.

The roadmap is intentionally staged. Reliability and the runtime contract come before cosmetic IDE expansion.

---

# 2. Product Vision

> **A complete software-engineering workstation in your Android phone, with an autonomous coding agent that knows when to act, when to ask, and when to stop.**

The defining interaction should feel like:

```text
User goal
   ↓
Agent understands project
   ↓
Agent plans
   ↓
Agent executes
   ↓
Build / test / inspect
   ↓
 ┌───────────────────────────────┐
 │ Decision required?            │
 │                               │
 │ NO → continue automatically   │
 │ YES → ask user with options   │
 └───────────────────────────────┘
   ↓
Verify
   ↓
Checkpoint / summarize
   ↓
Done
```

---

# 3. Explicit Product Boundaries

## In scope

- Android / ARM64 phone-first execution
- Local Linux/PRoot development environment
- Local terminal and filesystem
- Claude-compatible agent runtime
- Multiple LLM provider/gateway configurations
- Persistent agent task state
- Structured user questions and approvals
- Project memory
- Autonomous build/test/fix loops
- Git/checkpoint/rollback workflows
- MCP/tool ecosystem
- Mobile-native code editing improvements
- Web preview and eventually device-local app testing
- Resource-aware execution for phones

## Explicitly out of scope for this roadmap

- Remote PC/VPS compute mode
- Cloud-hosted development environments
- Turning the phone into a remote desktop for another machine
- GPU inference as a core requirement

Remote compute may be revisited after the phone-first architecture is mature.

---

# 4. Baseline Architecture Audit

## 4.1 Current high-level architecture

```text
Android UI / Compose
        │
        ▼
MainViewModel
        │
        ├──────── AppPreferences / ApiKeyVault
        ├──────── ProviderApiClient
        └──────── ClaudeRuntimeBridge
                         │
                         ├── RuntimeInstaller
                         ├── NativeSpawnProcess
                         │        │
                         │        ▼
                         │   JNI / native spawn
                         │        │
                         │        ▼
                         │   PRoot + Ubuntu userspace
                         │        │
                         │        ▼
                         │   /usr/local/bin/claude
                         │
                         └── filesystem checkpoint/diff layer
```

The architecture has a sensible separation between UI state, runtime orchestration, native process spawning, installation, persistence, provider configuration, and secrets. The main weakness is that **agent lifecycle/state is still too tightly coupled to one runtime implementation and one very large ViewModel/bridge pair**.

---

# 5. Code-Level Audit Findings

## 5.1 Critical: permission/approval path is currently unsafe

**File:** `app/src/main/java/com/jarves/mh/runtime/ClaudeRuntimeBridge.kt`

The runtime has a `ToolRequest` / `respondToApproval()` contract and the UI state already has `pendingApproval`. However, `watchPermissionRequests()` currently writes `allow` directly for detected permission requests.

This effectively bypasses the intended approval abstraction.

### Required change

Replace implicit auto-approval with a real policy engine:

```text
Agent tool request
      ↓
Policy evaluator
      ↓
SAFE ─────────────→ auto allow
REVIEW ────────────→ ask user
HIGH ──────────────→ explicit confirmation
BLOCKED ───────────→ deny
```

No destructive or high-risk action may be silently approved.

---

## 5.2 Critical: runtime state is process-local

**Files:**
- `ClaudeRuntimeBridge.kt`
- `MainViewModel.kt`
- `RuntimeExecutionService.kt`

The runtime uses volatile fields and in-memory maps such as active session/process IDs, pending permissions, tool-call tracking, and streamed output buffers.

This is fine for a prototype, but an Android process can be killed or recreated. A robust autonomous agent needs durable task state.

### Required change

Introduce a persistent `AgentTaskStore` with explicit states:

```text
CREATED
QUEUED
RUNNING
WAITING_FOR_USER
WAITING_FOR_PROCESS
VERIFYING
COMPLETED
FAILED
CANCELLED
RECOVERABLE
```

Every state transition should be persisted atomically.

---

## 5.3 Critical: `RuntimeBridge` is Claude-specific in practice

**File:** `RuntimeBridge.kt`

The interface is generic-looking, but implementation details are strongly Claude Code-specific. `RuntimeLaunchConfigBuilder` also maps several provider protocols into Claude environment variables and launches `/usr/local/bin/claude`.

### Required change

Split responsibilities:

```text
AgentRuntime
├── ClaudeCodeRuntime
├── FutureOpenCodeRuntime
└── FutureOtherRuntime

ModelGateway
├── Anthropic
├── OpenAI-compatible
├── LLM router
└── Custom gateway

AgentOrchestrator
└── runtime-independent task lifecycle
```

Do not make the UI depend on Claude-specific event shapes.

---

## 5.4 High: provider abstraction is useful but not yet a true model router

**File:** `Models.kt`, `RuntimeBridge.kt`, `ProviderApiClient.kt`

Provider profiles already support Claude login, Anthropic, an Anthropic-compatible router, OpenAI-compatible providers, Kimi, and custom APIs. This is a strong foundation.

The missing layer is an explicit **Model Gateway / Router contract** that can normalize:

- model selection,
- capability discovery,
- streaming,
- retries,
- timeout behavior,
- authentication,
- provider errors,
- fallback,
- model aliases.

### Required change

Create a provider-neutral contract:

```text
ModelGateway
  send()
  stream()
  discoverModels()
  validate()
  classifyError()
```

OmniRoute compatibility should be treated as a first-class OpenAI/Anthropic-compatible gateway, not as a special-case provider.

---

## 5.5 High: `MainViewModel` is too large

**File:** `MainViewModel.kt` (~91 KB at audit time)

It currently handles UI state, project management, chat persistence, runtime setup, provider state, terminal execution, file operations, attachments, runtime events, and other concerns.

### Required refactor

Move toward:

```text
MainViewModel
  └── screen composition only

ProjectRepository
ChatRepository
AgentTaskRepository
WorkspaceRepository
TerminalService
AgentOrchestrator
RuntimeManager
ProviderRepository
SecretsRepository
CheckpointRepository
```

The ViewModel should consume flows and dispatch intents rather than contain the entire application.

---

## 5.6 High: `ClaudeRuntimeBridge` is too large

**File:** `ClaudeRuntimeBridge.kt` (~46 KB at audit time)

It currently combines:

- process lifecycle,
- streaming parsing,
- provider error detection,
- permission watching,
- checkpointing,
- file change detection,
- foreground notifications,
- Claude event interpretation,
- session completion/failure.

### Required refactor

Split into:

```text
ClaudeProcessRunner
ClaudeStreamParser
AgentPermissionBroker
AgentEventMapper
TaskLifecycleManager
CheckpointManager
ForegroundExecutionController
```

This will make unit testing dramatically easier.

---

## 5.7 High: structured agent questions are missing as a first-class protocol

The current runtime has tool approval infrastructure, but there is no dedicated durable `ask_user()` contract for arbitrary agent decisions.

### Required design

Add:

```kotlin
sealed interface AgentQuestion {
    val id: String
    val taskId: String
    val question: String
    val title: String?
    val required: Boolean
}
```

Supported types:

```text
SELECT_ONE
SELECT_MULTIPLE
YES_NO
TEXT
NUMBER
PATH
CONFIRMATION
```

The response must be structured and persisted, not injected into the conversation as an untyped string.

---

## 5.8 High: question/approval state must survive app lifecycle events

If the app goes into the background while the agent is waiting for an answer, the question must remain pending.

Required behavior:

```text
Agent asks
 ↓
Persist WAITING_FOR_USER
 ↓
Android UI renders question
 ↓
User leaves app
 ↓
Task remains pending
 ↓
User returns / notification opens app
 ↓
Question restored
 ↓
Answer persisted
 ↓
Agent resumes
```

Never rely solely on an in-memory `pendingApproval` field.

---

## 5.9 Medium: persistence is JSON/file based and needs transactional boundaries

**File:** `AppPreferences.kt`

Current project/chat persistence uses SharedPreferences plus JSON files. This is simple and portable, but it becomes fragile once task state, questions, tool calls, event history, and memory grow.

### Required direction

Introduce a small local database layer (Room/SQLite or an equivalent transactional store) for:

- projects,
- chats,
- tasks,
- task events,
- questions,
- answers,
- checkpoints metadata,
- memory metadata.

Large files/diffs should remain filesystem-backed.

---

## 5.10 Medium: secret vault is good foundation but needs hardening

**File:** `ApiKeyVault.kt`

The current AES-GCM + Android Keystore design is a good baseline. Improvements should include:

- provider-scoped aliases or authenticated metadata,
- optional biometric/device credential protection,
- migration-safe key rotation,
- explicit handling of invalidated Keystore keys,
- never logging secret-bearing environment values,
- redaction of secrets from agent/tool output.

The runtime should also maintain a **secret redaction layer** before output reaches chat/history/logs.

---

## 5.11 Medium: process output transport is file-backed and should be made bounded

**File:** `NativeSpawnProcess.kt`

Using a native process plus an output file is a pragmatic Android workaround. However, long-running sessions can produce large output and require careful lifecycle/rotation handling.

Required improvements:

- bounded output buffers,
- output file rotation,
- structured event stream where possible,
- backpressure,
- explicit process-group cleanup,
- orphan-process detection,
- resource limits.

---

## 5.12 Medium: native process lifecycle needs stronger cleanup semantics

`NativeSpawnProcess` exposes SIGTERM/SIGKILL behavior and a Ctrl+C-style interrupt, which is useful. The next layer should guarantee cleanup of child processes and descendants, not only the direct PID.

Add:

- process-group/session management,
- cancellation propagation,
- timeout enforcement,
- orphan detection,
- crash recovery.

---

## 5.13 Medium: security boundary must be explicit

The repository already uses PRoot/Ubuntu without root. PRoot is a userspace virtualization mechanism, not a strong security sandbox.

Therefore the product must never advertise PRoot as a security boundary.

Implement application-level controls:

- workspace path validation,
- protected paths,
- command risk classification,
- network policy,
- secret redaction,
- resource limits,
- destructive-operation confirmations,
- explicit user approval for privileged/destructive operations.

---

## 5.14 Medium: build configuration needs modernization as part of hardening

**File:** `app/build.gradle.kts`

The current build intentionally supports legacy target SDK behavior and keeps minification disabled. This may be necessary for the existing PRoot path, but the roadmap should separate:

```text
Compatibility build
Production build
Play/modern Android build
```

Before increasing target SDK, validate:

- foreground service behavior,
- notifications,
- background execution,
- file access,
- native spawning,
- PRoot behavior,
- installation/update behavior.

---

# 6. Target Architecture

```text
┌────────────────────────────────────────────────────────────┐
│                    MOBILE HARNESS APP                      │
├────────────────────────────────────────────────────────────┤
│ UI Layer                                                   │
│  Chat │ Editor │ Terminal │ Diff │ Preview │ Questions     │
├────────────────────────────────────────────────────────────┤
│ Application Layer                                          │
│  AgentOrchestrator │ TaskManager │ PolicyEngine            │
│  QuestionManager   │ VerificationManager │ MemoryManager   │
├────────────────────────────────────────────────────────────┤
│ Domain Contracts                                           │
│  AgentEvent │ AgentQuestion │ ToolRequest │ TaskState      │
│  ModelRequest │ ModelResponse │ Checkpoint │ MemoryEntry   │
├────────────────────────────────────────────────────────────┤
│ Infrastructure                                             │
│  Runtime │ ModelGateway │ Terminal │ Workspace │ Git       │
│  MCP │ Persistence │ Secrets │ Notifications               │
├────────────────────────────────────────────────────────────┤
│ Runtime                                                    │
│  PRoot Ubuntu │ Native Spawn │ Claude Code / Agent Runtime │
└────────────────────────────────────────────────────────────┘
```

The key architectural rule is:

> **The UI must not need to know how the agent is implemented, and the agent must not need to know how Android renders a question.**

---

# 7. Agent Interaction Protocol

## 7.1 Unified event model

Create a versioned event protocol:

```text
SESSION_STARTED
PLAN_CREATED
ASSISTANT_DELTA
THINKING_PROGRESS
TOOL_STARTED
TOOL_OUTPUT
TOOL_REQUESTED
QUESTION_REQUESTED
QUESTION_ANSWERED
CHECKPOINT_CREATED
FILES_CHANGED
VERIFICATION_STARTED
VERIFICATION_RESULT
SESSION_COMPLETED
SESSION_FAILED
SESSION_CANCELLED
```

Each event must contain:

- event ID,
- task ID,
- session ID,
- monotonic sequence number,
- timestamp,
- type,
- payload version.

This allows event replay and recovery.

---

# 8. Agent Question System

## 8.1 Requirements

The agent can pause execution and request a decision with structured options.

Example:

```json
{
  "type": "select_one",
  "id": "q_123",
  "title": "Choose a database",
  "question": "Which database should I use for this project?",
  "options": [
    {"id": "sqlite", "label": "SQLite", "description": "Simple local database"},
    {"id": "supabase", "label": "Supabase", "description": "Hosted Postgres + auth"},
    {"id": "mongo", "label": "MongoDB", "description": "Document database"}
  ],
  "required": true,
  "defaultOptionId": "sqlite"
}
```

## 8.2 UX

The question should appear inline in the agent conversation, not as a random system dialog.

Required UI elements:

- clear question,
- optional explanation,
- selectable options,
- recommended option indicator,
- submit button,
- optional free-text response,
- disabled state after submission,
- answer shown in conversation history.

## 8.3 Policy

The agent should ask only when:

- requirements are ambiguous,
- multiple materially different architectures are valid,
- a destructive/irreversible action is proposed,
- a credential/security decision requires user consent,
- the agent cannot safely infer the desired behavior.

The agent should **not** ask about trivial implementation details it can infer.

---

# 9. Risk / Approval Policy

Every tool operation receives a risk classification.

### SAFE

Examples:

- read file,
- list directory,
- search code,
- inspect git status,
- run a read-only test.

Default: auto-allow.

### REVIEW

Examples:

- install package,
- modify many files,
- run a server,
- network access,
- update dependencies.

Default: policy-driven; ask when project/user settings require it.

### HIGH

Examples:

- delete files,
- destructive git reset,
- overwrite protected configuration,
- credential access,
- privileged/system changes.

Default: explicit user confirmation.

### BLOCKED

Examples:

- access outside approved workspace,
- attempts to exfiltrate secrets,
- dangerous commands prohibited by policy.

Default: deny and explain.

---

# 10. Project Memory

Each project should have durable memory with a clear distinction between facts and guesses.

```text
.memory/
├── project.md
├── architecture.md
├── decisions.md
├── constraints.md
├── known-issues.md
└── agent-state.json
```

The UI should also expose a summarized memory view.

Memory rules:

- never silently store secrets,
- allow user deletion/editing,
- distinguish user-provided facts from agent-generated observations,
- update memory after verified architectural changes,
- avoid storing every chat message as memory.

---

# 11. Autonomous Engineering Loop

The target agent loop is:

```text
UNDERSTAND
   ↓
PLAN
   ↓
EXECUTE
   ↓
OBSERVE
   ↓
VERIFY
   ↓
 ┌───────────────┐
 │ failure?      │
 └───────┬───────┘
    YES  │  NO
     ↓   │   ↓
DIAGNOSE │ COMPLETE
     ↓   │
PATCH    │
     ↓   │
RETEST ──┘
```

Add configurable limits:

- maximum autonomous turns,
- maximum retries per failure,
- maximum wall-clock time,
- maximum command duration,
- maximum files changed without review,
- maximum verification attempts.

The current hard-coded `--max-turns 25` should eventually become a policy/configuration value.

---

# 12. Verification System

The agent should know whether its work actually works.

Verification levels:

1. syntax/static validation,
2. unit tests,
3. build,
4. integration tests,
5. web preview smoke test,
6. Android/local app smoke test where supported.

Verification results should be structured rather than only appended to chat text.

---

# 13. Git / Checkpoint Model

Current checkpoint and per-file undo concepts are valuable and should become a formal subsystem.

```text
Task starts
   ↓
Create checkpoint
   ↓
Agent changes files
   ↓
Verify
   ├── PASS → checkpoint becomes accepted
   └── FAIL → rollback / repair
```

Add:

- task-linked checkpoints,
- named checkpoints,
- diff statistics,
- selective file rollback,
- whole-task rollback,
- checkpoint retention policy,
- recovery after app restart.

---

# 14. Mobile Performance Requirements

The app must assume constrained hardware.

Requirements:

- never load an entire huge output stream into Compose state,
- paginate/virtualize large file lists,
- stream chat output incrementally,
- debounce high-frequency runtime events,
- cap retained terminal history,
- avoid blocking the main thread,
- expose CPU/RAM/disk indicators when useful,
- gracefully reduce background work under memory pressure.

The runtime should remain usable on mid-range 4–6 GB RAM Android devices.

---

# 15. Editor Roadmap

The editor is not Phase 1 priority. First make the agent reliable.

Then add:

- syntax highlighting,
- line numbers,
- tabs,
- search/replace,
- symbol navigation,
- diff editor,
- file rename/delete,
- undo/redo,
- large-file protection.

Avoid building a full desktop-class IDE editor before the agent architecture is stable.

---

# 16. MCP / Tool Ecosystem

Introduce an MCP manager after the core agent protocol stabilizes.

Required capabilities:

- add server,
- enable/disable,
- configure credentials,
- inspect tool list,
- per-project enablement,
- per-tool risk policy,
- health check,
- remove server.

The agent should receive a normalized tool catalog independent of the UI.

---

# 17. Testing Strategy

## Unit tests

Must cover:

- provider configuration,
- question serialization/deserialization,
- task-state transitions,
- risk classification,
- path validation,
- checkpoint manifest handling,
- event parsing,
- error classification,
- secret redaction.

## Integration tests

Must cover:

- runtime launch,
- stream parsing,
- process cancellation,
- process restart recovery,
- question pause/resume,
- tool approval/denial,
- checkpoint/rollback,
- provider failure handling.

## Device tests

At minimum validate:

- fresh install,
- first runtime setup,
- backgrounding during agent execution,
- app process recreation,
- low-memory conditions,
- network loss,
- provider timeout,
- long output session,
- large repository,
- destructive-command confirmation.

---

# 18. Phased Implementation Plan

## Phase 0 — Architecture Hardening

### Objective
Create stable boundaries before adding autonomous behavior.

### Deliverables

- Extract repositories/services from `MainViewModel`.
- Split `ClaudeRuntimeBridge` into focused components.
- Introduce domain-level agent event types.
- Introduce `AgentTaskStore`.
- Add task IDs and durable lifecycle states.
- Add structured logging.
- Add output limits and process cleanup.
- Add unit-test foundation.

### Acceptance criteria

- No UI component directly controls a native process.
- Runtime can be replaced behind the domain contract.
- A task can be restored after Activity recreation.
- Existing project/chat functionality remains intact.

### Implementation prompt

> Refactor Mobile Harness toward the target architecture in this PRD without changing user-visible behavior unnecessarily. First extract runtime/process/event responsibilities from MainViewModel and ClaudeRuntimeBridge. Introduce domain contracts and persistent task state. Preserve the existing PRoot/Claude execution path. Add tests for each extracted component. Do not add remote compute.

---

## Phase 1 — Safe Agent Interaction Core

### Objective
Make agent execution reliable and safe.

### Deliverables

- Replace implicit permission auto-approval with `AgentPolicyEngine`.
- Implement SAFE/REVIEW/HIGH/BLOCKED risk levels.
- Persist pending approvals.
- Add cancellation and recovery.
- Add secret redaction.
- Add process-group cleanup.
- Add configurable autonomous limits.

### Acceptance criteria

- No HIGH-risk operation can be silently approved.
- Pending approval survives app recreation.
- Stop/cancel leaves no orphan agent process.
- Provider errors become structured failure events.

### Implementation prompt

> Implement the Safe Agent Interaction Core from this PRD. Preserve the current runtime protocol but route every tool request through a durable policy engine. Remove implicit allow behavior. Persist approvals and task state. Add cancellation/recovery tests, secret redaction, and process cleanup. Treat PRoot as non-security-isolated and enforce application-level workspace/risk policies.

---

## Phase 2 — Agent Question System

### Objective
Give the agent the ability to ask structured questions with options.

### Deliverables

- `AgentQuestion` domain model.
- SELECT_ONE.
- SELECT_MULTIPLE.
- YES_NO.
- TEXT.
- NUMBER.
- PATH.
- CONFIRMATION.
- durable question state,
- inline Compose question card,
- recommended option support,
- answer persistence,
- resume execution after answer,
- notification/deep-link to pending question.

### Acceptance criteria

- Agent can pause on a question.
- App can be backgrounded safely.
- Question is restored after process recreation.
- Answer is delivered exactly once.
- Agent continues from the correct task state.
- User can see previous answers in chat history.

### Implementation prompt

> Implement the Agent Question System exactly as specified in this PRD. Build it as a runtime-independent protocol, not a Claude-specific UI hack. Persist questions and answers transactionally, render questions inline in the conversation, support all required question types, and guarantee exactly-once answer delivery and task resume across Android lifecycle events.

---

## Phase 3 — Project Memory + Context Engine

### Objective
Give the agent durable project understanding without flooding every prompt with the entire repository.

### Deliverables

- project memory store,
- architecture/decision/constraint documents,
- context selection engine,
- changed-file prioritization,
- recent task summary,
- memory editing UI,
- secret filtering.

### Acceptance criteria

- New task can recover useful project context without replaying all chat history.
- Memory never stores known secrets.
- User can inspect/delete memory.

### Implementation prompt

> Implement project memory and context management from this PRD. Build a compact local memory system that distinguishes verified project facts, user decisions, constraints, and agent observations. Integrate it into task context selection while enforcing strict secret redaction and user control over stored memory.

---

## Phase 4 — Autonomous Engineering Loop

### Objective
Move from chat-driven coding to verified autonomous software engineering.

### Deliverables

- planner/executor/verifier state machine,
- automatic build/test loop,
- failure diagnosis loop,
- retry budgets,
- checkpoint per task,
- structured verification results,
- automatic rollback on failed policy conditions.

### Acceptance criteria

A task such as "fix the failing login flow" can:

1. inspect the project,
2. form a plan,
3. edit code,
4. run tests/build,
5. diagnose failures,
6. patch,
7. retest,
8. stop when verified or request human input.

### Implementation prompt

> Implement the Autonomous Engineering Loop from this PRD. Build a durable state machine around planning, execution, observation, verification, diagnosis, repair, and completion. Use checkpoints and bounded retries. The agent must ask the user when confidence is insufficient or an irreversible decision is required, rather than endlessly retrying.

---

## Phase 5 — Verification & QA Engine

### Objective
Make the agent prove its changes work.

### Deliverables

- verification profiles,
- build/test runners,
- structured result parser,
- web-preview smoke testing,
- regression detection,
- verification timeline UI.

### Implementation prompt

> Implement the Verification and QA Engine. Detect the project's available validation commands, execute them under resource/time policies, parse results into structured verification events, and feed failures back into the autonomous loop. Do not mark a task complete solely because the agent claims success.

---

## Phase 6 — Model Gateway / OmniRoute Integration

### Objective
Make the agent runtime model-provider neutral.

### Deliverables

- normalized `ModelGateway`,
- Anthropic adapter,
- OpenAI-compatible adapter,
- OmniRoute-compatible configuration,
- model discovery,
- retry/fallback policy,
- latency/error metrics,
- per-task model policy.

### Implementation prompt

> Implement the Model Gateway abstraction from this PRD. Decouple agent orchestration from provider-specific environment variables. Support Anthropic-compatible and OpenAI-compatible gateways, including OmniRoute-style endpoints. Add model discovery, validation, normalized errors, bounded retries, and configurable fallback without changing the user-facing task model.

---

## Phase 7 — Git / Checkpoint Intelligence

### Objective
Turn existing rollback capability into a first-class task safety system.

### Deliverables

- named checkpoints,
- task-linked checkpoints,
- selective rollback,
- whole-task rollback,
- checkpoint timeline,
- accepted/rejected change state,
- recovery after restart.

### Implementation prompt

> Upgrade the existing checkpoint/diff system into the Git/Checkpoint Intelligence subsystem. Preserve compatibility with existing checkpoint data. Link checkpoints to agent tasks, verification results, and user approvals. Make rollback deterministic and safe under partial file changes.

---

## Phase 8 — MCP / Tool Ecosystem

### Objective
Make external capabilities installable and policy-controlled.

### Deliverables

- MCP manager,
- server configuration,
- tool discovery,
- project-level enablement,
- tool-level policy,
- health checks,
- secret handling.

### Implementation prompt

> Implement the MCP Tool Ecosystem. Add a provider-neutral tool registry and MCP manager. Every external tool must expose metadata, permissions/risk classification, health state, and configuration requirements. Integrate tools into the same AgentPolicyEngine used by native tools.

---

## Phase 9 — Mobile IDE Experience

### Objective
Improve direct human coding workflows after the autonomous core is stable.

### Deliverables

- editor improvements,
- multi-tab editing,
- search/replace,
- diff editor,
- symbol navigation,
- large-file handling,
- improved terminal UX,
- project explorer improvements.

### Implementation prompt

> Improve the Mobile IDE experience without compromising agent/runtime performance. Prioritize mobile ergonomics, large-file safety, fast navigation, and seamless transitions between agent-generated changes and manual edits. Reuse the domain repositories instead of adding new state directly to the ViewModel.

---

## Phase 10 — Local App / Device QA

### Objective
Let the phone test software running on itself where technically feasible.

### Deliverables

- APK build pipeline,
- install/run workflow,
- app smoke tests,
- crash/log collection,
- screenshot-based verification where practical.

This phase must respect Android package/security constraints and should only be implemented after the core agent loop is reliable.

### Implementation prompt

> Implement phone-local application QA only where supported by Android constraints. Build a controlled workflow for compiling, installing, launching, observing, and verifying test applications. Keep this isolated from the main agent lifecycle and require explicit policy for install/launch operations.

---

# 19. Non-Goals / Anti-Patterns

Do not:

- add remote compute before phone-first reliability is complete,
- put more logic into `MainViewModel` just because it is convenient,
- auto-approve tools to make demos look smoother,
- make the question system Claude-specific,
- persist secrets in task memory,
- treat PRoot as a security sandbox,
- allow unbounded terminal/output history,
- mark tasks successful without verification,
- make every agent decision a user question,
- build a giant editor before the runtime is stable.

---

# 20. Definition of Done for the Next Major Release

The release is considered architecturally successful when all of the following are true:

- [ ] Agent tasks have durable lifecycle state.
- [ ] Android lifecycle events do not lose active tasks.
- [ ] Tool permissions use a real policy engine.
- [ ] HIGH-risk operations require explicit confirmation.
- [ ] Agent can ask structured questions with options.
- [ ] Questions survive app backgrounding/recreation.
- [ ] Answers are delivered exactly once.
- [ ] Agent can resume after user input.
- [ ] Project memory is persistent and user-controllable.
- [ ] Autonomous build/test/fix loop exists.
- [ ] Verification is required for successful completion where applicable.
- [ ] Checkpoints are linked to tasks.
- [ ] Provider/model integration is abstracted from orchestration.
- [ ] OmniRoute-compatible gateway configuration is supported.
- [ ] Secrets are redacted from logs/history.
- [ ] Process cancellation cleans up descendants.
- [ ] Output streams are bounded.
- [ ] Unit/integration/device tests cover the critical lifecycle paths.
- [ ] Existing projects remain backward compatible.
- [ ] No remote compute dependency is required.

---

# 21. Recommended Implementation Order

```text
PHASE 0  Architecture Hardening
   ↓
PHASE 1  Safe Agent Interaction
   ↓
PHASE 2  Agent Questions ⭐
   ↓
PHASE 3  Project Memory
   ↓
PHASE 4  Autonomous Engineering ⭐⭐⭐
   ↓
PHASE 5  Verification / QA
   ↓
PHASE 6  Model Gateway / OmniRoute
   ↓
PHASE 7  Git / Checkpoint Intelligence
   ↓
PHASE 8  MCP Ecosystem
   ↓
PHASE 9  Mobile IDE
   ↓
PHASE 10 Local Device QA
```

**Reasoning:** Phase 2 is deliberately early because structured user interaction is the bridge between a chat agent and an autonomous agent. Phase 4 should not be attempted before durable task state, safe approvals, and user questions are reliable.

---

# 22. Engineering Quality Bar

Every phase must include:

1. architecture changes,
2. implementation,
3. migration/backward compatibility,
4. unit tests,
5. integration tests,
6. failure-path tests,
7. performance review,
8. security review,
9. documentation update,
10. manual device validation.

A feature is **not complete** when the happy path works. It is complete when restart, cancellation, timeout, offline mode, malformed provider output, partial file changes, and Android lifecycle interruptions are handled predictably.

---

# 23. Audit Conclusion

The current Mobile Harness foundation is strong enough to evolve without a rewrite. The important architectural move is to stop treating the app as **"Claude Code wrapped in Android UI"** and establish a stable **agent platform layer** underneath the UI.

The biggest immediate risks are:

1. implicit permission auto-approval,
2. in-memory agent/session state,
3. oversized runtime/ViewModel classes,
4. lack of a durable structured question protocol,
5. lack of a provider-neutral model gateway,
6. weak process/resource lifecycle guarantees.

Fix those in that order before investing heavily in secondary UI features.

The resulting product should feel less like a terminal app and more like a **mobile autonomous software-engineering environment** while remaining lightweight enough to run locally on an Android phone.
