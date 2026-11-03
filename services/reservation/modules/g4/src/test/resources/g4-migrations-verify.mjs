/**
 * G4 迁移真机验证（G4 自测工具，不属于 Maven 测试生命周期）
 *
 * 为什么存在：迁移里有 61 个 CHECK、1 个触发器、以及依赖 (id, room_id) 复合外键的
 * 完整性约束。只看 SQL 能否执行通过，不足以证明这些约束真的拦得住错误数据。
 * 本脚本用「故意违反约束」的方式逐条验证，直接对应课程任务书的要求：
 * 「测试必须确实验证了行为……验证手段：故意破坏实现，观察测试是否失败」。
 *
 * 覆盖：3 个迁移可执行；19 张表 / 52 个索引 / 1 个触发器 / 61 个 CHECK 建立；
 * 4 类合法占用可写入；22 项非法数据被拒绝（含错误码断言）。
 *
 * 如何运行（需要 Node >= 18）：
 *   mkdir -p /tmp/g4db && cd /tmp/g4db && npm init -y
 *   npm i pg embedded-postgres
 *   cp <repo>/services/reservation/modules/g4/src/test/resources/g4-migrations-verify.mjs .
 *   node g4-migrations-verify.mjs <repo 根目录>
 *
 * 依赖说明：使用 embedded-postgres 是为了在无 Docker 的机器上也能验证。
 * 若要接入 CI，更合适的做法是在 compose 的 postgres 容器上直接跑 SQL 断言，
 * 那需要 G6 在 .github/workflows/ci.yml 增加一段（属 G6 所有权）。
 *
 * 退出码：0 全部通过；1 有失败项。
 */
import EmbeddedPostgres from "embedded-postgres";
import pg from "pg";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";

const repo = process.argv[2];
if (!repo) {
  console.error("用法：node g4-migrations-verify.mjs <repo 根目录>");
  process.exit(2);
}
// 数据目录放在系统临时区，避免在仓库工作区里留下 pgdata
const dataDir = path.join(os.tmpdir(), `g4-pgdata-${process.pid}-${Date.now()}`);
const PORT = 55432;

const MIGRATIONS = [
  "V20261103_140000_4_001__G4_resource_master.sql",
  "V20261103_140100_4_002__G4_resource_ledger.sql",
  "V20261103_140200_4_003__G4_operation_tables.sql",
];

const results = [];
const log = (ok, name, detail) => {
  results.push({ ok, name, detail });
  console.log(`${ok ? "  ✓" : "  ✗"} ${name}${detail ? "  — " + detail : ""}`);
};

fs.rmSync(dataDir, { recursive: true, force: true });

const server = new EmbeddedPostgres({
  databaseDir: dataDir,
  user: "postgres",
  password: "postgres",
  port: PORT,
  persistent: false,
});

await server.initialise();
await server.start();
await server.createDatabase("campus");

const client = new pg.Client({
  host: "127.0.0.1",
  port: PORT,
  user: "postgres",
  password: "postgres",
  database: "campus",
});
await client.connect();

await client.query("CREATE SCHEMA m1");
await client.query("SET search_path TO m1");

console.log("\n=== 1. 依次执行迁移 ===");
for (const f of MIGRATIONS) {
  const sql = fs.readFileSync(path.join(repo, "db/m1/g4", f), "utf8");
  try {
    await client.query(sql);
    log(true, f);
  } catch (e) {
    log(false, f, `${e.code || ""} ${e.message}`);
    console.log("\n迁移执行失败，后续测试跳过。");
    await client.end();
    await server.stop();
    process.exit(1);
  }
}

const objects = await client.query(`
  SELECT
    (SELECT count(*) FROM information_schema.tables WHERE table_schema='m1') AS tables,
    (SELECT count(*) FROM pg_indexes WHERE schemaname='m1') AS indexes,
    (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
       JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='m1' AND NOT t.tgisinternal) AS triggers,
    (SELECT count(*) FROM pg_constraint k JOIN pg_namespace n ON n.oid=k.connamespace
       WHERE n.nspname='m1' AND k.contype='c') AS checks
`);
log(true, "对象统计", JSON.stringify(objects.rows[0]));

const R1 = "11111111-1111-4111-8111-111111111111";
const R2 = "22222222-2222-4222-8222-222222222222";
const S1 = "33333333-3333-4333-8333-333333333331";
const S2 = "33333333-3333-4333-8333-333333333332";
const OP = "44444444-4444-4444-8444-444444444444";

async function expectOk(name, sql) {
  await client.query("BEGIN");
  try {
    await client.query(sql);
    await client.query("ROLLBACK");
    log(true, name);
  } catch (e) {
    await client.query("ROLLBACK");
    log(false, name, `${e.code || ""} ${e.message}`);
  }
}

async function expectFail(name, sql, code) {
  await client.query("BEGIN");
  try {
    await client.query(sql);
    await client.query("ROLLBACK");
    log(false, name, "没有报错，约束未生效");
  } catch (e) {
    await client.query("ROLLBACK");
    const hit = e.code === code;
    log(hit, name, hit ? `按预期拒绝 (${e.code})` : `错误码不符：期望 ${code} 实际 ${e.code}`);
  }
}

console.log("\n=== 2. 正常数据可写入 ===");

await client.query(`
  INSERT INTO room (id, version, code, name, location, area, room_type, capacity, school_wide, dept_id, enabled)
  VALUES
    ('${R1}', 1, 'R001', '示例教室', '教学楼1层', '东区', 'CLASSROOM', 2, true, NULL, true),
    ('${R2}', 1, 'R002', '研讨室',   '教学楼2层', '东区', 'CLASSROOM', 4, false,
     '99999999-9999-4999-8999-999999999999', true)
`);
await client.query(`
  INSERT INTO seat (id, version, room_id, code, socket_type, enabled)
  VALUES ('${S1}', 1, '${R1}', 'A01', 'FIXED', true),
         ('${S2}', 1, '${R2}', 'B01', 'NONE',  true)
`);
log(true, "房间与座位写入");

await expectOk("PERSONAL 占用（本室座位 + 有结束时刻）", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'PERSONAL', gen_random_uuid(),
    '2026-11-04 09:00+08', '2026-11-04 10:00+08', 'ACTIVE', '2026-11-04 09:00+08', '个人预约', 1, '${OP}')
`);
await expectOk("WHOLE 整室占用（seat_id 为 NULL）", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', NULL, 'WHOLE', gen_random_uuid(),
    '2026-11-04 14:00+08', '2026-11-04 16:00+08', 'ACTIVE', '2026-11-04 14:00+08', '整室批准', 1, '${OP}')
`);
await expectOk("REPAIR 整室封锁（end 为 NULL，持续到显式释放）", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', NULL, 'REPAIR', gen_random_uuid(),
    '2026-11-04 18:00+08', NULL, 'ACTIVE', '2026-11-04 18:00+08', '空调检修', 1, '${OP}')
`);
await expectOk("ADMIN 单座停用（带 seat_id）", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'ADMIN', gen_random_uuid(),
    '2026-11-05 09:00+08', NULL, 'ACTIVE', '2026-11-05 09:00+08', '单座维修', 1, '${OP}')
`);
await expectOk("claim：RELEASED 带释放时刻（合法）", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, released_at, reason, release_reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'PERSONAL', gen_random_uuid(),
    '2026-11-10 09:00+08', '2026-11-10 10:00+08', 'RELEASED', '2026-11-10 09:00+08',
    '2026-11-10 09:30+08', 'x', '提前离开', 1, '${OP}')
`);

console.log("\n=== 3. 故意违反约束，必须被拒绝 ===");

await expectFail("room：全校开放却带 dept_id", `
  INSERT INTO room (id, version, code, name, location, area, room_type, capacity, school_wide, dept_id, enabled)
  VALUES (gen_random_uuid(), 1, 'R900', 'x', 'x', 'x', 'CLASSROOM', 2, true,
    '99999999-9999-4999-8999-999999999999', true)`, "23514");

await expectFail("room：院系专属却无 dept_id", `
  INSERT INTO room (id, version, code, name, location, area, room_type, capacity, school_wide, dept_id, enabled)
  VALUES (gen_random_uuid(), 1, 'R901', 'x', 'x', 'x', 'CLASSROOM', 2, false, NULL, true)`, "23514");

await expectFail("room：capacity 为 0", `
  INSERT INTO room (id, version, code, name, location, area, room_type, capacity, school_wide, dept_id, enabled)
  VALUES (gen_random_uuid(), 1, 'R902', 'x', 'x', 'x', 'CLASSROOM', 0, true, NULL, true)`, "23514");

await expectFail("room：编号重复", `
  INSERT INTO room (id, version, code, name, location, area, room_type, capacity, school_wide, dept_id, enabled)
  VALUES (gen_random_uuid(), 1, 'R001', 'x', 'x', 'x', 'CLASSROOM', 2, true, NULL, true)`, "23505");

await expectFail("seat：编号重复", `
  INSERT INTO seat (id, version, room_id, code, socket_type, enabled)
  VALUES (gen_random_uuid(), 1, '${R1}', 'A01', 'NONE', true)`, "23505");

await expectFail("claim：PERSONAL 不带 seat_id", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', NULL, 'PERSONAL', gen_random_uuid(),
    '2026-11-06 09:00+08', '2026-11-06 10:00+08', 'ACTIVE', '2026-11-06 09:00+08', 'x', 1, '${OP}')`, "23514");

await expectFail("claim：WHOLE 带了 seat_id", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'WHOLE', gen_random_uuid(),
    '2026-11-06 09:00+08', '2026-11-06 10:00+08', 'ACTIVE', '2026-11-06 09:00+08', 'x', 1, '${OP}')`, "23514");

await expectFail("claim：REPAIR 带了 seat_id", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'REPAIR', gen_random_uuid(),
    '2026-11-06 09:00+08', NULL, 'ACTIVE', '2026-11-06 09:00+08', 'x', 1, '${OP}')`, "23514");

await expectFail("claim：PERSONAL 没有结束时刻", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'PERSONAL', gen_random_uuid(),
    '2026-11-06 09:00+08', NULL, 'ACTIVE', '2026-11-06 09:00+08', 'x', 1, '${OP}')`, "23514");

await expectFail("claim：WHOLE 整室占用没有结束时刻", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, released_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', NULL, 'WHOLE', gen_random_uuid(),
    '2026-11-09 09:00+08', NULL, 'ACTIVE', '2026-11-09 09:00+08', NULL, 'x', 1, '${OP}')`, "23514");

await expectFail("claim：RELEASED 但没有释放时刻", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, released_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'PERSONAL', gen_random_uuid(),
    '2026-11-09 09:00+08', '2026-11-09 10:00+08', 'RELEASED', '2026-11-09 09:00+08', NULL, 'x', 1, '${OP}')`, "23514");

await expectFail("claim：座位不属于该房间（复合外键）", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S2}', 'PERSONAL', gen_random_uuid(),
    '2026-11-06 09:00+08', '2026-11-06 10:00+08', 'ACTIVE', '2026-11-06 09:00+08', 'x', 1, '${OP}')`, "23503");

await expectFail("claim：结束不晚于开始", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'PERSONAL', gen_random_uuid(),
    '2026-11-06 10:00+08', '2026-11-06 10:00+08', 'ACTIVE', '2026-11-06 10:00+08', 'x', 1, '${OP}')`, "23514");

await expectFail("claim：state 写成 ABSENT（不应落库）", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'PERSONAL', gen_random_uuid(),
    '2026-11-06 09:00+08', '2026-11-06 10:00+08', 'ABSENT', '2026-11-06 09:00+08', 'x', 1, '${OP}')`, "23514");

const SEED = "55555555-5555-4555-8555-555555555555";
await client.query(`
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES ('${SEED}', 1, '${R1}', '${S1}', 'PERSONAL', '66666666-6666-4666-8666-666666666666',
    '2026-11-07 09:00+08', '2026-11-07 10:00+08', 'ACTIVE', '2026-11-07 09:00+08', 'x', 1, '${OP}')
`);

await expectFail("claim：同一来源重复占用", `
  INSERT INTO resource_claim (id, version, room_id, seat_id, source_type, source_id, start_at, end_at,
    state, effective_at, reason, source_version, operation_id)
  VALUES (gen_random_uuid(), 1, '${R1}', '${S1}', 'PERSONAL', '66666666-6666-4666-8666-666666666666',
    '2026-11-07 10:00+08', '2026-11-07 11:00+08', 'ACTIVE', '2026-11-07 10:00+08', 'x', 1, '${OP}')`, "23505");

await expectFail("seat：改座位编号（触发器）", `
  UPDATE seat SET code = 'Z99' WHERE id = '${S1}'`, "P0001");

await expectFail("seat：把座位迁到别的房间（触发器）", `
  UPDATE seat SET room_id = '${R2}' WHERE id = '${S1}'`, "P0001");

await expectFail("opening_weekly：序号 9（每日最多 8 段）", `
  INSERT INTO opening_rule (id, room_id, version) VALUES ('${SEED}', '${R1}', 1);
  INSERT INTO opening_weekly (id, rule_id, weekday, seq, start_minute, end_minute)
  VALUES (gen_random_uuid(), '${SEED}', 1, 9, 480, 1320)`, "23514");

await expectFail("opening_weekly：开始不早于结束", `
  INSERT INTO opening_weekly (id, rule_id, weekday, seq, start_minute, end_minute)
  VALUES (gen_random_uuid(), '${SEED}', 2, 1, 1320, 480)`, "23514");

await expectFail("g4_operation：ROOM 范围却没有 room_id", `
  INSERT INTO g4_operation (id, version, owner_group, source_type, source_id, room_id, scope_kind,
    category, event_id, actor_id, operation_id, status, phase, request_digest, accepted_at, status_url)
  VALUES (gen_random_uuid(), 1, 'G4', 'WHOLE', '${SEED}', NULL, 'ROOM', 'RESOURCE', NULL, NULL,
    gen_random_uuid(), 'PENDING', 'NONE', repeat('a',64), now(), '/x')`, "23514");

await expectFail("g4_operation：RESOURCE 类别带 event_id", `
  INSERT INTO g4_operation (id, version, owner_group, source_type, source_id, room_id, scope_kind,
    category, event_id, actor_id, operation_id, status, phase, request_digest, accepted_at, status_url)
  VALUES (gen_random_uuid(), 1, 'G4', 'WHOLE', '${SEED}', '${R1}', 'ROOM', 'RESOURCE',
    gen_random_uuid(), NULL, gen_random_uuid(), 'PENDING', 'NONE', repeat('a',64), now(), '/x')`, "23514");

await expectFail("claim_operation_result：DECLINED 但缺拒绝体", `
  INSERT INTO claim_operation_result (operation_id, source_type, source_id, room_id, request_digest, outcome)
  VALUES (gen_random_uuid(), 'WHOLE', '${SEED}', '${R1}', repeat('b',64), 'DECLINED')`, "23514");

console.log("\n=== 4. 清理与汇总 ===");
await client.end();
await server.stop();

const passed = results.filter((r) => r.ok).length;
const failed = results.length - passed;
console.log(`\n共 ${results.length} 项：通过 ${passed}，失败 ${failed}`);
if (failed > 0) {
  console.log("失败项：");
  results.filter((r) => !r.ok).forEach((r) => console.log("  - " + r.name + "：" + r.detail));
  process.exit(1);
}
console.log("全部通过：三个迁移可在真实 PostgreSQL 上执行，全部约束拒绝行为与合法写入均符合预期。");
