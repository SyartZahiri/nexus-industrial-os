# M1-A Fundament und Verträge – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein baubares Maven-Monorepo mit den verbindlichen Verträgen (Zustände, Ereignisse, Ports), dem Datenbankschema samt Stammdaten und einem idempotenten Ereignisspeicher, auf dem FlowPilot und SmartFactory danach parallel entwickelt werden.

**Architecture:** Ein Spring-Boot-Prozess (`platform/app`) verdrahtet die Module. `platform/contracts` ist reines Java ohne Spring und die einzige gemeinsame Abhängigkeit von `FlowPilot` und `SmartFactory`. Persistenz über `JdbcClient` mit handgeschriebenem SQL (kein JPA), Schema über Flyway, Datenbank lokal und im Test als Embedded PostgreSQL.

**Tech Stack:** Java (Release 21, gebaut mit JDK 25), Maven 3.9.16 über Wrapper, Spring Boot 4.1.2, PostgreSQL 18 (`io.zonky.test:embedded-postgres:2.2.2`, Binaries-BOM `18.6.0`), Flyway (Version aus Spring Boot), springdoc-openapi `3.2.0`, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md`

**Einordnung:** M1 aus der Spec ist in vier Pläne geteilt. Dieser Plan (M1-A) kommt zuerst und läuft sequenziell. Danach folgen M1-B FlowPilot und M1-C SmartFactory parallel in getrennten Worktrees, zuletzt M1-D Simulation, OPC UA und Ende-zu-Ende-Integration. B bis D werden geschrieben, sobald A gemergt ist, damit sie gegen den echten Vertragscode argumentieren.

## Global Constraints

- Java-Release 21 (`maven.compiler.release=21`), Build mit dem installierten JDK 25.
- Basis-Package `io.nexus`. Artefakte: `nexus-contracts`, `nexus-sim`, `nexus-app`, `flowpilot`, `smartfactory`.
- `FlowPilot` und `SmartFactory` hängen nur von `nexus-contracts` ab, nie voneinander. `nexus-contracts` hat keine Spring-Abhängigkeit.
- Bestehende IDs unverändert: `CNC-01`..`CNC-04`, `ROB-01`, `ROB-02`, `QC-01`, `CV-01`..`CV-04`, `CH-1`..`CH-6`, Produkte `GH200`, `AW50`, `FP12`, `MT7`, Prozesse `DREHEN`, `FRAESEN`, `MONTAGE`, `PRUEFEN`.
- Kein `Math.random`, kein `System.currentTimeMillis`/`Instant.now` in Fachlogik. Zeit kommt aus `SimTime`, Zufall aus einem Seed-RNG.
- REST unter `/api/v1`, Fehler als RFC-7807-Problem-JSON: 400 Validierung, 404 unbekannte ID, 409 ungültiger Zustandsübergang oder Versionskonflikt.
- Alle Befehle in der Shell aus dem Repo-Wurzelordner `Industire/`. Unter Windows `.\mvnw.cmd`, sonst `./mvnw`.
- Commits enden mit `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- `FactoryTwin/` wird in diesem Plan nicht verändert (nur gelesen, um den Wegegraphen zu exportieren).

## Review Focus

1. **Ereignis in zurückgerollter Transaktion.** Schlägt die Geschäftstransaktion fehl, darf weder ein Ereignis gespeichert noch ein Verbraucher aufgerufen werden. Test in Task 5.
2. **Doppelte Zustellung.** Dasselbe Ereignis zweimal zugestellt wirkt genau einmal. Test in Task 5.
3. **Verbraucher wirft.** Ein fehlerhafter Verbraucher darf weder die anderen Verbraucher blockieren noch das Ereignis als verarbeitet markieren. Test in Task 5.
4. **Überbuchung auf Datenbankebene.** `reserved > on_hand` und Bestand über Lagerplatzkapazität werden von der Datenbank abgelehnt, auch wenn die Anwendung fehlerhaft wäre. Tests in Task 4.
5. **Zwei aktive Transporte für ein AGV.** Die Datenbank lehnt den zweiten ab. Test in Task 4.

---

### Task 1: Maven-Monorepo mit Wrapper

**Files:**
- Create: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- Create: `platform/contracts/pom.xml`, `platform/sim/pom.xml`, `platform/app/pom.xml`, `FlowPilot/pom.xml`, `SmartFactory/pom.xml`
- Create: `platform/app/src/main/java/io/nexus/app/NexusApplication.java`
- Create: `platform/app/src/main/resources/application.yml`
- Test: `platform/app/src/test/java/io/nexus/app/ContextLoadsTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces: Modul-Koordinaten `io.nexus:{nexus-contracts,nexus-sim,nexus-app,flowpilot,smartfactory}:0.1.0-SNAPSHOT`. Alle fünf Module existieren ab jetzt, damit spätere parallele Zweige den Parent-POM nicht anfassen müssen.

- [ ] **Step 1: Gerüst von Spring Initializr holen** (liefert den Maven Wrapper und die für Boot 4 korrekten Starter-Namen)

```bash
curl -s -o "$TMP/nexus-init.zip" "https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=4.1.2&javaVersion=21&groupId=io.nexus&artifactId=nexus-app&name=nexus-app&packageName=io.nexus.app&dependencies=web,websocket,jdbc,flyway,postgresql,validation,actuator"
mkdir -p "$TMP/nexus-init" && unzip -q -o "$TMP/nexus-init.zip" -d "$TMP/nexus-init"
cp "$TMP/nexus-init/mvnw" "$TMP/nexus-init/mvnw.cmd" . && mkdir -p .mvn && cp -r "$TMP/nexus-init/.mvn/wrapper" .mvn/
cat "$TMP/nexus-init/pom.xml"
```

Aus der ausgegebenen `pom.xml` den `<dependencies>`-Block notieren. Er wird in Step 3 unverändert in `platform/app/pom.xml` übernommen.

- [ ] **Step 2: Parent-POM schreiben** – `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.2</version>
    <relativePath/>
  </parent>
  <groupId>io.nexus</groupId>
  <artifactId>nexus</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>NEXUS Industrial OS</name>

  <properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <modules>
    <module>platform/contracts</module>
    <module>platform/sim</module>
    <module>FlowPilot</module>
    <module>SmartFactory</module>
    <module>platform/app</module>
  </modules>

  <dependencyManagement>
    <dependencies>
      <dependency><groupId>io.nexus</groupId><artifactId>nexus-contracts</artifactId><version>${project.version}</version></dependency>
      <dependency><groupId>io.nexus</groupId><artifactId>nexus-sim</artifactId><version>${project.version}</version></dependency>
      <dependency><groupId>io.nexus</groupId><artifactId>flowpilot</artifactId><version>${project.version}</version></dependency>
      <dependency><groupId>io.nexus</groupId><artifactId>smartfactory</artifactId><version>${project.version}</version></dependency>
      <dependency>
        <groupId>io.zonky.test.postgres</groupId><artifactId>embedded-postgres-binaries-bom</artifactId>
        <version>18.6.0</version><type>pom</type><scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

- [ ] **Step 3: Modul-POMs schreiben**

`platform/contracts/pom.xml` (für `platform/sim/pom.xml` identisch mit `artifactId` `nexus-sim` und zusätzlicher Abhängigkeit auf `nexus-contracts`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.nexus</groupId><artifactId>nexus</artifactId><version>0.1.0-SNAPSHOT</version><relativePath>../../pom.xml</relativePath></parent>
  <artifactId>nexus-contracts</artifactId>
  <dependencies>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

`FlowPilot/pom.xml` (für `SmartFactory/pom.xml` identisch mit `artifactId` `smartfactory`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.nexus</groupId><artifactId>nexus</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>flowpilot</artifactId>
  <dependencies>
    <dependency><groupId>io.nexus</groupId><artifactId>nexus-contracts</artifactId></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
  </dependencies>
</project>
```

`platform/app/pom.xml`: Parent wie bei `contracts` (`relativePath` `../../pom.xml`), `artifactId` `nexus-app`. Abhängigkeiten: der in Step 1 notierte Block aus dem Initializr, dazu:

```xml
<dependency><groupId>io.nexus</groupId><artifactId>nexus-contracts</artifactId></dependency>
<dependency><groupId>io.nexus</groupId><artifactId>nexus-sim</artifactId></dependency>
<dependency><groupId>io.nexus</groupId><artifactId>flowpilot</artifactId></dependency>
<dependency><groupId>io.nexus</groupId><artifactId>smartfactory</artifactId></dependency>
<dependency><groupId>io.zonky.test</groupId><artifactId>embedded-postgres</artifactId><version>2.2.2</version></dependency>
<dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>3.2.0</version></dependency>
```

und im `<build>` das `spring-boot-maven-plugin` wie im Initializr-POM.

- [ ] **Step 4: Anwendung und Embedded-PostgreSQL-Profil**

`platform/app/src/main/java/io/nexus/app/NexusApplication.java`:

```java
package io.nexus.app;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(scanBasePackages = "io.nexus")
public class NexusApplication {
  public static void main(String[] args) {
    SpringApplication.run(NexusApplication.class, args);
  }

  /** Real PostgreSQL without an installation; used for local dev and tests. Data is discarded on shutdown. */
  @Configuration
  @Profile("embedded")
  static class EmbeddedDatabase {
    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
      return EmbeddedPostgres.builder().start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres pg) {
      return pg.getPostgresDatabase();
    }
  }
}
```

`platform/app/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: nexus
  profiles:
    default: embedded
  datasource:
    url: ${NEXUS_DB_URL:jdbc:postgresql://localhost:5432/nexus}
    username: ${NEXUS_DB_USER:nexus}
    password: ${NEXUS_DB_PASSWORD:nexus}
nexus:
  sim:
    seed: 4711
```

Ohne gesetztes Profil startet die Anwendung mit Embedded PostgreSQL. Docker Compose setzt später `SPRING_PROFILES_ACTIVE=postgres` und nutzt die `datasource`-Werte.

- [ ] **Step 5: Test schreiben** – `platform/app/src/test/java/io/nexus/app/ContextLoadsTest.java`

```java
package io.nexus.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ContextLoadsTest {
  @Autowired JdbcClient jdbc;

  @Test
  void startsAgainstRealPostgres() {
    String version = jdbc.sql("select version()").query(String.class).single();
    assertThat(version).startsWith("PostgreSQL 18");
  }
}
```

- [ ] **Step 6: Bauen und testen**

Run: `.\mvnw.cmd -q verify`
Expected: `BUILD SUCCESS`, 1 Test grün. Der erste Lauf lädt Maven, Abhängigkeiten und die PostgreSQL-Binaries herunter (einige Minuten).

Falls Flyway beim Start meldet, dass keine Migrationen gefunden wurden und deshalb abbricht: `spring.flyway.fail-on-missing-locations` nicht setzen, stattdessen ist das in Task 4 mit der ersten Migration erledigt. Bis dahin in `application.yml` `spring.flyway.enabled: false` setzen und in Task 4 wieder entfernen.

- [ ] **Step 7: Commit**

```bash
git add pom.xml mvnw mvnw.cmd .mvn platform FlowPilot SmartFactory
git commit -m "build: Maven-Monorepo mit Spring Boot 4.1 und Embedded PostgreSQL"
```

---

### Task 2: Zustände und Zustandsautomaten

**Files:**
- Create: `platform/contracts/src/main/java/io/nexus/contracts/state/Transitions.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/state/IllegalTransitionException.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/state/OrderStatus.java`, `TransportStatus.java`, `MachineStatus.java`, `AgvStatus.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/Domain.java`
- Test: `platform/contracts/src/test/java/io/nexus/contracts/state/TransitionsTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces:
  - `Transitions<S extends Enum<S>>` mit `boolean can(S from, S to)` und `S move(String entityId, S from, S to)` (wirft `IllegalTransitionException`).
  - `OrderStatus.RULES`, `TransportStatus.RULES`, `MachineStatus.RULES`, `AgvStatus.RULES` (jeweils `Transitions<…>`), `boolean terminal()` auf `OrderStatus` und `TransportStatus`, `TransportStatus.ACTIVE` (`Set<TransportStatus>`).
  - `Domain.ProcessType { DREHEN, FRAESEN, MONTAGE, PRUEFEN }`, `Domain.Priority { NORMAL, HOCH, EILIG }`, `Domain.TransportKind { SUPPLY, WIP, PUTAWAY }`, `Domain.MachineKind { CNC, ROBOT, QC }`, `Domain.Severity { INFO, SUCCESS, WARNING, ERROR }`.

- [ ] **Step 1: Failing Test schreiben** – `TransitionsTest.java`

```java
package io.nexus.contracts.state;

import static io.nexus.contracts.state.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class TransitionsTest {
  @Test
  void orderHappyPath() {
    OrderStatus s = CREATED;
    for (OrderStatus next : new OrderStatus[] {MATERIAL_PENDING, RELEASED, IN_PROGRESS, COMPLETED}) {
      s = OrderStatus.RULES.move("PO-1", s, next);
    }
    assertThat(s).isEqualTo(COMPLETED);
  }

  @Test
  void orderShortageLoopsBackToMaterialPending() {
    assertThat(OrderStatus.RULES.can(MATERIAL_PENDING, SHORTAGE)).isTrue();
    assertThat(OrderStatus.RULES.can(SHORTAGE, MATERIAL_PENDING)).isTrue();
    assertThat(OrderStatus.RULES.can(SHORTAGE, RELEASED)).isFalse();
  }

  @Test
  void everyNonTerminalOrderStateCanBeCancelledAndTerminalStatesAreDeadEnds() {
    for (OrderStatus s : OrderStatus.values()) {
      assertThat(OrderStatus.RULES.can(s, CANCELLED)).as(s.name()).isEqualTo(!s.terminal());
      if (s.terminal()) {
        for (OrderStatus t : OrderStatus.values()) assertThat(OrderStatus.RULES.can(s, t)).isFalse();
      }
    }
  }

  @Test
  void illegalMoveNamesEntityAndBothStates() {
    assertThatThrownBy(() -> OrderStatus.RULES.move("PO-7", CREATED, COMPLETED))
        .isInstanceOf(IllegalTransitionException.class)
        .hasMessageContaining("PO-7").hasMessageContaining("CREATED").hasMessageContaining("COMPLETED");
  }

  @Test
  void transportFailsOnlyWhileLoadedAndIsReassignedFromFailed() {
    var r = TransportStatus.RULES;
    for (TransportStatus s : EnumSet.of(TransportStatus.LOADING, TransportStatus.IN_TRANSIT, TransportStatus.UNLOADING)) {
      assertThat(r.can(s, TransportStatus.FAILED)).isTrue();
      assertThat(r.can(s, TransportStatus.CANCELLED)).isFalse();
    }
    assertThat(r.can(TransportStatus.FAILED, TransportStatus.ASSIGNED)).isTrue();
    // an unloaded AGV failing puts the order back into the queue
    assertThat(r.can(TransportStatus.ASSIGNED, TransportStatus.CREATED)).isTrue();
    assertThat(r.can(TransportStatus.TO_PICKUP, TransportStatus.CREATED)).isTrue();
    assertThat(r.can(TransportStatus.IN_TRANSIT, TransportStatus.CREATED)).isFalse();
  }

  @Test
  void activeTransportStatesAreExactlyTheOnesHoldingAnAgv() {
    assertThat(TransportStatus.ACTIVE).containsExactlyInAnyOrder(
        TransportStatus.ASSIGNED, TransportStatus.TO_PICKUP, TransportStatus.LOADING,
        TransportStatus.IN_TRANSIT, TransportStatus.UNLOADING);
  }

  @Test
  void machineMustBeRepairedBeforeRunningAgain() {
    var r = MachineStatus.RULES;
    assertThat(r.can(MachineStatus.RUNNING, MachineStatus.FAULT)).isTrue();
    assertThat(r.can(MachineStatus.FAULT, MachineStatus.RUNNING)).isFalse();
    assertThat(r.can(MachineStatus.FAULT, MachineStatus.REPAIR)).isTrue();
    assertThat(r.can(MachineStatus.REPAIR, MachineStatus.RUNNING)).isTrue();
    assertThat(r.can(MachineStatus.RUNNING, MachineStatus.MAINTENANCE)).isFalse();
  }

  @Test
  void agvFaultIsReachableFromEveryOperationalStateAndRecoversToIdle() {
    var r = AgvStatus.RULES;
    for (AgvStatus s : AgvStatus.values()) {
      if (s != AgvStatus.FAULT && s != AgvStatus.OFFLINE) assertThat(r.can(s, AgvStatus.FAULT)).as(s.name()).isTrue();
    }
    assertThat(r.can(AgvStatus.FAULT, AgvStatus.IDLE)).isTrue();
    assertThat(r.can(AgvStatus.FAULT, AgvStatus.TO_DROP)).isFalse();
  }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/contracts test`
Expected: Kompilierfehler, `Transitions` und die Status-Enums existieren nicht.

- [ ] **Step 3: Implementieren**

`Transitions.java`:

```java
package io.nexus.contracts.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Transition table of a state machine. Immutable after build(). */
public final class Transitions<S extends Enum<S>> {
  private final Map<S, Set<S>> allowed;

  private Transitions(Map<S, Set<S>> allowed) {
    this.allowed = allowed;
  }

  public boolean can(S from, S to) {
    return allowed.getOrDefault(from, Set.of()).contains(to);
  }

  /** Returns {@code to} if the transition is legal, otherwise throws. */
  public S move(String entityId, S from, S to) {
    if (!can(from, to)) throw new IllegalTransitionException(entityId, from, to);
    return to;
  }

  public static <S extends Enum<S>> Builder<S> of(Class<S> type) {
    return new Builder<>(type);
  }

  public static final class Builder<S extends Enum<S>> {
    private final Class<S> type;
    private final Map<S, Set<S>> allowed;

    private Builder(Class<S> type) {
      this.type = type;
      this.allowed = new EnumMap<>(type);
    }

    @SafeVarargs
    public final Builder<S> allow(S from, S... to) {
      allowed.computeIfAbsent(from, k -> EnumSet.noneOf(type)).addAll(Set.of(to));
      return this;
    }

    public Builder<S> allowFromAll(Set<S> from, S to) {
      for (S f : from) allow(f, to);
      return this;
    }

    public Transitions<S> build() {
      return new Transitions<>(allowed);
    }
  }
}
```

`IllegalTransitionException.java`:

```java
package io.nexus.contracts.state;

public class IllegalTransitionException extends RuntimeException {
  public IllegalTransitionException(String entityId, Enum<?> from, Enum<?> to) {
    super("Ungültiger Zustandsübergang für " + entityId + ": " + from + " → " + to);
  }
}
```

`OrderStatus.java`:

```java
package io.nexus.contracts.state;

import java.util.EnumSet;

public enum OrderStatus {
  CREATED, MATERIAL_PENDING, SHORTAGE, RELEASED, IN_PROGRESS, COMPLETED, CANCELLED;

  public boolean terminal() {
    return this == COMPLETED || this == CANCELLED;
  }

  public static final Transitions<OrderStatus> RULES = Transitions.of(OrderStatus.class)
      .allow(CREATED, MATERIAL_PENDING)
      .allow(MATERIAL_PENDING, RELEASED, SHORTAGE)
      .allow(SHORTAGE, MATERIAL_PENDING)
      .allow(RELEASED, IN_PROGRESS)
      .allow(IN_PROGRESS, COMPLETED)
      .allowFromAll(EnumSet.of(CREATED, MATERIAL_PENDING, SHORTAGE, RELEASED, IN_PROGRESS), CANCELLED)
      .build();
}
```

`TransportStatus.java`:

```java
package io.nexus.contracts.state;

import java.util.EnumSet;
import java.util.Set;

public enum TransportStatus {
  CREATED, ASSIGNED, TO_PICKUP, LOADING, IN_TRANSIT, UNLOADING, DELIVERED, FAILED, CANCELLED;

  /** States in which the order occupies an AGV. Mirrors the partial unique index in the database. */
  public static final Set<TransportStatus> ACTIVE =
      Set.copyOf(EnumSet.of(ASSIGNED, TO_PICKUP, LOADING, IN_TRANSIT, UNLOADING));

  public boolean terminal() {
    return this == DELIVERED || this == CANCELLED;
  }

  public static final Transitions<TransportStatus> RULES = Transitions.of(TransportStatus.class)
      .allow(CREATED, ASSIGNED, CANCELLED)
      .allow(ASSIGNED, TO_PICKUP, CREATED, CANCELLED)
      .allow(TO_PICKUP, LOADING, CREATED, CANCELLED)
      .allow(LOADING, IN_TRANSIT, FAILED)
      .allow(IN_TRANSIT, UNLOADING, FAILED)
      .allow(UNLOADING, DELIVERED, FAILED)
      .allow(FAILED, ASSIGNED)
      .build();
}
```

`MachineStatus.java` (Übergänge wie in der bisherigen Engine `FactoryTwin/src/sim/engine.ts`, dort `setMachineStatus` und `resumeAfterDowntime`):

```java
package io.nexus.contracts.state;

import java.util.EnumSet;

public enum MachineStatus {
  IDLE, SETUP, RUNNING, BLOCKED, STOPPED, FAULT, REPAIR, MAINTENANCE;

  public static final Transitions<MachineStatus> RULES = Transitions.of(MachineStatus.class)
      .allow(IDLE, SETUP, RUNNING, STOPPED, MAINTENANCE)
      .allow(SETUP, RUNNING, STOPPED)
      .allow(RUNNING, IDLE, BLOCKED, STOPPED)
      .allow(BLOCKED, IDLE, STOPPED)
      .allow(STOPPED, IDLE, SETUP, RUNNING, BLOCKED, MAINTENANCE)
      .allowFromAll(EnumSet.of(IDLE, SETUP, RUNNING, BLOCKED, STOPPED), FAULT)
      .allow(FAULT, REPAIR)
      .allow(REPAIR, IDLE, SETUP, RUNNING, BLOCKED, STOPPED)
      .allow(MAINTENANCE, IDLE, STOPPED)
      .build();
}
```

`AgvStatus.java`:

```java
package io.nexus.contracts.state;

import java.util.EnumSet;

public enum AgvStatus {
  IDLE, TO_PICKUP, LOADING, TO_DROP, UNLOADING, TO_CHARGE, CHARGING, FAULT, OFFLINE;

  public static final Transitions<AgvStatus> RULES = Transitions.of(AgvStatus.class)
      .allow(IDLE, TO_PICKUP, TO_CHARGE, OFFLINE)
      .allow(TO_PICKUP, LOADING, IDLE)
      .allow(LOADING, TO_DROP, IDLE)
      .allow(TO_DROP, UNLOADING)
      .allow(UNLOADING, IDLE, TO_DROP)
      .allow(TO_CHARGE, CHARGING, OFFLINE)
      .allow(CHARGING, IDLE, OFFLINE)
      .allow(OFFLINE, CHARGING, IDLE)
      .allowFromAll(EnumSet.of(IDLE, TO_PICKUP, LOADING, TO_DROP, UNLOADING, TO_CHARGE, CHARGING), FAULT)
      .allow(FAULT, IDLE)
      .build();
}
```

`Domain.java`:

```java
package io.nexus.contracts;

/** Shared vocabulary of all modules. Names are persisted as text; do not rename. */
public final class Domain {
  private Domain() {}

  public enum ProcessType { DREHEN, FRAESEN, MONTAGE, PRUEFEN }
  public enum Priority { NORMAL, HOCH, EILIG }
  public enum TransportKind { SUPPLY, WIP, PUTAWAY }
  public enum MachineKind { CNC, ROBOT, QC }
  public enum Severity { INFO, SUCCESS, WARNING, ERROR }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/contracts test`
Expected: 8 Tests grün.

- [ ] **Step 5: Commit**

```bash
git add platform/contracts
git commit -m "feat(contracts): Zustandsautomaten für Auftrag, Transport, Maschine und AGV"
```

---

### Task 3: Ereignisse, Simulationszeit und Ports

**Files:**
- Create: `platform/contracts/src/main/java/io/nexus/contracts/event/NexusEvent.java`, `EventTypes.java`, `EventPublisher.java`, `EventConsumer.java`, `EventIds.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/sim/SimTime.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/port/MaterialService.java`, `TransportService.java`, `ProductCatalog.java`, `MachineControl.java`
- Test: `platform/contracts/src/test/java/io/nexus/contracts/event/EventIdsTest.java`, `platform/contracts/src/test/java/io/nexus/contracts/sim/SimTimeTest.java`

**Interfaces:**
- Consumes: `Domain.*` aus Task 2.
- Produces:
  - `record NexusEvent(UUID eventId, long simTimeMs, String type, String source, Domain.Severity severity, String correlationId, Map<String,Object> payload)`
  - `interface EventPublisher { NexusEvent publish(String type, String source, Domain.Severity severity, String correlationId, Map<String,Object> payload); }`
  - `interface EventConsumer { String name(); void on(NexusEvent event); }`
  - `EventIds(long runId, long seed)` mit `UUID next()`
  - `SimTime` mit `long nowMs()`, `void advance(long ms)`, `void reset()`
  - Ports `MaterialService`, `TransportService`, `ProductCatalog`, `MachineControl` mit den Records unten.

- [ ] **Step 1: Failing Tests schreiben**

`EventIdsTest.java`:

```java
package io.nexus.contracts.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EventIdsTest {
  private static List<UUID> take(EventIds ids, int n) {
    return Stream.generate(ids::next).limit(n).toList();
  }

  @Test
  void sameRunAndSeedGiveTheSameSequence() {
    assertThat(take(new EventIds(3, 4711), 100)).isEqualTo(take(new EventIds(3, 4711), 100));
  }

  @Test
  void idsAreUniqueWithinARunAndAcrossRuns() {
    var all = new HashSet<UUID>();
    all.addAll(take(new EventIds(1, 4711), 1000));
    all.addAll(take(new EventIds(2, 4711), 1000));
    assertThat(all).hasSize(2000);
  }

  @Test
  void largeAndNegativeSeedsDoNotCollideAcrossRuns() {
    var all = new HashSet<UUID>();
    for (long seed : new long[] {-1, Long.MAX_VALUE, 0}) {
      all.addAll(take(new EventIds(1, seed), 10));
      all.addAll(take(new EventIds(2, seed), 10));
    }
    assertThat(all).hasSize(40); // see note below: -1 and MAX_VALUE coincide within a run, never across runs
  }
}
```

Hinweis zum dritten Test: Die Seeds `-1` und `Long.MAX_VALUE` haben dieselben unteren 32 Bit. Innerhalb desselben Laufs dürfen ihre IDs kollidieren (ein Lauf hat genau einen Seed), über Läufe hinweg nicht. Erwartet werden deshalb 40 verschiedene IDs: 3 Seeds × 2 Läufe × 10 minus der Überschneidung von `-1` und `MAX` je Lauf (2 × 10).

`SimTimeTest.java`:

```java
package io.nexus.contracts.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SimTimeTest {
  @Test
  void advancesAndResets() {
    var t = new SimTime();
    t.advance(100);
    t.advance(100);
    assertThat(t.nowMs()).isEqualTo(200);
    t.reset();
    assertThat(t.nowMs()).isZero();
  }

  @Test
  void neverRunsBackwards() {
    assertThatThrownBy(() -> new SimTime().advance(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Tests laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/contracts test`
Expected: Kompilierfehler, `EventIds` und `SimTime` existieren nicht.

- [ ] **Step 3: Implementieren**

`NexusEvent.java`:

```java
package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Severity;
import java.util.Map;
import java.util.UUID;

/** correlationId is the production order id where one exists, so an order can be traced end to end. */
public record NexusEvent(UUID eventId, long simTimeMs, String type, String source, Severity severity,
                         String correlationId, Map<String, Object> payload) {}
```

`EventTypes.java`:

```java
package io.nexus.contracts.event;

public final class EventTypes {
  private EventTypes() {}

  public static final String ORDER_CREATED = "order.created";
  public static final String ORDER_MATERIAL_PENDING = "order.material_pending";
  public static final String ORDER_SHORTAGE = "order.shortage";
  public static final String ORDER_RELEASED = "order.released";
  public static final String ORDER_STARTED = "order.started";
  public static final String ORDER_RESCHEDULED = "order.rescheduled";
  public static final String ORDER_COMPLETED = "order.completed";
  public static final String ORDER_CANCELLED = "order.cancelled";
  public static final String OPERATION_STARTED = "operation.started";
  public static final String OPERATION_COMPLETED = "operation.completed";
  public static final String MATERIAL_RESERVED = "material.reserved";
  public static final String MATERIAL_RELEASED = "material.released";
  public static final String MATERIAL_SHORTAGE = "material.shortage";
  public static final String STOCK_CHANGED = "stock.changed";
  public static final String TRANSPORT_CREATED = "transport.created";
  public static final String TRANSPORT_ASSIGNED = "transport.assigned";
  public static final String TRANSPORT_STATE = "transport.state";
  public static final String TRANSPORT_FAILED = "transport.failed";
  public static final String TRANSPORT_REASSIGNED = "transport.reassigned";
  public static final String TRANSPORT_DELIVERED = "transport.delivered";
  public static final String AGV_STATE = "agv.state";
  public static final String AGV_FAULT = "agv.fault";
  public static final String ROUTE_BLOCKED = "route.blocked";
  public static final String ROUTE_REPLANNED = "route.replanned";
  public static final String MACHINE_STATUS = "machine.status";
  public static final String MACHINE_FAULT = "machine.fault";
  public static final String MACHINE_MATERIAL_REQUEST = "machine.material_request";
  public static final String OPCUA_CONNECTION = "opcua.connection";
  public static final String SCENARIO_STARTED = "scenario.started";
  public static final String SCENARIO_FINISHED = "scenario.finished";
  /** A reasoned automatic decision, e.g. AGV selection or alternative machine. */
  public static final String DECISION = "decision";
}
```

`EventPublisher.java`:

```java
package io.nexus.contracts.event;

import io.nexus.contracts.Domain.Severity;
import java.util.Map;

public interface EventPublisher {
  /**
   * Stores the event in the caller's transaction and delivers it to consumers after commit.
   * If the transaction rolls back, the event is neither stored nor delivered.
   */
  NexusEvent publish(String type, String source, Severity severity, String correlationId, Map<String, Object> payload);
}
```

`EventConsumer.java`:

```java
package io.nexus.contracts.event;

public interface EventConsumer {
  /** Stable name; idempotency is tracked per (name, eventId). */
  String name();

  /** Called at most once per event with effect. Runs in its own transaction. */
  void on(NexusEvent event);
}
```

`EventIds.java`:

```java
package io.nexus.contracts.event;

import java.util.UUID;

/** Deterministic event ids: same run and seed give the same sequence, different runs never collide. */
public final class EventIds {
  private final long mostSig;
  private long counter;

  public EventIds(long runId, long seed) {
    this.mostSig = (runId << 32) | (seed & 0xFFFFFFFFL);
  }

  public synchronized UUID next() {
    return new UUID(mostSig, counter++);
  }
}
```

`SimTime.java`:

```java
package io.nexus.contracts.sim;

/** Simulation clock in milliseconds since run start. Advanced only by the simulation loop. */
public final class SimTime {
  private volatile long nowMs;

  public long nowMs() {
    return nowMs;
  }

  public void advance(long ms) {
    if (ms < 0) throw new IllegalArgumentException("Simulationszeit läuft nicht rückwärts: " + ms);
    nowMs += ms;
  }

  public void reset() {
    nowMs = 0;
  }
}
```

`MaterialService.java`:

```java
package io.nexus.contracts.port;

/** Implemented by FlowPilot. */
public interface MaterialService {
  /** Idempotent on requestId: the same request returns the same result and reserves once. All or nothing. */
  ReservationResult reserve(ReservationRequest request);

  /** Releases an active reservation. Releasing an already released reservation is a no-op. */
  void release(String reservationId);

  Availability availability(String materialId);

  record ReservationRequest(String requestId, String orderId, String materialId, int quantity) {}

  sealed interface ReservationResult {
    record Reserved(String reservationId, String locationId) implements ReservationResult {}
    record Shortage(String materialId, int requested, int available) implements ReservationResult {}
  }

  record Availability(String materialId, int onHand, int reserved) {
    public int available() {
      return onHand - reserved;
    }
  }
}
```

`TransportService.java`:

```java
package io.nexus.contracts.port;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;

/** Implemented by FlowPilot. */
public interface TransportService {
  /** Idempotent on requestId. Returns the transport order id. */
  String requestTransport(TransportRequest request);

  /** Allowed only while the load has not been picked up; otherwise IllegalTransitionException. */
  void cancel(String transportId);

  /** Changes the destination of a transport that has not been delivered yet (machine failure rerouting). */
  void redirect(String transportId, String newToNode);

  /**
   * @param reservationId reservation consumed on pickup; null for WIP and PUTAWAY
   * @param materialId    null for WIP moves of a workpiece
   */
  record TransportRequest(String requestId, TransportKind kind, String orderId, String materialId, int quantity,
                          String fromNode, String toNode, Priority priority, String reservationId) {}
}
```

`ProductCatalog.java`:

```java
package io.nexus.contracts.port;

import java.util.List;

/** Implemented by SmartFactory. */
public interface ProductCatalog {
  /** Material demand of an order, one entry per (routing step, material). Unknown product: IllegalArgumentException. */
  List<MaterialDemand> demandFor(String productId, int quantity);

  record MaterialDemand(int stepSeq, String materialId, int quantity) {}
}
```

`MachineControl.java`:

```java
package io.nexus.contracts.port;

/** Implemented by the simulation, reached through OPC UA method calls. */
public interface MachineControl {
  void start(String machineId);
  void stop(String machineId);
  /** @param faultTypeId null picks a fault type valid for the machine kind */
  void injectFault(String machineId, String faultTypeId);
  void repair(String machineId);
  void maintain(String machineId);
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/contracts test`
Expected: alle Tests grün (8 aus Task 2, 5 neue).

- [ ] **Step 5: Commit**

```bash
git add platform/contracts
git commit -m "feat(contracts): Ereignisse, Simulationszeit und Modul-Ports"
```

---

### Task 4: Datenbankschema und Stammdaten

**Files:**
- Create: `platform/app/src/main/resources/db/migration/V1__schema.sql`
- Create: `platform/app/src/main/resources/db/migration/V2__master_data.sql`
- Create: `platform/app/src/main/resources/db/migration/V3__path_graph.sql` (generiert)
- Modify: `platform/app/src/main/resources/application.yml` (falls in Task 1 `spring.flyway.enabled: false` gesetzt wurde: Zeile entfernen)
- Test: `platform/app/src/test/java/io/nexus/app/SchemaTest.java`

**Interfaces:**
- Consumes: Spring-Kontext aus Task 1.
- Produces: Tabellen und Constraints wie unten. Spaltennamen sind für M1-B und M1-C verbindlich.

- [ ] **Step 1: Failing Test schreiben** – `SchemaTest.java`

```java
package io.nexus.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("embedded")
@Transactional // every test rolls back
class SchemaTest {
  @Autowired JdbcClient jdbc;

  private int count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
  }

  @Test
  void masterDataIsSeeded() {
    assertThat(count("product")).isEqualTo(4);
    assertThat(count("machine")).isEqualTo(7);
    assertThat(count("routing_step")).isEqualTo(11);
    assertThat(count("agv")).isEqualTo(6);
    assertThat(count("material")).isEqualTo(10);
  }

  @Test
  void everyRoutingStepHasACapableMachine() {
    int uncovered = jdbc.sql("""
        select count(*) from routing_step r
        where not exists (select 1 from machine m where r.process = any(m.capabilities))
        """).query(Integer.class).single();
    assertThat(uncovered).isZero();
  }

  @Test
  void pathGraphIsConnectedToEveryDockInUse() {
    int missing = jdbc.sql("""
        select count(*) from (
          select dock_node from machine where dock_node is not null
          union select dock_node from storage_location
          union select node_id from agv
        ) d where not exists (select 1 from graph_node n where n.id = d.dock_node)
        """).query(Integer.class).single();
    assertThat(missing).isZero();
    int deadEnds = jdbc.sql("""
        select count(*) from graph_node n
        where not exists (select 1 from graph_edge e where e.from_node = n.id)
           or not exists (select 1 from graph_edge e where e.to_node = n.id)
        """).query(Integer.class).single();
    assertThat(deadEnds).isZero();
  }

  @Test
  void databaseRejectsReservingMoreThanOnHand() {
    assertThatThrownBy(() -> jdbc.sql(
        "update stock set reserved = on_hand + 1 where material_id = 'RM-GH200'").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsStockAboveLocationCapacity() {
    assertThatThrownBy(() -> jdbc.sql(
        "update stock set on_hand = 100000 where material_id = 'RM-GH200'").update())
        .hasMessageContaining("Kapazität");
  }

  @Test
  void databaseRejectsDuplicateReservationRequest() {
    String insert = """
        insert into reservation (id, request_id, order_id, material_id, location_id, qty, created_at_ms)
        values (:id, 'REQ-1', 'PO-1', 'RM-GH200', 'RAW-A', 1, 0)
        """;
    jdbc.sql(insert).param("id", "RES-1").update();
    assertThatThrownBy(() -> jdbc.sql(insert).param("id", "RES-2").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsSecondActiveTransportForTheSameAgv() {
    String insert = """
        insert into transport_order (id, request_id, kind, qty, from_node, to_node, priority, status, agv_id, created_at_ms)
        values (:id, :id, 'SUPPLY', 1, 'D_RAW', 'D_CNC01', 'NORMAL', :status, 'AGV-01', 0)
        """;
    jdbc.sql(insert).param("id", "TO-1").param("status", "IN_TRANSIT").update();
    jdbc.sql(insert).param("id", "TO-2").param("status", "DELIVERED").update(); // finished ones do not count
    assertThatThrownBy(() -> jdbc.sql(insert).param("id", "TO-3").param("status", "ASSIGNED").update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/app -am test -Dtest=SchemaTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, Relation `product` existiert nicht.

- [ ] **Step 3: Schema schreiben** – `V1__schema.sql`

```sql
create table sim_run (
  id         bigserial primary key,
  seed       bigint      not null,
  started_at timestamptz not null default now()
);

-- SmartFactory -------------------------------------------------------------
create table product (
  id         text primary key,
  name       text not null,
  short_name text not null,
  color      text not null
);

create table material (
  id            text primary key,
  name          text not null,
  kind          text not null check (kind in ('RAW', 'KIT', 'FINISHED')),
  reorder_point int  not null default 0 check (reorder_point >= 0),
  reorder_qty   int  not null default 0 check (reorder_qty >= 0)
);

create table machine (
  id           text primary key,
  name         text not null,
  kind         text not null check (kind in ('CNC', 'ROBOT', 'QC')),
  model        text not null,
  capabilities text[] not null,
  pos_x        double precision not null,
  pos_z        double precision not null,
  dock_node    text,
  status       text not null default 'IDLE',
  version      int  not null default 0
);

create table routing_step (
  product_id           text not null references product,
  seq                  int  not null,
  process              text not null check (process in ('DREHEN', 'FRAESEN', 'MONTAGE', 'PRUEFEN')),
  base_time_s          int  not null check (base_time_s > 0),
  preferred_machine_id text not null references machine,
  primary key (product_id, seq)
);

create table bom_item (
  product_id  text not null,
  seq         int  not null,
  material_id text not null references material,
  qty         int  not null check (qty > 0),
  primary key (product_id, seq, material_id),
  foreign key (product_id, seq) references routing_step
);

create table maintenance_window (
  id         bigserial primary key,
  machine_id text   not null references machine,
  start_ms   bigint not null,
  end_ms     bigint not null,
  check (end_ms > start_ms)
);

create table production_order (
  id              text primary key,
  product_id      text   not null references product,
  quantity        int    not null check (quantity > 0),
  priority        text   not null check (priority in ('NORMAL', 'HOCH', 'EILIG')),
  status          text   not null default 'CREATED',
  due_at_ms       bigint not null,
  created_at_ms   bigint not null,
  completed_at_ms bigint,
  good            int    not null default 0,
  scrapped        int    not null default 0,
  version         int    not null default 0
);

create table operation (
  id               text primary key,
  order_id         text not null references production_order,
  seq              int  not null,
  process          text not null,
  machine_id       text references machine,
  status           text not null default 'PLANNED',
  planned_start_ms bigint,
  planned_end_ms   bigint,
  started_at_ms    bigint,
  ended_at_ms      bigint,
  unique (order_id, seq)
);

create table machine_state_interval (
  id         bigserial primary key,
  machine_id text   not null references machine,
  status     text   not null,
  from_ms    bigint not null,
  to_ms      bigint
);
create index machine_state_interval_machine on machine_state_interval (machine_id, from_ms);

-- FlowPilot ----------------------------------------------------------------
create table graph_node (
  id   text primary key,
  x    double precision not null,
  z    double precision not null,
  dock boolean not null default false
);

create table graph_edge (
  id        text primary key,
  from_node text not null references graph_node,
  to_node   text not null references graph_node,
  length    double precision not null check (length > 0),
  blocked   boolean not null default false,
  unique (from_node, to_node)
);

create table storage_location (
  id        text primary key,
  name      text not null,
  zone      text not null check (zone in ('RAW', 'FINISHED')),
  dock_node text not null,
  capacity  int  not null check (capacity > 0)
);

create table stock (
  material_id text not null references material,
  location_id text not null references storage_location,
  on_hand     int  not null default 0 check (on_hand >= 0),
  reserved    int  not null default 0 check (reserved >= 0),
  primary key (material_id, location_id),
  constraint stock_reserved_within_on_hand check (reserved <= on_hand)
);

create function stock_within_capacity() returns trigger language plpgsql as $$
declare
  total int;
  cap   int;
begin
  select coalesce(sum(on_hand), 0) into total from stock where location_id = new.location_id;
  select capacity into cap from storage_location where id = new.location_id;
  if total > cap then
    raise exception 'Kapazität von Lagerplatz % überschritten: % > %', new.location_id, total, cap
      using errcode = 'check_violation';
  end if;
  return null;
end $$;

create constraint trigger stock_capacity after insert or update of on_hand on stock
  for each row execute function stock_within_capacity();

create table reservation (
  id            text primary key,
  request_id    text   not null unique,
  order_id      text   not null,
  material_id   text   not null,
  location_id   text   not null,
  qty           int    not null check (qty > 0),
  status        text   not null default 'ACTIVE' check (status in ('ACTIVE', 'RELEASED', 'CONSUMED')),
  created_at_ms bigint not null,
  foreign key (material_id, location_id) references stock
);

create table agv (
  id       text primary key,
  name     text not null,
  capacity int  not null default 1 check (capacity > 0),
  status   text not null default 'IDLE',
  node_id  text not null references graph_node,
  battery  double precision not null default 100 check (battery between 0 and 100),
  enabled  boolean not null default true,
  version  int  not null default 0
);

create table transport_order (
  id             text primary key,
  request_id     text   not null unique,
  kind           text   not null check (kind in ('SUPPLY', 'WIP', 'PUTAWAY')),
  order_id       text,
  material_id    text,
  qty            int    not null check (qty > 0),
  from_node      text   not null references graph_node,
  to_node        text   not null references graph_node,
  priority       text   not null check (priority in ('NORMAL', 'HOCH', 'EILIG')),
  status         text   not null default 'CREATED',
  agv_id         text references agv,
  reservation_id text references reservation,
  created_at_ms  bigint not null,
  version        int    not null default 0
);
-- must match TransportStatus.ACTIVE
create unique index transport_one_active_per_agv on transport_order (agv_id)
  where agv_id is not null and status in ('ASSIGNED', 'TO_PICKUP', 'LOADING', 'IN_TRANSIT', 'UNLOADING');

-- Events ---------------------------------------------------------------------
create table event (
  event_id       uuid primary key,
  seq            bigserial unique,
  run_id         bigint not null references sim_run,
  sim_time_ms    bigint not null,
  type           text   not null,
  source         text   not null,
  severity       text   not null check (severity in ('INFO', 'SUCCESS', 'WARNING', 'ERROR')),
  correlation_id text,
  payload        jsonb  not null
);
create index event_type on event (type, seq);
create index event_correlation on event (correlation_id, seq);

create table processed_event (
  consumer text not null,
  event_id uuid not null,
  primary key (consumer, event_id)
);
```

- [ ] **Step 4: Stammdaten schreiben** – `V2__master_data.sql`

Werte aus `FactoryTwin/src/sim/config.ts` und `initialState.ts` übernommen. Materialien und Stücklisten sind neu (die bisherige Engine kannte nur einen Rohteilzähler).

```sql
insert into product (id, name, short_name, color) values
  ('GH200', 'Getriebegehäuse GH-200', 'GH-200', '#3987e5'),
  ('AW50',  'Antriebswelle AW-50',    'AW-50',  '#d95926'),
  ('FP12',  'Flanschplatte FP-12',    'FP-12',  '#199e70'),
  ('MT7',   'Motorträger MT-7',       'MT-7',   '#c98500');

insert into machine (id, name, kind, model, capabilities, pos_x, pos_z, dock_node) values
  ('CNC-01', 'Fräszentrum 1',    'CNC',   'VMC-850 · 3-Achs-Vertikal',  '{FRAESEN}',        -12,   -10,   'D_CNC01'),
  ('CNC-02', 'Fräszentrum 2',    'CNC',   'VMC-850 · 3-Achs-Vertikal',  '{FRAESEN}',         -4,   -10,   'D_CNC02'),
  ('CNC-03', 'Drehzentrum',      'CNC',   'TC-400 · CNC-Drehmaschine',  '{DREHEN}',           4,   -10,   'D_CNC03'),
  ('CNC-04', '5-Achs-Zentrum',   'CNC',   'UMC-500 · 5-Achs-Universal', '{DREHEN,FRAESEN}',  12,   -10,   'D_CNC04'),
  ('ROB-01', 'Montagezelle 1',   'ROBOT', 'Knickarm 6-Achs · 12 kg',    '{MONTAGE}',         -7.3,  10.9, 'D_CV01'),
  ('ROB-02', 'Montagezelle 2',   'ROBOT', 'Knickarm 6-Achs · 12 kg',    '{MONTAGE}',          1.7,  10.9, 'D_CV02'),
  ('QC-01',  'Qualitätsprüfung', 'QC',    'Vision- & Laser-Messzelle',  '{PRUEFEN}',         14.8,  12.6, 'D_CV03');

insert into routing_step (product_id, seq, process, base_time_s, preferred_machine_id) values
  ('GH200', 1, 'FRAESEN', 85, 'CNC-01'), ('GH200', 2, 'MONTAGE', 65, 'ROB-01'), ('GH200', 3, 'PRUEFEN', 28, 'QC-01'),
  ('AW50',  1, 'DREHEN',  70, 'CNC-03'), ('AW50',  2, 'FRAESEN', 45, 'CNC-02'), ('AW50',  3, 'PRUEFEN', 22, 'QC-01'),
  ('FP12',  1, 'FRAESEN', 55, 'CNC-02'), ('FP12',  2, 'PRUEFEN', 18, 'QC-01'),
  ('MT7',   1, 'DREHEN',  60, 'CNC-04'), ('MT7',   2, 'MONTAGE', 80, 'ROB-02'), ('MT7',   3, 'PRUEFEN', 30, 'QC-01');

insert into material (id, name, kind, reorder_point, reorder_qty) values
  ('RM-GH200',  'Gussrohling GH-200',        'RAW', 12, 40),
  ('RM-AW50',   'Rundstahl AW-50',           'RAW', 12, 40),
  ('RM-FP12',   'Plattenzuschnitt FP-12',    'RAW', 12, 40),
  ('RM-MT7',    'Schmiederohling MT-7',      'RAW', 12, 40),
  ('KIT-GH200', 'Lager- und Dichtsatz GH-200', 'KIT', 8, 30),
  ('KIT-MT7',   'Befestigungssatz MT-7',     'KIT',  8, 30),
  ('FG-GH200',  'Getriebegehäuse GH-200',    'FINISHED', 0, 0),
  ('FG-AW50',   'Antriebswelle AW-50',       'FINISHED', 0, 0),
  ('FG-FP12',   'Flanschplatte FP-12',       'FINISHED', 0, 0),
  ('FG-MT7',    'Motorträger MT-7',          'FINISHED', 0, 0);

insert into bom_item (product_id, seq, material_id, qty) values
  ('GH200', 1, 'RM-GH200', 1), ('GH200', 2, 'KIT-GH200', 1),
  ('AW50',  1, 'RM-AW50',  1),
  ('FP12',  1, 'RM-FP12',  1),
  ('MT7',   1, 'RM-MT7',   1), ('MT7',   2, 'KIT-MT7',   1);

insert into storage_location (id, name, zone, dock_node, capacity) values
  ('RAW-A', 'Rohlager Regal A', 'RAW',      'D_RAW', 120),
  ('RAW-B', 'Rohlager Regal B', 'RAW',      'D_RAW', 120),
  ('FG-A',  'Fertiglager Regal A', 'FINISHED', 'D_FG', 160);

insert into stock (material_id, location_id, on_hand) values
  ('RM-GH200', 'RAW-A', 30), ('RM-AW50', 'RAW-A', 30), ('RM-FP12', 'RAW-A', 30), ('RM-MT7', 'RAW-A', 30),
  ('KIT-GH200', 'RAW-B', 24), ('KIT-MT7', 'RAW-B', 24),
  ('FG-GH200', 'FG-A', 0), ('FG-AW50', 'FG-A', 0), ('FG-FP12', 'FG-A', 0), ('FG-MT7', 'FG-A', 0);
```

Montagezellen und Prüfzelle haben in der bisherigen Engine kein eigenes Dock, sondern werden über ihr Zuführband beliefert. `dock_node` zeigt deshalb auf das Dock des jeweiligen Zuführbands (`D_CV01`, `D_CV02`, `D_CV03`).

Die AGVs stehen in `V3`, weil ihre Startknoten erst mit dem Graphen existieren.

- [ ] **Step 5: Wegegraph aus FactoryTwin exportieren** – erzeugt `V3__path_graph.sql`

Der Graph wird in `FactoryTwin/src/sim/layout.ts` berechnet. Einmalig mit Node (ab 24, kann TypeScript-Typen direkt entfernen) nach SQL exportieren:

```bash
node --input-type=module -e "
import { PATH_GRAPH } from './FactoryTwin/src/sim/layout.ts';
const q = (s) => \"'\" + s + \"'\";
const out = ['-- Generiert aus FactoryTwin/src/sim/layout.ts (buildPathGraph). Nicht von Hand ändern.'];
out.push('insert into graph_node (id, x, z, dock) values');
out.push([...PATH_GRAPH.nodes.values()].map(n => '  (' + [q(n.id), n.pos.x, n.pos.z, !!n.dock].join(', ') + ')').join(',\n') + ';');
out.push('insert into graph_edge (id, from_node, to_node, length) values');
out.push(PATH_GRAPH.edges.map(e => '  (' + [q(e.from + '>' + e.to), q(e.from), q(e.to), e.length.toFixed(3)].join(', ') + ')').join(',\n') + ';');
out.push('insert into agv (id, name, node_id) values');
out.push(['D_CH1','D_CH2','D_CH3','D_CH4','D_CH5','D_CH6'].map((n, i) => '  (' + [q('AGV-0' + (i + 1)), q('AGV ' + (i + 1)), q(n)].join(', ') + ')').join(',\n') + ';');
console.log(out.join('\n'));
" > platform/app/src/main/resources/db/migration/V3__path_graph.sql
```

Prüfen: `head -5 platform/app/src/main/resources/db/migration/V3__path_graph.sql` zeigt die Kommentarzeile und `insert into graph_node`. Falls Node den Import mit einem Hinweis auf fehlende Typ-Unterstützung ablehnt, denselben Befehl mit `node --experimental-strip-types` ausführen.

- [ ] **Step 6: Test laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/app -am test -Dtest=SchemaTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 7 Tests grün.

- [ ] **Step 7: Commit**

```bash
git add platform/app/src
git commit -m "feat(app): Datenbankschema, Stammdaten und Wegegraph"
```

---

### Task 5: Ereignisspeicher mit idempotenter Zustellung

**Files:**
- Create: `platform/app/src/main/java/io/nexus/app/event/JdbcEventPublisher.java`
- Create: `platform/app/src/main/java/io/nexus/app/event/SimRun.java`
- Test: `platform/app/src/test/java/io/nexus/app/event/JdbcEventPublisherTest.java`

**Interfaces:**
- Consumes: `EventPublisher`, `EventConsumer`, `NexusEvent`, `EventIds`, `SimTime` (Task 3); Tabellen `sim_run`, `event`, `processed_event` (Task 4).
- Produces:
  - Spring-Beans `SimTime`, `SimRun` (mit `long id()`, `long seed()`, `EventIds ids()`, `void restart(long seed)`), `EventPublisher` (Implementierung `JdbcEventPublisher`).
  - `JdbcEventPublisher.redeliver(UUID eventId)` stellt ein gespeichertes Ereignis erneut allen Verbrauchern zu.
  - Jeder Spring-Bean vom Typ `EventConsumer` wird automatisch registriert, Zustellung in Reihenfolge der Bean-Namen.

- [ ] **Step 1: Failing Test schreiben** – `JdbcEventPublisherTest.java`

```java
package io.nexus.app.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.NexusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("embedded")
class JdbcEventPublisherTest {
  static final List<String> seenByA = new ArrayList<>();
  static final List<String> seenByB = new ArrayList<>();
  static boolean aFails;

  @TestConfiguration
  static class Consumers {
    @Bean
    EventConsumer consumerA(JdbcClient jdbc) {
      return new EventConsumer() {
        public String name() { return "test-a"; }
        public void on(NexusEvent e) {
          // a real side effect, so the test can prove it rolls back together with the marker
          jdbc.sql("insert into maintenance_window (machine_id, start_ms, end_ms) values ('CNC-01', 0, 1)").update();
          if (aFails) throw new IllegalStateException("boom");
          seenByA.add(e.type());
        }
      };
    }

    @Bean
    EventConsumer consumerB() {
      return new EventConsumer() {
        public String name() { return "test-b"; }
        public void on(NexusEvent e) { seenByB.add(e.type()); }
      };
    }
  }

  @Autowired EventPublisher events;
  @Autowired JdbcEventPublisher publisher;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    seenByA.clear();
    seenByB.clear();
    aFails = false;
    jdbc.sql("delete from processed_event").update();
    jdbc.sql("delete from event").update();
    jdbc.sql("delete from maintenance_window").update();
  }

  private int count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
  }

  @Test
  void storesAndDeliversAfterCommit() {
    tx.executeWithoutResult(s -> {
      events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of("quantity", 5));
      assertThat(seenByA).as("not delivered before commit").isEmpty();
    });
    assertThat(seenByA).containsExactly("order.created");
    assertThat(seenByB).containsExactly("order.created");
    assertThat(jdbc.sql("select payload->>'quantity' from event where correlation_id = 'PO-1'")
        .query(String.class).single()).isEqualTo("5");
  }

  @Test
  void rolledBackTransactionLeavesNoEventAndCallsNoConsumer() {
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
      events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of());
      throw new IllegalStateException("business failure");
    })).hasMessage("business failure");
    assertThat(count("event")).isZero();
    assertThat(seenByA).isEmpty();
    assertThat(seenByB).isEmpty();
  }

  @Test
  void redeliveryHasNoSecondEffect() {
    NexusEvent e = events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of());
    publisher.redeliver(e.eventId());
    publisher.redeliver(e.eventId());
    assertThat(seenByA).hasSize(1);
    assertThat(seenByB).hasSize(1);
    assertThat(count("maintenance_window")).isEqualTo(1);
  }

  @Test
  void failingConsumerDoesNotBlockOthersAndCanBeRetried() {
    aFails = true;
    NexusEvent e = events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of());
    assertThat(seenByB).as("B still receives the event").hasSize(1);
    assertThat(count("maintenance_window")).as("A's side effect rolled back").isZero();

    aFails = false;
    publisher.redeliver(e.eventId());
    assertThat(seenByA).hasSize(1);
    assertThat(seenByB).as("B is not called twice").hasSize(1);
    assertThat(count("maintenance_window")).isEqualTo(1);
  }

  @Test
  void eventsKeepPublishOrderAndCarrySimulationTime(@Autowired io.nexus.contracts.sim.SimTime time) {
    long before = time.nowMs();
    events.publish("a", "test", Severity.INFO, null, Map.of());
    time.advance(100);
    events.publish("b", "test", Severity.INFO, null, Map.of());
    assertThat(jdbc.sql("select type from event order by seq").query(String.class).list()).containsExactly("a", "b");
    assertThat(jdbc.sql("select sim_time_ms from event order by seq").query(Long.class).list())
        .containsExactly(before, before + 100);
  }

  @Test
  void payloadWithNullCorrelationAndNestedValuesRoundTrips() {
    NexusEvent e = events.publish("decision", "flowpilot", Severity.INFO, null,
        Map.of("chosen", "AGV-02", "candidates", List.of("AGV-01", "AGV-02"), "reason", "kürzeste Anfahrt"));
    publisher.redeliver(e.eventId()); // forces a read back from the database
    assertThat(jdbc.sql("select payload->'candidates'->>1 from event").query(String.class).single()).isEqualTo("AGV-02");
    assertThat(jdbc.sql("select payload->>'reason' from event").query(String.class).single()).isEqualTo("kürzeste Anfahrt");
  }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/app -am test -Dtest=JdbcEventPublisherTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Kompilierfehler, `JdbcEventPublisher` existiert nicht.

- [ ] **Step 3: Implementieren**

`SimRun.java`:

```java
package io.nexus.app.event;

import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.sim.SimTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The current simulation run. Every start and every reset opens a new run, so event ids never collide. */
@Component
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization // Flyway must have run first
public class SimRun {
  private final JdbcClient jdbc;
  private long id;
  private long seed;
  private EventIds ids;

  SimRun(JdbcClient jdbc, @Value("${nexus.sim.seed}") long seed) {
    this.jdbc = jdbc;
    restart(seed);
  }

  public synchronized void restart(long seed) {
    this.seed = seed;
    this.id = jdbc.sql("insert into sim_run (seed) values (:seed) returning id").param("seed", seed)
        .query(Long.class).single();
    this.ids = new EventIds(id, seed);
  }

  public synchronized long id() { return id; }
  public synchronized long seed() { return seed; }
  public synchronized EventIds ids() { return ids; }

  @Configuration
  static class Beans {
    @Bean
    SimTime simTime() {
      return new SimTime();
    }
  }
}
```

`JdbcEventPublisher.java`:

```java
package io.nexus.app.event;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.sim.SimTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcEventPublisher implements EventPublisher {
  private static final Logger log = LoggerFactory.getLogger(JdbcEventPublisher.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final SimRun run;
  private final SimTime time;
  private final ObjectMapper json;
  private final ObjectProvider<EventConsumer> consumers;
  private final TransactionTemplate newTx;

  JdbcEventPublisher(JdbcClient jdbc, SimRun run, SimTime time, ObjectMapper json,
                     ObjectProvider<EventConsumer> consumers, PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.run = run;
    this.time = time;
    this.json = json;
    this.consumers = consumers;
    this.newTx = new TransactionTemplate(txManager);
    this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public NexusEvent publish(String type, String source, Severity severity, String correlationId,
                            Map<String, Object> payload) {
    var event = new NexusEvent(run.ids().next(), time.nowMs(), type, source, severity, correlationId, payload);
    jdbc.sql("""
        insert into event (event_id, run_id, sim_time_ms, type, source, severity, correlation_id, payload)
        values (:id, :run, :time, :type, :source, :severity, :corr, cast(:payload as jsonb))
        """)
        .param("id", event.eventId()).param("run", run.id()).param("time", event.simTimeMs())
        .param("type", type).param("source", source).param("severity", severity.name())
        .param("corr", correlationId).param("payload", json.writeValueAsString(payload))
        .update();

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          deliver(event);
        }
      });
    } else {
      deliver(event);
    }
    return event;
  }

  /** Delivers a stored event again. Consumers that already processed it are skipped. */
  public void redeliver(UUID eventId) {
    NexusEvent event = jdbc.sql("""
        select event_id, sim_time_ms, type, source, severity, correlation_id, payload::text as payload
        from event where event_id = :id
        """).param("id", eventId)
        .query((rs, i) -> new NexusEvent(rs.getObject("event_id", UUID.class), rs.getLong("sim_time_ms"),
            rs.getString("type"), rs.getString("source"), Severity.valueOf(rs.getString("severity")),
            rs.getString("correlation_id"), json.readValue(rs.getString("payload"), MAP)))
        .single();
    deliver(event);
  }

  private void deliver(NexusEvent event) {
    List<EventConsumer> ordered = consumers.orderedStream().sorted((a, b) -> a.name().compareTo(b.name())).toList();
    for (EventConsumer consumer : ordered) {
      try {
        // marker and the consumer's own writes commit together or not at all
        newTx.executeWithoutResult(status -> {
          int first = jdbc.sql("""
              insert into processed_event (consumer, event_id) values (:c, :e) on conflict do nothing
              """).param("c", consumer.name()).param("e", event.eventId()).update();
          if (first == 1) consumer.on(event);
        });
      } catch (RuntimeException ex) {
        log.error("Verbraucher {} konnte Ereignis {} ({}) nicht verarbeiten", consumer.name(), event.eventId(),
            event.type(), ex);
      }
    }
  }
}
```

Hinweis für den Umsetzer: Spring Boot 4 liefert Jackson 3 (Package `tools.jackson`, unchecked Exceptions). Falls der Build stattdessen `com.fasterxml.jackson.databind.ObjectMapper` bereitstellt, die beiden Imports anpassen und die geprüfte `JsonProcessingException` in `publish` und im Row-Mapper in eine `IllegalStateException` umwickeln. Welche Variante gilt, zeigt `.\mvnw.cmd -q -pl platform/app dependency:tree -Dincludes=tools.jackson.core,com.fasterxml.jackson.core`.

Zustellung nach dem Commit läuft im aufrufenden Thread. Das hält die Reihenfolge deterministisch (Spec Abschnitt 11).

- [ ] **Step 4: Tests laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/app -am test -Dtest=JdbcEventPublisherTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 6 Tests grün. Der Test `failingConsumer…` schreibt absichtlich einen Stacktrace ins Log.

- [ ] **Step 5: Commit**

```bash
git add platform/app/src
git commit -m "feat(app): transaktionaler Ereignisspeicher mit idempotenter Zustellung"
```

---

### Task 6: REST-Grundlagen und Ereignisabfrage

**Files:**
- Create: `platform/app/src/main/java/io/nexus/app/web/ApiErrors.java`
- Create: `platform/app/src/main/java/io/nexus/app/web/NotFoundException.java`
- Create: `platform/app/src/main/java/io/nexus/app/event/EventController.java`
- Test: `platform/app/src/test/java/io/nexus/app/web/ApiTest.java`

**Interfaces:**
- Consumes: `IllegalTransitionException` (Task 2), `EventPublisher` und Tabelle `event` (Task 5).
- Produces:
  - `NotFoundException(String what, String id)` → 404. Von M1-B und M1-C in Controllern zu verwenden.
  - Fehlerabbildung für alle Controller: `IllegalTransitionException` → 409, `OptimisticLockingFailureException` → 409, `IllegalArgumentException` → 400, Bean-Validation-Fehler → 400 mit Feld `errors` (Liste aus `field`, `message`).
  - `GET /api/v1/events?type=&source=&correlationId=&after=&limit=` liefert Ereignisse aufsteigend nach `seq`; `after` ist die letzte gesehene `seq`, `limit` Standard 200, Maximum 1000.
  - OpenAPI-Dokument unter `/v3/api-docs`, Swagger UI unter `/swagger-ui.html`.

- [ ] **Step 1: Failing Test schreiben** – `ApiTest.java`

```java
package io.nexus.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.event.EventPublisher;
import io.nexus.contracts.state.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
@Import(ApiTest.Probe.class)
class ApiTest {
  record ProbeInput(@NotBlank String product, @Min(1) int quantity) {}

  @TestConfiguration
  @RestController
  static class Probe {
    @GetMapping("/api/v1/probe/missing/{id}")
    String missing(@PathVariable String id) { throw new NotFoundException("Auftrag", id); }

    @GetMapping("/api/v1/probe/illegal")
    String illegal() { return OrderStatus.RULES.move("PO-1", OrderStatus.CREATED, OrderStatus.COMPLETED).name(); }

    @PostMapping("/api/v1/probe/validate")
    String validate(@Valid @RequestBody ProbeInput in) { return in.product(); }
  }

  @Value("${local.server.port}") int port;
  @Autowired EventPublisher events;
  @Autowired JdbcClient jdbc;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void clean() {
    jdbc.sql("delete from processed_event").update();
    jdbc.sql("delete from event").update();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void unknownIdIs404ProblemJson() throws Exception {
    var r = get("/api/v1/probe/missing/PO-9");
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
    assertThat(r.body()).contains("PO-9");
  }

  @Test
  void illegalTransitionIs409() throws Exception {
    var r = get("/api/v1/probe/illegal");
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(r.body()).contains("CREATED").contains("COMPLETED");
  }

  @Test
  void validationErrorIs400AndNamesTheFields() throws Exception {
    var r = post("/api/v1/probe/validate", "{\"product\":\"\",\"quantity\":0}");
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(r.body()).contains("\"errors\"").contains("product").contains("quantity");
  }

  @Test
  void malformedJsonIs400NotAServerError() throws Exception {
    assertThat(post("/api/v1/probe/validate", "{not json").statusCode()).isEqualTo(400);
  }

  @Test
  void eventsAreFilterableAndPageableBySeq() throws Exception {
    events.publish("order.created", "smartfactory", Severity.INFO, "PO-1", Map.of());
    events.publish("transport.created", "flowpilot", Severity.INFO, "PO-1", Map.of());
    events.publish("order.created", "smartfactory", Severity.INFO, "PO-2", Map.of());

    assertThat(get("/api/v1/events").body()).contains("PO-1").contains("PO-2").contains("transport.created");
    var onlyPo1 = get("/api/v1/events?correlationId=PO-1").body();
    assertThat(onlyPo1).contains("transport.created").doesNotContain("PO-2");
    assertThat(get("/api/v1/events?type=transport.created&source=flowpilot").body()).doesNotContain("order.created");

    long firstSeq = jdbc.sql("select min(seq) from event").query(Long.class).single();
    var afterFirst = get("/api/v1/events?after=" + firstSeq + "&limit=1").body();
    assertThat(afterFirst).contains("transport.created").doesNotContain("PO-2");
  }

  @Test
  void limitIsClampedInsteadOfFailing() throws Exception {
    assertThat(get("/api/v1/events?limit=999999").statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/events?limit=0").statusCode()).isEqualTo(200);
  }

  @Test
  void openApiDocumentListsTheEventsEndpoint() throws Exception {
    var r = get("/v3/api-docs");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("/api/v1/events");
  }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/app -am test -Dtest=ApiTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Kompilierfehler, `NotFoundException` existiert nicht.

- [ ] **Step 3: Implementieren**

`NotFoundException.java`:

```java
package io.nexus.app.web;

public class NotFoundException extends RuntimeException {
  public NotFoundException(String what, String id) {
    super(what + " " + id + " nicht gefunden");
  }
}
```

`ApiErrors.java`:

```java
package io.nexus.app.web;

import io.nexus.contracts.state.IllegalTransitionException;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** One error format for every controller: RFC 7807 problem JSON. */
@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(IllegalTransitionException.class)
  ProblemDetail illegalTransition(IllegalTransitionException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ProblemDetail versionConflict(OptimisticLockingFailureException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
        "Das Objekt wurde zwischenzeitlich geändert. Bitte neu laden und erneut versuchen.");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                HttpStatusCode status, WebRequest request) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Eingabe ungültig");
    List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))).toList();
    body.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(body);
  }
}
```

`EventController.java`:

```java
package io.nexus.app.event;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/events")
class EventController {
  record EventView(long seq, UUID eventId, long simTimeMs, String type, String source, String severity,
                   String correlationId, JsonNode payload) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  EventController(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Events in ascending order. Pass the last seen seq as {@code after} to page forward. */
  @GetMapping
  List<EventView> events(@RequestParam(required = false) String type,
                         @RequestParam(required = false) String source,
                         @RequestParam(required = false) String correlationId,
                         @RequestParam(defaultValue = "0") long after,
                         @RequestParam(defaultValue = "200") int limit) {
    return jdbc.sql("""
        select seq, event_id, sim_time_ms, type, source, severity, correlation_id, payload::text as payload
        from event
        where seq > :after
          and (cast(:type as text) is null or type = :type)
          and (cast(:source as text) is null or source = :source)
          and (cast(:corr as text) is null or correlation_id = :corr)
        order by seq
        limit :limit
        """)
        .param("after", after).param("type", type).param("source", source).param("corr", correlationId)
        .param("limit", Math.clamp(limit, 1, 1000))
        .query((rs, i) -> new EventView(rs.getLong("seq"), rs.getObject("event_id", UUID.class),
            rs.getLong("sim_time_ms"), rs.getString("type"), rs.getString("source"), rs.getString("severity"),
            rs.getString("correlation_id"), json.readTree(rs.getString("payload"))))
        .list();
  }
}
```

Für die Jackson-Imports gilt derselbe Hinweis wie in Task 5.

- [ ] **Step 4: Tests laufen lassen**

Run: `.\mvnw.cmd -q -pl platform/app -am test -Dtest=ApiTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 7 Tests grün.

- [ ] **Step 5: Gesamtlauf**

Run: `.\mvnw.cmd -q verify`
Expected: `BUILD SUCCESS`. Contracts 13 Tests, App 21 Tests (1 + 7 + 6 + 7).

- [ ] **Step 6: Commit**

```bash
git add platform/app/src
git commit -m "feat(app): einheitliche API-Fehler, Ereignisabfrage und OpenAPI"
```

---

## Nach diesem Plan

- M1-B (FlowPilot) und M1-C (SmartFactory) können parallel starten. Beide legen Code nur im eigenen Modulordner und unter `platform/app/src/main/java/io/nexus/app/` in eigenen Unterpackages an; `V1` bis `V3` werden nicht mehr verändert, neue Migrationen bekommen getrennte Nummernkreise (FlowPilot `V100+`, SmartFactory `V200+`).
- Offen für M1-D: Simulationsschleife, OPC UA, WebSocket-Frames, Ende-zu-Ende-Ablauf.
