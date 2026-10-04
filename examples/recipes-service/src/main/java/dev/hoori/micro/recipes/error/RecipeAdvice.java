package dev.hoori.micro.recipes.error;

import hoori.rest.mvc.*;

@RestControllerAdvice
public final class RecipeAdvice {
    @ExceptionHandler(RecipeNotFound.class)
    public Problem missing(RecipeNotFound error) {
        return new Problem(404, "recipe_missing");
    }

    @ExceptionHandler(RecipeStoreFull.class)
    public Problem full(RecipeStoreFull error) {
        return new Problem(409, "demo_store_full");
    }
}
