package dev.hoori.micro.demo;

import hoori.validation.DtoValidator;
import hoori.validation.ValidationLimits;
import hoori.validation.Validators;
import java.util.Locale;

/** Eager bootstrap, explicit generated adapters; no request-time DTO discovery. */
public final class DemoValidation {
    private DemoValidation() {}

    private static final Validators VALIDATORS = create();
    public static final DtoValidator<GetRecipe> GET_RECIPE =
            VALIDATORS.forType(GetRecipe.class, ValidationLimits.DEFAULT);
    public static final DtoValidator<RecipeIds> RECIPE_IDS =
            VALIDATORS.forType(RecipeIds.class, ValidationLimits.DEFAULT);

    private static Validators create() {
        Locale.setDefault(Locale.ENGLISH);

        return Validators.builder()
                .record(GetRecipe.class, GetRecipeValidationAdapter::new)
                .record(RecipeIds.class, RecipeIdsValidationAdapter::new)
                .build();
    }
}
