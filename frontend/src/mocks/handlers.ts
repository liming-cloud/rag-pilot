import { http, HttpResponse } from "msw";
import type { KnowledgeBase } from "@/features/knowledge/api";

/** 假接口与后端契约保持一致；示例数据只用于原型和测试。 */
export const sampleKnowledgeBases: KnowledgeBase[] = [
  { id: 1, name: "门店运营知识库" },
  { id: 2, name: "商品知识库" },
];

export const handlers = [http.get("/api/knowledge-bases", () => HttpResponse.json(sampleKnowledgeBases))];
