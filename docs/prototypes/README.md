# Validation prototypes

Small, standalone Python scripts used to sanity-check the algorithmic
design independent of the Java toolchain, before/alongside implementing
the real thing. Not part of the shipped system -- see
[`../BENCHMARKS.md`](../BENCHMARKS.md) for what each one produced and how
that maps to the actual Java implementation and its JUnit tests.

- `cms_bench.py` -- Count-Min Sketch sizing/hashing/error-bound check.
- `gossip_rounds.py` -- discrete-round push-gossip convergence scaling.

Run with `python3 docs/prototypes/cms_bench.py` etc. (no dependencies).
