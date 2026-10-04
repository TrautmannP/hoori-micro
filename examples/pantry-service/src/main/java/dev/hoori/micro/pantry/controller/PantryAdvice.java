package dev.hoori.micro.pantry.controller;

import dev.hoori.micro.pantry.service.PantryService.StockNotFound;
import hoori.rest.mvc.*;

@RestControllerAdvice
public final class PantryAdvice {
    @ExceptionHandler(StockNotFound.class)
    public Problem missing(StockNotFound error) {
        return new Problem(404, "stock_missing");
    }
}
