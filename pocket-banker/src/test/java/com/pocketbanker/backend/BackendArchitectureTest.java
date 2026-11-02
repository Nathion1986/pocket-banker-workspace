package com.pocketbanker.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class BackendArchitectureTest {
  @Test
  void persistenceDoesNotDependOnServicesAndChannelsCannotInvokeBankOrModelProviders() {
    var classes = new ClassFileImporter().importPackages("com.pocketbanker");
    noClasses()
        .that()
        .resideInAPackage("..data..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.pocketbanker.agent..",
            "com.pocketbanker.actions..",
            "com.pocketbanker.security..",
            "com.pocketbanker.orchestration..",
            "com.pocketbanker.perception..",
            "com.pocketbanker.profile..",
            "com.pocketbanker.prediction..")
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage("..channels.web..", "..channels.telegram..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.pocketbanker.actions..", "..adapters.nativebank..")
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage("..shadow..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.pocketbanker.orchestration..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("com.pocketbanker.security..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.pocketbanker.actions..")
        .check(classes);
  }

  @Test
  void lowLevelModulesCannotDependOnAccountOwnershipOrOtherUpperServices() {
    var production =
        new ClassFileImporter()
            .withImportOption(
                com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.pocketbanker");
    noClasses()
        .that()
        .resideInAnyPackage("..adapters..", "..data..", "..sandbox..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.pocketbanker.agent..",
            "com.pocketbanker.actions..",
            "com.pocketbanker.accounts..",
            "com.pocketbanker.profile..",
            "com.pocketbanker.prediction..",
            "com.pocketbanker.perception..",
            "com.pocketbanker.orchestration..",
            "com.pocketbanker.notifications..",
            "com.pocketbanker.security..")
        .check(production);
  }

  @Test
  void modelTransportImplementationsLiveAtTheSingleOrchestrationBoundary() {
    var production =
        new ClassFileImporter()
            .withImportOption(
                com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.pocketbanker");
    com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
        .that()
        .haveSimpleNameEndingWith("PlanningProvider")
        .should()
        .resideInAPackage("com.pocketbanker.orchestration..")
        .check(production);
  }

  @Test
  void channelsDoNotInvokeModelsOrBankInfrastructure() {
    var production =
        new ClassFileImporter()
            .withImportOption(
                com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.pocketbanker");
    noClasses()
        .that()
        .resideInAnyPackage(
            "com.pocketbanker.channels.web..", "com.pocketbanker.channels.telegram..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.pocketbanker.orchestration..",
            "com.pocketbanker.shadow..",
            "com.pocketbanker.accounts..",
            "com.pocketbanker.actions..",
            "com.pocketbanker.adapters..")
        .check(production);
  }

  @Test
  void onlyTheWorkflowExecutorExternallyDispatchesFinancialActions() {
    var production =
        new ClassFileImporter()
            .withImportOption(
                com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.pocketbanker");
    var accesses =
        production.stream()
            .flatMap(c -> c.getMethodCallsFromSelf().stream())
            .filter(
                call ->
                    call.getTargetOwner()
                            .getName()
                            .equals("com.pocketbanker.actions.workflow.FinancialActions")
                        && call.getTarget().getName().equals("execute")
                        && !call.getOriginOwner().equals(call.getTargetOwner()))
            .toList();
    org.assertj.core.api.Assertions.assertThat(accesses).isNotEmpty();
    for (var call : accesses)
      org.assertj.core.api.Assertions.assertThat(call.getOriginOwner().getName())
          .isEqualTo("com.pocketbanker.agent.WorkflowExecutor");
  }
}
