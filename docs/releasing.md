# 发布 release 版本

Release APK 必须签名，否则 Android 直接拒绝安装。签名配置从 Gradle 属性读取，
密钥库放在仓库之外，仓库里永远不出现密钥。

## 一次性准备

1. 生成密钥库（放在 `~/.masumi/`，不要放进仓库）：

   ```bash
   keytool -genkeypair -v \
     -keystore ~/.masumi/masumi-release.keystore \
     -alias masumi -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=masumi"
   ```

2. 在 `~/.gradle/gradle.properties` 里写入：

   ```properties
   MASUMI_RELEASE_STORE_FILE=/Users/<you>/.masumi/masumi-release.keystore
   MASUMI_RELEASE_STORE_PASSWORD=<密码>
   MASUMI_RELEASE_KEY_ALIAS=masumi
   MASUMI_RELEASE_KEY_PASSWORD=<密码>
   ```

**务必备份密钥库文件和密码。** 丢了它就无法再发布同签名的更新，
用户只能卸载重装（清掉工作区和设置）才能升级。

属性缺失时 `assembleRelease` 仍会构建，只是产物未签名——CI 和其他机器不受影响。

## 出包

```bash
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`，单文件即装：
AOT 修复模型已打进 assets，检测与 OCR 模型由应用在首次使用时自行下载。
要求 Android 9+（minSdk 28）、arm64-v8a 设备。

发布到 GitHub：

```bash
gh release create v<版本> app/build/outputs/apk/release/app-release.apk#masumi-<版本>-arm64.apk \
  --title "masumi <版本>" --notes "<说明>"
```
