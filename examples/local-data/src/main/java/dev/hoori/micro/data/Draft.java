package dev.hoori.micro.data;

import hoori.rest.codegen.GenerateJsonCodec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@GenerateJsonCodec
@Valid
public record Draft(@Positive long id, @Positive long recipeId) {}
