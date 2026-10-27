# Voice Billing

Say the items, get the bill. Kannada, English and Hindi. Kannada can run fully offline.

- `web/page.html` is the screen (bill, item list, settings) and `web/parser.js` understands what was said. The build joins them into `app/src/main/assets/index.html`; edit the `web/` files, not index.html.
- `MainActivity.kt` shows it and connects it to the microphone, speech and the phone's voice.
- `Speech.kt` uses the phone's speech recognizer (Google; on-device where the phone has a pack).
- `LocalSpeech.kt` is the offline engine: the app records the mic, Silero VAD splits phrases, and the Kannada model turns them into text.
- `ModelStore.kt` downloads the Kannada model once (~190 MB, resumable) into the app's private storage.

Builds on every push to `main`; the APK is at Releases › latest › VoiceBilling.apk.

## Run it from Android Studio
1. `git clone https://github.com/bampatil/voicebilling.git`
2. Android Studio › Open › the `voicebilling` folder. The first sync downloads Gradle, the Android tools, and the two speech files (sherpa-onnx.aar ~39 MB, silero_vad.onnx) — a few minutes, once.
3. Phone: Settings › About phone › Software information › tap Build number 7 times; then Settings › Developer options › USB debugging on. Connect the cable and allow the computer.
4. Pick the phone at the top of Android Studio and press Run ▶. It installs over the app from Releases (same signing key), keeping items and the downloaded Kannada model.

## Credits and licences
- Speech model: AI4Bharat IndicConformer (MIT), ONNX export by parismitaglobalsolutions/indicconformer-sherpa-onnx on Hugging Face. `tools/prepare_model.py` (workflow "Prepare Kannada model") removes an unused format label so ONNX Runtime 1.17 in the app can open it, test-runs it, and publishes it as release `model-kn-1`, which the app downloads.
- Speech engine: sherpa-onnx by k2-fsa (Apache-2.0), downloaded from its official releases at build time.
- Voice detector: Silero VAD (MIT), from the sherpa-onnx releases.
