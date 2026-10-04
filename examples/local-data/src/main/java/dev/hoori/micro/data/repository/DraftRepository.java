package dev.hoori.micro.data.repository;

import dev.hoori.micro.contracts.dto.Overview;
import hoori.jdbi.JdbiTransactions;

/** SQL constraints are checked inside the same local transaction as the outbox insert. */
@hoori.micro.app.Repository
public final class DraftRepository {
    private final JdbiTransactions manager;

    public DraftRepository(JdbiTransactions manager) {
        this.manager = manager;
    }

    public void save(long id, Overview prepared) {
        manager.jdbi().useHandle(handle -> {
            handle.execute(
                    "insert into meal_drafts(id, recipe_id, title, available_count) values (?, ?, ?, ?)",
                    id,
                    prepared.recipe().id(),
                    prepared.recipe().title(),
                    prepared.available().size());
            handle.execute("insert into meal_outbox(id, event) values (?, 'meal-drafted')", id);
        });
    }
}
