#!/usr/bin/env python3
"""Phase 5g write-capture normalization policy.

This module is the single place that decides what is volatile and what is
meaningful in a captured write effect. It is imported by
`measure-write-effect.py` and targeted directly by
`verify-write-normalizer-mutation-proof.sh`, so the policy can be mutated and
scored in isolation from the database machinery around it.

Two failure modes bound the policy from opposite sides, and both are proved
rather than argued:

  * **Under-normalization** leaves genuinely volatile data in the comparison, so
    two isolated captures of the same flow disagree. The A/B self-diff detects
    it.
  * **Over-normalization** erases the very differences the oracle exists to
    catch, so a broken run compares equal to a correct one. The committed raw
    fixture plus the mutation proof detects it.

The single most important rule here is that **generated identities are
normalized through a captured mapping and are never dropped**. Dropping them is
tempting -- they are the most volatile values in the capture -- but it erases
exactly the two defects the comparison exists to catch: a broken foreign-key
relationship between created rows, and a duplicated effect. Mapping them to
stable symbols keeps both observable while removing the volatility.
"""

from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation

# Columns whose value is volatile by construction and carries no business
# meaning. `Created` and `Updated` are wall-clock timestamps; they differ
# between any two captures and mean nothing to a transition.
#
# `CreatedBy` and `UpdatedBy` are deliberately NOT here. They identify the acting
# user, which is the whole point of the concurrency capture: without `UpdatedBy`
# the oracle cannot say which of two editors won.
VOLATILE_COLUMNS = frozenset(
    {
        "created",
        "updated",
        # `AD_ChangeLog.TrxName` is a per-save random value: Trx.createTrxName
        # appends a UUID (base/src/org/compiere/util/Trx.java), PO.save uses
        # "POSave" as its prefix, and MChangeLog persists the result. Two
        # captures of the same flow therefore always disagree on it, and the
        # disagreement says nothing about the runtime.
        "trxname",
        # The session identity is allocated at login. It is a real foreign key,
        # but AD_Session is not in the measurement scope, so it cannot resolve
        # to a capture-local symbol and would otherwise be frozen as a raw
        # integer that changes on every capture.
        "ad_session_id",
        # Every ADempiere table carries a `UUID` column whose value is a freshly
        # generated random identifier, allocated per row at insert. Run
        # 33486692102 captured one on the created business partner. It is not a
        # business fact, it is not a foreign key any in-scope row references, and
        # it differs between any two captures of the same flow, so left literal
        # it would make the A/B self-diff fail permanently for a reason that says
        # nothing about the runtime.
        "uuid",
    }
)

# Columns that hold a wall-clock timestamp with business meaning. The value is
# kept but reduced to a date, because the time-of-day component is volatile
# while the date is a business fact.
DATE_ONLY_COLUMNS = frozenset(
    {
        "datelastaction",
        "datenextaction",
    }
)

TIMESTAMP_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?$")

# A generated identity that the capture itself created is rendered as a stable
# symbol. The prefix is deliberately not a legal SQL identifier or number, so a
# symbol can never be mistaken for a real value in a diff.
SYMBOL_PREFIX = "@"


class IdentityMap:
    """Captured mapping from generated identity values to stable symbols.

    The mapping is built from the rows the capture CREATED, not from an ID-range
    heuristic. A heuristic such as "ids above one million are generated" is a
    guess about the seed that would silently misclassify rows the moment a
    sequence moved; membership in the created set is a fact about this capture.
    """

    def __init__(self) -> None:
        self._by_value: dict[tuple[str, str], str] = {}
        self._by_raw: dict[str, set[str]] = {}
        self._order: list[tuple[str, str, str]] = []
        # table name -> AD_Table_ID, from the reviewed attribution scope. Used
        # only to qualify a generic `Record_ID` pointer; see symbol_for.
        self._table_ids: dict[str, int] = {}

    def declare_table_id(self, table: str, ad_table_id: int) -> None:
        self._table_ids[table.lower()] = int(ad_table_id)

    def table_of(self, symbol: str) -> str | None:
        for table, _raw, sym in self._order:
            if sym == symbol:
                return table.lower()
        return None

    def declare(self, table: str, key_column: str, value: str) -> str:
        """Register a created row's primary key and return its symbol."""
        entry = (key_column.lower(), str(value))
        if entry not in self._by_value:
            ordinal = sum(
                1 for t, _c, _s in self._order if t == table
            ) + 1
            symbol = f"{SYMBOL_PREFIX}{table.lower()}#{ordinal}"
            self._by_value[entry] = symbol
            self._by_raw.setdefault(str(value), set()).add(symbol)
            self._order.append((table, str(value), symbol))
        return self._by_value[entry]

    def symbol_for(
        self, column: str, value: str, row: dict | None = None
    ) -> str | None:
        """The symbol for a value, if this capture created the row it names.

        Matching is by (column, value). A foreign key column named after its
        target table resolves directly, and the edge becomes visible in the
        normalized output.

        There is deliberately NO general "match on the value alone" fallback.
        ADempiere allocates every table's primary key from that table's own
        `AD_Sequence`, so distinct tables routinely hand out identical integers.
        A value-only fallback would rewrite any `*_id` column that merely
        collided with a created row's key -- `AD_ChangeLog.AD_Session_ID` is a
        large sequence-allocated value in the same range as `C_BPartner_ID` --
        and would render a foreign-key edge that does not exist. It would also
        be flaky in the other direction: the collision holds in one capture and
        not the next, so the A/B self-diff would fail for a reason that has
        nothing to do with the business transition.

        `Record_ID` is the one genuine exception. It is a generic row pointer,
        so it can never resolve by name, but ADempiere always pairs it with
        `AD_Table_ID`, which says which table it points into. It is resolved
        only when that companion column is present, maps to a table this
        capture created rows in, and the value is unambiguous within that
        table. Without the companion column it stays literal.
        """
        exact = self._by_value.get((column.lower(), str(value)))
        if exact is not None:
            return exact
        if column.lower() != "record_id" or row is None:
            return None
        raw_table_id = row.get("ad_table_id")
        if raw_table_id is None:
            return None
        try:
            table_id = int(raw_table_id)
        except (TypeError, ValueError):
            return None
        target = next(
            (t for t, tid in self._table_ids.items() if tid == table_id), None
        )
        if target is None:
            return None
        candidates = {
            sym
            for sym in self._by_raw.get(str(value), set())
            if self.table_of(sym) == target
        }
        if len(candidates) == 1:
            return next(iter(candidates))
        return None

    def rows(self) -> list[tuple[str, str, str]]:
        """(table, raw_value, symbol), in declaration order."""
        return list(self._order)


def normalize_value(
    column: str, value, identities: IdentityMap | None = None, row: dict | None = None
) -> str:
    """Normalize one column value to its comparable string form."""
    name = column.lower()

    if value is None:
        # Rendered explicitly. An empty string and a NULL are different facts,
        # and collapsing them would hide a column that stopped being populated.
        return "<null>"

    text = str(value)

    if name in VOLATILE_COLUMNS:
        return "<volatile>"

    if name in DATE_ONLY_COLUMNS:
        match = TIMESTAMP_RE.match(text)
        return match.group(1) if match else text

    # `Record_ID` is included because it is a generic row pointer, but it only
    # resolves when its companion `AD_Table_ID` qualifies it. See symbol_for.
    if identities is not None and (name.endswith("_id") or name == "record_id"):
        symbol = identities.symbol_for(name, text, row)
        if symbol is not None:
            return symbol

    # Numeric scale is a rendering artifact of the driver, not a business fact:
    # `10` and `10.00` are the same quantity and must not diff. The comparison
    # is on the numeric VALUE, so `10.5` and `10.50` normalize together while
    # `10.5` and `10.6` still differ.
    if _looks_numeric(text):
        try:
            return _canonical_number(text)
        except InvalidOperation:
            return text

    match = TIMESTAMP_RE.match(text)
    if match:
        # A timestamp column that is neither declared volatile nor declared
        # date-only keeps its full value. Silently truncating every unlisted
        # timestamp would be over-normalization by default.
        return text

    # Whitespace volatility only. Interior structure is preserved.
    return " ".join(text.replace("\u00a0", " ").split())


def _looks_numeric(text: str) -> bool:
    return bool(re.fullmatch(r"[+-]?\d+(?:\.\d+)?", text))


def _canonical_number(text: str) -> str:
    number = Decimal(text)
    normalized = number.normalize()
    # Decimal.normalize renders integers in exponent form (1E+3). Restore the
    # plain form so the output is readable and stable.
    if normalized == normalized.to_integral_value():
        return str(normalized.quantize(Decimal(1)))
    return format(normalized, "f")


def normalize_row(
    table: str,
    row: dict,
    identities: IdentityMap | None = None,
    skip_columns: frozenset[str] = frozenset(),
) -> dict[str, str]:
    """Normalize a whole row, dropping only explicitly skipped columns."""
    return {
        # The whole row is passed through so that a generic `Record_ID` can be
        # qualified by its companion `AD_Table_ID` in the same row.
        column: normalize_value(column, value, identities, row)
        for column, value in sorted(row.items())
        if column.lower() not in skip_columns
    }


def render_row(table: str, key: str, row: dict[str, str]) -> str:
    """One row as a single stable, diffable line."""
    body = ",".join(f"{column}={value}" for column, value in sorted(row.items()))
    return f"{table}\t{key}\t{body}"
