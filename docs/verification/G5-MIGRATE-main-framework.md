# G5 迁入 main 框架：验证记录

日期：2026-11-04。本文记录本地迁入候选验证；验证阶段未提交、未推送、未合并。后续用户提交推送授权见文末；本文不代替模块负责人和公共层消费方人工评审。

## 来源与结构

- main 基准：152c1bd20c99387092f6d97b1b64adfbd9749941（已含 G4 PR #2）。
- G5 来源：d5b19f0cb3e9fbb471ccc6e62b18c5f2b11f9e5f。
- 新分支：g5/migrate-main-framework-20261104。
- 工作目录：PJ/pj-online-reservation-platform-main-audit-20261104。原 G5 仓库和预览保持独立。
- 本次直接在 main 框架中接入源码；此前试合并已撤销，当前没有 merge/rebase 或待解决冲突。

沿用 cn.edu.campus 父工程、Java 21 / Spring Boot 4.1.2、三个服务根启动入口、根 npm workspace 与单一 package-lock.json、Vue 单一路由注册器。G5 不再另建 approval/app 或模块独立启动工程。

后端迁入 G5 api/application/domain/persistence，包名改为 cn.edu.campus.approval.g5。G5 组件排除 shell；shell 只验证 main 原装配边界。正常模式仍检查持久身份/CSRF/服务凭据；仅已实现路径经过安全链，其他业务路径保持拒绝。未知管理端点同样保持拒绝并返回 404，不开放额外管理接口。

前端保留 main HTTP client 的原契约并增加 G5 兼容入口，一次请求、无写操作自动重试；一个共享会话、管理菜单及路由守卫。G5 页面、管理交互、绿白风格和“校园资源”名称保留。首页路由仍为 g6-home，G5 路由名以 g5- 开头。

## 验证进度

后端和浏览器最终轮均已通过。实际日志位于 .local/g5-migration；失败轮次保留，不以旧分支测试替代本次证据。

- 已通过 main 原 9 项 HTTP 单测、前端类型与产物构建、npm ci、仓库检查（9 个迁移版本）。
- 已通过三个服务根 mvn verify（6 项装配测试）。
- 已通过 G5 OpenAPI 引用及 58 个成功/失败响应例子、19 类通知事件及闭合校验、配置 47 项边界/类型校验、G4 原结构检查。
- 第一轮数据库测试由于 Windows 清理环境变量产生空值而无法启动；已改用真正删除当前进程变量。
- 中间轮数据库回归 67 项中 66 项通过；未开放健康路径的 404/401 差异通过保持拒绝策略、修正响应解决。定向健康验证 3 项通过；最终完整真实数据库回归 67 项通过，0 失败/错误/跳过（backend-it-final.log，15:52:27 完成）。
- 最终根构建 6 项装配测试通过（backend-root-final.log，15:44:28 完成）；运行端口契约检查含 4 份实际提供端结果通过。
- 24 项真实浏览器回归全部通过，0 失败/重试/跳过（web-e2e-first.log，2.7 分钟）；覆盖登录/改密/会话撤销、管理权限隔离、冲突草稿、未知结果同键重试、参数保存和 390px 页面。
- 独立预览 https://127.0.0.1:5177 已启动（后端 18102、健康 18182），开发库 pj_g5_migration_dev。通过真实 HTTPS 管理接口创建 7 个合成验证账户，6 个启用登录成功、1 个停用登录被拒绝；另有初始系统管理员。私有账号说明在 .local/验证账号.md。
- 已查看登录、首页、全局参数桌面图及 390px 首页图；4 个管理页各一个 main、无页面异常，桌面及手机无横向溢出。结构化结果 preview-inspection.json；这是自动化与 AI 视觉检查，不代替负责人验收。

### 最终验收命令与日志

| 命令/场景 | 结果 | 日志 |
| --- | --- | --- |
| 根 npm ci / npm run check | 通过，9 个唯一迁移 | npm-ci.log / repository-check.log |
| npm test / npm run build | 9 项单测及类型/构建通过 | web-unit-final.log / web-build-final.log |
| 根 mvn -B -ntp verify | 三服务成功，6 项装配测试 | backend-root-final.log |
| scripts/Test-G5Management.ps1（postgres-it） | 67 项通过，6 个隔离库 | backend-it-final.log |
| scripts/Test-WebAuth.ps1 -SkipBuild | 24 项通过，2 个隔离库 | web-e2e-first.log |
| G5 认证/事件、配置、运行端口和 G4 契约检查 | 通过，运行端口含 4 份实际结果 | contracts-*.log |

日志均位于 .local/g5-migration；Playwright 结构化报告在 apps/web/test-results/summary.json，后端报告在 services/approval/target/failsafe-reports。Windows PowerShell 7；Node 24.22.1 / Java 21.0.13+9 / Maven 3.9.11 / PostgreSQL 17.11。

## 不变性核对

已逐文件核对：49 份 G5 生产 Java 仅包名和 shell 排除注解有变化，规范化后与来源相同；6 份 G5 SQL 字节、文件名和校验和均不改写。G4 契约/SQL、reservation 和 maintenance 实现与 main 一致。数据不从原开发库搬运；新预览使用自己的开发库。

对照清单见 .local/g5-migration/source-equivalence.json；最终源码/产物摘要已保存于 manifest.json（Git 忽略目录）。数据库测试沿用原 6 组独立夹具；浏览器使用另外两组测试夹具，不改原开发数据。

## 接线与待验证范围

更新了服务构建所需契约/技术文档资源、Docker 上下文忽略规则、approval 独立健康端口、启动脚本、主干配置变量兼容、G5 验收工作流。Windows 脚本显式选择本地数据库别名，清除继承的部署变量；前端子进程移除数据库、管理员、HMAC 和服务凭据。

新增 g5-validation 工作流：独立 PostgreSQL、真实打包服务验收、契约、真实浏览器，并接入 required 汇总。该候选未推送，GitHub Actions 尚未实际运行；本地 YAML 结构解析通过不等于远端执行通过。

本机无 Docker 命令，未执行本候选 Compose 镜像构建/运行；不能引用旧 main 的 CI 当成本候选证据。容器和受限运行账户检查须远端验证。

G4 当前只有契约和 SQL，尚无真实 HTTP 提供方；G5 已有提供端与自身逻辑验收，但 G2/G1/G3 消费、G4/G5 真实网络组合、G6 集中接收审计仍待对应实现与联调。全局参数 G1/G2/G6 基线片段仍待各 owner 评审。

公共启动、前端共享层、容器和 CI 改动正式合入前由 G6 / 同服务 G2 及实际消费方评审；本文不代签人工验收。初次验证交付仅为本地迁入；后续推送授权见下节，main 仍不合并。

## 后续提交与分支替换授权（2026-11-04）

用户明确指定本地 `g5/migrate-main-framework-20261104`，要求“先把本地的这个推送到远程，覆盖原 G5”。本次操作仅提交该已验证候选并替换远端 G5，不创建 PR、不请求他人评审、不合入 main。

推送前再次刷新并确认 main 为 152c1bd20c99387092f6d97b1b64adfbd9749941，旧 G5 为 d5b19f0cb3e9fbb471ccc6e62b18c5f2b11f9e5f；源码与制品匹配上面的通过记录。此后补充本段交付说明与任务单状态，并清理 6 个 Vue 文件空白行的行尾空格；没有行为、契约或 SQL 变化。行尾清理后重新通过 9 项前端单测和类型/构建，原数据库与浏览器验收记录仍按原轮次保留。

旧 G5 保留为 backup/g5-before-main-migration-20261104-d5b19f0。替换使用绑定旧 G5 SHA 的 force-with-lease，备份分支要求不存在，采用原子推送；任一远端状态变化则停止，不覆盖未见的新提交。作者沿用此前用户确认的 Richardsrj / GitHub noreply 邮箱。提交命名：`feat(g5): G5-MIGRATE 将身份权限迁入 main 框架`。

提交及远端更新是否成功以实际 Git 记录和远端 SHA 回读为准；本段记录用户操作授权，不冒充跨模块人工评审、远端 CI 通过或生产发布。

提交前补充复验：prepush-web-unit.log 的 9 项单测、prepush-web-build.log 的类型/构建通过。第一次浏览器复验 22 项通过；第 23 项已完成配置保存、原键重试、版本和数据库历史断言，随后在 page.unroute 清理网络拦截器时触发 45 秒测试超时，第 24 项因串行套件中断未执行。失败日志 prepush-web-e2e.log 和 prepush-web-e2e-failed-summary.json 保留；不删除失败证据，不增加超时或放宽断言。重跑结论另行记录，不能以之前成功覆盖本次失败。

随后使用相同源码、同一构建产物和未修改的测试重新完整运行，24 项全部通过、0 失败/跳过（prepush-web-e2e-second.log，约 2.4 分钟）。前轮清理超时属一次已观察到的测试不稳定现象，尚未做专门根因修复；本次分支推送不代表已满足正式合入的全部门禁。
