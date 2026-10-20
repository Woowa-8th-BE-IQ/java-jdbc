package transaction.stage5;

import com.arjuna.ats.arjuna.coordinator.TransactionReaper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JtaGlobalTransactionTest {

    private final PlatformTransactionManager transactionManager = NarayanaTestSupport.transactionManager();

    @Test
    @DisplayName("두 DB 모두 prepare에 성공한 뒤 2단계 커밋으로 함께 반영한다")
    void commitsBothDatabasesAfterBothResourcesPrepare() throws Exception {
        final var events = new ArrayList<String>();
        try (final var first = new NarayanaTestSupport.XaDatabase("first", events, false);
             final var second = new NarayanaTestSupport.XaDatabase("second", events, false)) {

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                NarayanaTestSupport.enlist(first.resource(), second.resource());
                first.insert();
                second.insert();
            });

            assertThat(first.count()).isEqualTo(1);
            assertThat(second.count()).isEqualTo(1);
            assertThat(events.subList(0, 2)).containsExactlyInAnyOrder("first.prepare", "second.prepare");
            assertThat(events.subList(2, 4)).containsExactlyInAnyOrder(
                    "first.commit(onePhase=false)", "second.commit(onePhase=false)");
            assertThat(events).hasSize(4);
        }
    }

    @Test
    @DisplayName("두 DB에 SQL을 실행한 뒤 업무 예외가 나면 함께 롤백한다")
    void businessFailureRollsBackBothDatabases() throws Exception {
        final var events = new ArrayList<String>();
        final var failure = new IllegalStateException("두 번째 DB 처리 후 업무 실패");
        try (final var first = new NarayanaTestSupport.XaDatabase("first", events, false);
             final var second = new NarayanaTestSupport.XaDatabase("second", events, false)) {

            final var thrown = assertThrows(IllegalStateException.class,
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        NarayanaTestSupport.enlist(first.resource(), second.resource());
                        first.insert();
                        second.insert();
                        throw failure;
                    }));

            assertThat(thrown).isSameAs(failure);
            assertThat(first.count()).isZero();
            assertThat(second.count()).isZero();
            assertThat(events).containsExactlyInAnyOrder("first.rollback", "second.rollback");
        }
    }

    @Test
    @DisplayName("업무가 끝났어도 한 DB가 prepare에 실패하면 두 DB 모두 롤백한다")
    void prepareFailurePreventsCommitOnBothDatabases() throws Exception {
        final var events = new ArrayList<String>();
        try (final var first = new NarayanaTestSupport.XaDatabase("first", events, false);
             final var second = new NarayanaTestSupport.XaDatabase("second", events, true)) {

            assertThrows(UnexpectedRollbackException.class,
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        NarayanaTestSupport.enlist(first.resource(), second.resource());
                        first.insert();
                        second.insert();
                    }));

            assertThat(first.count()).isZero();
            assertThat(second.count()).isZero();
            assertThat(events).contains("second.prepare", "first.rollback", "second.rollback");
            assertThat(events).noneMatch(event -> event.contains(".commit"));
        }
    }

    @AfterEach
    void noTransactionRemainsOnTheTestThread() throws Exception {
        final var manager = com.arjuna.ats.jta.TransactionManager.transactionManager();
        final var remaining = manager.getTransaction();
        if (remaining != null) {
            manager.rollback();
        }
        assertThat(remaining).isNull();
    }

    @AfterAll
    static void stopTimeoutMonitor() {
        TransactionReaper.terminate(false);
    }
}
