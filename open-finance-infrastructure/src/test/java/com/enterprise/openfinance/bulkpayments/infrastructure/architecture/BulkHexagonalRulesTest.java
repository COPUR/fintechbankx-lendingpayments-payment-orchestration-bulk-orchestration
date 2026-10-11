package com.enterprise.openfinance.bulkpayments.infrastructure.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four service guardrail rules (FINTECHBANKX_SERVICE_GUARDRAILS.md section 3, ADR-028),
 * checked over the whole service: domain, application and infrastructure together.
 */
class BulkHexagonalRulesTest {

    private static final String ROOT = "com.enterprise.openfinance.bulkpayments";

    private static final JavaClasses SERVICE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    private static final DescribedPredicate<JavaClass> INBOUND_ADAPTER = DescribedPredicate.describe(
            "controllers and listeners",
            c -> c.isAnnotatedWith(RestController.class) || c.isAnnotatedWith(Controller.class)
                    || c.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(KafkaListener.class)));

    private static final DescribedPredicate<JavaClass> OUTBOUND_PORT_IMPLEMENTATION = DescribedPredicate.describe(
            "implementations of domain.port.out interfaces",
            c -> !c.isInterface() && c.getAllRawInterfaces().stream()
                    .anyMatch(i -> i.getPackageName().startsWith(ROOT + ".domain.port.out")));

    @Test
    void rule1DomainDependsOnNoOuterLayerOrFramework() {
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".application..", ROOT + ".infrastructure..",
                        "org.springframework..", "jakarta.persistence..",
                        "org.apache.kafka..", "org.mongodb..", "com.mongodb..")
                .check(SERVICE);
    }

    @Test
    void rule2ApplicationDependsOnNoInfrastructure() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure..")
                .check(SERVICE);
    }

    @Test
    void rule3InboundAdaptersUseInPortsNotApplicationClasses() {
        noClasses().that(INBOUND_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".application..")
                .check(SERVICE);
        classes().that(INBOUND_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.port.in..")
                .check(SERVICE);
    }

    @Test
    void rule4OutboundPortImplementationsLiveInInfrastructure() {
        classes().that(OUTBOUND_PORT_IMPLEMENTATION)
                .should().resideInAPackage(ROOT + ".infrastructure..")
                .check(SERVICE);
    }
}
