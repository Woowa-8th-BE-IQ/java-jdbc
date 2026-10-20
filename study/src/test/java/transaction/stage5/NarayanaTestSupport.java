package transaction.stage5;

import com.arjuna.ats.arjuna.common.arjPropertyManager;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.transaction.jta.JtaTransactionManager;

import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

final class NarayanaTestSupport {

    private NarayanaTestSupport() {}

    static JtaTransactionManager transactionManager() {
        return RuntimeHolder.TRANSACTION_MANAGER;
    }

    private static final class RuntimeHolder {

        private static final JtaTransactionManager TRANSACTION_MANAGER = createTransactionManager();

        private static JtaTransactionManager createTransactionManager() {
            final var store = Path.of("build", "tmp", "step5-jta", UUID.randomUUID().toString()).toAbsolutePath();
            try {
                Files.createDirectories(store);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            // 테스트 프로세스별 로그를 분리한다. 이 메모리 DB 실습은 장애 복구 실험이 아니다.
            arjPropertyManager.getObjectStoreEnvironmentBean().setObjectStoreDir(store.toString());
            arjPropertyManager.getCoreEnvironmentBean()
                    .setProcessImplementationClassName("com.arjuna.ats.internal.arjuna.utils.UuidProcessId");
            arjPropertyManager.getCoordinatorEnvironmentBean().setTransactionStatusManagerEnable(false);

            return new JtaTransactionManager(
                    com.arjuna.ats.jta.UserTransaction.userTransaction(),
                    com.arjuna.ats.jta.TransactionManager.transactionManager());
        }
    }

    static void enlist(final XAResource... resources) {
        try {
            final var transaction = com.arjuna.ats.jta.TransactionManager.transactionManager().getTransaction();
            if (transaction == null) {
                throw new IllegalStateException("먼저 글로벌 트랜잭션을 시작해야 한다.");
            }
            for (final var resource : resources) {
                if (!transaction.enlistResource(resource)) {
                    throw new IllegalStateException("XA 자원 등록에 실패했다.");
                }
            }
        } catch (jakarta.transaction.RollbackException | jakarta.transaction.SystemException e) {
            throw new IllegalStateException("XA 자원을 글로벌 트랜잭션에 등록하지 못했다.", e);
        }
    }

    static final class XaDatabase implements AutoCloseable {

        private final JdbcDataSource dataSource = new JdbcDataSource();
        private final XAConnection xaConnection;
        private final Connection connection;
        private final TracedXaResource resource;

        XaDatabase(final String name, final List<String> events, final boolean failOnPrepare) throws SQLException {
            dataSource.setURL("jdbc:h2:mem:step5-" + UUID.randomUUID());
            xaConnection = dataSource.getXAConnection();
            connection = xaConnection.getConnection();
            resource = new TracedXaResource(name, xaConnection.getXAResource(), events, failOnPrepare);
            try (final var statement = connection.createStatement()) {
                statement.executeUpdate("create table events (id int primary key, message varchar(100))");
            }
        }

        XAResource resource() {
            return resource;
        }

        void insert() {
            try (final var statement = connection.prepareStatement("insert into events (id, message) values (?, ?)")) {
                statement.setInt(1, 1);
                statement.setString(2, "글로벌 트랜잭션으로 저장");
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("테스트 데이터 저장에 실패했다.", e);
            }
        }

        int count() throws SQLException {
            // 트랜잭션 종료 후 별도 연결에서 실제로 반영된 데이터를 확인한다.
            try (final var readConnection = dataSource.getConnection();
                 final var statement = readConnection.createStatement();
                 final var rows = statement.executeQuery("select count(*) from events")) {
                rows.next();
                return rows.getInt(1);
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.close();
            } finally {
                xaConnection.close();
            }
        }
    }

    /** 실제 H2 XAResource에 위임하면서, 코디네이터가 호출한 단계를 관찰한다. */
    private static final class TracedXaResource implements XAResource {

        private final String name;
        private final XAResource delegate;
        private final List<String> events;
        private final boolean failOnPrepare;

        private TracedXaResource(final String name, final XAResource delegate,
                                 final List<String> events, final boolean failOnPrepare) {
            this.name = name;
            this.delegate = delegate;
            this.events = events;
            this.failOnPrepare = failOnPrepare;
        }

        @Override
        public int prepare(final Xid xid) throws XAException {
            events.add(name + ".prepare");
            if (failOnPrepare) {
                throw new XAException(XAException.XAER_RMERR);
            }
            return delegate.prepare(xid);
        }

        @Override
        public void commit(final Xid xid, final boolean onePhase) throws XAException {
            events.add(name + ".commit(onePhase=" + onePhase + ")");
            delegate.commit(xid, onePhase);
        }

        @Override
        public void rollback(final Xid xid) throws XAException {
            events.add(name + ".rollback");
            delegate.rollback(xid);
        }

        @Override
        public void start(final Xid xid, final int flags) throws XAException {
            delegate.start(xid, flags);
        }

        @Override
        public void end(final Xid xid, final int flags) throws XAException {
            delegate.end(xid, flags);
        }

        @Override
        public boolean isSameRM(final XAResource other) throws XAException {
            return other instanceof TracedXaResource traced && delegate.isSameRM(traced.delegate);
        }

        @Override
        public Xid[] recover(final int flag) throws XAException {
            return delegate.recover(flag);
        }

        @Override
        public void forget(final Xid xid) throws XAException {
            delegate.forget(xid);
        }

        @Override
        public int getTransactionTimeout() throws XAException {
            return delegate.getTransactionTimeout();
        }

        @Override
        public boolean setTransactionTimeout(final int seconds) throws XAException {
            return delegate.setTransactionTimeout(seconds);
        }
    }
}
