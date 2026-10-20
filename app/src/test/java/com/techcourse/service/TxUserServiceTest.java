package com.techcourse.service;

import com.interface21.dao.DataAccessException;
import com.interface21.transaction.support.TransactionSynchronizationManager;
import com.techcourse.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TxUserServiceTest {

    @Test
    @DisplayName("조회와 저장을 위임할 때 불필요한 트랜잭션을 열지 않는다")
    void findAndSaveDelegateWithoutOpeningAConnection() throws Exception {
        final var fixture = fixture();
        final var user = new User(1L, "gugu", "password", "gugu@example.com");
        when(fixture.delegate().findById(1L)).thenReturn(user);

        assertThat(fixture.service().findById(1L)).isSameAs(user);
        fixture.service().save(user);

        verify(fixture.delegate()).findById(1L);
        verify(fixture.delegate()).save(user);
        verifyNoInteractions(fixture.dataSource(), fixture.connection());
    }

    @Test
    @DisplayName("업무 처리 전 연결을 등록하고 커밋·복원·등록 해제·종료 순서로 정리한다")
    void successBindsBeforeBusinessAndUnbindsBeforeClosing() throws Exception {
        final var fixture = fixture();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.getResource(fixture.dataSource()))
                    .isSameAs(fixture.connection());
            return null;
        }).when(fixture.delegate()).changePassword(1L, "new", "operator");
        doAnswer(invocation -> {
            assertUnbound(fixture);
            return null;
        }).when(fixture.connection()).close();

        fixture.service().changePassword(1L, "new", "operator");

        verify(fixture.dataSource(), times(1)).getConnection();
        final var order = inOrder(fixture.connection(), fixture.delegate());
        order.verify(fixture.connection()).getAutoCommit();
        order.verify(fixture.connection()).setAutoCommit(false);
        order.verify(fixture.delegate()).changePassword(1L, "new", "operator");
        order.verify(fixture.connection()).commit();
        order.verify(fixture.connection()).setAutoCommit(true);
        order.verify(fixture.connection()).close();
        verify(fixture.connection(), never()).rollback();
        assertUnbound(fixture);
    }

    @Test
    @DisplayName("RuntimeException과 Error 모두 원래 예외를 보존하고 롤백·정리한다")
    void runtimeAndErrorRollbackWithoutReplacingTheOriginalFailure() throws Exception {
        for (final var failure : new Throwable[]{new DataAccessException("business"), new AssertionError("business")}) {
            final var fixture = fixture();
            doThrow(failure).when(fixture.delegate()).changePassword(1L, "new", "operator");

            assertThat(assertThrows(Throwable.class,
                    () -> fixture.service().changePassword(1L, "new", "operator"))).isSameAs(failure);

            verify(fixture.connection()).rollback();
            verify(fixture.connection()).setAutoCommit(true);
            verify(fixture.connection()).close();
            verify(fixture.connection(), never()).commit();
            assertUnbound(fixture);
        }
    }

    @Test
    @DisplayName("롤백 실패 시 암묵적 커밋을 피하고 롤백·종료 오류를 원래 예외에 남긴다")
    void failedRollbackSkipsRestoreAndPreservesBothCleanupFailures() throws Exception {
        final var fixture = fixture();
        final var failure = new DataAccessException("business");
        final var rollbackFailure = new SQLException("rollback");
        final var closeFailure = new SQLException("close");
        doThrow(failure).when(fixture.delegate()).changePassword(1L, "new", "operator");
        doThrow(rollbackFailure).when(fixture.connection()).rollback();
        doThrow(closeFailure).when(fixture.connection()).close();

        final var thrown = assertThrows(DataAccessException.class,
                () -> fixture.service().changePassword(1L, "new", "operator"));

        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).containsExactly(rollbackFailure, closeFailure);
        verify(fixture.connection(), never()).setAutoCommit(true);
        verify(fixture.connection(), never()).commit();
        verify(fixture.connection()).close();
        assertUnbound(fixture);
    }

    @Test
    @DisplayName("롤백 후 설정 복원 실패가 업무 예외를 덮지 않는다")
    void failedRestoreAfterRollbackDoesNotHideTheBusinessFailure() throws Exception {
        final var fixture = fixture();
        final var failure = new DataAccessException("business");
        final var restoreFailure = new SQLException("restore");
        doThrow(failure).when(fixture.delegate()).changePassword(1L, "new", "operator");
        doThrow(restoreFailure).when(fixture.connection()).setAutoCommit(true);

        final var thrown = assertThrows(DataAccessException.class,
                () -> fixture.service().changePassword(1L, "new", "operator"));

        assertThat(thrown).isSameAs(failure);
        assertThat(thrown.getSuppressed()).containsExactly(restoreFailure);
        verify(fixture.connection()).rollback();
        verify(fixture.connection()).close();
        assertUnbound(fixture);
    }

    @Test
    @DisplayName("커밋 실패도 롤백하며 원래 SQLException을 원인으로 보존한다")
    void failedCommitRollsBackAndKeepsTheSqlCause() throws Exception {
        final var fixture = fixture();
        final var commitFailure = new SQLException("commit");
        doThrow(commitFailure).when(fixture.connection()).commit();

        final var thrown = assertThrows(DataAccessException.class,
                () -> fixture.service().changePassword(1L, "new", "operator"));

        assertThat(thrown.getCause()).isSameAs(commitFailure);
        final var order = inOrder(fixture.connection());
        order.verify(fixture.connection()).commit();
        order.verify(fixture.connection()).rollback();
        order.verify(fixture.connection()).setAutoCommit(true);
        order.verify(fixture.connection()).close();
        assertUnbound(fixture);
    }

    @Test
    @DisplayName("연결 획득·초기 상태 조회·설정 실패 시 업무를 실행하지 않고 획득한 자원만 정리한다")
    void initializationFailuresCloseAcquiredConnectionsWithoutLeavingABinding() throws Exception {
        for (final var stage : new String[]{"get", "readAutoCommit", "setAutoCommit"}) {
            final var fixture = fixture();
            final var failure = new SQLException(stage);
            if (stage.equals("get")) {
                when(fixture.dataSource().getConnection()).thenThrow(failure);
            } else if (stage.equals("readAutoCommit")) {
                when(fixture.connection().getAutoCommit()).thenThrow(failure);
            } else {
                doThrow(failure).when(fixture.connection()).setAutoCommit(false);
            }

            final var thrown = assertThrows(DataAccessException.class,
                    () -> fixture.service().changePassword(1L, "new", "operator"));

            assertThat(thrown.getCause()).isSameAs(failure);
            verify(fixture.delegate(), never()).changePassword(anyLong(), anyString(), anyString());
            verify(fixture.connection(), never()).commit();
            verify(fixture.connection(), never()).rollback();
            verify(fixture.connection(), times(stage.equals("get") ? 0 : 1)).close();
            assertUnbound(fixture);
        }
    }

    @Test
    @DisplayName("커밋 후 종료 실패를 알리되 이미 커밋한 데이터에 롤백을 시도하지 않는다")
    void closeFailureAfterCommitIsReportedWithoutRollback() throws Exception {
        final var fixture = fixture();
        final var failure = new SQLException("close");
        doThrow(failure).when(fixture.connection()).close();

        final var thrown = assertThrows(DataAccessException.class,
                () -> fixture.service().changePassword(1L, "new", "operator"));

        assertThat(thrown.getCause()).isSameAs(failure);
        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
        assertUnbound(fixture);
    }

    @Test
    @DisplayName("커밋 후 설정 복원 실패에도 등록을 해제하고 연결을 닫는다")
    void restoreFailureAfterCommitStillUnbindsAndCloses() throws Exception {
        final var fixture = fixture();
        final var failure = new SQLException("restore");
        doThrow(failure).when(fixture.connection()).setAutoCommit(true);

        final var thrown = assertThrows(DataAccessException.class,
                () -> fixture.service().changePassword(1L, "new", "operator"));

        assertThat(thrown.getCause()).isSameAs(failure);
        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
        verify(fixture.connection()).close();
        assertUnbound(fixture);
    }

    @Test
    @DisplayName("원래 autoCommit=false인 연결도 기존 설정으로 복원한다")
    void restoresTheOriginalAutoCommitValueRatherThanAlwaysEnablingIt() throws Exception {
        final var fixture = fixture();
        when(fixture.connection().getAutoCommit()).thenReturn(false);

        fixture.service().changePassword(1L, "new", "operator");

        final var order = inOrder(fixture.connection(), fixture.delegate());
        order.verify(fixture.connection()).getAutoCommit();
        order.verify(fixture.connection()).setAutoCommit(false);
        order.verify(fixture.delegate()).changePassword(1L, "new", "operator");
        order.verify(fixture.connection()).commit();
        order.verify(fixture.connection()).setAutoCommit(false);
        order.verify(fixture.connection()).close();
        verify(fixture.connection(), never()).setAutoCommit(true);
        assertUnbound(fixture);
    }

    @Test
    @DisplayName("이미 진행 중인 트랜잭션을 덮어쓰거나 그 연결을 종료하지 않는다")
    void rejectsNestedTransactionsWithoutTouchingTheExistingResource() throws Exception {
        final var fixture = fixture();
        final var existing = mock(Connection.class);
        TransactionSynchronizationManager.bindResource(fixture.dataSource(), existing);
        try {
            assertThrows(IllegalStateException.class,
                    () -> fixture.service().changePassword(1L, "new", "operator"));

            assertThat(TransactionSynchronizationManager.getResource(fixture.dataSource())).isSameAs(existing);
            verifyNoInteractions(fixture.dataSource(), existing, fixture.delegate());
        } finally {
            TransactionSynchronizationManager.unbindResource(fixture.dataSource());
        }
    }

    private Fixture fixture() throws SQLException {
        final var dataSource = mock(DataSource.class);
        final var connection = mock(Connection.class);
        final var delegate = mock(UserService.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new Fixture(dataSource, connection, delegate, new TxUserService(delegate, dataSource));
    }

    private void assertUnbound(final Fixture fixture) {
        assertThat(TransactionSynchronizationManager.getResource(fixture.dataSource())).isNull();
    }

    private record Fixture(DataSource dataSource, Connection connection, UserService delegate,
                           TxUserService service) {}
}
