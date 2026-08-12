# Offline Transcribe Lab architecture

## Session pipeline

Every explicit transcription start uses one pipeline:

`Trigger → profile → audio source → ASR engine → transcript → output sink → local history`

The current application implements the microphone and MediaProjection audio sources through
`WhisperEngine`, `AudioRecorder`, and `TranscriptionCoordinator`. New Android entry points must
call that pipeline rather than owning an independent recorder or decoder.

## Engine capabilities

`EngineCapabilities` is the contract between a model/backend and the UI. It covers streaming,
offline operation, language modes, context features, timestamps, partial hypotheses, translation,
supported languages, execution providers, CPU configuration, quantization, and system-audio
compatibility. Controls must be disabled or hidden when the selected capability is false.

Execution-provider diagnostics use two values: **requested** and **actual**. A requested Qualcomm
HTP/GPU path must be reported as CPU fallback unless runtime evidence proves the actual provider.

## Performance profiles

Eco, Balanced, Max Performance, and Benchmark are persisted preferences. Their thread counts are
derived from the current processor topology; no fixed thread count is treated as a device profile.

## Autonomous Capture privacy boundary

`CapturePolicy` has no capture-everything mode. A package must be explicitly allowlisted and is
rejected if it is denied, a secure window, or contains a sensitive accessibility field. The
denylist always wins. `EventJournal` is an fsync'd, append-only local write-ahead journal: a
minimal event is stored before a screenshot, OCR result, or report so interrupted sessions can be
recovered without claiming that unavailable content was captured.
