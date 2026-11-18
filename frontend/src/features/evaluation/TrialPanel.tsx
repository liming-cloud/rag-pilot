import { useMutation, useQuery } from "@tanstack/react-query";
import { Save, Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { QuestionDialog } from "./QuestionDialog";
import { evaluationKeys, fetchKnowledgeBaseOptions, type QuestionDraft, type TrialResult, trialAsk } from "./api";

/**
 * 试问（#224）：在一个知识库里按自己的权限检索和回答，列出召回的原文、相关度和是否被引用；不写回答记录。
 * 觉得结果对，就存为标准题：期望文档默认取被引用的文档，拒答时默认“应当拒答”，存之前可以改。
 */
export function TrialPanel({ initialQuestion }: { initialQuestion: string }) {
  const [question, setQuestion] = useState(initialQuestion);
  const [knowledgeBaseId, setKnowledgeBaseId] = useState<number | null>(null);
  const [saving, setSaving] = useState<{ draft: QuestionDraft; names: Record<number, string> } | null>(null);
  const knowledgeBases = useQuery({ queryKey: evaluationKeys.knowledgeBases, queryFn: fetchKnowledgeBaseOptions });
  const selected = knowledgeBaseId ?? knowledgeBases.data?.[0]?.id ?? null;
  const ask = useMutation({
    mutationFn: (input: { knowledgeBaseId: number; question: string }) =>
      trialAsk(input.knowledgeBaseId, input.question),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (selected !== null && question.trim()) ask.mutate({ knowledgeBaseId: selected, question: question.trim() });
  }

  return (
    <div className="flex flex-col gap-4">
      <form onSubmit={submit} className="flex flex-col gap-3 rounded-xl border bg-card p-4 shadow-sm">
        <div className="flex flex-wrap items-end gap-3">
          <Label className="flex flex-col gap-1 text-sm">
            知识库
            <NativeSelect
              value={selected ?? ""}
              className="w-60"
              onChange={(event) => setKnowledgeBaseId(Number(event.target.value))}
            >
              {knowledgeBases.data?.map((base) => (
                <NativeSelectOption key={base.id} value={base.id}>
                  {base.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Label>
          <p className="pb-2 text-sm text-muted-foreground">按你自己的权限检索，不写回答记录，用量记为“评测”。</p>
        </div>
        <Label className="flex flex-col gap-1 text-sm">
          问题
          <Textarea
            value={question}
            rows={2}
            maxLength={500}
            placeholder="输入员工会怎么问"
            onChange={(event) => setQuestion(event.target.value)}
          />
        </Label>
        <div>
          <Button type="submit" size="sm" disabled={ask.isPending || selected === null || !question.trim()}>
            <Search aria-hidden="true" className="size-4" />
            {ask.isPending ? "检索和回答中…" : "试问"}
          </Button>
        </div>
      </form>
      <ErrorMessage error={knowledgeBases.error ?? ask.error} className="text-base" />
      {ask.data && ask.variables && (
        <TrialOutcome
          result={ask.data}
          onSave={() =>
            setSaving({
              draft: draftFrom(ask.variables, ask.data),
              names: Object.fromEntries(ask.data.citedDocuments.map((d) => [d.documentId, d.documentName])),
            })
          }
        />
      )}
      <Dialog open={saving !== null} onOpenChange={(open) => !open && setSaving(null)}>
        {saving && (
          <QuestionDialog
            setId={null}
            questionId={null}
            initial={saving.draft}
            initialNames={saving.names}
            onDone={() => setSaving(null)}
          />
        )}
      </Dialog>
    </div>
  );
}

function draftFrom(input: { knowledgeBaseId: number; question: string }, result: TrialResult): QuestionDraft {
  return {
    question: input.question,
    knowledgeBaseId: input.knowledgeBaseId,
    expectation: result.refused ? "REFUSAL" : "DOCUMENTS",
    match: "ALL",
    expectedDocumentIds: result.refused ? [] : result.citedDocuments.map((document) => document.documentId),
  };
}

function TrialOutcome({ result, onSave }: { result: TrialResult; onSave: () => void }) {
  const { passages } = result;
  return (
    <section aria-label="试问结果" className="flex flex-col gap-3">
      <div className="flex flex-col gap-2 rounded-xl border bg-card p-4 shadow-sm">
        <div className="flex items-center gap-2">
          <h2 className="mr-auto font-medium">回答</h2>
          {result.refused && <Badge variant="destructive">拒答</Badge>}
          <Can permission={PERMISSIONS.EVAL_MANAGE}>
            <Button size="sm" variant="outline" onClick={onSave}>
              <Save aria-hidden="true" className="size-4" />
              存为标准题
            </Button>
          </Can>
        </div>
        <p className="text-sm whitespace-pre-wrap">{result.text}</p>
        {result.citedDocuments.length > 0 && (
          <p className="text-sm text-muted-foreground">
            引用：{result.citedDocuments.map((document) => document.documentName).join("、")}
          </p>
        )}
      </div>
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <Table aria-label="召回的原文">
          <TableHeader>
            <TableRow>
              <TableHead className="w-56">文档</TableHead>
              <TableHead>原文</TableHead>
              <TableHead className="w-20 text-right">相关度</TableHead>
              <TableHead className="w-20">引用</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {passages.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                  没有召回任何原文
                </TableCell>
              </TableRow>
            )}
            {passages.map((passage, index) => (
              <TableRow key={index}>
                <TableCell className="max-w-56 align-top">
                  <div className="truncate font-medium" title={passage.documentName}>
                    {passage.documentName}
                  </div>
                  <div className="truncate text-xs text-muted-foreground" title={passage.headingPath}>
                    {passage.headingPath}
                  </div>
                </TableCell>
                <TableCell className="align-top">
                  <span className="line-clamp-4 text-sm wrap-anywhere" title={passage.excerpt}>
                    {passage.excerpt}
                  </span>
                </TableCell>
                <TableCell className="text-right align-top tabular-nums">{passage.relevance.toFixed(2)}</TableCell>
                <TableCell className="align-top">
                  {passage.cited ? <Badge variant="success">已引用</Badge> : <span className="text-muted-foreground">—</span>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </section>
  );
}
