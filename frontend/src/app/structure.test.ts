import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

// 前端目录规范见 AGENTS.md“目录结构”：feature 之间只能引用对方的 api.ts，feature 不引用 app。
const src = path.resolve(import.meta.dirname, "..");
const ALLOWED_TOP = [
  "app",
  "components",
  "features",
  "lib",
  "mocks",
  "styles",
  "test",
  "index.css",
  "main.tsx",
  "vite-env.d.ts",
];

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}

/** 一个 feature 文件里违规的 import；file 是相对 src 的路径。 */
export function importProblems(file: string, source: string): string[] {
  const match = /^features\/([^/]+)\//.exec(file);
  if (!match) return [];
  const own = match[1];
  const dir = path.posix.dirname(file);
  const problems: string[] = [];
  for (const [, spec] of source.matchAll(/from\s+["']([^"']+)["']/g)) {
    if (!spec) continue;
    const target = spec.startsWith("@/") ? spec.slice(2) : spec.startsWith(".") ? path.posix.join(dir, spec) : null;
    if (!target) continue;
    if (target === "app" || target.startsWith("app/")) {
      problems.push(`${file} 引用了 ${spec}：feature 不引用 app`);
    }
    const other = /^features\/([^/]+)\/(.+)$/.exec(target);
    if (other && other[1] !== own && other[2] !== "api") {
      problems.push(`${file} 引用了 ${spec}：其他 feature 只能引用它的 api.ts`);
    }
  }
  return problems;
}

/** feature 里直接写的原生表单、表格元素；这些要用 components/ui 里的 shadcn 组件（AGENTS.md：界面只用 shadcn/ui）。 */
export function nativeElementProblems(file: string, source: string): string[] {
  if (!file.startsWith("features/") || /\.test\.tsx?$/.test(file)) return [];
  return [...source.matchAll(/<(input|select|textarea|table|label|option)[\s>]/g)].map(
    (match) => `${file} 直接用了 <${match[1]}>：改用 components/ui 里的组件`,
  );
}

/** 写死的产品名称（D-042）：名称由部署配置，默认值只在 lib/branding.ts。测试和假接口里可以出现。 */
const PRODUCT_NAMES = ["知枢", "门店运营知识库", "门店助手"];

export function productNameProblems(file: string, source: string): string[] {
  if (file === "lib/branding.ts" || file.startsWith("mocks/") || /\.test\.tsx?$/.test(file)) return [];
  return PRODUCT_NAMES.filter((name) => source.includes(name)).map(
    (name) => `${file} 写死了产品名称“${name}”：改用 useBranding()`,
  );
}

describe("前端目录结构", () => {
  it("src 下只有约定的目录，components 下只放 shadcn 的 ui", () => {
    expect(readdirSync(src).filter((name) => !ALLOWED_TOP.includes(name))).toEqual([]);
    expect(readdirSync(path.join(src, "components"))).toEqual(["ui"]);
  });

  it("feature 之间只引用对方的 api.ts，不引用 app", () => {
    const problems = walk(path.join(src, "features"))
      .filter((file) => /\.tsx?$/.test(file))
      .flatMap((file) =>
        importProblems(path.relative(src, file).split(path.sep).join("/"), readFileSync(file, "utf8")),
      );
    expect(problems).toEqual([]);
  });

  it("feature 里不直接写原生的表单和表格元素", () => {
    const problems = walk(path.join(src, "features"))
      .filter((file) => /\.tsx$/.test(file))
      .flatMap((file) =>
        nativeElementProblems(path.relative(src, file).split(path.sep).join("/"), readFileSync(file, "utf8")),
      );
    expect(problems).toEqual([]);
  });

  it("源码里不写死产品名称，名称从部署配置读取", () => {
    const problems = walk(src)
      .filter((file) => /\.tsx?$/.test(file))
      .flatMap((file) =>
        productNameProblems(path.relative(src, file).split(path.sep).join("/"), readFileSync(file, "utf8")),
      );
    expect(problems).toEqual([]);
  });

  it("写死产品名称会被指出；默认值、测试和假接口不算", () => {
    expect(productNameProblems("app/AppShell.tsx", "<span>门店运营知识库</span>")).toHaveLength(1);
    expect(productNameProblems("features/auth/LoginPage.tsx", "<h1>知枢</h1>")).toHaveLength(1);
    expect(productNameProblems("lib/branding.ts", 'name: "知枢"')).toEqual([]);
    expect(productNameProblems("features/auth/LoginPage.test.tsx", "知枢")).toEqual([]);
    expect(productNameProblems("mocks/identity.ts", "门店运营知识库")).toEqual([]);
  });

  it("直接写原生元素会被指出，用组件不会", () => {
    expect(nativeElementProblems("features/users/UsersPage.tsx", '<input className="h-9" />')).toHaveLength(1);
    expect(nativeElementProblems("features/stats/StatsPage.tsx", "<table>\n<label>")).toHaveLength(2);
    expect(nativeElementProblems("features/users/UsersPage.tsx", "<Input required />")).toEqual([]);
    expect(nativeElementProblems("features/users/UsersPage.test.tsx", "<input />")).toEqual([]);
  });

  it("违规的引用会被指出", () => {
    expect(
      importProblems("features/answers/AnswersPage.tsx", 'import { fetchUsers } from "@/features/users/api";'),
    ).toEqual([]);
    expect(
      importProblems("features/answers/AnswersPage.tsx", 'import { UsersPage } from "@/features/users/UsersPage";'),
    ).toHaveLength(1);
    expect(
      importProblems("features/answers/AnswersPage.tsx", 'import { FeedbackBar } from "../chat/FeedbackBar";'),
    ).toHaveLength(1);
    expect(importProblems("features/chat/ChatPage.tsx", 'import { AnswerCard } from "./AnswerCard";')).toEqual([]);
    expect(importProblems("features/chat/ChatPage.tsx", 'import { AppShell } from "@/app/AppShell";')).toHaveLength(1);
  });
});
