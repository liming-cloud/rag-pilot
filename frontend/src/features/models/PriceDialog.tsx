import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorMessage } from "@/lib/errors";
import { type ModelPrice, modelKeys, saveModelPrice } from "./api";

/** 填或改一个模型每百万 token 的输入、输出单价（元）；费用按它估算，改价后历史用量也按新价算。 */
export function PriceDialog({ price, onDone }: { price: ModelPrice | null; onDone: () => void }) {
  return (
    <Dialog open={price !== null} onOpenChange={(open) => !open && onDone()}>
      {price && <PriceForm price={price} onDone={onDone} />}
    </Dialog>
  );
}

function PriceForm({ price, onDone }: { price: ModelPrice; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [input, setInput] = useState(price.inputPerMillion?.toString() ?? "");
  const [output, setOutput] = useState(price.outputPerMillion?.toString() ?? "");
  const valid = [input, output].every((value) => value.trim() !== "" && Number(value) >= 0);
  const save = useMutation({
    mutationFn: () =>
      saveModelPrice({ model: price.model, inputPerMillion: Number(input), outputPerMillion: Number(output) }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: modelKeys.usage });
      onDone();
    },
  });

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>设置单价：{price.model}</DialogTitle>
        <DialogDescription>
          填服务商价目表上的价格，单位是元 / 百万 token。费用只是估算，改价后历史用量也按新价算。
        </DialogDescription>
      </DialogHeader>
      <Label className="flex flex-col gap-1 text-sm">
        输入单价（元 / 百万 token）
        <Input type="number" min={0} step="any" value={input} onChange={(event) => setInput(event.target.value)} />
      </Label>
      <Label className="flex flex-col gap-1 text-sm">
        输出单价（元 / 百万 token）
        <Input type="number" min={0} step="any" value={output} onChange={(event) => setOutput(event.target.value)} />
      </Label>
      <ErrorMessage error={save.error} />
      <DialogFooter>
        <Button type="button" variant="ghost" size="sm" onClick={onDone}>
          取消
        </Button>
        <Button type="button" size="sm" disabled={!valid || save.isPending} onClick={() => save.mutate()}>
          保存
        </Button>
      </DialogFooter>
    </DialogContent>
  );
}
