---
name: retro
user-invocable: true
description: Run a retrospective with transcript analysis and log feedback. Use when the user explicitly invokes /retro, when a full plan implementation is complete, or after creating a PR or addressing code review feedback (if a retro hasn't happened yet this session).
---
# Retrospective

Run this skill when:
- The user explicitly invokes `/retro`
- A full plan implementation is complete (all work packages done and verified)
- After creating a PR (offer a quick retro, if a retro hasn't happened yet this session)
- After receiving and addressing code review feedback (offer, if a retro hasn't happened yet this session)

For the last two triggers, use a lightweight prompt: "Good moment for a quick retro. Want me to run `/retro`?" Do NOT auto-run — just offer. If the user declines, move on.

## Session Mode

Determine whether this is a **human-led** or **autonomous** session:
- **Human-led**: A human is actively participating in the conversation (they've been sending messages, answering questions, etc.)
- **Autonomous**: The agent is running independently (ticket agent, background task, no human interaction in the session)

This affects Steps 3 and 5 below. All other steps run the same regardless of mode.

## Steps

1. **Session time analysis**: Run the transcript analysis script to extract timing data. Do NOT write custom parsing code or use a subagent for JSONL extraction. Note the current time before starting — you'll record how long this analysis took in the retro log.

   **How to run the analysis:**
   - Find the transcript: glob `~/.claude/projects/<converted-cwd>/*.jsonl` sorted by modification time (convert cwd slashes to dashes, e.g., `/Users/me/myproject` → `-Users-me-myproject`). Pick the most recent.
   - Run the script: `python3 scripts/analyze_transcript.py <path-to-jsonl>` — the script lives in `scripts/` alongside this SKILL.md.
   - The script outputs: per-turn breakdown (full user text, assistant word count, tools, errors) and timing stats (reading at 150 wpm, typing at 60 wpm, 1 min buffer per turn, overlapping turns merged).
   - System-injected messages (skill injections, /mcp outputs, system reminders) are automatically filtered out.

   **What you do with the output:**
   - Read the turn-by-turn output to understand what happened
   - Group turns into high-level phases (plan, build, review, etc.) based on what was being worked on
   - Use the adjusted hands-on time as the hands-on metric
   - Identify pain points from the turn data: errors, user corrections, repeated tool calls

   Present as a time breakdown table with proportional bars and a metrics summary:

   | Started | Phase | 👤 Hands-On Time | 🤖 Agent Time | Problems |
   |---------|-------|-----------------|---------------|----------|
   | Feb 10 10:00am | Build (engine restart, voice recog, UI tweaks) | ██████ 60m | ███ 30m | ⚠ 5 fix cycles |
   | Feb 10 11:30am | Research (BT routing for AirPods + external mics) | | █████ 45m | |
   | Feb 10 1:00pm | Review (code review, docs, feedback log) | | ██ 15m | |

   **Format rules:**
   - **Started**: Date and wall-clock time when the phase began (from transcript timestamps)
   - **Bars**: Use █ blocks proportional to time (each █ ≈ 10min), followed by the minute label (e.g., `███ 30m`)
   - **Empty cells**: Leave the column blank if that role wasn't involved in the phase
   - **Problems**: Inline with ⚠ marker — only for phases that had real friction or rework
   - **Sort**: Chronological (by start time)
   - **Brevity**: Keep the table to 10 rows max. A 4-row table is better than a 12-row table.

   | Metric | Duration |
   |--------|----------|
   | Total wall-clock | X hours |
   | Hands-on | X hours (Y%) |
   | Automated agent time | X hours (Y%) |
   | Idle/away | X hours (Y%) |

2. **Key observations from transcript**: Before asking the user for feedback, identify patterns yourself:
   - Where did Claude work most independently? Why?
   - Where were the most user interactions needed? What caused them?
   - Were there avoidable back-and-forth cycles (bugs that better testing would have caught, unclear requirements that better planning would have resolved)?
   - What was the ratio of productive work to debugging/rework?

   In human-led mode, present these observations to the user as conversation starters. In autonomous mode, use them as input to Step 4. For each observation, also suggest what kind of action might address it (see Step 4 for action types).

3. **Gather feedback** (mode-dependent):

   **Human-led mode:** Ask the user in a single prompt:
   - What worked well in how we approached this?
   - What was frustrating or slower than expected?
   - Anything I should do differently?

   Wait for their response — don't assume or fill in answers.

   **Autonomous mode:** Skip this step. Use only the transcript observations from Step 2 as input to Step 5.

4. **Propose concrete actions**: For each issue identified (from your observations in Step 2 AND the user's feedback from Step 3 if human-led), investigate the right action and propose a specific deliverable.

   **4a. Launch CLAUDE.md review in parallel.** While investigating actions below, launch a Task agent (`general-purpose` type) to audit CLAUDE.md. In the prompt, include your key observations from Step 2 and the user's feedback from Step 3 (if human-led). The agent should:
   - Glob for all `**/CLAUDE.md` files in the project
   - Read each one and evaluate whether any sections need additions or updates based on the session observations and feedback you provided
   - Return specific proposed edits (section + exact change), not vague suggestions
   - If nothing needs changing, say so

   Call this Task in the same message as your first tool calls for 4b — they'll run in parallel naturally. Do NOT use `run_in_background`.

   **4b. Investigate actions for each issue.** Each action must be one of these types:

   | Action Type | When to use | What to do |
   |-------------|-------------|------------|
   | **Update a skill** | A skill's behavior caused the issue, or a skill should enforce a new practice | Read the skill's SKILL.md, propose the specific edit |
   | **Update CLAUDE.md** | A new rule or convention should be followed in all future sessions | Propose the specific addition to the relevant section |
   | **Update docs** | Architecture, decisions, or learnings are wrong/missing | Propose the specific edit to the doc |
   | **Create a ticket** | The fix requires implementation work beyond a doc/config change | Draft the ticket title + description |
   | **No action needed** | The issue was a one-off or already resolved | Explain why no systemic fix is needed |

   For each proposed action:
   1. Read the file you'd change (skill, CLAUDE.md, doc)
   2. Identify the specific section to edit
   3. Draft the exact change (not a vague suggestion)

   **4c. Merge CLAUDE.md review results.** When the subagent returns, incorporate its recommendations into your action proposals. Deduplicate with actions you've already proposed — if the subagent suggests something you've already covered, note it as reinforcement rather than listing it twice.

   **Example — good:**
   > Issue: Agent didn't check learnings.md before writing HTTP client code.
   > Action type: Update CLAUDE.md
   > File: CLAUDE.md → "Before Making Changes" section
   > Change: Add "Check `docs/process/learnings.md` when writing code that touches external services"

   **Example — bad:**
   > Issue: Agent didn't check learnings.md before writing HTTP client code.
   > Action: "We should remember to check learnings next time."

5. **Approve and execute actions** (mode-dependent):

   **Human-led mode:** Present all proposed actions (from 4b and 4c) to the user and ask which ones they'd like to take. Execute approved actions; skip declined ones.

   **Autonomous mode:** Execute actions that are low-risk and reversible (doc updates, learnings additions, ticket creation). Do NOT execute actions that change skill behavior or CLAUDE.md without human review — log these as "proposed but deferred" in the retro entry.

6. **Log to `docs/process/retrospective.md`** using this format:
```markdown
   ## YYYY-MM-DD - [Brief context of what we worked on]

   ### Time Breakdown
   | Started | Phase | 👤 Hands-On Time | 🤖 Agent Time | Problems |
   |---------|-------|-----------------|---------------|----------|
   | ... | ... | ... | ... | ... |

   ### Metrics
   | Metric | Duration |
   |--------|----------|
   | Total wall-clock | X hours |
   | Hands-on | X hours (Y%) |
   | Automated agent time | X hours (Y%) |
   | Idle/testing/away | X hours (Y%) |
   | Retro analysis time | X min |

   ### Key Observations
   - [Patterns identified from transcript]

   ### Feedback
   **What worked:** [User's feedback, or "N/A — autonomous session" if autonomous]
   **What didn't:** [User's feedback, or "N/A — autonomous session" if autonomous]

   ### Actions Taken
   | Issue | Action Type | Change |
   |-------|-------------|--------|
   | [Issue description] | Skill / CLAUDE.md / Doc / Ticket | [Specific change made or ticket created] |
```

7. **Elevate to learnings**: Review the session for things worth adding to `docs/process/learnings.md`:
  - Technical gotchas or surprises
  - Patterns that worked well
  - Mistakes to avoid repeating
  - API quirks, environment issues, or tooling discoveries

   **Propose specific additions**, e.g.:
   > "Based on this session, I'd suggest adding to learnings.md:
   > `## [Category]`
   > `- [Specific learning]`
   > Want me to add it?"

   Don't just ask "anything to add?" - identify candidates yourself.

8. **Commit** all retro changes (actions, retrospective log, learnings) with message: `docs: retro for [brief context]`

9. **Confirm** what was logged and what actions were taken.
