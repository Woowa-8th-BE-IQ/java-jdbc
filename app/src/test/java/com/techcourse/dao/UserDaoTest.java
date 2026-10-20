package com.techcourse.dao;

import com.techcourse.config.DataSourceConfig;
import com.techcourse.domain.User;
import com.techcourse.support.jdbc.init.DatabasePopulatorUtils;
import com.interface21.dao.DataAccessException;
import com.interface21.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserDaoTest {

    private UserDao userDao;
    private Connection databaseAnchor;

    @BeforeEach
    void setup() throws SQLException {
        final var dataSource = DataSourceConfig.createDataSource(
                "jdbc:h2:mem:user-dao-" + UUID.randomUUID());
        databaseAnchor = dataSource.getConnection();
        DatabasePopulatorUtils.execute(dataSource);

        userDao = new UserDao(new JdbcTemplate(dataSource));
        final var user = new User("gugu", "password", "hkkang@woowahan.com");
        userDao.insert(user);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        databaseAnchor.close();
    }

    @Test
    void findAll() {
        final var users = userDao.findAll();

        assertThat(users).extracting(User::getAccount).containsExactly("gugu");
    }

    @Test
    void findById() {
        final var user = userDao.findById(1L);

        assertThat(user.getAccount()).isEqualTo("gugu");
        assertThat(user.getId()).isEqualTo(1L);
        assertThat(user.getPassword()).isEqualTo("password");
        assertThat(user.getEmail()).isEqualTo("hkkang@woowahan.com");
    }

    @Test
    void findByAccount() {
        final var account = "gugu";
        final var user = userDao.findByAccount(account);

        assertThat(user.getAccount()).isEqualTo(account);
    }

    @Test
    void insert() {
        final var account = "insert-gugu";
        final var user = new User(account, "password", "hkkang@woowahan.com");
        userDao.insert(user);

        final var actual = userDao.findById(2L);

        assertThat(actual.getAccount()).isEqualTo(account);
        assertThat(actual.getPassword()).isEqualTo("password");
        assertThat(actual.getEmail()).isEqualTo("hkkang@woowahan.com");
    }

    @Test
    void update() {
        final var newPassword = "password99";
        final var user = userDao.findById(1L);
        user.changePassword(newPassword);

        userDao.update(user);

        final var actual = userDao.findById(1L);

        assertThat(actual.getPassword()).isEqualTo(newPassword);
        assertThat(actual.getAccount()).isEqualTo("gugu");
        assertThat(actual.getEmail()).isEqualTo("hkkang@woowahan.com");
    }

    @Test
    @DisplayName("존재하지 않는 ID와 계정 조회는 null을 반환한다")
    void missingUsersReturnNull() {
        assertThat(userDao.findById(999L)).isNull();
        assertThat(userDao.findByAccount("missing")).isNull();
    }

    @Test
    @DisplayName("SQL 형태의 문자열도 SQL 문법으로 실행하지 않고 값 그대로 저장·조회한다")
    void sqlLikeInputIsBoundAsAValue() {
        final var account = "intruder' OR '1'='1";
        userDao.insert(new User(account, "quote'password", "quoted@example.com"));

        final var user = userDao.findByAccount(account);

        assertThat(user.getAccount()).isEqualTo(account);
        assertThat(user.getPassword()).isEqualTo("quote'password");
        assertThat(userDao.findByAccount("' OR 1=1 --")).isNull();
        assertThat(userDao.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("INSERT 제약 위반은 SQL 원인을 보존하고 기존 데이터를 변경하지 않는다")
    void failedInsertPreservesExistingDataAndSqlCause() {
        final var failure = assertThrows(DataAccessException.class,
                () -> userDao.insert(new User("invalid", null, "invalid@example.com")));

        assertThat(failure.getCause()).isInstanceOf(SQLException.class);
        assertThat(userDao.findByAccount("invalid")).isNull();
        assertThat(userDao.findAll()).hasSize(1);
        assertThat(userDao.findById(1L).getPassword()).isEqualTo("password");
    }

    @Test
    @DisplayName("UPDATE 제약 위반 시 기존 행의 값은 유지된다")
    void failedUpdateDoesNotChangeThePersistedUser() {
        final var user = userDao.findById(1L);
        user.changePassword(null);

        assertThrows(DataAccessException.class, () -> userDao.update(user));

        assertThat(userDao.findById(1L).getPassword()).isEqualTo("password");
        assertThat(userDao.findAll()).hasSize(1);
    }
}
