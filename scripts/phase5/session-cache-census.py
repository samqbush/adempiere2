#!/usr/bin/env python3
"""Phase 5e: read SessionManager cache sizes out of a container log.

Why this exists at all
----------------------

The Phase 5e scope forbids adding a diagnostics endpoint, so the only way to
observe the seven ``SessionManager`` caches is the log line ADempiere's own
``SessionManagerListener`` already writes. The first version of this capture
took "the last occurrence of each cache line anywhere in the log", which was
wrong in a way that made the lifecycle assertion vacuous:

* ``sessionCreated`` writes its per-cache lines **before**
  ``SessionManager.createSession`` inserts anything, so a reading taken from a
  creation is a pre-insertion reading.
* ``sessionDestroyed`` writes its lines **after** the cleanup, so a reading
  taken from a destruction is a post-removal reading.

Comparing one against the other compares two different points in the session
lifecycle. This module therefore only ever reads **post-mutation** records:

``census line``
    The single machine-readable line the modern (Jakarta) listener writes after
    the mutation on both ends, carrying the session identifier and the point.
    Preferred whenever it is present.

``destruction block``
    The frozen Tomcat 9 listener cannot be changed - it is the Phase 5b oracle -
    so its post-cleanup block is parsed instead. It is anchored on
    ``Destroyed Session Id`` so a creation block can never be mistaken for it.

Both forms are read only from lines **after a recorded offset**, so "a
destruction happened because of this lifecycle action" is proved rather than
inferred from whatever event happened to be last.

Bounding a destruction block, and why a routed one owns no census
-----------------------------------------------------------------

The frozen listener writes its seven cache lines **only inside**
``if (SessionManager.existsSession(id))``, and then always writes its own
``Invalidate Session : <id>`` terminator::

     Destroyed Session Id : <id>
    ------------------------------------------------
    [ seven cache lines, only when the session was still registered ]
           Invalidate Session : <id>
    ------------------------------------------------

A routed session is deliberately **not** registered by the time the container
destroys it: ``CohortRoutingFilter.rotateAndTicket`` calls
``request.changeSessionId()`` and ``discardLegacySessionState`` drops every
``SessionManager`` entry the pre-rotation identifier owned, so
``existsSession(rotatedId)`` is false and the block carries **zero** cache
lines. That is the normal, correct shape of a routed Tomcat 9 destruction, not
a truncated read.

An earlier version scanned forward from ``Destroyed Session Id`` until the next
``Destroyed Session Id``, which gave that zero-line block two ways to lie:

* if any ``sessionCreated`` block followed, the scan walked straight into it and
  adopted its seven **pre-insertion** cache lines as this destruction's
  post-cleanup reading; and
* if nothing followed, the block never reached seven values, was discarded
  entirely, and the destruction was reported ``absent`` - which is how a real,
  correctly cleaned-up routed logout looked like a missing cleanup.

Each block is therefore bounded by evidence it owns: its own
``Invalidate Session : <same id>`` terminator, the modern listener's census line
for the same session, or - failing both - the next ``Create Session Id`` or
``Destroyed Session Id`` marker, whichever comes first. A block that ends with
no cache lines is a **recorded destruction with no census**, reported as exactly
that rather than as a borrowed reading or an absent destruction.
"""

import hashlib
import re
import sys

CACHES = (
    "Session-Cache",
    "Session-Context-Cache",
    "Application-Cache",
    "Desktop-Cache",
    "Execution-CarryOver-Cache",
    "User-Preference-Cache",
    "User-Authentication-Cache",
)

# The frozen listener's label for each cache, in the same order.
LEGACY_LABELS = (
    "Session Cache",
    "Session Context Cache",
    "Application Cache",
    "Desktop Cache",
    "Execution CarryOver Cache",
    "User Preference Cache",
    "User Authentication Cache",
)

CENSUS = re.compile(r"SessionManager cache census point=(?P<point>\S+) "
                    r"session=(?P<session>\S+) (?P<values>.*)$")
DESTROYED = re.compile(r"Destroyed Session Id\s*:\s*(?P<session>\S+)")
INVALIDATED = re.compile(r"Invalidate Session\s*:\s*(?P<session>\S+)")
CREATED = re.compile(r"Create Session Id\s*:\s*(?P<session>\S+)")

# Where a reading came from, reported beside it so a consumer can tell the four
# cases apart instead of inferring them from the numbers.
FROM_CENSUS = "census-line"
FROM_BLOCK = "destruction-block"
NO_CENSUS = "none"      # a record this action owns, carrying no cache lines
NO_RECORD = "absent"    # no post-mutation record at all

AFTER_DESTROY = "after-destroy"


def _read(path, offset):
    with open(path, "r", encoding="ISO-8859-1", errors="replace") as handle:
        lines = handle.read().splitlines()
    return lines[offset:]


def _census_records(lines):
    """Every census line as (index, point, session, {cache: value})."""
    records = []
    for index, line in enumerate(lines):
        match = CENSUS.search(line)
        if not match:
            continue
        values = {}
        for pair in match.group("values").split():
            if "=" not in pair:
                continue
            name, _, value = pair.partition("=")
            if name in CACHES:
                values[name] = value
        if len(values) == len(CACHES):
            records.append((index, match.group("point"), match.group("session"),
                            values))
    return records


def _destruction_blocks(lines):
    """Every frozen-listener destruction block as (index, session, values).

    ``values`` is the seven post-cleanup cache sizes, or ``None`` when the block
    carried none - the normal shape for a routed session, which the rotation had
    already unregistered before the container destroyed it.

    Every block is reported, with or without a census. A destruction that
    happened is a fact about the lifecycle; whether the frozen listener also
    printed cache sizes for it is a separate fact, and collapsing the two is
    what made a clean routed logout read as an absent destruction.
    """
    blocks = []
    index = 0
    while index < len(lines):
        marker = DESTROYED.search(lines[index])
        if not marker:
            index += 1
            continue
        session = marker.group("session")
        values = {}
        cursor = index + 1
        while cursor < len(lines):
            line = lines[cursor]
            # This block's own terminator. Anchored on the identifier, so an
            # interleaved session's terminator cannot close this block early.
            ended = INVALIDATED.search(line)
            if ended and ended.group("session") == session:
                cursor += 1
                break
            # The modern listener writes no `Invalidate Session` line; its
            # census is the last thing it writes for this session.
            censused = CENSUS.search(line)
            if censused and censused.group("session") == session:
                cursor += 1
                break
            # Backstops for a truncated or interleaved block. `Create Session
            # Id` is the one that matters: without it a cache-line-less block
            # walks into the next creation and adopts its PRE-insertion lines.
            if censused or DESTROYED.search(line) or CREATED.search(line):
                break
            for canonical, label in zip(CACHES, LEGACY_LABELS):
                if canonical in values:
                    continue
                found = re.search(re.escape(label) + r"\s*:\s*(\d+)", line)
                if found:
                    values[canonical] = found.group(1)
            cursor += 1
            if len(values) == len(CACHES):
                break
        blocks.append((index, session,
                       values if len(values) == len(CACHES) else None))
        index = max(cursor, index + 1)
    return blocks


def _post_mutation_records(lines):
    """Every post-mutation record, oldest first.

    Each record is ``(point, session, values, source)``. ``values`` is ``None``
    for a destruction the frozen listener recorded without cache lines, and
    ``source`` is then ``NO_CENSUS``.
    """
    census = _census_records(lines)
    records = [(index, point, session, values, FROM_CENSUS)
               for index, point, session, values in census]
    censused_ends = [(index, session) for index, point, session, _ in census
                     if point == AFTER_DESTROY]
    for index, session, values in _destruction_blocks(lines):
        # The modern listener writes both a block and a census for the same
        # destruction. They are one record and the census is the canonical
        # form, so the block is dropped rather than counted twice.
        if any(ended == session and at > index for at, ended in censused_ends):
            continue
        records.append((index, AFTER_DESTROY, session, values,
                        FROM_BLOCK if values is not None else NO_CENSUS))
    records.sort(key=lambda record: record[0])
    return [record[1:] for record in records]


def _reading(lines, destructions_only, session_digest=None):
    """The newest owned post-mutation record as (values, destroyed, source).

    The record is the newest one, full stop: when it carries no census this
    reports ``NO_CENSUS`` instead of reaching further back for an older record
    that happens to have numbers. An older record belongs to a different
    session, and reporting its numbers here is precisely the borrowing this
    module exists to prevent.
    """
    records = _post_mutation_records(lines)
    destroyed = any(point == AFTER_DESTROY for point, _, _, _ in records)
    if destructions_only:
        records = [record for record in records if record[0] == AFTER_DESTROY]
    if session_digest:
        records = [
            record for record in records
            if hashlib.sha256(record[1].encode("UTF-8")).hexdigest()
            == session_digest
        ]
        destroyed = bool(records)
    if not records:
        return None, destroyed, NO_RECORD
    _, _, values, source = records[-1]
    return values, destroyed, source


def main(argv):
    if len(argv) not in (5, 6):
        print("usage: session-cache-census.py snapshot|baseline|observe "
              "<runtime> <log> <offset> [session-sha256]", file=sys.stderr)
        return 64
    mode, runtime, path, offset = argv[1], argv[2], argv[3], int(argv[4])
    if mode not in ("snapshot", "baseline", "observe"):
        print("mode must be snapshot, baseline or observe", file=sys.stderr)
        return 64
    lines = _read(path, offset)
    # `baseline` and `observe` read the SAME class of evidence - the newest
    # destruction - so a mark and the observation taken against it are
    # comparable readings of the same point in the lifecycle. `snapshot` is the
    # unanchored at-rest reading and is deliberately not used for the lifecycle
    # comparison.
    session_digest = argv[5] if len(argv) == 6 else None
    values, found, source = _reading(
        lines, destructions_only=(mode in ("baseline", "observe")),
        session_digest=session_digest)
    if mode == "observe":
        print("%s\tdestruction\t%s" % (runtime, "observed" if found else "absent"))
    print("%s\tcensus\t%s" % (runtime, source))
    if values is None:
        # No census to report. `no-census` is a destruction this action owns
        # that the frozen listener recorded without cache lines; `unknown` is no
        # post-mutation record at all. Neither is defaulted to zero: a guessed
        # baseline is how a leak passes as balance.
        filler = "no-census" if source == NO_CENSUS else "unknown"
        values = {cache: filler for cache in CACHES}
    for cache in CACHES:
        print("%s\t%s\t%s" % (runtime, cache, values[cache]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
