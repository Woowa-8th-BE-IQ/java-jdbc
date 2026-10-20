package transaction.stage5;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import transaction.stage5.jpa.LearningRecord;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionManagerAbstractionTest {

    @Test
    @DisplayName("공통 트랜잭션 실행 코드에 JDBC 관리자를 전달하면 JDBC 작업이 커밋된다")
    void commonTransactionBoundaryUsesAJdbcManager() throws Exception {
        try (final var database = new LocalDatabase()) {
            database.createTable();
            execute(new DataSourceTransactionManager(database.dataSource()), () ->
                    database.jdbc().update("insert into tx_records (label) values (?)", "jdbc"));

            assertThat(database.count()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("동일한 트랜잭션 실행 코드에 JPA 관리자를 전달하면 JPA 작업이 커밋된다")
    void commonTransactionBoundaryUsesAJpaManager() throws Exception {
        try (final var database = new LocalDatabase()) {
            final var factory = entityManagerFactory(database);
            try {
                final var manager = new JpaTransactionManager(factory.getObject());
                final var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());

                execute(manager, () -> entityManager.persist(new LearningRecord("jpa")));

                assertThat(database.count()).isEqualTo(1L);
            } finally {
                factory.destroy();
            }
        }
    }

    @Test
    @DisplayName("JPA 관리자를 사용해도 공통 경계 안의 예외는 DB 변경을 롤백한다")
    void commonBoundaryRollsBackJpaChanges() throws Exception {
        try (final var database = new LocalDatabase()) {
            final var factory = entityManagerFactory(database);
            try {
                final var manager = new JpaTransactionManager(factory.getObject());
                final var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
                final var failure = new IllegalArgumentException("JPA 작업 후 실패");

                assertThat(assertThrows(IllegalArgumentException.class, () -> execute(manager, () -> {
                    entityManager.persist(new LearningRecord("jpa"));
                    entityManager.flush();
                    throw failure;
                }))).isSameAs(failure);

                assertThat(database.count()).isZero();
            } finally {
                factory.destroy();
            }
        }
    }

    private void execute(final PlatformTransactionManager transactionManager, final Runnable business) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> business.run());
    }

    private LocalContainerEntityManagerFactoryBean entityManagerFactory(final LocalDatabase database) {
        final var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(database.dataSource());
        factory.setPackagesToScan("transaction.stage5.jpa");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        final var properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();
        return factory;
    }
}
