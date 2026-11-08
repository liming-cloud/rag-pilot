import { json } from "@/lib/api";
import { authFetch } from "@/lib/auth";

/** 授权对象：角色或账号，与知识库授权同一套模型（D-056）。 */
export type GranteeType = "ROLE" | "USER";

/** 与后端 /api/data-grants 一致：授权给一个角色或账号的工具、站点、区域。 */
export type DataGrant = { tools: string[]; units: string[]; regions: string[] };

/** 可授权的工具（部署配置）。 */
export type DataTool = { name: string; title: string };

/** 客户系统的站点主数据：编号、名称和所属区域。 */
export type ScopeUnit = { id: string; name: string; region: string };

export const dataKeys = {
  all: ["data-grants"] as const,
  tools: ["data-grants", "tools"] as const,
  units: ["data-grants", "units"] as const,
  grant: (type: GranteeType, id: number) => ["data-grants", "grant", type, id] as const,
};

export async function fetchDataTools(): Promise<DataTool[]> {
  return json(await authFetch("/api/data-grants/tools"), "读取可授权的工具失败");
}

/** 取不到时后端返回 503，提示稍后再试。 */
export async function fetchScopeUnits(): Promise<ScopeUnit[]> {
  return json(await authFetch("/api/data-grants/units"), "读取站点信息失败");
}

export async function fetchDataGrant(type: GranteeType, id: number): Promise<DataGrant> {
  return json(await authFetch(`/api/data-grants?granteeType=${type}&granteeId=${id}`), "读取授权失败");
}

/** 整体替换：请求里没有的就是收回。 */
export async function saveDataGrant(type: GranteeType, id: number, grant: DataGrant): Promise<DataGrant> {
  const response = await authFetch(`/api/data-grants?granteeType=${type}&granteeId=${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(grant),
  });
  return json(response, "保存授权失败");
}
