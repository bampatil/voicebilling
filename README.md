# Voice Billing

Say the items, get the bill. Kannada, English and Hindi. Kannada can run fully offline.

- `app/src/main/assets/index.html` is the screen (bill, item list, voice parser).
- `MainActivity.kt` shows it and connects it to the microphone, speech and the phone's voice.
- `Speech.kt` uses the phone's speech recognizer (Google; on-device where the phone has a pack).
- `LocalSpeech.kt` is the offline engine: the app records the mic, Silero VAD splits phrases, and the Kannada model turns them into text.
- `ModelStore.kt` downloads the Kannada model once (~190 MB, resumable) into the app's private storage.

Builds on every push to `main`; the APK is at Releases › latest › VoiceBilling.apk.

## Credits and licences
- Speech model: AI4Bharat IndicConformer (MIT), ONNX export by parismitaglobalsolutions/indicconformer-sherpa-onnx on Hugging Face.
- Speech engine: sherpa-onnx by k2-fsa (Apache-2.0), downloaded from its official releases at build time.
- Voice detector: Silero VAD (MIT), from the sherpa-onnx releases.
