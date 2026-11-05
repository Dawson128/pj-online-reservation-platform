# G6 共享认证客户端

此目录是纯Java库，由reservation/maintenance两个已有服务使用。身份事实与接口归G5；本库负责每请求内省、传输验证和Spring Security接线。

## 注册业务入口

模块在自己的Spring配置中声明一条具体的动作规则，例如G4房间列表：

```java
@Bean
ApiAccessRule roomListAccess() {
    return new ApiAccessRule("G4", "GET", "/api/m1/rooms", "room:read");
}
```

该规则只应与真实接口一同交付；本批没有添加这个房间接口。不同HTTP方法分别登记。方法与路径不能重复；方法/路径必须属于对应服务。公开的`/internal/**`与管理探针不会因登记业务规则而开放。

控制器可通过 `@AuthenticationPrincipal CampusPrincipal principal` 获取本次真实身份，或读取请求属性`campus.principal`。`accountVersion`/`permissionVersion`保持字符串；grants/scope不可变。不要保存整个身份到会话、数据库或跨请求缓存。

**动作门禁不等于对象授权。** 例如ROOM范围的room:read不允许浏览全校房间；列表必须按当前范围过滤，详情和写操作必须检查实际目标。按G5-I10及G4当前房间事实实现授权；本人归属、审批回避也仍由业务owner检查。未登记接口默认拒绝，不能用通配permitAll接入。

## 配置

- `G5_AUTH_BASE_URL`：G5服务根地址，默认本机8082；HTTPS为正常跨主机配置。
- `G5_AUTH_ALLOW_HTTP=true`：仅显式允许同机开发私网HTTP，Compose已设置；不要在跨主机环境启用。
- reservation：`G5_SERVICE_G1_TOKEN`、`G5_SERVICE_G4_TOKEN`。
- maintenance：`G5_SERVICE_G3_TOKEN`、`G5_SERVICE_G6_TOKEN`。
- approval/G5：接收上述四个独立token；长度32—256，不可复用。不得放入前端或Git。

已有开发.env执行：`pwsh -File infra/scripts/init-env.ps1 -AddServiceTokens`。只追加不存在的key；原数据库密码和已存在token不变。已经有空token的key应由开发者通过受控配置补充，脚本不会擅自覆盖。

## 校验流程

匹配已登记入口→读取唯一PJ_SESSION→选择所属模块服务凭据→POST G5内省，携带实际方法及写请求CSRF→严格验证Principal→建立本次请求身份→按动作门禁与控制器对象授权执行。

浏览器伪造X-User-Id、Authorization或角色头均不作为用户身份。CSRF重复头拒绝；写请求缺少CSRF仍由G5先检查会话，再拒绝CSRF。退出、停用、到期或权限版本变化在下次请求重新核验，不使用旧身份兜底。请求结束清理SecurityContext，不创建JSESSIONID保存认证。

G5-I01增加的字段见`contracts/openapi/g5.yaml`；原`{sessionToken}`仍代表只读内省，新消费者不得省略实际方法。CSRF拒绝结构化字段为csrfToken；消费者据此区分用户校验失败与服务凭据配置故障。

## 验证

`mvn -B -ntp verify`：共享客户端、真实消费者安全链、既有后端测试。

真实三服务验收：准备空的`pj_g6_auth_it_*` PostgreSQL数据库，设置`G6_AUTH_TEST_DB_URL`、`G6_AUTH_TEST_DB_USERNAME`、`G6_AUTH_TEST_DB_PASSWORD`后，执行`mvn -B -ntp -Pauth-it verify`。账号需能在隔离测试服务器创建测试角色/三个schema；不得指向日常或生产数据库。测试只操作该专用数据库，生成独立角色与随机凭据，启动并停止三个真实jar，不清空既有数据库。探针不包含在产品jar中。

GitHub CI增加auth-integration作业并纳入bootstrap-required。所有现有业务测试仍需持续保留；本验收不代替G4真实资源和范围判定联调。
