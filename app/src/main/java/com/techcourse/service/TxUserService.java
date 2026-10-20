package com.techcourse.service;

import com.interface21.dao.DataAccessException;
import com.interface21.jdbc.datasource.DataSourceUtils;
import com.interface21.transaction.support.TransactionSynchronizationManager;
import com.techcourse.domain.User;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public class TxUserService implements UserService {

    private final UserService userService;
    private final DataSource dataSource;

    public TxUserService(final UserService userService, final DataSource dataSource) {
        this.userService = Objects.requireNonNull(userService, "userService must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public User findById(final long id) {
        return userService.findById(id);
    }

    @Override
    public void save(final User user) {
        userService.save(user);
    }

    @Override
    public void changePassword(final long id, final String newPassword, final String createdBy) {
        if (TransactionSynchronizationManager.getResource(dataSource) != null) {
            throw new IllegalStateException("A transaction is already bound to this DataSource");
        }

        final var connection = DataSourceUtils.getConnection(dataSource);
        try {
            Throwable failure = null;
            try {
                changePasswordInTransaction(connection, id, newPassword, createdBy);
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                throw e;
            } finally {
                releaseConnection(connection, failure);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Failed to change password in a transaction", e);
        }
    }

    private void changePasswordInTransaction(final Connection connection, final long id,
                                             final String newPassword, final String createdBy) throws SQLException {
        final var autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        TransactionSynchronizationManager.bindResource(dataSource, connection);

        try {
            try {
                userService.changePassword(id, newPassword, createdBy);
                connection.commit();
            } catch (SQLException | RuntimeException | Error e) {
                rollbackAndRestore(connection, autoCommit, e);
                throw e;
            }

            connection.setAutoCommit(autoCommit);
        } finally {
            // 등록을 해제한 뒤 반환해야 공유 중인 연결도 정상적으로 닫힌다.
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
    }

    private void rollbackAndRestore(final Connection connection, final boolean autoCommit, final Throwable cause) {
        try {
            connection.rollback();
            // 롤백에 실패하면 autoCommit 복원을 건너뛰어 암묵적인 커밋을 피한다.
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private void releaseConnection(final Connection connection, final Throwable failure) throws SQLException {
        try {
            DataSourceUtils.doReleaseConnection(connection, dataSource);
        } catch (SQLException | RuntimeException | Error e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                throw e;
            }
        }
    }
}
