package dev.hoori.micro.demo;

import hoori.micro.JsonTree;
import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.rest.RequestException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stateless, read-only fixture, not the migrated Dahemm recipes backend.
 */
public final class RecipesMain {
    static Service definition() {
        Service recipes = Service.named("recipes")
                .version(1)
                .action(Recipes.GET, (ctx, input) -> find(input.id))
                .http("get", "GET", "/recipes/{id}")
                .requirePermission("get", "recipes:read")
                .action(Recipes.GET_MANY, (ctx, input) -> {
                    List<Recipe> result = new ArrayList<>();
                    for (long id : input.ids()) result.add(find(id));

                    return List.copyOf(result);
                })
                .action(Recipes.SLOW, (ctx, input) -> {
                    System.out.println(
                            "demo_slow_started id=" + ctx.invocation().requestId());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Demo interrupted");
                    }

                    return find(input.id);
                })
                .action(
                        "context",
                        JsonTree.CODEC,
                        JsonTree.CODEC,
                        (ctx, input) -> Map.of(
                                "requestId",
                                ctx.invocation().requestId(),
                                "authorization",
                                ctx.ownerRequest().headers.get("Authorization") != null));

        // Simulates a later release with one more action; shopping and gateway stay untouched.
        if ("1".equals(System.getenv("HOORI_DEMO_RECOMMEND")))
            recipes.action(
                            "recommend",
                            GetRecipe.CODEC,
                            RecipeCodec.INSTANCE,
                            (ctx, input) -> new Recipe(2, "Apfelstrudel"))
                    .http("recommend", "GET", "/recipes/{id}/recommendation")
                    .requirePermission("recommend", "recipes:read");

        return recipes;
    }

    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create(definition())) {
            app.run();
        }
    }

    private static Recipe find(long id) {
        if (id == 2) return new Recipe(2, "Apfelstrudel");

        if (id != 1) {
            throw new RequestException(404, "Recipe not found");
        }

        return new Recipe(1, "Kartoffelsuppe");
    }
}
