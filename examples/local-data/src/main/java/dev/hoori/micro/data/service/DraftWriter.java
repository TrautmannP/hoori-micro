package dev.hoori.micro.data.service;

import dev.hoori.micro.contracts.dto.Overview;
import hoori.tasks.Transactional;

/** Only the short local database/outbox operation enters a transaction. */
@Transactional
public interface DraftWriter {
    void save(long id, Overview prepared) throws Exception;
}
