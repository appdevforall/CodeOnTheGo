# ADFA-4128 "Son of Stubby" Investigation Spike Transcript Review

> TODO: Paste the full transcript in here in case anyone wants to review for fun.

Here's an example of how we did a quick investigation spike to figure out what ticket ADFA-4128 might be about, and what product or technical questions to ask.  It took about 2 hours total of hands-on attention and 12.5 hours of agent time over two days (while also working on half a dozen other projects in parallel).

The whole raw transcript is here, if you want to see it:TODO: Paste the full transcript in here in case anyone wants to review for fun.

And in the notes below, also tried to highlight some prompts and techniques along the way (mapped to [Claude Code best practices](https://code.claude.com/docs/en/best-practices)).

---

# Summary of Prompting Techniques + Best Practices Behind Them

During the spike tried to use a bunch of common [Claude Code best practices](https://code.claude.com/docs/en/best-practices)

| What I asked for                                             | Sample prompt I used during session                          | Best practice                                                |
| ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| Reframe technical solution → measurable goal Claude can optimize against | *"…the actual desire is quick edit→run turnaround… within 1s if possible"* | [Provide specific context](https://code.claude.com/docs/en/best-practices#provide-specific-context-in-your-prompts) |
| Explicitly review but don't only consider solutions mentioned in the ticket | *"…explore and consider other solutions too"*                | [Explore first, then plan, then code](https://code.claude.com/docs/en/best-practices#explore-first-then-plan-then-code)  |
| Start a goal loop once there's a measurable goal             | *"Get as far as you can… I'm going to sleep"; "keep going until you have no more open questions"* | [Give Claude a way to verify its work](https://code.claude.com/docs/en/best-practices#give-claude-a-way-to-verify-its-work) |
| Demand measured numbers, not claims                          | *"…and we want benchmark numbers"; "verify output against a clean build"* | [Give Claude a way to verify its work](https://code.claude.com/docs/en/best-practices#give-claude-a-way-to-verify-its-work) |
| Ask Claude to do broader product market research or technical research to make good decisions | *"is there no incremental-compile support already?"*         | [Use subagents for investigation](https://code.claude.com/docs/en/best-practices#use-subagents-for-investigation) |
| Challenge drift mid-flight when things look like they're going off the rails! | *"Have you weakened the use case? Does this still handle arbitrary apps?"* | [Course-correct early and often](https://code.claude.com/docs/en/best-practices#course-correct-early-and-often) |
| Adversarial review — of the output *and* the request.  Ask agents to spawn other agents and argue with themselves... | *"run a code review pass"; "critique my request before executing"; "compare structures, don't just take my advice"* | [Add an adversarial review step](https://code.claude.com/docs/en/best-practices#add-an-adversarial-review-step) |

# Detailed Transcript Notes

## 2026-07-07 — Get the goal loop going on ambitious criteria

### 1. Frame the problem (give context, set an ambitious measurable goal)

- **Goal:**
  - Figure out what it'd actually take to chase this goal — and what the goal even *should* be (what's achievable).
  - Map the real solution space, not just the ticket's two proposals.
- **Prompts & practices:**
  - Pasted the ticket, then widened it: *"this is only two proposed approaches; the actual desire is quick edit→run turnaround… reflected in a running app within 1s if possible."*
    - → [**Provide specific context**](https://code.claude.com/docs/en/best-practices#provide-specific-context-in-your-prompts) — a concrete number (< 1s) the whole spike then optimized against.
    - *Note: a goal that seems almost-too-ambitious (but still reachable) is often the fun kind — it can yield great results when it lands.*
  - Told it to *explore and consider other solutions too*, not just the ticket's two.
    - → [**Explore first, then plan, then code**](https://code.claude.com/docs/en/best-practices#explore-first-then-plan-then-code).
- **Agent, alone — ~0.5 h agent · ~15 m hands-on · 1 msg:**
  - Pulled the ticket + David's original "Stubby" page; mapped the two proposals; scoped a prototype.
  - Correctly separated the *mechanism* (how) from the *outcome* (a fast loop).
- **Learned:**
  - The real target is the **edit→run loop**, not any single mechanism — this reframing set up everything downstream.

### 2. Start the loop (and make it clear I'm disappearing :P)

- **Goal:**
  - Prove a shell app can load a user app's DEX + resources with **no install** — and that it survives real app shapes (androidx, Material3, Compose, Fragments, native, multi-Activity).
- **Prompts & practices:**
  - A long leash + walk-away: *"Please get as far as you can on the fast edit/debug loop… I'm going to sleep, will check in in the morning,"* later reinforced with *"keep going until you have no more open questions."*
    - → [**Give Claude a way to verify its work**](https://code.claude.com/docs/en/best-practices#give-claude-a-way-to-verify-its-work) — pairing the ambitious < 1s bar with "keep going until done" works *because* the push-gate makes the overnight autonomy safe.
    - *(Could also use the goal skill explicitly; didn't in this case.)*
- **Agent, alone — ~3 h agent · ~20 m hands-on · 4 msgs (+ ~38 device screenshots reviewed overnight):**
  - Built the shell; got DEX + resource hot-load working on-device.
  - Tested androidx / Material3 / Compose / Fragments / native / multi-Activity and logged blockers — the capability matrix was real, not claimed.
- **Learned:**
  - The "Son of Stubby" mechanism (shell app where we reload) is sound across a few test app typesWorth building on and using as a testbed
  - So far, the cost is compilation, not loading into the shell app (~40 ms reload)

---

## 2026-07-08 — Keep pushing boundaries & learning

### 3. Handle the hard cases + design the loop

- **Goal:**
  - Find where the mechanism breaks, and design a warm, tiered loop that's faster and more reliable — including on slower devices.
- **Prompts & practices:**
  - Narrow, source-able probes rather than open-ended asks:
    - *"test all these other common cases and see where you hit blockers"*
    - *"explain instrumentation-based Activity interception"*
    - *"is there a way to make the 1s loop significantly faster so it works on slower devices?"*
    - → [**Use subagents for investigation**](https://code.claude.com/docs/en/best-practices#use-subagents-for-investigation) — prompted it to enumerate goals, then fan out and investigate each in parallel
- **Agent, alone — ~2 h agent · ~15 m hands-on · 4 msgs:**
  - Researched VirtualApp / instrumentation.
  - Designed the persistent warm-compile service + 3-tier reload (resource edits skip the compiler; code hot-reload when the class signature doesn't change).
  - Analyzed slow-device impact.
- **Learned:**
  - A tiered loop is the right structure — resource edits skip the compiler and cover a big chunk of edit types without rebuilding the whole app.
  - Slow devices are the risk to keep watching.

### 4. Push on robustness + kill a tempting path

- **Goal:**
  - Test whether method-body hot-swap (e.g. JVMTI) can give sub-second code edits *when the signature changes* — and harden the loop with a cleanup pass.
- **Prompts & practices:**
  - *"Can you figure out how to make Tier 1 more robust?"* then *"Make robust please. And run a code review pass on the work so far."*
    - → [**Add an adversarial review step**](https://code.claude.com/docs/en/best-practices#add-an-adversarial-review-step) — felt like we'd done enough that a review pass was worthwhile, to get a firmer foundation before exploring more.
- **Agent, alone — ~1.5 h agent · ~10 m hands-on · 3 msgs:**
  - Root-caused JVMTI's limits (body-only, no repaint); ran an 18-finding self-review; fixed edit-loss bugs.
  - Notably *honest* — it *demonstrated* the limitation rather than asserting it, which let us drop the path with confidence.
- **Learned:**
  - JVMTI / JRebel-style hot-swap isn't worth it; full reload at 0.5–3 s wins.
  - Same wall Compose Live Edit and Google's abandoned Instant Run hit — Google dropped it because it was fragile and often made debugging hard.
    - → Added **debuggability** to the product requirements.

---

## 2026-07-09 — Get the E2E flow working & gather evidence for discussion

### 5. Prove it end-to-end with an ambitious demo (designed to break things)

- **Goal:**
  - See whether the whole loop holds for a real user building a real app — and capture a recording so the team can react to how it *feels*.
- **Prompts & practices:**
  - *"Demonstrate this using Claude prompts only — blank app → a lemonade-stand game."* *(TODO: how do I ask for a recording?)*
  - Mid-demo course-correct: *"You've been tweaking stuff. Have you weakened the use case? Does this still handle building arbitrary apps?"*
    - → [**Course-correct early and often**](https://code.claude.com/docs/en/best-practices#course-correct-early-and-often) — even when I'm hands-off, if the agent runs long on a hard task I'll peek and nudge, catching drift while it's cheap to fix rather than at the end.
- **Agent, alone — ~3 h agent · ~25 m hands-on · 6 msgs:**
  - Built the demo; the challenge caught a real drift — a demo convenience had quietly forced every app into a single `render()` method.
  - Re-architected (dev controls into activity sub-windows) to restore full fidelity; added a self-healing build loop.
- **Learned:**
  - Agents still cut corners sometimes when the goal is clear and they're falling short of it. *(Documented behaviour in the wild too — TODO: link.)*
  - Guarding the invariant ("is it still a *real* app?") is the human's job — not delegable.

### 6. Move the toolchain on-device + benchmark (fix agent oversight / missed product requirement)

- **Goal:**
  - Confirm the compile loop actually runs *on the phone* (not just the Mac) — and get real numbers, not hand-waving.
- **Prompts & practices:**
  - *"The warm compile service and d8 and aapt2 need to run on device. And we want benchmark numbers."*
    - → [**Give Claude a way to verify its work**](https://code.claude.com/docs/en/best-practices#give-claude-a-way-to-verify-its-work)Demanding measured numbers (not "it should be fast") makes the benchmark *the* verification, and it surfaced the on-device cost honestly.
- **Agent, alone — ~0.8 h agent · ~8 m hands-on · 3 msgs:**
  - Staged the toolchain (JDK, kotlinc, d8, aapt2) on the A56; produced the benchmark ladder.
  - Honest about the cold-start cost.
- **Learned:**
  - Full on-device loop is real: resource edits ~0.75 s, code ~1–3 s.
  - But **full compile balloons with app size** — foreshadowing stage 7.

### 7. Critique, then chase the real bottleneck (push boundaries on app size / complexity)

- **Goal:**
  - Make sure the loop stays fast for *large, real* apps — and check whether incremental compile already exists rather than reinventing it.
- **Prompts & practices:**
  - *"Critique my request before executing"* (I'd asked for a LoC-ladder benchmark).
    - → [**Add an adversarial review step**](https://code.claude.com/docs/en/best-practices#add-an-adversarial-review-step) — applied to *my own instruction*, not just the agent's output.
  - *"Is there no support already for incremental compile?"*
    - Claude is heavily biased towards solving things (and so are most engineers)Every once in a while, it's helpful to take a step back and if it smells like something might already exist in the world (or in the codebase) ask to explicitly look for it
    - → [**Use subagents for investigation**](https://code.claude.com/docs/en/best-practices#use-subagents-for-investigation) — a source-able question that found Kotlin's own engine.
- **Agent, alone — ~1.75 h agent · ~20 m hands-on · 6 msgs:**
  - The critique showed my benchmark-as-specified would have measured the *absence* of incremental compilation, not the design — so we re-sequenced.
  - Integrated Kotlin's incremental engine; verified output byte-for-byte vs clean builds; tested under memory pressure; proved the hybrid dependency model against CoGo's real offline repo.
- **Learned:**
  - Incremental compile keeps edits **flat (~0.5 s) regardless of app size**.
  - Real dependencies work by consuming CoGo's resolved classpath — not reimplementing Gradle.

---
