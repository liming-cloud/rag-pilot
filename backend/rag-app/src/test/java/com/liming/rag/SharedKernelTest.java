package com.liming.rag;

import static com.liming.rag.ArchitectureRulesTest.assertViolates;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.archfixture.domain.FixtureAggregate;
import com.liming.rag.archfixture.domain.FixtureEntity;
import com.liming.rag.archfixture.domain.FixtureEntityRepository;
import com.liming.rag.shared.AggregateRoot;
import com.liming.rag.shared.SharedUsesSpring;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 共享内核（rag-shared）的边界，以及聚合根的位置。定义见 AGENTS.md“目录结构”、DECISIONS.md D-034。
 */
class SharedKernelTest {

    static final ArchRule SHARED_ONLY_ON_JDK = classes()
            .that().resideInAPackage("com.liming.rag.shared..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.liming.rag.shared..", "org.springframework.modulith..")
            .as("共享内核只依赖 JDK（package-info 上的 Modulith 注解除外），不依赖框架和其他模块");

    static final ArchRule AGGREGATES_ONLY_IN_CORE = classes()
            .that().implement(AggregateRoot.class)
            .should().resideInAnyPackage("com.liming.rag.knowledge.domain..", "com.liming.rag.answering.domain..")
            .as("聚合根只在 knowledge、answering 的 domain 包");

    static final ArchRule REPOSITORIES_ONLY_FOR_AGGREGATES = classes()
            .that().resideInAPackage("..domain..")
            .and().haveSimpleNameEndingWith("Repository")
            .should(new ArchCondition<>("只为同包的聚合根而建（XxxRepository 对应实现 AggregateRoot 的 Xxx）") {
                @Override
                public void check(JavaClass repository, ConditionEvents events) {
                    String root = repository.getName().substring(0, repository.getName().length() - "Repository".length());
                    boolean ok = repository.getDirectDependenciesFromSelf().stream()
                            .map(dependency -> dependency.getTargetClass())
                            .filter(target -> target.getName().equals(root))
                            .anyMatch(target -> target.isAssignableTo(AggregateRoot.class));
                    events.add(new SimpleConditionEvent(repository, ok,
                            repository.getName() + (ok ? " 对应聚合根 " : " 没有对应的聚合根 ") + root));
                }
            });

    static final List<ArchRule> RULES = List.of(SHARED_ONLY_ON_JDK, AGGREGATES_ONLY_IN_CORE,
            REPOSITORIES_ONLY_FOR_AGGREGATES);

    @Test
    @DisplayName("生产代码遵守共享内核与聚合根规则")
    void productionCodeFollowsRules() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.liming.rag");
        RULES.forEach(rule -> rule.check(production));
        assertThat(production.stream().filter(c -> c.isAssignableTo(AggregateRoot.class) && !c.isInterface()))
                .as("knowledge、answering 至少各有一个聚合根").hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("共享内核没有资源文件（迁移、配置归各自的模块）")
    void sharedKernelHasNoResources() {
        assertThat(Path.of("../rag-shared/src/main/java")).isDirectory();
        assertThat(Files.exists(Path.of("../rag-shared/src/main/resources"))).isFalse();
    }

    @Test
    @DisplayName("每条规则都能拦下对应的违规样例")
    void rulesCatchViolations() {
        assertViolates(SHARED_ONLY_ON_JDK, SharedUsesSpring.class);
        assertViolates(AGGREGATES_ONLY_IN_CORE, FixtureAggregate.class);
        assertViolates(REPOSITORIES_ONLY_FOR_AGGREGATES, FixtureEntityRepository.class, FixtureEntity.class);
    }
}
