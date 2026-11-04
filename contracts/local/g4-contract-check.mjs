#!/usr/bin/env node
/**
 * G4 契约结构自检（零依赖，只需 Node >= 18）
 *
 * 用途：在没有 CI 的阶段，用一条命令证明 contracts/ 下的 G4 契约结构完整、编号不缺、
 *       引用不悬空、关键约束没被改坏。供 G6 接入流水线时直接复用。
 *
 * 用法：
 *   node contracts/local/g4-contract-check.mjs
 *   node contracts/local/g4-contract-check.mjs --root <repo 根目录>
 *
 * 退出码：0 全部通过；1 存在失败项（便于流水线判定）。
 *
 * 注意：本脚本只做结构断言，不能替代 OpenAPI 规范校验与行为测试。
 *       规范校验见 contracts/local/g4.md 第 13 节（Redocly，锁补丁版本）。
 */

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const argRoot = process.argv.indexOf("--root");
const root = argRoot > -1 ? resolve(process.argv[argRoot + 1]) : resolve(here, "../..");

const fail = [];
const ok = [];
const read = (p) => readFileSync(join(root, p), "utf8");

// 取某个 schema 的代码块：以 4 空格缩进的键切分，避免被 description 内的空行截断
function block(src, name) {
  const parts = src.split(/\n(?= {4}[A-Za-z][A-Za-z0-9]*:\n)/);
  return parts.find((p) => p.startsWith("    " + name + ":\n")) || "";
}

let g4, rt, md;
try {
  g4 = read("contracts/openapi/g4.yaml");
  rt = read("contracts/common/resource-types.yaml");
  md = read("contracts/local/g4.md");
} catch (e) {
  console.error("读取契约文件失败：" + e.message);
  process.exit(1);
}

// 1. 对外接口编号 G4-P01..P18 齐全且无重复
const pOps = [...g4.matchAll(/operationId:\s*(G4-P[0-9]+[A-Za-z-]*)/g)].map((m) => m[1]);
const pNums = [...new Set([...new Set(pOps)].map((o) => o.match(/G4-(P\d+)/)[1]))].sort();
ok.push(`对外 operation ${pOps.length} 个（唯一 ${new Set(pOps).size}），覆盖编号 ${pNums.length} 个`);
for (let i = 1; i <= 18; i++) {
  const k = "P" + String(i).padStart(2, "0");
  if (!pNums.includes(k)) fail.push("缺对外接口编号 G4-" + k);
}
if (pOps.length !== new Set(pOps).size) fail.push("operationId 存在重复");

// 2. 内部接口 G4-I01..I08 齐全
const iOps = [...new Set([...g4.matchAll(/operationId:\s*(G4-I[0-9]+)/g)].map((m) => m[1]))].sort();
ok.push(`内部 operation ${iOps.length} 个：${iOps.join(", ")}`);
for (let i = 1; i <= 8; i++) {
  const k = "G4-I" + String(i).padStart(2, "0");
  if (!iOps.includes(k)) fail.push("缺内部接口 " + k);
}

// 3. 本地端口 G4-L01..L14 齐全
const ports = [...new Set([...md.matchAll(/G4-L(0[1-9]|1[0-4])/g)].map((m) => "L" + m[1]))].sort();
ok.push(`本地端口 ${ports.length} 个`);
for (let i = 1; i <= 14; i++) {
  const k = "L" + String(i).padStart(2, "0");
  if (!ports.includes(k)) fail.push("缺本地端口 G4-" + k);
}

// 4. 所有成功响应（2xx）必须用 Ok<T> 包裹，不能裸返回对象
const successRefs = [
  ...g4.matchAll(/'2(?:00|01)':\n(?:.*\n){0,6}?\s+\$ref: '#\/components\/schemas\/(\w+)'/g),
].map((m) => m[1]);
const notOk = [...new Set(successRefs.filter((n) => !n.startsWith("Ok")))];
ok.push(`成功响应信封：命中 ${successRefs.length} 处，全部为 Ok<T>`);
if (notOk.length) fail.push("成功响应未用 Ok 包裹：" + notOk.join(", "));

// 5. 跨文件引用目标必须存在于 resource-types.yaml
const rtSchemas = new Set([...rt.matchAll(/^ {4}([A-Za-z][A-Za-z0-9]*):$/gm)].map((m) => m[1]));
const extRefs = [
  ...new Set(
    [...g4.matchAll(/resource-types\.yaml#\/components\/schemas\/([A-Za-z0-9]+)/g)].map((m) => m[1])
  ),
];
const missExt = extRefs.filter((n) => !rtSchemas.has(n));
ok.push(`跨文件引用 ${extRefs.length} 个，全部可解析`);
if (missExt.length) fail.push("跨文件引用未定义：" + missExt.join(", "));

// 6. 同文件内部引用必须存在
const g4Schemas = new Set([...g4.matchAll(/^ {4}([A-Za-z][A-Za-z0-9-]*):$/gm)].map((m) => m[1]));
const intRefs = [
  ...new Set(
    [...g4.matchAll(/(?<!resource-types\.yaml)#\/components\/schemas\/([A-Za-z0-9]+)/g)].map((m) => m[1])
  ),
];
const missInt = intRefs.filter((n) => !g4Schemas.has(n));
ok.push(`同文件引用 ${intRefs.length} 个，全部可解析`);
if (missInt.length) fail.push("内部引用未定义：" + missInt.join(", "));

// 7. ResourceChange 父模式只能是 oneOf 组合，不能声明 additionalProperties
const rc = block(rt, "ResourceChange");
if (/^\s+additionalProperties:/m.test(rc)) fail.push("ResourceChange 父模式声明了 additionalProperties，子类型字段会被拒绝");
if (!/oneOf:/.test(rc)) fail.push("ResourceChange 缺 oneOf 组合");

// 8. 拒绝事实必须是完整 Error 形状
const rej = block(rt, "ClaimRejection");
for (const f of ["traceId", "details"]) {
  if (!rej.includes(f)) fail.push("ClaimRejection 缺字段 " + f + "，无法重放原 409/422");
}
if (!rt.includes("\n    ClaimAdjudication:")) fail.push("缺 ClaimAdjudication");

// 9. ResourceSourceType 限定四种，ResourceSlice 不得使用全部 SourceType
if (!/ResourceSourceType:[\s\S]{0,600}enum: \[PERSONAL, WHOLE, REPAIR, ADMIN\]/.test(rt)) {
  fail.push("ResourceSourceType 未限定为四种资源来源");
}
if (!block(rt, "ResourceSlice").includes("ResourceSourceType")) {
  fail.push("ResourceSlice.sourceType 未使用 ResourceSourceType");
}

// 10. 关键枚举存在且取值完整
const enums = {
  SourceType: ["PERSONAL", "WHOLE", "REPAIR", "ADMIN"],
  ClaimState: ["ACTIVE", "RELEASED", "INVALIDATED", "REJECTED", "EXPIRED", "ABSENT"],
  Socket: ["NONE", "FIXED", "RAIL"],
  RoomType: ["CLASSROOM", "LIBRARY", "OTHER"],
};
for (const [name, values] of Object.entries(enums)) {
  const b = block(rt, name);
  if (!b.includes("enum:")) {
    fail.push("枚举 " + name + " 缺失或无 enum");
    continue;
  }
  const miss = values.filter((v) => !b.includes(v));
  if (miss.length) fail.push("枚举 " + name + " 缺取值：" + miss.join(","));
}

// 11. ReasonBody 必须带 expectedVersion
if (!block(g4, "ReasonBody").includes("expectedVersion")) fail.push("ReasonBody 缺 expectedVersion，丢失乐观并发控制");

// 12. Metrics 必须含必填 notifications
if (!/required:\s*\[asOf, operations, notifications\]/.test(block(g4, "Metrics"))) {
  fail.push("Metrics 缺必填 notifications");
}

// 13. 事件契约存在、可解析、带样例
const evDir = join(root, "contracts/events/g4");
let evFiles = [];
if (existsSync(evDir)) evFiles = readdirSync(evDir).filter((f) => f.endsWith(".json"));
else fail.push("缺 contracts/events/g4 目录");
ok.push(`事件契约 ${evFiles.length} 个`);
for (const need of ["ClaimInvalidated", "MaintenanceImpactApplied"]) {
  const hit = evFiles.some((f) => {
    try {
      return JSON.parse(read(join("contracts/events/g4", f)))["x-contract"].eventType === need;
    } catch {
      return false;
    }
  });
  if (!hit) fail.push("缺事件 " + need);
}
for (const f of evFiles) {
  try {
    const j = JSON.parse(read(join("contracts/events/g4", f)));
    if (!j.examples || j.examples.length === 0) fail.push(f + " 缺 examples");
  } catch (e) {
    fail.push(f + " JSON 解析失败：" + e.message);
  }
}

// 14. 本地端口名称已按基线固定
if (!md.includes("releaseAdmin")) fail.push("local/g4.md 未使用基线方法名 releaseAdmin");
if (md.includes("名称待确认")) fail.push("local/g4.md 仍存在'名称待确认'的未决项");

// 输出
console.log("—— G4 契约自检 ——");
ok.forEach((l) => console.log("  · " + l));
console.log("");
if (fail.length) {
  console.log(`✗ 失败 ${fail.length} 项：`);
  fail.forEach((f) => console.log("  - " + f));
  process.exit(1);
}
console.log("✓ 全部通过（本脚本只校验结构，规范校验见 contracts/local/g4.md 第 13 节）");
