package dev.hoori.micro.data;

import dev.hoori.micro.demo.Overview;
import hoori.jdbi.JdbiTransactions;

/** SQL constraints are checked inside the same local transaction as the outbox insert. */
public final class MealStore implements Store {
    private final JdbiTransactions manager;

    public MealStore(JdbiTransactions manager) {
        this.manager = manager;
    }

    public void save(long id, Overview prepared) {
        manager.jdbi().useHandle(handle -> {
            handle.execute(
                    "insert into meal_drafts(id, recipe_id, title, available_count) values (?, ?, ?, ?)",
                    id,
                    prepared.recipe().id,
                    prepared.recipe().title,
                    prepared.available().size());
            handle.execute("insert into meal_outbox(id, event) values (?, 'meal-drafted')", id);
        });
    }
}
