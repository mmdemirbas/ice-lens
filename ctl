#!/usr/bin/env bash
# The driver for Iceberg Lens: ./ctl --list names what it does (ajans.toml).
set -euo pipefail
cd "$(dirname "$0")"

op_dev() { ./gradlew run "$@"; }

op_dev_ide() { ./gradlew :intellij:runIde "$@"; }

op_build() { ./gradlew build -x test "$@"; }

op_build_dmg() { ./gradlew :desktop:packageDmg "$@"; }

op_build_plugin() { ./gradlew :intellij:buildPlugin "$@"; }

op_build_cli() { ./gradlew :cli:installDist "$@"; }

op_test() { ./gradlew test "$@"; }

# CI's command line (.github/workflows/ci.yml), less the JDK 21 hint CI needs and
# this machine's toolchain detection does not.
op_verify() {
	./ctl --check
	./gradlew build test "$@"
}

# release.sh takes the version and refuses any that build.gradle.kts does not
# declare, so with no argument it gets the declared one: the board can then run
# it without asking. A version already tagged is refused by release.sh.
op_deploy_release() {
	if [[ $# -eq 0 ]]; then
		set -- "$(sed -n 's/^ *version = "\([0-9][0-9.]*\)"$/\1/p' build.gradle.kts | head -n 1)"
	fi
	./release.sh "$@"
}

# >>> ajans runtime 3 >>>
# Written by `ajans contract sync`; edits here are overwritten. The operations
# are declared in ajans.toml and implemented above as op_<verb>[_<target>].

# Where this file is. A relative path was relative to where ctl was started,
# and the driver may have changed directory since - usually into its own, with
# `cd "$(dirname "$0")"` - so it is tried against here, then against where the
# last cd came from, and failing both the directory the driver changed into.
# A function rather than inline: bash 3.2 cannot parse a `case` inside `$(...)`.
_ajans_where() {
	local src="${BASH_SOURCE[0]}" base
	case "$src" in
		/*) ;;
		*)
			for base in "$PWD" "${OLDPWD:-}"; do
				if [[ -n "$base" && -f "$base/$src" ]]; then
					src="$base/$src"
					break
				fi
			done
			;;
	esac
	case "$src" in
		/*) ;;
		*) src="$PWD/${src##*/}" ;;
	esac
	(cd "$(dirname "$src")" && pwd)
}
_ajans_root="$(_ajans_where)"

_ajans_fn() {
	local name="op_$*"
	name="${name// /_}"
	name="${name//-/_}"
	printf '%s' "${name//./_}"
}

# Reads ajans.toml with awk. Modes: list [verb], keys.
#
# awk and bash ship with the OS, so the listing and the check need nothing
# a package manager can move: version 2 looked for a python3 with tomllib,
# and one Homebrew upgrade was enough to lose it. /usr/bin/awk first, so a
# Homebrew gawk ahead of it on PATH is not what runs.
#
# It reads TOML as manifests are written - each operation an
# [operations.<key>] table, its summary a one-line string - and steps over
# every other value, multi-line ones included. A manifest written otherwise
# (dotted operation keys, inline operation tables, a multi-line summary) is
# refused with its line rather than read wrong.
_ajans_toml() {
	local awk=/usr/bin/awk
	if [[ ! -x "$awk" ]]; then awk="awk"; fi
	if [[ ! -f "$_ajans_root/ajans.toml" ]]; then
		printf '✗ no ajans.toml beside %s\n' "$_ajans_root/ctl" >&2
		return 66
	fi
	# shellcheck disable=SC2016 # the program is awk: its $ are fields, not expansions
	_AJANS_MODE="$1" _AJANS_VERB="${2:-}" LC_ALL=C "$awk" '
BEGIN { SQ = "\047"; DQ = "\""; mode = ENVIRON["_AJANS_MODE"]; verb = ENVIRON["_AJANS_VERB"] }
function fail(why) { if (BAD == "") BAD = "ajans.toml:" NR ": " why; exit }
function ws() { while (P <= length(L) && index(" \t", substr(L, P, 1))) P++ }
function at(s) { return substr(L, P, length(s)) == s }
function hex(h,   i, d, v) {
	for (i = 1; i <= length(h); i++) {
		d = index("0123456789abcdef", tolower(substr(h, i, 1)))
		if (d == 0) fail("an escape that is not hex: \\u" h)
		v = v * 16 + d - 1
	}
	return v
}
function utf8(c) {
	if (c < 128) return sprintf("%c", c)
	if (c < 2048) return sprintf("%c%c", 192 + int(c / 64), 128 + c % 64)
	if (c < 65536) return sprintf("%c%c%c", 224 + int(c / 4096), 128 + int(c / 64) % 64, 128 + c % 64)
	return sprintf("%c%c%c%c", 240 + int(c / 262144), 128 + int(c / 4096) % 64, 128 + int(c / 64) % 64, 128 + c % 64)
}
# A one-line "basic" string from P, its escapes decoded into S.
function basic(   c, n) {
	S = ""
	for (P++; P <= length(L); P++) {
		c = substr(L, P, 1)
		if (c == DQ) { P++; return }
		if (c != "\\") { S = S c; continue }
		c = substr(L, ++P, 1)
		if (c == "u" || c == "U") {
			n = c == "u" ? 4 : 8
			if (length(substr(L, P + 1, n)) < n) fail("a \\" c " escape cut short")
			S = S utf8(hex(substr(L, P + 1, n)))
			P += n
		} else if (index("btnfr" DQ "\\", c) == 0) fail("an escape TOML does not have: \\" c)
		else S = S (c == "b" ? "\b" : c == "t" ? "\t" : c == "n" ? "\n" : c == "f" ? "\f" : c == "r" ? "\r" : c)
	}
	fail("a string that does not end on its line")
}
# A one-line literal string from P into S.
function literal(   e) {
	e = index(substr(L, P + 1), SQ)
	if (e == 0) fail("a string that does not end on its line")
	S = substr(L, P + 1, e - 1)
	P += e + 1
}
# One key: bare, "basic" or literal, into S. 0 when there is none.
function part(   s) {
	ws()
	if (at(DQ)) { basic(); return 1 }
	if (at(SQ)) { literal(); return 1 }
	for (s = P; P <= length(L) && substr(L, P, 1) ~ /[A-Za-z0-9_-]/; P++);
	S = substr(L, s, P - s)
	return P > s
}
# A dotted key into K[1..KN].
function path() {
	KN = 0
	if (!part()) return 0
	K[++KN] = S
	for (ws(); at("."); ws()) {
		P++
		if (!part()) return 0
		K[++KN] = S
	}
	return 1
}
# Past a value, or to the end of its line when it goes on: ML (a multi-line
# string still open) and DEPTH (arrays and tables still open) carry on to the
# next line, where no key or header can begin.
function skip(   c) {
	while (P <= length(L)) {
		if (ML != "") {
			if (ML == DQ && at("\\")) { P += 2; continue }
			if (!at(ML ML ML)) { P++; continue }
			for (P += 3; at(ML); P++);
			ML = ""
			if (DEPTH == 0) return
			continue
		}
		c = substr(L, P, 1)
		if (at(DQ DQ DQ) || at(SQ SQ SQ)) { ML = c; P += 3; continue }
		if (c == DQ || c == SQ) {
			if (c == DQ) basic(); else literal()
			if (DEPTH == 0) return
			continue
		}
		if (c == "#") return
		if (c == "[" || c == "{") DEPTH++
		else if (c == "]" || c == "}") { if (--DEPTH == 0) { P++; return } }
		else if (DEPTH == 0 && index(" \t,", c)) return
		P++
	}
}
# A summary: a one-line string.
function text() {
	if (at(DQ DQ DQ) || at(SQ SQ SQ)) fail("a multi-line string; a summary is read on one line")
	if (at(DQ)) basic()
	else if (at(SQ)) literal()
	else fail("a summary that is not a string")
	return S
}
function header(   arrays) {
	P++
	arrays = at("[")
	if (arrays) P++
	if (!path()) fail("a table header it cannot read")
	ws()
	if (!at(arrays ? "]]" : "]")) fail("a table header with no closing bracket")
	if (K[1] != "operations" && K[1] != "verbs") { TABLE = "other"; return }
	if (arrays) fail("an array of tables; each operation is its own [operations.<key>] table")
	if (K[1] == "verbs" && KN > 1) fail("a table under [verbs]; each verb there is a string")
	if (KN == 1) { TABLE = K[1]; return }
	if (!(K[2] in SEEN)) { SEEN[K[2]] = 1; OPS[++N] = K[2] }
	OP = K[2]
	TABLE = KN == 2 ? "op" : "below"
}
function keyval() {
	if (!path()) fail("a line that is neither a [table] nor key = value")
	ws()
	if (!at("=")) fail("a key with no = after it")
	P++
	ws()
	if (TABLE == "op" && KN == 1 && K[1] == "summary") SUMMARY[OP] = text()
	else if (TABLE == "verbs" && KN == 1) VERBS[K[1]] = text()
	else if (TABLE == "operations" || TABLE == "verbs" || (TABLE == "" && (K[1] == "operations" || K[1] == "verbs")))
		fail("operations written as dotted keys or inline tables; each is read as its own [operations.<key>] table")
	else skip()
}
{
	L = $0
	sub(/\r$/, "", L)
	P = 1
	if (ML != "" || DEPTH > 0) { skip(); next }
	ws()
	if (P > length(L) || at("#")) next
	if (at("[")) header()
	else keyval()
}
END {
	if (BAD == "" && (ML != "" || DEPTH > 0)) BAD = "ajans.toml: a value that never ends"
	if (BAD != "") { printf "✗ %s\n", BAD > "/dev/stderr"; exit 65 }
	for (i = 1; i <= N; i++) {
		key = OPS[i]
		cut = index(key, " ")
		head = cut ? substr(key, 1, cut - 1) : key
		if (mode == "keys") { gsub(/[ .-]/, "_", key); print "op_" key }
		else if (verb != "") { if (head == verb && cut && cut < length(key)) print substr(key, cut + 1) "\t" SUMMARY[key] }
		else if (!(head in LISTED)) { LISTED[head] = 1; print head "\t" ((head in SEEN) ? SUMMARY[head] : VERBS[head]) }
	}
}' "$_ajans_root/ajans.toml"
}

_ajans_check() {
	local declared defined missing extra fn count noun
	declared="$(_ajans_toml keys)" || return
	declared="$(printf '%s\n' "$declared" | sort)"
	defined="$(declare -F | awk '$3 ~ /^op_/ { print $3 }' | sort)"
	missing="$(comm -23 <(printf '%s\n' "$declared") <(printf '%s\n' "$defined") | sed '/^$/d')"
	extra="$(comm -13 <(printf '%s\n' "$declared") <(printf '%s\n' "$defined") | sed '/^$/d')"
	if [[ -z "$missing" && -z "$extra" ]]; then
		count="$(printf '%s\n' "$declared" | grep -c .)"
		if [[ "$count" == 1 ]]; then noun=operation; else noun=operations; fi
		printf '✓ ajans.toml and ctl agree: %s %s\n' "$count" "$noun"
		return 0
	fi
	if [[ -n "$missing" ]]; then
		while IFS= read -r fn; do printf '✗ declared, no function: %s\n' "$fn" >&2; done <<<"$missing"
	fi
	if [[ -n "$extra" ]]; then
		while IFS= read -r fn; do printf '✗ function, not declared: %s\n' "$fn" >&2; done <<<"$extra"
	fi
	return 1
}

_ajans_main() {
	case "${1:-}" in
	'' | --list | -h | --help)
		_ajans_toml list
		return
		;;
	--check)
		_ajans_check
		return
		;;
	esac
	if [[ "${2:-}" == --list ]]; then
		_ajans_toml list "$1"
		return
	fi
	local fn
	if [[ $# -ge 2 ]]; then
		fn="$(_ajans_fn "$1 $2")"
		if declare -F "$fn" >/dev/null; then
			shift 2
			"$fn" "$@"
			return
		fi
	fi
	fn="$(_ajans_fn "$1")"
	if declare -F "$fn" >/dev/null; then
		shift
		"$fn" "$@"
		return
	fi
	if declare -F | awk -v p="$fn"_ '$3 ~ "^" p { found = 1 } END { exit !found }'; then
		printf '✗ %s needs a target: ./ctl %s --list\n' "$1" "$1" >&2
	else
		printf '✗ %s: not an operation of this project (./ctl --list)\n' "$*" >&2
	fi
	return 64
}

_ajans_main "$@"
# <<< ajans runtime <<<
