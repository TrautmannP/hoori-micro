package dev.hoori.micro.data;

import dev.hoori.micro.demo.Overview;
import hoori.tasks.Transactional;

/** Only a local repository, never a remote client or distributed transaction. */
@Transactional
public interface Store {
    void save(long id, Overview prepared) throws Exception;
}
