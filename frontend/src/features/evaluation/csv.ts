import { parseCsv } from "@/lib/csv";
import type { ImportRow, MatchMode } from "./api";

/** 标准题模板的表头，顺序固定。 */
export const QUESTION_HEADER = ["问题", "知识库", "期望文档", "命中方式"] as const;
/** 一次最多导入的行数，与后端一致。 */
export const MAX_QUESTION_ROWS = 500;

/** 带 BOM 的 UTF-8 模板，Excel 直接打开中文不乱码。 */
export function questionTemplate(): Blob {
  return new Blob([`\uFEFF${QUESTION_HEADER.join(",")}\r\n`], { type: "text/csv;charset=utf-8" });
}

const MATCH: Record<string, MatchMode> = { "": "ALL", 全部: "ALL", 任一: "ANY" };

/** 把文件读成行：表头必须与模板一致，跳过空行，行号按文件里的行（表头是第 1 行）。名称匹配由后端做。 */
export function toQuestionRows(text: string): { rows: ImportRow[] } | { error: string } {
  const [header, ...lines] = parseCsv(text);
  if (!header || QUESTION_HEADER.some((name, index) => header[index]?.trim() !== name)) {
    return { error: `表头必须是“${QUESTION_HEADER.join("，")}”，请下载模板填写` };
  }
  const rows: ImportRow[] = [];
  for (const [index, cells] of lines.entries()) {
    if (cells.every((cell) => cell.trim() === "")) continue;
    const line = index + 2;
    const match = MATCH[cells[3]?.trim() ?? ""];
    if (!cells[0]?.trim() || !cells[1]?.trim()) return { error: `第 ${line} 行：问题和知识库都要填` };
    if (!match) return { error: `第 ${line} 行：命中方式只能填“全部”或“任一”` };
    const documents = (cells[2] ?? "")
      .split("|")
      .map((name) => name.trim())
      .filter(Boolean);
    rows.push({ line, question: cells[0].trim(), knowledgeBase: cells[1].trim(), documents, match });
  }
  if (rows.length === 0) return { error: "文件里没有要导入的题" };
  if (rows.length > MAX_QUESTION_ROWS) return { error: `一次最多导入 ${MAX_QUESTION_ROWS} 道题，请分批导入` };
  return { rows };
}
