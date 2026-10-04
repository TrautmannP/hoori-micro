package dev.hoori.micro.facade;

import dev.hoori.micro.demo.DemoValidation;
import dev.hoori.micro.demo.GetRecipeJsonCodec;
import dev.hoori.micro.demo.Overview;
import dev.hoori.micro.demo.OverviewJsonCodec;
import dev.hoori.micro.demo.Pantry;
import dev.hoori.micro.demo.Recipes;
import hoori.concurrent.Tasks;
import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.rest.Responses;
import hoori.rest.validation.ValidatedBody;

/** Optional build-time facade; task generation is independent of DTO generation. */
public final class FacadeMain {
    public static OverviewService overview(Microservice app) {
        RecipesClient recipes = query -> app.context().call(Recipes.GET, query);
        PantryClient pantry = query -> app.context().call(Pantry.FOR_RECIPE, query);
        var recipesTasks = new RecipesClientTasks(recipes);
        var pantryTasks = new PantryClientTasks(pantry);

        return new OverviewServiceScoped(query -> Tasks.parallel(recipesTasks.get(query), pantryTasks.items(query))
                .named("facade.overview")
                .failFast()
                .map(Overview::new));
    }

    public static void routes(Microservice app) {
        OverviewService overview = overview(app);
        app.routes()
                .post(
                        "/overview",
                        ValidatedBody.handle(
                                GetRecipeJsonCodec.INSTANCE,
                                app.jsonLimits(),
                                DemoValidation.GET_RECIPE,
                                (request, input) -> Responses.json(
                                        200, overview.get(input), OverviewJsonCodec.INSTANCE, app.jsonLimits())));
    }

    public static void main(String[] args) throws Exception {
        Service service = Service.named("facade").dependsOn("recipes", 1).dependsOn("pantry", 1);
        try (Microservice app = Microservice.create(service)) {
            routes(app);
            app.run();
        }
    }
}
