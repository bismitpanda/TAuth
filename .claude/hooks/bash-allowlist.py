#!/usr/bin/env python3

import sys

sys.dont_write_bytecode = True

import json
import re

GRADLE = frozenset(
    {
        "./gradlew check",
        "./gradlew check --rerun-tasks",
        "./gradlew :shared:jvmTest",
        "./gradlew formatKotlin",
        "./gradlew lintKotlin",
        "./gradlew :shared:compileKotlinJvm",
    }
)

GIT_READ = frozenset(
    {
        "git diff",
        "git diff --stat",
        "git diff --cached",
        "git diff --cached --stat",
        "git status --short",
    }
)

ALLOWED = {
    "tauth-implementer": GRADLE | GIT_READ,
    "tauth-verifier": GRADLE | GIT_READ,
}

PATTERNED = (
    re.compile(r"\./gradlew :shared:jvmTest --tests '[A-Za-z0-9_.*]{1,60}'"),
    re.compile(r"rg -[nl] -- '[^']{1,139}'( [A-Za-z0-9_./-]{1,100})?"),
)

PATTERNED_FORMS = (
    "./gradlew :shared:jvmTest --tests '<Pattern>'",
    "rg -n -- '<regex>' [path]",
    "rg -l -- '<regex>' [path]",
)

PIPE_SUFFIX = re.compile(r"\s*\|\s*(?:head|tail)\s+-\d+$")

SUFFIX_FORM = "any of the above may end with a single | head -<number> or | tail -<number>"

MAX_COMMAND_LENGTH = 250


def deny(reason: str) -> None:
    decision = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }
    print(json.dumps(decision))
    sys.exit(0)


def main() -> None:
    try:
        event = json.loads(sys.stdin.read())
    except ValueError:
        deny("the hook could not read the tool call")
        return

    agent_type = event.get("agent_type")
    if agent_type not in ALLOWED or event.get("tool_name") != "Bash":
        sys.exit(0)

    tool_input = event.get("tool_input") or {}
    if tool_input.get("dangerouslyDisableSandbox"):
        deny("this agent may not disable the sandbox")

    command = (tool_input.get("command") or "").strip()
    if len(command) > MAX_COMMAND_LENGTH:
        deny("the command is longer than anything this agent is allowed to run")

    base = PIPE_SUFFIX.sub("", command, count=1).rstrip()
    if base in ALLOWED[agent_type] or any(p.fullmatch(base) for p in PATTERNED):
        sys.exit(0)

    permitted = sorted(ALLOWED[agent_type]) + list(PATTERNED_FORMS) + [SUFFIX_FORM]
    deny("Not a permitted command. Permitted:\n" + "\n".join(permitted))


if __name__ == "__main__":
    main()
