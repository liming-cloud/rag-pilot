import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { X } from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { fetchDocuments, knowledgeKeys } from "@/features/knowledge/api";
import { ErrorMessage } from "@/lib/errors";

const SHOWN = 8;

type Props = {
  knowledgeBaseId: number;
  selected: number[];
  initialNames: Record<number, string>;
  onChange: (ids: number[]) => void;
};

/** 从知识库里按文件名搜文档、勾选期望文档；已选的显示在上面，可以单独去掉。只列已入库的文档。 */
export function DocumentPicker({ knowledgeBaseId, selected, initialNames, onChange }: Props) {
  const [keyword, setKeyword] = useState("");
  const [names, setNames] = useState(initialNames);
  const filter = { keyword: keyword.trim(), status: "READY" as const, page: 1, size: SHOWN };
  const documents = useQuery({
    queryKey: knowledgeKeys.documents(knowledgeBaseId, filter),
    queryFn: () => fetchDocuments(knowledgeBaseId, filter),
    placeholderData: keepPreviousData,
  });

  function toggle(id: number, filename: string, checked: boolean) {
    setNames((current) => ({ ...current, [id]: filename }));
    onChange(checked ? [...selected, id] : selected.filter((other) => other !== id));
  }

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1 text-sm font-medium">期望文档</legend>
      <div className="flex min-h-7 flex-wrap gap-1" aria-label="已选的期望文档">
        {selected.length === 0 && <span className="text-sm text-muted-foreground">还没选，从下面勾选</span>}
        {selected.map((id) => (
          <Badge key={id} variant="outline" className="gap-1">
            {names[id] ?? `文档 ${id}`}
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="size-4"
              aria-label={`去掉 ${names[id] ?? id}`}
              onClick={() => onChange(selected.filter((other) => other !== id))}
            >
              <X aria-hidden="true" className="size-3" />
            </Button>
          </Badge>
        ))}
      </div>
      <Input
        value={keyword}
        placeholder="按文件名搜索"
        aria-label="按文件名搜索文档"
        onChange={(event) => setKeyword(event.target.value)}
      />
      <ul className="flex max-h-60 flex-col gap-1 overflow-y-auto rounded-md border p-2">
        {documents.data?.items.length === 0 && <li className="text-sm text-muted-foreground">没有符合条件的文档</li>}
        {documents.data?.items.map((document) => (
          <li key={document.id}>
            <Label className="flex items-center gap-2 font-normal">
              <Checkbox
                checked={selected.includes(document.id)}
                onCheckedChange={(checked) => toggle(document.id, document.filename, checked === true)}
              />
              <span className="truncate" title={document.filename}>
                {document.filename}
              </span>
            </Label>
          </li>
        ))}
        {documents.data && documents.data.total > SHOWN && (
          <li className="text-xs text-muted-foreground">共 {documents.data.total} 份，只列前 {SHOWN} 份，输入文件名缩小范围</li>
        )}
      </ul>
      <ErrorMessage error={documents.error} />
    </fieldset>
  );
}
