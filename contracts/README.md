# 契约目录

本启动包只建立接入位置，不把尚未评审的类型当作已冻结契约。接口 ID、输入输出、错误和事件依据技术文档 §4—5、§8。

- `openapi/gN.yaml`：对应提供方创建；不能以空 OpenAPI 文件冒充接口已实现。
- `events/gN/*.schema.json`：对应事件提供方创建，G6 消费前评审。
- `local/gN.md`：本地端口由提供方维护，消费方评审。
- `common`：G5 身份、G4 资源、G6 错误与信封；改变需相关消费方批准。

G6 公共 HTTP 客户端已按技术 §4 使用 `{data,traceId}` 与统一错误结构，但全量 OpenAPI/schema 校验属于后续 G6-CONTRACTS 及各组任务。
