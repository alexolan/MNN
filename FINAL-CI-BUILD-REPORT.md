# MNN 3.6.1 本地知识库 RAG 最终构建报告

生成日期：2026-09-06（Asia/Shanghai）

## 1. 最终状态

GitHub Actions 的 Debug 与 unsigned Release 工作流均已构建成功，增量覆盖、RAG 单元测试、Android APK 编译、ABI 校验、签名状态校验和产物上传全部通过。

- 最终修复提交：`b174f4c [Fix:CI] Align native library path and release tests`
- Debug 构建：成功
- Release 构建：成功
- 目标 ABI：`arm64-v8a`
- Release APK：未签名，符合工作流预期

## 2. 触发信息

### Debug

- Workflow：Android Debug Incremental Build
- Run ID：`34029335767`
- 链接：https://github.com/alexolan/MNN/actions/runs/34029335767
- 结论：`success`

### Release

- Workflow：Android Release Incremental Build
- Run ID：`34029335749`
- 链接：https://github.com/alexolan/MNN/actions/runs/34029335749
- 结论：`success`

## 3. 修复记录

1. 修复 RAG JVM 单元测试中的 Markdown 列表、引用解析、Chunk 预算、Robolectric 运行环境、提示词拼接及中文语言识别问题。
2. 重新生成增量 ZIP 和 Manifest，保证包内文件摘要与内容一致。
3. 修复 Release 单元测试变体错误。`VoiceDumperPlugin` 仅存在于 Debug source set，因此 Release 工作流改用 `testStandardDebugUnitTest` 执行共享 RAG JVM 测试，再独立构建 unsigned Release APK。
4. 修复 MNN Native 库路径不一致。`build_64.sh` 输出 `project/android/build_64/libMNN.so`，而 `mnn_tts` 期望 `project/android/build_64/lib/libMNN.so`，工作流在 Native 构建后创建并校验兼容路径。
5. 最终增量包 SHA-256：`c6f2411a38e9f8f314c655ce0afd872d25f499cc5b92777b56873c35833d30b1`。
6. 最终 Manifest SHA-256：`fc5fd28d07bb7efd362566c6ecb2a8079ea414aadad0c259f64a86e8e2d568b9`。

## 4. Debug 构建结果

以下关键步骤全部成功：

- 校验并应用增量 ZIP
- 构建 MNN arm64 Native 库
- 运行 RAG 单元测试
- 构建 standard Debug APK
- 校验 APK ABI 并生成摘要
- 上传 APK 和测试报告

产物：

- Artifact：`mnn-3.6.1-local-rag-debug`
- Artifact ID：`9988289296`
- Artifact 大小：`44,767,098` 字节
- Debug APK：`app-standard-debug.apk`
- APK SHA-256：`83c5b924f870502e7d796bbf0e7b84b2bc1c6913e0b22ad0caeb2a95acfdd030`
- ABI：`arm64-v8a`
- 报告 Artifact：`mnn-3.6.1-local-rag-debug-reports`
- 报告 Artifact ID：`9988289517`

## 5. Release 构建结果

以下关键步骤全部成功：

- 校验并应用增量 ZIP
- 构建 MNN arm64 Native 库
- 运行 RAG 单元测试
- 构建 unsigned standard Release APK
- 校验 APK ABI、签名状态和摘要
- 上传 APK 和测试报告

产物：

- Artifact：`mnn-3.6.1-local-rag-unsigned-release`
- Artifact ID：`9988289583`
- Artifact 大小：`30,958,569` 字节
- Release APK：`app-standard-release-unsigned.apk`
- APK SHA-256：`e604b67f015b106b1246475465c9a5aab659aca95815d8883dc9c3de92a72cd4`
- ABI：`arm64-v8a`
- 签名校验：`DOES NOT VERIFY`，符合 unsigned Release 预期
- 报告 Artifact：`mnn-3.6.1-local-rag-release-reports`
- 报告 Artifact ID：`9988289889`

## 6. 计划执行回顾

1. 推送固定为 `ubuntu-24.04` 的工作流配置。
2. 跟踪首次 GitHub Actions 构建并下载失败日志。
3. 修复 RAG 单元测试和测试运行环境问题。
4. 重建增量包并再次触发构建。
5. 定位 Release source set 编译问题和 Native 库路径问题。
6. 提交第二轮 CI 修复并再次触发构建。
7. Debug 与 Release 构建全部成功。
8. 下载产物并复核 APK 摘要、ABI 和 Release 未签名状态。

## 7. 最终结论

本次 GitHub Actions 构建闭环已完成。Debug 与 unsigned Release APK 均由 GitHub Actions 在 `ubuntu-24.04` 标准运行器上成功生成，RAG 单元测试通过，APK 均仅包含 `arm64-v8a`，Release APK 保持未签名状态。