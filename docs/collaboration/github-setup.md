# GitHub 接入待办

本地工程不能证明 GitHub 规则已生效。当前未读取管理员设置、未配置保护、未创建生产或测试环境。

1. 登记六组真实账号及权限。`.github/CODEOWNERS.example` 仅模板，补齐所有真实所有者和共享路径后生成 CODEOWNERS。
2. main 通过 PR，至少一位非作者批准，更新后旧批准失效，讨论解决，禁止强推/删主干；管理员不绕过。允许 merge commit 时勿同时要求线性历史。
3. 初次 Bootstrap CI 真实执行后，将精确检查名 `bootstrap-required` 设置 required；启用 strict up-to-date 或可用 merge queue（本 CI 支持 merge_group）。
4. CODEOWNERS 同行多人只有任一批准；提供/消费方逐组批准仍需人工核对，后续实现受保护规则驱动的 owner-review-coverage 检查，不能伪造空壳绿灯。
5. 补齐业务契约、SQL 语义所有权和各组真实测试，再扩展必需检查。
6. 当前 CI Compose 是一次性隔离验证，不是共享测试服务器自动部署。服务器准备后另建受信任发布流程；生产必须实际配置人工审批环境保护。

Git 认证使用 Git Credential Manager/SSH 或本机 gh 登录；不要把 token 写入 remote。仓库当前远程身份为 github.com/Dawson128/pj-online-reservation-platform。
