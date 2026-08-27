#!/usr/bin/env python3
"""Tests for ``session-cache-census.py``.

Run directly (``python3 scripts/phase5/session_cache_census_test.py``) or through
the Gradle gate ``verifyPhase5eSessionCensusParser``.

Every case is driven through ``main`` against a fixture log rather than against
the helper functions, so what is pinned is the TSV contract
``capture-routed-lane.sh`` and ``RoutedCohortMatrixTest`` actually consume.
"""

import importlib.util
import hashlib
import io
import contextlib
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "fixtures", "session-cache-census")

# Loading the parser by path would otherwise leave a `__pycache__` beside the
# scripts, which is a build artefact in a source tree that ignores neither.
sys.dont_write_bytecode = True

_spec = importlib.util.spec_from_file_location(
    "session_cache_census", os.path.join(HERE, "session-cache-census.py"))
census = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(census)

CACHES = census.CACHES


def run(mode, log, offset=0, runtime="public", extra=None):
    """``(exit_code, {key: value})`` for one invocation over a fixture.

    Keys are the second TSV field, so ``destruction``, ``census`` and each
    cache name are read exactly as a consumer reads them.
    """
    path = log if os.path.isabs(log) else os.path.join(FIXTURES, log)
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = census.main(["session-cache-census.py", mode, runtime, path,
                            str(offset)] + (extra or []))
    rows = {}
    order = []
    for line in out.getvalue().splitlines():
        fields = line.split("\t")
        assert len(fields) == 3, line
        assert fields[0] == runtime, line
        rows[fields[1]] = fields[2]
        order.append(fields[1])
    return code, rows, order


class DestructionWithSevenCacheLines(unittest.TestCase):
    """The frozen listener recorded a session it still held."""

    def test_reports_the_post_cleanup_values(self):
        code, rows, _ = run("observe", "legacy-destruction-with-census.log")
        self.assertEqual(0, code)
        self.assertEqual("observed", rows["destruction"])
        self.assertEqual(census.FROM_BLOCK, rows["census"])
        self.assertEqual(
            {
                "Session-Cache": "2",
                "Session-Context-Cache": "2",
                "Application-Cache": "1",
                "Desktop-Cache": "2",
                "Execution-CarryOver-Cache": "0",
                "User-Preference-Cache": "2",
                "User-Authentication-Cache": "2",
            },
            {cache: rows[cache] for cache in CACHES})

    def test_does_not_read_the_preceding_creation_block(self):
        # The creation block in the same fixture carries 3/3/1/3/0/3/3, written
        # BEFORE SessionManager.createSession inserted anything.
        _, rows, _ = run("observe", "legacy-destruction-with-census.log")
        self.assertNotIn("3", [rows[cache] for cache in CACHES])

    def test_baseline_reads_the_same_record_as_observe(self):
        _, observed, _ = run("observe", "legacy-destruction-with-census.log")
        _, baseline, _ = run("baseline", "legacy-destruction-with-census.log")
        self.assertEqual({cache: observed[cache] for cache in CACHES},
                         {cache: baseline[cache] for cache in CACHES})
        self.assertNotIn("destruction", baseline)


class RoutedDestructionWithZeroCacheLines(unittest.TestCase):
    """The rotated routed session was already unregistered, so the frozen
    listener wrote its markers and no cache lines at all."""

    def test_is_a_recorded_destruction(self):
        code, rows, _ = run("observe", "routed-destruction-no-census.log")
        self.assertEqual(0, code)
        self.assertEqual("observed", rows["destruction"])

    def test_reports_no_census_rather_than_numbers(self):
        _, rows, _ = run("observe", "routed-destruction-no-census.log")
        self.assertEqual(census.NO_CENSUS, rows["census"])
        for cache in CACHES:
            self.assertEqual("no-census", rows[cache])

    def test_does_not_borrow_the_earlier_creation_values(self):
        # The creation block earlier in the same fixture carries 5s.
        _, rows, _ = run("observe", "routed-destruction-no-census.log")
        self.assertNotIn("5", [rows[cache] for cache in CACHES])

    def test_snapshot_agrees_with_observe(self):
        _, observed, _ = run("observe", "routed-destruction-no-census.log")
        _, snapshot, _ = run("snapshot", "routed-destruction-no-census.log")
        self.assertEqual(census.NO_CENSUS, snapshot["census"])
        self.assertEqual({cache: observed[cache] for cache in CACHES},
                         {cache: snapshot[cache] for cache in CACHES})


class FollowedByACreation(unittest.TestCase):
    """The reviewed defect: a cache-line-less destruction absorbing the next
    ``sessionCreated`` block's seven PRE-insertion lines."""

    def test_does_not_absorb_the_following_creation_block(self):
        _, rows, _ = run("observe", "routed-destruction-then-create.log")
        self.assertEqual("observed", rows["destruction"])
        self.assertEqual(census.NO_CENSUS, rows["census"])
        # The following creation carries 9/9/1/9/4/9/9. None of it may appear.
        self.assertEqual({"no-census"}, {rows[cache] for cache in CACHES})

    def test_is_bounded_even_without_its_own_terminator(self):
        # Same shape with the `Invalidate Session` terminator removed, so the
        # ONLY bound left is the next `Create Session Id`.
        source = os.path.join(FIXTURES, "routed-destruction-then-create.log")
        with open(source, encoding="ISO-8859-1") as handle:
            kept = [line for line in handle.read().splitlines()
                    if "Invalidate Session" not in line]
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False,
                                         encoding="ISO-8859-1") as scratch:
            scratch.write("\n".join(kept) + "\n")
            path = scratch.name
        try:
            _, rows, _ = run("observe", path)
        finally:
            os.unlink(path)
        self.assertEqual("observed", rows["destruction"])
        self.assertEqual(census.NO_CENSUS, rows["census"])
        self.assertEqual({"no-census"}, {rows[cache] for cache in CACHES})


class NoFollowingCreation(unittest.TestCase):
    """A destruction truncated at end of log, with no terminator and nothing
    after it. The earlier parser discarded it and reported ``absent``."""

    def test_is_still_a_recorded_destruction(self):
        code, rows, _ = run("observe",
                            "routed-destruction-no-following-create.log")
        self.assertEqual(0, code)
        self.assertEqual("observed", rows["destruction"])
        self.assertEqual(census.NO_CENSUS, rows["census"])
        self.assertEqual({"no-census"}, {rows[cache] for cache in CACHES})


class ModernCensusLine(unittest.TestCase):
    """The Jakarta listener writes a machine-readable census after the mutation
    on both ends."""

    def test_observe_reads_the_after_destroy_census(self):
        _, rows, _ = run("observe", "modern-census.log", runtime="modern")
        self.assertEqual("observed", rows["destruction"])
        self.assertEqual(census.FROM_CENSUS, rows["census"])
        self.assertEqual({"0"}, {rows[cache] for cache in CACHES})

    def test_snapshot_reads_the_newest_record_of_either_point(self):
        _, rows, _ = run("snapshot", "modern-census.log", runtime="modern")
        self.assertEqual(census.FROM_CENSUS, rows["census"])
        self.assertEqual({"0"}, {rows[cache] for cache in CACHES})

    def test_the_after_create_census_is_never_the_observed_reading(self):
        # Truncate to the creation only: `observe` must report no destruction
        # rather than fall back to the after-create census, which is a reading
        # of a different point in the lifecycle.
        source = os.path.join(FIXTURES, "modern-census.log")
        with open(source, encoding="ISO-8859-1") as handle:
            lines = handle.read().splitlines()
        head = lines[:[i for i, l in enumerate(lines)
                       if "Destroyed Session Id" in l][0]]
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False,
                                         encoding="ISO-8859-1") as scratch:
            scratch.write("\n".join(head) + "\n")
            path = scratch.name
        try:
            _, observed, _ = run("observe", path, runtime="modern")
            _, snapshot, _ = run("snapshot", path, runtime="modern")
        finally:
            os.unlink(path)
        self.assertEqual("absent", observed["destruction"])
        self.assertEqual(census.NO_RECORD, observed["census"])
        self.assertEqual({"unknown"}, {observed[cache] for cache in CACHES})
        # The snapshot may report it, and says where it came from.
        self.assertEqual(census.FROM_CENSUS, snapshot["census"])
        self.assertEqual("1", snapshot["Session-Cache"])

    def test_the_duplicated_block_is_not_counted_twice(self):
        with open(os.path.join(FIXTURES, "modern-census.log"),
                  encoding="ISO-8859-1") as handle:
            lines = handle.read().splitlines()
        records = census._post_mutation_records(lines)
        self.assertEqual(
            [("after-create", "MODERNSESSIONDDDD4444"),
             ("after-destroy", "MODERNSESSIONDDDD4444")],
            [(point, session) for point, session, _, _ in records])


class InterleavedBlocks(unittest.TestCase):
    """Two destructions whose lines interleave in one log."""

    def test_a_block_is_closed_by_its_own_terminator(self):
        with open(os.path.join(FIXTURES, "interleaved-destructions.log"),
                  encoding="ISO-8859-1") as handle:
            lines = handle.read().splitlines()
        blocks = census._destruction_blocks(lines)
        self.assertEqual(["ROUTEDROTATEDEEEE5555", "LEGACYSESSIONFFFF6666"],
                         [session for _, session, _ in blocks])
        # The routed one owns no census; the legacy one keeps all seven values
        # even though another session's `Invalidate Session` sits inside it.
        self.assertIsNone(blocks[0][2])
        self.assertEqual({"1", "0"}, set(blocks[1][2].values()))
        self.assertEqual(len(CACHES), len(blocks[1][2]))

    def test_session_digest_selects_the_owned_destruction(self):
        path = os.path.join(FIXTURES, "interleaved-destructions.log")
        digest = hashlib.sha256(
            b"ROUTEDROTATEDEEEE5555").hexdigest()
        code, rows, _ = run("observe", path, extra=[digest])
        self.assertEqual(0, code)
        self.assertEqual("observed", rows["destruction"])
        self.assertEqual(census.NO_CENSUS, rows["census"])
        self.assertEqual({"no-census"}, {rows[cache] for cache in CACHES})

    def test_unknown_session_digest_does_not_borrow_another_record(self):
        path = os.path.join(FIXTURES, "interleaved-destructions.log")
        digest = hashlib.sha256(b"NOT-THIS-SESSION").hexdigest()
        code, rows, _ = run("observe", path, extra=[digest])
        self.assertEqual(0, code)
        self.assertEqual("absent", rows["destruction"])
        self.assertEqual(census.NO_RECORD, rows["census"])


class NoRecordAtAll(unittest.TestCase):
    """Nothing was destroyed. Reported as unknown, never as zero."""

    def test_reports_absent_and_unknown(self):
        code, rows, _ = run("observe", "creations-only.log")
        self.assertEqual(0, code)
        self.assertEqual("absent", rows["destruction"])
        self.assertEqual(census.NO_RECORD, rows["census"])
        self.assertEqual({"unknown"}, {rows[cache] for cache in CACHES})

    def test_a_creation_block_is_never_a_reading(self):
        # The fixture's creation block carries 6/6/1/6/2/6/6.
        _, rows, _ = run("snapshot", "creations-only.log")
        self.assertEqual(census.NO_RECORD, rows["census"])
        self.assertEqual({"unknown"}, {rows[cache] for cache in CACHES})


class OffsetAnchoring(unittest.TestCase):
    """Only records written after the mark are read."""

    def test_an_offset_past_the_destruction_reports_absent(self):
        path = os.path.join(FIXTURES, "legacy-destruction-with-census.log")
        with open(path, encoding="ISO-8859-1") as handle:
            total = len(handle.read().splitlines())
        _, rows, _ = run("observe", path, offset=total)
        self.assertEqual("absent", rows["destruction"])
        self.assertEqual(census.NO_RECORD, rows["census"])

    def test_an_offset_before_the_destruction_reports_it(self):
        _, rows, _ = run("observe", "legacy-destruction-with-census.log",
                         offset=1)
        self.assertEqual("observed", rows["destruction"])


class OutputContract(unittest.TestCase):
    """The TSV shape the shell and the browser test parse."""

    def test_observe_emits_destruction_then_census_then_seven_caches(self):
        _, _, order = run("observe", "legacy-destruction-with-census.log")
        self.assertEqual(["destruction", "census"] + list(CACHES), order)

    def test_baseline_and_snapshot_emit_census_then_seven_caches(self):
        for mode in ("baseline", "snapshot"):
            _, _, order = run(mode, "legacy-destruction-with-census.log")
            self.assertEqual(["census"] + list(CACHES), order, mode)

    def test_an_unknown_mode_is_refused(self):
        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = census.main([
                "session-cache-census.py", "guess", "public",
                os.path.join(FIXTURES, "creations-only.log"), "0"])
        self.assertEqual(64, code)
        self.assertEqual("", out.getvalue())


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0], "-v"])
