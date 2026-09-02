# Vietnamese offline phrase model

`seed-v1` is deliberately a small, original FrostKeys-curated Vietnamese phrase seed. It is
**not** presented as a Leipzig, Wikipedia, or Wiktionary corpus model. Its source and output are
versioned together so the APK never needs a network connection to obtain phrase predictions.

Run the deterministic verifier from the repository root:

```powershell
python tools/vietnamese/test_phrase_model.py
```

It regenerates the expected bytes in memory, verifies the checked-in artifact and manifest, and
checks the required predictions `học → sinh`, `công → nghệ`, and `Việt → Nam`.

To intentionally refresh the artifact after reviewing a source edit, run
`build_phrase_model.py` locally, inspect its output and attribution, then commit the output. The
Gradle `verifyVietnamesePhraseModel` gate runs the non-writing check for `check`, `lintRelease`,
and personal APK packaging.

A future corpus-backed model must not be slipped into this seed. It needs a separate immutable
source lock with snapshot/version, SHA-256, licence, attribution, and an offline preprocessing
record that documents NFC normalization and data/PII review before it can replace or extend this
artifact.
