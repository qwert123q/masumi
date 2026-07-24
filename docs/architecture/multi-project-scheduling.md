# Multi-project scheduling

Masumi schedules chapters as a pipeline instead of running every chapter end to end or allowing every
stage to compete for the phone at once.

## Resource lanes

- The local lane has capacity one. Detection, OCR, cleanup, typesetting, quality, and export all acquire
  the same filesystem lock, including when a stage is started manually or from the isolated OCR process.
- The network lane runs one translation on smaller or thermally stressed devices and up to two on a
  phone with at least 6 GiB RAM, six processors, and no severe thermal status.
- A chapter has at most one active stage, so its artifact dependency chain remains deterministic.

The planner first fills free translation slots. While those requests wait on the network, the local lane
advances another chapter. When a network slot is empty, shorter detection/OCR work is favored so a new
translation can begin sooner; once network slots are occupied, completed translations proceed through
cleanup and typesetting.

## Durability and recovery

The project queue is committed to application preferences. The foreground scheduler reconciles it with
published artifacts, and every existing stage keeps its own page/window checkpoints. A process restart
therefore reconstructs the next stage rather than restarting a whole chapter. Failed, cancelled, or
quality-blocked projects are paused; tapping continue reactivates only that project.

The scheduler never stores provider credentials. Translation workers load the existing application
settings when they start, and model acquisition continues to use the shared verified model cache.
