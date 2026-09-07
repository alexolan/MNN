# RAG 自动化测试矩阵

## 目标

本矩阵用于追踪 APK 内置 Embedding、永久知识库和会话附件能力的自动化验证范围。Android 与 Gradle 测试不在本地执行，由 GitHub Actions 运行 `testStandardDebugUnitTest`；真实内置模型推理由 Android 仪器测试和后续设备验收执行。

## 单元与 Robolectric 测试

### 模型供应链与运行时

- `ModelManifestValidatorTest`
  - Manifest schema、模型角色、输入输出和维度契约
  - 文件大小与 SHA-256 校验
  - 路径穿越和非法模型包拒绝
- `BundledEmbeddingModelInstallerTest`
  - 首次安装、重复复用、损坏重装
  - 存储空间不足、中断临时目录清理
  - Manifest 升级和内容寻址目录切换
- `VectorStoreTest`
  - 向量追加、重启恢复、模型与维度不匹配
  - 非有限数值、截断载荷和未提交尾部恢复
- `VectorIndexingPipelineTest`
  - 批量 Embedding、向量输出校验和事务绑定

### 数据库与迁移

- `RagDatabaseTest`
  - 永久知识库及文档 SHA-256 去重
  - 文档、Chunk 和会话附件级联删除
  - 会话内去重、跨会话隔离和 Chunk 归属校验
  - 向量位置绑定和事务原子性
- `RagDatabaseMigrationTest`
  - 构造真实 v1 schema 和既有知识库、文档、Chunk 数据
  - 打开 v2 数据库后验证旧数据完整保留
  - 验证新增会话附件表可正常写入和查询

### 文档导入、解析与 OCR

- `DocumentImporterTest`
  - SAF 导入、类型限制、大小限制、SHA-256 和去重
- `DocumentParsersTest`
  - TXT 编码、Markdown 布局结构和输入限制
- `DocxDocumentParserTest`
  - 标题、段落、列表和表格
  - ZIP 路径安全、膨胀限制和损坏文档
- `PdfDocumentParserTest`
  - 文本层逐页提取、页码映射
  - 纯扫描及混合 PDF 的 OCR 分流
  - 文件头、大小、页数和加密文档限制
- `SessionAttachmentParsingPipelineTest`
  - TXT、Markdown、DOCX、PDF 和图片附件解析
  - 会话归属、状态推进、OCR 成功与失败隔离
- `RagResourceGovernanceTest`
  - 单附件、单会话数量及总容量限制
  - Chunk 数量限制、错误脱敏与截断
  - 私有附件安全删除和越界删除拒绝

### 会话附件生命周期

- `SessionAttachmentImporterTest`
  - 私有复制、SHA-256、持久化和回滚
  - 会话内去重、跨会话隔离和重复 URI
  - 大小及类型拒绝
- `SessionAttachmentIndexingOrchestratorTest`
  - 有界队列、任务去重和失败隔离
  - 附件及会话取消
  - 运行中取消和应用重启恢复顺序
- `SessionAttachmentUiPolicyTest`
  - 持久化状态到 UI 状态映射
  - 可恢复错误提示、重试和取消反馈

### 检索与上下文

- `ChunkingTest`
  - 确定性切分、Token 预算、Unicode 和重叠
- `RagRetrievalTest`
  - 余弦相似度、最低分、Top K 和可选 reranker
  - 永久知识库与当前会话附件联合排序
  - 严格 sessionId 隔离和来源预算
  - 引用、页码、标题路径和上下文字符预算
- `IndexingOrchestratorTest`
  - 永久知识库索引状态推进、失败隔离和重启恢复

## Android 仪器测试

- `BundledEmbeddingInferenceTest`
  - 从 APK assets 安装并加载真实内置模型
  - 验证输出维度 512、有限且非零
  - 验证相关中文语句相似度高于无关语句
  - 覆盖英文、Unicode、空白输入和超长文本

## GitHub Actions 接线

Debug 和 unsigned Release 工作流均执行：

```text
./gradlew --no-daemon --stacktrace testStandardDebugUnitTest
```

测试报告和 XML 结果在工作流结束时上传：

```text
app/build/reports/tests/
app/build/test-results/
app/build/reports/problems/
```

APK 构建后还会验证：

- 仅包含 `arm64-v8a`
- 内置模型资产文件清单、大小、SHA-256 和未压缩状态
- Debug APK 哈希
- Release APK 保持未签名

## 验收边界

- 本地阶段只执行源码结构、测试契约、工作流接线、`git diff --check`、Manifest、ZIP 和隔离覆盖验证。
- 不将未实际运行的 Gradle、Robolectric、Android 仪器测试声明为已通过。
- 最终自动化结论以 GitHub Actions 运行结果为准。
