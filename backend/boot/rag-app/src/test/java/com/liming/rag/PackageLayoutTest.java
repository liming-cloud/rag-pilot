package com.liming.rag;

import static com.liming.rag.ArchitectureRulesTest.assertViolates;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.liming.rag.ai.PublicImplementation;
import com.liming.rag.archfixture.api.ApiLeaksDomain;
import com.liming.rag.archfixture.application.ApplicationUsesInfrastructure;
import com.liming.rag.archfixture.application.ApplicationUsesSaToken;
import com.liming.rag.archfixture.domain.DomainOutsideCore;
import com.liming.rag.archfixture.domain.DomainUsesSpring;
import com.liming.rag.archfixture.web.WebUsesDomain;
import com.liming.rag.archfixture.web.WebUsesInfrastructure;
import com.liming.rag.knowledge.StrayAtModuleRoot;
import com.liming.rag.shared.SharedUtilityWithState;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 目录结构与分层依赖，规范见 AGENTS.md“目录结构”。
 *
 * <p>上下文模块（identity、knowledge、answering、evaluation、settings）的类放在 api、web、application、domain、infrastructure 五个子包；
 * 技术模块（shared、ai）扁平，对外只公开接口、record、枚举和异常（shared 另可公开无实例的工具类）。
 */
class PackageLayoutTest {

    private static final String CONTEXT = "com\\.liming\\.rag\\.(identity|knowledge|answering|evaluation|settings)";

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

    static final ArchRule DOMAIN_ONLY_IN_CORE = classes()
            .that().resideInAPackage("com.liming.rag..domain..")
            .should().resideInAnyPackage("com.liming.rag.knowledge.domain..", "com.liming.rag.answering.domain..")
            .as("domain 包只在核心上下文 knowledge、answering；其他上下文按普通 Service 实现，不建聚合（#136）");

    static final ArchRule APPLICATION_NOT_ON_SA_TOKEN = noClasses()
            .that().resideInAnyPackage("..application..", "..domain..")
            .should().dependOnClassesThat().resideInAPackage("cn.dev33.satoken..")
            .as("application、domain 不依赖 Sa-Token：会话、密码摘要经 identity 的 Sessions、PasswordHasher 端口（#136）");

    static final ArchRule APPLICATION_NOT_ON_ADAPTERS = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..web..")
            .as("application 不依赖 infrastructure、web：需要外部能力时在 application 定义端口接口，由 infrastructure 实现");

    static final ArchRule WEB_NOT_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .as("web 不依赖 infrastructure，经 application 调用");

    static final ArchRule WEB_NOT_ON_DOMAIN = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAPackage("..domain..")
            .as("web 不依赖 domain：Controller 用 application 和 api 的类型，聚合不出应用层（#136）");

    static final ArchRule API_SELF_CONTAINED = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..domain..", "..infrastructure..", "..web..")
            .as("api 是对外合同，不暴露模块内部的类型");

    /** shared 的通用工具（D-053）：final、构造器全是私有的、字段和方法都是静态的，没有实例可言。 */
    static final DescribedPredicate<JavaClass> SHARED_UTILITY = new DescribedPredicate<>("shared 的无实例工具类") {
        @Override
        public boolean test(JavaClass c) {
            return c.getPackageName().startsWith("com.liming.rag.shared")
                    && c.getModifiers().contains(JavaModifier.FINAL)
                    && c.getConstructors().stream().allMatch(k -> k.getModifiers().contains(JavaModifier.PRIVATE))
                    && c.getMethods().stream().allMatch(m -> m.getModifiers().contains(JavaModifier.STATIC))
                    && c.getFields().stream().allMatch(f -> f.getModifiers().contains(JavaModifier.STATIC));
        }
    };

    static final ArchRule TECH_MODULES_EXPOSE_CONTRACTS_ONLY = classes()
            .that().resideInAnyPackage("com.liming.rag.ai..", "com.liming.rag.shared..")
            .and().arePublic()
            .and().areNotInterfaces()
            .and().areNotEnums()
            .and().areNotAssignableTo(Throwable.class)
            .and(not(SHARED_UTILITY))
            .should().beAssignableTo(Record.class)
            .as("技术模块（shared、ai）对外只公开接口、record、枚举和异常，实现类包内可见；shared 另可公开无实例的工具类（D-053）");

    static final List<ArchRule> RULES = List.of(CONTEXT_CLASSES_IN_LAYERS, DOMAIN_IS_PURE, DOMAIN_ONLY_IN_CORE,
            APPLICATION_NOT_ON_SA_TOKEN,
            APPLICATION_NOT_ON_ADAPTERS, WEB_NOT_ON_INFRASTRUCTURE, WEB_NOT_ON_DOMAIN, API_SELF_CONTAINED,
            TECH_MODULES_EXPOSE_CONTRACTS_ONLY);

    @Test
    @DisplayName("每个主代码包都有 package-info.java 并标 @NullMarked：默认不为 null，可以为 null 的地方写 @Nullable（#145）")
    void everyPackageIsNullMarked() throws IOException {
        var root = BackendTree.root();
        try (var files = Files.walk(root)) {
            var packages = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> file.toString().replace('\\', '/').contains("/src/main/java/"))
                    .map(Path::getParent).distinct().toList();
            var missing = packages.stream().filter(dir -> {
                var info = dir.resolve("package-info.java");
                try {
                    return !Files.exists(info) || !Files.readString(info)
                            .contains("@NullMarked");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).map(dir -> BackendTree.moduleRelative(root, dir)).sorted().toList();
            assertThat(packages).hasSizeGreaterThan(30);
            assertThat(missing).as("缺 @NullMarked 的包").isEmpty();
        }
    }

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
        assertViolates(DOMAIN_ONLY_IN_CORE, DomainOutsideCore.class);
        assertViolates(APPLICATION_NOT_ON_SA_TOKEN, ApplicationUsesSaToken.class);
        assertViolates(APPLICATION_NOT_ON_ADAPTERS, ApplicationUsesInfrastructure.class);
        assertViolates(WEB_NOT_ON_INFRASTRUCTURE, WebUsesInfrastructure.class);
        assertViolates(WEB_NOT_ON_DOMAIN, WebUsesDomain.class, com.liming.rag.archfixture.domain.FixtureAggregate.class);
        assertViolates(API_SELF_CONTAINED, ApiLeaksDomain.class);
        assertViolates(TECH_MODULES_EXPOSE_CONTRACTS_ONLY, PublicImplementation.class);
        assertViolates(TECH_MODULES_EXPOSE_CONTRACTS_ONLY, SharedUtilityWithState.class);
    }
}
