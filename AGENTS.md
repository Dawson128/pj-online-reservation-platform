# 校园资源服务平台：项目级规则

## 基线与范围
- 业务依据 `需求与技术文档/需求文档v2.md`；设计依据 `需求与技术文档/技术文档v2.md`。
- 首先读取 `github-team-workflow/SKILL.md` 和适用 references。
- 六人各一个模块：G1 预约、G2 审批、G3 维修、G4 资源、G5 身份、G6 通知审计及工程集成。
- 三部署服务：reservation=G1+G4，approval=G2+G5，maintenance=G3+G6。不增加后端部署单元。
- 本次 G6 启动包只建立其他组空目录，不代表认领其业务实现。其他组的业务代码/契约/表须由其 owner 实施及评审。

## 所有权与边界
- `apps/web/src/features/gN`、`services/*/modules/gN`、`db/m*/gN`、`contracts/openapi/gN.yaml`、`contracts/events/gN`、`contracts/local/gN.md` 归 GN。
- G6 管 `apps/web/src/app`、共享 UI、服务启动装配、根构建、`infra` 和 `.github`；G4 管 reservation/integration。
- 跨模块只引用对方 api；禁止引用其 entity/repository、执行其表 SQL。
- G4 的资源协调器是资源写事务入口；G1 状态仍归 G1。锁内不得跨网络调用。详细锁顺序遵循技术文档 §7。
- 公共身份由 G5 定义、资源由 G4 定义、错误与信封由 G6 定义；消费者共同评审。
- 一个 Vue Router、一个注册入口。各组导出 routes/navigation；不得新建全局拦截器。
- 所有时间 Asia/Shanghai，区间 [start,end)，UUID，版本号 JSON 使用十进制字符串。

## 开发与验证
- 分支 `gN/<task>`；一项可验收任务一个分支，保持小批提交。
- AI 先给方案，人审核后实现；提交前负责人验收准确 diff 和行为。不得代签人工评审或编造证据。
- 合并冲突由人实际编辑和决定，AI 只分析和复验。
- `npm ci`、`npm run check`、`npm test`、`npm run build`、`mvn -B -ntp verify`。
- Docker 可用时执行 README 的 Compose 验证。shell profile 只证明装配，不证明业务/数据库就绪。
- 新增业务必须补本人行为测试、契约、迁移和追踪。不得 mock 掉被测核心或返回空壳绿灯。
- 数据迁移使用 `VYYYYMMDD_HHMMSS_N_NNN__GN_description.sql`；UTC 版本键，同 schema 唯一且递增，已合入不可改写；每服务一套 Flyway 历史。
- 禁止手工改业务库补救、跨 schema 外键、以应用账号做 DDL。
- 密钥不进入 Git、日志或镜像；测试数据不得使用真实个人信息。

## 集成与发布
- 公共、跨组、schema 变更由提供方及消费方人工评审；实际保护需 GitHub 设置，模板不能冒充已生效。
- 主干通过必需检查与人工验收后 PR 合入；不绕过门禁。
- 本批仅有 bootstrap CI。测试服务器自动部署、生产审批、备份回退及业务探针尚待后续任务，不能宣称上线完成。
- 发布必须同批覆盖 web+三个服务及三 schema 版本；测试自动部署、生产人工审批；镜像摘要可追溯。
- 持续记录实际结果。失败、未知及未验证项目明确标记。
