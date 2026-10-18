# Voice Billing

Say the items, get the bill. Kannada, English and Hindi; works without internet.

- `app/src/main/assets/index.html` is the screen (bill, item list, voice parser).
- `MainActivity.kt` shows it and gives it the microphone, the phone's speech recognizer and its voice.
- `Speech.kt` listens continuously, preferring Android's on-device recognizer, and checks or downloads the offline language pack.
- The app has no INTERNET permission.

Builds on every push to `main`; the APK is at Releases › latest › VoiceBilling.apk.
