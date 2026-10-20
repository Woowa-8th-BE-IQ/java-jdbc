# Step5: 로컬 트랜잭션에서 글로벌 트랜잭션까지

공식 1~4단계 이후에 진행하는 별도 학습이다. 실제 Spring 트랜잭션 관리자와 H2, Narayana를 사용한다.

## 실행

```bash
# 이 실습만 실행한다. Docker는 필요 없다.
./gradlew :study:step5Test --console=plain

# JDBC 라이브러리와 애플리케이션의 강화된 테스트도 함께 실행한다.
./gradlew :jdbc:test :app:test :study:step5Test --console=plain
```

기존 study의 미완성 학습 테스트와 구분할 수 있도록 `step5Test` 태스크를 제공한다.

## 1. 로컬 트랜잭션의 범위부터 관찰하기

[LocalTransactionTest](src/test/java/transaction/stage5/LocalTransactionTest.java)를 먼저 읽는다.

| 실험 | 실패 후 첫 번째 DB | 실패 후 두 번째 DB |
| --- | --- | --- |
| 한 DB에 여러 SQL 실행 | 모두 롤백 | 해당 없음 |
| 첫 번째 DB만 로컬 트랜잭션으로 관리 | 롤백 | 자동 커밋되어 남음 |
| 두 로컬 관리자를 중첩하고 내부 작업 커밋 후 실패 | 롤백 | 이미 커밋되어 남음 |

`DataSourceTransactionManager`는 자신에게 전달된 DataSource의 연결을 관리한다. 두 관리자를 연달아 호출하거나 중첩해도 두 DB를 위한 공통 커밋 결정이 생기는 것은 아니다.

## 2. PlatformTransactionManager가 추상화하는 것

[TransactionManagerAbstractionTest](src/test/java/transaction/stage5/TransactionManagerAbstractionTest.java)는 같은 `TransactionTemplate` 실행 코드에 JDBC 관리자와 JPA 관리자를 전달한다.

| 구현체 | 실제 트랜잭션 처리 대상 |
| --- | --- |
| `DataSourceTransactionManager` | JDBC Connection |
| `JpaTransactionManager` | JPA EntityManager의 트랜잭션 |
| `JtaTransactionManager` | JTA 제공자가 조정하는 글로벌 트랜잭션 |

공통 계약은 `getTransaction`, `commit`, `rollback`이다. 이 계약을 사용하는 코드는 트랜잭션 구현체에 덜 의존할 수 있다. SQL과 JPA 엔티티 처리, XA 자원 등록 등 각 기술에 필요한 구성은 여전히 다르다.

## 3. 두 DB의 결정을 JTA 조정자에게 맡기기

[JtaGlobalTransactionTest](src/test/java/transaction/stage5/JtaGlobalTransactionTest.java)에서 실제 Narayana 조정자와 서로 다른 H2 DB의 XAResource 두 개를 사용한다.

1. `JtaTransactionManager`가 글로벌 트랜잭션을 시작한다.
2. 두 XAResource를 현재 트랜잭션에 명시적으로 등록한다.
3. 각 DB에 SQL을 실행한다.
4. 조정자가 두 자원에 `prepare`를 요청한다.
5. 모두 준비되면 `commit(onePhase=false)`로 반영하고, 준비 단계가 실패하면 롤백한다.

정상 테스트는 **두 번의 prepare가 두 번의 commit보다 먼저 실행되는지**와 실제 DB 결과를 함께 검사한다. 실패 테스트는 업무 도중의 예외와 prepare 단계의 실패를 각각 다룬다. 관찰용 XAResource는 실제 H2 자원에 위임하며, prepare 실패 실험에서만 오류를 주입한다.

여기서 H2의 일반 DataSource를 두 개 만드는 것만으로는 XA에 자동 참여하지 않는다. [NarayanaTestSupport](src/test/java/transaction/stage5/NarayanaTestSupport.java)의 `getXAConnection()`과 `enlistResource()`가 연결되는 지점을 확인한다.

## 이번 실습의 범위

두 H2 메모리 DB를 대상으로 프로세스가 실행 중일 때의 공동 커밋·롤백을 검증한다. XA 지원 JMS도 글로벌 트랜잭션에 참여할 수 있지만, 이 실습에는 메시지 브로커를 추가하지 않았다. 프로세스 중단 후 복구에는 영속적인 트랜잭션 로그와 자원 복구 구성이 필요하며, 여기서 사용하는 메모리 DB는 그 복구 실험의 대상이 아니다.

## 참고

- [Spring 트랜잭션 추상화](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/strategies.html)
- [Spring JtaTransactionManager](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/jta/JtaTransactionManager.html)
- [Narayana 공식 가이드](https://www.narayana.io/docs/project/)
