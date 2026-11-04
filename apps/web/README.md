# 校园资源前端（G5 迁入版）

沿用 main 的 Vue 3 / TypeScript、根 npm workspace、单一 Router 和模块注册器。G5 提供登录、个人身份、主动改密、账户、院系、角色权限和全局参数页面；米白/森林绿界面与临时名称“校园资源”保留。空间查询、预约、审批和维修入口等待对应模块，页面不展示虚构业务结果。

## 准备与构建

要求 Node 24.22.1（.nvmrc）、Java 21、PostgreSQL 17。所有 npm 安装从仓库根目录执行，只维护根 package-lock.json。

```powershell
npm ci --no-audit --no-fund
npm run check
npm test
npm run build
```

后端按 [服务指南](../../services/approval/README.md) 构建 services/approval/target/approval-service-0.1.0-SNAPSHOT.jar。新电脑需要准备自己的工具、数据库和私有凭据；本机缓存不纳入 Git。

## 独立预览

迁入版使用独立数据库 pj_g5_migration_dev 和端口 5177 / 18102 / 18182，先由数据库管理员创建该开发库。原 G5 的 5173 / 8082 / 18082 与原数据库保持独立。从仓库根目录运行：

```powershell
.\scripts\Start-CampusLocal.ps1 -CredentialFile '<本机加密数据库凭据文件>' -JdkHome '<JDK 21 目录>'
```

打开 https://127.0.0.1:5177 。首次访问会有本机自签名证书提示。脚本可通过 WebPort、BackendPort、ManagementPort、DatabaseUrl 调整；占用端口时直接报错，不停止其他进程。停止使用 scripts/Stop-CampusLocal.ps1，只停止当前仓库启动器记录并核对过的进程，不停止 PostgreSQL。

初始管理员显式初始化，密码保存在本机 .local/approval-admin-credential.xml；启动不会覆盖数据库中已更改的密码。测试账号通过 scripts/Initialize-G5DemoAccounts.ps1 创建，密码说明仅写入忽略目录 .local/验证账号.md。

直接 npm run dev 使用 https://127.0.0.1:5173，默认代理 approval 到 8082。独立调试时设置 APPROVAL_PROXY_TARGET 为相应回环 HTTP 地址并显式选空闲端口；它是开发服务器配置，不进入浏览器包。构建输出 apps/web/dist/；preview 仅用于本地验收。

## 页面与身份

| 路径 | 功能 |
| --- | --- |
| /login | 真实账号密码登录、错误/限速/连接失败提示 |
| / | 校园服务概览、真实账户摘要、待开放业务说明 |
| /profile | 个人身份、授权、有效期、刷新及改密入口 |
| /change-password | 8—24 字符主动改密，成功后全部旧会话失效并重登 |
| /admin/accounts | 查询/筛选/分页、创建/编辑/启停账户、分配角色 |
| /admin/roles | 角色列表、权限目录、新增/编辑、范围授权 |
| /admin/departments | 院系列表、新增、改名、启停 |
| /admin/config | 五项全局参数、版本、生效时间、边界与冲突处理 |
| /access-denied | 缺少管理授权时的说明 |

一个共享会话来源，G5 页面路由使用 g5- 前缀，根首页保留 g6-home。导航由 features/gN/navigation.ts 注册，系统管理菜单依据实际 system:manage + SYSTEM 授权显示；菜单控制不能代替后端鉴权。管理员要预约须显式追加学生角色，不自动拥有学生权限。

会话 Cookie 为 Secure / HttpOnly / SameSite=Lax。CSRF 仅存当前标签页 sessionStorage/内存，不保存密码、身份或权限缓存。路由进入、刷新和窗口重新激活时确认后端身份；新标签页只有 Cookie 而缺 CSRF 时重新登录。401 清身份，503 阻止使用旧身份继续操作并提供重新连接。无需强制首次改密。

公共 request<T> 保留 main 的同源 API、CSRF、幂等键、错误信封和禁止自动重试规则；requestForG5 只适配既有 G5 调用约定。409 保留草稿供用户比较，未知结果不生成新键、不自动重放写操作；改密未知结果提示重登核实。

## 真实浏览器验收

```powershell
.\scripts\Test-WebAuth.ps1 -JdkHome '<JDK 21 目录>'
```

Windows 辅助脚本使用 PostgreSQL 管理员私有凭据创建两个 pj_g5_auth_it_web_* 空数据库，用真实 HTTPS、打包后端、PostgreSQL 和 Chrome/Edge 验证。支持 PLAYWRIGHT_CHROMIUM_EXECUTABLE；脚本路径按本机工具调整。后端测试和浏览器测试顺序执行，避免测试端口和夹具互相干扰。

CI 使用独立临时 PostgreSQL、scripts/prepare-g5-ci.py 和 Playwright Chromium；远端未执行前不能宣称该工作流通过。测试不记录 trace、录像或真实开发账户密码；忽略目录保存进程日志与结构化结果。

范围及本轮实际证据见 [迁入记录](../../docs/verification/G5-MIGRATE-main-framework.md)。G4 真实 HTTP、G2/G1/G3 消费及 G6 集中投递仍须后续联调。
