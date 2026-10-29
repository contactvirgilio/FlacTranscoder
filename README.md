# Flac Transcoder

Android app (Jetpack Compose, Material 3 with dynamic color) that converts FLAC and WAV files and splits single-file albums.

## What it does

| Output | Encoder settings |
| --- | --- |
| MP3 320 | LAME, 320 kbps constant bitrate |
| MP3 V0 | LAME, `-q:a 0` variable bitrate (about 245 kbps) |
| FLAC | Lossless, compression level 8 (useful for WAV to FLAC, and for splitting) |

- **Inputs:** `.flac` and `.wav`.
- **Split single-file albums:** turn on the switch and pick the album file together with its `.cue` in one selection (matching names are paired automatically), or use *Add CUE sheet* on a file. If no `.cue` is attached, the app falls back to a CUESHEET tag or chapters embedded in the file. Tracks are written to a subfolder named `Artist - Album` as `NN - Title.ext`, tagged from the CUE sheet.
- **Tags and cover art** are carried over on whole-file conversions. Embedded chapters and CUE text are not copied into the output.
- Sources above 48 kHz are resampled for MP3 (88.2/176.4 kHz to 44.1 kHz, others to 48 kHz).
- Only single-file CUE sheets are supported. A CUE listing several files is rejected with a message.

## Build on GitHub

Every push runs the *Build APK* workflow (unit tests, then `assembleDebug`). Download the `FlacTranscoder-apk` artifact from the run. Push a tag such as `v2.0` to also attach the APK to a GitHub Release.

## Notes

- FFmpeg comes from `dev.ffmpegkit-maintained:ffmpeg-kit-audio` (community continuation of the retired FFmpegKit). It currently ships arm64-v8a only.
- No Gradle wrapper is committed; the workflow installs Gradle 8.10.2. Locally: `gradle wrapper --gradle-version 8.10.2`.
- Input files are copied to the app cache for processing, then written to the folder you pick.
- The APK is debug-signed, which is fine for sideloading.
