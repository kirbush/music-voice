# Зависимости и лицензии

| Компонент | Источник и лицензия |
| --- | --- |
| sherpa-onnx 2.0.2 | [Исходники](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.2) · [Apache-2.0](../voice/licenses/sherpa-onnx.txt) |
| ONNX Runtime 1.28.2, в составе Android AAR sherpa-onnx | [Исходники](https://github.com/microsoft/onnxruntime/tree/v1.28.2) · [MIT](../voice/licenses/onnxruntime.txt) · [Third-party notices](../voice/licenses/onnxruntime-third-party.txt) |
| Kotlin Standard Library 1.9.20 | [Исходники](https://github.com/JetBrains/kotlin/tree/v1.9.20) · [Apache-2.0](../voice/licenses/kotlin.txt) |
| whisper.cpp b5454 | [Исходники](https://github.com/ggml-org/whisper.cpp/tree/b5454) · [MIT](../voice/licenses/whisper.cpp.txt). В сборку применяется локальный патч для выбора между русским и английским. |
| LLVM libc++ из Android NDK r27c | [Исходники](https://android.googlesource.com/toolchain/llvm-project/) · [Apache-2.0 с исключениями LLVM](../voice/licenses/libcxx.txt) |

## Модели

Файлы весов приложение загружает по проверяемым ссылкам из [каталога моделей](../voice/assets/model-files.tsv).

- **Whisper Base** основан на [OpenAI Whisper](https://github.com/openai/whisper#license). Источник ONNX-файлов — [sherpa-onnx-whisper-base](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base).
- **FUTO‑74** основан на [FUTO ACFT Whisper Base](https://huggingface.co/futo-org/acft-whisper-base) с лицензией Apache-2.0. [Метод и исходный GGML-файл FUTO](https://github.com/futo-org/whisper-acft). SHA-256 зеркала совпадает с исходной раздачей.
- Для ключевой фразы используются [английская модель Zipformer GigaSpeech](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/) и [русская Zipformer/Vosk](https://huggingface.co/alphacep/vosk-model-small-streaming-ru). В исходники включены небольшие таблицы токенизации этих моделей. За условиями использования и атрибуцией моделей следуйте их карточкам.

Тексты в этой папке описывают сторонние компоненты. Лицензия на собственный код приложения отдельно не объявлена.
