import { useMutation, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { ErrorMessage } from "@/lib/errors";
import { type EvalSet, evaluationKeys, saveSet } from "./api";

/** 新建或修改题集的名称和说明。 */
export function SetDialog({ set, onDone }: { set: EvalSet | null; onDone: (saved: EvalSet) => void }) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(set?.name ?? "");
  const [description, setDescription] = useState(set?.description ?? "");
  const save = useMutation({
    mutationFn: () => saveSet(set?.id ?? null, { name: name.trim(), description: description.trim() }),
    onSuccess: async (saved) => {
      await queryClient.invalidateQueries({ queryKey: evaluationKeys.sets });
      onDone(saved);
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    save.mutate();
  }

  return (
    <DialogContent className="max-w-lg">
      <form onSubmit={submit} className="flex flex-col gap-4">
        <DialogHeader>
          <DialogTitle>{set ? "修改题集" : "新建题集"}</DialogTitle>
        </DialogHeader>
        <Label className="flex flex-col gap-1">
          名称
          <Input value={name} maxLength={100} required onChange={(event) => setName(event.target.value)} />
        </Label>
        <Label className="flex flex-col gap-1">
          说明（可选）
          <Textarea
            value={description}
            maxLength={500}
            rows={3}
            placeholder="例如：换模型、整理资料后跑一遍，看常见问题有没有变差"
            onChange={(event) => setDescription(event.target.value)}
          />
        </Label>
        <ErrorMessage error={save.error} />
        <DialogFooter>
          <Button type="submit" disabled={save.isPending || !name.trim()}>
            保存
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  );
}
