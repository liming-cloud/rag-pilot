import { ThumbsDown, ThumbsUp } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { rateAnswer } from "./api";

type Rating = "UP" | "DOWN" | null;

/**
 * 回答下方的点赞点踩。点一下就提交，再点一次撤销；点踩后可以补一句哪里不对。
 * 提交失败时恢复原状。拒答也能点“没帮助”，管理员据此发现知识库缺了什么。
 */
export function FeedbackBar({ answerId, allowUp = true }: { answerId: number | null; allowUp?: boolean }) {
  const [rating, setRating] = useState<Rating>(null);
  const [comment, setComment] = useState("");
  const [state, setState] = useState<"idle" | "failed" | "thanked">("idle");

  if (answerId === null) {
    return null;
  }
  const id = answerId;

  async function send(next: Rating, note?: string) {
    const previous = rating;
    setRating(next);
    setState("idle");
    try {
      await rateAnswer(id, next, note);
      if (note) {
        setState("thanked");
      }
    } catch {
      setRating(previous);
      setState("failed");
    }
  }

  function submitComment(event: FormEvent) {
    event.preventDefault();
    if (comment.trim()) {
      void send("DOWN", comment.trim());
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex justify-end gap-1">
        {allowUp && (
          <Button
            variant="ghost"
            size="icon"
            aria-label="有帮助"
            aria-pressed={rating === "UP"}
            onClick={() => void send(rating === "UP" ? null : "UP")}
          >
            <ThumbsUp aria-hidden="true" className="size-4" />
          </Button>
        )}
        <Button
          variant="ghost"
          size="icon"
          aria-label="没帮助"
          aria-pressed={rating === "DOWN"}
          onClick={() => void send(rating === "DOWN" ? null : "DOWN")}
        >
          <ThumbsDown aria-hidden="true" className="size-4" />
        </Button>
      </div>
      {rating === "DOWN" && state !== "thanked" && (
        <form onSubmit={submitComment} className="flex gap-2">
          <Label className="sr-only" htmlFor={`feedback-${id}`}>
            哪里不对？（选填）
          </Label>
          <Input
            id={`feedback-${id}`}
            value={comment}
            maxLength={500}
            onChange={(event) => setComment(event.target.value)}
            placeholder="哪里不对？（选填）"
            className="flex-1"
          />
          <Button type="submit" size="sm" variant="outline" disabled={!comment.trim()}>
            提交
          </Button>
        </form>
      )}
      {state === "thanked" && <p className="text-right text-sm text-muted-foreground">已收到，谢谢反馈</p>}
      {state === "failed" && (
        <p role="alert" className="text-right text-sm text-destructive">
          反馈没有提交成功，请稍后重试。
        </p>
      )}
    </div>
  );
}
