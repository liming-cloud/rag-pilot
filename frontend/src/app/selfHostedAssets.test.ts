import { readFileSync } from "node:fs";
import path from "node:path";

// 客户内网可能访问不了外网（D-011）：页面不能依赖任何外部地址上的字体、样式或脚本。
describe("页面资源全部自托管", () => {
  const root = path.resolve(import.meta.dirname, "../..");

  it("index.html 不引用外部地址", () => {
    const html = readFileSync(path.join(root, "index.html"), "utf8");
    expect(html).not.toMatch(/(?:href|src)="(?:https?:)?\/\//);
  });

  it("字体随前端打包", () => {
    const main = readFileSync(path.join(root, "src/main.tsx"), "utf8");
    expect(main).toContain("@fontsource/noto-sans-sc");
    expect(main).toContain("@fontsource/noto-serif-sc");
  });
});
