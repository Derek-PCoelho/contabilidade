package br.com.contadoresassociados.folhas.server.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.sync.UpsertClientCommand;
import br.com.contadoresassociados.folhas.server.TestDatabase;
import br.com.contadoresassociados.folhas.server.db.DatabaseMigrator;
import br.com.contadoresassociados.folhas.server.db.Db;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Pendência 3.1: com gravações concorrentes, um cliente que puxa mudanças em laço nunca pode
 * avançar o checkpoint além de uma mudança que ainda não viu.
 */
class CheckpointOrderingTest {

    @Test
    void concurrentWritersNeverLetPullSkipChanges() throws Exception {
        try (var database = TestDatabase.create()) {
            DatabaseMigrator.migrate(database.dataSource());
            var org = UUID.randomUUID();
            try (var c = database.admin(); var st = c.createStatement()) {
                st.execute("INSERT INTO organizations VALUES ('" + org + "', 'Org', 'org', true, now(), now())");
            }
            var repository = new SyncRepository(new Db(database.dataSource()), Clock.system());
            var writers = 6;
            var perWriter = 15;
            var pool = Executors.newFixedThreadPool(writers + 1);
            var expected = new HashSet<UUID>();
            for (var w = 0; w < writers; w++) {
                var ids = new java.util.ArrayList<UUID>();
                for (var i = 0; i < perWriter; i++) {
                    ids.add(UUID.randomUUID());
                }
                expected.addAll(ids);
                pool.submit(() -> {
                    for (var id : ids) {
                        repository.apply(org, UUID.randomUUID(), null,
                                new UpsertClientCommand(UUID.randomUUID(), id, "Cliente " + id, true, 0));
                    }
                    return null;
                });
            }
            var seen = java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
            var puller = pool.submit(() -> {
                long checkpoint = 0;
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (seen.size() < expected.size() && System.nanoTime() < deadline) {
                    var page = repository.pull(org, checkpoint);
                    page.clients().forEach(c -> seen.add(c.id()));
                    checkpoint = page.checkpoint();
                }
                return checkpoint;
            });
            puller.get(90, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
        }
    }
}
