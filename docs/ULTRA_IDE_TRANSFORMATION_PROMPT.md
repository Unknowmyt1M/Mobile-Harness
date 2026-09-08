# Mobile Harness — Ultra IDE Transformation Prompt

> **Purpose:** Master implementation prompt for transforming Mobile Harness from a capable AI coding workspace into a genuinely desktop-class, AI-native development environment on Android.
>
> **Repository:** `Unknowmyt1M/Mobile-Harness`
>
> **Instruction:** This document is meant to be given to a coding agent with access to the repository. It is intentionally comprehensive. Do not interpret it as a request for a superficial UI refresh. The goal is a coordinated product, UX, architecture, runtime, developer-tooling, AI-agent, performance, and reliability upgrade.

---

## 0. ROLE AND MISSION

You are the lead product engineer, Android engineer, systems engineer, UX architect, IDE engineer, terminal engineer, and QA lead for Mobile Harness.

Your mission is to evolve the existing application into:

> **Mobile Harness — an AI-native, desktop-class software development environment for Android phones.**

The product should make serious software development possible from a phone without feeling like a reduced toy version of a desktop IDE.

The target experience should combine the strongest ideas from modern desktop IDEs, mobile code editors, terminals, Git clients, AI coding agents, project managers, debuggers, build systems, and browser-based development environments — while being designed specifically for touch, limited screen width, Android lifecycle constraints, ARM64 hardware, and the existing rootless Linux/PRoot architecture.

Do not simply copy VS Code, Android Studio, Termux, Cursor, or any other product. Build a coherent Mobile Harness identity and interaction model optimized for a phone.

**Critical principle:** implement systems, not isolated mock screens. Every visible feature must connect to real application state and real capabilities wherever technically possible. Never create fake buttons, fake progress, fake terminal output, fake build success, fake diagnostics, or placeholder functionality presented as complete.

---

## 1. FIRST: AUDIT THE EXISTING REPOSITORY

Before changing code, perform a full repository audit.

Read and understand at minimum:

- `README.md`
- all Gradle configuration
- `app/src/main/java/com/jarves/mh/`
- `data/`
- `model/`
- `runtime/`
- `terminal/`
- `editor/`
- `gateway/`
- `loop/`
- `mcp/`
- `memory/`
- `network/`
- `policy/`
- `qa/`
- `security/`
- native C++ / JNI code
- Android services
- persistence/database layer
- existing Compose UI
- existing ViewModels/state holders
- WebView/preview implementation
- project/session lifecycle
- agent/Claude Code integration
- existing tests

Create an internal architecture map before implementation.

Do not delete working functionality merely because it is not part of the new design.

Refactor where necessary, but preserve working behavior unless the new architecture explicitly replaces it.

The current repository already has important foundations including Jetpack Compose UI, an ARM64 rootless Ubuntu/PRoot environment, Claude Code integration, Node.js/npm/Git, project workspaces, terminal sessions, an editor subsystem, web preview, checkpoints/diffs, secure credential handling, memory/MCP/gateway-related modules, and Android foreground/runtime infrastructure. Treat these as foundations to evolve rather than starting from zero.

---

## 2. PRODUCT NORTH STAR

The final product should feel like:

**Home screen → project → complete development workspace → AI agent + files + editor + terminal + preview + Git + diagnostics + builds.**

A user should be able to:

1. create/import a project;
2. understand its structure immediately;
3. ask the agent to work on it;
4. inspect and edit files manually;
5. run commands;
6. see live output;
7. run tests;
8. diagnose failures;
9. review AI changes;
10. accept/reject/rollback changes;
11. commit and push to Git;
12. run local servers and preview them;
13. install/manage language toolchains;
14. build real artifacts such as web applications and Android APKs where the device/runtime supports the required toolchain;
15. keep multiple tasks/sessions alive;
16. resume exactly where they left off.

The app should minimize context switching. Chat, files, editor, terminal, preview, Git, diagnostics, builds, and tasks must share one project context.

---

# 3. COMPLETE INFORMATION ARCHITECTURE

Redesign navigation around a **Project Workspace**, not four unrelated screens.

Recommended primary areas:

- **Home / Projects**
- **Workspace**
  - Agent
  - Files
  - Editor
  - Terminal
  - Preview
  - Problems
  - Output
  - Git
  - Tasks
- **Toolchains / Runtime**
- **Global Search / Command Palette**
- **Settings**

The exact navigation can be adapted after UX analysis. Do not blindly force every item into bottom navigation.

Use contextual navigation and a persistent project/workspace identity.

---

# 4. NEW HOME / PROJECT DASHBOARD

Replace a basic project list with a professional development dashboard.

Implement:

- recent projects;
- pinned projects;
- project search;
- project sorting;
- project groups/folders;
- last-opened timestamp;
- Git branch;
- dirty/clean status;
- running task indicator;
- running server indicator;
- active agent indicator;
- build status;
- test status;
- project language detection;
- framework detection;
- toolchain readiness;
- storage usage;
- quick actions;
- New Project;
- Import Project;
- Clone Git Repository;
- Open Workspace;
- Quick Project;
- duplicate project;
- archive project;
- export project;
- delete with confirmation;
- project recovery.

Each project card should communicate meaningful state instead of only displaying a folder name.

---

# 5. PROJECT ONBOARDING / PROJECT INTELLIGENCE

When opening a project for the first time, automatically inspect it.

Detect:

- language(s);
- package manager;
- build system;
- framework;
- entry points;
- scripts;
- Git repository;
- test framework;
- lint configuration;
- environment files;
- lockfiles;
- Docker files (informational only if unsupported);
- Android Gradle projects;
- Node projects;
- Python projects;
- C/C++ projects;
- PHP projects;
- static websites;
- Vite/Next/React/etc. where detectable.

Generate a compact **Project Health** view:

- toolchains ready/missing;
- dependencies installed/missing;
- build command;
- test command;
- dev server command;
- Git state;
- unresolved errors;
- warnings;
- disk usage;
- last build;
- last test run.

Never claim detection unless it is actually derived from the project.

---

# 6. FILE EXPLORER — COMPLETE REBUILD

The current Files UI is too close to a raw filesystem browser. Replace it with an IDE-grade project explorer.

Implement:

- project-root breadcrumb;
- collapsible folder tree;
- compact/list modes;
- optional grid mode;
- file-type icons;
- language icons where useful;
- Git status badges;
- modified/added/deleted/untracked indicators;
- ignored-file indication;
- hidden-file toggle;
- search within project;
- filename filtering;
- fuzzy filename search;
- sort by name/type/size/date/status;
- folder pinning;
- recent files;
- favorites;
- open editors section;
- changed files section;
- context menu;
- create file;
- create folder;
- rename;
- duplicate;
- move;
- copy;
- cut;
- paste;
- delete;
- restore where possible;
- share/export;
- import;
- open with;
- archive/extract where supported;
- file properties;
- file permissions where meaningful;
- copy path;
- copy relative path;
- reveal parent;
- open in terminal;
- open in editor;
- attach to agent;
- compare with previous version;
- compare two files.

Support long-press multi-selection and batch operations.

Make file actions touch-friendly but powerful.

---

# 7. MOBILE-FIRST CODE EDITOR — SERIOUS IDE CAPABILITY

Turn the existing editor subsystem into a real mobile code editor.

Implement or substantially improve:

- multi-tab editing;
- pinned tabs;
- tab overflow management;
- unsaved indicators;
- dirty-state tracking;
- autosave option;
- save/save-all;
- close/close-all;
- reopen closed tab;
- split-view where screen size permits;
- minimap optional;
- line numbers;
- current-line highlight;
- syntax highlighting;
- bracket matching;
- bracket auto-close;
- indentation;
- smart indentation;
- code folding;
- selection handles;
- multi-cursor if feasible;
- find;
- replace;
- replace all;
- regex search;
- case-sensitive search;
- whole-word search;
- go to line;
- go to symbol;
- document outline;
- breadcrumbs;
- symbol navigation;
- error/warning underlines;
- diagnostics gutter;
- code actions;
- formatting;
- configurable tab width;
- spaces/tabs;
- encoding display;
- line-ending display;
- language mode;
- read-only mode;
- large-file handling;
- undo/redo history;
- crash-safe recovery;
- external file change detection.

Build a mobile coding toolbar with context-aware actions:

`Undo | Redo | Find | Select | Comment | Brackets | Indent | Format | Save`

Do not permanently consume excessive screen space. The toolbar should adapt to the active language and keyboard state.

---

# 8. KEYBOARD-FIRST + TOUCH-FIRST UX

The application must work exceptionally well with both:

- touchscreen-only phones;
- Bluetooth/USB keyboards.

Add a shortcut system for common IDE operations.

Examples:

- Ctrl+P → Quick Open
- Ctrl+Shift+P → Command Palette
- Ctrl+P / fuzzy open
- Ctrl+F → Find
- Ctrl+H → Replace
- Ctrl+S → Save
- Ctrl+Shift+S → Save As where applicable
- Ctrl+Z / Ctrl+Shift+Z
- Ctrl+W → close tab
- Ctrl+Tab → next tab
- Ctrl+Shift+Tab → previous tab
- Ctrl+` → terminal
- Ctrl+Shift+F → global search
- Ctrl+Shift+G → Git/source control

Allow customization.

Do not rely only on keyboard shortcuts; every critical operation must remain reachable by touch.

---

# 9. COMMAND PALETTE / QUICK OPEN

Build a universal command system.

Features:

- fuzzy command search;
- recent commands;
- keyboard shortcut display;
- contextual commands;
- project actions;
- editor actions;
- Git actions;
- terminal actions;
- agent actions;
- build/test actions;
- runtime/toolchain actions.

Examples:

`Open File`
`Go to Symbol`
`Search in Files`
`Run Task`
`Run Tests`
`Build Project`
`Start Preview`
`Git Commit`
`Git Push`
`Open Terminal`
`Open Agent`
`Install Toolchain`
`Format Document`
`Toggle Hidden Files`
`Kill Process`
`Restart Server`

This becomes the mobile equivalent of a desktop IDE command palette.

---

# 10. GLOBAL SEARCH ENGINE

Implement fast project-wide search.

Capabilities:

- filename search;
- full-text search;
- fuzzy matching;
- regex;
- case sensitivity;
- whole word;
- include/exclude patterns;
- Git-aware ignore handling;
- result grouping by file;
- result preview;
- jump to line;
- replace in file;
- replace across project;
- search history.

For large projects, use indexing/caching rather than scanning the entire tree on every keystroke.

---

# 11. TERMINAL — TURN IT INTO A REAL MOBILE TERMINAL

The terminal must evolve into a first-class development surface.

Preserve the existing PTY/native architecture but improve the UX and lifecycle.

Implement:

- multiple terminal sessions;
- terminal tabs;
- named sessions;
- persistent sessions;
- session restore;
- split terminal where practical;
- maximize terminal;
- clear screen;
- scrollback control;
- copy selection;
- paste;
- select all;
- search terminal output;
- command history;
- history search;
- working-directory tracking;
- project-aware start directory;
- environment variable display/configuration;
- process list;
- kill process;
- interrupt process;
- restart command;
- terminal font size;
- terminal line spacing;
- cursor settings;
- dark/light terminal themes;
- ANSI color support;
- hyperlinks;
- clickable file paths;
- clickable line references;
- clickable URLs;
- output-to-file;
- export terminal log;
- terminal notifications for long-running jobs;
- background task integration.

**Do not retain a shell-selection UX merely for the sake of having one.** The terminal should launch the configured project Linux environment directly and expose advanced shell/environment configuration only where useful.

Make terminal errors actionable:

`Command failed → Open error details → Send to Agent → Fix → Re-run`

---

# 12. TASK / JOB MANAGER

Introduce a unified background task system.

Every long-running operation should be represented as a task:

- build;
- test;
- install package;
- Git clone;
- Git pull;
- Git push;
- npm install;
- Python environment creation;
- Android build;
- server;
- agent run;
- file operation;
- runtime installation.

Each task gets:

- status;
- progress where measurable;
- elapsed time;
- command;
- output;
- cancellation;
- retry;
- background/foreground state;
- resource usage where available.

Add a unified **Tasks** panel and unobtrusive background indicators.

---

# 13. OUTPUT + PROBLEMS SYSTEM

Create desktop-IDE-style unified diagnostics.

Output channels:

- Build
- Test
- Terminal
- Agent
- Git
- Runtime
- Preview
- System

Problems panel:

- errors;
- warnings;
- informational diagnostics;
- source file;
- line;
- column;
- message;
- severity;
- source/tool;
- quick action;
- send to agent.

Parse common compiler/build output patterns where reliable.

Tapping an error should open the exact source location when possible.

---

# 14. AI AGENT — MAKE IT THE CORE OF THE IDE

The AI system should not be a chat screen bolted onto an IDE.

It should operate as a project-native engineering agent.

Implement:

- project-aware context;
- file tree awareness;
- active editor awareness;
- selected-code awareness;
- terminal awareness;
- build/test awareness;
- Git awareness;
- diagnostics awareness;
- preview awareness;
- toolchain awareness;
- persistent conversation history;
- task history;
- agent checkpoints;
- resumable tasks;
- plan mode;
- implementation mode;
- review mode;
- debug mode;
- explain mode;
- refactor mode;
- test mode.

Agent tools should include, where permitted:

- read file;
- write file;
- edit file;
- search files;
- search code;
- list directory;
- run terminal command;
- start process;
- stop process;
- inspect process;
- run tests;
- run build;
- inspect Git diff;
- create checkpoint;
- restore checkpoint;
- inspect diagnostics;
- inspect preview;
- manage supported dependencies;
- inspect runtime/toolchains.

---

# 15. AGENT PLAN → EXECUTE → VERIFY LOOP

Implement a visible autonomous development loop.

Example:

`Understand request`
→ `Inspect project`
→ `Create plan`
→ `Ask only necessary clarification`
→ `Modify files`
→ `Run formatter`
→ `Run tests/build`
→ `Inspect errors`
→ `Fix`
→ `Re-run`
→ `Review diff`
→ `Summarize`

Expose this as structured progress, not a wall of chat text.

The user should be able to:

- pause;
- resume;
- cancel;
- inspect tool calls;
- approve risky operations;
- reject individual changes;
- revert a step;
- restore a checkpoint.

---

# 16. AGENT PERMISSION / SAFETY MODEL

Expand the existing policy/security system.

Classify actions:

### Safe
- read files;
- search;
- inspect Git diff;
- diagnostics;
- normal formatting.

### Review recommended
- installing dependencies;
- modifying many files;
- network commands;
- Git commit;
- deleting files.

### Explicit confirmation
- recursive destructive deletion;
- credential access;
- modifying security-sensitive files;
- force push;
- destructive Git reset;
- arbitrary privileged/root-like operations.

Make permissions understandable and contextual.

Never weaken the existing credential encryption, checksum verification, SAF integration, or security boundaries just to make a feature easier.

---

# 17. DIFF / CHANGE REVIEW — IDE-GRADE

Upgrade checkpoint/diff functionality.

Implement:

- unified diff;
- side-by-side diff where screen size allows;
- changed-file list;
- additions/deletions;
- per-hunk review;
- accept hunk;
- reject hunk;
- revert file;
- restore checkpoint;
- compare against HEAD;
- compare against previous checkpoint;
- compare against saved state;
- AI-generated change labels;
- generated vs manually edited indication where reliably trackable.

Agent changes must be reviewable before destructive actions.

---

# 18. GIT / SOURCE CONTROL CENTER

Build a real Git workspace.

Implement:

- current branch;
- branch switch;
- create branch;
- rename branch;
- delete branch with safeguards;
- status;
- changed files;
- staged files;
- stage/unstage;
- commit;
- commit message helper;
- history;
- commit details;
- diff;
- checkout/revert where safe;
- stash;
- stash list;
- stash apply;
- pull;
- push;
- fetch;
- remote list;
- remote configuration;
- clone;
- Git identity configuration;
- authentication integration;
- conflict detection;
- merge conflict UI;
- conflict file navigation;
- mark resolved;
- abort merge where supported.

Never hide destructive Git operations behind ambiguous UI.

---

# 19. BUILD SYSTEM / RUNNER

Introduce a unified project build runner.

Detect and expose project-native commands rather than hardcoding one ecosystem.

Support common patterns:

- Gradle
- Gradle Wrapper
- npm
- pnpm where available
- yarn where available
- Python
- CMake
- Make
- Composer/PHP
- custom scripts

Allow:

- run build;
- run clean;
- run test;
- run lint;
- run format;
- run custom task;
- save task configuration;
- task history;
- output capture;
- problem parsing;
- retry.

---

# 20. REAL ANDROID DEVELOPMENT STACK

The existing Android development stack currently installs Java/JDK but is not by itself a complete Android SDK build environment.

Transform it into a real Android development capability where technically feasible on supported ARM64 devices.

Support:

- OpenJDK 17;
- Android SDK management;
- SDK platforms;
- Android Build Tools;
- platform-tools where supported;
- Gradle Wrapper;
- Android Gradle Plugin compatibility;
- Kotlin projects through the Gradle toolchain;
- Android project detection;
- `assembleDebug`;
- `assembleRelease`;
- test tasks;
- lint;
- APK discovery;
- AAB discovery where supported;
- build logs;
- compiler diagnostics;
- signing configuration UX;
- keystore selection without exposing secrets;
- APK install/export/share actions where Android permits them.

Do not pretend that desktop-only tools are available on ARM64/PRoot. Detect architecture and capability accurately and provide graceful explanations.

Create a **Toolchain Manager** so large components are installed on demand rather than blindly consuming storage.

---

# 21. TOOLCHAIN MANAGER

Upgrade the existing development-stack model into a proper environment manager.

Stacks should be modular:

- Core Linux
- Node.js
- Python
- Java/JVM
- Android
- C/C++
- PHP
- Git
- optional utilities

For each stack show:

- installed;
- version;
- disk usage;
- health;
- update available;
- dependencies;
- install;
- repair;
- remove where safe;
- verify.

Use caching and incremental installation.

Never download a giant runtime if the requested task needs only a small component.

---

# 22. WEB PREVIEW — PROFESSIONALIZE IT

Upgrade the existing live preview system.

Implement:

- automatic port detection;
- server discovery;
- start/stop/restart;
- server list;
- live reload;
- refresh;
- desktop/mobile viewport modes;
- rotate viewport;
- open external browser;
- console logs;
- network errors;
- JavaScript errors;
- HTTP status visibility;
- copy URL;
- QR/share where useful;
- preview history;
- multi-server support.

Connect preview telemetry to the agent so the agent can diagnose runtime failures.

---

# 23. DEBUGGING WORKFLOW

Create a practical mobile debugging layer.

At minimum provide:

- terminal log inspection;
- build diagnostics;
- test failure navigation;
- runtime error capture;
- preview console errors;
- process status;
- environment inspection;
- recent command history;
- one-tap “Explain with Agent”;
- one-tap “Attempt Fix with Agent”.

Where true debugger support is technically feasible, design an extensible debugger abstraction rather than coupling the entire UI to one language.

---

# 24. DEPENDENCY / PACKAGE MANAGEMENT

Add project-aware dependency management.

Detect:

- `package.json`;
- Python requirements/pyproject;
- Gradle dependencies;
- Composer;
- CMake package configuration where detectable.

Show:

- installed dependencies;
- missing dependencies;
- scripts;
- outdated packages where reliable;
- install/update/remove actions;
- security/audit command hooks where available.

Do not silently run network-changing package operations without appropriate user visibility.

---

# 25. FILE ATTACHMENTS + AGENT CONTEXT

Make attachments first-class.

Users should be able to attach:

- files;
- folders;
- code selections;
- diffs;
- logs;
- screenshots;
- terminal output;
- diagnostics.

The agent should understand exactly what has been attached and why.

Provide context chips such as:

`index.html`
`server.py`
`Terminal error`
`Git diff`
`Build output`

Allow removing context before sending.

---

# 26. MEMORY / PROJECT KNOWLEDGE

Use the existing memory subsystem to create project-scoped engineering memory.

Store useful durable facts such as:

- architecture decisions;
- project conventions;
- build commands;
- important paths;
- user preferences for the project;
- known issues;
- previous failed approaches;
- deployment notes.

Provide a transparent memory UI.

Users must be able to inspect, edit, delete, and disable memory.

Never store secrets in memory.

---

# 27. MCP / EXTENSIBILITY

Upgrade the MCP subsystem into an extensibility layer.

Support where practical:

- MCP server configuration;
- tool discovery;
- enable/disable;
- per-project permissions;
- tool invocation logs;
- failures;
- timeout handling;
- cancellation;
- agent tool visibility.

Keep MCP failures isolated so a broken external tool cannot crash the workspace.

---

# 28. AI PROVIDER / MODEL EXPERIENCE

Improve provider configuration without coupling the UI to one vendor.

Support:

- provider profiles;
- endpoint configuration;
- model selection;
- model capability display;
- context limits where known;
- tool-calling capability;
- vision capability;
- streaming;
- fallback models;
- per-project model preference;
- connection test;
- usage/error status.

Credentials must remain protected by the existing secure storage model.

Do not expose API keys in logs, terminal output, crash reports, or UI diagnostics.

---

# 29. MULTI-AGENT / PARALLEL TASKS

Design the architecture for multiple concurrent agent tasks.

Examples:

- Agent A → frontend
- Agent B → tests
- Agent C → documentation

Prevent unsafe concurrent writes to the same files.

Use workspace locks, file ownership, checkpoints, or equivalent mechanisms.

The UI should show active agents and their scopes.

---

# 30. PROJECT SNAPSHOTS / RECOVERY

Implement robust local recovery.

Support:

- automatic checkpoints;
- manual checkpoints;
- before-agent-operation snapshot;
- before-large-refactor snapshot;
- restore;
- checkpoint naming;
- checkpoint timeline;
- disk cleanup policy;
- crash recovery;
- unsaved editor recovery.

Avoid unbounded storage growth.

---

# 31. PERFORMANCE ARCHITECTURE

This is an Android application. Do not implement desktop assumptions blindly.

Requirements:

- Compose recomposition discipline;
- lazy rendering;
- efficient file tree loading;
- background indexing;
- cancellable IO;
- streaming terminal output without UI overload;
- bounded log buffers;
- large-file safeguards;
- efficient diff rendering;
- task cancellation;
- process lifecycle handling;
- memory pressure handling;
- battery-aware background execution;
- foreground service integration where required;
- state restoration after activity recreation;
- rotation/configuration resilience;
- Android process death recovery.

Never block the main thread with filesystem, Git, network, process, indexing, or build operations.

---

# 32. MOBILE UI/UX DESIGN SYSTEM

Completely redesign the visual language if necessary.

Target aesthetic:

- premium;
- technical;
- modern;
- dense but readable;
- minimal wasted space;
- excellent dark mode;
- optional light mode;
- strong hierarchy;
- subtle motion;
- clear states;
- professional developer-tool identity.

Avoid:

- giant decorative cards everywhere;
- excessive rounded containers;
- oversized empty space;
- generic AI-chat aesthetics;
- childish gradients;
- excessive animations;
- desktop UI squeezed into a phone.

Use a coherent design system:

- typography scale;
- spacing scale;
- icon system;
- semantic colors;
- surfaces;
- borders/dividers;
- elevation;
- interaction states;
- motion principles;
- accessibility.

Create reusable Compose components instead of styling every screen independently.

---

# 33. WORKSPACE LAYOUT

The workspace should feel unified.

Possible structure:

```text
┌─────────────────────────────────────┐
│ ← Project     branch ●    ⋮         │
├─────────────────────────────────────┤
│ Context / tabs / active task        │
├─────────────────────────────────────┤
│                                     │
│          ACTIVE WORK SURFACE        │
│                                     │
├─────────────────────────────────────┤
│ Problems  Output  Terminal  Git     │
├─────────────────────────────────────┤
│ Agent  Files  Editor  Run  Preview  │
└─────────────────────────────────────┘
```

This is an example, not a rigid requirement. Optimize the final layout through actual UX reasoning.

Support adaptive layouts for:

- portrait phone;
- landscape phone;
- foldable/tablet if available;
- external keyboard.

---

# 34. CONTEXTUAL ACTION BAR

The action bar should change based on context.

In editor:

`Save | Find | Format | Run | Agent`

In file explorer:

`New | Search | Sort | Select | More`

In terminal:

`New | History | Search | Clear | Processes`

In Git:

`Refresh | Stage | Commit | Pull | Push`

In build output:

`Rerun | Stop | Problems | Send to Agent`

Do not fill the interface with permanent controls that are irrelevant to the current task.

---

# 35. NOTIFICATIONS / STATUS CENTER

Create a unified status center for:

- build finished;
- build failed;
- tests failed;
- server started;
- server stopped;
- agent waiting for approval;
- agent completed;
- toolchain installation completed;
- Git operation completed;
- runtime health problems.

Notifications must be actionable and deduplicated.

---

# 36. OFFLINE / DEGRADED MODE

The app should remain useful without network access.

Offline capabilities should include:

- file editing;
- terminal commands that do not need network;
- Git local operations;
- project browsing;
- local search;
- diffs;
- checkpoints;
- local builds if dependencies are already available;
- project diagnostics already available locally.

Clearly show when a feature requires network access.

---

# 37. SECURITY HARDENING

Preserve and strengthen existing security foundations.

Requirements:

- Android Keystore-backed credential encryption;
- no plaintext API keys;
- no secret leakage in logs;
- checksum verification for downloaded runtime artifacts;
- safe archive extraction;
- path traversal protection;
- command argument safety;
- explicit destructive-operation confirmation;
- project sandbox boundaries;
- MCP permission boundaries;
- agent permission policy;
- secure temporary files;
- safe cleanup;
- network failure handling;
- corrupted runtime recovery.

Remember that PRoot is userspace isolation, not a hardened VM/container. Do not describe it as stronger isolation than it actually provides.

---

# 38. ACCESSIBILITY

Support:

- content descriptions;
- scalable text;
- sufficient contrast;
- touch target sizes;
- keyboard navigation;
- reduced-motion preference;
- screen-reader sensible semantics;
- error states understandable without color alone.

---

# 39. ERROR UX

Never show raw exceptions as the primary UX.

Convert errors into:

**What happened**
**Why it happened**
**What you can do**

Example:

`Android SDK component missing`

→ `This project requires Android API 35.`

→ `Install required SDK components`

→ `Open Toolchain Manager`

→ `Retry Build`

Provide technical details behind an expandable section.

---

# 40. SETTINGS REBUILD

Create professional settings sections:

### Workspace
- default project directory;
- autosave;
- restore sessions;
- indexing;
- ignored files.

### Editor
- font;
- size;
- tabs;
- wrapping;
- minimap;
- syntax behavior;
- format-on-save.

### Terminal
- font;
- scrollback;
- cursor;
- shell/environment configuration.

### AI
- provider;
- model;
- permissions;
- context;
- memory;
- tool usage.

### Runtime
- Ubuntu environment;
- toolchains;
- storage;
- health;
- repair.

### Git
- identity;
- defaults;
- remotes;
- authentication.

### Security
- credentials;
- permissions;
- confirmations;
- privacy.

### Appearance
- theme;
- density;
- animations.

### Advanced
- logs;
- diagnostics;
- experimental features.

---

# 41. STORAGE MANAGEMENT

Because developer environments can consume many GB, create storage visibility.

Show:

- runtime size;
- SDK size;
- toolchain size;
- project size;
- build caches;
- npm caches;
- package caches;
- checkpoint storage;
- logs.

Provide safe cleanup actions with exact size estimates before deletion.

Never delete user source files as part of cache cleanup.

---

# 42. CRASH / RECOVERY ARCHITECTURE

The app must recover gracefully from:

- Android activity recreation;
- process death;
- app restart;
- interrupted downloads;
- interrupted builds;
- terminal process termination;
- corrupted state;
- partial toolchain installation;
- agent cancellation;
- network loss.

Use transactional state updates where appropriate.

---

# 43. TESTING REQUIREMENTS

Do not consider the project complete because the APK compiles.

Add/expand tests for:

### Unit
- project detection;
- file operations;
- search;
- diff;
- task state;
- Git state parsing;
- toolchain state;
- runtime markers;
- permissions;
- memory;
- agent state transitions.

### Integration
- terminal lifecycle;
- project open;
- editor save/reload;
- build runner;
- preview lifecycle;
- Git operations;
- agent tool calls;
- checkpoint restore.

### UI
- navigation;
- project creation;
- file operations;
- editor tabs;
- terminal;
- agent approval;
- build failure flow;
- Git conflict flow;
- settings.

### Stress
- large project;
- many files;
- large logs;
- many terminal sessions;
- long agent run;
- repeated build failures;
- low-memory conditions.

---

# 44. OBSERVABILITY / DEBUGGING

Create internal diagnostics that can be disabled in release builds.

Track locally:

- task durations;
- runtime failures;
- terminal lifecycle failures;
- editor crashes;
- build failures;
- preview failures;
- agent tool failures;
- storage problems.

Do not introduce hidden telemetry that violates the project's privacy-first model.

---

# 45. IMPLEMENTATION STRATEGY

Do NOT implement features as hundreds of disconnected patches.

Refactor toward coherent subsystems.

Suggested architecture layers:

```text
UI / Compose
    ↓
Workspace State + Navigation
    ↓
Application Services
    ↓
Project / Editor / Git / Task / Agent / Preview APIs
    ↓
Runtime + Native Process Layer
    ↓
PRoot Ubuntu Environment
```

Use interfaces around major subsystems so UI does not directly manipulate shell/process/filesystem internals.

Use a single source of truth for workspace state where practical.

Prefer event/state-driven architecture for long-running operations.

---

# 46. MIGRATION RULES

When changing existing models or persisted state:

- provide migration;
- preserve old projects;
- preserve conversations;
- preserve checkpoints;
- preserve credentials;
- preserve toolchain markers where compatible;
- avoid destructive database resets.

If old state cannot be migrated automatically, provide a recovery/export path.

---

# 47. UI STATE COVERAGE

Every major screen must design these states:

- loading;
- empty;
- populated;
- error;
- offline;
- permission required;
- operation running;
- operation completed;
- operation failed;
- destructive confirmation;
- first-use;
- partially configured;
- recovering.

Do not design only the happy path.

---

# 48. MICRO-INTERACTIONS

Use motion intentionally:

- tab open/close;
- task progress;
- file creation;
- diff expansion;
- bottom-panel expansion;
- agent tool execution;
- success/failure state.

Animations must be fast, subtle, interruptible, and battery-conscious.

---

# 49. FINAL FEATURE MATRIX

Before declaring completion, verify that the product has meaningful support for all of these categories:

- project management;
- project detection;
- file explorer;
- code editor;
- tabs;
- search;
- command palette;
- terminal;
- task manager;
- output;
- problems;
- Git;
- diffs;
- checkpoints;
- AI agent;
- agent tools;
- agent permissions;
- agent memory;
- MCP;
- provider management;
- build runner;
- test runner;
- package management;
- Android toolchain;
- Node toolchain;
- Python toolchain;
- C/C++ toolchain;
- PHP toolchain;
- live preview;
- debugging workflow;
- notifications;
- storage manager;
- runtime manager;
- offline/degraded mode;
- recovery;
- accessibility;
- keyboard support;
- adaptive layouts;
- performance safeguards;
- security hardening.

This list is a baseline, not a maximum.

If repository inspection reveals another missing subsystem required for a serious IDE, add it.

---

# 50. QUALITY BAR

The result must pass this mental test:

### A. New user
Can a new user understand what Mobile Harness is within 30 seconds?

### B. Developer
Can a developer open a real repository and immediately navigate, edit, search, run, build, test, and inspect it?

### C. AI workflow
Can the agent inspect → modify → test → diagnose → fix → review without forcing the user through disconnected screens?

### D. Terminal workflow
Can a power user work comfortably from the terminal without fighting the mobile UI?

### E. Recovery
Can the user safely recover from an agent mistake, failed build, interrupted task, or app restart?

### F. Mobile
Does it feel designed for a phone rather than a desktop UI squeezed into a phone?

### G. Desktop comparison
Would a developer genuinely choose Mobile Harness for some development tasks even when a computer is available?

If the answer is no, continue improving the relevant subsystem.

---

# 51. NON-NEGOTIABLE RULES FOR THE CODING AGENT

1. **Read before modifying.** Understand existing code and architecture first.
2. **Do not fake functionality.** A button is not a feature unless it works.
3. **Do not throw away working infrastructure unnecessarily.** Extend/refactor it.
4. **Do not create a UI-only prototype.** Wire features into real state and services.
5. **Do not block the Android main thread.**
6. **Do not leak credentials.**
7. **Do not weaken security to simplify implementation.**
8. **Do not silently delete user data.**
9. **Do not hardcode project-specific assumptions.** Detect capabilities dynamically.
10. **Do not assume x86 desktop binaries work on ARM64 Android.** Validate architecture.
11. **Do not claim Android builds are supported until an actual representative project builds successfully.**
12. **Do not claim a toolchain is installed until its health checks pass.**
13. **Do not leave placeholder TODO buttons in production UI.**
14. **Do not overload the UI with permanent controls.** Use contextual actions.
15. **Do not sacrifice performance for visual effects.**
16. **Do not sacrifice touch usability for desktop-style density.**
17. **Do not sacrifice power-user capability for simplicity.** Expose advanced controls contextually.
18. **Do not reset persisted user state during migration.**
19. **Do not stop after the first compile.** Run tests, inspect behavior, and fix regressions.
20. **Do not merely report what should be done. Implement it.**

---

# 52. EXECUTION LOOP

Follow this loop:

```text
1. Repository audit
2. Architecture map
3. Identify reusable infrastructure
4. Identify broken/incomplete systems
5. Define migration boundaries
6. Implement shared foundation
7. Implement workspace/navigation architecture
8. Implement UI design system
9. Implement project/file/editor systems
10. Implement terminal/task/output systems
11. Implement Git/diff/checkpoint systems
12. Implement agent/workflow systems
13. Implement toolchain/build systems
14. Implement preview/debugging systems
15. Implement settings/security/storage/recovery
16. Add tests
17. Build APK
18. Run automated checks
19. Perform manual UX audit
20. Fix regressions
21. Repeat until quality bar is met
```

Work in **large coherent feature batches**, not one tiny feature at a time, but keep commits and internal changes understandable.

---

# 53. DELIVERABLES

At the end, provide:

1. architecture summary;
2. changed modules;
3. new modules;
4. migration notes;
5. feature matrix;
6. supported toolchains;
7. known platform limitations;
8. test results;
9. build result;
10. APK output path;
11. performance observations;
12. security review summary;
13. remaining technical debt;
14. screenshots/UX evidence where available.

Do not claim completion for features that remain mocked, partially wired, or architecture-only.

---

# 54. DEFINITION OF DONE

Mobile Harness is considered successfully transformed only when:

- the new workspace UX is coherent;
- Files feels like an IDE project explorer;
- Editor feels like a serious mobile code editor;
- Terminal is a real persistent development terminal;
- Agent is deeply integrated with the project;
- Git is first-class;
- diffs/checkpoints are first-class;
- tasks/output/problems are unified;
- toolchains are discoverable and health-checked;
- supported Android projects can actually build when the required environment is available;
- live preview and diagnostics are integrated;
- state survives normal Android lifecycle events;
- destructive actions are protected;
- credentials remain secure;
- performance remains acceptable on realistic ARM64 phones;
- tests pass;
- release APK builds successfully;
- there are no obvious fake/placeholder controls;
- the application feels like **one development environment**, not a collection of separate screens.

---

# 55. FINAL PRODUCT VISION

Do not optimize for “more screens.”

Optimize for **development capability per interaction**.

The winning experience is:

> **Describe → inspect → edit → run → observe → fix → verify → review → ship.**

All from one Android workspace.

Mobile Harness should not merely imitate a desktop IDE.

It should use AI + mobile-native interaction + a real Linux runtime to create a workflow that can be **faster and more intelligent than a traditional desktop IDE for many development tasks**.

Build that product.
