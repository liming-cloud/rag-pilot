import type {
  DocumentFinding,
  FindingCode,
  KnowledgeBase,
  KnowledgeDocument,
  SimilarDocument,
  Validity,
} from "@/features/knowledge/api";

/** 假接口与后端契约保持一致；示例数据只用于原型和测试。 */
export const sampleKnowledgeBases: KnowledgeBase[] = [
  { id: 1, name: "门店运营知识库" },
  { id: 2, name: "商品知识库" },
  // 多生成一些知识库，原型里能看到分页和筛选；只有管理员是成员。
  ...Array.from({ length: 22 }, (_, index) => ({ id: 3 + index, name: `区域 ${index + 1} 门店规范` })),
];

/** 存储的文档不带 validity，读取时按今天现算，与后端一致。 */
export type StoredDocument = Omit<KnowledgeDocument, "validity">;

/** 提前多少天提醒到期，对应部署配置 rag.knowledge.expiry-warning-days（默认 30）。 */
export const WARNING_DAYS = 30;

/** 统计时区（默认北京时间）的今天，yyyy-MM-dd。 */
function today() {
  return new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai" }).format(new Date());
}

function addDays(date: string, days: number) {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + days);
  return next.toISOString().slice(0, 10);
}

/** 失效日期当天仍有效；未到生效日期的不参与检索。 */
export function validityOf(document: StoredDocument): Validity {
  const now = today();
  if (document.effectiveFrom && document.effectiveFrom > now) return "PENDING";
  if (document.expiresOn && document.expiresOn < now) return "EXPIRED";
  if (document.expiresOn && document.expiresOn <= addDays(now, WARNING_DAYS)) return "EXPIRING";
  return "ACTIVE";
}

export const withValidity = (document: StoredDocument): KnowledgeDocument => ({ ...document, validity: validityOf(document) });

const longTerm = { effectiveFrom: null, expiresOn: null };

/** 体检（#218）：一个问题；重复时带上另一份文档。 */
const finding = (code: FindingCode, ...similar: SimilarDocument[]): DocumentFinding => ({ code, similar });
const inStoreBase = (documentId: number, filename: string): SimilarDocument => ({
  knowledgeBaseId: 1,
  knowledgeBaseName: "门店运营知识库",
  documentId,
  filename,
});

export const sampleDocuments: Record<number, StoredDocument[]> = {
  1: [
    {
      id: 11,
      filename: "临期与报损管理.md",
      status: "READY",
      error: null,
      chunkCount: 12,
      sizeBytes: 18_432,
      createdAt: "2026-10-26T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [finding("DUPLICATE", { knowledgeBaseId: 2, knowledgeBaseName: "商品知识库", documentId: 201, filename: "临期与报损管理.md" })],
      ...longTerm,
    },
    // 有效期的几种情况（日期相对今天，原型里随时都能看到）：旧版制度已失效、今天到期（当天仍有效）、
    // 月底到期的促销规则、下周才生效的新制度。
    {
      id: 13,
      filename: "临期与报损管理（旧版）.md",
      status: "READY",
      error: null,
      chunkCount: 10,
      sizeBytes: 16_200,
      createdAt: "2026-03-01T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [],
      effectiveFrom: "2026-03-01",
      expiresOn: addDays(today(), -10),
    },
    {
      id: 14,
      filename: "双十一活动执行方案.pdf",
      status: "READY",
      error: null,
      chunkCount: 6,
      sizeBytes: 220_000,
      createdAt: "2026-10-20T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [finding("GARBLED")],
      effectiveFrom: null,
      expiresOn: today(),
    },
    {
      id: 15,
      filename: "会员积分规则（本季度）.docx",
      status: "READY",
      error: null,
      chunkCount: 9,
      sizeBytes: 64_000,
      createdAt: "2026-10-01T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [],
      effectiveFrom: null,
      expiresOn: addDays(today(), 18),
    },
    {
      id: 16,
      filename: "收银交接班制度（下周起执行）.md",
      status: "READY",
      error: null,
      chunkCount: 5,
      sizeBytes: 12_000,
      createdAt: "2026-10-30T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [finding("NEAR_DUPLICATE", inStoreBase(21, "收银交接班制度（草稿）.md"))],
      effectiveFrom: addDays(today(), 5),
      expiresOn: null,
    },
    {
      id: 12,
      filename: "旧版陈列手册.txt",
      status: "FAILED",
      error: "文件不是 UTF-8 编码，请用“另存为 UTF-8”后重新上传",
      chunkCount: 0,
      sizeBytes: 40_960,
      createdAt: "2026-10-26T09:05:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: null,
      ...longTerm,
    },
    // 原型（#145 A9）：模型暂时不可用而失败的文档，可以重新处理，10 分钟后自动重试一次。
    {
      id: 17,
      filename: "夜班交接手册.docx",
      status: "FAILED",
      error: "向量化失败：嵌入模型服务暂时不可用，请稍后重试",
      chunkCount: 0,
      sizeBytes: 52_224,
      createdAt: "2026-11-04T09:30:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: true,
      retryAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
      findings: null,
      ...longTerm,
    },
    // 资料体检（#218）的几种结论：截图为主的手册、图片为主的课件、过大的表格、内容与另一份重合的草稿。
    {
      id: 18,
      filename: "收银系统操作指南.docx",
      status: "READY",
      error: null,
      chunkCount: 3,
      sizeBytes: 4_800_000,
      createdAt: "2026-11-02T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [finding("IMAGE_HEAVY"), finding("WORD_NO_HEADINGS")],
      ...longTerm,
    },
    {
      id: 19,
      filename: "新员工培训课件.pptx",
      status: "READY",
      error: null,
      chunkCount: 14,
      sizeBytes: 22_000_000,
      createdAt: "2026-11-02T09:10:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [finding("IMAGE_HEAVY")],
      ...longTerm,
    },
    {
      id: 20,
      filename: "全年促销排期表.xlsx",
      status: "READY",
      error: null,
      chunkCount: 86,
      sizeBytes: 380_000,
      createdAt: "2026-11-02T09:20:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [finding("LARGE_TABLE")],
      ...longTerm,
    },
    {
      id: 21,
      filename: "收银交接班制度（草稿）.md",
      status: "READY",
      error: null,
      chunkCount: 5,
      sizeBytes: 11_500,
      createdAt: "2026-10-28T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [finding("NEAR_DUPLICATE", inStoreBase(16, "收银交接班制度（下周起执行）.md"))],
      ...longTerm,
    },
    // 多生成一些文档，原型里能看到分页。
    ...Array.from({ length: 26 }, (_, index): StoredDocument => ({
      id: 300 + index,
      filename: `门店操作手册-第${index + 1}章.pdf`,
      status: "READY",
      error: null,
      chunkCount: 8 + (index % 7),
      sizeBytes: 120_000 + index * 9_000,
      createdAt: "2026-10-27T10:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      // 长 PDF 常读不出章节。
      findings: index % 10 === 0 ? [finding("PDF_NO_STRUCTURE")] : [],
      ...longTerm,
    })),
  ],
  // 和门店运营知识库里的同名文档内容相同（#218 跨库重复）。
  2: [
    {
      id: 201,
      filename: "临期与报损管理.md",
      status: "READY",
      error: null,
      chunkCount: 12,
      sizeBytes: 18_432,
      createdAt: "2026-10-29T09:00:00+08:00",
      replacesDocumentId: null,
      replacesFilename: null,
      reprocessable: false,
      retryAt: null,
      findings: [finding("DUPLICATE", inStoreBase(11, "临期与报损管理.md"))],
      ...longTerm,
    },
  ],
};
