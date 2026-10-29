package com.liming.rag;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.archfixture.FixtureDO;
import com.liming.rag.archfixture.answering.CallsChatModelDirectly;
import com.liming.rag.archfixture.application.UsesQueryWrapper;
import com.liming.rag.archfixture.web.ChecksRoleByHand;
import com.liming.rag.knowledge.infrastructure.persistence.BypassesSearchFilter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 把 AGENTS.md 里能明确判定的约束写成规则。每条规则先检查生产代码，再用 archfixture 里故意违规的样例确认规则能拦下。
 * 模块之间的依赖由 {@link ModularityTests} 检查，这里不重复。
 */
class ArchitectureRulesTest {

    static final ArchRule MYBATIS_ONLY_IN_INFRASTRUCTURE = noClasses()
            .that().resideOutsideOfPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("com.baomidou..", "org.apache.ibatis..")
            .as("MyBatis-Plus 和 MyBatis 的类型只出现在 infrastructure 包");

    static final ArchRule DO_ONLY_IN_INFRASTRUCTURE = classes()
            .that().haveSimpleNameEndingWith("DO")
            .should().resideInAPackage("..infrastructure..")
            .as("DO 只放在 infrastructure 包");

    static final ArchRule MODEL_CALLS_ONLY_IN_RAG_AI = noClasses()
            .that().resideOutsideOfPackage("com.liming.rag.ai..")
            .should().dependOnClassesThat(nameMatching("org\\.springframework\\.ai\\.("
                    + "chat\\.model\\.(ChatModel|StreamingChatModel)"
                    + "|chat\\.client\\..*"
                    + "|embedding\\.EmbeddingModel"
                    + "|openai\\..*)"))
            .as("模型调用（ChatModel、ChatClient、EmbeddingModel、OpenAI 实现）只在 rag-ai 模块");

    static final String CHUNK_SEARCH_MAPPER =
            "com.liming.rag.knowledge.infrastructure.persistence.ChunkSearchMapper";

    static final ArchRule SEARCH_FILTER_IN_ONE_PLACE = noClasses()
            .that().doNotHaveFullyQualifiedName("com.liming.rag.knowledge.infrastructure.persistence.MybatisChunkIndex")
            .and().doNotHaveFullyQualifiedName(CHUNK_SEARCH_MAPPER)
            .should().dependOnClassesThat().haveFullyQualifiedName(CHUNK_SEARCH_MAPPER)
            .as("带成员授权过滤的检索 SQL（ChunkSearchMapper）只经 MybatisChunkIndex 调用");

    static final ArchRule LOGIN_AND_ROLE_ONLY_BY_ANNOTATION = noClasses()
            .should().callMethodWhere(describe("StpUtil 的登录、角色、权限校验方法",
                    (JavaMethodCall call) -> call.getTargetOwner().getFullName().equals("cn.dev33.satoken.stp.StpUtil")
                            && call.getName().matches("check.*|has.*|isLogin")))
            .as("登录和角色校验只用 Sa-Token 注解，不在代码里调用 StpUtil.check*/has*/isLogin");

    static final List<ArchRule> RULES = List.of(MYBATIS_ONLY_IN_INFRASTRUCTURE, DO_ONLY_IN_INFRASTRUCTURE,
            MODEL_CALLS_ONLY_IN_RAG_AI, SEARCH_FILTER_IN_ONE_PLACE, LOGIN_AND_ROLE_ONLY_BY_ANNOTATION);

    @Test
    @DisplayName("生产代码遵守全部架构规则")
    void productionCodeFollowsRules() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.liming.rag");
        assertThat(production).as("应导入各模块的生产代码").hasSizeGreaterThan(50);
        RULES.forEach(rule -> rule.check(production));
    }

    @Test
    @DisplayName("每条规则都能拦下对应的违规样例")
    void rulesCatchViolations() {
        assertViolates(MYBATIS_ONLY_IN_INFRASTRUCTURE, UsesQueryWrapper.class);
        assertViolates(DO_ONLY_IN_INFRASTRUCTURE, FixtureDO.class);
        assertViolates(MODEL_CALLS_ONLY_IN_RAG_AI, CallsChatModelDirectly.class);
        assertViolates(SEARCH_FILTER_IN_ONE_PLACE, BypassesSearchFilter.class);
        assertViolates(LOGIN_AND_ROLE_ONLY_BY_ANNOTATION, ChecksRoleByHand.class);
    }

    private static void assertViolates(ArchRule rule, Class<?> sample) {
        JavaClasses classes = new ClassFileImporter().importClasses(sample);
        assertThat(rule.evaluate(classes).hasViolation())
                .as("%s 应被规则“%s”拦下", sample.getSimpleName(), rule.getDescription())
                .isTrue();
    }
}
