package dev.hoori.micro.crud.error;

import hoori.rest.mvc.ExceptionHandler;
import hoori.rest.mvc.Problem;
import hoori.rest.mvc.RestControllerAdvice;

@RestControllerAdvice
public final class RecipeAdvice {
    @ExceptionHandler(RecipeNotFound.class)
    public Problem missing(RecipeNotFound failure) {
        return new Problem(404, "recipe_missing");
    }
}
