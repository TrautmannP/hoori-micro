package dev.hoori.micro.shopping.service;

import dev.hoori.micro.contracts.dto.*;
import dev.hoori.micro.shopping.client.*;
import hoori.concurrent.Outcome;
import hoori.concurrent.Tasks;
import hoori.micro.ServiceCallException;
import hoori.micro.app.Service;
import hoori.rest.mvc.HttpResult;
import java.util.List;

@Service
public final class ShoppingService {
    private final RecipeClient recipes;
    private final PantryClient pantry;

    public ShoppingService(RecipeClient recipes, PantryClient pantry) {
        this.recipes = recipes;
        this.pantry = pantry;
    }

    public Recipe meal(long id) {
        try {
            return recipes.get(id);
        } catch (ServiceCallException failure) {
            if (failure.upstreamStatus() == 404) throw new RecipeMissing();

            throw failure;
        }
    }

    public List<Recipe> list(String prefix) {
        return recipes.list(prefix);
    }

    public HttpResult<Recipe> create(CreateRecipe input) {
        return recipes.create(input);
    }

    public void delete(long id) {
        recipes.delete(id);
    }

    public Overview overview(long id) throws Exception {
        return Tasks.parallel(Tasks.task(recipes::get, id), Tasks.task(pantry::items, id))
                .named("shopping.overview")
                .failFast()
                .map(Overview::new);
    }

    public Dashboard dashboard(long id) throws Exception {
        var parts = Tasks.parallel(Tasks.task(recipes::get, id), Tasks.task(pantry::items, id))
                .named("shopping.dashboard")
                .settled();
        Recipe recipe = optional(parts.first());
        List<String> items = optional(parts.second());

        return new Dashboard(
                new RecipeSection(recipe == null ? "unavailable" : "ok", recipe),
                new PantrySection(items == null ? "unavailable" : "ok", items));
    }

    public List<Recipe> batch(RecipeIds input) throws Exception {
        return Tasks.map(input.ids(), recipes::get)
                .named("shopping.batch")
                .maxConcurrency(2)
                .toList();
    }

    public List<Recipe> bulk(RecipeIds input) {
        return recipes.bulk(input);
    }

    public Recipe slow(long id) {
        return recipes.slow(id);
    }

    public RequestInfo context() {
        return recipes.context();
    }

    private static <T> T optional(Outcome<T> outcome) throws Exception {
        if (outcome.isSuccess()) return outcome.value();

        Throwable failure = outcome.failure();

        if (failure instanceof ServiceCallException) return null;

        if (failure instanceof Exception error) throw error;

        if (failure instanceof Error error) throw error;

        throw new IllegalStateException("Unexpected outcome", failure);
    }

    public static final class RecipeMissing extends RuntimeException {}
}
