# Runs one plugin command, written by AgentRunner to "$id.cmd" and "$id.dir" beside this script,
# in a bash process of its own, so a cd or export does not reach the next command.
#
# It reports to the Terminal with OSC 133 shell-integration marks, which the terminal does not
# show: "C" where the command's output starts and "D;<exit code>" when it ends, Ctrl-C included.
# Both carry cogo-id=<id>, so a mark the user's own shell prints is never taken for this command,
# and cogo-pid=<pid>, so the Terminal can tell this run still holds the foreground as it exits.

id=$1
dir=${0%/*}

mark() {
	printf '\033]133;%s;cogo-id=%s;cogo-pid=%s\007' "$1" "$id" "$$"
}

# Set first, so every way out reports an exit code.
trap 'mark "D;$?"' EXIT

# Taken by a rename, which AgentRunner.withdraw cannot race: either this run or the Terminal gets it.
mv -- "$dir/$id.cmd" "$dir/$id.run" 2>/dev/null || exit 1
cmd=$(cat "$dir/$id.run") || exit 1
wd=$(cat "$dir/$id.dir") || exit 1
# The command as shown: AgentRunner made its control characters visible, which printed raw would
# drive the terminal.
shown=$(cat "$dir/$id.show") || exit 1
rm -f "$dir/$id.run" "$dir/$id.dir" "$dir/$id.show"

mark C
printf '$ %s\n' "$shown"
cd -- "${wd:-$HOME}" || exit 1
# A syntax error message quotes the command raw, so the syntax is checked first and an error is
# shown with its control characters replaced. Exits 2, as eval does on a syntax error, but before
# any of the command runs: eval would run the lines ahead of the error. The check has extglob on:
# eval runs a command's lines one at a time, so a "shopt -s extglob" on one line applies to the
# next, but -n runs nothing. The cost: an extglob pattern in a command that never turns extglob on
# passes the check, and eval's message rejecting it quotes the line raw. The command goes in on
# stdin, as exec refuses an argument over 32 pages (128 KiB at 4 KiB pages).
if ! err=$(bash -O extglob -n <<<"$cmd" 2>&1); then
	while IFS= read -r line; do printf '%s\n' "${line//[[:cntrl:]]/?}"; done <<<"$err" >&2
	exit 2
fi
# In a subshell, so an exec or a trap in the command cannot take the EXIT trap with it.
(eval "$cmd")
