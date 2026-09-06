# Mobile Harness — Post-PRD Universal Model Provider & Agent Runtime Implementation Prompt

**Status:** Implementation-ready
**Target developer/agent:** Darko
**Baseline:** Current `main` after the original Mobile Harness PRD phases 0–10 and the post-PRD terminal/permission/question work.
**Primary goal:** Make Mobile Harness model-agnostic so the user can run the same engineering workflow with OpenAI, Anthropic, OpenAI-compatible endpoints, gateways, and arbitrary supported model IDs instead of being effectively locked to Claude Code.

> **IMPORTANT:** This is a new post-PRD workstream. Do not redo completed PRD phases. Do not replace working terminal, permission, question, checkpoint, or task infrastructure. Integrate with the current implementation and preserve existing Claude Code behavior.

---

# 1. Mission

Mobile Harness currently has provider configuration for multiple protocols, including OpenAI Responses and OpenAI Chat, but the actual agent execution path is still Claude-specific.

The current architecture effectively does this:

```text
Android UI
   ↓
ProviderProfile
   ↓
RuntimeBridge
   ↓
RuntimeLaunchConfigBuilder
   ↓
/usr/local/bin/claude
   ↓
Claude Code
   ↓
Anthropic-compatible API
```

This means that adding `ProviderKind.OPENAI` alone is not sufficient. The provider configuration layer already knows about OpenAI, while the agent runtime still launches Claude Code and translates OpenAI providers into Claude environment variables.

The required evolution is:

```text
                         ┌─ Claude Code Runtime
                         │
User → Agent Orchestrator ├─ OpenAI Responses Runtime
                         │
                         ├─ OpenAI Chat Runtime
                         │
                         └─ Generic OpenAI-Compatible Runtime
                                      │
                                      ▼
                              Model Provider Layer
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                 ▼
                Anthropic          OpenAI        OpenAI-compatible
                endpoint           endpoint        gateway/custom
```

The core application must depend on normalized runtime/model contracts, not on Claude-specific APIs, environment variables, event formats, or CLI assumptions.

---

# 2. Current Repository Findings — DO NOT IGNORE

The implementation must start from the code that exists now.

## 2.1 `Models.kt`

Current provider model already contains:

```text
ProviderProtocol
├── CLAUDE_LOGIN
├── ANTHROPIC
├── ANTHROPIC_GATEWAY
├── OPENAI_RESPONSES
└── OPENAI_CHAT
```

`ProviderKind` already includes:

```text
CLAUDE
ANTHROPIC
LLM_ROUTER
OPENAI
KIMI
CUSTOM
```

`OPENAI` currently uses `OPENAI_RESPONSES` and `KIMI` uses `OPENAI_CHAT`.

Do not create a second unrelated provider enum. Evolve the existing model layer.

`ProviderProfile` currently stores:

```text
kind
baseUrl
model
hasSecret
```

Extend this only where necessary for a true provider/runtime contract.

## 2.2 `ProviderApiClient.kt`

This file already supports model discovery and connection validation.

It already distinguishes:

```text
OPENAI_RESPONSES → /models and /responses
OPENAI_CHAT      → /models and /chat/completions
other protocols  → Anthropic-style endpoints
```

The existing model parser accepts common `{data:[...]}`, `{models:[...]}`, array, `id`, `name`, and display-name forms.

Do not duplicate model discovery.

Refactor it into the new provider layer where appropriate.

Also fix provider-neutral wording such as the current validation success text referring specifically to “Claude Code settings”. A generic OpenAI model must not receive Claude-specific UI text.

## 2.3 `RuntimeBridge.kt`

The existing interface looks generic but `RuntimeLaunchConfigBuilder` is not generic.

For OpenAI protocols it currently requires a `localGatewayUrl` and maps the selected OpenAI model into:

```text
ANTHROPIC_BASE_URL
ANTHROPIC_MODEL
```

while still launching:

```text
/usr/local/bin/claude
```

This is the central architectural limitation to remove.

Do not make OpenAI work by pretending it is Anthropic forever.

## 2.4 `ClaudeRuntimeBridge.kt`

The current bridge directly:

- starts Claude Code,
- constructs Claude-specific command arguments,
- parses Claude stream-json events,
- watches Claude permission request files,
- handles Claude-specific runtime errors,
- emits the application's runtime events.

This implementation must become one runtime adapter, not the universal runtime.

Keep it working as `ClaudeCodeRuntime` (or an equivalent adapter) while extracting common orchestration and event normalization.

## 2.5 `AppPreferences.kt`

Provider selection is persisted through:

```text
provider_kind
provider_base_url
provider_model
```

API credentials are stored through `ApiKeyVault`.

Preserve existing saved-provider migration compatibility.

## 2.6 `ApiKeyVault.kt`

Credentials are stored using Android Keystore-backed AES/GCM.

Do not move secrets into plaintext SharedPreferences, project JSON, chat history, logs, model metadata, or runtime event payloads.

---

# 3. Product Definition

The feature should be called internally:

**Universal Model Provider & Agent Runtime**

The user experience should simply feel like:

```text
Choose Provider
      ↓
Choose / enter Model
      ↓
Start chat
      ↓
Agent works
```

The user should NOT have to care whether the backend is Claude Code, OpenAI Responses, Chat Completions, or an OpenAI-compatible gateway unless advanced settings require it.

The same project should be able to switch models/providers without changing project files or rebuilding the runtime.

Examples that should be representable:

```text
OpenAI → any available OpenAI model ID
Anthropic → any supported Anthropic model ID
OpenAI-compatible → arbitrary model ID
OpenRouter / OmniRoute-style gateway → arbitrary compatible model ID
Local/custom endpoint → arbitrary model ID when protocol-compatible
Kimi → existing OpenAI Chat-compatible flow
```

Do not hardcode a short whitelist of model names as the only mechanism. Manual model IDs must remain supported.

---

# 4. Architecture

Implement these layers:

```text
UI
 │
 ▼
ProviderRepository / ProviderManager
 │
 ▼
ProviderProfile + ModelDescriptor
 │
 ▼
AgentOrchestrator
 │
 ├── AgentRuntimeSelector
 │       │
 │       ├── ClaudeCodeRuntime
 │       ├── OpenAIResponsesRuntime
 │       ├── OpenAIChatRuntime
 │       └── GenericOpenAICompatibleRuntime
 │
 ▼
Normalized AgentEvent / ToolCall / ToolResult
 │
 ├── Question Manager
 ├── Permission Manager
 ├── Terminal / execution tools
 ├── Filesystem tools
 ├── Checkpoint/diff system
 └── Future Preview tools
```

Separate the concepts:

```text
Model Provider
= where/how the model API is reached

Agent Runtime
= how an agent loop is executed

Agent Orchestrator
= lifecycle, tools, questions, permissions, cancellation, persistence
```

Do not merge all three into one giant class.

---

# 5. Provider Contract

Create or evolve a provider-neutral model contract similar to:

```kotlin
data class ModelProfile(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val capabilities: ModelCapabilities,
    val contextWindow: Long? = null,
    val maxOutputTokens: Long? = null,
)
```

Capabilities should be explicit rather than assumed:

```text
streaming
textInput
imageInput
vision
toolCalling
parallelToolCalling
structuredOutput
reasoning
systemMessages
```

Unknown capability values must be represented as unknown rather than falsely claiming support.

The model selected by the user is authoritative. Do not silently replace it with another model.

---

# 6. Provider Protocols

Support these first-class execution protocols:

## 6.1 Anthropic Messages

Preserve current Anthropic behavior.

Use a dedicated adapter rather than forcing all providers through Claude Code.

## 6.2 OpenAI Responses

Implement a native OpenAI Responses adapter.

It must support, where the selected model/provider supports them:

- text input/output,
- streaming,
- tool definitions,
- tool calls,
- tool results,
- structured output where supported,
- reasoning output/events where available,
- image input where supported,
- usage information,
- errors and cancellation.

Do not require a local Anthropic translation gateway merely to use an OpenAI Responses provider.

## 6.3 OpenAI Chat Completions

Implement a native Chat Completions adapter for compatibility with providers such as Kimi and other compatible gateways.

Support:

- messages,
- streaming deltas,
- tool calls,
- tool results,
- system/developer messages where supported,
- usage,
- finish reasons,
- provider errors.

## 6.4 Generic OpenAI-Compatible

The user must be able to configure:

```text
base URL
API key
model ID
protocol = OpenAI Responses or OpenAI Chat
optional custom headers if genuinely required
```

Do not assume every compatible provider implements every OpenAI feature identically.

Capability negotiation and graceful degradation are required.

---

# 7. Normalized Streaming Event Contract

All model adapters must translate provider-specific streams into one internal event model.

Suggested event categories:

```text
RunStarted
TextDelta
ReasoningDelta
ReasoningSummary
ToolCallStarted
ToolCallDelta
ToolCallCompleted
ToolResultStarted
ToolResultCompleted
UsageUpdated
RunCompleted
RunFailed
RunCancelled
```

The UI must consume normalized events.

It must not contain logic such as:

```text
if Claude event...
if OpenAI event...
if Kimi event...
```

Provider-specific parsing belongs inside adapters.

Preserve the existing `RuntimeEvent` compatibility where practical, but extend/refactor it so provider-neutral consumers do not break.

---

# 8. Tool Calling — CRITICAL

The most important requirement is that changing models must not break the existing tool system.

Create a normalized tool contract:

```text
ToolDefinition
├── name
├── description
├── inputSchema
└── risk/capability metadata

ToolCall
├── id
├── name
└── arguments

ToolResult
├── toolCallId
├── content
├── success
└── metadata
```

The following existing/future tools must be runtime-independent:

```text
terminal / command execution
filesystem operations
question
permission
checkpoint / diff
project context
MCP tools
preview tools later
```

A GPT/OpenAI-compatible model must be able to call the same `ask_question` and `request_permission` concepts as Claude.

Do not implement separate Question/Permission systems for each provider.

---

# 9. Question + Permission Integration

The post-PRD Question and Permission systems are already separate and must remain separate.

## Question

```text
Model → ask_question
       ↓
QuestionManager
       ↓
UI / policy
       ↓
QuestionResult
       ↓
Model continues
```

Question types remain:

```text
SELECT_ONE
SELECT_MULTIPLE
YES_NO
TEXT
NUMBER
PATH
CONFIRMATION
```

Only `AUTO_RESOLVE` questions may use the 5-minute automatic recommendation behavior.

A `USER_REQUIRED` question must never be silently auto-resolved by a model adapter.

## Permission

```text
Model/tool request
      ↓
PermissionManager
      ↓
Policy evaluation
      ↓
ALLOW_ONCE
ALLOW_PROJECT
ALLOW_ALWAYS
DENY_ONCE
```

Permission scopes must remain capability-based rather than exact-command-based.

Existing permission security guarantees must not be weakened when moving from Claude to another model.

---

# 10. Agent Runtime Design

Create a common interface similar to:

```kotlin
interface AgentRuntime {
    val events: Flow<AgentEvent>

    suspend fun start(request: AgentRunRequest): String
    suspend fun submitToolResult(result: ToolResult)
    suspend fun submitQuestionAnswer(answer: QuestionAnswer)
    suspend fun submitPermissionDecision(decision: PermissionDecision)
    suspend fun cancel(sessionId: String)
}
```

Exact naming may differ if the repository already has a better abstraction.

The key rule is that `AgentOrchestrator` should not know how an individual provider serializes requests.

---

# 11. Claude Runtime Migration

Do NOT delete Claude Code support.

Refactor the current `ClaudeRuntimeBridge` into a `ClaudeCodeRuntime` adapter or equivalent.

Responsibilities that remain Claude-specific:

- launching `/usr/local/bin/claude`,
- Claude CLI flags,
- Claude stream-json parsing,
- Claude-specific environment variables,
- Claude-specific login behavior,
- Claude-specific error interpretation.

Responsibilities that must move out:

- generic task lifecycle,
- provider selection,
- common tool definitions,
- question handling,
- permission policy,
- normalized event delivery,
- generic model configuration,
- generic retry/cancellation policy.

This is the key architectural refactor.

---

# 12. OpenAI Runtime

Create an Android-side runtime that can execute the model loop directly over HTTPS rather than spawning Claude Code.

Preferred conceptual flow:

```text
Android
  ↓
OpenAI Agent Runtime
  ↓
HTTP client
  ↓
OpenAI Responses / Chat endpoint
  ↓
Model
  ↓
normalized stream
  ↓
Agent Orchestrator
  ↓
Tool call?
  ├─ no → continue/final response
  └─ yes
       ↓
   execute normalized tool
       ↓
   send ToolResult back to model
       ↓
   continue loop
```

Do not execute arbitrary model-generated commands directly from the HTTP adapter.

All tool execution must pass through the existing capability/permission infrastructure.

---

# 13. Tool Loop

Implement a robust model/tool loop:

```text
START
 ↓
Send context + available tools
 ↓
Stream model output
 ↓
Tool call(s)?
 ├── NO → finish
 └── YES
      ↓
 Validate tool schema
      ↓
 Permission/policy evaluation if required
      ↓
 Execute tool
      ↓
 Capture structured result
      ↓
 Append tool result
      ↓
 Continue model turn
```

Requirements:

- support multiple tool calls when the protocol/model supports them,
- preserve tool call IDs exactly enough to correlate results,
- reject malformed tool arguments safely,
- never execute a tool before validation/policy,
- cap tool-loop iterations,
- support cancellation,
- support timeouts,
- persist recoverable state where the existing task store supports it,
- prevent infinite loops,
- surface provider/tool failures as structured events.

Recommended configurable guardrails:

```text
max tool iterations
per-request timeout
overall run timeout
max tool result size
max context size
```

Use safe defaults appropriate for a phone.

---

# 14. Context Management

Do not reproduce the old Claude-specific approach of stuffing the entire conversation into a command-line argument.

The generic runtime should build structured request messages.

Context layers should be conceptually:

```text
System / agent instructions
Project context
Relevant conversation history
Current user request
Tool definitions
Previous tool results
```

Implement bounded history/context handling.

If the provider exposes a context-window limit, use it.

If not known, use a conservative configurable limit.

Implement compaction/truncation before request construction rather than allowing an oversized HTTP payload or argument list.

Do not silently drop the current user request.

---

# 15. Reasoning Support

Reasoning must be treated as an optional capability, not a provider assumption.

```text
Model supports reasoning
    ↓
normalize reasoning events

Model does not support reasoning
    ↓
normal text/tool loop continues
```

Do not fake chain-of-thought.

If a provider returns only a reasoning summary or reasoning metadata, display/store only the supported representation.

Do not expose hidden/private reasoning merely because a provider has internal reasoning fields.

---

# 16. Vision / Image Input

Vision/image support should be capability-driven.

If a model supports images:

```text
ChatAttachment
   ↓
provider-specific multimodal input
```

If it does not:

- clearly report unsupported input,
- do not silently discard the attachment,
- do not claim that vision was used.

This may be implemented after the core text/tool loop if needed, but the model capability contract should be designed for it from the beginning.

---

# 17. Provider Configuration UI

Reuse the existing `SettingsScreenModern.kt` provider UI instead of creating a parallel settings screen.

The existing UI already supports:

- provider selection,
- base URL,
- model entry,
- model discovery,
- model search,
- API key entry,
- connection validation.

Evolve it to represent the new architecture.

For each provider show only settings that actually apply.

Example:

```text
OpenAI
  API key
  Model
  [Discover models]

OpenAI-compatible
  Base URL
  API key
  Protocol: Responses / Chat
  Model
  [Discover models]

Anthropic
  API key
  Model
  [Discover models]

Claude subscription
  Existing Claude login flow
```

Do not require a fake “Pocket gateway” for direct OpenAI support.

The current `OPENAI` subtitle says “Runs through the Pocket gateway”; remove/update this once direct native OpenAI execution is implemented.

Similarly, Kimi should remain functional through its OpenAI Chat-compatible path.

---

# 18. Model Discovery

Keep the existing discovery mechanism and make it provider-neutral.

Requirements:

- discovery is optional,
- manual model ID always works,
- discovered models are selectable,
- exact IDs are preserved,
- refresh works,
- empty/unsupported model lists do not block manual entry,
- HTTP/auth errors are shown clearly,
- provider-specific endpoint differences are isolated in the provider adapter.

Do not make a provider unusable just because `/models` is unavailable.

---

# 19. Authentication and Secrets

Continue using `ApiKeyVault` / Android Keystore.

Rules:

- never log API keys,
- never include API keys in `RuntimeEvent`,
- never persist API keys in chat history,
- never put API keys into project files,
- never include API keys in crash/error messages,
- keep decrypted secrets in memory only for the duration needed,
- use provider-specific credential references rather than raw secrets in persistent model profiles.

If custom headers are added, distinguish secret headers from ordinary headers and never display secret values in diagnostics.

---

# 20. HTTP Transport

The current `ProviderApiClient` uses `HttpURLConnection` for lightweight discovery/validation.

For the long-lived streaming runtime, choose the smallest robust HTTP implementation already compatible with the project.

Do not add a large dependency stack without justification.

The transport must support:

- HTTPS,
- streaming response bodies,
- cancellation,
- connect/read/write timeouts,
- HTTP status handling,
- UTF-8 event parsing,
- connection cleanup,
- retries only where safe.

Do not blindly retry non-idempotent operations or tool-bearing requests in a way that can duplicate actions.

---

# 21. Streaming Protocol Handling

OpenAI-style streaming may use SSE/event streams and provider-specific event names.

Implement a resilient incremental parser:

```text
bytes
 ↓
UTF-8 incremental decoder
 ↓
event framing
 ↓
JSON event
 ↓
provider adapter
 ↓
normalized AgentEvent
```

Requirements:

- handle chunk boundaries splitting JSON/events,
- handle multiple events per network read,
- handle final events,
- handle provider error events,
- handle clean disconnects,
- handle cancellation,
- avoid assuming each network read is one event.

Do not parse a streaming response with `readText()` for the live agent path.

---

# 22. Error Normalization

Create provider-neutral error categories such as:

```text
AUTHENTICATION
AUTHORIZATION
RATE_LIMIT
BAD_REQUEST
MODEL_NOT_FOUND
CONTEXT_TOO_LARGE
TOOL_NOT_SUPPORTED
CAPABILITY_UNSUPPORTED
NETWORK
TIMEOUT
SERVER
STREAM_PROTOCOL
CANCELLED
UNKNOWN
```

Keep provider status/message details as diagnostic metadata, but show concise user-facing messages.

Examples:

```text
API key rejected
Model not found
Context is too large for this model
This model does not support tool calling
Provider rate limit reached
Network connection failed
Provider temporarily unavailable
```

Do not expose raw API keys or giant provider payloads in the UI.

---

# 23. Retry / Failover

The existing PRD calls for retries, fallback, and model aliases at the gateway layer.

Implement conservative retries in the new provider layer.

Safe retry candidates:

- transient network failures,
- selected 5xx errors,
- rate-limit responses when a usable retry delay is provided.

Do NOT automatically replay a tool-bearing model request if doing so could cause a tool to execute twice.

Fallback models/providers may be supported by the provider manager later, but the first implementation must not silently switch models unless an explicit fallback policy exists.

---

# 24. OmniRoute / Gateways

Treat OmniRoute-style endpoints as ordinary protocol-compatible providers.

Do not create a special-case “OmniRoute runtime”.

For example:

```text
ProviderProfile
  baseUrl = gateway endpoint
  protocol = OPENAI_RESPONSES or OPENAI_CHAT
  model = selected model ID
```

The same OpenAI adapter should handle it.

Anthropic-compatible gateways should use the Anthropic adapter where appropriate.

---

# 25. Project-Level Model Selection

A project should be able to use a selected provider/model without changing global configuration unexpectedly.

Implement the minimum persistence needed for:

```text
project → provider profile/model selection
```

If global provider selection is already the intended product behavior, do not invent complex per-project settings yet; instead ensure the runtime can switch cleanly between provider profiles.

The important requirement is that starting a session captures the selected provider/model as an immutable run configuration so changing Settings mid-run cannot mutate an active request.

---

# 26. Runtime Session Isolation

Each active agent session must have:

```text
sessionId
projectId
providerId
modelId
runtimeType
startedAt
status
```

The provider/model used for a run must be observable in diagnostics without exposing secrets.

An OpenAI run must never accidentally inherit a Claude process/environment.

A Claude run must continue using the existing Claude Code path.

---

# 27. Persistence / Recovery

Integrate with the existing durable task/session work.

Persist enough state to recover or clearly fail a model run after Android process recreation.

At minimum preserve:

```text
session ID
provider/model identity
run state
conversation/task linkage
pending question linkage
pending permission linkage
last normalized event / checkpoint needed for recovery
```

Do not persist raw secrets.

If full network stream replay cannot be safely recovered, mark the run `RECOVERABLE` or `FAILED` rather than pretending it is still running.

---

# 28. UI Runtime Independence

The chat UI should display common concepts:

```text
Assistant response
Reasoning summary/progress when available
Tool activity
Question
Permission
Error
Completion
```

It should not display provider-specific implementation details unless useful, e.g.:

```text
GPT / Claude / Kimi
model ID
provider
```

Avoid UI branches based on provider protocol wherever a normalized event can be used.

---

# 29. Backward Compatibility

Existing users must not lose:

- saved Anthropic configuration,
- Claude subscription flow,
- LLMrouter configuration,
- Kimi configuration,
- custom Anthropic-compatible endpoints,
- existing chats/projects,
- API keys stored in `ApiKeyVault`.

If the provider model schema changes, add explicit migration logic.

Do not silently reinterpret an old Anthropic-compatible custom endpoint as OpenAI.

---

# 30. Security Requirements

This feature expands the number of models that can control project tools, so security must become more centralized, not weaker.

Mandatory:

1. Every tool call goes through the normalized tool registry.
2. Every permission-sensitive tool goes through PermissionManager.
3. QuestionManager remains separate.
4. Model output is untrusted input.
5. Tool arguments are validated before execution.
6. Paths are canonicalized and checked against project boundaries where applicable.
7. Shell commands are never executed merely because a model emitted text resembling a command.
8. Secrets are never exposed to the model unless an explicit `SECRETS_USE` capability is granted.
9. Provider responses are treated as untrusted network input.
10. JSON parsing failures cannot crash the whole app.
11. Streaming parser failures must terminate the run safely.
12. No secret-bearing logs.

---

# 31. Do NOT Do These Things

Do NOT:

- simply add `OPENAI_API_KEY` and call it finished,
- route all OpenAI requests through fake Anthropic environment variables,
- require Claude Code to be installed for native OpenAI execution,
- create a separate Question implementation per provider,
- create a separate Permission implementation per provider,
- hardcode only GPT/Kimi model names,
- remove Claude Code support,
- remove Kimi support,
- replace the existing model discovery UI with a second screen,
- store API keys in plaintext,
- log request headers containing credentials,
- execute tool calls without the existing permission/policy layer,
- silently fall back to a different model,
- assume every OpenAI-compatible endpoint supports every feature,
- assume every streaming network read equals one JSON event,
- make the entire app depend on a giant provider-specific `when` statement,
- create another giant `MainViewModel`/`RuntimeBridge`.

---

# 32. Suggested Package Structure

Adapt names to the repository's existing conventions, but aim toward:

```text
runtime/
  AgentRuntime.kt
  AgentOrchestrator.kt
  ClaudeCodeRuntime.kt
  OpenAIResponsesRuntime.kt
  OpenAIChatRuntime.kt
  GenericOpenAICompatibleRuntime.kt
  RuntimeSelector.kt

provider/
  ModelProvider.kt
  ModelGateway.kt
  ProviderRepository.kt
  ProviderProtocolAdapter.kt
  OpenAIResponsesAdapter.kt
  OpenAIChatAdapter.kt
  AnthropicAdapter.kt
  ModelCapabilityDetector.kt
  ProviderErrorMapper.kt
  StreamingEventParser.kt

model/
  ProviderProfile.kt              # or evolve existing Models.kt
  ModelProfile.kt
  ModelCapabilities.kt
  AgentEvent.kt
  ToolCall.kt
  ToolResult.kt

network/
  ProviderApiClient.kt             # evolve existing implementation

```

Do not blindly create these exact files if an equivalent existing abstraction already exists. Prefer refactoring existing code.

---

# 33. Dependency Policy

Before adding a dependency:

1. inspect current Gradle dependencies,
2. check whether the functionality already exists,
3. choose the smallest maintained option,
4. avoid heavyweight SDKs that duplicate only a small amount of JSON/HTTP functionality,
5. keep ARM64 Android compatibility,
6. ensure streaming/cancellation works correctly.

The current app is intentionally lightweight. Do not turn a provider feature into a large SDK bundle.

---

# 34. Testing Requirements

Add unit/integration tests for the new architecture.

## Provider profile tests

- OpenAI Responses profile
- OpenAI Chat profile
- Anthropic profile
- custom OpenAI-compatible profile
- saved profile migration

## Model discovery tests

Given:

```json
{"data":[{"id":"gpt-example"}]}
```

expect the correct model ID.

Also test:

```json
{"models":[...]}
```

and malformed/empty responses.

## Streaming parser tests

Test:

- event split across chunks,
- multiple events in one chunk,
- text deltas,
- tool calls,
- tool arguments split across deltas,
- final event,
- error event,
- malformed event,
- disconnect.

## Tool loop tests

Test:

```text
model → text → finish
model → tool → result → text → finish
model → multiple tools → results → finish
model → malformed tool args → safe failure
model → permission required → wait → allow → resume
model → permission required → deny → resume/fail safely
model → question → wait → answer → resume
```

## Capability tests

A model without tool calling must not receive/expose executable tools as if supported.

A model without vision must not silently accept image input.

## Security tests

Verify:

- API key never appears in logs,
- API key never appears in RuntimeEvent,
- tool execution cannot bypass PermissionManager,
- path validation remains active,
- malformed provider JSON cannot execute anything.

## Claude regression tests

Existing Claude Code behavior must continue to work.

The refactor is not complete if Claude sessions regress.

---

# 35. Manual Device QA Matrix

On a real ARM64 Android device test at minimum:

### OpenAI Responses

```text
configure API key
select model
validate
start chat
stream response
run a safe tool
request permission
answer question
complete task
cancel task
```

### OpenAI Chat-compatible

Repeat the same flow with a compatible model such as the existing Kimi configuration.

### Anthropic

Verify existing direct API flow.

### Claude subscription

Verify existing Claude Code flow remains functional.

### Custom endpoint

Test a known-compatible endpoint with manual model ID.

### Lifecycle

During a run:

- background app,
- return to app,
- rotate/recreate Activity where applicable,
- cancel run,
- verify session state remains coherent.

---

# 36. Performance Requirements

Mobile Harness runs on phones, so avoid unnecessary memory use.

Requirements:

- stream responses incrementally,
- do not retain unbounded raw response bodies,
- cap diagnostic buffers,
- avoid duplicate copies of large conversation payloads,
- close network resources promptly,
- cancel HTTP streams when a run is cancelled,
- do not keep inactive provider connections alive indefinitely.

The model runtime should not block the Compose main thread.

---

# 37. Observability

Diagnostics should be useful without leaking secrets.

Safe diagnostic fields:

```text
provider kind
protocol
model ID
runtime type
session ID
HTTP status
latency
retry count
input/output token usage when available
```

Never log:

```text
API key
Authorization header
secret custom headers
full private prompts if they contain secrets
raw provider payloads containing credentials
```

---

# 38. Implementation Order

Implement in this order:

### Step 1 — Repository audit

Inspect the full current codebase and identify exactly which post-PRD terminal/permission/question components are now present.

### Step 2 — Extract normalized provider/runtime contracts

Do this before implementing OpenAI networking.

### Step 3 — Refactor Claude

Move Claude-specific behavior behind `ClaudeCodeRuntime` while preserving behavior.

### Step 4 — Build shared tool/event layer

Ensure Question/Permission/terminal/filesystem/checkpoint tools can be consumed by any runtime.

### Step 5 — Implement OpenAI Responses adapter

Native direct execution; no Claude Code dependency.

### Step 6 — Implement OpenAI Chat adapter

Reuse transport/event/tool infrastructure.

### Step 7 — Generic OpenAI-compatible configuration

Allow custom endpoint + protocol + model ID.

### Step 8 — Update provider settings UI

Reuse the existing Settings implementation.

### Step 9 — Persistence/recovery integration

Ensure run configuration and state survive lifecycle events.

### Step 10 — Tests + real-device QA

Run automated tests, then test every provider path on ARM64 hardware.

---

# 39. Definition of Done

This work is complete only when all of the following are true:

- [ ] Mobile Harness no longer treats Claude Code as the only practical agent runtime.
- [ ] OpenAI Responses can run directly without a Claude translation gateway.
- [ ] OpenAI Chat-compatible models can run directly through the common runtime layer.
- [ ] Arbitrary compatible model IDs can be entered manually.
- [ ] Existing model discovery continues to work.
- [ ] Existing Anthropic/LLMrouter/Kimi/Claude flows remain functional.
- [ ] Claude-specific code is isolated behind its runtime adapter.
- [ ] Provider-specific streaming is normalized.
- [ ] Provider-specific errors are normalized.
- [ ] Tool calls use a common schema.
- [ ] Question and Permission remain separate and runtime-independent.
- [ ] Permission policy cannot be bypassed by a new model adapter.
- [ ] `USER_REQUIRED` questions cannot be auto-resolved by the model.
- [ ] Tool loops have iteration/time/resource limits.
- [ ] Cancellation works.
- [ ] Context is bounded and no oversized command-line prompt is used by the generic runtime.
- [ ] API keys remain protected by Android Keystore.
- [ ] No credentials are logged.
- [ ] Model capability differences are handled explicitly.
- [ ] Existing chats/projects are preserved.
- [ ] Android lifecycle/background behavior is coherent.
- [ ] Automated tests cover parsing, tool loops, capabilities, errors, and security.
- [ ] Real-device QA passes for Claude + Anthropic + OpenAI Responses + OpenAI Chat-compatible + custom endpoint.

---

# 40. Final Engineering Principle

The objective is NOT:

> “Add an OpenAI provider to Claude Code.”

The objective is:

> **Turn Mobile Harness into a model-agnostic agent platform while keeping Claude Code as one supported runtime.**

The long-term architecture should allow this:

```text
                    Mobile Harness
                         │
                  Agent Orchestrator
                         │
              ┌──────────┴──────────┐
              │                     │
        Agent Runtime          Tool System
              │                     │
      ┌───────┼────────┐      ┌─────┼──────────┐
      │       │        │      │     │          │
   Claude   OpenAI   Generic  Bash  Question  Permission
   Code    Responses  OpenAI  FS    MCP       Checkpoint
                      Compat
```

A new model provider should require a new adapter, **not a rewrite of the app**.

A new tool should be usable by every compatible runtime, **not implemented separately for Claude/OpenAI/Kimi**.

That is the architectural end state this implementation must establish.
