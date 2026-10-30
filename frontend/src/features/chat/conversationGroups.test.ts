import type { ConversationSummary } from "./api";
import { groupByDate } from "./conversationGroups";

const at = (lastAskedAt: string, id: number): ConversationSummary => ({
  id,
  title: `会话 ${id}`,
  knowledgeBaseIds: [1],
  lastAskedAt,
});

describe("会话按日期分组", () => {
  it("按自然日分成今天、昨天、前 7 天、前 30 天、更早，空组不返回", () => {
    const now = new Date(2026, 9, 30, 9, 0);
    const groups = groupByDate(
      [
        at(new Date(2026, 9, 30, 0, 5).toISOString(), 1),
        at(new Date(2026, 9, 29, 23, 50).toISOString(), 2),
        at(new Date(2026, 9, 24, 12, 0).toISOString(), 3),
        at(new Date(2026, 9, 23, 12, 0).toISOString(), 4),
        at(new Date(2026, 8, 1, 12, 0).toISOString(), 5),
      ],
      now,
    );

    expect(groups.map((group) => [group.label, group.items.map((item) => item.id)])).toEqual([
      ["今天", [1]],
      ["昨天", [2]],
      ["前 7 天", [3]],
      ["前 30 天", [4]],
      ["更早", [5]],
    ]);
  });
});
