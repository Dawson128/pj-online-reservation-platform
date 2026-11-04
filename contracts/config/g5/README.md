# 参数契约与 owner 交接

用户已授权按技术文档 v2 §4.3、§5.5 独立开发 G5。本目录是 G5 整理的分组基线，状态 `BASELINE_PENDING_OWNER_REVIEW`，不代表 G1/G2/G6 已建立、提交或会签契约。

- `baseline/g1.schema.json`：个人预约时长、提前天数。
- `baseline/g2.schema.json`：整室申请提前天数。
- `baseline/g6.schema.json`：扫描间隔、通知重试上限。
- `config-values.schema.json`：当前唯一组合定义，前端直接导入。后端构建核对片段/文档/组合文件，输出 `g5/config-values.schema.json`，运行时使用该资源校验。

片段是封闭 JSON Schema 对象，只支持五个必填有界整数及中文名称、用途、单位、默认值、业务 owner。构建拒绝未知/重复/归属错误的键、不支持的校验语法、文档之外的范围或默认值、片段与组合不一致。后端是此受限契约的校验器，不是通用 JSON Schema 引擎。

Maven `verify` 在 `process-classes` 组合并检查契约，无需其他业务服务。`scripts/verify-config-contracts.py` 用 JSON Schema 库检查默认值、边界、类型、闭合性、OpenAPI 和构建资源。参数变化须同步契约、数据库约束、样例、页面说明与测试。

技术文档要求将来各 owner 提交 `contracts/config/gN` 正式 schema，并由构建组合、CI 核对 owner 评审。当前未建立他组或远端 CI/审批门禁，不伪造流程。后续由各 owner 确认基线，交接各自片段及生效语义，再调整组合输入及评审检查。本地构建可拒绝偏离现行文档，不证明已获人工批准。

签到时点、两级审批、总截止、维修时限、评价期限等固定规则不可配置。新操作和消费者下次调度采用新值，已有对象保留原快照。跨组效果须真实消费者联调后验收。
