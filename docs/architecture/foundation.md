# Foundation architecture

## Scope

The foundation slice establishes the boundary between Android file access and the future localization pipeline. Its only user-facing operation is importing one chapter folder. It deliberately does not perform OCR, translation, cleanup, rendering, or export.

The design has two goals:

1. A later pipeline stage always reads stable, verified source objects instead of transient document-provider handles.
2. Every completed or failed import has a machine-readable report without exposing source filesystem paths or raw exception messages.

## Module boundary

`app` owns Android-specific behavior:

- system document-tree selection;
- persisted read permission when the provider grants it;
- direct-child enumeration through `DocumentsContract`;
- adapting document URIs to reopenable `SourceCandidate` streams;
- background execution and safe UI status text.

`pipeline-core` owns portable behavior:

- supported media detection;
- natural filename ordering;
- streaming copy and SHA-256 hashing;
- manifest and report schemas;
- staging, cleanup, and atomic publication.

No Android class is referenced by `pipeline-core`. This keeps the artifact contract testable on the JVM and reusable by future workers or command-line tooling.

## Import flow

1. Enumerate the selected folder's direct children without recursion.
2. Reject directories, hidden entries, and unsupported media.
3. Sort accepted pages naturally by display name.
4. Copy each page into project staging while computing SHA-256.
5. Reuse one stored source object when multiple page entries contain identical bytes.
6. Write the manifest and success reports into staging.
7. Atomically move the completed staging directory into the project collection.

If any source read or storage operation fails, staging is removed and no project directory is published. A sanitized report is written under `failed-reports` when storage remains available.

## Project artifacts

```text
workspace/
├── projects/
│   └── <project-id>/
│       ├── manifest.json
│       ├── sources/
│       │   └── <sha256>.<extension>
│       └── reports/
│           ├── <job-id>.json
│           └── <job-id>.txt
├── staging/
└── failed-reports/
    ├── <job-id>.json
    └── <job-id>.txt
```

The manifest records schema version, project identity, creation time, and ordered page records. Each page record contains the original display name, normalized media type, byte length, content hash, and a project-relative stored path.

The report records counts, copied bytes, timestamps, status, warnings, and a stable error code when applicable. It contains no provider credentials, absolute paths, hardware information, or raw exception text.

## Invariants

- Imported source objects are immutable.
- All stored paths are relative to the project.
- A visible project directory is complete.
- A failed import never publishes a partial project.
- Repeated bytes may share one source object while remaining separate ordered pages.
- Unknown JSON fields are rejected by the current schema reader so contract drift is explicit.

## Next slices

Future work can add page analysis and translation as separate stages that consume the manifest and emit new versioned artifacts. Detection, OCR, translation, cleanup, typesetting, quality scoring, and final export should remain independently reportable so a failed stage can be retried without reimporting or mutating the source pages.
