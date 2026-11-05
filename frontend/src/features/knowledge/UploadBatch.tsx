import { Button } from "@/components/ui/button";
import { type BatchItem, type Outcome, tally } from "./batchUpload";

const LABEL: Partial<Record<Outcome, string>> = {
  duplicate: "重复",
  unsupported: "不支持",
  failed: "失败",
};

/**
 * 一批上传的进度和结果汇总：成功、重复、不支持、失败各多少；没成功的逐个列出原因，失败的可以只重试这些。
 * 成功的不逐个列出（下方文档列表里能看到），免得几百个文件把页面撑长。
 */
export function UploadBatch({
  items,
  onRetry,
  onClose,
}: {
  items: BatchItem[];
  onRetry: () => void;
  onClose: () => void;
}) {
  const counts = tally(items);
  const running = counts.done < counts.total;
  const problems = items.filter((item) => ["duplicate", "unsupported", "failed"].includes(item.outcome));
  return (
    <section aria-label="本批上传" className="flex flex-col gap-2 rounded-xl border bg-card shadow-sm p-3 text-sm">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-medium">
          {running ? `正在上传 ${counts.done} / ${counts.total}` : `本批 ${counts.total} 个文件已处理完`}
        </span>
        <span className="text-success">成功 {counts.success}</span>
        <span className="text-muted-foreground">重复 {counts.duplicate}</span>
        <span className="text-muted-foreground">不支持 {counts.unsupported}</span>
        <span className={counts.failed > 0 ? "text-destructive" : "text-muted-foreground"}>失败 {counts.failed}</span>
        <span className="ml-auto flex gap-1">
          {!running && counts.failed > 0 && (
            <Button size="sm" variant="outline" onClick={onRetry}>
              重试失败的 {counts.failed} 个
            </Button>
          )}
          {!running && (
            <Button size="sm" variant="ghost" onClick={onClose}>
              关闭
            </Button>
          )}
        </span>
      </div>
      {problems.length > 0 && (
        <ul aria-label="未成功的文件" className="flex max-h-48 flex-col gap-1 overflow-y-auto">
          {problems.map((item) => (
            <li
              key={item.key}
              className={item.outcome === "failed" ? "text-destructive" : "text-muted-foreground"}
              role={item.outcome === "failed" ? "alert" : undefined}
            >
              <span className="mr-1 inline-block min-w-12">{LABEL[item.outcome]}</span>
              <span className="break-all">{item.file.name}</span>
              {item.message && <span>：{item.message}</span>}
            </li>
          ))}
        </ul>
      )}
      {!running && counts.success > 0 && (
        <p className="text-xs text-muted-foreground">成功上传的文件在后台解析，解析结果见下方列表的状态。</p>
      )}
    </section>
  );
}
