# Changelog

All notable changes to stable releases of Motion Photo Maker are documented here.

## [Unreleased]

### Added
- Add a small slideshow video editor that turns multiple selected photos into an H.264 MP4 for use as the video portion of a Motion Photo.
- Support long-press drag reordering, independent 0.5–10 second display duration for each photo, 1:1 / 16:9 / 9:16 output, preview playback and short fade-to-black transitions.
- Support selecting a local audio file as slideshow background music; short tracks loop automatically and long tracks are clipped to the slideshow duration, with AAC audio in the exported MP4.
- Save slideshow exports to `Movies/MotionPhotoMaker` and provide a shortcut into the single Motion Photo editor after export.

### Changed
- Debug/development builds now use the separate package id `com.example.motionphotomaker.dev`, version suffix `-dev`, and app label `Motion Photo Maker Dev`, so they can be installed alongside the signed stable release.

### Planned
- Continue improving preview/export alignment across device codecs and rotation metadata.
- Improve editing ergonomics and export diagnostics.

## [0.2.0] - 2026-09-14

### Added
- PNG cover image support with conversion to the JPEG primary image used by Motion Photo.
- An image-aspect output option that follows the selected cover image ratio.
- Progressive precision zoom for trimming short clips from long videos.
- 4, 6 and 9-grid Motion Photo projects with one video per square tile.
- An independent 1:1 editor for every grid video with per-tile trim-in, trim-out, zoom and pan.
- Batch video selection, per-tile replacement, grouped MediaStore output and multi-image WeChat sharing.

### Changed
- Precision timeline mode uses a local time ruler instead of stretching full-video thumbnails.
- Unedited grid videos keep full-duration centered 1:1 crop defaults, while edited tiles use saved parameters.
- The app now opens on a selector for single-photo editing or grid collage generation.

## [0.1.0] - 2026-09-14

### Added
- Generate Android/Google Motion Photo files from an arbitrary JPEG cover and MP4 video.
- Visual timeline trim-in and trim-out selection.
- Visual video crop, pan and 1x-4x zoom editing.
- Visual JPEG cover crop, pan and zoom editing.
- Aspect-ratio presets: original, 1:1, 4:3, 3:4, 16:9 and 9:16.
- Save generated Motion Photos to the Android media library.
- Launcher icon, project README and signed GitHub Release pipeline.

### Changed
- Video preview and export share normalized crop parameters.
- JPEG and video output use matching aspect ratios for better WeChat compatibility.
- Motion Photo XMP injection removes conflicting existing standard/extended XMP.
- Video export uses MediaCodec + OpenGL with H.264 output and preserves AAC audio when available.
- Project license changed to GNU GPL v3.

### Fixed
- TextureView startup crash caused by applying a background drawable.
- Black-frame exports seen with the earlier Media3 Transformer effect path.
- Live video pan/zoom preview being overwritten by ExoPlayer transforms.
- Duplicate video preview transforms that caused preview/export mismatch.

[0.2.0]: https://github.com/Vincent-ice/Motion_Photo/releases/tag/v0.2.0
[0.1.0]: https://github.com/Vincent-ice/Motion_Photo/releases/tag/v0.1.0
