package com.enterprise.openfinance.bulkpayments.infrastructure.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Adapter placement rules from the hexagonal guardrails, mapped onto this
 * repository's adapter packages (rest = inbound web, persistence/outbox/consent = outbound).
 */
class BulkInfrastructureArchitectureTest {

    private static final String BASE = "com.enterprise.openfinance.bulkpayments.infrastructure";

    private static final JavaClasses INFRASTRUCTURE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    void jpaEntitiesStayInsidePersistenceAdapters() {
        classes().that().areAnnotatedWith(Entity.class)
                .should().resideInAnyPackage(BASE + ".persistence..", BASE + ".outbox..")
                .andShould().haveSimpleNameEndingWith("JpaEntity")
                .check(INFRASTRUCTURE);
    }

    @Test
    void restControllersStayInTheInboundWebAdapter() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage(BASE + ".rest..")
                .check(INFRASTRUCTURE);
    }

    @Test
    void outboundAdaptersDoNotReachIntoTheWebLayer() {
        noClasses().that().resideInAnyPackage(BASE + ".persistence..", BASE + ".outbox..", BASE + ".consent..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".rest..")
                .check(INFRASTRUCTURE);
    }

    @Test
    void webLayerUsesUseCasesNotPersistence() {
        noClasses().that().resideInAPackage(BASE + ".rest..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".persistence..", BASE + ".outbox..", "jakarta.persistence..",
                        "com.enterprise.openfinance.bulkpayments.domain.port.out..")
                .check(INFRASTRUCTURE);
    }
}
