import type { ImportRow } from "./api";

/** 模板的表头，顺序固定；角色多个时用英文分号分隔，按角色名匹配（#124）。 */
export const HEADER = ["用户名", "显示名", "角色"] as const;
/** 一次最多导入的行数，与后端一致。 */
export const MAX_ROWS = 500;

/** 带 BOM 的 UTF-8 模板，Excel 直接打开中文不乱码；只有表头，不放示例行，免得示例账号被误导入。 */
export function template(): Blob {
  return new Blob([`\uFEFF${HEADER.join(",")}\r\n`], { type: "text/csv;charset=utf-8" });
}

/** 先按 UTF-8 解码；不是合法 UTF-8 时按 GBK 解码（Excel 默认“CSV（逗号分隔）”在中文系统里存成 GBK）。 */
export function decode(bytes: ArrayBuffer): string {
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    text = new TextDecoder("gbk").decode(bytes);
  }
  return text.replace(/^\uFEFF/, "");
}

/** RFC 4180 的 CSV：字段可以用双引号包起来，引号里可以有逗号、换行，两个双引号表示一个。 */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      row.push(field);
      field = "";
    } else if (char === "\n" || char === "\r") {
      if (char === "\r" && text[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else {
      field += char;
    }
  }
  if (field !== "" || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

export type Parsed = { rows: ImportRow[] } | { error: string };

/**
 * 把导入文件读成行：表头必须与模板一致，跳过空行，行号按文件里的行（表头是第 1 行）。
 * 只检查文件格式；用户名重复、已存在、角色不存在或超出权限由后端预检逐行给出。
 */
export function toRows(text: string): Parsed {
  const [header, ...lines] = parseCsv(text);
  if (!header || HEADER.some((name, index) => header[index]?.trim() !== name)) {
    return { error: `表头必须是“${HEADER.join("，")}”，请下载模板填写` };
  }
  const rows = lines
    .map((cells, index) => ({ cells, line: index + 2 }))
    .filter(({ cells }) => cells.some((cell) => cell.trim() !== ""))
    .map(({ cells, line }) => ({
      line,
      username: cells[0]?.trim() ?? "",
      displayName: cells[1]?.trim() ?? "",
      roleNames: (cells[2] ?? "")
        .split(/[;；]/)
        .map((name) => name.trim())
        .filter(Boolean),
    }));
  if (rows.length === 0) return { error: "文件里没有要导入的账号" };
  if (rows.length > MAX_ROWS)
    return { error: `一次最多导入 ${MAX_ROWS} 个账号，这个文件有 ${rows.length} 个，请分批导入` };
  return { rows };
}

/** 用 FileReader 读字节：比 file.arrayBuffer() 兼容性好，测试环境（jsdom）也支持。 */
export function readBytes(file: Blob): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error ?? new Error("读取文件失败"));
    reader.readAsArrayBuffer(file);
  });
}
