package dev.hoori.micro.data.dto;

import jakarta.validation.constraints.Positive;

public record Draft(@Positive long id, @Positive long recipeId) {}
