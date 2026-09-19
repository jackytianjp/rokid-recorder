# Rokid 会议记录键 (MeetingKey)

在 Rokid Glasses 上**按一下镜腿键就开始录 Rokid 原生会议记录**，不用再掏手机开 App。

界面只占屏幕最下方两行，不遮挡视线：

![界面](docs/screenshot.png)

## 功能

| 操作 | 效果 |
| --- | --- |
| 镜腿键单击 / 触控板轻点 | 开始录音 |
| 镜腿键双击 | 停止录音 |

- 录音期间眼镜屏显示 `● 00:12` 计时
- 用 `audio_no_ui` 后台静默录，不会弹出录音页抢画面
- 内部带看门狗：若录音被系统侧停掉，界面会自动复位为「待机」

## 产出文件

```
/sdcard/Recordings/record-YYYYMMDD-HHMMSS-xx.wav
```

PCM 16bit / 16kHz / 单声道 —— 与**手机 App 触发会议记录完全同一规格**，
手机 Rokid App 里的转写、导出照常可用。

## 安装

**免数据线（推荐）**：新版 Rokid AI App 支持通过 Wi-Fi 给眼镜安装 APK ——
手机和眼镜连同一网络，在 App 里选 APK 装上即可。

**传统 ADB**：

```bash
adb install -r MeetingKey-1.0.apk
adb shell pm grant com.rokid.meetingkey android.permission.RECORD_AUDIO
adb shell pm grant com.rokid.meetingkey android.permission.READ_EXTERNAL_STORAGE
```

APK 见本仓库 **Releases** 页面。

## 原理

Rokid Glasses 上，系统功能（会议记录、拍照、AI 等）**不走广播、也不走普通 Activity**，
而是 `assistserver` 的 binder 场景命令。核心就一行：

```kotlin
// IAssistServer.controlMsgJson(packageName, json) —— transaction 3
val json = """{"type":"cmd_start_audio_record","data":{"audioOpenType":"audio_no_ui"}}"""
```

- `audio_no_ui` = 后台静默录；`audio_with_ui` = 带录音页
- 服务 `com.rokid.os.sprite.assistserver/.assist.MasterAssistService`，`exported` 且无权限限制，普通 App 可 bind
- 本项目用 **raw Parcel** 直接发事务，不依赖任何 Rokid 私有 SDK，**不需要 root**
- 代码见 [`AssistBridge.kt`](app/src/main/java/com/rokid/meetingkey/AssistBridge.kt)

另一个坑：录音期间系统会把画面拉回主屏，触控板点击到不了自己的应用，
所以「停止」走**镜腿按键广播**（前台服务的动态注册接收器）。

## 兼容性

- 设备：Rokid Glasses（`RG-glasses`）
- 固件：`1.25.015-20260903-150201`（YodaOS-Sprite / Android 12 / API 32）
- 只在这个固件上实测过，其他版本欢迎反馈

## 构建

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
```

需要 SDK 组件 `platforms;android-36.1`（源码里是 `compileSdk = release(36) + minorApiLevel 1`）。
打正式包用 `./gradlew :app:assembleRelease`，需要自备签名文件（本仓库不含签名 key）。

## 项目结构

```
app/src/main/java/com/rokid/meetingkey/
  AssistBridge.kt       # assistserver binder 桥（raw Parcel，不依赖 Rokid SDK）
  MeetingKeyService.kt  # 常驻前台服务 + 手势分发 + 状态看门狗
  MainActivity.kt       # 极简状态界面
  ToggleActivity.kt     # 无界面一键触发（桌面快捷方式 / adb）
  Receivers.kt          # 开机自启
```

## 注意

- 非官方工具，固件升级后私有接口可能变化，届时需要适配
- 第三方 App 受后台策略限制：服务要在 App 处于前台时启动

## License

MIT
