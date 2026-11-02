import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ACCEPT } from "./batchUpload";
import { type KnowledgeDocument, updateValidity, type ValidityRange } from "./api";

/** 表格里显示的有效期，分成“某日起”“至某日”两段，窄屏时各占一行而不是把日期从中间折断。 */
export function rangeParts({ effectiveFrom, expiresOn }: ValidityRange): string[] {
  const parts = [...(effectiveFrom ? [`${effectiveFrom} 起`] : []), ...(expiresOn ? [`至 ${expiresOn}`] : [])];
  return parts.length > 0 ? parts : ["长期有效"];
}

function invalid({ effectiveFrom, expiresOn }: ValidityRange) {
  return effectiveFrom && expiresOn && expiresOn < effectiveFrom ? "失效日期不能早于生效日期" : null;
}

/** 生效、失效日期两个输入框；清空表示不限。规则写在下面，免得管理员猜“失效当天还能不能用”。 */
function ValidityFields({ value, onChange }: { value: ValidityRange; onChange: (value: ValidityRange) => void }) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1 text-sm">有效期</legend>
      <div className="flex flex-wrap gap-3">
        <Label className="flex flex-col gap-1 text-sm">
          生效日期
          <Input
            type="date"
            value={value.effectiveFrom ?? ""}
            onChange={(event) => onChange({ ...value, effectiveFrom: event.target.value || null })}
          />
        </Label>
        <Label className="flex flex-col gap-1 text-sm">
          失效日期
          <Input
            type="date"
            value={value.expiresOn ?? ""}
            onChange={(event) => onChange({ ...value, expiresOn: event.target.value || null })}
          />
        </Label>
      </div>
      <p className="text-xs text-muted-foreground">
        留空表示不限。失效日期当天仍可使用，次日 0
        点起不再参与回答；未到生效日期的也不参与。员工过去的回答记录里仍能查看原文。
      </p>
    </fieldset>
  );
}

/** 修改一份文档的有效期（doc:update）。 */
export function ValidityDialog({
  knowledgeBaseId,
  document,
  onDone,
}: {
  knowledgeBaseId: number;
  document: KnowledgeDocument;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [range, setRange] = useState<ValidityRange>({
    effectiveFrom: document.effectiveFrom,
    expiresOn: document.expiresOn,
  });
  const save = useMutation({
    mutationFn: () => updateValidity(knowledgeBaseId, document.id, range),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["knowledge-bases"] });
      onDone();
    },
  });
  const problem = invalid(range);
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>有效期</DialogTitle>
        <DialogDescription className="break-all">{document.filename}</DialogDescription>
      </DialogHeader>
      <form
        aria-label="修改有效期"
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          if (!problem) save.mutate();
        }}
      >
        <ValidityFields value={range} onChange={setRange} />
        {(problem ?? save.error) && <p className="text-sm text-destructive">{problem ?? save.error?.message}</p>}
        <DialogFooter>
          <Button type="button" variant="ghost" size="sm" onClick={onDone}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={save.isPending || problem !== null}>
            保存
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}

/**
 * 上传新版本：选文件，有效期默认继承旧版本的失效日期，生效日期留空（新版本上传后即生效），可以在这里改。
 */
export function ReplaceDialog({
  document,
  onSubmit,
  onDone,
}: {
  document: KnowledgeDocument;
  onSubmit: (file: File, range: ValidityRange) => void;
  onDone: () => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [range, setRange] = useState<ValidityRange>({ effectiveFrom: null, expiresOn: document.expiresOn });
  const problem = invalid(range);
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>上传新版本</DialogTitle>
        <DialogDescription className="break-all">
          替换《{document.filename}》。新版本解析成功后旧版本自动删除；解析失败时保留旧版本。
        </DialogDescription>
      </DialogHeader>
      <form
        aria-label="上传新版本"
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          if (file && !problem) {
            onSubmit(file, range);
            onDone();
          }
        }}
      >
        <Label className="flex flex-col gap-1 text-sm">
          新版本文件
          <Input
            type="file"
            accept={ACCEPT}
            className="py-1.5"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
        </Label>
        <ValidityFields value={range} onChange={setRange} />
        {problem && <p className="text-sm text-destructive">{problem}</p>}
        <DialogFooter>
          <Button type="button" variant="ghost" size="sm" onClick={onDone}>
            取消
          </Button>
          <Button type="submit" size="sm" disabled={!file || problem !== null}>
            上传
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}
