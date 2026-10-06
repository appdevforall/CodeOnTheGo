# Runs one plugin command, written by AgentRunner to "$id.cmd" and "$id.dir" beside this script,
# in a bash process of its own, so a cd or export does not reach the next command.
#
# It reports to the Terminal with OSC 133 shell-integration marks, which the terminal does not
# show: "C" where the command's output starts and "D;<exit code>" when it ends, Ctrl-C included.
# Both carry cogo-id=<id>, so a mark the user's own shell prints is never taken for this command.

id=$1
dir=${0%/*}

mark() {
	printf '\033]133;%s;cogo-id=%s\007' "$1" "$id"
}

# Set first, so every way out reports an exit code.
trap 'mark "D;$?"' EXIT

cmd=$(cat "$dir/$id.cmd") || exit 1
wd=$(cat "$dir/$id.dir") || exit 1
rm -f "$dir/$id.cmd" "$dir/$id.dir"

mark C
printf '$ %s\n' "$cmd"
cd -- "${wd:-$HOME}" || exit 1
# In a subshell, so an exec or a trap in the command cannot take the EXIT trap with it.
(eval "$cmd")
