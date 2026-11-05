import { http, HttpResponse } from "msw";
import type { GapGroup, GapRecord, GapReport } from "@/features/gaps/api";
import { forbidden } from "./identity";

/**
 * 知识缺口的假接口（#125）。后端按向量相似度归类，这里用预先标好的“话题”模拟：同一话题的不同问法归成一类。
 * 记录时间相对现在生成，原型里随时都能看到近 7 / 30 / 90 天的差别；数量足够看到分页和长问题折行。
 */
type StoredRecord = GapRecord & { topic: string };

const TOPICS: { topic: string; phrasings: string[]; document: string; knowledgeBase?: string; disliked?: string }[] = [
  {
    topic: "营业时间",
    phrasings: ["门店几点打烊？", "晚上几点关门", "营业到几点", "周末营业时间有变化吗"],
    document: "门店营业规范.md",
  },
  {
    topic: "餐补",
    phrasings: ["员工餐补怎么报销？", "餐补报销流程是什么", "餐费补贴每月多少"],
    document: "员工手册.md",
  },
  {
    topic: "冷柜报修",
    phrasings: ["冷柜坏了找谁修？", "冷柜不制冷联系谁", "设备报修电话是多少"],
    document: "设备维护手册.md",
    disliked: "给的电话是旧的",
  },
  {
    topic: "生日优惠",
    phrasings: ["会员生日当天有什么优惠？", "会员生日有礼品吗"],
    document: "会员与促销.md",
    knowledgeBase: "商品知识库",
  },
  {
    topic: "临期折扣",
    phrasings: ["临期商品打几折？"],
    document: "临期与报损管理.md",
    disliked: "门店规定是五折",
  },
  {
    topic: "长问题",
    phrasings: [
      "如果顾客在会员日用了满减券又想用积分抵扣，同时购买的商品里有一部分是临期打折的，这种情况下收银应该先算哪一个优惠，最后的价格怎么算才对？",
    ],
    document: "会员与促销.md",
    knowledgeBase: "商品知识库",
  },
  // 只出现一两次的零散问题，原型里能看到长尾和分页。
  ...Array.from({ length: 26 }, (_, index) => ({
    topic: `零散-${index}`,
    phrasings: [`第 ${index + 1} 个零散问题：区域 ${(index % 5) + 1} 的特殊规定是什么？`],
    document: `区域 ${(index % 5) + 1} 门店规范.md`,
  })),
];

let records: StoredRecord[] = [];

export function resetGaps() {
  const now = Date.now();
  let id = 9000;
  records = TOPICS.flatMap(({ topic, phrasings, document, knowledgeBase, disliked }, topicIndex) => {
    // 前几个话题问得多，越往后越少。
    const times = Math.max(1, 12 - topicIndex * 2);
    return Array.from({ length: times }, (_, index): StoredRecord => {
      const daysAgo = (index * 9 + topicIndex * 3) % 80;
      const isDisliked = Boolean(disliked) && index % 3 === 0;
      return {
        id: id++,
        topic,
        // 一半用第一种问法，它就是代表问题；另一半轮流用其他问法。
        question:
          index % 2 === 0 || phrasings.length === 1
            ? phrasings[0]!
            : phrasings[1 + ((index >> 1) % (phrasings.length - 1))]!,
        askerName: `门店店员 ${((index + topicIndex) % 12) + 1}`,
        createdAt: new Date(now - daysAgo * 86_400_000 - index * 3_600_000).toISOString(),
        refused: !isDisliked,
        comment: isDisliked ? (disliked ?? null) : null,
        // 员工没点名知识库时检索全部可访问的库；有一部分点名了一个库。
        knowledgeBases:
          index % 3 === 1 ? [knowledgeBase ?? "门店运营知识库"] : ["门店运营知识库", "商品知识库", "区域 1 门店规范"],
        // 只有差评有当时引用的文档，拒答没有引用。
        citedDocuments: isDisliked ? [document] : [],
        // 营业时间这一类有一部分已经处理过。
        reviewStatus: topic === "营业时间" && index >= 8 ? "RESOLVED" : "OPEN",
      };
    });
  });
}
resetGaps();

/** 示例知识库的 id（见 handlers.ts 的 sampleKnowledgeBases），缺口详情里的“去补资料”用。 */
const KNOWLEDGE_BASE_IDS: Record<string, number> = { 门店运营知识库: 1, 商品知识库: 2, "区域 1 门店规范": 3 };

/** 去重，出现多的在前（与后端一致）。 */
function byFrequency(names: string[]) {
  const counts = new Map<string, number>();
  for (const name of names) counts.set(name, (counts.get(name) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([name]) => name);
}

function withoutTopic(record: StoredRecord): GapRecord {
  const copy: Partial<StoredRecord> = { ...record };
  delete copy.topic;
  return copy as GapRecord;
}

function day(offset: number) {
  return new Date(Date.now() - offset * 86_400_000).toISOString().slice(0, 10);
}

function report(days: number, status: string): GapReport {
  const since = Date.now() - days * 86_400_000;
  const inRange = records.filter((record) => new Date(record.createdAt).getTime() >= since);
  const byTopic = new Map<string, StoredRecord[]>();
  for (const record of inRange) byTopic.set(record.topic, [...(byTopic.get(record.topic) ?? []), record]);
  const groups = [...byTopic.values()]
    .map((list): GapGroup => {
      const sorted = [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      const counts = new Map<string, number>();
      for (const record of list) counts.set(record.question, (counts.get(record.question) ?? 0) + 1);
      const representative = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]![0];
      return {
        id: list.find((record) => record.question === representative)!.id,
        question: representative,
        count: list.length,
        openCount: list.filter((record) => record.reviewStatus === "OPEN").length,
        refusedCount: list.filter((record) => record.refused).length,
        dislikedCount: list.filter((record) => !record.refused).length,
        phrasings: counts.size,
        lastAskedAt: sorted[0]!.createdAt,
        knowledgeBases: byFrequency(list.flatMap((record) => record.knowledgeBases)),
        // 带 id，缺口详情里能直接打开这个库补资料（#145 A10）。
        knowledgeBaseRefs: byFrequency(list.flatMap((record) => record.knowledgeBases))
          .filter((name) => name in KNOWLEDGE_BASE_IDS)
          .map((name) => ({ id: KNOWLEDGE_BASE_IDS[name]!, name })),
        citedDocuments: [...new Set(list.flatMap((record) => record.citedDocuments))],
        records: sorted.map(withoutTopic),
      };
    })
    .filter((group) => status === "all" || group.openCount > 0)
    .sort((a, b) => b.openCount - a.openCount || b.count - a.count);
  return {
    from: day(days - 1),
    to: day(0),
    zone: "Asia/Shanghai",
    grouping: "SEMANTIC",
    records: inRange.length,
    truncated: false,
    groups,
  };
}

/** 运营概览用：前几类缺口的代表问题和待处理次数。 */
export function topGaps(days: number, limit: number) {
  return report(days, "open")
    .groups.slice(0, limit)
    .map((group) => ({ question: group.question, count: group.openCount }));
}

export const gapHandlers = [
  http.get("/api/answers/gaps", ({ request }) => {
    const denied = forbidden("answer:view");
    if (denied) return denied;
    const url = new URL(request.url);
    return HttpResponse.json(
      report(Number(url.searchParams.get("days")) || 30, url.searchParams.get("status") ?? "open"),
    );
  }),
  http.post("/api/answers/gaps/resolve", async ({ request }) => {
    const denied = forbidden("answer:review");
    if (denied) return denied;
    const { recordIds } = (await request.json()) as { recordIds: number[]; note?: string };
    for (const record of records) {
      if (recordIds.includes(record.id)) record.reviewStatus = "RESOLVED";
    }
    return new HttpResponse(null, { status: 204 });
  }),
];
