/** 导入 CSV 的通用读取：账号导入（#124）和标准题导入（#224）共用。 */

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

/** 用 FileReader 读字节：比 file.arrayBuffer() 兼容性好，测试环境（jsdom）也支持。 */
export function readBytes(file: Blob): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error ?? new Error("读取文件失败"));
    reader.readAsArrayBuffer(file);
  });
}
