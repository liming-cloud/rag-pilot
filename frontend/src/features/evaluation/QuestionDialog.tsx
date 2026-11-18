import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { ErrorMessage } from "@/lib/errors";
import { DocumentPicker } from "./DocumentPicker";
import {
  evaluationKeys,
  EXPECTATION_LABELS,
  type Expectation,
  fetchKnowledgeBaseOptions,
  fetchSets,
  MATCH_LABELS,
  type MatchMode,
  type QuestionDraft,
  saveQuestion,
} from "./api";

type Props = {
  /** 为 null 时（从试问存）先选题集。 */
  setId: number | null;
  /** 为 null 时新建。 */
  questionId: number | null;
  initial: QuestionDraft;
  /** 已选文档的文件名（编辑时由标准题给出），选择器翻页后也能显示。 */
  initialNames?: Record<number, string>;
  onDone: () => void;
};

/**
 * 新建、修改标准题（#224）：一道题在一个知识库里检索；期望命中这个库里的一份或多份文档（全部命中或任一命中），
 * 或者期望拒答。存的是文档，不是分块：重新分块、上传新版本后标准题照样有效。
 */
export function QuestionDialog({ setId, questionId, initial, initialNames = {}, onDone }: Props) {
  const queryClient = useQueryClient();
  const [edited, setDraft] = useState(initial);
  const [targetSet, setTargetSet] = useState<number | null>(setId);
  const sets = useQuery({ queryKey: evaluationKeys.sets, queryFn: fetchSets, enabled: setId === null });
  const knowledgeBases = useQuery({ queryKey: evaluationKeys.knowledgeBases, queryFn: fetchKnowledgeBaseOptions });
  const chosenSet = targetSet ?? sets.data?.[0]?.id ?? null;
  // 新建时还没选知识库，默认第一个。
  const draft = { ...edited, knowledgeBaseId: edited.knowledgeBaseId || (knowledgeBases.data?.[0]?.id ?? 0) };
  const save = useMutation({
    mutationFn: () => saveQuestion(chosenSet!, questionId, draft),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: evaluationKeys.all });
      onDone();
    },
  });
  const update = (change: Partial<QuestionDraft>) => setDraft({ ...draft, ...change });
  const incomplete =
    !draft.question.trim() || chosenSet === null || !draft.knowledgeBaseId || (draft.expectation === "DOCUMENTS" && draft.expectedDocumentIds.length === 0);

  function submit(event: FormEvent) {
    event.preventDefault();
    save.mutate();
  }

  return (
    <DialogContent className="max-w-2xl">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <DialogHeader>
          <DialogTitle>{questionId === null ? "添加标准题" : "修改标准题"}</DialogTitle>
          <DialogDescription>判定只看三个信号：有没有拒答、有没有召回期望文档、引用里有没有期望文档。</DialogDescription>
        </DialogHeader>
        {setId === null && (
          <Label className="flex flex-col gap-1">
            存到题集
            <NativeSelect value={chosenSet ?? ""} onChange={(event) => setTargetSet(Number(event.target.value))}>
              {sets.data?.map((set) => (
                <NativeSelectOption key={set.id} value={set.id}>
                  {set.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
            {sets.data?.length === 0 && <span className="text-sm text-destructive">还没有题集，先到“题集”里新建一个。</span>}
          </Label>
        )}
        <Label className="flex flex-col gap-1">
          问题
          <Textarea
            value={draft.question}
            rows={2}
            maxLength={500}
            required
            onChange={(event) => update({ question: event.target.value })}
          />
        </Label>
        <div className="flex flex-wrap gap-3">
          <Label className="flex flex-col gap-1">
            知识库
            <NativeSelect
              value={draft.knowledgeBaseId}
              className="w-60"
              onChange={(event) => update({ knowledgeBaseId: Number(event.target.value), expectedDocumentIds: [] })}
            >
              {knowledgeBases.data?.map((base) => (
                <NativeSelectOption key={base.id} value={base.id}>
                  {base.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Label>
          <Label className="flex flex-col gap-1">
            期望
            <NativeSelect
              value={draft.expectation}
              onChange={(event) => update({ expectation: event.target.value as Expectation })}
            >
              {Object.entries(EXPECTATION_LABELS).map(([value, label]) => (
                <NativeSelectOption key={value} value={value}>
                  {label}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Label>
          {draft.expectation === "DOCUMENTS" && draft.expectedDocumentIds.length > 1 && (
            <Label className="flex flex-col gap-1">
              命中方式
              <NativeSelect value={draft.match} onChange={(event) => update({ match: event.target.value as MatchMode })}>
                {Object.entries(MATCH_LABELS).map(([value, label]) => (
                  <NativeSelectOption key={value} value={value}>
                    {label}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </Label>
          )}
        </div>
        {draft.expectation === "DOCUMENTS" ? (
          <DocumentPicker
            knowledgeBaseId={draft.knowledgeBaseId}
            selected={draft.expectedDocumentIds}
            initialNames={initialNames}
            onChange={(expectedDocumentIds) => update({ expectedDocumentIds })}
          />
        ) : (
          <p className="text-sm text-muted-foreground">资料里没有答案、超出范围的问题：系统拒答才算通过。</p>
        )}
        <ErrorMessage error={save.error ?? sets.error ?? knowledgeBases.error} />
        <DialogFooter>
          <Button type="submit" disabled={save.isPending || incomplete}>
            保存
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}
