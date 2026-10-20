package transaction.stage5;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalTransactionTest {

    @Test
    @DisplayName("하나의 DB에서 수행한 여러 SQL은 함께 롤백된다")
    void localTransactionRollsBackAllWritesOnItsDataSource() throws Exception {
        try (final var database = new LocalDatabase()) {
            database.createTable();
            final var transaction = new TransactionTemplate(new DataSourceTransactionManager(database.dataSource()));
            final var failure = new IllegalStateException("두 번째 작업 이후 실패");

            assertThat(assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                database.jdbc().update("insert into tx_records (label) values (?)", "first");
                database.jdbc().update("insert into tx_records (label) values (?)", "second");
                throw failure;
            }))).isSameAs(failure);

            assertThat(database.count()).isZero();
        }
    }

    @Test
    @DisplayName("로컬 트랜잭션은 다른 DataSource의 자동 커밋을 되돌리지 못한다")
    void anotherDataSourceCommitsOutsideTheLocalTransaction() throws Exception {
        try (final var first = new LocalDatabase(); final var second = new LocalDatabase()) {
            first.createTable();
            second.createTable();
            final var transaction = new TransactionTemplate(new DataSourceTransactionManager(first.dataSource()));

            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                first.jdbc().update("insert into tx_records (label) values (?)", "first");
                second.jdbc().update("insert into tx_records (label) values (?)", "second");
                throw new IllegalStateException("두 DB에 기록한 뒤 실패");
            }));

            assertThat(first.count()).isZero();
            assertThat(second.count()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("두 로컬 트랜잭션 관리자를 중첩해도 이미 커밋한 DB는 되돌릴 수 없다")
    void twoLocalManagersDoNotCreateOneGlobalTransaction() throws Exception {
        try (final var first = new LocalDatabase(); final var second = new LocalDatabase()) {
            first.createTable();
            second.createTable();
            final var firstTransaction = new TransactionTemplate(new DataSourceTransactionManager(first.dataSource()));
            final var secondTransaction = new TransactionTemplate(new DataSourceTransactionManager(second.dataSource()));

            assertThrows(IllegalStateException.class, () -> firstTransaction.executeWithoutResult(outer -> {
                first.jdbc().update("insert into tx_records (label) values (?)", "first");
                secondTransaction.executeWithoutResult(inner ->
                        second.jdbc().update("insert into tx_records (label) values (?)", "second"));
                throw new IllegalStateException("두 번째 DB 커밋 후 실패");
            }));

            assertThat(first.count()).isZero();
            assertThat(second.count()).isEqualTo(1L);
        }
    }
}
