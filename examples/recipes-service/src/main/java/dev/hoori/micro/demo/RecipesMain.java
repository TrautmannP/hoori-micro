package dev.hoori.micro.demo;

import hoori.micro.JsonTree;
import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.rest.RequestException;
import java.io.InterruptedIOException;
import java.util.Map;

/**
 * Stateless, read-only fixture, not the migrated Dahemm recipes backend.
 */
public final class RecipesMain {
    public static void main(String[] args) throws Exception {

        Service recipes = Service.named("recipes")
                .version(1)
                .action("get", GetRecipe.CODEC, RecipeCodec.INSTANCE, (ctx, input) -> find(input.id))
                .http("GET", "/recipes/{id}")
                .requirePermission("recipes:read")
                .action("slow", GetRecipe.CODEC, RecipeCodec.INSTANCE, (ctx, input) -> {
                    System.out.println("demo_slow_started id=" + ctx.request().id());
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
                                ctx.request().id(),
                                "authorization",
                                ctx.request().headers.get("Authorization") != null));

        // Simulates a later release with one more action; shopping and gateway stay untouched.
        if ("1".equals(System.getenv("HOORI_DEMO_RECOMMEND")))
            recipes.action(
                            "recommend",
                            GetRecipe.CODEC,
                            RecipeCodec.INSTANCE,
                            (ctx, input) -> new Recipe(2, "Apfelstrudel"))
                    .http("GET", "/recipes/{id}/recommendation")
                    .requirePermission("recipes:read");

        try (Microservice app = Microservice.create(recipes)) {
            app.run();
        }
    }

    private static Recipe find(long id) {
        if (id != 1) {
            throw new RequestException(404, "Recipe not found");
        }

        return new Recipe(1, "Kartoffelsuppe");
    }
}
