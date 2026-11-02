# GitHub 规则接入与验证

本文件描述目标规则。实际可用能力取决于用户提供的仓库、可见性、套餐和当前权限。仓库创建后回读实际配置；本地有配置文件不等于GitHub已强制执行。

## 仓库身份和权限

从用户URL提取并校验 `HOST/OWNER/REPO`，所有gh操作显式选仓库，API选正确主机。GitHub.com可使用以下只读形式，变量由已经校验的输入赋值：

```bash
gh repo view "$REPO" --json nameWithOwner,url,defaultBranchRef,viewerPermission,isPrivate
gh pr view "$PR_NUMBER" --repo "$REPO" --json number,url,baseRefName,headRefName,headRefOid,isDraft,reviewDecision,mergeStateStatus,statusCheckRollup
gh pr checks "$PR_NUMBER" --repo "$REPO"
```

检查本地fetch/push目标、PR head仓库和base仓库。fork模式中分别记录提交仓库与目标仓库，按各自授权使用SSH地址。远端匹配不以remote名称 `origin` 判定。

403/404可能来自权限、仓库类型或功能限制；只根据实际返回和设置页面判断。所需能力不可用时列为接入阻断，由仓库管理员选择支持的配置/套餐；修改仓库公开性属于用户明确决策。

## 主分支保护

建议用适用的 ruleset 或 branch protection 强制：

| 规则 | 核验内容 |
|---|---|
 通过PR | 主分支变更均走PR，常规成员和自动化使用同一入口 |
 人工评审 | 至少1位非作者批准，最新变更重新评审 |
 旧批准失效 | dismiss stale approvals；可结合最新push由非推送者批准 |
 所有者 | CODEOWNERS review与跨组覆盖分别核验 |
 必需检查 | 唯一、稳定的check名称，匹配受信任的工作流/App |
 最新组合验证 | strict up-to-date或可用的merge queue |
 讨论 | 未解决评审讨论阻断合并 |
 主分支历史 | 常规强推和删除关闭，管理员和自动化同样受约束 |
 合并方法 | 与团队规则一致；merge commit与强制线性历史不能同时要求 |
 发布tag | 创建/修改/删除权限按发布角色收敛，已发布tag保持稳定 |

当前操作使用正常保护入口，不用管理员bypass放行未通过版本。首次配置以已授权的配置任务执行；未授权时输出具体设置差异和可审查文件。

## CODEOWNERS 的两个陷阱

1. **最后匹配行优先，不会累加所有匹配行。** 更具体路径放后面；CODEOWNERS自身和CI规则也有真实所有者。
2. **同一行多所有者是任意一位批准即可，不是每组都批准。** `contracts/ @group-a @group-b` 不能证明两组均已评审。

逐组覆盖需要另外维护“变更路径/契约 → 所需组 → 真实合格成员 → 当前有效批准”映射。组员提供真实GitHub账号；使用team须确认组织中存在、可见且有仓库write权限。个人仓库可使用真实用户映射。

若需平台强制每组批准，建立独立必需check，例如 `owner-review-coverage`，其名称是建议配置值。检查实现应：

- 从**受保护目标分支**读取组配置和规则代码，依据PR实际changed files与契约调用方计算所需组。
- 读取真实review，排除作者、机器人、dismissed和已失效批准；逐评审人取最新有效意见，再按当前head检查。
- 针对pull_request更新、review提交/撤销、成员配置变化重新计算；较早head的check不能作为新head通过依据。
- 任一相关组缺批准或存在未解决阻断意见则失败，网络/权限失败保持未通过。
- 元数据工作流使用最小权限；有写check权限的上下文只读取PR元数据，不执行PR提供的脚本。

本skill交付的是检查规则，不预装一个空壳绿灯工作流。该check尚未真实实现并设为required时，报告“平台逐组门禁未完成”，由授权合并负责人逐组核验；这一人工补充不冒充强制门禁已生效。

## CI 应覆盖真实行为

- push/PR触发构建、测试与契约校验；必需检查名称在不同工作流中保持唯一。
- 测试命令从真实构建配置读取；缺失命令或缺少被测服务不能返回虚假的通过。
- GitHub可将 skipped/neutral 视为某些required checks满足；项目的汇总check须检查必需测试确实执行成功，对取消/超时/跳过给出失败或明确合理的“不适用”证据。
- 使用路径过滤时，始终运行汇总check；相关组全部需要的检查才可被计入，避免整条required工作流被跳过后永久pending。
- 使用merge queue时，CI同时支持 `merge_group`，验证队列组合。
- fork PR构建使用受限只读权限和隔离环境；部署凭据只用于受信任的发布阶段。

## 测试部署、生产审批与环境互斥

- 测试环境：合并后构建/验证成功，自动统一部署；不设人为点击才能继续的审批步骤。
- 生产环境：提升测试验证过的manifest；GitHub Environment或实际可用的等价系统配置真实人工审批。说明配置能力与审批主体，不能把注释或普通workflow输入当成审批保护。
- deploy与针对**同一个共享环境**的integration-test使用完全相同的环境锁。独立临时PR环境可独立运行。
- GitHub concurrency用于调度；服务器侧同一锁也覆盖人工脚本与测试进程。部署临界区 `cancel-in-progress: false`；GitHub待运行任务可能被后续任务替换，逐版本必部署时另设明确队列。
- 对同一发布工作流，外部集成测试等待部署持锁完成；部署自己的冒烟/验收在已持有的锁中运行，避免嵌套再次取同一锁。
- 实测“测试先持锁，部署等待”及“部署先持锁，测试等待”两种顺序；失败恢复完成前继续保持维护和互斥。

## 官方依据

创建时核对日期：2026-10-16。接入具体仓库时再核验实际能力。

- [CODEOWNERS语法、权限、任意所有者批准规则](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)
- [主分支保护、stale review、strict检查及merge queue](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [GitHub Actions并发控制](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
- [部署保护规则](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
