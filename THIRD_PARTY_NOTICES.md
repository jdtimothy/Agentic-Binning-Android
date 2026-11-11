# Third-Party Notices

Agentic Binning's own code is under the MIT License (see [LICENSE](LICENSE)).
The app also includes, links against, or downloads the third-party components
below, which keep their own licenses. Building or redistributing this app —
especially the compiled APK — means these terms apply to those components.

## Qualcomm GenieX

- **What:** the GenieX Android library (`com.qualcomm.qti:geniex-android`),
  pulled from Maven Central at build time and merged into the APK. This
  project does not vendor GenieX source or binaries in this repository.
- **License:** BSD 3-Clause (https://github.com/qualcomm/GenieX/blob/main/LICENSE).
  Qualcomm's Terms of Use also apply to GenieX as distributed through its
  Maven artifact.
- GenieX was developed by Qualcomm Technologies, Inc. and/or its subsidiaries.

## Qualcomm QNN / AI Engine Direct (QAIRT) runtime libraries

- **What:** prebuilt native libraries (`libQnnHtp.so`, `libQnnSystem.so`,
  Hexagon HTP architecture libraries, and related files) that ship **inside**
  the GenieX Android AAR and therefore inside this app's APK.
- **License:** these libraries are **not** BSD 3-Clause. They are Qualcomm
  proprietary software distributed under the **Qualcomm AI Runtime SDK EULA**.
  They may be redistributed only as part of an application, not extracted or
  redistributed on their own. See the notices in the upstream
  geniex-qairt-plugin project (THIRD_PARTY_NOTICES.md) and Qualcomm's AI
  Runtime SDK terms.

## ggml / llama.cpp

- **What:** the GGUF inference path inside the GenieX runtime.
- **License:** MIT (https://github.com/ggml-org/llama.cpp/blob/master/LICENSE).

## LFM3-VL-2B model (Liquid AI)

- **What:** the vision-language model the app uses for on-device analysis.
- **License:** LFM Open License v1.0
  (https://huggingface.co/LiquidAI/LFM3-VL-2B).
- **Important:** the model is **not** distributed with this app or this
  repository. The app downloads it from Hugging Face the first time you run
  an analysis, under Liquid AI's terms, which apply between you and Liquid AI.

## Qualcomm AI Hub Apps (reference)

This project was developed with reference to sample code from
[Qualcomm AI Hub Apps](https://github.com/qualcomm/ai-hub-apps) — in
particular the image classification Android sample — which is licensed
under the BSD 3-Clause License.

## Other libraries

All of the following are resolved from Maven Central / Google's Maven repo
at build time; none are vendored in this repository.

| Component | License |
| --- | --- |
| AndroidX (Compose, Room, Lifecycle, DataStore, ExifInterface, Activity) | Apache License 2.0 |
| Kotlin and kotlinx (coroutines, serialization) | Apache License 2.0 |
| Coil 3 (image/video loading) | Apache License 2.0 |
| Haze | Apache License 2.0 |
| Anthropic Java SDK (optional cloud sorting) | MIT License |
| OkHttp (used by the Anthropic Java SDK) | Apache License 2.0 |
| JUnit, AndroidX Test (tests only) | Apache License 2.0 / EPL 1.0 |

The Apache License 2.0 text is available at
https://www.apache.org/licenses/LICENSE-2.0

---

### BSD 3-Clause License (GenieX, AI Hub Apps)

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
