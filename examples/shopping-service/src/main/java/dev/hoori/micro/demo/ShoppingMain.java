package dev.hoori.micro.demo;

import hoori.concurrent.Outcome;
import hoori.concurrent.Tasks;
import hoori.micro.Context;
import hoori.micro.JsonTree;
import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.micro.ServiceCallException;
import hoori.rest.RequestException;
import java.util.Map;

/**
 * Calls recipes by action name only; knows no route, host, port or instance. Not a migrated domain.
 */
public final class ShoppingMain {
    static Service definition() {
        return Service.named("shopping")
                .version(1)
                .dependsOn("recipes", 1)
                .dependsOn("pantry", 1)
                .action(
                        "meal",
                        GetRecipeJsonCodec.INSTANCE,
                        RecipeJsonCodec.INSTANCE,
                        DemoValidation.GET_RECIPE,
                        (ctx, input) -> {
                            try {
                                return ctx.call(Recipes.GET, input);
                            } catch (ServiceCallException failed) {
                                // The application decides whether an upstream status has the same domain meaning.
                                if (failed.upstreamStatus() == 404) throw new RequestException(404, "Recipe not found");

                                throw failed;
                            }
                        })
                .http("meal", "GET", "/meals/{id}")
                .requirePermission("meal", "shopping:read")
                .action(
                        "overview",
                        GetRecipeJsonCodec.INSTANCE,
                        OverviewJsonCodec.INSTANCE,
                        DemoValidation.GET_RECIPE,
                        ShoppingMain::overview)
                .http("overview", "GET", "/overview/{id}")
                .requirePermission("overview", "shopping:read")
                .action(
                        "dashboard",
                        GetRecipeJsonCodec.INSTANCE,
                        JsonTree.CODEC,
                        DemoValidation.GET_RECIPE,
                        (ctx, input) -> {
                            var parts = Tasks.parallel(ctx.task(Recipes.GET, input), ctx.task(Pantry.FOR_RECIPE, input))
                                    .named("shopping.dashboard")
                                    .settled();

                            return Map.of(
                                    "recipe",
                                            section(
                                                    parts.first(),
                                                    recipe -> Map.of("id", recipe.id(), "title", recipe.title())),
                                    "pantry", section(parts.second(), items -> items));
                        })
                .http("dashboard", "GET", "/dashboard/{id}")
                .requirePermission("dashboard", "shopping:read")
                // Demonstrates bounded independent item work. Prefer the bulk action below for this read model.
                .action(
                        "batch",
                        RecipeIdsJsonCodec.INSTANCE,
                        Recipes.LIST,
                        DemoValidation.RECIPE_IDS,
                        (ctx, input) -> Tasks.map(input.ids(), id -> ctx.call(Recipes.GET, new GetRecipe(id)))
                                .named("shopping.batch")
                                .maxConcurrency(2)
                                .toList())
                .http("batch", "POST", "/meals/batch")
                .requirePermission("batch", "shopping:read")
                .action(
                        "bulk",
                        RecipeIdsJsonCodec.INSTANCE,
                        Recipes.LIST,
                        DemoValidation.RECIPE_IDS,
                        (ctx, input) -> ctx.call(Recipes.GET_MANY, input))
                .http("bulk", "POST", "/meals/bulk")
                .requirePermission("bulk", "shopping:read")
                // Generic variant without a typed contract; the result is a JsonTree value.
                .action(
                        "context",
                        JsonTree.CODEC,
                        JsonTree.CODEC,
                        (ctx, input) -> ctx.call("recipes.context", Map.of()))
                .http("context", "GET", "/demo/context")
                .requirePermission("context", "shopping:demo")
                .action(
                        "slow",
                        GetRecipeJsonCodec.INSTANCE,
                        RecipeJsonCodec.INSTANCE,
                        DemoValidation.GET_RECIPE,
                        (ctx, input) -> ctx.call(Recipes.SLOW, input))
                .http("slow", "GET", "/demo/slow/{id}")
                .requirePermission("slow", "shopping:demo");
    }

    public static void main(String[] args) throws Exception {
        try (Microservice app = Microservice.create(definition())) {
            app.run();
        }
    }

    static Overview overview(Context ctx, GetRecipe input) throws Exception {
        return Tasks.parallel(ctx.task(Recipes.GET, input), ctx.task(Pantry.FOR_RECIPE, input))
                .named("shopping.overview")
                .failFast()
                .map(Overview::new);
    }

    private static <T> Object section(Outcome<T> outcome, Tasks.Function<T, Object> encode) throws Exception {
        if (outcome.isSuccess()) return Map.of("status", "ok", "data", encode.apply(outcome.value()));

        Throwable failure = outcome.failure();

        // Only a local upstream result becomes optional. Global deadline, admission and cleanup still fail.
        if (failure instanceof ServiceCallException) return Map.of("status", "unavailable");

        if (failure instanceof Exception error) throw error;

        if (failure instanceof Error error) throw error;

        throw new IllegalStateException("Unexpected outcome", failure);
    }
}
