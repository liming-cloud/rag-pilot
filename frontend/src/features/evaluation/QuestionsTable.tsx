import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Search, Trash2 } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Pagination } from "@/components/ui/pagination";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ErrorMessage } from "@/lib/errors";
import { PAGE_SIZE } from "@/lib/paging";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { deleteQuestion, evaluationKeys, fetchQuestions, type GoldenQuestion, MATCH_LABELS } from "./api";

/** 题集里的标准题：按问题关键词和“只看过期”筛选，前端分页（一个题集一般几十到几百道题）。 */
export function QuestionsTable({ setId, onEdit }: { setId: number; onEdit: (question: GoldenQuestion) => void }) {
  const [keyword, setKeyword] = useState("");
  const [applied, setApplied] = useState("");
  const [staleOnly, setStaleOnly] = useState(false);
  const [page, setPage] = useState(1);
  const questions = useQuery({ queryKey: evaluationKeys.questions(setId), queryFn: () => fetchQuestions(setId) });
  const matched =
    questions.data?.filter((question) => (!applied || question.question.includes(applied)) && (!staleOnly || question.stale)) ??
    [];
  const shown = matched.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  function search(event: FormEvent) {
    event.preventDefault();
    setApplied(keyword.trim());
    setPage(1);
  }

  return (
    <section aria-label="标准题" className="flex min-w-0 flex-col gap-3">
      <form role="search" aria-label="筛选标准题" onSubmit={search} className="flex flex-wrap items-center gap-2">
        <Input
          value={keyword}
          aria-label="问题关键词"
          placeholder="问题关键词"
          className="w-56"
          onChange={(event) => setKeyword(event.target.value)}
        />
        <NativeSelect
          aria-label="状态"
          value={staleOnly ? "stale" : "all"}
          onChange={(event) => {
            setStaleOnly(event.target.value === "stale");
            setPage(1);
          }}
        >
          <NativeSelectOption value="all">全部</NativeSelectOption>
          <NativeSelectOption value="stale">只看过期</NativeSelectOption>
        </NativeSelect>
        <Button type="submit" size="sm" variant="outline">
          <Search aria-hidden="true" className="size-4" />
          查询
        </Button>
      </form>
      <ErrorMessage error={questions.error} className="text-base" />
      <div className="overflow-x-auto rounded-xl border bg-card shadow-sm">
        <Table aria-label="标准题">
          <TableHeader>
            <TableRow>
              <TableHead>问题</TableHead>
              <TableHead className="w-36">知识库</TableHead>
              <TableHead className="w-64">期望</TableHead>
              <TableHead className="w-24" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {questions.data && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} className="py-10 text-center text-muted-foreground">
                  {questions.data.length === 0 ? "还没有标准题。可以从“试问”存、导入 CSV，或在这里添加。" : "没有符合条件的标准题"}
                </TableCell>
              </TableRow>
            )}
            {shown.map((question) => (
              <QuestionRow key={question.id} setId={setId} question={question} onEdit={() => onEdit(question)} />
            ))}
          </TableBody>
        </Table>
      </div>
      {questions.data && (
        <Pagination page={page} size={PAGE_SIZE} total={matched.length} onChange={(next) => setPage(next.page ?? 1)} />
      )}
    </section>
  );
}

function QuestionRow({ setId, question, onEdit }: { setId: number; question: GoldenQuestion; onEdit: () => void }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const remove = useMutation({
    mutationFn: () => deleteQuestion(setId, question.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: evaluationKeys.all }),
  });

  return (
    <TableRow>
      <TableCell className="max-w-md min-w-56 align-top">
        <span className="line-clamp-3 wrap-anywhere" title={question.question}>
          {question.question}
        </span>
        {question.stale && (
          <Badge variant="destructive" className="mt-1">
            过期：期望文档已删除，不参与运行
          </Badge>
        )}
        <ErrorMessage error={remove.error} />
      </TableCell>
      <TableCell className="align-top text-muted-foreground">{question.knowledgeBaseName}</TableCell>
      <TableCell className="align-top">
        {question.expectation === "REFUSAL" ? (
          <Badge variant="outline">应当拒答</Badge>
        ) : (
          <div className="flex flex-col gap-0.5 text-sm">
            {question.expectedDocuments.length > 1 && (
              <span className="text-xs text-muted-foreground">{MATCH_LABELS[question.match]}</span>
            )}
            {question.expectedDocuments.map((document) => (
              <span
                key={document.documentId}
                title={document.filename}
                className={document.deleted ? "truncate text-destructive line-through" : "truncate"}
              >
                {document.filename}
              </span>
            ))}
          </div>
        )}
      </TableCell>
      <TableCell className="align-top">
        <Can permission={PERMISSIONS.EVAL_MANAGE}>
          {confirming ? (
            <span className="flex flex-col items-end gap-1">
              <Button size="sm" variant="destructive" disabled={remove.isPending} onClick={() => remove.mutate()}>
                确认删除
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setConfirming(false)}>
                取消
              </Button>
            </span>
          ) : (
            <span className="flex justify-end gap-1">
              <Button size="icon" variant="ghost" aria-label={`修改：${question.question}`} onClick={onEdit}>
                <Pencil aria-hidden="true" className="size-4" />
              </Button>
              <Button size="icon" variant="ghost" aria-label={`删除：${question.question}`} onClick={() => setConfirming(true)}>
                <Trash2 aria-hidden="true" className="size-4" />
              </Button>
            </span>
          )}
        </Can>
      </TableCell>
    </TableRow>
  );
}
