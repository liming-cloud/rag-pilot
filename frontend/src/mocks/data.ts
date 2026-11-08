import { http, HttpResponse } from "msw";
import type { DataGrant, ScopeUnit } from "@/features/data/api";
import { forbidden } from "./identity";

/** 数据范围授权的假接口（D-056）：站点主数据来自客户系统，这里用示例；授权按「类型:编号」存。 */
const TOOLS = [
  { name: "get_store_inventory", title: "门店库存查询" },
  { name: "get_store_sales", title: "门店销售查询" },
];

const REGIONS = ["east", "south", "north"];
const UNITS: ScopeUnit[] = Array.from({ length: 36 }, (_, index) => ({
  id: `S${String(index + 1).padStart(3, "0")}`,
  name: `${["静安", "徐汇", "浦东", "闵行", "天河", "福田", "朝阳", "海淀"][index % 8]}${index + 1} 店`,
  region: REGIONS[index % REGIONS.length]!,
}));

const grants = new Map<string, DataGrant>();
let unitsDown = false;

export function resetData() {
  grants.clear();
  grants.set("ROLE:4", { tools: ["get_store_inventory"], units: ["S001"], regions: ["east"] });
  unitsDown = false;
}

/** 测试用：让站点主数据取不到。 */
export function setUnitsDown(down: boolean) {
  unitsDown = down;
}

const key = (url: URL) => `${url.searchParams.get("granteeType")}:${url.searchParams.get("granteeId")}`;

export const dataHandlers = [
  http.get("/api/data-grants/tools", () => forbidden("data:view") ?? HttpResponse.json(TOOLS)),
  http.get("/api/data-grants/units", () => {
    const denied = forbidden("data:view");
    if (denied) return denied;
    return unitsDown
      ? HttpResponse.json(
          { status: 503, code: "DATA_UNAVAILABLE", detail: "站点信息暂时取不到，请稍后再试" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        )
      : HttpResponse.json(UNITS);
  }),
  http.get("/api/data-grants", ({ request }) => {
    const denied = forbidden("data:view");
    if (denied) return denied;
    return HttpResponse.json(grants.get(key(new URL(request.url))) ?? { tools: [], units: [], regions: [] });
  }),
  http.put("/api/data-grants", async ({ request }) => {
    const denied = forbidden("data:grant");
    if (denied) return denied;
    const body = (await request.json()) as DataGrant;
    const saved = { tools: [...body.tools].sort(), units: [...body.units].sort(), regions: [...body.regions].sort() };
    grants.set(key(new URL(request.url)), saved);
    return HttpResponse.json(saved);
  }),
];
