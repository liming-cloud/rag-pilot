import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Pencil, Play, Plus, Trash2, Upload } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";
import { PageShell } from "@/components/ui/page-shell";
import { ErrorMessage } from "@/lib/errors";
import { Can, PERMISSIONS } from "@/lib/permissions";
import { ImportDialog } from "./ImportDialog";
import { QuestionDialog } from "./QuestionDialog";
import { QuestionsTable } from "./QuestionsTable";
import { RunDialog } from "./RunDialog";
import { RunsTable } from "./RunsTable";
import { SetDialog } from "./SetDialog";
import { deleteSet, evaluationKeys, fetchSet, type GoldenQuestion } from "./api";

type Opened =
  | { kind: "edit-set" }
  | { kind: "question"; question: GoldenQuestion | null }
  | { kind: "import" }
  | { kind: "run" }
  | null;

/** 一个题集：标准题（可筛选、编辑、导入）和历次运行；“运行”前先显示预估费用。 */
export function SetDetailPage() {
  const setId = Number(useParams().setId);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [opened, setOpened] = useState<Opened>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const set = useQuery({ queryKey: evaluationKeys.set(setId), queryFn: () => fetchSet(setId) });
  const remove = useMutation({
    mutationFn: () => deleteSet(setId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: evaluationKeys.sets });
      void navigate("/admin/evaluation");
    },
  });
  const close = () => setOpened(null);

  return (
    <PageShell>
      <Link to="/admin/evaluation" className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft aria-hidden="true" className="size-4" />
        知识质量
      </Link>
      <ErrorMessage error={set.error ?? remove.error} className="text-base" />
      {set.data && (
        <div className="flex flex-wrap items-start gap-2">
          <div className="mr-auto">
            <h1 className="font-display text-2xl font-semibold tracking-tight wrap-anywhere">{set.data.name}</h1>
            {set.data.description && <p className="text-sm text-muted-foreground">{set.data.description}</p>}
          </div>
          <Can permission={PERMISSIONS.EVAL_RUN}>
            <Button size="sm" disabled={set.data.questionCount === 0} onClick={() => setOpened({ kind: "run" })}>
              <Play aria-hidden="true" className="size-4" />
              运行
            </Button>
          </Can>
          <Can permission={PERMISSIONS.EVAL_MANAGE}>
            <Button size="sm" variant="outline" onClick={() => setOpened({ kind: "question", question: null })}>
              <Plus aria-hidden="true" className="size-4" />
              添加标准题
            </Button>
            <Button size="sm" variant="outline" onClick={() => setOpened({ kind: "import" })}>
              <Upload aria-hidden="true" className="size-4" />
              导入 CSV
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setOpened({ kind: "edit-set" })}>
              <Pencil aria-hidden="true" className="size-4" />
              改名
            </Button>
            {confirmingDelete ? (
              <span className="flex items-center gap-1">
                <span className="text-sm">删除题集和全部运行记录？</span>
                <Button size="sm" variant="destructive" disabled={remove.isPending} onClick={() => remove.mutate()}>
                  确认删除
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setConfirmingDelete(false)}>
                  取消
                </Button>
              </span>
            ) : (
              <Button size="sm" variant="ghost" onClick={() => setConfirmingDelete(true)}>
                <Trash2 aria-hidden="true" className="size-4" />
                删除
              </Button>
            )}
          </Can>
        </div>
      )}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <QuestionsTable setId={setId} onEdit={(question) => setOpened({ kind: "question", question })} />
        <RunsTable setId={setId} />
      </div>
      <Dialog open={opened !== null} onOpenChange={(open) => !open && close()}>
        {opened?.kind === "edit-set" && set.data && <SetDialog set={set.data} onDone={close} />}
        {opened?.kind === "question" && <EditQuestion setId={setId} question={opened.question} onDone={close} />}
        {opened?.kind === "import" && <ImportDialog setId={setId} onDone={close} />}
        {opened?.kind === "run" && (
          <RunDialog setId={setId} onStarted={(run) => void navigate(`/admin/evaluation/runs/${run.id}`)} />
        )}
      </Dialog>
    </PageShell>
  );
}

function EditQuestion({ setId, question, onDone }: { setId: number; question: GoldenQuestion | null; onDone: () => void }) {
  const knowledgeBaseId = question?.knowledgeBaseId ?? 0;
  return (
    <QuestionDialog
      setId={setId}
      questionId={question?.id ?? null}
      initial={{
        question: question?.question ?? "",
        knowledgeBaseId,
        expectation: question?.expectation ?? "DOCUMENTS",
        match: question?.match ?? "ALL",
        expectedDocumentIds: question?.expectedDocuments.filter((d) => !d.deleted).map((d) => d.documentId) ?? [],
      }}
      initialNames={Object.fromEntries(question?.expectedDocuments.map((d) => [d.documentId, d.filename]) ?? [])}
      onDone={onDone}
    />
  );
}
