-- Disposable demo schema, owned by this example. No migration or event dispatcher.
CREATE TABLE meal_drafts (
    id bigint PRIMARY KEY CHECK (id > 0),
    recipe_id bigint NOT NULL CHECK (recipe_id > 0),
    title text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    available_count integer NOT NULL CHECK (available_count BETWEEN 0 AND 16)
);
CREATE TABLE meal_outbox (
    id bigint PRIMARY KEY REFERENCES meal_drafts(id),
    event text NOT NULL CHECK (event = 'meal-drafted')
);
