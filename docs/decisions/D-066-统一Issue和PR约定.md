# D-066 与 domain-forge 统一 Issue 和 PR 约定（2026-11-09）

两个仓库都由 AI 编码工具主导研发，Issue 模板、PR 模板和提交前缀原来各写一套。人答复的四个决定：

- **同一份文件**：两个仓库各放一份内容相同的 `.github/ISSUE_TEMPLATE/`（结果、缺陷、不稳定测试）、`.github/pull_request_template.md` 和 `scripts/check_pr_guard.sh`，修改时同步另一个仓库；不建组织级 `.github` 仓库，本地 AI 要能直接读到模板。项目特有的内容（验证命令、评测、截图）写在 AGENTS.md，不写进模板。
- **前缀**：提交信息和 PR 标题只用 `feat`、`fix`、`build`、`docs`，CI 新增 `guard` 任务检查 PR 标题和 wip 提交（脚本来自 domain-forge）。
- **结果模板**：取两边字段之长——目标、已确认的决定、未决问题、验收标准、改动范围（含不做的）、开工（前置、分支、风险）、实施记录；原“要做的”“不做的”并入“改动范围”。Issue 标题不加前缀，用标签分类。
- **度量标签**：两个仓库共用 `rework`（合并后返工的 PR）和 `escaped-defect`（合并后才发现的缺陷），为以后统计一次通过率和逃逸缺陷。
