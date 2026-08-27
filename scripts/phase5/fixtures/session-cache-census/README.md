# `session-cache-census.py` parser fixtures

Excerpts in the exact shape `CLogFormatter` writes to `catalina.out`
(`HH:mm:ss.SSS`, one space at `INFO`, `Class.method: `, then the message), so
the parser is exercised against the log it actually reads rather than against a
convenient abstraction of it.

Session identifiers are literal, made-up, and deliberately **not** taken from a
real capture: an evidence file must never contain a real container session
identifier, and `capture-routed-lane.sh secrets` fails the lane if one appears.

| Fixture | Shape | What it pins |
|---|---|---|
| `legacy-destruction-with-census.log` | Frozen Tomcat 9 listener, session still registered | The seven **post-cleanup** lines are read, and the seven **pre-insertion** lines of the preceding `sessionCreated` block are not |
| `routed-destruction-no-census.log` | Frozen Tomcat 9 listener, routed session already unregistered by the rotation | Zero cache lines is a recorded destruction with **no census**, not an absent destruction |
| `routed-destruction-then-create.log` | The same block, immediately followed by a new `sessionCreated` block | The cache-line-less block does not absorb the next creation's pre-insertion lines |
| `routed-destruction-no-following-create.log` | The same block, truncated at end of log with no terminator and nothing after it | A destruction with nothing after it is still recorded |
| `modern-census.log` | Modern Jakarta listener, which writes a machine-readable census line | The census line wins, and the block it duplicates is not counted a second time |
| `interleaved-destructions.log` | A routed and a legacy destruction whose lines interleave | A block is closed by **its own** `Invalidate Session : <id>`, not by another session's |
| `creations-only.log` | Creations, no destruction at all | `destruction absent`, `census absent`, values `unknown` - never a fabricated zero |
