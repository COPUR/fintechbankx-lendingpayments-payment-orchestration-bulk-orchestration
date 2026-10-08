package com.enterprise.openfinance.bulkpayments.domain.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The bulk-payments domain depends on the JDK only (fbx-hexagonal-service §2).
 */
class BulkDomainArchitectureTest {

    private static final JavaClasses DOMAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.enterprise.openfinance.bulkpayments.domain");

    @Test
    void domainDoesNotDependOnApplicationOrInfrastructure() {
        noClasses().that().resideInAPackage("com.enterprise.openfinance.bulkpayments.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.enterprise.openfinance.bulkpayments.application..",
                        "com.enterprise.openfinance.bulkpayments.infrastructure..")
                .check(DOMAIN);
    }

    @Test
    void domainIsFreeOfFrameworkPersistenceAndMessagingTypes() {
        noClasses().that().resideInAPackage("com.enterprise.openfinance.bulkpayments.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "org.hibernate..",
                        "com.fasterxml.jackson..", "org.apache.kafka..", "org.mongodb..", "com.mongodb..", "lombok..",
                        "org.slf4j..")
                .check(DOMAIN);
    }

    @Test
    void useCasesAreInterfacesInPortIn() {
        classes().that().haveSimpleNameEndingWith("UseCase")
                .should().beInterfaces()
                .andShould().resideInAPackage("com.enterprise.openfinance.bulkpayments.domain.port.in")
                .check(DOMAIN);
    }

    @Test
    void outboundPortsAreInterfacesNamedPortOrRepository() {
        classes().that().resideInAPackage("com.enterprise.openfinance.bulkpayments.domain.port.out..")
                .should().beInterfaces()
                .andShould().haveSimpleNameEndingWith("Port").orShould().haveSimpleNameEndingWith("Repository")
                .check(DOMAIN);
    }

    @Test
    void domainEventsLiveInTheEventPackageAndAreImmutable() {
        classes().that().implement("com.enterprise.openfinance.bulkpayments.domain.event.BulkFileEvent")
                .should().resideInAPackage("com.enterprise.openfinance.bulkpayments.domain.event..")
                .andShould().haveOnlyFinalFields()
                .check(DOMAIN);
    }
}
