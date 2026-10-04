#!/usr/bin/env python3
"""Build a separate provider release with one added controller for native rolling checks."""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

from runtime_check import ROOT, maven_repository


def build(runtime):
    repo = maven_repository(runtime)
    mvn = ["mvn", "-B", "-ntp", f"-Dmaven.repo.local={repo}"]
    subprocess.run(mvn + ["-pl", "processor,starter,examples/demo-contracts", "-am", "install", "-DskipTests", "-Dspotless.skip=true"],
                   cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory(prefix="hoori-micro-release-") as directory:
        work = Path(directory)
        original = ROOT / "examples/recipes-service"
        shutil.copytree(original / "src/main", work / "src/main")
        pom = (original / "pom.xml").read_text().replace("../../starter/pom.xml", "")
        pom = pom.replace("<spotless.skip>false</spotless.skip>", "<spotless.skip>true</spotless.skip>")
        (work / "pom.xml").write_text(pom)
        source = work / "src/main/java/dev/hoori/micro/recipes/controller/RecommendationController.java"
        source.write_text('''package dev.hoori.micro.recipes.controller;
import dev.hoori.micro.contracts.dto.Recipe;
import dev.hoori.micro.recipes.service.RecipeService;
import hoori.micro.app.GatewayRoute;
import hoori.rest.mvc.*;
import jakarta.validation.constraints.Positive;
@RestController public final class RecommendationController {
 private final RecipeService recipes;
 public RecommendationController(RecipeService recipes) { this.recipes=recipes; }
 @GetMapping("/recipes/{id}/recommendation") @GatewayRoute(permission="recipes:read")
 public Recipe recommend(@PathVariable("id") @Positive long id) {
  recipes.get(id); return recipes.get(id == 1 ? 2 : 1);
 }
}
''')
        contract = work / "src/main/resources/openapi.json"
        document = json.loads(contract.read_text())
        operation = json.loads(json.dumps(document["paths"]["/recipes/{id}"]["get"]))
        operation["operationId"] = "recipesRecommendation"
        operation["summary"] = "Recommend a recipe"
        document["paths"]["/recipes/{id}/recommendation"] = {"get": operation}
        contract.write_text(json.dumps(document, indent=2) + "\n")
        subprocess.run(mvn + ["package"], cwd=work, check=True)
        target = ROOT / ".docker-context/apps/recipes-next/lib"
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(ROOT / ".docker-context/apps/recipes/lib", target)
        shutil.copy2(work / "target/recipes-service-0.1.0-SNAPSHOT.jar", target)
    return target


if __name__ == "__main__":
    build(ROOT / ".docker-context/runtime")
