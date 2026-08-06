#!/usr/bin/env python3
"""Deterministic transcript analysis for retro skill.

Usage:
  python3 analyze_transcript.py <path-to-jsonl>          Single transcript
  python3 analyze_transcript.py --scan                   All sessions, last 24h
  python3 analyze_transcript.py --scan --hours 48        All sessions, last 48h
  python3 analyze_transcript.py --scan --since 2026-04-15

Single-transcript mode: turn-by-turn analysis with timing + cost per turn.
Scan mode: cross-session cost report showing top consumers and model breakdown.

Hands-on time model (for parallel-session users):
  Reading:  assistant_output_words / 150 wpm
  Typing:   user_input_words / 60 wpm
  Buffer:   1 min per turn (context switch overhead)
  Merge:    consecutive turns with overlapping buffers merge into one block

Pricing (per million tokens, as of 2026-04):
  Opus:   input $15, cache-read $1.50, cache-write $18.75, output $75
  Sonnet: input $3,  cache-read $0.30, cache-write $3.75,  output $15
  Haiku:  input $0.80, cache-read $0.08, cache-write $1,   output $4

Filters out system-injected messages:
  - Skill injections ("Base directory for this skill:")
  - Local command outputs (<command-name>, <local-command-)
  - System reminders (<system-reminder>)
"""

from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

# --- Constants ---

READ_WPM = 150
TYPE_WPM = 60
BUFFER_MIN = 1.0
HUMAN_PREVIEW_LIMIT = 2000
ASSISTANT_PREVIEW_LIMIT = 150

CLAUDE_PROJECTS_DIR = Path.home() / ".claude" / "projects"

PRICING = {
    "opus": {"input": 15.0, "cache_read": 1.50, "cache_write": 18.75, "output": 75.0},
    "sonnet": {"input": 3.0, "cache_read": 0.30, "cache_write": 3.75, "output": 15.0},
    "haiku": {"input": 0.80, "cache_read": 0.08, "cache_write": 1.0, "output": 4.0},
}

SYSTEM_MESSAGE_PATTERNS = [
    re.compile(r"^Base directory for this skill:"),
    re.compile(r"^<(command-name|local-command|system-reminder)"),
    re.compile(r"^<local-command-caveat>"),
    re.compile(r"^This session is being continued from a previous conversation"),
]


# --- Cost tracking ---


def model_family(model_str: str) -> str:
    m = model_str.lower()
    if "opus" in m:
        return "opus"
    if "sonnet" in m:
        return "sonnet"
    if "haiku" in m:
        return "haiku"
    return "unknown"


@dataclass
class TokenBucket:
    input_tokens: int = 0
    cache_read_tokens: int = 0
    cache_write_tokens: int = 0
    output_tokens: int = 0
    api_calls: int = 0

    def add_usage(self, usage: dict) -> None:
        self.input_tokens += usage.get("input_tokens", 0)
        self.output_tokens += usage.get("output_tokens", 0)
        self.cache_read_tokens += usage.get("cache_read_input_tokens", 0)
        cr = usage.get("cache_creation_input_tokens", 0)
        if not cr:
            cc = usage.get("cache_creation", {})
            cr = cc.get("ephemeral_1h_input_tokens", 0) + cc.get("ephemeral_5m_input_tokens", 0)
        self.cache_write_tokens += cr
        self.api_calls += 1

    def cost(self, family: str) -> float:
        p = PRICING.get(family, PRICING["opus"])
        return (
            self.input_tokens * p["input"]
            + self.cache_read_tokens * p["cache_read"]
            + self.cache_write_tokens * p["cache_write"]
            + self.output_tokens * p["output"]
        ) / 1_000_000

    def merge(self, other: TokenBucket) -> None:
        self.input_tokens += other.input_tokens
        self.cache_read_tokens += other.cache_read_tokens
        self.cache_write_tokens += other.cache_write_tokens
        self.output_tokens += other.output_tokens
        self.api_calls += other.api_calls

    def total_tokens(self) -> int:
        return self.input_tokens + self.cache_read_tokens + self.cache_write_tokens + self.output_tokens


# --- Data classes ---


@dataclass
class Message:
    """A single extracted message from the transcript."""

    timestamp: datetime
    role: str
    is_human: bool
    is_tool_result: bool
    is_system: bool
    word_count: int
    tools: list[str]
    has_error: bool
    preview: str
    model: str = ""
    usage: dict = field(default_factory=dict)


@dataclass
class Turn:
    """A grouped turn: one user message and all subsequent assistant messages."""

    number: int
    timestamp: datetime
    user_text: str
    user_words: int
    asst_words: int
    tools: list[str]
    errors: int
    asst_preview: str
    cost_by_model: dict[str, TokenBucket] = field(default_factory=dict)

    def turn_cost(self) -> float:
        return sum(b.cost(fam) for fam, b in self.cost_by_model.items())


@dataclass
class TurnTiming:
    """Timing data for a single turn."""

    read_min: float
    type_min: float
    buffer_min: float = BUFFER_MIN
    merged: bool = False


@dataclass
class TimingStats:
    """Aggregate timing statistics."""

    total_turns: int
    total_read_min: float
    total_type_min: float
    total_buffer_min: float
    raw_handson_min: float
    adjusted_handson_min: float
    per_turn: list[TurnTiming] = field(default_factory=list)


# --- Extraction ---


def _extract_text(content: str | list) -> str:
    """Extract readable text from message content."""
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts = []
    for block in content:
        if not isinstance(block, dict):
            continue
        block_type = block.get("type", "")
        if block_type == "text":
            parts.append(block.get("text", ""))
        elif block_type == "thinking":
            pass  # excluded from text
        elif block_type == "tool_use":
            parts.append(f"TOOL:{block.get('name', '')}")
        elif block_type == "tool_result":
            text = str(block.get("content", ""))
            if block.get("is_error"):
                parts.append(f"ERROR:{text}")
            else:
                parts.append(text)
    return " ".join(parts)


def _extract_tools(content: str | list) -> list[str]:
    """Extract tool names from assistant message content."""
    if not isinstance(content, list):
        return []
    return [
        block.get("name", "")
        for block in content
        if isinstance(block, dict) and block.get("type") == "tool_use"
    ]


def _has_error(content: str | list) -> bool:
    """Check if message content contains errors."""
    if isinstance(content, list):
        return any(
            isinstance(block, dict)
            and block.get("type") == "tool_result"
            and block.get("is_error") is True
            for block in content
        )
    if isinstance(content, str):
        return bool(re.search(r"(?i)error|failed|exit code [1-9]", content))
    return False


def _is_system_message(text: str) -> bool:
    """Check if a human message is system-injected."""
    return any(pat.search(text) for pat in SYSTEM_MESSAGE_PATTERNS)


def _word_count(text: str) -> int:
    """Count words in text, splitting on whitespace."""
    return len([w for w in text.split() if w])


def _make_preview(role: str, is_human: bool, is_system: bool, text: str, tools: list[str]) -> str:
    """Create a content preview for display."""
    if is_human and is_system:
        return f"[SYSTEM] {text[:100]}"
    if is_human:
        if len(text) > HUMAN_PREVIEW_LIMIT:
            return text[:HUMAN_PREVIEW_LIMIT] + " [TRUNCATED]"
        return text
    if role == "assistant" and tools:
        return f"tools: {', '.join(tools)}"
    if role == "assistant":
        return text[:ASSISTANT_PREVIEW_LIMIT]
    return text[:ASSISTANT_PREVIEW_LIMIT]


def _parse_timestamp(ts: str) -> datetime:
    """Parse ISO timestamp to datetime."""
    ts = ts.replace("Z", "+00:00")
    return datetime.fromisoformat(ts)


def extract_messages(jsonl_text: str) -> list[Message]:
    """Parse JSONL transcript text into a list of Messages."""
    messages = []
    for line in jsonl_text.strip().split("\n"):
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue

        if "timestamp" not in record or "message" not in record:
            continue

        msg = record["message"]
        role = msg.get("role", "")
        if role not in ("user", "assistant"):
            continue

        content = msg.get("content", "")
        is_human = (
            role == "user"
            and record.get("sourceToolAssistantUUID") is None
            and record.get("toolUseResult") is None
        )
        is_tool_result = (
            record.get("sourceToolAssistantUUID") is not None
            or record.get("toolUseResult") is not None
        )

        text = _extract_text(content)
        is_system = is_human and _is_system_message(text)
        tools = _extract_tools(content) if role == "assistant" else []

        if is_system:
            words = 0
        elif role == "assistant":
            if isinstance(content, list):
                text_parts = [
                    block.get("text", "")
                    for block in content
                    if isinstance(block, dict) and block.get("type") == "text"
                ]
                words = _word_count(" ".join(text_parts))
            else:
                words = _word_count(text)
        else:
            words = _word_count(text)

        preview = _make_preview(role, is_human, is_system, text, tools)

        messages.append(
            Message(
                timestamp=_parse_timestamp(record["timestamp"]),
                role=role,
                is_human=is_human,
                is_tool_result=is_tool_result,
                is_system=is_system,
                word_count=words,
                tools=tools,
                has_error=_has_error(content),
                preview=preview,
                model=msg.get("model", ""),
                usage=msg.get("usage", {}),
            )
        )
    return messages


# --- Turn grouping ---


def group_into_turns(messages: list[Message]) -> list[Turn]:
    """Group messages into turns. A new turn starts with each real human message."""
    turns: list[Turn] = []
    current: Turn | None = None

    for msg in messages:
        if msg.is_human and not msg.is_system:
            if current is not None:
                turns.append(current)
            current = Turn(
                number=len(turns) + 1,
                timestamp=msg.timestamp,
                user_text=msg.preview,
                user_words=msg.word_count,
                asst_words=0,
                tools=[],
                errors=0,
                asst_preview="",
            )
            continue

        if msg.is_human and msg.is_system:
            continue

        if current is not None:
            if msg.role == "assistant":
                current.asst_words += msg.word_count
                for tool in msg.tools:
                    if tool not in current.tools:
                        current.tools.append(tool)
                if not current.asst_preview and msg.preview and not msg.preview.startswith("tools: "):
                    current.asst_preview = msg.preview
                if msg.usage:
                    fam = model_family(msg.model)
                    if fam not in current.cost_by_model:
                        current.cost_by_model[fam] = TokenBucket()
                    current.cost_by_model[fam].add_usage(msg.usage)
            if msg.has_error:
                current.errors += 1

    if current is not None:
        turns.append(current)

    for i, turn in enumerate(turns):
        turn.number = i + 1

    return turns


# --- Timing ---


def calculate_timing(turns: list[Turn]) -> TimingStats:
    """Calculate hands-on timing with overlapping turn merging."""
    if not turns:
        return TimingStats(
            total_turns=0,
            total_read_min=0,
            total_type_min=0,
            total_buffer_min=0,
            raw_handson_min=0,
            adjusted_handson_min=0,
        )

    per_turn: list[TurnTiming] = []
    for turn in turns:
        read_min = turn.asst_words / READ_WPM
        type_min = turn.user_words / TYPE_WPM
        per_turn.append(TurnTiming(read_min=read_min, type_min=type_min))

    n = len(turns)
    merge_end = list(range(n))

    i = 0
    while i < n:
        t_start = turns[i].timestamp
        buffered_end_minutes = (
            per_turn[i].read_min + per_turn[i].type_min + BUFFER_MIN
        )
        j = i + 1
        while j < n:
            gap_minutes = (turns[j].timestamp - t_start).total_seconds() / 60.0
            if buffered_end_minutes >= gap_minutes:
                per_turn[j].merged = True
                merge_end[i] = j
                gap_to_j = (turns[j].timestamp - t_start).total_seconds() / 60.0
                buffered_end_minutes = gap_to_j + per_turn[j].read_min + per_turn[j].type_min + BUFFER_MIN
                j += 1
            else:
                break
        i = j if j > i + 1 else i + 1

    total_read = sum(t.read_min for t in per_turn)
    total_type = sum(t.type_min for t in per_turn)
    total_buffer = BUFFER_MIN * n
    raw_handson = total_read + total_type + total_buffer

    adjusted_handson = 0.0
    i = 0
    while i < n:
        if per_turn[i].merged:
            i += 1
            continue
        end = merge_end[i]
        group_raw = sum(per_turn[k].read_min + per_turn[k].type_min for k in range(i, end + 1))
        adjusted_handson += group_raw + BUFFER_MIN
        i = end + 1

    return TimingStats(
        total_turns=n,
        total_read_min=total_read,
        total_type_min=total_type,
        total_buffer_min=total_buffer,
        raw_handson_min=raw_handson,
        adjusted_handson_min=adjusted_handson,
        per_turn=per_turn,
    )


# --- Formatting (single transcript) ---


def _fmt_tokens(n: int) -> str:
    if n >= 1_000_000:
        return f"{n/1_000_000:.1f}M"
    if n >= 1_000:
        return f"{n/1_000:.0f}K"
    return str(n)


def _fmt_cost(c: float) -> str:
    if c < 0.01:
        return "<$0.01"
    return f"${c:.2f}"


def format_markdown(turns: list[Turn], stats: TimingStats) -> str:
    """Format turns and timing stats as markdown."""
    lines: list[str] = []
    lines.append("# Transcript Analysis")
    lines.append("")

    # Session cost summary
    total_cost = sum(t.turn_cost() for t in turns)
    total_output = sum(
        b.output_tokens for t in turns for b in t.cost_by_model.values()
    )
    total_calls = sum(
        b.api_calls for t in turns for b in t.cost_by_model.values()
    )
    if total_calls > 0:
        lines.append("## Cost Summary")
        lines.append("")
        lines.append(f"**Total estimated cost: {_fmt_cost(total_cost)}** | API calls: {total_calls} | Output tokens: {_fmt_tokens(total_output)}")
        lines.append("")

        # Model breakdown
        model_agg: dict[str, TokenBucket] = {}
        for t in turns:
            for fam, b in t.cost_by_model.items():
                if fam not in model_agg:
                    model_agg[fam] = TokenBucket()
                model_agg[fam].merge(b)

        lines.append("| Model | Calls | Input | Cache Read | Cache Write | Output | Cost |")
        lines.append("|-------|-------|-------|------------|-------------|--------|------|")
        for fam in sorted(model_agg.keys(), key=lambda f: model_agg[f].cost(f), reverse=True):
            b = model_agg[fam]
            lines.append(
                f"| {fam} | {b.api_calls} | {_fmt_tokens(b.input_tokens)} "
                f"| {_fmt_tokens(b.cache_read_tokens)} | {_fmt_tokens(b.cache_write_tokens)} "
                f"| {_fmt_tokens(b.output_tokens)} | {_fmt_cost(b.cost(fam))} |"
            )
        lines.append("")

        # Costliest turns
        costly = sorted(enumerate(turns), key=lambda x: x[1].turn_cost(), reverse=True)[:10]
        if costly and costly[0][1].turn_cost() > 0:
            lines.append("**Costliest turns:**")
            lines.append("")
            for idx, t in costly:
                if t.turn_cost() < 0.01:
                    break
                preview = t.user_text[:80] if t.user_text else "(no text)"
                lines.append(f"- Turn {t.number} ({_fmt_cost(t.turn_cost())}): {preview}")
            lines.append("")

    lines.append("## Turns")
    lines.append("")

    for turn in turns:
        tools_str = ", ".join(turn.tools)
        cost_str = f" | Cost: {_fmt_cost(turn.turn_cost())}" if turn.turn_cost() > 0 else ""
        lines.append(f"### Turn {turn.number} — {turn.timestamp.isoformat()}")
        lines.append(f"**User** ({turn.user_words} words):")
        lines.append(f"> {turn.user_text}")
        lines.append("")

        asst_line = f"**Assistant** ({turn.asst_words} words)"
        if tools_str:
            asst_line += f" | Tools: {tools_str}"
        if turn.errors > 0:
            asst_line += f" | ERRORS: {turn.errors}"
        asst_line += cost_str
        lines.append(asst_line)

        if turn.asst_preview:
            lines.append(f"Preview: {turn.asst_preview}")
        lines.append("")

    lines.append("---")
    lines.append("## Timing Stats")
    lines.append("")
    lines.append(f"Total turns: {stats.total_turns}")
    lines.append("")

    lines.append("| Turn | Timestamp | User Words | Asst Words | Read (min) | Type (min) | Buffer | Turn Total | Cost | Merged? |")
    lines.append("|------|-----------|------------|------------|------------|------------|--------|------------|------|---------|")

    for i, turn in enumerate(turns):
        pt = stats.per_turn[i]
        turn_total = pt.read_min + pt.type_min + pt.buffer_min
        merged_flag = "merged" if pt.merged else ""
        lines.append(
            f"| {turn.number} | {turn.timestamp.isoformat()} | {turn.user_words} | {turn.asst_words} "
            f"| {pt.read_min:.1f} | {pt.type_min:.1f} | {pt.buffer_min:.1f} | {turn_total:.1f} "
            f"| {_fmt_cost(turn.turn_cost())} | {merged_flag} |"
        )

    lines.append("")
    lines.append(
        f"**Raw hands-on: {stats.raw_handson_min:.1f} min** "
        f"(reading: {stats.total_read_min:.1f} + typing: {stats.total_type_min:.1f} + buffer: {stats.total_buffer_min:.1f})"
    )
    lines.append(
        f"**Adjusted hands-on: {stats.adjusted_handson_min:.1f} min** "
        f"(overlapping turns merged, single buffer per group)"
    )
    if total_calls > 0:
        lines.append(f"**Estimated cost: {_fmt_cost(total_cost)}**")

    return "\n".join(lines)


# --- Scan mode (cross-session) ---


@dataclass
class SessionCost:
    path: str
    project: str
    session_id: str
    title: str
    model_buckets: dict[str, TokenBucket] = field(default_factory=dict)
    first_ts: datetime | None = None
    last_ts: datetime | None = None
    turn_count: int = 0
    subagents: list[SessionCost] = field(default_factory=list)

    def total_cost(self) -> float:
        c = sum(b.cost(fam) for fam, b in self.model_buckets.items())
        c += sum(sa.total_cost() for sa in self.subagents)
        return c

    def total_output_tokens(self) -> int:
        t = sum(b.output_tokens for b in self.model_buckets.values())
        t += sum(sa.total_output_tokens() for sa in self.subagents)
        return t

    def total_input_tokens(self) -> int:
        t = sum(
            b.input_tokens + b.cache_read_tokens + b.cache_write_tokens
            for b in self.model_buckets.values()
        )
        t += sum(sa.total_input_tokens() for sa in self.subagents)
        return t

    def total_api_calls(self) -> int:
        t = sum(b.api_calls for b in self.model_buckets.values())
        t += sum(sa.total_api_calls() for sa in self.subagents)
        return t


def _extract_project_name(dir_path: str) -> str:
    base = os.path.basename(dir_path)
    parts = base.split("-")
    if len(parts) >= 4 and parts[0] == "" and parts[1] == "Users":
        meaningful = parts[3:]
        name = "-".join(meaningful)
        name = re.sub(r"--claude-worktrees-.*", "", name)
        return name
    return base


def _analyze_session(path: str, cutoff: datetime) -> SessionCost | None:
    project_dir = os.path.dirname(path)
    project = _extract_project_name(project_dir)
    session_id = os.path.splitext(os.path.basename(path))[0]

    sc = SessionCost(path=path, project=project, session_id=session_id, title="")

    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    continue

                if record.get("type") == "summary" and record.get("customTitle"):
                    sc.title = record["customTitle"]
                    continue

                ts_str = record.get("timestamp")
                if not ts_str:
                    continue

                ts = _parse_timestamp(ts_str)
                msg = record.get("message", {})

                if msg.get("role") == "user" and record.get("sourceToolAssistantUUID") is None and record.get("toolUseResult") is None:
                    if ts >= cutoff:
                        sc.turn_count += 1

                if msg.get("role") != "assistant":
                    continue

                usage = msg.get("usage")
                if not usage:
                    continue

                if ts < cutoff:
                    continue

                family = model_family(msg.get("model", ""))
                if family not in sc.model_buckets:
                    sc.model_buckets[family] = TokenBucket()
                sc.model_buckets[family].add_usage(usage)

                if sc.first_ts is None or ts < sc.first_ts:
                    sc.first_ts = ts
                if sc.last_ts is None or ts > sc.last_ts:
                    sc.last_ts = ts

    except (OSError, PermissionError):
        return None

    if not sc.model_buckets:
        return None
    return sc


def _find_sessions(cutoff: datetime) -> list[SessionCost]:
    sessions: dict[str, SessionCost] = {}
    subagent_files: list[tuple[str, str]] = []

    cutoff_epoch = cutoff.timestamp()

    for root, dirs, files in os.walk(str(CLAUDE_PROJECTS_DIR)):
        for fname in files:
            if not fname.endswith(".jsonl"):
                continue
            fpath = os.path.join(root, fname)
            try:
                if os.path.getmtime(fpath) < cutoff_epoch:
                    continue
            except OSError:
                continue

            if "/subagents/" in fpath:
                parts = fpath.split("/subagents/")
                parent_jsonl = parts[0] + ".jsonl"
                parent_id = os.path.splitext(os.path.basename(parent_jsonl))[0]
                subagent_files.append((parent_id, fpath))
            else:
                sc = _analyze_session(fpath, cutoff)
                if sc:
                    sessions[sc.session_id] = sc

    for parent_id, sa_path in subagent_files:
        sa = _analyze_session(sa_path, cutoff)
        if sa and parent_id in sessions:
            sessions[parent_id].subagents.append(sa)

    return sorted(sessions.values(), key=lambda s: s.total_cost(), reverse=True)


def format_scan_report(sessions: list[SessionCost], hours: float) -> str:
    lines: list[str] = []
    lines.append(f"# Claude Code Cost Report — last {hours:.0f}h")
    lines.append("")

    grand_cost = sum(s.total_cost() for s in sessions)
    grand_output = sum(s.total_output_tokens() for s in sessions)
    grand_input = sum(s.total_input_tokens() for s in sessions)
    grand_calls = sum(s.total_api_calls() for s in sessions)

    lines.append(f"**Total estimated cost: {_fmt_cost(grand_cost)}**")
    lines.append(f"API calls: {grand_calls} | Input: {_fmt_tokens(grand_input)} | Output: {_fmt_tokens(grand_output)}")
    lines.append(f"Sessions: {len(sessions)}")
    lines.append("")

    # Model breakdown
    model_totals: dict[str, TokenBucket] = {}
    for s in sessions:
        for fam, bucket in s.model_buckets.items():
            if fam not in model_totals:
                model_totals[fam] = TokenBucket()
            model_totals[fam].merge(bucket)
        for sa in s.subagents:
            for fam, bucket in sa.model_buckets.items():
                if fam not in model_totals:
                    model_totals[fam] = TokenBucket()
                model_totals[fam].merge(bucket)

    lines.append("## By Model")
    lines.append("")
    lines.append("| Model | API Calls | Input | Cache Read | Cache Write | Output | Est. Cost |")
    lines.append("|-------|-----------|-------|------------|-------------|--------|-----------|")
    for fam in sorted(model_totals.keys(), key=lambda f: model_totals[f].cost(f), reverse=True):
        b = model_totals[fam]
        lines.append(
            f"| {fam} | {b.api_calls} | {_fmt_tokens(b.input_tokens)} "
            f"| {_fmt_tokens(b.cache_read_tokens)} | {_fmt_tokens(b.cache_write_tokens)} "
            f"| {_fmt_tokens(b.output_tokens)} | {_fmt_cost(b.cost(fam))} |"
        )
    lines.append("")

    # Per session
    lines.append("## By Session")
    lines.append("")
    lines.append("| # | Project | Title | Cost | Output | Calls | Turns | Subagents |")
    lines.append("|---|---------|-------|------|--------|-------|-------|-----------|")
    for i, s in enumerate(sessions[:20], 1):
        sa_count = len(s.subagents)
        sa_cost = sum(sa.total_cost() for sa in s.subagents)
        title = s.title or s.session_id[:12]
        lines.append(
            f"| {i} | {s.project} | {title} "
            f"| {_fmt_cost(s.total_cost())} | {_fmt_tokens(s.total_output_tokens())} "
            f"| {s.total_api_calls()} | {s.turn_count} | {sa_count} ({_fmt_cost(sa_cost)}) |"
        )
    lines.append("")

    # Top 5 detail
    lines.append("## Top 5 Detailed Breakdown")
    lines.append("")
    for s in sessions[:5]:
        duration = ""
        if s.first_ts and s.last_ts:
            dur_min = (s.last_ts - s.first_ts).total_seconds() / 60
            duration = f" ({dur_min:.0f} min active)"
        lines.append(f"### {s.project} — {s.title or s.session_id[:12]}{duration}")
        lines.append("")

        for fam, b in sorted(s.model_buckets.items(), key=lambda x: x[1].cost(x[0]), reverse=True):
            lines.append(
                f"  - {fam}: {b.api_calls} calls, "
                f"in={_fmt_tokens(b.input_tokens)} cr={_fmt_tokens(b.cache_read_tokens)} "
                f"cw={_fmt_tokens(b.cache_write_tokens)} out={_fmt_tokens(b.output_tokens)} "
                f"-> {_fmt_cost(b.cost(fam))}"
            )

        if s.subagents:
            lines.append(f"  **Subagents ({len(s.subagents)}):**")
            for sa in sorted(s.subagents, key=lambda x: x.total_cost(), reverse=True):
                sa_title = sa.session_id[:20]
                for fam, b in sa.model_buckets.items():
                    lines.append(
                        f"    - {sa_title} ({fam}): {b.api_calls} calls, "
                        f"out={_fmt_tokens(b.output_tokens)} -> {_fmt_cost(b.cost(fam))}"
                    )
        lines.append("")

    return "\n".join(lines)


# --- CLI ---


def main() -> None:
    args = sys.argv[1:]

    if "--help" in args or "-h" in args:
        print(__doc__)
        return

    if "--scan" in args:
        hours = 24.0
        cutoff = None

        for i, a in enumerate(args):
            if a == "--hours" and i + 1 < len(args):
                hours = float(args[i + 1])
            elif a == "--since" and i + 1 < len(args):
                cutoff = datetime.fromisoformat(args[i + 1]).replace(tzinfo=timezone.utc)

        if cutoff is None:
            cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)

        sessions = _find_sessions(cutoff)
        if not sessions:
            print(f"No transcripts found with activity in the last {hours:.0f}h.", file=sys.stderr)
            sys.exit(1)

        print(format_scan_report(sessions, hours))
        return

    # Single-transcript mode
    non_flag_args = [a for a in args if not a.startswith("--")]
    if not non_flag_args:
        print("Usage: python3 analyze_transcript.py <path-to-jsonl>", file=sys.stderr)
        print("       python3 analyze_transcript.py --scan [--hours N]", file=sys.stderr)
        sys.exit(1)

    path = non_flag_args[0]
    try:
        with open(path) as f:
            jsonl_text = f.read()
    except FileNotFoundError:
        print(f"Error: File not found: {path}", file=sys.stderr)
        sys.exit(1)

    messages = extract_messages(jsonl_text)
    turns = group_into_turns(messages)
    stats = calculate_timing(turns)
    print(format_markdown(turns, stats))


if __name__ == "__main__":
    main()
