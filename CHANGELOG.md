# Changelog

All notable changes to stable releases of **Motion Photo Maker** are documented in this file.

The project follows semantic versioning for stable releases. Development-only commits remain on the `develop` branch and are summarized here only when promoted to `main`.

## [Unreleased]

### Planned
- Continue improving preview/export alignment across more device codecs and rotation metadata.
- Improve editing ergonomics and export diagnostics.

## [0.1.0] - 2026-09-14

### Added
- Generate Android/Google Motion Photo files from an arbitrary JPEG cover and MP4 video.
- Visual video timeline with manual trim-in and trim-out selection.
- Visual video crop, pan and 1×–4× zoom editing.
- Visual JPEG cover crop, pan and zoom editing.
- Aspect-ratio presets: original, 1:1, 4:3, 3:4, 16:9 and 9:16.
- Save generated Motion Photos to the Android media library and open them in a gallery.
- New launcher icon.

### Changed
- Video preview and export now share the same normalized crop parameters.
- JPEG and video output are kept at the same aspect ratio for better WeChat Motion Photo compatibility.
- Motion Photo XMP injection removes conflicting existing standard/extended XMP and writes metadata before JPEG SOS.
- Video export uses a MediaCodec + OpenGL pipeline and H.264 output, preserving AAC audio when available.
- Project license changed from MIT to GNU GPL v3.

### Fixed
- Fixed `TextureView` startup crash caused by applying a background drawable.
- Fixed black-frame exports seen with the earlier Media3 Transformer effect path.
- Fixed live video pan/zoom preview being overwritten by ExoPlayer transforms.
- Fixed duplicate video preview transforms that caused preview/export mismatch.

[0.1.0]: https://github.com/Vincent-ice/Motion_Photo
