package dev.hoori.micro.contracts.dto;

public record CreateRecipe(
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200)
        String title) {}
