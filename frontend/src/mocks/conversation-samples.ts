import type { Answer } from "@/features/chat/api";

/** 员工端会话假接口用到的示例回答（#107、#182）；按问题里的关键词挑一个，见 conversations.ts 的 answerFor。 */
export const answerAboutExpiry: Answer = {
  id: 701,
  text: "保质期剩余三分之一时贴黄色临期标签并移到临期专区 [1]；剩余 1 天的商品当天闭店前下架，登记报损后由店长确认 [2]。",
  refused: false,
  refusalReason: null,
  citations: [
    {
      index: 1,
      chunkId: 101,
      documentId: 11,
      documentName: "临期与报损管理.md",
      headingPath: "临期商品 > 下架时限",
      excerpt: "保质期剩余不足三分之一的商品，贴黄色临期标签，集中陈列到临期专区，按原价七折销售。",
      documentUpdatedAt: "2026-10-26T09:00:00+08:00",
      documentState: "CURRENT",
    },
    {
      index: 2,
      chunkId: 102,
      documentId: 11,
      documentName: "临期与报损管理.md",
      headingPath: "报损 > 登记与确认",
      excerpt: "保质期仅剩 1 天的商品，当天闭店前全部下架，在报损登记表写明品名、数量和原因，店长签字确认。",
      documentUpdatedAt: "2026-10-26T09:00:00+08:00",
      documentState: "EXPIRED",
    },
  ],
  dataCitations: [],
  topRelevance: 0.97,
};

export const answerAboutFrozen: Answer = {
  id: 702,
  text: "冷冻柜温度要保持在 -18℃ 以下 [1]，每天开店和闭店各记录一次 [1]。",
  refused: false,
  refusalReason: null,
  citations: [
    {
      index: 1,
      chunkId: 103,
      documentId: 13,
      documentName: "商品陈列与补货规范.md",
      headingPath: "冷链 > 冷冻柜",
      excerpt: "冷冻柜温度保持在 -18℃ 以下，开店、闭店各记录一次温度，异常时立即报告店长。",
      documentUpdatedAt: "2026-10-26T09:00:00+08:00",
      documentState: "REPLACED",
    },
  ],
  dataCitations: [],
  topRelevance: 0.93,
};

export const refusal: Answer = {
  id: 703,
  text: "知识库里没有找到能回答这个问题的可靠依据，建议换个问法，或联系店长确认。",
  refused: true,
  refusalReason: "LOW_RELEVANCE",
  citations: [],
  dataCitations: [],
  topRelevance: 0.04,
};

/** 寒暄：模型判断不需要检索，只回没有事实内容的话（D-036）。 */
export const smallTalk: Answer = {
  id: 704,
  text: "不客气，还有问题随时问我。",
  refused: false,
  refusalReason: null,
  citations: [],
  dataCitations: [],
  topRelevance: null,
};

/**
 * 数据问答（#182）：资料和业务系统的数据并列引用，编号是同一套；数据出处带来源、条件、数据截至和查询时间。
 * 原型给人确认展示位置（D-059），接口字段与后端 DataCitation 一致。
 */
export const answerWithData: Answer = {
  id: 707,
  text: "饮料冷柜每天开店和闭店各盘点一次 [1]；S001 店青柚气泡水现在还剩 24 瓶 [2]。",
  refused: false,
  refusalReason: null,
  citations: [
    {
      index: 1,
      chunkId: 104,
      documentId: 13,
      documentName: "商品陈列与补货规范.md",
      headingPath: "冷链 > 盘点",
      excerpt: "饮料冷柜每天开店、闭店各盘点一次，缺货时当班补齐。",
      documentUpdatedAt: "2026-10-26T09:00:00+08:00",
      documentState: "CURRENT",
    },
  ],
  dataCitations: [
    {
      index: 2,
      tool: "get_store_inventory",
      system: "ERP",
      dataset: "store_inventory",
      conditions: "store_id=S001，sku=A1001",
      asOf: "2026-11-08T09:30:00+08:00",
      queriedAt: "2026-11-08T09:30:02+08:00",
      truncated: false,
    },
  ],
  topRelevance: 0.88,
};

/** 要查的数据超出提问人的数据范围：没有发出查询，如实说无权查看。 */
export const dataDenied: Answer = {
  id: 708,
  text: "无权查看所请求的站点的数据",
  refused: true,
  refusalReason: "DATA_DENIED",
  citations: [],
  dataCitations: [],
  topRelevance: null,
};

/** 数据查询失败：如实说暂时查不到，不编数据、不用旧数据。 */
export const dataUnavailable: Answer = {
  id: 709,
  text: "数据查询暂时不可用，暂时查不到。",
  refused: true,
  refusalReason: "DATA_UNAVAILABLE",
  citations: [],
  dataCitations: [],
  topRelevance: null,
};

export const DATA_ANSWERS = [answerWithData, dataDenied, dataUnavailable];
