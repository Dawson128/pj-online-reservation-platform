# G6 后续任务

1. G6-AUTH-WIRING：前端会话恢复/401由G5适配提供；跨服务身份、写请求CSRF与默认拒绝接线见G6-AUTH-INTEGRATION（已实现、待人工验收与合并）。G4真实资源/范围接口就绪后的业务联调仍待完成；当前密码策略沿用已合入G5基线。
2. G6-CONTRACTS：根据技术 §4/5/8 编写公共错误和事件信封、G6 OpenAPI 与样例；提供方/消费者人工评审后冻结首批契约。
3. G6-INTAKE：G6-I01/L01 持久 inbox、重复事件一致性、路由 operation、审计，独立 m3/g6 迁移。
4. G6-MAIL：G5 收件人接线、真实受控 SMTP、通知与 attempt、失败重试与 UNKNOWN，不以发送成功代替业务成功。
5. G6-CONSOLE：通知/审计查询、授权范围、异常恢复聚合、真实版本展示。
6. G6-READINESS：独立凭据的 internal readiness，真实数据库/迁移/身份/资源/收件/SMTP 探针，不用 liveness 冒充。
7. G6-TEST-DEPLOY：配置本项目独占测试环境，合入后自动部署完整四组件；CI/服务器环境互斥；真实 HTTPS。
8. G6-RELEASE：固定制品摘要与 manifest，生产受保护环境人工审批，同制品提升；不是重新构建不同镜像。
9. G6-RECOVERY：每日加密备份/7天、隔离恢复、schema 向后兼容及写门禁，按技术 §12 演练回退。
10. G6-EVIDENCE：协调五项现场展示，保持需求→实现→真实测试证据；课程后90天清理由各 owner 实施、G6 协调。

前置信息：成员真实 GitHub 账号/评审关系；测试和生产主机、域名/HTTPS方案、受控 SMTP。密钥通过本机认证或平台 Secrets 设置，不进聊天、Git 或镜像。
