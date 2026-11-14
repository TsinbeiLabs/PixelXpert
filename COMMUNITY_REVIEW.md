# 社区模块跟进评估（2026-11-13）

本次基于源码审查；`:app:assembleDebug` 和 `git diff --check` 已通过，设备运行效果需实机验证。

## PixelXpert-Next

审查基准：[main / 58bc420f](https://github.com/anirudhgupta109/PixelXpert-Next/commit/58bc420f)。本地之前跟进到的远端 main 为 `0b35d731`，两者之间有三项实质更新，另有合并、版本和发布流水线提交。

| 更新 | 本地情况 | 结论 |
| --- | --- | --- |
| [`e1c9a403`](https://github.com/anirudhgupta109/PixelXpert-Next/commit/e1c9a403)：Compose 右侧时钟 | `StatusbarMods.placeClock()` 仍将时钟放进旧 `system_icons`；Compose 管线可将该容器设为 GONE | 本次跟进：按当前视图树选择右侧容器，设置右侧时钟尺寸及垂直居中；网速视图也直接解析右侧容器 |
| [`12e48893`](https://github.com/anirudhgupta109/PixelXpert-Next/commit/12e48893)：锁屏手势与 ambient tap | `ScreenGestures` 仍依赖延迟 5 秒解析 `mPulsingWakeupGestureHandler`，Scene 分支也读取缓存的 `isDozing` | 高优先级后续迁移；统一传统/Scene 触摸入口、直接监听 PulsingGestureListener、动态读取 dozing 状态，需要验证 AOD 开关、单双击、长按手电与 QS/bouncer 阻断 |
| [`2b68367a`](https://github.com/anirudhgupta109/PixelXpert-Next/commit/2b68367a)：热重载与设置实时应用 | `XPLauncher` 接受热重载，但只释放旧 hook；未提供 Next 的 modpack 状态保存/恢复与后台线程退休机制 | 高优先级架构工作；不能只复制单个 modpack 的恢复函数。需要先处理 hook、线程、监听器与既有 framework view 的归属，再迁移各功能 |

Next 的时钟使用组合容器，本地移动原生 ComposeView；本次保留本地的左侧/居中尺寸规则及原生时钟可见性控制，没有直接套用 Next 的全部时钟实现。Next 的版本号与发布元数据不适合覆盖本地发行渠道。

## NativeComplements

审查基准：[main / 9e8f16e3](https://github.com/AIXINJUELUOAI/NativeComplements/commit/9e8f16e3)，源码版本 `0.9.19-signal-test1`。许可证为 Apache-2.0，可与本项目 GPLv3 组合；实际复制代码或资源时仍须保留源版权、许可证及相关 NOTICE，README 致谢不能替代这些义务。

以下推荐建立在 README、NOTICE 与指定实现审查上，不是对整个小窗引擎的运行验收。

| 内容 | 价值与建议 |
| --- | --- |
| 状态栏内容垂直偏移 | **优先候选**。`StatusBarOffset` 平移 `status_bar_contents` / 锁屏状态栏，与我们调整状态栏高度、左右 padding 的能力互补。可并入现有状态栏设置，复用本地偏好系统；需验证裁剪、刘海与点击坐标，避免再引入其 Provider/广播配置栈 |
| 原生旋转建议呈现 | **值得借鉴**。`RotationSuggestion` / Launcher 桥接保留系统建议方向和原生点击动作，自定义显示。与我们强制旋转的 `ScreenRotation` 是不同能力。其当前方向校验还应结合 proposal 过期/撤销身份检查，不能只凭方向相等接受旧点击 |
| 小窗任务判定与交互 | **研究价值高，整体移植成本高**。本地已有 AOSP freeform 启动手势；它额外提供 mini、贴边、IME 避让与任务切换。`c8eec766` 的“跳过当前小窗任务寻找真正全屏前台”值得在增加通知小窗时采用。完整引擎涉及 framework/SystemUI/Launcher 的 task、SurfaceControl 和 Binder 协作，需要单独设计及实机验收 |
| 按应用防摇一摇 | **功能候选，但不直接复制当前后端**。已读 `MotionPolicy`、`MotionAccess`、`SensorCommand`：以 UID 为隔离单位，经 SensorService `set-uid-state ... idle --user 0` 限制，然后 `reset-uid-state` 恢复。这是 UID 传感器策略，不是仅阻止加速度传感器；共享 UID 会合并规则，且写死 user 0。若引入必须明确实际影响范围，支持用户/工作资料及限制恢复 |
| Stacked Compose 双卡信号模型 | **兼容性研究可取**。`StackedSignalDisplay` 按真实 subscription ID 关联两卡，复制呈现模型而不修改共享原模型，保留感叹号和 carrier-change 状态。这种方法适合未来组合信号图标适配；固定阈值重映射格数不改善射频，不推荐默认采用 |
| 图标适配 | 本地已有算法单色图标和自定义 themed icon 颜色；逐应用资源覆盖、导入包和缓存刷新有增量价值，但须另审查导入/缓存完整实现。Perfect Icons 等外部素材不随其仓库提供，不应假设素材授权随 Apache-2.0 一并获得 |
| 横屏双栏 | 本地已有 `LandscapeSplitShade`；若特定 Compose 构建上现有资源 hook 不生效，再针对 `ComposeShadeLayout` 做适配，没有必要增加重复开关 |
| 弹幕通知、来电规则、固定边缘禁触 | 属于新增产品能力，优先级低于现有 A17 稳定性修复。弹幕成功显示后才取消原通知的设计可借鉴；来电拦截需单独验证双卡/重复来电与 Telecom；固定边缘禁触应与返回手势、多指输入一起验收 |

## 后续移植落实

按用户选择，小窗采用“增强现有 AOSP 小窗”范围。本次接入状态栏垂直偏移、原生旋转图标与缩放、通知 freeform 开窗及前台过滤、Stacked Compose 组合/隐藏信号适配，以及分层图标 ZIP/MTZ 导入与逐应用排除。运行代码和资源来源见 APK 随附的 `NativeComplements-NOTICE.txt`；README 已更新为实际移植致谢。

双卡适配保留原始 DualSim 与 subscription ID；旋转保留系统原生按钮生命周期。图标导入支持包名目录下的 `0.png` 背景和 `1.png` 前景（含 MTZ 内层 `icons` ZIP），选择一个主题源进行替换；普通单层图标、目录直接导入和通知小图标不在本次格式内。

### 实机验收

1. 状态栏偏移设为正/负/零；检查锁屏、旋转、刘海区域和点击命中，零值恢复原位。
2. SystemUI 与 Launcher 中分别触发旋转建议；检查简洁图标、缩放、点击、原生超时与撤销，关闭后恢复原生图标。
3. 从后台应用通知打开小窗；当前全屏应用的通知仍保持全屏，已有小窗/PiP 不应被当作全屏前台；验证闹钟、来电、工作资料与全屏通知仍走原生路径。
4. 以桌面手势和通知分别开窗；检查竖屏/横屏边界、系统栏/刘海避让、键盘输入、任务切换和关闭后恢复。
5. 在支持 Stacked Compose 的双卡构建上开启组合信号图标、连接/断开 Wi-Fi、隐藏/恢复 mobile slot；检查原生双卡信号、感叹号与网络切换状态恢复。
6. 导入含多个主题的 ZIP、MTZ 内层包；验证目录选择、彩色/主题图标、逐应用排除、工作资料原生图标、关闭及清空后恢复（含重启桌面），退出导入页及异常包不会发布半成品。

Next 锁屏手势与热重载生命周期仍为独立后续项。
