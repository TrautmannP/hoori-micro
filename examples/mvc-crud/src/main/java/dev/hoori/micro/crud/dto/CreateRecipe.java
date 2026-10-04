package dev.hoori.micro.crud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRecipe(@NotBlank @Size(max = 200) String title) {}
