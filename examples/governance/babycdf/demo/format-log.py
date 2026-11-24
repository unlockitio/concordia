#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render Daml Script debug output as readable presentation text.

The SDK prints every `debug` call wrapped in a source location and a quoted,
escaped string literal, which is what makes a demo terminal unreadable:

    [DA.Internal.Prelude:557]: \\"[STEP 2][BUSINESS] The committee ...\\"

This filter turns that into the payload alone, decoded, so a room sees:

    The committee ...

It is a line filter, not an evaluator. It parses the wrapper with an explicit
string scanner and decodes only a fixed set of escape sequences. No general
decoder and no interpreter is ever applied to demo output, so nothing in a log
can be made to execute; a unit test asserts that none of those constructs
appear in this file at all. Anything it does not recognise is passed through
unchanged rather than guessed at, and every non-debug line, including real
errors and stack traces, is printed as it arrives.

Streaming is deliberate: input is read line by line and each result is flushed
immediately, so phase headings appear as the actions happen rather than after
the step finishes.

Usage:
    format-log.py [--verbose] [--raw FILE] < stream
      --verbose  also print technical [ACTION]/[TIMING] lines
      --raw      append every original input line, unmodified, to FILE
"""
import argparse
import sys

# The wrapper the SDK puts around a debug payload: `[<module>:<line>]: <literal>`.
# Observed as `[DA.Internal.Prelude:557]: ...`, but the module is whichever one
# called `debug`, so the shape is recognised rather than one fixed module name.
# A hand-written scanner is used instead of a regular expression so that an
# unusual payload cannot change how the wrapper is found.
PREFIX_END = "]: "

# Tags the demo uses, matched as an anchored leading tag and never as a
# substring. PHASE/BUSINESS/VOTE/DECISION/RESULT/EXPECTED-REFUSAL/EVIDENCE are
# presentation; ACTION and TIMING are the technical record.
TECHNICAL_TAGS = ("ACTION", "TIMING")
PRESENTATION_TAGS = (
    "PHASE", "BUSINESS", "VOTE", "DECISION", "RESULT", "EXPECTED-REFUSAL",
    "EVIDENCE",
)
# A recognised tag is stripped; an unrecognised one is kept with its text.
KNOWN_TAGS = TECHNICAL_TAGS + PRESENTATION_TAGS

# Only these escapes are decoded. An unknown escape is kept verbatim.
ESCAPES = {
    "n": "\n",
    "t": "\t",
    "r": "\r",
    '"': '"',
    "\\": "\\",
    "'": "'",
}


def strip_wrapper(line):
    """Return the literal inside an SDK debug wrapper, or None.

    Recognises `[<module>:<line>]: <literal>`, where module is a dotted Daml
    module name and line is a number. The wrapper must start at column zero and
    the location must look exactly like that, so an ordinary log line that
    merely contains a bracket is not mistaken for debug output. The location is
    bounded by the FIRST `]: `, which is why a payload containing `]: ` cannot
    confuse the split.
    """
    if not line.startswith("["):
        return None
    end = line.find(PREFIX_END)
    if end < 1:
        return None
    location = line[1:end]
    module, separator, number = location.rpartition(":")
    if not separator or not number.isdigit() or not module:
        return None
    # A Daml module is dotted identifiers; anything with whitespace or a
    # bracket is not one, and is left alone.
    if any(ch.isspace() or ch in "[]" for ch in module):
        return None
    return line[end + len(PREFIX_END):]


def decode_literal(text):
    """Decode one Daml/Haskell-style string literal.

    The SDK emits the literal already backslash-escaped, and in the observed
    output the surrounding quotes themselves arrive escaped as \\". Both the
    escaped and the plain form are accepted. Returns None when `text` is not a
    recognisable literal, so the caller can pass the line through untouched.
    """
    body = text.strip()
    if body.startswith('\\"') and body.endswith('\\"') and len(body) >= 4:
        body = body[2:-2]
    elif body.startswith('"') and body.endswith('"') and len(body) >= 2:
        body = body[1:-1]
    else:
        return None
    out = []
    index = 0
    while index < len(body):
        char = body[index]
        if char != "\\":
            out.append(char)
            index += 1
            continue
        if index + 1 >= len(body):
            # Trailing backslash: keep it rather than inventing a meaning.
            out.append(char)
            break
        following = body[index + 1]
        if following in ESCAPES:
            out.append(ESCAPES[following])
            index += 2
            continue
        if following == "x" or following.isdigit():
            # Numeric escape: \xHH.. or \DDD. Consume the digits and emit the
            # character only when it is printable ASCII; otherwise drop it.
            # Decoding is arithmetic on a digit string, never code execution.
            if following == "x":
                digits, cursor = "", index + 2
                while cursor < len(body) and body[cursor] in "0123456789abcdefABCDEF":
                    digits += body[cursor]
                    cursor += 1
                value = int(digits, 16) if digits else None
            else:
                digits, cursor = "", index + 1
                while cursor < len(body) and body[cursor].isdigit():
                    digits += body[cursor]
                    cursor += 1
                value = int(digits) if digits else None
            if value is not None and 32 <= value < 127:
                out.append(chr(value))
            elif value is not None and value in (9, 10, 13):
                out.append(chr(value))
            index = cursor
            continue
        # Unrecognised escape: keep both characters verbatim.
        out.append(char)
        out.append(following)
        index += 2
    return "".join(out)


def split_tags(payload):
    """Split a payload into (step_prefix_present, tag, remainder).

    The grammar is anchored: an optional `[STEP <n>]` at the very start,
    then an optional `[TAG]`, then the text. Tags are therefore recognised
    only in that position, never by searching the payload, so a narration line
    whose own words mention `[ACTION]` or `[TIMING]` is not misclassified as a
    technical line and not stripped. `tag` is None when the payload does not
    begin with one.
    """
    text = payload
    step = False
    if text.startswith("[STEP "):
        close = text.find("]")
        if close > 0 and text[6:close].strip().isdigit():
            text = text[close + 1:]
            step = True
            if text.startswith(" "):
                text = text[1:]
    tag = None
    if text.startswith("["):
        close = text.find("]")
        if close > 0:
            candidate = text[1:close]
            # A tag is a single uppercase word, optionally hyphenated:
            # ACTION, TIMING, BUSINESS, EXPECTED-REFUSAL. Anything else is
            # payload text that merely happens to start with a bracket.
            if candidate and all(ch.isupper() or ch in "-_" for ch in candidate):
                tag = candidate
                text = text[close + 1:]
    return step, tag, text.strip()


def is_technical(payload):
    """Whether this payload is a technical record rather than presentation.

    Decided by the ANCHORED leading tag only. A substring search would hide a
    presentation line that quotes the word `[ACTION]`, which is exactly the
    kind of silent loss this filter must not introduce.
    """
    _, tag, _ = split_tags(payload)
    return tag in TECHNICAL_TAGS


def presentable(payload):
    """The text to show for a decoded payload.

    The anchored `[STEP n][TAG] ` prefix is removed because the phase headings
    already say where the run is, and repeating it on every line is the noise
    the demo is trying to lose. An unrecognised leading tag is PRESERVED
    together with its text, so an unknown tag is never silently swallowed.
    """
    step, tag, text = split_tags(payload)
    if tag is not None and tag not in KNOWN_TAGS:
        # Unknown tag: keep it visible rather than discarding information.
        return ("[" + tag + "] " + text).strip()
    return text.strip()


HEADING_TITLE_WIDTH = 48

def heading(title):
    return '########## ' + title.center(max(HEADING_TITLE_WIDTH, len(title))) + ' ##########'

DISPLAY_HANDLES = {'member-a':'jonathan-ieu','member-b':'heslin-zenith','member-c':'shaul-da','member-d':'ali-abrar','member-e':'EduardoFromTheFuture','alternate-f':'Jatinp26','alternate-g':'alexatdrw'}

def display_roles(text):
    import re
    text = re.sub(r'(?<![A-Za-z0-9_-])(?:member-[a-e]|alternate-[fg])(?![A-Za-z0-9_-]|::)', lambda match: DISPLAY_HANDLES[match.group(0)], text)
    return text.replace(': YES (core)', ': YES (Voting member)').replace('; core ', '; Voting members ').replace('; alternates ', '; Alternates ').replace('5 permanent (core)', '5 Voting members').replace(' core YES', ' Voting members YES')

def recognized_role_narration(payload):
    import re
    if '\n' in payload or '\r' in payload or '::' in payload:
        return False
    role = r'(?:member-[a-e]|alternate-[fg])'
    return bool(re.fullmatch(r'\[STEP [1-6]\]\[BUSINESS\] ' + role + r': credential accepted \((?:direct|registry)\)\.', payload) or re.fullmatch(r'\[STEP [1-6]\]\[VOTE\] ' + role + r': (?:YES \(core\)|ABSENT \(no cast; not an abstention, not a NO\))', payload))

def layout_text(text):
    import re
    # Decode a literal separator only at an otherwise complete heading boundary.
    if text.startswith('\\n') and '\n' not in text:
        candidate = text[2:]
        if re.fullmatch(r'#+ +[A-Z0-9][A-Z0-9 :_-]* +#+', candidate):
            text = candidate
    match = re.fullmatch(r'(?:\\\\n|\n)?#+[ ]+([^\r\n#]+?)[ ]+#+', text)
    return heading(match.group(1).strip()) if match else text

def setup_line(text):
    import re
    title, separator, body = text.partition(': ')
    if separator and title in ('SANDBOX','COMMITTEE','CREDENTIALS','RULEBOOK','ACCESS','SETUP','INITIAL PENDING'):
        committee = re.fullmatch(r'opener [A-Za-z0-9_-]+; core \[(?:"?(?:member-[a-e]|alternate-[fg])"?[, ]*)+\]; alternates \[(?:"?(?:member-[a-e]|alternate-[fg])"?[, ]*)+\]', body)
        mapped = display_roles(body) if title == 'COMMITTEE' and committee and '::' not in body else body
        if title == 'COMMITTEE' and committee and '::' not in body:
            mapped = re.sub(r'\[([^\[\]\r\n]*)\]', lambda m: m.group(1).replace('"',''), mapped)
        return heading('LEDGER USERS' if title == 'ACCESS' else title) + '\n' + mapped
    return text

def render(line, verbose):
    """One input line -> the line to print, or None to drop it."""
    literal = strip_wrapper(line)
    if literal is None:
        # Not a debug line: a real error, a warning, runner output. Always keep.
        return layout_text(line)
    payload = decode_literal(literal)
    if payload is None:
        # Recognised wrapper but unrecognised literal: show it rather than
        # silently dropping something that might matter.
        return line
    if is_technical(payload) and not verbose:
        return None
    # A payload may itself contain newlines once decoded; each physical line is
    # presented on its own so nothing arrives as one very long wrapped blob.
    out = []
    for part in payload.split("\n"):
        shown = presentable(part)
        if shown:
            out.append(display_roles(layout_text(shown)) if recognized_role_narration(payload) else layout_text(shown))
    return "\n".join(out) if out else None


def known_setup_narration(payload):
    """Whole known redundant records only. A tag never establishes safety."""
    exact = {
        '[STEP 1][BUSINESS] All parties, committees and rules here are invented for this sandbox.',
        '[STEP 1][BUSINESS] No Canton Foundation authority. Nothing decided here binds anyone.',
        '[STEP 1][BUSINESS] Electorate: 7 members - 5 permanent (core) + 2 alternates.',
        '[STEP 1][BUSINESS] Alternates vote in their own name; they are not stand-ins.',
        '[STEP 1][BUSINESS] Quorum (attendance): at least 3 of 7 must take part.',
        '[STEP 1][BUSINESS] Majority (of votes cast): one option needs at least half.',
        '[STEP 1][BUSINESS] Steps 2-4 run the realistic case: 3 core YES, 4 ABSENT. Quorum met exactly.',
        '[STEP 1][BUSINESS] Each member receives one personal voting credential, valid 12 hours.',
        '[STEP 1][BUSINESS] All seven use the voting-right factory and registry. Same weight: 1 vote each.',
        '[STEP 1][BUSINESS] All 7 credentialed, 1 vote each.',
        '[STEP 1][BUSINESS] Credentials are re-checked when a result is counted, not only when issued.',
        '[STEP 1][BUSINESS] architecture-review v1 is now in force.',
        '[STEP 1][BUSINESS] Caveat: v1 is an UNVOTED seed placed by the organiser, not a Foundation rule.',
        '[STEP 1][BUSINESS] The first rule actually voted on is v2, in step 2.',
        '[STEP 1][BUSINESS] Watching agents get exactly 1 right: readAs organiser; no actAs.',
        '[STEP 1][RESULT] Provisioned opener-only reader and separate Intake user; Intake submission is not implemented here.',
        '[STEP 1][EVIDENCE] No demo scope allocated yet: no committee, no credentials, no rule book.',
        "[STEP 1][EVIDENCE] This is a fresh-run statement only. If a previous run's parties are still present on this participant they are included in the counts above; contract-level INITIAL STATE begins at step 2.",
    }
    if payload in exact:
        return True
    import re
    patterns = (
        r'\[STEP 1\]\[PHASE\] \n################ STEP 1: SETUP AND CREDENTIALS ################',
        r'\[STEP 1\]\[PHASE\] (?:\\n|\n)########## (?:INITIAL STATE|SANDBOX NOTICE|COMMITTEE|CREDENTIALS|RULE BOOK|AGENT RIGHTS|LEDGER EVIDENCE|FINAL STATE) ##########',
        r'\[STEP 1\]\[BUSINESS\] [^\r\n:]+: credential accepted \((?:direct|registry)\)\.',
        r'\[STEP 1\]\[EVIDENCE\] Participant before setup: parties known [0-9]+, ledger users [0-9]+ \[participant-level counts, not a contract query\]',
        r'\[STEP 1\]\[EVIDENCE\] Parties known: [0-9]+; ledger users: [0-9]+',
        r'\[STEP 1\]\[EVIDENCE\] VotingRights active: [0-9]+ \(direct [0-9]+, registered [0-9]+\)',
        r'\[STEP 1\]\[EVIDENCE\] Resolver \(governor\) active: [0-9]+; RuleRegistry active: [0-9]+',
        r'\[STEP 1\]\[EVIDENCE\] Agent read-only rights verified: [0-9]+ \(no actAs\)',
    )
    return any(re.fullmatch(pattern, payload) for pattern in patterns)

def main():
    import re
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--raw")
    parser.add_argument("--hide-sdk-info", action="store_true",
                        help="hide known SDK/JVM INFO lines only (raw log unchanged)")
    args = parser.parse_args()
    import os
    setup_summary = os.environ.get("DEMO_SETUP_FILTER") == "1" and not args.verbose and os.environ.get("VERBOSE", "0") != "1"
    raw = open(args.raw, "a", encoding="utf-8", errors="replace") if args.raw else None
    try:
        for line in sys.stdin:
            line = line.rstrip("\n")
            if raw:
                raw.write(line + "\n")
                raw.flush()
            if setup_summary:
                literal = strip_wrapper(line)
                payload = decode_literal(literal) if literal is not None else None
                if payload is not None:
                    _, _, setup_text = split_tags(payload)
                    if setup_text.startswith("[SETUP-SUMMARY] "):
                        print(setup_line(setup_text[len("[SETUP-SUMMARY] "):]), flush=True)
                        continue
                    elif payload.startswith("[SETUP-SUMMARY] "):
                        print(setup_line(payload[len("[SETUP-SUMMARY] "):]), flush=True)
                        continue
                    elif payload in (
                        "[STEP 1] allocating synthetic parties and governor; sandbox only",
                        "[STEP 1] direct and registered unit voting credentials issued to seven members",
                        "[STEP 1] EffectiveRule v1 is a sandbox seed created by the opener, not a voted or Foundation rule",
                    ):
                        continue
                    elif re.fullmatch(r'\[STEP 1\] dedicated user rights verified: \[CanReadAs [^\r\n\[\]]+\]', payload):
                        continue
                    elif known_setup_narration(payload):
                        continue
                    # Unknown tags and unclassified decoded SDK payloads follow
                    # the ordinary renderer, including permission/assert warnings.
            # Suppress only explicitly INFO-labelled infrastructure lines.
            # Warnings, errors and unrecognised lines still pass through.
            sdk_info = re.match(
                r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})? +\[INFO\] +\[(?:multi-package build|build)\](?: |$)",
                line)
            jvm_info = re.match(
                r"^(?:\d{4}-\d{2}-\d{2}[ T])?\d{2}:\d{2}:\d{2}(?:[.,]\d+)? +\[[^\]\r\n]+\] +INFO +com\.digitalasset\.[A-Za-z0-9_.$]+(?: +-| +:)",
                line)
            jvm_plain_info = re.match(
                r"^INFO +com\.digitalasset\.[A-Za-z0-9_.$]+(?: +-| +:)", line)
            known_shutdown = re.fullmatch(r'.* INFO (?:io\.netty\.channel\.epoll\.Epoll|org\.apache\.pekko\.actor\.CoordinatedShutdown|akka\.actor\.CoordinatedShutdown) - (?:Running CoordinatedShutdown with reason \[ActorSystemTerminateReason\]|.*Epoll.*)', line)
            if args.hide_sdk_info and (sdk_info or jvm_info or jvm_plain_info or known_shutdown):
                continue
            shown = render(line, args.verbose)
            if shown is not None:
                print(shown, flush=True)
    except BrokenPipeError:
        pass
    finally:
        if raw:
            raw.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
