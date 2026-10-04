package dev.hoori.micro.contracts.dto;

public record RecipeIds(
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(min = 1, max = 16)
        java.util.List<@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long> ids) {}
