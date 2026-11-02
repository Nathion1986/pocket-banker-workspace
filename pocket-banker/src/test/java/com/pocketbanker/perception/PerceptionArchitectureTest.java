package com.pocketbanker.perception;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class PerceptionArchitectureTest {
  @Test
  void perceptionAndProfileCannotExecuteActionsOrAccessBankAdapters() {
    var classes = new ClassFileImporter().importPackages("com.pocketbanker");
    noClasses()
        .that()
        .resideInAnyPackage("..perception..", "..profile..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..actions..", "..adapters.bank..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..profile..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..perception..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..data..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..perception..", "..profile..", "..agent..", "..orchestration..")
        .check(classes);
  }
}
