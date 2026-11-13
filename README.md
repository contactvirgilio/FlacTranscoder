# Flac Transcoder

Android app (Jetpack Compose, Material 3 with dynamic color) that converts FLAC and WAV files and splits single-file albums.

## What it does

| Output | Encoder settings |
| --- | --- |
| MP3 320 | LAME, 320 kbps constant bitrate |
| MP3 V0 | LAME, `-q:a 0` variable bitrate (about 245 kbps) |
| MP3 V2 | LAME, `-q:a 2` variable bitrate (about 190 kbps) |
| Ogg Vorbis | libvorbis, quality 8 variable bitrate (about 256 kbps) |
| FLAC | Lossless, compression level 8 (useful for WAV to FLAC, and for splitting) |

- **Inputs:** `.flac` and `.wav`.
- **Split single-file albums:** turn on the switch and pick the album file together with its `.cue` in one selection (matching names are paired automatically), or use *Add CUE sheet* on a file. If no `.cue` is attached, the app falls back to a CUESHEET tag or chapters embedded in the file. Tracks are written to a subfolder named `Artist - Album` as `NN - Title.ext`, tagged from the CUE sheet.
- **Tags and cover art** are carried over on whole-file conversions. Embedded chapters and CUE text are not copied into the output.
- Sources above 48 kHz are resampled for MP3 and Ogg Vorbis (88.2/176.4 kHz to 44.1 kHz, others to 48 kHz).
- **Speed:** several files (or several tracks of a split album) are encoded at once, up to half the phone's cores and at most four.
- Only single-file CUE sheets are supported. A CUE listing several files is rejected with a message.

## Build on GitHub

Every push runs the *Build APK* workflow (unit tests, then `assembleDebug`). Download the `FlacTranscoder-apk` artifact from the run. Push a tag such as `v2.0` to also attach the APK to a GitHub Release.

## Notes

- FFmpeg comes from `dev.ffmpegkit-maintained:ffmpeg-kit-audio` (community continuation of the retired FFmpegKit). It currently ships arm64-v8a only.
- No Gradle wrapper is committed; the workflow installs Gradle 8.10.2. Locally: `gradle wrapper --gradle-version 8.10.2`.
- Input files are read in place. Files from providers that cannot be read that way (some cloud storage) are copied to the app cache first. Outputs are encoded in the cache, then written to the folder you pick.
- The APK is debug-signed, which is fine for sideloading.
