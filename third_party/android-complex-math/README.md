# Meeting SDK complex math dependency

The meeting SDK ships a Termux `libandroid-complex-math.so` with 4 KB ELF load
alignment. This directory pins the BSD-licensed NetBSD implementation used by
Termux 0.2, including its headers, license and original recipe. Imported files
are unchanged; `source-lock.json` records their upstream Git blobs and SHA256.

`gradle/meeting-native.gradle` applies only to the optional meeting SDK project.
It stages its JNI inputs without the old prebuilt and builds this replacement
using the project's NDK 25.2.9519653 / CMake 3.22.1. Both ELF page-size linker
options are explicitly 16384; CMake checks pinned sources on configuration and
again whenever those inputs change. No Gradle cache or dependency checkout
source is patched. Other native libraries are retained byte for byte.

The SDK's generated outputs are relocated to the host's ignored `build/` and
`.cxx/` directories, avoiding Ninja's non-ASCII Windows path failure.
The replacement is ARM64, matching the existing shared application.

The original license and per-source notices ship in
`assets/notices/android-complex-math.txt`. Numerical smoke checks and ABI export
comparison accompany the APK's full ELF audit; static alignment alone is not
proof of operation on a 16 KB kernel.

Sources:
- [Pinned Termux package](https://github.com/termux/termux-packages/tree/706caf5e5ec39084ec19dc4c4fb99282fca85434/packages/libandroid-complex-math)
- [Android page-size build and runtime checks](https://developer.android.com/guide/practices/page-sizes)

## 中文

会议 SDK 原有的 Termux 复数数学库 `libandroid-complex-math.so` 按 4 KB 对齐。本目录包含 Termux 0.2 采用的 NetBSD 实现、头文件、BSD 许可和原始构建配方。`source-lock.json` 记录每个上游文件的 Git blob 和 SHA-256。

`gradle/meeting-native.gradle` 从会议 SDK 的 JNI 输入中筛出待替换的库，使用 NDK 25.2.9519653 和 CMake 3.22.1 为 ARM64 重新编译。两个 ELF 页大小链接选项均设为 16384。CMake 在配置和输入发生变化时核验源码，其余 JNI 库逐字节保留。

SDK 产物写入宿主忽略的 `build/` 和 `.cxx/` 目录，以兼容 Windows Ninja 对路径的要求。APK 内的 `assets/notices/android-complex-math.txt` 保留原许可及逐文件版权声明。数值冒烟测试、ABI 导出比对及全 APK ELF 检查覆盖不同环节，16 KB 内核上的实际运行需通过真机或模拟器验证。

[固定版本的 Termux 配方](https://github.com/termux/termux-packages/tree/706caf5e5ec39084ec19dc4c4fb99282fca85434/packages/libandroid-complex-math) · [Android 页大小文档](https://developer.android.com/guide/practices/page-sizes)
