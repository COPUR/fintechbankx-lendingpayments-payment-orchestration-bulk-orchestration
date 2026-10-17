package com.enterprise.openfinance.bulkpayments.application;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class BulkApplicationArchitectureTest {

    private static final JavaClasses APPLICATION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.enterprise.openfinance.bulkpayments.application");

    @Test
    void applicationDoesNotDependOnInfrastructureOrPersistence() {
        JavaClasses classes = APPLICATION;

        noClasses().that().resideInAPackage("com.enterprise.openfinance.bulkpayments.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.enterprise.openfinance.bulkpayments.infrastructure..",
                        "jakarta.persistence..", "org.hibernate..", "org.springframework.jdbc..",
                        "org.springframework.web..", "org.apache.kafka..")
                .check(classes);
    }

    @Test
    void applicationServicesImplementInboundUseCasePorts() {
        classes().that().resideInAPackage("com.enterprise.openfinance.bulkpayments.application")
                .and().haveSimpleNameEndingWith("Service")
                .should().implement(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "a domain.port.in use case",
                        (com.tngtech.archunit.core.domain.JavaClass c) ->
                                c.getPackageName().startsWith("com.enterprise.openfinance.bulkpayments.domain.port.in")))
                .check(APPLICATION);
    }
}
