import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import {
  type DataGrant,
  dataKeys,
  fetchDataTools,
  fetchScopeUnits,
  type GranteeType,
  saveDataGrant,
  type ScopeUnit,
} from "./api";

/** 站点太多时只列前这么多条，其余用搜索和区域筛选缩小范围。 */
const SHOWN_UNITS = 50;

const toggle = (values: string[], value: string, on: boolean) =>
  on ? [...new Set([...values, value])] : values.filter((existing) => existing !== value);

const removedCount = (before: DataGrant, after: DataGrant) =>
  [
    before.tools.filter((tool) => !after.tools.includes(tool)),
    before.units.filter((unit) => !after.units.includes(unit)),
    before.regions.filter((region) => !after.regions.includes(region)),
  ].reduce((sum, list) => sum + list.length, 0);

const same = (a: DataGrant, b: DataGrant) =>
  a.tools.length === b.tools.length &&
  a.units.length === b.units.length &&
  a.regions.length === b.regions.length &&
  removedCount(a, b) === 0 &&
  removedCount(b, a) === 0;

/**
 * 编辑一个角色或账号的工具与数据范围授权（D-056、D-059）：可用范围 = 账号本人 ∪ 所属角色的授权；区域授权按客户的
 * 站点主数据展开，新增站点自动在范围内。保存是整体替换，会收回授权时先确认；管理员也要授权才能查到数据。
 */
export function GrantEditor({
  type,
  id,
  name,
  initial,
}: {
  type: GranteeType;
  id: number;
  name: string;
  initial: DataGrant;
}) {
  const queryClient = useQueryClient();
  const [saved, setSaved] = useState(initial);
  const [draft, setDraft] = useState(initial);
  const [confirming, setConfirming] = useState(false);
  const tools = useQuery({ queryKey: dataKeys.tools, queryFn: fetchDataTools });
  const units = useQuery({ queryKey: dataKeys.units, queryFn: fetchScopeUnits, retry: false });
  const save = useMutation({
    mutationFn: () => saveDataGrant(type, id, draft),
    onSuccess: async (result) => {
      setSaved(result);
      setDraft(result);
      setConfirming(false);
      await queryClient.invalidateQueries({ queryKey: dataKeys.grant(type, id) });
    },
  });

  const changed = !same(saved, draft);
  const removed = removedCount(saved, draft);

  return (
    <section aria-label={`${name} 的授权`} className="flex flex-col gap-5 rounded-xl border bg-card p-4 shadow-sm">
      <h2 className="text-base font-semibold">
        {name}
        <span className="ml-2 text-xs font-normal text-muted-foreground">{type === "ROLE" ? "角色" : "账号"}</span>
      </h2>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">可调用的工具</legend>
        <ErrorMessage error={tools.error} />
        {tools.data?.length === 0 && <p className="text-sm text-muted-foreground">还没有配置可授权的工具。</p>}
        {tools.data?.map((tool) => (
          <Label key={tool.name} className="flex items-center gap-2 text-sm font-normal">
            <Checkbox
              checked={draft.tools.includes(tool.name)}
              onCheckedChange={(on) => setDraft({ ...draft, tools: toggle(draft.tools, tool.name, on === true) })}
            />
            {tool.title}
            <span className="text-xs text-muted-foreground">{tool.name}</span>
          </Label>
        ))}
      </fieldset>
      <UnitPicker
        units={units.data}
        error={units.error}
        onRetry={() => void units.refetch()}
        draft={draft}
        onChange={setDraft}
      />
      <ErrorMessage error={save.error} />
      <div className="flex items-center justify-end gap-2">
        {changed && <span className="mr-auto text-xs text-muted-foreground">有未保存的修改</span>}
        <Button size="sm" variant="ghost" disabled={!changed} onClick={() => setDraft(saved)}>
          还原
        </Button>
        <Can permission={PERMISSIONS.DATA_GRANT}>
          <Button
            size="sm"
            disabled={!changed || save.isPending}
            onClick={() => (removed > 0 ? setConfirming(true) : save.mutate())}
          >
            保存
          </Button>
        </Can>
      </div>
      <Dialog open={confirming} onOpenChange={setConfirming}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认收回授权</DialogTitle>
            <DialogDescription>
              这次保存会收回 {removed} 项授权（工具、站点或区域），立即生效，相关提问会被拒绝。
            </DialogDescription>
          </DialogHeader>
          <ErrorMessage error={save.error} />
          <DialogFooter>
            <Button variant="ghost" size="sm" onClick={() => setConfirming(false)}>
              取消
            </Button>
            <Button size="sm" disabled={save.isPending} onClick={() => save.mutate()}>
              确认保存
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}

function UnitPicker({
  units,
  error,
  onRetry,
  draft,
  onChange,
}: {
  units: ScopeUnit[] | undefined;
  error: Error | null;
  onRetry: () => void;
  draft: DataGrant;
  onChange: (grant: DataGrant) => void;
}) {
  const [keyword, setKeyword] = useState("");
  const [region, setRegion] = useState("");
  const regions = [...new Set(units?.map((unit) => unit.region))].sort();
  const matching = (units ?? []).filter(
    (unit) =>
      (!region || unit.region === region) && (!keyword || `${unit.id}${unit.name}`.toLowerCase().includes(keyword.toLowerCase())),
  );
  const shown = matching.slice(0, SHOWN_UNITS);
  const row = (children: ReactNode, key: string) => (
    <li key={key} className="p-2 text-sm">
      {children}
    </li>
  );

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1 text-sm font-medium">可查看的站点</legend>
      <p className="text-xs text-muted-foreground">
        勾选区域＝区域内的全部站点（新增站点自动包含）；也可以只勾选个别站点。
      </p>
      <ErrorMessage error={error} />
      {error && (
        <div>
          <Button size="sm" variant="outline" onClick={onRetry}>
            重试
          </Button>
          <span className="ml-2 text-xs text-muted-foreground">取不到站点信息时，已有授权不受影响。</span>
        </div>
      )}
      {units && (
        <>
          <div className="flex flex-wrap items-end gap-2">
            <Label className="flex flex-1 flex-col gap-1 text-sm font-normal">
              搜索站点
              <Input value={keyword} placeholder="编号或名称" onChange={(event) => setKeyword(event.target.value)} />
            </Label>
            <Label className="flex flex-col gap-1 text-sm font-normal">
              区域
              <NativeSelect value={region} onChange={(event) => setRegion(event.target.value)}>
                <NativeSelectOption value="">全部区域</NativeSelectOption>
                {regions.map((name) => (
                  <NativeSelectOption key={name} value={name}>
                    {name}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </Label>
          </div>
          <ul aria-label="站点" className="flex max-h-72 flex-col divide-y overflow-y-auto rounded-md border">
            {regions
              .filter((name) => !region || name === region)
              .map((name) =>
                row(
                  <Label className="flex items-center gap-2 font-medium">
                    <Checkbox
                      checked={draft.regions.includes(name)}
                      onCheckedChange={(on) => onChange({ ...draft, regions: toggle(draft.regions, name, on === true) })}
                    />
                    区域 {name}
                    <span className="text-xs font-normal text-muted-foreground">
                      共 {units.filter((unit) => unit.region === name).length} 个站点
                    </span>
                  </Label>,
                  `region-${name}`,
                ),
              )}
            {matching.length === 0 && row(<span className="text-muted-foreground">没有符合条件的站点</span>, "none")}
            {shown.map((unit) =>
              row(
                <Label className="flex items-center gap-2 font-normal">
                  <Checkbox
                    checked={draft.units.includes(unit.id) || draft.regions.includes(unit.region)}
                    disabled={draft.regions.includes(unit.region)}
                    onCheckedChange={(on) => onChange({ ...draft, units: toggle(draft.units, unit.id, on === true) })}
                  />
                  {unit.name}
                  <span className="text-xs text-muted-foreground">
                    {unit.id} · {unit.region}
                  </span>
                </Label>,
                unit.id,
              ),
            )}
          </ul>
          {matching.length > shown.length && (
            <p className="text-xs text-muted-foreground">
              共 {matching.length} 个，只显示前 {shown.length} 个，请用搜索或区域缩小范围。
            </p>
          )}
        </>
      )}
    </fieldset>
  );
}
