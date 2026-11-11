# Agentic Binning

**Your photos and videos, understood and sorted by an AI that lives on your phone.**

If you shoot a lot of photos and video — trips, family events, video projects — you know the problem: a folder full of files named `20260924_133728.jpg`, and no easy way to remember what's what or find anything later. Agentic Binning fixes that. You point it at a project folder, and an AI model running entirely on your phone's NPU looks at every photo and video, gives each one a plain, friendly name (like "Sea Turtle Swimming"), writes a short description, and tags it with a category and shot type. When you're ready, tap **Bin Files** and everything is moved into tidy category folders — Sunsets, Wildlife, Waves, and so on. Later, you can search your whole project with a word like "turtle" and get matching media instantly. Nothing has to leave your phone: the AI runs on-device, so it works offline, it's fast (about 32 tokens per second on a current Snapdragon), and your media stays yours.

## Screenshots

<p>
  <img src="docs/images/analysis-on-npu.png" width="240" alt="The app analyzing a photo on the NPU with the on-device model">
  <img src="docs/images/project-media.png" width="240" alt="A project folder sorted into categories, ready to bin">
  <img src="docs/images/search.png" width="240" alt="Searching a project for turtle photos and videos">
</p>

*Left to right: the on-device model analyzing a photo · a project sorted into categories · searching the project for "turtle".*

## How it works

1. **Pick a project folder** on your phone — a trip, a shoot, an event.
2. **Tap Analyze.** The app looks at each photo and video with a vision model (Liquid AI's LFM3-VL-2B) running on the Snapdragon NPU through Qualcomm's GenieX. For each file it writes a friendly name, a short description, tags, a category, and a shot type.
3. **Browse, search, and filter** by category, keyword, or photo vs. video.
4. **Tap Bin Files.** The app moves every file into a subfolder named for its category, so the folder on disk ends up organized the same way it looks in the app.

The first time you analyze something, the app downloads the model from Hugging Face. After that, analysis is fully on-device and works with no connection at all.

### Optional: cloud analysis

If you prefer, the app can also analyze media with Claude (by Anthropic) instead of the on-device model. That mode is off unless you turn it on and supply your own Anthropic API key, and in that mode the media you analyze is sent to Anthropic's service. On-device analysis never sends anything anywhere.

## What you need

- An Android phone running **Android 12 or newer**.
- For on-device analysis at full speed, a **Snapdragon chip with a Hexagon NPU** (the app was developed and tested on a Snapdragon-powered Galaxy phone). The model download on first use needs a Wi-Fi or data connection.
- A project folder of photos and/or videos to sort.

## Getting the app

Prebuilt APKs are attached to each release on the [Releases](../../releases) page. Download the APK, open it on your phone, and allow installing from your browser/files app when Android asks.

Or build it yourself — see below.

## Building from source

You need:

- **JDK 17**
- The **Android SDK** (the project targets SDK 35), with `ANDROID_HOME` set or a `local.properties` file pointing at it

Then:

```bash
git clone https://github.com/jdtimothy/Agentic-Video-Binning-Android.git
cd Agentic-Video-Binning-Android
./gradlew assembleDebug
```

The debug APK lands in `build/outputs/apk/debug/`. Dependencies — including the GenieX Android library — are pulled from Maven Central and Google's Maven repo automatically. No model files are in this repository; the app downloads its model at runtime.

Release builds are made by GitHub Actions: pushing a version tag (for example `v1.0.0`) builds the release APK and attaches it to a GitHub Release.

## Versioning

This project uses [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

- **MAJOR** — big changes that rework how the app behaves or handles your projects
- **MINOR** — new features that don't break anything existing
- **PATCH** — bug fixes

The version lives in `build.gradle` (`versionName` / `versionCode`), releases are tagged `vX.Y.Z` in git, and changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Privacy, in plain terms

- On-device analysis happens entirely on your phone. Your photos and videos are not uploaded, tracked, or phoned home.
- The app asks for access only to the folders you pick.
- Cloud analysis (Claude) is optional, off by default, uses your own API key, and is the only feature that sends media off the device.

## License

This project's own code is released under the [MIT License](LICENSE).

The app is built on other people's work, some of it under different terms — most importantly Qualcomm's GenieX (BSD 3-Clause) and the QNN / AI Engine Direct runtime libraries inside it (Qualcomm's AI Runtime SDK EULA), plus the LFM3-VL model (LFM Open License v1.0, downloaded at runtime, not distributed with the app). Please read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before redistributing builds.

## Credits

- [Qualcomm GenieX](https://github.com/qualcomm/GenieX) — on-device inference
- [Qualcomm AI Hub Apps](https://github.com/qualcomm/ai-hub-apps) — sample apps this project learned from (the image classification Android sample was the starting point)
- [Liquid AI](https://huggingface.co/LiquidAI/LFM3-VL-2B) — the LFM3-VL-2B vision-language model
