package dev.hoori.micro.data.service;

import dev.hoori.micro.contracts.dto.Overview;
import dev.hoori.micro.data.repository.DraftRepository;
import hoori.micro.app.Service;

@Service
public final class LocalDraftWriter implements DraftWriter {
    private final DraftRepository repository;

    public LocalDraftWriter(DraftRepository repository) {
        this.repository = repository;
    }

    public void save(long id, Overview prepared) {
        repository.save(id, prepared);
    }
}
