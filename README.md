# Motion Photo Maker

<p align="center">
  <img src="docs/icon.svg" width="112" alt="Motion Photo Maker icon" />
</p>

<p align="center">
  <strong>在 Android 上把任意 JPEG 封面与 MP4 视频制作成 Motion Photo。</strong><br>
  可视化时间裁剪、比例裁剪、缩放与平移，并针对微信动态照片兼容性进行优化。
</p>

<p align="center">
  <a href="https://github.com/Vincent-ice/Motion_Photo/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/Vincent-ice/Motion_Photo?display_name=tag"></a>
  <a href="https://github.com/Vincent-ice/Motion_Photo/actions/workflows/build-apk.yml"><img alt="CI" src="https://github.com/Vincent-ice/Motion_Photo/actions/workflows/build-apk.yml/badge.svg?branch=develop"></a>
  <a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-blue.svg"></a>
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white">
</p>

## 功能

- 从任意 **JPEG + MP4** 生成单文件 Android / Google Motion Photo。
- 视频时间轴可视化，支持手动设置切入点与切出点，不限制为 3 秒。
- 视频支持实时预览的拖动、双指缩放与 1×–4× 缩放。
- 封面支持可视化拖动、缩放与裁剪。
- 支持原始、1:1、4:3、3:4、16:9、9:16 等输出比例。
- 视频与静态封面保持相同宽高比，以提升微信 Motion Photo 识别兼容性。
- 视频导出使用 MediaCodec + OpenGL，输出 H.264；有 AAC 音轨时保留音频。
- 生成标准 Motion Photo XMP，并清理可能冲突的旧 XMP / Extended XMP。
- 结果直接写入 Android 媒体库，可从系统相册打开。

## 下载

稳定版请只从 **GitHub Releases** 下载：

**[前往 Releases](https://github.com/Vincent-ice/Motion_Photo/releases)**

`develop` 分支的 Actions Artifact 属于开发构建，仅用于测试，不保证签名可持续覆盖安装。

## 使用方法

1. 选择一个 MP4 视频。
2. 在时间轴上调整切入 / 切出范围。
3. 选择输出比例，并在视频预览中拖动或缩放取景。
4. 选择 JPEG 封面，在封面预览中调整裁剪位置和缩放。
5. 点击“生成 Motion Photo”。
6. 在系统相册或支持 Motion Photo 的应用中查看 / 分享。

> 当前测试表明，微信是否接受 Motion Photo 与静态封面和动态视频的**宽高比一致性**高度相关；视频时长并非 3 秒硬限制，长视频也可被识别。不同微信版本、系统相册和厂商 ROM 的行为仍可能存在差异。

## Motion Photo 结构

本项目生成单文件 Motion Photo：

```text
JPEG
├─ APP1 / XMP Motion Photo metadata
├─ JPEG image data
└─ EOI (FF D9)
   ↓
MP4 video bytes
```

XMP 使用 Google / Android Motion Photo 的 `GCamera`、`Container` 和 `Item` 命名空间；MP4 紧跟在 JPEG EOI 后。

## 隐私

应用不声明 Internet 权限，编辑和生成过程在设备本地完成。选择的照片和视频不会由本项目上传到服务器。

## 开发构建

要求：

- JDK 17
- Android SDK / compileSdk 36
- Gradle 8.11.1
- Android Gradle Plugin 8.10.1
- Kotlin 2.2.21

构建 Debug APK：

```bash
gradle :app:assembleDebug
```

主要分支：

- `main`：稳定版代码。
- `develop`：开发与真机验证。

开发提交应先进入 `develop`，验证后再合并到 `main`。

## 正式发布

正式发布采用 **Git tag + GitHub Actions + 持久 release keystore + GitHub Release**：

```bash
git checkout main
git pull
git tag v0.1.1
git push origin v0.1.1
```

推送 `v*` 标签后，Release workflow 会：

1. 校验 tag 与 `versionName` 一致；
2. 从 GitHub Secrets 恢复 release keystore；
3. 构建签名的 Release APK；
4. 使用 `apksigner` 验证签名；
5. 生成 SHA-256 校验文件；
6. 自动创建 GitHub Release 并上传 APK 与校验文件。

首次配置签名、Secrets 和完整发布流程请阅读 **[docs/RELEASING.md](docs/RELEASING.md)**。

## Changelog

稳定版本变更记录见 **[CHANGELOG.md](CHANGELOG.md)**。

## 第三方依赖与协议

项目自身以 **GNU GPL v3.0** 发布。AndroidX、Media3、Kotlin、Android Gradle Plugin、Gradle 等直接依赖 / 工具链的协议记录见 **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**。

## License

Copyright © 2026 Vincent-ice

本项目根据 **GNU General Public License v3.0** 发布。详见 [LICENSE](LICENSE)。
