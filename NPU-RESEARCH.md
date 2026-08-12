# Snapdragon 8 Elite NPU research status

## Current product result

The shipping Qwen ONNX and native Qwen backends execute on CPU. The application reports this as
`requested CPU / actual CPU`; it must not display an NPU label for either path.

## Evidence-based paths investigated

1. **LiteRT + Qualcomm AI Engine Direct delegate.** Google documents Qualcomm's delegate for
   Snapdragon 8 Elite (SM8750), but it consumes LiteRT/TFLite models. The current Qwen ASR
   bundle is a multi-graph ONNX model, not a LiteRT model.
2. **ONNX Runtime QNN Execution Provider.** ONNX Runtime supports Android QNN builds using the
   Qualcomm AI Engine Direct SDK and supports the HTP backend. The packaged ONNX Runtime in this
   app comes from sherpa-onnx and has no QNN EP or QNN runtime libraries.

Primary references:

- [LiteRT Qualcomm NPU delegate](https://ai.google.dev/edge/litert/android/npu)
- [LiteRT Qualcomm integration and SM8750 support](https://ai.google.dev/edge/litert/android/npu/qualcomm)
- [ONNX Runtime QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- [ONNX Runtime Android/QNN build](https://onnxruntime.ai/docs/build/android.html)

## Required experimental implementation

An experimental `QwenQnnEngine` is only valid after all of the following are available:

1. Qualcomm AI Engine Direct SDK accepted and installed for build use.
2. An arm64 Android ONNX Runtime build compiled with `--use_qnn static_lib --qnn_home <SDK>`.
3. Qwen encoder, decoder-prefill, and decoder-decode graphs converted/validated as QNN-compatible
   QDQ models, with exact tokenizer and numerical regression tests.
4. QNN HTP runtime libraries packaged in the experimental artifact under their license terms.
5. HTP profiling/log output collected on the physical SM8750 device.
6. Runtime verification that records requested provider, actual provider, graph partition/fallback
   diagnostics, and timing. If a required graph falls back to CPU, the UI must say so.

`session.disable_cpu_ep_fallback=1` is useful for a strict per-graph validation run. It is not a
production default because partial offload must be diagnosed rather than hidden.

## Acceptance bar

Creating a delegate or loading a QNN library does not prove hardware offload. A result is only
reported as **Actual: Qualcomm HTP** if an on-device QNN profile/log proves the HTP graph ran and
the benchmark records that run. Until then, the stable Qwen ONNX INT8 CPU backend remains default.
