package dev.hoori.micro.shopping.controller;

import dev.hoori.micro.shopping.service.ShoppingService.RecipeMissing;
import hoori.rest.mvc.*;

@RestControllerAdvice
public final class ShoppingAdvice {
    @ExceptionHandler(RecipeMissing.class)
    public Problem missing(RecipeMissing error) {
        return new Problem(404, "recipe_missing");
    }
}
