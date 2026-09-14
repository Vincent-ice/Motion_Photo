# Third-Party Licenses

This document records the directly declared libraries and build tools used by Motion Photo Maker v0.1.0.

## License decision

The application source is licensed under **GNU GPL v3.0**.

The project's direct Android/Kotlin dependencies are Apache License 2.0 components. The Free Software Foundation explicitly lists Apache License 2.0 as compatible with GPLv3, while noting that Apache-2.0 is not compatible with GPLv2-only. For that reason, this repository uses GPLv3 rather than GPLv2-only.

## Direct application dependencies

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| AndroidX Core KTX | 1.15.0 | Apache-2.0 | Runtime dependency |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 | Runtime dependency |
| AndroidX Activity KTX | 1.10.1 | Apache-2.0 | Runtime dependency |
| AndroidX Media3 Common | 1.11.0 | Apache-2.0 | Runtime dependency |
| AndroidX Media3 ExoPlayer | 1.11.0 | Apache-2.0 | Runtime dependency |

## Build toolchain

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| Kotlin / Kotlin Gradle Plugin | 2.2.21 | Apache-2.0 | Compiler/plugin; Kotlin runtime components are Apache-2.0 |
| Android Gradle Plugin | 8.10.1 | Apache-2.0 | Build-time dependency |
| Gradle | 8.11.1 in CI | Apache-2.0 | Build system |

## Compliance notes

- Apache-2.0 components retain their original copyright and license terms; placing this application under GPLv3 does not relicense those third-party components.
- Gradle may resolve additional transitive dependencies. When direct dependency versions are changed, the dependency tree should be re-audited before the next stable release and this file updated if additional license obligations appear.
- Upstream references: AndroidX/Media3 source headers and publication metadata use Apache-2.0; Kotlin documents Apache-2.0 as its license; Android Gradle Plugin source/POM metadata uses Apache-2.0.
- This is a project-maintenance summary, not legal advice.
