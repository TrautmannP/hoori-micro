package dev.hoori.micro.data;

import dev.hoori.micro.demo.GetRecipe;
import dev.hoori.micro.demo.Overview;
import dev.hoori.micro.demo.Pantry;
import dev.hoori.micro.demo.Recipes;
import hoori.concurrent.TaskScope;
import hoori.concurrent.Tasks;
import hoori.jdbi.JdbiTransactions;
import hoori.micro.Context;
import hoori.micro.JsonTree;
import hoori.micro.Microservice;
import hoori.micro.Service;
import hoori.transaction.Transactions;
import java.util.Map;

/** Remote preparation, then one short local data/outbox commit; no business-write replay. */
public final class DataMain {
    public static Service definition(JdbiTransactions manager) {
        Store repository = new MealStore(manager);
        Store explicit = (id, prepared) -> TaskScope.named("drafts.save")
                .with(Transactions.required(manager))
                .run(scope -> repository.save(id, prepared));
        Store generated = new StoreScoped(repository, manager);

        return Service.named("drafts")
                .dependsOn("recipes", 1)
                .dependsOn("pantry", 1)
                .action("save", Draft.CODEC, JsonTree.CODEC, (ctx, input) -> save(ctx, input, explicit))
                .http("save", "POST", "/drafts")
                .requirePermission("save", "drafts:write")
                .action("save-scoped", Draft.CODEC, JsonTree.CODEC, (ctx, input) -> save(ctx, input, generated))
                .http("save-scoped", "POST", "/drafts/scoped")
                .requirePermission("save-scoped", "drafts:write");
    }

    private static Object save(Context ctx, Draft input, Store local) throws Exception {
        GetRecipe query = new GetRecipe(input.recipeId());
        Overview prepared = Tasks.parallel(ctx.task(Recipes.GET, query), ctx.task(Pantry.FOR_RECIPE, query))
                .named("drafts.prepare")
                .failFast()
                .map(Overview::new);
        // The physical commit happens here, before JSON encoding and response I/O.
        local.save(input.id(), prepared);

        return Map.of("id", input.id(), "status", "saved");
    }

    public static void main(String[] args) throws Exception {
        var manager = new JdbiTransactions(Database.factory(), Database.IO_TIMEOUT_MILLIS);
        try (Microservice app = Microservice.create(definition(manager))) {
            app.run();
        }
    }
}
