import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { PERMISSIONS } from "@/lib/permissions";

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
const PRODUCT_NAMES = ["明答", "门店运营知识库", "门店助手"];

/** 只认独立出现的名称：前后挨着汉字的不算，比如“说明答案”里的“明答”。 */
const standalone = (name: string) => new RegExp(`(?<![\\u4e00-\\u9fff])${name}(?![\\u4e00-\\u9fff])`);

export function productNameProblems(file: string, source: string): string[] {
  if (file === "lib/branding.ts" || file.startsWith("mocks/") || /\.test\.tsx?$/.test(file)) return [];
  return PRODUCT_NAMES.filter((name) => standalone(name).test(source)).map(
    (name) => `${file} 写死了产品名称“${name}”：改用 useBranding()`,
  );
}

/**
 * 通用内核 + 行业包（D-045）：行业内容只写在后端的行业包里，前端非测试、非 mocks/ 的代码（含注释）里不出现任何行业包的
 * 行业标志词和词表里的词。后端主代码由 KernelNeutralityTest 用同一份行业包检查。
 */
const PACKS = path.resolve(src, "../../backend/contexts/rag-settings/src/main/resources/industry-packs");

/** 从 pack.yml 里取出行业标志词和词表；这些列表在行业包里用 [a, b] 的写法。 */
export function industryWordsOf(yaml: string): string[] {
  const words: string[] = [];
  for (const [, list] of yaml.matchAll(/^\s*(?:markers|members):\s*\[([^\]]*)\]/gm)) {
    words.push(...(list ?? "").split(",").map((word) => word.trim()));
  }
  for (const [, term] of yaml.matchAll(/^\s*-\s*term:\s*(\S+)/gm)) {
    words.push(term ?? "");
  }
  return words.filter(Boolean);
}

export function industryWordProblems(file: string, source: string, words: string[]): string[] {
  if (file.startsWith("mocks/") || /\.test\.tsx?$/.test(file)) return [];
  return words
    .filter((word) => source.includes(word))
    .map((word) => `${file} 出现了行业词“${word}”：行业内容放进行业包，界面从接口读取`);
}

const industryWords = () =>
  readdirSync(PACKS).flatMap((pack) => industryWordsOf(readFileSync(path.join(PACKS, pack, "pack.yml"), "utf8")));

/** 手写的权限码（#145 C7）：界面判断权限只用 lib/permissions.tsx 的 PERMISSIONS 常量。测试和假接口里可以出现。 */
export function permissionLiteralProblems(file: string, source: string, codes: string[]): string[] {
  if (file === "lib/permissions.tsx" || file.startsWith("mocks/") || /\.test\.tsx?$/.test(file)) return [];
  return codes
    .filter((code) => source.includes(`"${code}"`) || source.includes(`'${code}'`))
    .map((code) => `${file} 手写了权限码“${code}”：改用 PERMISSIONS 常量`);
}

/** 查询键写在各 feature 的 api.ts 里（#145），页面和组件引用常量，不现场写数组。测试可以直接写。 */
export function queryKeyLiteralProblems(file: string, source: string): string[] {
  if (/(^|\/)api\.ts$/.test(file) || /\.test\.tsx?$/.test(file)) return [];
  return /queryKey:\s*\[/.test(source) ? [`${file} 现场写了查询键：改用 api.ts 里的 xxxKeys 常量`] : [];
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

  it("界面判断权限只用 PERMISSIONS 常量，不手写权限码", () => {
    const codes = Object.values(PERMISSIONS);
    const problems = walk(src)
      .filter((file) => /\.tsx?$/.test(file))
      .flatMap((file) =>
        permissionLiteralProblems(path.relative(src, file).split(path.sep).join("/"), readFileSync(file, "utf8"), codes),
      );
    expect(problems).toEqual([]);
    expect(permissionLiteralProblems("features/x/XPage.tsx", '<Can permission="kb:view">', codes)).toHaveLength(1);
    expect(permissionLiteralProblems("mocks/identity.ts", '"kb:view"', codes)).toEqual([]);
  });

  it("查询键只在各 api.ts 里定义", () => {
    const problems = walk(src)
      .filter((file) => /\.tsx?$/.test(file))
      .flatMap((file) =>
        queryKeyLiteralProblems(path.relative(src, file).split(path.sep).join("/"), readFileSync(file, "utf8")),
      );
    expect(problems).toEqual([]);
    expect(queryKeyLiteralProblems("features/x/XPage.tsx", 'useQuery({ queryKey: ["roles"] })')).toHaveLength(1);
    expect(queryKeyLiteralProblems("features/x/api.ts", 'queryKey: ["roles"]')).toEqual([]);
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
    expect(productNameProblems("features/auth/LoginPage.tsx", "<h1>明答</h1>")).toHaveLength(1);
    expect(productNameProblems("lib/branding.ts", 'name: "明答"')).toEqual([]);
    expect(productNameProblems("features/chat/ChatPage.tsx", "<p>请说明答案的出处</p>")).toEqual([]);
    expect(productNameProblems("features/auth/LoginPage.test.tsx", "明答")).toEqual([]);
    expect(productNameProblems("mocks/identity.ts", "门店运营知识库")).toEqual([]);
  });

  it("非测试、非假接口的代码里没有行业词（D-045）", () => {
    const words = industryWords();
    expect(words).toEqual(expect.arrayContaining(["门店", "鲜食", "便当"]));
    const problems = walk(src)
      .filter((file) => /\.tsx?$/.test(file))
      .flatMap((file) =>
        industryWordProblems(path.relative(src, file).split(path.sep).join("/"), readFileSync(file, "utf8"), words),
      );
    expect(problems).toEqual([]);
  });

  it("写了行业词会被指出；测试和假接口不算；行业包里的标志词、上位词、下位词都会读到", () => {
    expect(industryWordProblems("features/chat/ChatPage.tsx", "问问门店规范…", ["门店"])).toHaveLength(1);
    expect(industryWordProblems("features/chat/ChatPage.tsx", "输入问题…", ["门店"])).toEqual([]);
    expect(industryWordProblems("features/chat/ChatPage.test.tsx", "门店", ["门店"])).toEqual([]);
    expect(industryWordProblems("mocks/settings.ts", "门店", ["门店"])).toEqual([]);
    expect(industryWordsOf("markers: [甲, 乙]\nvocabulary:\n  - term: 丙\n    members: [丁, 戊]\n")).toEqual([
      "甲",
      "乙",
      "丁",
      "戊",
      "丙",
    ]);
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
