import { decode, parseCsv, toRows } from "./csv";

describe("账号导入文件", () => {
  it("解析带引号的字段：引号里的逗号、换行和两个双引号", () => {
    expect(parseCsv('a,"b,c","d""e"\r\n"多\n行",x,\n')).toEqual([
      ["a", "b,c", 'd"e'],
      ["多\n行", "x", ""],
    ]);
  });

  it("按模板读成行：跳过空行，行号按文件里的行，多个角色用分号分隔", () => {
    expect(toRows("用户名,显示名,角色\r\nwang,小王,员工\r\n,,\r\nli, 小李 ,员工;店长；管理员\r\n")).toEqual({
      rows: [
        { line: 2, username: "wang", displayName: "小王", roleNames: ["员工"] },
        { line: 4, username: "li", displayName: "小李", roleNames: ["员工", "店长", "管理员"] },
      ],
    });
  });

  it("表头不对、没有数据行、超过 500 行时给出原因", () => {
    expect(toRows("账号,名字\nwang,小王")).toHaveProperty("error", expect.stringContaining("表头必须是"));
    expect(toRows("用户名,显示名,角色\n")).toEqual({ error: "文件里没有要导入的账号" });
    const many = ["用户名,显示名,角色", ...Array.from({ length: 501 }, (_, i) => `u${i},用户${i},员工`)].join("\n");
    expect(toRows(many)).toHaveProperty("error", expect.stringContaining("这个文件有 501 个"));
  });

  it("UTF-8（带或不带 BOM）和 Excel 存的 GBK 都能读出中文", () => {
    const utf8 = new TextEncoder().encode("\uFEFF用户名,显示名,角色");
    expect(decode(utf8.buffer)).toBe("用户名,显示名,角色");
    // “用户名”的 GBK 编码。
    const gbk = new Uint8Array([0xd3, 0xc3, 0xbb, 0xa7, 0xc3, 0xfb]);
    expect(decode(gbk.buffer)).toBe("用户名");
  });
});
