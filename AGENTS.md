# AndMX — Agent 说明

面向在本仓库工作的 coding agent。人类贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 代码约定

- 写代码不要写注释（流程/文档/CI 策略说明除外）
- 改动保持最小、对准任务；不要顺手大重构
- 不提交密钥、`local.properties`、keystore、IDE 缓存等机器本地文件

## 常用验证

```bash
./gradlew :app:compileLiteDebugKotlin --offline --no-daemon
```

有网络/干净环境时可去掉 `--offline`。

## Delivery（Issue + PR + CI）

仓库：`https://github.com/shiaho777/andmx`  
集成分支：**`main`**（默认分支即 main）。  
功能分支前缀建议：`codex/`（用户另有要求时从其约定）。

### 硬规则

1. **优先 PR 合入 `main`**，不要对日常功能直接 push `main`（用户本回合明确覆盖除外）。
2. **先有 Issue**。已有跟踪同一工作的 Issue 则复用；否则创建。
3. **PR 只以 `main` 为 base**（用户或维护者明确指定其它 base 除外）。
4. PR 正文必须说明改了什么/为什么，并包含 **`Fixes #N` 或 `Closes #N`**，使 **合并时** 关闭 Issue。
5. **禁止**在 PR 刚打开、CI 仍红、或未合并时关闭关联 Issue。
6. **CI 是合并门禁**。合入前要求检查必须绿；红则修并 push，不要合红。
7. **CI 不得自动关 Issue**；关 Issue 只靠 PR 合并时的 `Fixes`/`Closes`。
8. 一个 PR 尽量对应一个主 Issue；其它 Issue 可链接但不随意加关闭关键字。
9. **未要求交付时**：不要 commit / push / 开 PR / 建 Issue。
10. **无合并权限时**：仍可开 PR、在 Issue 下留言链接；Issue 保持 open，交给维护者在绿后合并。

### 标准流程

```text
Issue open → branch from main → PR into main (Fixes #N) → CI
  ├─ red  → fix & push（Issue 保持 open）
  └─ green → merge to main → Issue auto-closes
```

### 门禁检查名（本仓库）

| 项 | 值 |
|----|-----|
| Workflow 文件 | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| Workflow 显示名 | `CI` |
| Job id | `build` |
| 建议分支保护必选检查 | **CI / build**（GitHub 上显示为 workflow「CI」下的 job「build」） |

CI 失败时修代码或工作流后重推同一 PR，不要另开无关 PR 糊弄门禁。

### 例外（仅当用户本回合明确要求）

- 极小文档直推 `main`
- 紧急 hotfix 直推
- 跳过 Issue / 跳过 CI / 跳过 PR  

在交接说明里写明覆盖项。

### 与人类文档的关系

- 人类流程：[CONTRIBUTING.md](CONTRIBUTING.md)
- PR 模板：[`.github/pull_request_template.md`](.github/pull_request_template.md)
