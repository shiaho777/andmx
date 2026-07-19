# Contributing to AndMX

感谢贡献。本仓库使用 **Issue → PR → main → CI → merge** 交付环。  
编码 agent 额外说明见 [AGENTS.md](AGENTS.md) 的 Delivery 一节。

## 仓库与分支

- Remote：`https://github.com/shiaho777/andmx`
- 默认 / 集成分支：**`main`**
- 功能分支：从最新 `main` 拉出（建议前缀 `codex/`）

## 流程

1. **先开 Issue**（或复用已有 Issue），写清问题/目标与验收标准。
2. **从 `main` 建分支** 开发。
3. **向 `main` 开 PR**：
   - 说明用户可见结果与原因
   - 正文包含 **`Fixes #N` 或 `Closes #N`**（合并时关闭 Issue，**不是**打开 PR 时关闭）
4. **等 CI 变绿**（见下方门禁）。红则修复后 push 同一分支。
5. **合并进 `main`** → Issue 应自动关闭。若未关闭，由维护者按合并 PR 手动关闭。

```text
Issue → PR (base=main, Fixes #N) → CI green → merge → Issue closes
```

## CI 门禁

| 项 | 值 |
|----|-----|
| Workflow | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |
| 名称 | `CI` |
| Job | `build` |
| 合并前 | 该检查必须 **green**；不要合红 |
| Issue | **不要**由 CI 自动关闭；只靠 PR 合并时的 `Fixes`/`Closes` |

建议在 GitHub 仓库设置里对 `main` 开启分支保护，并将 **CI / build** 设为 required check。

### 本地自检

```bash
./gradlew :app:compileLiteDebugKotlin --no-daemon
```

## PR 检查清单

- [ ] 关联 Issue：`Fixes #N` / `Closes #N`
- [ ] base = `main`
- [ ] 无密钥、`local.properties`、keystore、无关键大文件
- [ ] 自测或说明为何无需
- [ ] CI `build` 通过

## 豁免

- 完全自动化的 bot/catalog 类 PR：可不绑 Issue（正文说明即可）；仍建议过 CI。
- 用户或维护者**显式**授权的直推 `main`、跳过 CI 等：仅限该次，并在说明中记录。

## 行为准则

尊重审查意见；保持 diff 小而清晰；不要在无关 PR 里夹带重构。
