package com.interface21.jdbc.core;

import com.interface21.dao.DataAccessException;
import com.interface21.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcTemplateTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet resultSet;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("setter 바인딩 후 SQL을 실행하고 영향받은 행 수를 반환한다")
    void updateBindsParametersBeforeExecutionAndReturnsAffectedRows() throws SQLException {
        final var sql = "update sample set name = ?, age = ? where id = ?";
        when(statement.executeUpdate()).thenReturn(2);

        final int affectedRows = jdbcTemplate.update(sql, preparedStatement -> {
            preparedStatement.setString(1, "Alice");
            preparedStatement.setNull(2, Types.INTEGER);
            preparedStatement.setLong(3, 10L);
        });

        assertThat(affectedRows).isEqualTo(2);
        verify(connection).prepareStatement(sql);
        final var order = inOrder(statement, connection);
        order.verify(statement).setString(1, "Alice");
        order.verify(statement).setNull(2, Types.INTEGER);
        order.verify(statement).setLong(3, 10L);
        order.verify(statement).executeUpdate();
        order.verify(statement).close();
        order.verify(connection).close();
    }

    @Test
    @DisplayName("여러 행을 순서대로 매핑하고 결과·Statement·연결 순서로 닫는다")
    void queryMapsRowsInOrderAndClosesResourcesAfterMapping() throws SQLException {
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("name")).thenReturn("Alice", "Bob");

        final var actual = jdbcTemplate.query("select name from sample", ignored -> {},
                row -> row.getString("name"));

        assertThat(actual).containsExactly("Alice", "Bob");
        final var order = inOrder(resultSet, statement, connection);
        order.verify(resultSet).next();
        order.verify(resultSet).getString("name");
        order.verify(resultSet).next();
        order.verify(resultSet).getString("name");
        order.verify(resultSet).next();
        order.verify(resultSet).close();
        order.verify(statement).close();
        order.verify(connection).close();
    }

    @Test
    @DisplayName("조회 결과가 없으면 매핑하지 않고 빈 목록을 반환한다")
    void queryReturnsEmptyListWithoutMappingWhenNoRowsExist() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        final var actual = jdbcTemplate.query("select name from sample", ignored -> {},
                row -> row.getString("name"));

        assertThat(actual).isEmpty();
        verify(resultSet, never()).getString("name");
        verifyQueryResourcesClosed();
    }

    @Test
    @DisplayName("단건 조회 결과가 없으면 null을 반환한다")
    void queryForObjectReturnsNullWhenNoRowsExist() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        final var actual = jdbcTemplate.queryForObject("select id from sample", ignored -> {},
                row -> row.getLong("id"));

        assertThat(actual).isNull();
        verifyQueryResourcesClosed();
    }

    @Test
    @DisplayName("단건 조회는 여러 행이 있어도 첫 번째 행만 읽고 반환한다")
    void queryForObjectReturnsFirstRowWhenSeveralRowsMatch() throws SQLException {
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getLong("id")).thenReturn(10L, 20L);

        final var actual = jdbcTemplate.queryForObject("select id from sample order by id", ignored -> {},
                row -> row.getLong("id"));

        assertThat(actual).isEqualTo(10L);
        verify(resultSet, times(1)).next();
        verify(resultSet, times(1)).getLong("id");
        verifyQueryResourcesClosed();
    }

    @Test
    @DisplayName("연결 획득 실패 시 SQL 원인을 보존하고 획득하지 않은 자원에 접근하지 않는다")
    void acquisitionFailurePreservesSqlException() throws SQLException {
        final var failure = new SQLException("connection unavailable");
        when(dataSource.getConnection()).thenThrow(failure);

        assertThatThrownBy(() -> jdbcTemplate.update("update sample set name = 'Alice'", ignored -> {}))
                .isInstanceOf(DataAccessException.class)
                .hasCause(failure);

        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).close();
    }

    @Test
    @DisplayName("Statement 생성 실패 시 이미 획득한 연결을 닫는다")
    void preparationFailureClosesAcquiredConnection() throws SQLException {
        final var failure = new SQLException("cannot prepare");
        when(connection.prepareStatement(anyString())).thenThrow(failure);

        assertThatThrownBy(() -> jdbcTemplate.update("invalid sql", ignored -> {}))
                .isInstanceOf(DataAccessException.class)
                .hasCause(failure);

        verify(connection).close();
        verify(statement, never()).close();
    }

    @Test
    @DisplayName("바인딩 실패 시 SQL을 실행하지 않고 자원을 정리한다")
    void parameterFailureClosesResourcesAndPreventsExecution() throws SQLException {
        final var failure = new SQLException("cannot bind");

        assertThatThrownBy(() -> jdbcTemplate.update("update sample set name = ?", preparedStatement -> {
            throw failure;
        })).isInstanceOf(DataAccessException.class).hasCause(failure);

        verify(statement, never()).executeUpdate();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    @DisplayName("수정 SQL 실패 시 원인을 보존하고 Statement와 연결을 닫는다")
    void updateFailurePreservesSqlExceptionAndClosesResources() throws SQLException {
        final var failure = new SQLException("update rejected");
        when(statement.executeUpdate()).thenThrow(failure);

        assertThatThrownBy(() -> jdbcTemplate.update("update sample set name = ?", ignored -> {}))
                .isInstanceOf(DataAccessException.class)
                .hasCause(failure);

        verify(statement).close();
        verify(connection).close();
    }

    @Test
    @DisplayName("조회 SQL 실패 시 아직 얻지 않은 ResultSet은 닫지 않는다")
    void queryExecutionFailureClosesStatementAndConnection() throws SQLException {
        final var failure = new SQLException("query rejected");
        when(statement.executeQuery()).thenThrow(failure);

        assertThatThrownBy(() -> jdbcTemplate.query("select name from sample", ignored -> {},
                row -> row.getString("name")))
                .isInstanceOf(DataAccessException.class)
                .hasCause(failure);

        verify(resultSet, never()).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    @DisplayName("커서 이동 실패 시 조회 자원을 모두 닫는다")
    void cursorFailureClosesAllQueryResources() throws SQLException {
        final var failure = new SQLException("cannot read next row");
        when(resultSet.next()).thenThrow(failure);

        assertThatThrownBy(() -> jdbcTemplate.query("select name from sample", ignored -> {},
                row -> row.getString("name")))
                .isInstanceOf(DataAccessException.class)
                .hasCause(failure);

        verifyQueryResourcesClosed();
    }

    @Test
    @DisplayName("매핑 중 SQL 예외의 원인을 보존하고 조회 자원을 닫는다")
    void mappingSqlFailurePreservesCauseAndClosesAllQueryResources() throws SQLException {
        final var failure = new SQLException("cannot map row");
        when(resultSet.next()).thenReturn(true);

        assertThatThrownBy(() -> jdbcTemplate.query("select name from sample", ignored -> {}, row -> {
            throw failure;
        })).isInstanceOf(DataAccessException.class).hasCause(failure);

        verifyQueryResourcesClosed();
    }

    @Test
    @DisplayName("매핑 중 런타임 예외를 바꾸지 않고 자원을 닫은 뒤 전달한다")
    void mappingRuntimeFailurePropagatesUnchangedAfterCleanup() throws SQLException {
        final var failure = new IllegalStateException("invalid mapped value");
        when(resultSet.next()).thenReturn(true);

        assertThatThrownBy(() -> jdbcTemplate.queryForObject("select name from sample", ignored -> {}, row -> {
            throw failure;
        })).isSameAs(failure);

        verifyQueryResourcesClosed();
    }

    @Test
    @DisplayName("Statement 종료 실패도 보고하면서 나머지 연결을 닫는다")
    void closeFailureIsReportedAndRemainingResourcesStillClose() throws SQLException {
        final var failure = new SQLException("cannot close statement");
        doThrow(failure).when(statement).close();

        assertThatThrownBy(() -> jdbcTemplate.update("update sample set name = 'Alice'", ignored -> {}))
                .isInstanceOf(DataAccessException.class)
                .hasCause(failure);

        verify(connection).close();
    }

    @Test
    @DisplayName("종료 중 추가 실패는 원래 SQL 예외의 suppressed로 보존한다")
    void cleanupFailuresDoNotReplaceOriginalSqlFailure() throws SQLException {
        final var failure = new SQLException("update rejected");
        final var statementCloseFailure = new SQLException("cannot close statement");
        final var connectionCloseFailure = new SQLException("cannot close connection");
        when(statement.executeUpdate()).thenThrow(failure);
        doThrow(statementCloseFailure).when(statement).close();
        doThrow(connectionCloseFailure).when(connection).close();

        assertThatThrownBy(() -> jdbcTemplate.update("update sample set name = 'Alice'", ignored -> {}))
                .isInstanceOf(DataAccessException.class)
                .hasCause(failure);

        assertThat(failure.getSuppressed()).containsExactly(statementCloseFailure, connectionCloseFailure);
    }

    @Test
    @DisplayName("가변 인자의 순서를 지켜 SQL NULL을 포함한 값을 실행 전에 바인딩한다")
    void varargsBindValuesAndNullInPlaceholderOrderBeforeExecution() throws SQLException {
        when(statement.executeUpdate()).thenReturn(1);

        assertThat(jdbcTemplate.update("update sample set name = ?, age = ? where id = ?",
                "Alice", null, 10L)).isEqualTo(1);

        final var order = inOrder(statement);
        order.verify(statement).setObject(1, "Alice");
        order.verify(statement).setObject(2, null);
        order.verify(statement).setObject(3, 10L);
        order.verify(statement).executeUpdate();
    }

    @Test
    @DisplayName("인자가 없는 조회는 값을 바인딩하지 않는다")
    void noParametersSkipsBinding() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        assertThat(jdbcTemplate.query("select name from sample", row -> row.getString("name"))).isEmpty();

        verify(statement, never()).setObject(anyInt(), org.mockito.ArgumentMatchers.any());
        verifyQueryResourcesClosed();
    }

    @Test
    @DisplayName("SQL NULL 한 개는 Object null로 전달할 수 있다")
    void oneSqlNullIsAParameterRatherThanANullArray() throws SQLException {
        jdbcTemplate.update("update sample set name = ?", (Object) null);

        verify(statement).setObject(1, null);
        verify(statement).executeUpdate();
    }

    @Test
    @DisplayName("잘못된 SQL·콜백·인자 배열은 연결 획득 전에 거부한다")
    void invalidInputsAreRejectedBeforeAnyDatabaseInteraction() {
        final RowMapper<String> mapper = row -> row.getString("name");
        final PreparedStatementSetter setter = ignored -> {};
        final var sql = "select name from sample";
        final List<Runnable> invalidCalls = List.of(
                () -> new JdbcTemplate(null),
                () -> jdbcTemplate.update(null, setter),
                () -> jdbcTemplate.update(null),
                () -> jdbcTemplate.query(null, setter, mapper),
                () -> jdbcTemplate.queryForObject(null, setter, mapper),
                () -> jdbcTemplate.update(sql, (PreparedStatementSetter) null),
                () -> jdbcTemplate.query(sql, (PreparedStatementSetter) null, mapper),
                () -> jdbcTemplate.queryForObject(sql, (PreparedStatementSetter) null, mapper),
                () -> jdbcTemplate.query(sql, (RowMapper<String>) null),
                () -> jdbcTemplate.queryForObject(sql, (RowMapper<String>) null),
                () -> jdbcTemplate.update(sql, (Object[]) null),
                () -> jdbcTemplate.query(sql, mapper, (Object[]) null),
                () -> jdbcTemplate.queryForObject(sql, mapper, (Object[]) null),
                () -> jdbcTemplate.update((Connection) null, sql),
                () -> jdbcTemplate.query((Connection) null, sql, mapper),
                () -> jdbcTemplate.queryForObject((Connection) null, sql, mapper));

        invalidCalls.forEach(call -> assertThatThrownBy(call::run).isInstanceOf(NullPointerException.class));

        verifyNoInteractions(dataSource, connection, statement, resultSet);
    }

    @Test
    @DisplayName("호출자가 전달한 연결은 모든 실행 API에서 닫거나 커밋하지 않는다")
    void borrowedConnectionsRemainOwnedByTheCaller() throws SQLException {
        final RowMapper<String> mapper = row -> row.getString("name");
        when(resultSet.next()).thenReturn(false);

        jdbcTemplate.update(connection, "update sample set name = ?", "Alice");
        assertThat(jdbcTemplate.query(connection, "select name from sample", mapper)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(connection, "select name from sample", mapper)).isNull();

        verifyNoInteractions(dataSource);
        verify(statement, times(3)).close();
        verify(resultSet, times(2)).close();
        verify(connection, never()).close();
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    @DisplayName("호출자가 빌려준 연결에서는 SQL·매핑 실패 후에도 연결을 닫지 않는다")
    void borrowedConnectionFailuresStillCloseStatementAndResultsOnly() throws SQLException {
        final var sqlFailure = new SQLException("execution failed");
        when(statement.executeUpdate()).thenThrow(sqlFailure);
        assertThatThrownBy(() -> jdbcTemplate.update(connection, "update sample set name = ?", "Alice"))
                .isInstanceOf(DataAccessException.class).hasCause(sqlFailure);

        final var mappingFailure = new IllegalArgumentException("invalid row");
        when(resultSet.next()).thenReturn(true);
        assertThatThrownBy(() -> jdbcTemplate.query(connection, "select name from sample", row -> {
            throw mappingFailure;
        })).isSameAs(mappingFailure);

        verifyNoInteractions(dataSource);
        verify(resultSet).close();
        verify(statement, times(2)).close();
        verify(connection, never()).close();
    }

    @Test
    @DisplayName("등록된 트랜잭션 연결은 유지하고 등록 해제 뒤의 일반 연결은 닫는다")
    void boundConnectionIsReusedAndOnlyReleasedAfterTheTransactionEnds() throws SQLException {
        TransactionSynchronizationManager.bindResource(dataSource, connection);
        try {
            jdbcTemplate.update("update sample set name = ?", "Alice");
            assertThat(TransactionSynchronizationManager.getResource(dataSource)).isSameAs(connection);
            verifyNoInteractions(dataSource);
            verify(connection, never()).close();
        } finally {
            TransactionSynchronizationManager.unbindResource(dataSource);
        }

        jdbcTemplate.update("update sample set name = ?", "Bob");

        verify(dataSource).getConnection();
        verify(connection).close();
        assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
    }

    @Test
    @DisplayName("실행 성공 후 연결 종료 실패도 SQL 원인과 함께 보고한다")
    void connectionCloseFailureAfterSuccessfulExecutionIsNotIgnored() throws SQLException {
        final var closeFailure = new SQLException("connection close failed");
        doThrow(closeFailure).when(connection).close();

        assertThatThrownBy(() -> jdbcTemplate.update("update sample set name = ?", "Alice"))
                .isInstanceOf(DataAccessException.class).hasCause(closeFailure);

        verify(statement).executeUpdate();
        verify(statement).close();
    }

    private void verifyQueryResourcesClosed() throws SQLException {
        final var order = inOrder(resultSet, statement, connection);
        order.verify(resultSet).close();
        order.verify(statement).close();
        order.verify(connection).close();
    }
}
