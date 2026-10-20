package com.techcourse.service;

import com.techcourse.config.DataSourceConfig;
import com.techcourse.dao.UserDao;
import com.techcourse.dao.UserHistoryDao;
import com.techcourse.domain.User;
import com.techcourse.domain.UserHistory;
import com.techcourse.support.jdbc.init.DatabasePopulatorUtils;
import com.interface21.dao.DataAccessException;
import com.interface21.jdbc.core.JdbcTemplate;
import com.interface21.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserServiceTest {

    private static final String ORIGINAL_PASSWORD = "password";

    private DataSource dataSource;
    private Connection databaseAnchor;
    private JdbcTemplate jdbcTemplate;
    private UserDao userDao;

    @BeforeEach
    void setUp() throws SQLException {
        this.dataSource = spy(DataSourceConfig.createDataSource("jdbc:h2:mem:user-service-" + UUID.randomUUID()));
        this.databaseAnchor = dataSource.getConnection();
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.userDao = new UserDao(jdbcTemplate);

        DatabasePopulatorUtils.execute(dataSource);
        final var user = new User("gugu", ORIGINAL_PASSWORD, "hkkang@woowahan.com");
        userDao.insert(user);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        final var retainedConnection = TransactionSynchronizationManager.getResource(dataSource);
        try {
            assertThat(retainedConnection).as("요청이 끝나면 연결 등록도 해제되어야 한다").isNull();
        } finally {
            if (retainedConnection != null) {
                TransactionSynchronizationManager.unbindResource(dataSource);
                retainedConnection.close();
            }
            databaseAnchor.close();
        }
    }

    @Test
    @DisplayName("조회·수정·이력을 연결 하나로 실행하고 변경자와 시각까지 저장한다")
    void testChangePassword() throws SQLException {
        final var userService = service(new UserHistoryDao(jdbcTemplate));
        final var newPassword = "qqqqq";
        final var createBy = "gugu";
        final var before = LocalDateTime.now();
        clearInvocations(dataSource);

        userService.changePassword(1L, newPassword, createBy);

        verify(dataSource, times(1)).getConnection();
        assertThat(userService.findById(1L).getPassword()).isEqualTo(newPassword);
        final var history = jdbcTemplate.queryForObject("select * from user_history where user_id = ?", row ->
                new SavedHistory(row.getLong("user_id"), row.getString("account"), row.getString("password"),
                        row.getString("email"), row.getString("created_by"),
                        row.getTimestamp("created_at").toLocalDateTime()), 1L);
        assertThat(history.userId()).isEqualTo(1L);
        assertThat(history.account()).isEqualTo("gugu");
        assertThat(history.password()).isEqualTo(newPassword);
        assertThat(history.email()).isEqualTo("hkkang@woowahan.com");
        assertThat(history.createdBy()).isEqualTo(createBy);
        assertThat(history.createdAt()).isBetween(before.minusSeconds(1), LocalDateTime.now().plusSeconds(1));
        assertThat(historyCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("이력 저장이 실패하면 원래 비밀번호를 정확히 복원한다")
    void testTransactionRollback() {
        final var userService = service(new MockUserHistoryDao(jdbcTemplate));

        assertThrows(DataAccessException.class,
                () -> userService.changePassword(1L, "newPassword", "gugu"));

        assertOriginalState();
    }

    @Test
    @DisplayName("이력 INSERT 뒤 실패해도 두 쓰기를 취소하고 다음 트랜잭션은 정상 실행한다")
    void failureAfterHistoryInsertRollsBackBothWritesAndDoesNotPoisonTheNextRequest() {
        final var failure = new DataAccessException("이력 INSERT 뒤 실패");
        final var historyDao = new UserHistoryDao(jdbcTemplate) {
            @Override
            public void log(final UserHistory history) {
                super.log(history);
                throw failure;
            }
        };

        assertThat(assertThrows(DataAccessException.class,
                () -> service(historyDao).changePassword(1L, "newPassword", "gugu"))).isSameAs(failure);
        assertOriginalState();
        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();

        service(new UserHistoryDao(jdbcTemplate)).changePassword(1L, "nextPassword", "operator");

        assertThat(userDao.findById(1L).getPassword()).isEqualTo("nextPassword");
        assertThat(historyCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("이력 테이블의 NOT NULL 제약 위반도 앞선 사용자 수정을 롤백한다")
    void databaseConstraintFailureInHistoryRollsBackThePassword() {
        assertThrows(DataAccessException.class,
                () -> service(new UserHistoryDao(jdbcTemplate)).changePassword(1L, "newPassword", null));

        assertOriginalState();
    }

    @Test
    @DisplayName("사용자 수정 SQL이 실패하면 비밀번호와 이력이 모두 원래 상태로 남는다")
    void databaseConstraintFailureInUserUpdateLeavesNoHistory() {
        assertThrows(DataAccessException.class,
                () -> service(new UserHistoryDao(jdbcTemplate)).changePassword(1L, null, "operator"));

        assertOriginalState();
    }

    @Test
    @DisplayName("연속 변경 시 이전 이력을 덮어쓰지 않고 각 변경 내용을 저장한다")
    void sequentialChangesKeepEachHistorySnapshot() {
        final var userService = service(new UserHistoryDao(jdbcTemplate));

        userService.changePassword(1L, "firstPassword", "firstOperator");
        userService.changePassword(1L, "secondPassword", "secondOperator");

        assertThat(userDao.findById(1L).getPassword()).isEqualTo("secondPassword");
        final var histories = jdbcTemplate.query("select password, created_by from user_history order by id",
                row -> row.getString("password") + ":" + row.getString("created_by"));
        assertThat(histories).containsExactly("firstPassword:firstOperator", "secondPassword:secondOperator");
    }

    @Test
    @DisplayName("같은 DataSource를 쓰는 동시 요청도 각 스레드의 트랜잭션으로 실행한다")
    void concurrentRequestsDoNotShareTheirTransactionConnection() throws Exception {
        userDao.insert(new User("other", "otherOriginal", "other@example.com"));
        final var connections = new ConcurrentHashMap<Long, Connection>();
        final var bothTransactionsActive = new CountDownLatch(2);
        final var historyDao = new UserHistoryDao(jdbcTemplate) {
            @Override
            public void log(final UserHistory history) {
                final var connection = TransactionSynchronizationManager.getResource(dataSource);
                assertThat(connection).isNotNull();
                connections.put(history.getUserId(), connection);
                bothTransactionsActive.countDown();
                try {
                    assertThat(bothTransactionsActive.await(5, TimeUnit.SECONDS))
                            .as("두 트랜잭션이 모두 열린 상태에서 연결 격리를 검사한다").isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("동시 요청 검사가 중단되었다", e);
                }
                assertThat(TransactionSynchronizationManager.getResource(dataSource)).isSameAs(connection);
                super.log(history);
            }
        };
        final var userService = service(historyDao);

        final var executor = Executors.newFixedThreadPool(2);
        try {
            final var first = executor.submit(() -> changeAndCheckUnbound(userService, 1L, "firstNew"));
            final var second = executor.submit(() -> changeAndCheckUnbound(userService, 2L, "secondNew"));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(connections).hasSize(2);
        assertThat(connections.get(1L)).isNotSameAs(connections.get(2L));
        assertThat(connections.get(1L).isClosed()).isTrue();
        assertThat(connections.get(2L).isClosed()).isTrue();
        assertThat(userDao.findById(1L).getPassword()).isEqualTo("firstNew");
        assertThat(userDao.findById(2L).getPassword()).isEqualTo("secondNew");
        assertThat(historyCount()).isEqualTo(2L);
    }

    private boolean changeAndCheckUnbound(final UserService userService, final long id, final String password) {
        userService.changePassword(id, password, "operator");
        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
        return true;
    }

    private UserService service(final UserHistoryDao historyDao) {
        return new TxUserService(new AppUserService(userDao, historyDao), dataSource);
    }

    private void assertOriginalState() {
        assertThat(userDao.findById(1L).getPassword()).isEqualTo(ORIGINAL_PASSWORD);
        assertThat(historyCount()).isZero();
    }

    private long historyCount() {
        return jdbcTemplate.queryForObject("select count(*) from user_history", row -> row.getLong(1));
    }

    private record SavedHistory(long userId, String account, String password, String email,
                                String createdBy, LocalDateTime createdAt) {}
}
