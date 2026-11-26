# EDITH (Android)

Say "Hey Jarvis" anywhere. EDITH hears you (through your Bluetooth glasses when they're connected),
sends what you said to your JARVIS PC at home over Tailscale, and speaks the answer.

## Build the app (free, no Android Studio)
1. On github.com make a new repository called EDITH.
2. Click "uploading an existing file" and drag in everything from this folder (including the .github folder).
3. Commit. Open the Actions tab and wait for "Build EDITH app" to finish (about 5 minutes).
4. Open the finished run and download "EDITH-app". Inside is app-debug.apk.

## Install on the phone
Open app-debug.apk on the phone and allow installing from that source.

## Set up
- Home computer address: the PC's Tailscale address plus :8765 (for example 100.84.12.7:8765)
- Phone code: the code JARVIS shows for phones
- Press Start EDITH, allow the microphone and notifications, then press "Keep EDITH running".
