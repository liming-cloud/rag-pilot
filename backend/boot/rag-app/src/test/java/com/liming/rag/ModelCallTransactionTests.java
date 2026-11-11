package com.liming.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.liming.rag.ai.EmbeddingClient;
import com.liming.rag.ai.ModelProbe;
import com.liming.rag.settings.application.model.ModelPurpose;
import com.liming.rag.answering.application.ask.AskResult;
import com.liming.rag.answering.application.review.AnswerLogService;
import com.liming.rag.answering.application.insight.KnowledgeGapService;
import com.liming.rag.answering.application.insight.UsageReportService;
import com.liming.rag.answering.domain.RefusalReason;
import com.liming.rag.knowledge.api.KnowledgeSearch;
import com.liming.rag.knowledge.api.SearchQuery;
import com.liming.rag.settings.application.model.ModelSettingsService;
import com.liming.rag.shared.TenantId;
import com.liming.rag.shared.UserId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 调用模型（连通测试、读模型列表、向量化）时不在数据库事务里（#135）：外部调用可能等到超时，不能一直占着连接池里的连接。
 * 在每次调用模型的那一刻记下当前线程是否在事务里。
 */
@SpringBootTest
class ModelCallTransactionTests {

    private static final TenantId TENANT = new TenantId(1);

    @MockitoSpyBean
    private ModelProbe probe;

    @MockitoSpyBean
    private EmbeddingClient embeddings;

    @Autowired
    private ModelSettingsService models;

    @Autowired
    private KnowledgeGapService gaps;

    @Autowired
    private UsageReportService usage;

    @Autowired
    private AnswerLogService answers;

    @Autowired
    private KnowledgeSearch search;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Boolean> inTransaction = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void recordTransactionState() {
        inTransaction.clear();
        doAnswer(invocation -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(probe).probe(any(), any());
        doAnswer(invocation -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(probe).models(any(), any());
        doAnswer(invocation -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(embeddings).embed(anyList());
        doAnswer(invocation -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.callRealMethod();
        }).when(embeddings).embedQuestion(anyString());
    }

    @Test
    @DisplayName("模型管理：保存前的连通测试、单独测试、读服务商的模型列表都不在事务里")
    void probesOutsideTransactions() {
        var admin = admin();

        ignoreFailure(() -> models.save(TENANT, admin, ModelPurpose.RERANK.name(), null, "any-reranker"));
        ignoreFailure(() -> models.test(ModelPurpose.ANSWER.name(), null, "any-model"));
        ignoreFailure(() -> models.availableModels(null));

        assertThat(inTransaction).hasSize(3).containsOnly(false);
    }

    @Test
    @DisplayName("知识缺口、运营概览归类时的向量化不在事务里")
    void embedsGapQuestionsOutsideTransactions() throws InterruptedException {
        var admin = admin();
        long id = answers.record(TENANT, admin, "冷柜温度要多少？",
                AskResult.refused(RefusalReason.NO_EVIDENCE, null, "答不上来")).id();
        // 后台补存问题向量与下面的归类赛跑（#164）：等它存好再置空，归类一定补算一次。
        waitForVector(id);
        jdbc.update("UPDATE answer_record SET question_embedding = NULL WHERE id = ?", id);

        gaps.report(TENANT, 7, "all");
        usage.usage(TENANT, 7, null);

        assertThat(inTransaction).hasSize(2).containsOnly(false);
    }

    @Test
    @DisplayName("检索时给问题向量化不在事务里")
    void embedsSearchQueryOutsideTransactions() {
        search.search(new SearchQuery(TENANT, admin(), List.of(), "冷柜温度要多少？", 5));

        assertThat(inTransaction).hasSize(1).containsOnly(false);
    }

    private UserId admin() {
        return new UserId(jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class,
                TestLogin.ADMIN_USERNAME));
    }

    private void waitForVector(long id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT question_embedding IS NOT NULL FROM answer_record WHERE id = ?", Boolean.class, id))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("回答 " + id + " 的问题向量 5 秒内没有存好");
    }

    /** 测试环境连不上真实模型，连通测试失败也要先调用到模型，这里只关心调用时的事务状态。 */
    private static void ignoreFailure(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            // 预期之内：测试环境的服务商地址不可用。
        }
    }
}
