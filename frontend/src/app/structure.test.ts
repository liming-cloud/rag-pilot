import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

// 前端目录规范见 AGENTS.md“目录结构”：feature 之间只能引用对方的 api.ts，feature 不引用 app。
const src = path.resolve(import.meta.dirname, "..");
const ALLOWED_TOP = ["app", "components", "features", "lib", "mocks", "styles", "test", "index.css", "main.tsx", "vite-env.d.ts"];

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

describe("前端目录结构", () => {
  it("src 下只有约定的目录，components 下只放 shadcn 的 ui", () => {
    expect(readdirSync(src).filter((name) => !ALLOWED_TOP.includes(name))).toEqual([]);
    expect(readdirSync(path.join(src, "components"))).toEqual(["ui"]);
  });

  it("feature 之间只引用对方的 api.ts，不引用 app", () => {
    const problems = walk(path.join(src, "features"))
      .filter((file) => /\.tsx?$/.test(file))
      .flatMap((file) => importProblems(path.relative(src, file).split(path.sep).join("/"), readFileSync(file, "utf8")));
    expect(problems).toEqual([]);
  });

  it("违规的引用会被指出", () => {
    expect(importProblems("features/answers/AnswersPage.tsx", 'import { fetchUsers } from "@/features/users/api";')).toEqual([]);
    expect(importProblems("features/answers/AnswersPage.tsx", 'import { UsersPage } from "@/features/users/UsersPage";')).toHaveLength(1);
    expect(importProblems("features/answers/AnswersPage.tsx", 'import { FeedbackBar } from "../chat/FeedbackBar";')).toHaveLength(1);
    expect(importProblems("features/chat/ChatPage.tsx", 'import { AnswerCard } from "./AnswerCard";')).toEqual([]);
    expect(importProblems("features/chat/ChatPage.tsx", 'import { AppShell } from "@/app/AppShell";')).toHaveLength(1);
  });
});
