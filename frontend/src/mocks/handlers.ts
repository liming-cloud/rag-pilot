import { answerHandlers, resetAnswers } from "./answers";
import { auditHandlers } from "./audit";
import { conversationHandlers, resetConversations } from "./conversations";
import { dataHandlers, resetData } from "./data";
import { gapHandlers, resetGaps } from "./gaps";
import { currentIdentity, identityHandlers, resetIdentity } from "./identity";
import { accessibleKnowledgeBaseIds, canAccess, knowledgeBaseName, knowledgeHandlers, resetKnowledge } from "./knowledge";
import { modelHandlers, resetModels } from "./models";
import { resetSettings, settingHandlers } from "./settings";
import { statsHandlers } from "./stats";
import { userHandlers } from "./users";

/** 假接口与后端契约保持一致（契约测试见 test/contract.test.ts）；示例数据只用于原型和测试，按 feature 分文件。 */
export function resetMockData() {
  resetGaps();
  resetKnowledge();
  resetAnswers();
  resetIdentity();
  resetModels();
  resetData();
  resetSettings();
  resetConversations();
}
resetMockData();

export const handlers = [
  ...identityHandlers,
  ...userHandlers,
  ...auditHandlers,
  ...gapHandlers,
  ...modelHandlers,
  ...dataHandlers,
  ...settingHandlers,
  ...answerHandlers,
  ...statsHandlers,
  ...knowledgeHandlers,
  ...conversationHandlers({
    currentUserId: () => currentIdentity().id,
    knowledgeBaseName,
    canAccess,
    accessibleKnowledgeBaseIds,
  }),
];
