# AGENTS.md - Complete Guide to the agentskill System

> **Purpose**: Teach any AI model to fully leverage the agentskill (ClaudeKit) system — from analyzing a user request to selecting the optimal skill/command/agent combination and executing end-to-end workflows.
>
> **How to use**: Read this file at session start. Use the Decision Engine (Section 3) to route every user request. Follow Workflow Patterns (Section 7) for execution.

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [System Architecture](#2-system-architecture)
3. [The Decision Engine](#3-the-decision-engine)
4. [Slash Commands Reference](#4-slash-commands-reference)
5. [Subagent Team Guide](#5-subagent-team-guide)
6. [Skills Catalog](#6-skills-catalog)
7. [Workflow Patterns](#7-workflow-patterns)
8. [Plan System](#8-plan-system)
9. [Memory System](#9-memory-system)
10. [Hooks & Automation](#10-hooks--automation)
11. [MCP Integration](#11-mcp-integration)
12. [Best Practices & Anti-Patterns](#12-best-practices--anti-patterns)
13. [Quick Reference Card](#13-quick-reference-card)

---

## 1. Introduction

The `agentskill/` directory is a **capability amplification system** for AI coding agents. It transforms a general-purpose AI into a specialized software engineering team through:

- **Slash commands** (`/cook`, `/plan`, `/fix`, etc.) — 60+ entry points that trigger structured workflows
- **Subagents** — 17 specialized agents (planner, tester, debugger, etc.) that work in parallel or sequence
- **Skills** — 35+ domain knowledge packages auto-activated by task type
- **Plan system** — persistent, multi-phase project tracking across sessions
- **Memory system** — file-based memory that persists user preferences and project context

**Core principle**: Never work ad-hoc. Always: **Analyze request → Assess complexity → Route to command → Activate skills → Execute with agents → Review → Deliver**.

---

## 2. System Architecture

```
agentskill/
├── agents/          # 17 subagent role definitions (.md files)
├── commands/        # 50+ slash commands organized in subdirectories
├── skills/          # 30+ skill packages (SKILL.md + references/ + scripts/)
├── workflows/       # 4 orchestration protocol files
├── hooks/           # Automation triggers (scout-block, notifications)
├── plans/           # Plan-driven development (timestamped directories)
├── AGENTS.md        # This file — AI routing guide
├── scripts/         # Helper scripts (catalog generators, env resolvers)
├── settings.json    # Shared project settings (hooks, statusline)
├── settings.local.json  # Personal settings (permissions, env vars)
├── metadata.json    # System version and metadata
├── active-plan      # Points to current working plan directory
└── .ckignore        # Patterns blocked by scout-block hook
```

| Component | Count | Role |
|-----------|-------|------|
| Agents | 17 | Specialized subagent roles (planner, tester, reviewer, etc.) |
| Commands | 60+ | Slash command entry points across 14 categories |
| Skills | 35+ | Domain knowledge packages with references and scripts |
| Workflows | 4 | Primary workflow, orchestration protocol, dev rules, docs management |
| Hooks | 4 types | Pre/post tool automation (scout-block, dev-rules, notifications) |

---

## 3. The Decision Engine

**Every user request must pass through this 5-step routing process.**

### 3.1 Step 1: Assess Complexity

| Level | Indicators | Scope | Default Action |
|-------|-----------|-------|----------------|
| **Simple** | 1 file, <50 LOC, obvious fix | <30 min | `/fix:fast` or direct edit |
| **Medium** | 2-5 files, logic change, some research | 30min-2hrs | `/cook:auto` or `/code` |
| **Complex** | 5+ files, architecture decision, unknowns | 2+ hrs | `/plan:hard` then `/code` |

**Rule**: If uncertain, assume **Complex** and use `/plan:hard`.

### 3.2 Step 2: Classify Task Type

```
User Request
│
├─ BUG/ERROR ──────────────────────────────────────────────
│  ├─ Type/compile error ────────────────→ /fix:types
│  ├─ Test failure ──────────────────────→ /fix:test
│  ├─ UI broken ─────────────────────────→ /fix:ui
│  ├─ CI/CD pipeline fail (fix now) ────→ /fix:ci <url>
│  │  └─ (plan only, don't fix) ───────→ /plan:ci <url>
│  ├─ Has error logs ────────────────────→ /fix:logs
│  ├─ Simple & obvious ─────────────────→ /fix:fast
│  ├─ Complex/unknown cause ────────────→ /fix:hard
│  └─ Multiple independent bugs ────────→ /fix:parallel
│
├─ NEW FEATURE ────────────────────────────────────────────
│  ├─ Need plan first ──────────────────→ /plan:hard → /code
│  ├─ Plan already exists ──────────────→ /code <plan-path>
│  ├─ Quick, no plan needed ────────────→ /cook:auto:fast
│  ├─ Full cycle with review ───────────→ /cook
│  ├─ Multi-team parallel ─────────────→ /cook:auto:parallel
│  └─ Need design/UI ───────────────────→ /design:good → /code
│
├─ REFACTOR ───────────────────────────────────────────────
│  ├─ Small cleanup ────────────────────→ /code:no-test
│  ├─ Architecture change ──────────────→ /plan:hard → /code
│  └─ Codebase-wide ────────────────────→ /review:codebase → /plan:hard
│
├─ DOCUMENTATION ──────────────────────────────────────────
│  ├─ Create from scratch ──────────────→ /docs:init
│  ├─ Update existing ──────────────────→ /docs:update
│  ├─ Full project walkthrough ─────────→ /docs:project
│  └─ Marketing content ────────────────→ /content:good
│
├─ RESEARCH/PLANNING ──────────────────────────────────────
│  ├─ Search codebase ──────────────────→ /scout or /scout:ext
│  ├─ Architecture decision ────────────→ /plan:hard
│  ├─ Compare 2 approaches ────────────→ /plan:two
│  ├─ Quick spec ───────────────────────→ /plan:fast
│  └─ Brainstorm ideas ────────────────→ /brainstorm
│
├─ DESIGN ─────────────────────────────────────────────────
│  ├─ High quality UI ──────────────────→ /design:good
│  ├─ Quick mockup ─────────────────────→ /design:fast
│  ├─ 3D/WebGL ─────────────────────────→ /design:3d
│  └─ From screenshot/video ────────────→ /design:screenshot or /design:video
│
├─ PROJECT SETUP ──────────────────────────────────────────
│  ├─ New project (guided) ─────────────→ /bootstrap
│  ├─ New project (auto) ──────────────→ /bootstrap:auto
│  └─ New project (fast parallel) ─────→ /bootstrap:auto:parallel
│
└─ GIT/DEPLOY ─────────────────────────────────────────────
   ├─ Commit ───────────────────────────→ /git:cm
   ├─ Commit & push ────────────────────→ /git:cp
   ├─ Pull request ─────────────────────→ /git:pr
   └─ Merge branches ───────────────────→ /git:merge
```

### 3.3 Step 3: Select Skills

| Task Type | Must-Have Skills | Nice-to-Have |
|-----------|-----------------|--------------|
| Bug Fix | debugging, code-review | sequential-thinking, scout |
| Feature | planning, code-review | research, problem-solving |
| Frontend | frontend-development, ui-styling | frontend-design, ui-ux-pro-max |
| Backend | backend-development, databases | devops, payment-integration |
| Mobile | mobile-development | frontend-design |
| Design | frontend-design, ui-styling | ai-multimodal, frontend-design-pro |
| DevOps | devops, chrome-devtools | backend-development |
| Research | research, docs-seeker | sequential-thinking |
| Docs | docs-seeker | repomix, document-skills |

**Skill synergies** (activate together for best results):
- `debugging` + `sequential-thinking` — systematic root cause analysis
- `code-review` + `sequential-thinking` — thorough quality checks
- `frontend-design` + `ai-multimodal` — design with generated assets
- `planning` + `problem-solving` — complex architecture decisions
- `research` + `docs-seeker` — comprehensive documentation research

### 3.4 Step 4: Choose Agents

See [Section 5](#5-subagent-team-guide) for the full agent catalog. Key routing:

| Need | Agent(s) |
|------|----------|
| Create implementation plan | `planner` |
| Research technologies/docs | `researcher` (parallel, max 2-3) |
| Find files in codebase | `scout` or `scout-external` |
| Implement code | `fullstack-developer` (parallel for multi-file) |
| Run tests | `tester` |
| Review code quality | `code-reviewer` |
| Debug issues | `debugger` |
| Update docs | `docs-manager` |
| Track project progress | `project-manager` |
| Commit/push | `git-manager` |
| UI/UX work | `ui-ux-designer` |
| Brainstorm solutions | `brainstormer` |
| Database work | `database-admin` |
| MCP tools | `mcp-manager` |
| Marketing copy | `copywriter` |
| Document incidents | `journal-writer` |

### 3.5 Step 5: Power Level Check

Commands have implicit power levels (0-5) controlling scope and risk:

| Level | Behavior | Examples |
|-------|----------|---------|
| 0 | Analysis only, no changes | `/plan:*`, `/scout`, `/brainstorm`, `/review:codebase` |
| 1 | Single change, minimal scope | `/fix:fast`, `/fix:types`, `/git:cm`, `/ask`, `/test` |
| 2 | Limited scope (1-3 files) | `/fix:hard`, `/fix:test`, `/cook:auto`, `/debug` |
| 3 | Moderate scope (3-8 files) | `/cook`, `/code`, `/plan:hard → /code` |
| 4 | Broad scope (8+ files) | `/bootstrap:auto`, `/docs:init` |
| 5 | Unrestricted | `/bootstrap:auto:parallel`, `/cook:auto:parallel` |

**Safety rules**:
- Default to level 0 for unknown contexts — explore first
- Commit before executing level >= 3
- Require explicit user approval for level >= 4
- Always pair level >= 3 with `code-reviewer` afterward

---

## 4. Slash Commands Reference

### Core Commands
| Command | Description | When to Use |
|---------|------------|-------------|
| `/ask` | Answer technical questions | Quick knowledge queries |
| `/cook` | Full dev cycle (research→plan→code→test→review) | Feature implementation with oversight |
| `/code` | Execute existing plan | Plan already created, ready to implement |
| `/debug` | Debug technical issues | Investigate errors, performance problems |
| `/fix` | Auto-route to specialized fix | Any bug/error (auto-detects complexity) |
| `/test` | Run tests and analyze | Validate implementation |
| `/brainstorm` | Brainstorm solutions | Explore ideas before committing |
| `/journal` | Document incidents | Technical difficulties, lessons learned |
| `/watzup` | Review recent changes | Session wrap-up, status check |
| `/use-mcp` | Activate MCP server tools | External tool integration |
| `/ck-help` | ClaudeKit usage guide | How to use agentskill features |

### Plan Commands
| Command | Description | When to Use |
|---------|------------|-------------|
| `/plan` | Intelligent plan creation | Auto-enhanced planning |
| `/plan:fast` | Quick plan, no research | Simple features, quick specs |
| `/plan:hard` | Deep research + planning | Complex features, architecture decisions |
| `/plan:two` | Compare 2 approaches | Need to evaluate trade-offs |
| `/plan:parallel` | Parallel-executable phases | Multi-team features |
| `/plan:ci` | Analyze CI/CD failures | GitHub Actions debugging |
| `/plan:cro` | CRO plan for content | Conversion rate optimization |

### Fix Commands
| Command | Description | When to Use |
|---------|------------|-------------|
| `/fix:fast` | Quick fix, small scope | Obvious bugs, single file |
| `/fix:hard` | Deep diagnosis + fix | Complex/unknown root cause |
| `/fix:test` | Fix failing tests | Test suite failures |
| `/fix:types` | Fix type errors | TypeScript/compile errors |
| `/fix:ui` | Fix UI/design issues | Visual bugs, layout problems |
| `/fix:ci` | Fix CI/CD pipeline | GitHub Actions failures |
| `/fix:logs` | Analyze logs + fix | Error log analysis |
| `/fix:parallel` | Fix multiple issues | Independent bugs in parallel |

### Cook Commands (Full Cycle)
| Command | Description | When to Use |
|---------|------------|-------------|
| `/cook` | Step-by-step with user oversight | Standard feature development |
| `/cook:auto` | Automatic (trust me bro) | Confident about approach |
| `/cook:auto:fast` | No research, scout+plan+code | Quick implementation |
| `/cook:auto:parallel` | Parallel fullstack agents | Large multi-component features |

### Other Commands
| Category | Commands |
|----------|---------|
| **Code** | `/code`, `/code:no-test`, `/code:parallel` |
| **Design** | `/design:fast`, `/design:good`, `/design:3d`, `/design:screenshot`, `/design:video`, `/design:describe` |
| **Content** | `/content:fast`, `/content:good`, `/content:enhance`, `/content:cro` |
| **Docs** | `/docs:init`, `/docs:update`, `/docs:summarize`, `/docs:project` |
| **Git** | `/git:cm`, `/git:cp`, `/git:pr`, `/git:merge` |
| **Bootstrap** | `/bootstrap`, `/bootstrap:auto`, `/bootstrap:auto:fast`, `/bootstrap:auto:parallel` |
| **Integrate** | `/integrate:polar`, `/integrate:sepay` |
| **Review** | `/review:codebase` |
| **Scout** | `/scout`, `/scout:ext` |
| **Skill** | `/skill:create`, `/skill:add`, `/skill:optimize`, `/skill:optimize:auto`, `/skill:fix-logs` |

---

## 5. Subagent Team Guide

### Development Agents
| Agent | Model | Role | Deploy When |
|-------|-------|------|-------------|
| `fullstack-developer` | sonnet | Implement code from plan phases | Parallel execution of plan phases |
| `tester` | haiku | Run tests, analyze coverage | After every implementation |
| `code-reviewer` | sonnet | Security, performance, quality review | After implementation + testing |
| `database-admin` | sonnet | Schema design, query optimization | Database-related features |

### Analysis Agents
| Agent | Model | Role | Deploy When |
|-------|-------|------|-------------|
| `researcher` | haiku | Research technologies, best practices | Before planning complex features |
| `scout` | haiku | Find files in codebase | Need to locate relevant code |
| `scout-external` | haiku | Search with external tools (Gemini, etc.) | Large/unfamiliar codebases |
| `debugger` | sonnet | Investigate errors, analyze logs | Bug investigation, performance issues |

### Planning Agents
| Agent | Model | Role | Deploy When |
|-------|-------|------|-------------|
| `planner` | **opus** | Create comprehensive implementation plans | Before any significant work |
| `project-manager` | haiku | Track progress, update roadmap | After completing phases |
| `brainstormer` | inherit | Evaluate approaches, debate trade-offs | Exploring solutions before committing |

### Content Agents
| Agent | Model | Role | Deploy When |
|-------|-------|------|-------------|
| `docs-manager` | haiku | Maintain technical documentation | After code changes |
| `copywriter` | sonnet | Marketing copy, landing pages | Content creation |
| `journal-writer` | haiku | Document incidents and learnings | Technical difficulties |

### Operations Agents
| Agent | Model | Role | Deploy When |
|-------|-------|------|-------------|
| `git-manager` | haiku | Commit, push, conventional commits | When user says "commit" or "push" |
| `mcp-manager` | haiku | Discover and execute MCP tools | External tool integration |
| `ui-ux-designer` | inherit | UI design, wireframes, responsive layouts | Frontend/design work |

### Orchestration Patterns

**Sequential** (tasks depend on each other):
```
planner → fullstack-developer → tester → code-reviewer → git-manager
```

**Parallel** (independent tasks):
```
┌─ researcher (topic A) ──┐
├─ researcher (topic B) ──┤→ planner (synthesize) → code
└─ scout (find files) ────┘
```

**When to parallelize**: Tasks touch different files, no shared dependencies, >2hrs total work.
**When to stay sequential**: File conflicts likely, tasks < 30min, outputs feed next step.

---

## 6. Skills Catalog

Skills are domain knowledge packages auto-activated by task type. Located in `agentskill/skills/*/SKILL.md`.

| Domain | Skills | Activate When |
|--------|--------|---------------|
| **AI & Multimodal** | `ai-multimodal` | Image/video/audio analysis or generation |
| **Backend** | `backend-development`, `databases`, `payment-integration`, `better-auth` | API, DB, auth, payment work |
| **Frontend** | `frontend-development`, `frontend-design`, `frontend-design-pro`, `ui-styling`, `ui-ux-pro-max` | React, Tailwind, component design |
| **Mobile** | `mobile-development` | React Native, Flutter, Swift, Kotlin |
| **3D/WebGL** | `threejs` | Three.js, WebGL, 3D visualization |
| **DevOps** | `devops`, `chrome-devtools` | Docker, CI/CD, browser automation |
| **Docs** | `docs-seeker`, `document-skills/*`, `repomix` | Documentation, PDF/DOCX/XLSX/PPTX |
| **Frameworks** | `web-frameworks`, `shopify`, `google-adk-python` | Next.js, Turborepo, Shopify, ADK |
| **Planning** | `planning`, `research`, `sequential-thinking`, `problem-solving` | Architecture decisions, complex analysis |
| **Quality** | `code-review`, `debugging` | Code review, systematic debugging |
| **Claude System** | `claude-code`, `mcp-builder`, `mcp-management`, `skill-creator` | Configuring agentskill itself |
| **Media** | `media-processing` | FFmpeg, ImageMagick, background removal |

**How skills activate**: Automatically loaded when task type matches. Or manually via reading `agentskill/skills/<name>/SKILL.md`. Use `docs-seeker` skill to fetch latest library/framework docs.

---

## 7. Workflow Patterns

### Pattern 1: `/cook` — Full Development Cycle
```
Research (parallel researchers) → Plan (planner synthesizes) → Implement (step-by-step)
→ Test (tester agent) → Review (code-reviewer) → Docs (docs-manager) → Git (git-manager)
```
**Use for**: Standard feature development with full quality gates.

### Pattern 2: `/plan:hard → /code` — Complex Features
```
Check active-plan → Parallel research (2 researchers) → Scout codebase
→ Planner creates plan.md + phase files → User reviews → /code executes phases
```
**Use for**: Architecture decisions, multi-day features, unknowns to resolve.

### Pattern 3: `/code` — Direct Implementation
```
Detect plan → Read phase → Extract tasks → Implement → Test → Review
→ User approval (BLOCKING) → Update plan status → Commit
```
**Use for**: Plan already exists, ready to implement a specific phase.

### Pattern 4: `/fix` — Intelligent Bug Routing
```
Classify issue type → Route to specialized fix command → Diagnose → Fix → Verify tests
```
**Use for**: Any bug/error. The router auto-selects `/fix:fast`, `/fix:hard`, `/fix:test`, etc.

### Pattern 5: `/cook:auto:parallel` — Multi-Team Execution
```
Research → /plan:parallel (dependency graph + file ownership) → Launch N fullstack-developer
agents in parallel → Integration → Test → Review → Docs → Git
```
**Use for**: Large features spanning frontend + backend + DB + DevOps.

### Parallel vs Sequential Decision

| Choose Parallel When | Choose Sequential When |
|---------------------|----------------------|
| Tasks touch different files | Tasks depend on previous output |
| Each task > 30 min | Total work < 2 hours |
| No shared state | Same file edits needed |
| Results can merge cleanly | Real-time feedback needed |

---

## 8. Plan System

Plans coordinate multi-phase work across sessions. Stored in `agentskill/plans/`.

### Directory Structure
```
plans/YYYYMMDD-HHmm-plan-name/
├── plan.md              # Overview (<80 lines): phases, status, risks
├── phase-01-name.md     # Phase details: requirements, steps, criteria
├── phase-02-name.md
├── research/            # Researcher agent reports
│   └── researcher-XX-report.md
├── reports/             # General reports
└── scout/               # Scout agent reports
```

### Active Plan Tracking
```bash
# Set active plan (auto-done by /plan commands):
echo "plans/20260325-1400-feature-name" > agentskill/active-plan

# Read at session start to resume work:
cat agentskill/active-plan
```

### Phase Gates
Each phase must pass: deliverables produced → acceptance criteria verified → user approval → blockers resolved. Do NOT advance until gate passes.

---

## 9. Memory System

Persistent file-based memory at `~/agentskill/projects/<project>/memory/`.

### What Goes in Memory
| Type | What to Store | Example |
|------|---------------|---------|
| `user` | Role, preferences, expertise | "Senior Kotlin dev, new to React" |
| `feedback` | Corrections and confirmations | "Don't mock DB in integration tests" |
| `project` | Goals, deadlines, decisions | "Merge freeze after March 5" |
| `reference` | Pointers to external resources | "Bugs tracked in Linear project INGEST" |

### What Does NOT Go in Memory
- Code patterns derivable from reading code
- Git history (use `git log`)
- Debugging solutions (fix is in the code)
- Anything already in CLAUDE.md
- Ephemeral task details

### Memory File Format
```markdown
---
name: memory-name
description: one-line description
type: user|feedback|project|reference
---
Content here. For feedback/project: rule, then **Why:** and **How to apply:** lines.
```

`MEMORY.md` is the index — contains only links to memory files, never content directly.

---

## 10. Hooks & Automation

Hooks trigger scripts at specific points in the workflow. Configured in `settings.json`.

| Hook | Trigger | Purpose |
|------|---------|---------|
| `scout-block` | PreToolUse (Bash/Read/Glob/Grep) | Block access to node_modules, .git, dist |
| `dev-rules-reminder` | UserPromptSubmit | Remind development rules before each task |
| `discord_notify` | Stop/SubagentStop | Send session completion to Discord |
| `telegram_notify` | Stop/SubagentStop | Send session completion to Telegram |

### Hook Types
- **PreToolUse**: Runs before a tool executes. Can block (exit code 2) or inject context.
- **PostToolUse**: Runs after a tool executes. Can suggest improvements.
- **UserPromptSubmit**: Runs when user sends a message.
- **Stop/SubagentStop**: Runs when session or subagent completes.

### Customization
- Edit `.ckignore` to add/remove blocked patterns for scout-block
- Add env vars in `agentskill/.env` or `agentskill/hooks/.env` for notifications
- Configure hooks in `settings.json` (shared) or `settings.local.json` (personal)

---

## 11. MCP Integration

MCP (Model Context Protocol) connects external tool servers to the agent system.

- **`/use-mcp`** command — discover and execute MCP server tools
- **`mcp-manager`** agent — manages MCP connections and tool discovery
- **`mcp-builder`** skill — guide for creating custom MCP servers (Python FastMCP or Node SDK)
- **`mcp-management`** skill — manage multi-server tool selection

**Use cases**: Database access, external API integration, custom tool servers, third-party services.

Configure in `.mcp.json` (see `.mcp.json.example` for template).

---

## 12. Best Practices & Anti-Patterns

### Do
- **Always assess complexity first** — route through the Decision Engine (Section 3)
- **Provide full context when spawning agents** — file paths, error messages, constraints
- **Use plan system for tasks > 2 hours** — prevents drift and enables session resumption
- **Commit before high-impact changes** — power level >= 3 needs a safety net
- **Update memory when discovering patterns** — helps future sessions
- **Parallelize only independent work** — different files, different domains
- **Review code after every implementation** — `code-reviewer` agent catches issues early
- **Follow YAGNI/KISS/DRY** — minimum complexity for current task

### Don't
- **Parallel agents editing same file** — causes overwrites, lost work
- **Spawn >6 agents for simple tasks** — coordination overhead > work saved
- **Plans without acceptance criteria** — can't tell when done
- **Agents spawned with no context** — "just fix the bug" wastes tokens re-exploring
- **Unbounded memory files** — archive completed sections, keep only living context
- **Skipping phase gates** — errors compound; always verify before advancing
- **Ignoring failing tests** — never comment out tests or change assertions to pass
- **High power level without commit first** — no rollback possible

---

## 13. Quick Reference Card

### 5-Step Routing Checklist
```
1. COMPLEXITY?  → Simple / Medium / Complex
2. TASK TYPE?   → Bug / Feature / Refactor / Design / Docs / DevOps / Research / Plan
3. COMMAND?     → Use Decision Tree (Section 3.2)
4. SKILLS?      → Use Selection Matrix (Section 3.3)
5. POWER LEVEL? → Check safety rules (Section 3.5)
```

### Common Scenarios
| Scenario | Command | Key Agents |
|----------|---------|-----------|
| Fix simple bug | `/fix:fast` | debugger |
| Fix complex bug | `/fix:hard` | debugger, scout |
| New feature (planned) | `/plan:hard` → `/code` | planner, fullstack-dev, tester |
| Quick feature (no plan) | `/cook:auto:fast` | — (main agent) |
| Full dev cycle | `/cook` | all relevant agents |
| Large parallel feature | `/cook:auto:parallel` | fullstack-dev (x N) |
| Setup new project | `/bootstrap:auto:parallel` | — |
| Refactor codebase | `/review:codebase` → `/plan:hard` | code-reviewer, planner |
| Search codebase | `/scout` or `/scout:ext` | scout agents |
| Research technology | `/brainstorm` or `/plan:hard` | researcher, brainstormer |
| Write documentation | `/docs:init` or `/docs:update` | docs-manager |
| Commit & push | `/git:cp` | git-manager |
| Create a PR | `/git:pr` | git-manager |

### Golden Rules
1. **Analyze before acting** — never jump to implementation without routing
2. **Plan complex work** — `/plan:hard` for anything > 2 hours
3. **Test everything** — `tester` agent after every implementation
4. **Review always** — `code-reviewer` catches what you miss
5. **Commit often** — safety net before risky changes
6. **Use memory** — save non-obvious learnings for future sessions
7. **Parallelize wisely** — only when tasks are truly independent
