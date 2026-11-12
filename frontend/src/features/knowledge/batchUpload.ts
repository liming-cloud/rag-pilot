import { ApiError } from "@/lib/api";

/** 支持的格式，与后端一致；选文件夹时不支持的文件在前端直接归为“不支持”，不发请求。 */
export const ACCEPT = ".pdf,.docx,.xlsx,.xls,.pptx,.txt,.md,.markdown";
const SUPPORTED = /\.(pdf|docx|xlsx|xls|pptx|txt|md|markdown)$/i;
/** 单个文件上限，与后端一致；超限的请求会先被 Nginx 或 multipart 拦下，后端的提示到不了页面，所以在前端先判断。 */
const MAX_FILE_BYTES = 50 * 1024 * 1024;

/** 同时上传的文件数：后端每个文件都要调嵌入模型，并发太多容易被服务商限流（#124）。 */
export const CONCURRENCY = 3;
/** 一批最多多少个文件，超出时整批不传，请分批选择。 */
export const MAX_BATCH = 200;

export type Outcome = "pending" | "uploading" | "success" | "duplicate" | "unsupported" | "failed";

export type BatchItem = { key: string; file: File; outcome: Outcome; message: string | null };

export function isSupported(file: File) {
  return SUPPORTED.test(file.name);
}

/** 系统和编辑器自动生成的文件（.DS_Store、Thumbs.db、Office 的 ~$ 临时文件）选文件夹时常带上，直接跳过、不计数。 */
function isSystemFile(file: File) {
  return /^(\.|~\$)|^(thumbs\.db|desktop\.ini)$/i.test(file.name);
}

function unsupportedReason(file: File) {
  if (/\.doc$/i.test(file.name)) return "旧版 .doc 请先另存为 .docx";
  if (/\.ppt$/i.test(file.name)) return "旧版 .ppt 请先另存为 .pptx";
  if (isSupported(file)) return "单个文件不能超过 50 MB；课件可先压缩图片或另存为 PDF";
  return "格式不支持";
}

/** 新的一批：跳过系统文件，不支持的格式和超过上限的文件直接标出来，其余等待上传。 */
export function prepare(files: File[], seed = Date.now()): BatchItem[] {
  return files
    .filter((file) => !isSystemFile(file))
    .map((file, index) =>
      isSupported(file) && file.size <= MAX_FILE_BYTES
        ? { key: `${seed}-${index}`, file, outcome: "pending", message: null }
        : { key: `${seed}-${index}`, file, outcome: "unsupported", message: unsupportedReason(file) },
    );
}

/** 后端的错误码决定归类：已在库里的算“重复”，格式不对的算“不支持”，其余算“失败”，可以重试。 */
export function classify(error: unknown): { outcome: Outcome; message: string } {
  const message = error instanceof Error ? error.message : "上传失败";
  if (error instanceof ApiError && error.code === "KNOWLEDGE_CONFLICT") {
    return { outcome: "duplicate", message: "知识库里已有这份文件" };
  }
  if (error instanceof ApiError && error.code === "KNOWLEDGE_UNSUPPORTED_TYPE") {
    return { outcome: "unsupported", message };
  }
  return { outcome: "failed", message };
}

/**
 * 按并发上限上传处于 pending 的文件，每个文件结束时回调一次；全部结束后返回。
 * 一个文件失败不影响其他文件。
 */
export async function run(
  items: BatchItem[],
  upload: (file: File) => Promise<unknown>,
  onChange: (key: string, change: Partial<BatchItem>) => void,
  concurrency = CONCURRENCY,
) {
  const queue = items.filter((item) => item.outcome === "pending");
  async function worker() {
    for (let item = queue.shift(); item; item = queue.shift()) {
      onChange(item.key, { outcome: "uploading" });
      try {
        await upload(item.file);
        onChange(item.key, { outcome: "success", message: null });
      } catch (e) {
        onChange(item.key, classify(e));
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, queue.length) }, worker));
}

export function tally(items: BatchItem[]) {
  const count = (outcome: Outcome) => items.filter((item) => item.outcome === outcome).length;
  return {
    total: items.length,
    done: items.filter((item) => item.outcome !== "pending" && item.outcome !== "uploading").length,
    success: count("success"),
    duplicate: count("duplicate"),
    unsupported: count("unsupported"),
    failed: count("failed"),
  };
}
