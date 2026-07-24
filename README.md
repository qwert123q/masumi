# masumi

masumi 是一款面向 Android 的漫画翻译应用。导入一章漫画后，它会自动完成文字检测、OCR、翻译、原文清理、中文嵌字和质量检查，并将成品保存到固定漫画库。

## 功能

- 导入 JPEG、PNG 和 WebP 漫画页面
- 在本地完成文字检测、OCR、原文清理与嵌字
- 通过 OpenAI 兼容接口翻译日文文本
- 自动维护术语表并统一人名、称谓和标点
- 支持断点续跑、后台处理和多漫画任务调度
- 缓存并复用本地模型，避免重复下载
- 自动保存成品，保留阅读历史并支持直接阅读

## 使用

1. 首次启动时选择一个固定文件夹作为漫画库。
2. 在应用内配置翻译服务。
3. 导入包含漫画图片的文件夹。
4. 等待流程完成，成品会自动保存到漫画库。

图片处理在本地完成；需要翻译的识别文本会发送到用户配置的翻译服务。翻译凭据保存在应用私有数据中。

## 构建

需要 JDK 17、Android SDK 36、Android NDK 28.2、CMake 3.22，以及一台 `arm64-v8a` Android 设备。

```bash
git submodule update --init --recursive
./gradlew :pipeline-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## 结构

- `app`：Android 界面、本地推理、任务调度及完整处理流水线
- `pipeline-core`：项目模型、状态管理、处理规则和产物协议

## 许可证

[GPL-3.0](LICENSE)
