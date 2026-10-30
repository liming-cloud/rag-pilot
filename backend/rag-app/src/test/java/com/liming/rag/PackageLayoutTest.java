package com.liming.rag;

import static com.liming.rag.ArchitectureRulesTest.assertViolates;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.PublicImplementation;
import com.liming.rag.archfixture.api.ApiLeaksDomain;
import com.liming.rag.archfixture.application.ApplicationUsesInfrastructure;
import com.liming.rag.archfixture.domain.DomainUsesSpring;
import com.liming.rag.archfixture.web.WebUsesInfrastructure;
import com.liming.rag.knowledge.StrayAtModuleRoot;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 目录结构与分层依赖，规范见 AGENTS.md“目录结构”。
 *
 * <p>上下文模块（identity、knowledge、answering、evaluation）的类放在 api、web、application、domain、infrastructure 五个子包；
 * 技术模块（shared、ai）扁平，对外只公开接口、record、枚举和异常。
 */
class PackageLayoutTest {

    private static final String CONTEXT = "com\\.liming\\.rag\\.(identity|knowledge|answering|evaluation)";

    static final ArchRule CONTEXT_CLASSES_IN_LAYERS = classes()
            .that().haveNameMatching(CONTEXT + "\\..+")
            .and().doNotHaveSimpleName("package-info")
            .should().haveNameMatching(CONTEXT + "\\.(api|web|application|domain|infrastructure)\\..+")
            .as("上下文模块的类只放在 api、web、application、domain、infrastructure 五个子包");

    static final ArchRule DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat(nameMatching("(org\\.springframework|com\\.baomidou|org\\.apache\\.ibatis"
                    + "|cn\\.dev33\\.satoken|com\\.liming\\.rag\\.ai)\\..*"
                    + "|.*\\.(application|infrastructure|web)\\..*"))
            .as("domain 只依赖 JDK、rag-shared 和本模块的 domain、api，不依赖框架和其他层");

    static final ArchRule APPLICATION_NOT_ON_ADAPTERS = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..web..")
            .as("application 不依赖 infrastructure、web：需要外部能力时在 application 定义端口接口，由 infrastructure 实现");

    static final ArchRule WEB_NOT_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .as("web 不依赖 infrastructure，经 application 调用");

    static final ArchRule API_SELF_CONTAINED = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..domain..", "..infrastructure..", "..web..")
            .as("api 是对外合同，不暴露模块内部的类型");

    static final ArchRule TECH_MODULES_EXPOSE_CONTRACTS_ONLY = classes()
            .that().resideInAnyPackage("com.liming.rag.ai..", "com.liming.rag.shared..")
            .and().arePublic()
            .and().areNotInterfaces()
            .and().areNotEnums()
            .and().areNotAssignableTo(Throwable.class)
            .should().beAssignableTo(Record.class)
            .as("技术模块（shared、ai）对外只公开接口、record、枚举和异常，实现类包内可见");

    static final List<ArchRule> RULES = List.of(CONTEXT_CLASSES_IN_LAYERS, DOMAIN_IS_PURE,
            APPLICATION_NOT_ON_ADAPTERS, WEB_NOT_ON_INFRASTRUCTURE, API_SELF_CONTAINED,
            TECH_MODULES_EXPOSE_CONTRACTS_ONLY);

    @Test
    @DisplayName("生产代码的目录和分层依赖符合规范")
    void productionCodeFollowsLayout() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.liming.rag");
        assertThat(production).as("应导入各模块的生产代码").hasSizeGreaterThan(50);
        RULES.forEach(rule -> rule.check(production));
    }

    @Test
    @DisplayName("每条目录规则都能拦下对应的违规样例")
    void rulesCatchViolations() {
        assertViolates(CONTEXT_CLASSES_IN_LAYERS, StrayAtModuleRoot.class);
        assertViolates(DOMAIN_IS_PURE, DomainUsesSpring.class);
        assertViolates(APPLICATION_NOT_ON_ADAPTERS, ApplicationUsesInfrastructure.class);
        assertViolates(WEB_NOT_ON_INFRASTRUCTURE, WebUsesInfrastructure.class);
        assertViolates(API_SELF_CONTAINED, ApiLeaksDomain.class);
        assertViolates(TECH_MODULES_EXPOSE_CONTRACTS_ONLY, PublicImplementation.class);
    }
}
