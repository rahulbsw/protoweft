# RAP interoperability harness

Proves that `protoweft-rap` interoperates with Hydro Infinity's reference RAP implementations
(Apache-2.0, pinned to commit `e1a3e4b36e5f6143e03a59a7ad86e1abacfd22e8`), in both directions.
Not published and not part of the Gradle build.

    scripts/interop/rap.sh        # builds, starts everything, runs both directions, stops everything

- `rust-client/` — a standalone Cargo crate with git dependencies on Hydro's crates at the pinned
  commit (its `Cargo.lock` was seeded from Infinity's own lock file):
  - `rap-interop-client`: direction B. Hydro's runtime code (`ToolsetLoader`, `RapTool::execute`,
    `RapCallbackBridge`, `RapNotifier`) drives Protoweft's `RapToolServer` and checks every
    callback parses as `rap_protocol::RapCallback` and converts to the runtime's `InputMessage`.
  - `rap-stub-servers`: runs Hydro's in-process stub servers (`rap-test-servers`) for direction A.
- The Java side lives in `protoweft-rap/src/test/.../Hydro*InteropTest.java` (env-gated; skipped
  in the normal build).

Results, decisions and the differences found are recorded in `protoweft-rap/SPEC.md`, section
"Interoperability". Run output goes to `build/interop/rap/`.
