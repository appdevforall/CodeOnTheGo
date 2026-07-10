# How we ran the ADFA-4128 spike so far

TODO: Paste the full transcript in here in case anyone wants to review for fun

> A worked example of how one person + an agent turned a tech-heavy ticket into a spawnable set of product + technical decisions in ~2.5 days. Each stage below names **what we tried to de-risk**, **how I steered** (with the actual prompts I used, tagged to the [Claude Code best practices](https://code.claude.com/docs/en/best-practices) they lean on), **what the agent did alone and how good it was**, and **what we learned**.

# 2026-07-07 Evening

## 1. Frame the problem (initial prompt)

- **De-risk:** Is the ticket's "Son of Stubby" actually the right framing? The ticket was tech-heavy, light on product goals.
- **Steer:** Pasted the ticket, then widened it — *"this is only two proposed approaches; the actual desire is quick edit→run turnaround… reflected in a running app within 1s if possible."* Reframed from *a solution* to *a goal*, and set the headline target up front: a change should show in a running app in **under ~1s**. Giving the agent a concrete number to optimize against is the ["provide specific context"](https://code.claude.com/docs/en/best-practices#provide-specific-context-in-your-prompts) practice — and pasting the raw ticket + letting it read source before proposing anything is ["explore first, then plan, then code"](https://code.claude.com/docs/en/best-practices#explore-first-then-plan-then-code).
- **Agent, alone:** Pulled the ticket + David's original "Stubby" page, mapped the two proposals, scoped a prototype. Good — correctly separated the mechanism from the outcome.
- **Learned:** The real target is the *loop*, not any one mechanism. Set up everything downstream.

## 2. Prove the mechanism (set a wildly ambitious, but specific goal, and get a goal loop going)

- **De-risk:** Can a shell app actually load a user app's DEX + resources with no install, and does it survive real app shapes?
- **Steer:** A long leash with a walk-away — *"Please get as far as you can on the fast edit/debug loop… I'm going to sleep, will check in in the morning,"* later reinforced with *"keep going until you have no more open questions."* Overnight autonomy only works if the agent can check its own work, so the steer pairs with the standing push-gate + an on-device capability matrix it had to actually produce — the ["give Claude a way to verify its work"](https://code.claude.com/docs/en/best-practices#give-claude-a-way-to-verify-its-work) practice, applied to a walk-away run.
- **Agent, alone (~3 h):** Built the shell, got DEX + resource hot-load working on-device, then tested androidx/Material3/Compose/Fragments/native/multi-Activity and logged blockers. Strong — the core worked and the capability matrix was real, not claimed.
- **Learned:** The mechanism is sound across app shapes; the cost is compilation, not loading (~40 ms reload).

# 2026-07-08

## 3. Handle the hard cases + design the loop

- **De-risk:** Where does the mechanism break, and how do we get to a warm, tiered loop?
- **Steer:** Targeted, source-able probes rather than open-ended asks — *"test all these other common cases and see where you hit blockers," "explain instrumentation-based Activity interception,"* and *"is there a way to make the 1s loop significantly faster so it works on slower devices?"* Narrow, answerable questions keep the research honest ([provide specific context](https://code.claude.com/docs/en/best-practices#provide-specific-context-in-your-prompts)); the agent fanned each out into its own investigation ([use subagents for investigation](https://code.claude.com/docs/en/best-practices#use-subagents-for-investigation)).
- **Agent, alone (~2 h):** Researched VirtualApp/instrumentation, designed the persistent warm-compile service and the 3-tier reload, analyzed slow-device impact. Good synthesis; produced the tier design we still use.
- **Learned:** A tiered loop (resource edits skip the compiler) is the right structure; slow devices are the real risk to name.

## 4. Push on robustness + kill a tempting path (derisk more things)

- **De-risk:** Can method-body hot-swap (JVMTI) give us sub-second code edits — and is the loop robust?
- **Steer:** *"Can you figure out how to make Tier 1 more robust?"* then *"Make robust please. And run a code review pass on the work so far."* The code-review pass is a deliberate ["adversarial review step"](https://code.claude.com/docs/en/best-practices#add-an-adversarial-review-step) — having the agent critique its own diff before I did.
- **Agent, alone (~1.5 h):** Root-caused JVMTI's limits (body-only, no repaint), ran an 18-finding self-review, fixed edit-loss bugs. Good — and notably *honest*: it demonstrated the limitation rather than asserting it, which is what let us drop the path with confidence.
- **Learned:** JVMTI/JRebel-style hot-swap isn't worth it; full reload at 0.5–3 s wins. (Later confirmed: it's exactly the wall Compose Live Edit and Google's abandoned Instant Run hit.)

# 2026-07-09

## 5. Prove it end-to-end with an ambitious demo (designed to break things)

- **De-risk:** Does the whole loop hold together for a real user building a real app?
- **Steer:** *"Demonstrate this using Claude prompts only — blank app → a lemonade-stand game."* Then, mid-demo, the highest-leverage message of the whole spike: **"You've been tweaking stuff. Have you weakened the use case? Does this still handle building arbitrary apps?"** That's ["course-correct early and often"](https://code.claude.com/docs/en/best-practices#course-correct-early-and-often) — catching drift while it's cheap to fix rather than at the end.
- **Agent, alone (~3 h):** Built the demo, and that challenge caught a real drift — a demo convenience had quietly constrained the payload's programming model. The agent re-architected (controls into window sub-windows) to restore full fidelity, and added a self-healing build loop. Good recovery; wouldn't have happened without the challenge.
- **Learned:** The loop works for a real build; **guarding the invariant ("still a real app?") is the human's job**, not delegable.

## 6. Move the toolchain on-device + benchmark (fix agent oversight / missed product requirement)

- **De-risk:** Does the compile loop actually run *on the phone* (not just the Mac), and how fast?
- **Steer:** *"The warm compile service and d8 and aapt2 need to run on device. And we want benchmark numbers."* Demanding measured numbers (not "it should be fast") is again ["give Claude a way to verify its work"](https://code.claude.com/docs/en/best-practices#give-claude-a-way-to-verify-its-work) — the benchmark *is* the verification, and it's what surfaced the on-device cost honestly.
- **Agent, alone (~50 m):** Staged the toolchain on the A56, produced the benchmark ladder. Good — real on-device numbers, honest about the cold-start cost.
- **Learned:** Full on-device loop is real; resource edits ~0.75 s, code ~1–3 s — but full compile balloons with app size, foreshadowing stage 7.

## 7. Critique, then chase the real bottleneck (push boundaries on app size / complexity)

- **De-risk:** Will this stay fast for *large, real* apps?
- **Steer:** Two sharp moves. First, **"critique my request before executing"** when I asked for a LoC-ladder benchmark — turning an [adversarial review step](https://code.claude.com/docs/en/best-practices#add-an-adversarial-review-step) onto *my own instruction*, not just the agent's output. Second, a source-able question: *"is there no support already for incremental compile?"*
- **Agent, alone (~1.75 h):** The critique showed my benchmark-as-specified would have measured the *absence of incremental compilation*, not the design — so we re-sequenced. Then it integrated Kotlin's own incremental engine, verified output byte-for-byte against clean builds, tested under memory pressure, and proved the hybrid dependency model against CoGo's real offline repo. Excellent — the spike's strongest result came directly from the request-critique.
- **Learned:** Incremental compile keeps edits flat (~0.5 s) regardless of app size; real dependencies work by consuming CoGo's resolved classpath, not reimplementing Gradle.

# 2026-07-10

## 8. Summarize to facilitate team discussion

- **De-risk:** Can others act on this in parallel without me?
- **Steer:** *"If you chose a different structure, please compare and let's discuss — don't just take my advice as correct."* Same adversarial-review reflex applied to *document structure*: I gave a strawman (A/B/C), asked the agent to argue against it, and we landed on goals → priorities (A) × options+evidence (B) → decision register (D).
- **Agent, alone (~35 m):** Proposed and built the discussion-doc structure; iterated on my feedback. Good — the A×B→D split is reusable beyond this ticket.
- **Learned:** Separating *priorities* from *evidence* and joining them explicitly in decisions is what makes a spike spawnable into parallel workstreams.

---

## The steering moves, and the best practices behind them

The whole spike used a small set of repeatable moves. Each maps to a documented [Claude Code best practice](https://code.claude.com/docs/en/best-practices):

| My move                                              | A prompt I actually used                                     | Best practice                                                |
| ---------------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| Reframe solution → goal, with a number               | "…the actual desire is quick edit→run turnaround… within 1s if possible" | [Provide specific context](https://code.claude.com/docs/en/best-practices#provide-specific-context-in-your-prompts) |
| Read the source before proposing                     | "Pull the ticket + the original Stubby page first"           | [Explore first, then plan, then code](https://code.claude.com/docs/en/best-practices#explore-first-then-plan-then-code) |
| Long leash + walk-away, gated                        | "Get as far as you can… I'm going to sleep"; "keep going until you have no more open questions" | [Give Claude a way to verify its work](https://code.claude.com/docs/en/best-practices#give-claude-a-way-to-verify-its-work) |
| Demand measured numbers, not claims                  | "…and we want benchmark numbers"; "verify output against a clean build" | [Give Claude a way to verify its work](https://code.claude.com/docs/en/best-practices#give-claude-a-way-to-verify-its-work) |
| Narrow, source-able questions                        | "explain instrumentation-based Activity interception"; "is there no incremental-compile support already?" | [Use subagents for investigation](https://code.claude.com/docs/en/best-practices#use-subagents-for-investigation) |
| Challenge drift mid-flight                           | **"Have you weakened the use case? Does this still handle arbitrary apps?"** | [Course-correct early and often](https://code.claude.com/docs/en/best-practices#course-correct-early-and-often) |
| Adversarial review — of the output *and* the request | "run a code review pass"; **"critique my request before executing"**; "compare structures, don't just take my advice" | [Add an adversarial review step](https://code.claude.com/docs/en/best-practices#add-an-adversarial-review-step) |

## What I'd generalize

1. **Spike with a running prototype** — every assertion here was backed by something that ran on the phone, and prototypes surface unknown-unknowns (payload-dir security, the Create-Project install prompt) that whiteboarding doesn't.
2. **Measure alternatives to death** — the dex-free debate ended in one number (0.3 s); JVMTI ended in one screenshot. Cheap measurements end long arguments.
3. **Be the invariant-holder and the challenger, not the task queue** — my highest-leverage messages were *"have you weakened the use case?"* and *"critique my request,"* not instructions.
4. **Separate priorities from evidence, join them in decisions** — the A×B→D structure is the transferable method.
5. **Long leash + hard gates** — overnight autonomy produced the biggest validated chunks *because* nothing could ship without my go.

*Numbers measured from the session transcript (2,299 agent turns, 31 steering messages) via the weekly-review tooling. Artifacts: spike branch **`ADFA-4128-prototype`** → **`spike/mini-stubby/`**.*

# How we ran the ADFA-4128 spike so far

TODO: Paste the full transcript in here in case anyone wants to review for fun

# 2026-07-07 Evening 

## 1. Frame the problem (initial prompt)

- **De-risk:** Is the ticket's "Son of Stubby" actually the right framing? The ticket was tech-heavy, light on product goals.
- **Steer:** Pasted the ticket, then widened it — "this is only two proposed approaches; the actual desire is quick edit→run turnaround." Reframed from *a solution* to *a goal* — and set the headline target up front: a change should show in a running app in **under ~1s** ("within 1s if possible"). Giving the agent a concrete number to optimize against is the ["provide specific context"](https://code.claude.com/docs/en/best-practices#provide-specific-context-in-your-prompts) practice.
- **Agent, alone:** Pulled the ticket + David's original "Stubby" page, mapped the two proposals, scoped a prototype. Good — correctly separated the mechanism from the outcome.
- **Learned:** The real target is the *loop*, not any one mechanism. Set up everything downstream.

## 2. Prove the mechanism (set a wildly ambitious, but specific goal, and get a goal loop going)

- **De-risk:** Can a shell app actually load a user app's DEX + resources with no install, and does it survive real app shapes?
- **Steer:** "Get as far as you can on the fast loop… I'm going to sleep." Direction + long leash + the standing push-gate that makes autonomy safe.
- **Agent, alone (~3 h):** Built the shell, got DEX + resource hot-load working on-device, then tested androidx/Material3/Compose/Fragments/native/multi-Activity and logged blockers. Strong — the core worked and the capability matrix was real, not claimed.
- **Learned:** The mechanism is sound across app shapes; the cost is compilation, not loading (~40 ms reload).

### 3. Handle the hard cases + design the loop

- **De-risk:** Where does the mechanism break, and how do we get to a warm, tiered loop?
- **Steer:** Targeted probes — "test all the common cases," "explain instrumentation-based Activity interception," "is there a way to make the 1s loop faster for slower devices?"
- **Agent, alone (~2 h):** Researched VirtualApp/instrumentation, designed the persistent warm-compile service and the 3-tier reload, analyzed slow-device impact. Good synthesis; produced the tier design we still use.
- **Learned:** A tiered loop (resource edits skip the compiler) is the right structure; slow devices are the real risk to name.

### 4. Push on robustness + kill a tempting path (derisk more things)

- **De-risk:** Can method-body hot-swap (JVMTI) give us sub-second code edits — and is the loop robust?
- **Steer:** "Can you figure out how to make Tier 1 more robust?" then "run a code review pass."
- **Agent, alone (~1.5 h):** Root-caused JVMTI's limits (body-only, no repaint), ran an 18-finding self-review, fixed edit-loss bugs. Good — and notably *honest*: it demonstrated the limitation rather than asserting it, which is what let us drop the path with confidence.
- **Learned:** JVMTI/JRebel-style hot-swap isn't worth it; full reload at 0.5–3 s wins. (Later confirmed: it's exactly the wall Compose Live Edit and Google's abandoned Instant Run hit.)

### 5. Prove it end-to-end with an ambitious demo (designed to break things)

- **De-risk:** Does the whole loop hold together for a real user building a real app?
- **Steer:** "Demonstrate this using Claude prompts only — blank app → a lemonade-stand game." Then, mid-demo, the highest-leverage move: **"You've been tweaking stuff. Have you weakened the use case? Does this still handle arbitrary apps?"**
- **Agent, alone (~3 h):** Built the demo, and that challenge caught a real drift — a demo convenience had quietly constrained the payload's programming model. The agent re-architected (controls into window sub-windows) to restore full fidelity, and added a self-healing build loop. Good recovery; wouldn't have happened without the challenge.
- **Learned:** The loop works for a real build; **guarding the invariant ("still a real app?") is the human's job**, not delegable.

### 6. Move the toolchain on-device + benchmark (fix agent oversight / missed product requirement)

- **De-risk:** Does the compile loop actually run *on the phone* (not just the Mac), and how fast?
- **Steer:** "The warm compile service and d8 and aapt2 need to run on device. And we want benchmark numbers."
- **Agent, alone (~50 m):** Staged the toolchain on the A56, produced the benchmark ladder. Good — real on-device numbers, honest about the cold-start cost.
- **Learned:** Full on-device loop is real; resource edits ~0.75 s, code ~1–3 s — but full compile balloons with app size, foreshadowing stage 7.

### 7. Critique, then chase the real bottleneck(push boundaries on app size / complexity)

- **De-risk:** Will this stay fast for *large, real* apps?
- **Steer:** Two sharp moves. First, **"critique my request before executing"** when I asked for a LoC-ladder benchmark. Second, "is there no incremental-compile support already?"
- **Agent, alone (~1.75 h):** The critique showed my benchmark-as-specified would have measured the *absence of incremental compilation*, not the design — so we re-sequenced. Then it integrated Kotlin's own incremental engine, verified output against clean builds, tested under memory pressure, and proved the hybrid dependency model against CoGo's real offline repo. Excellent — the spike's strongest result came directly from the request-critique.
- **Learned:** Incremental compile keeps edits flat (~0.5 s) regardless of app size; real dependencies work by consuming CoGo's resolved classpath, not reimplementing Gradle.

### 8. Summarize to facilitate team discussion

- **De-risk:** Can others act on this in parallel without me?
- **Steer:** "Don't just take my advice — compare structures." Landed on goals → priorities (A) × options+evidence (B) → decision register (D), with strawmen to knock down.
- **Agent, alone (~35 m):** Proposed and built the discussion-doc structure; iterated on my feedback. Good — the A×B→D split is reusable beyond this ticket.
- **Learned:** Separating *priorities* from *evidence* and joining them explicitly in decisions is what makes a spike spawnable into parallel workstreams.

---

### What I'd generalize

1. **Spike with a running prototype** — every assertion here was backed by something that ran on the phone, and prototypes surface unknown-unknowns (payload-dir security, the Create-Project install prompt) that whiteboarding doesn't.
2. **Measure alternatives to death** — the dex-free debate ended in one number (0.3 s); JVMTI ended in one screenshot. Cheap measurements end long arguments.
3. **Be the invariant-holder and the challenger, not the task queue** — my best messages were "have you weakened the use case?" and "critique my request," not instructions.
4. **Separate priorities from evidence, join them in decisions** — the A×B→D structure is the transferable method.
5. **Long leash + hard gates** — overnight autonomy produced the biggest validated chunks *because* nothing could ship without my go.

*Numbers measured from the session transcript (2,299 agent turns, 31 steering messages) via the weekly-review tooling. Artifacts: spike branch **`ADFA-4128-prototype`** → **`spike/mini-stubby/`**.*
