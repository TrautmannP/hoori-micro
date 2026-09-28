package dev.hoori.micro.demo;

/** Wire DTO only; never a shared domain entity or persistence model. */
public final class Recipe {
    public final long id;
    public final String title;

    public Recipe(long id, String title) {
        this.id = id;
        this.title = title;
    }
}
