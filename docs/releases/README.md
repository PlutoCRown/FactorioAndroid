# 版本发布说明

每个应用版本的 Release 正文保存在本目录的 `<版本号>.md`，例如 `0.3.13.md`。版本号读取自项目根目录 `gradle.properties` 的 `factorioVersionName`，与 APK 使用同一来源，不从 README 或 tag 注释中提取。

发布新版本时：

1. 更新 `gradle.properties` 中的 `factorioVersionName` 和递增的 `factorioVersionCode`。
2. 添加对应的 Markdown 文件，说明面向玩家的改动、已知问题和安装包选择。
3. 将源码和说明一起提交并推送，再推送对应的应用 tag；也可以在 Actions 的 Release 工作流填写 `release_tag` 手动发布。

正式发布前，工作流会检查对应说明文件存在且非空，并通过 `body_path` 将全文作为 Release 正文；同一 Release 再次发布时正文以该文件为准。缺少说明时在构建前报错，不发布没有说明的版本。手动运行且 `release_tag` 留空的构建不要求发布说明。

打 tag 不会自动修改 APK 版本。建议使用 `v<版本号>`，并让标签指向包含该版本源码及说明的提交。运行库附件使用的 `runtime-` 标签继续跳过应用发布。
