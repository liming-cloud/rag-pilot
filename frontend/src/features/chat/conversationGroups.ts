import type { ConversationSummary } from "./api";

export type ConversationGroup = { label: string; items: ConversationSummary[] };

const LABELS = ["今天", "昨天", "前 7 天", "前 30 天", "更早"] as const;

/** 按最后一次提问的日期分组（本地时区的自然日），组内保持传入的顺序；空组不返回。 */
export function groupByDate(conversations: ConversationSummary[], now = new Date()): ConversationGroup[] {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const day = 24 * 60 * 60 * 1000;
  const groups = new Map<string, ConversationSummary[]>(LABELS.map((label) => [label, []]));
  for (const conversation of conversations) {
    const asked = new Date(conversation.lastAskedAt);
    const days = Math.round((today - new Date(asked.getFullYear(), asked.getMonth(), asked.getDate()).getTime()) / day);
    const label = days <= 0 ? "今天" : days === 1 ? "昨天" : days < 7 ? "前 7 天" : days < 30 ? "前 30 天" : "更早";
    groups.get(label)!.push(conversation);
  }
  return LABELS.map((label) => ({ label, items: groups.get(label)! })).filter((group) => group.items.length > 0);
}
