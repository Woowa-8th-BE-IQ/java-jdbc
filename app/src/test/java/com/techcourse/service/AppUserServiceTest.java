package com.techcourse.service;

import com.techcourse.dao.UserDao;
import com.techcourse.dao.UserHistoryDao;
import com.techcourse.domain.User;
import com.techcourse.domain.UserHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppUserServiceTest {

    @Test
    @DisplayName("조회와 저장은 전달받은 사용자 DAO에 위임한다")
    void delegatesFindAndSaveToItsDao() {
        final var userDao = mock(UserDao.class);
        final var historyDao = mock(UserHistoryDao.class);
        final var user = new User(1L, "gugu", "password", "gugu@example.com");
        when(userDao.findById(1L)).thenReturn(user);
        final var service = new AppUserService(userDao, historyDao);

        assertThat(service.findById(1L)).isSameAs(user);
        service.save(user);

        verify(userDao).findById(1L);
        verify(userDao).insert(same(user));
        verifyNoInteractions(historyDao);
    }

    @Test
    @DisplayName("사용자를 수정한 뒤 변경된 비밀번호와 변경자로 이력을 만든다")
    void updatesTheUserBeforeRecordingItsChangedValues() {
        final var userDao = mock(UserDao.class);
        final var historyDao = mock(UserHistoryDao.class);
        final var user = new User(17L, "gugu", "old", "gugu@example.com");
        when(userDao.findById(17L)).thenReturn(user);

        new AppUserService(userDao, historyDao).changePassword(17L, "new", "operator");

        final var history = ArgumentCaptor.forClass(UserHistory.class);
        final var order = inOrder(userDao, historyDao);
        order.verify(userDao).findById(17L);
        order.verify(userDao).update(same(user));
        order.verify(historyDao).log(history.capture());
        assertThat(user.getPassword()).isEqualTo("new");
        assertThat(history.getValue().getUserId()).isEqualTo(17L);
        assertThat(history.getValue().getPassword()).isEqualTo("new");
        assertThat(history.getValue().getCreateBy()).isEqualTo("operator");
    }
}
