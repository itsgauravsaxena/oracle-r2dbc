/*
  Copyright (c) 2020, 2021, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License 
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package oracle.r2dbc.impl;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.ValidationDepth;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static oracle.r2dbc.DatabaseConfig.sharedConnection;
import static oracle.r2dbc.DatabaseConfig.connectTimeout;
import static oracle.r2dbc.DatabaseConfig.newConnection;
import static oracle.r2dbc.util.Awaits.awaitError;
import static oracle.r2dbc.util.Awaits.awaitExecution;
import static oracle.r2dbc.util.Awaits.awaitMany;
import static oracle.r2dbc.util.Awaits.awaitNone;
import static oracle.r2dbc.util.Awaits.awaitOne;
import static oracle.r2dbc.util.Awaits.awaitQuery;
import static oracle.r2dbc.util.Awaits.awaitUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that
 * {@link OracleConnectionImpl} implements behavior that is specified in it's
 * class and method level javadocs.
 */
public class OracleConnectionImplTest {

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#beginTransaction()}
   */
  @Test
  public void testBeginTransaction() {
    Connection sessionA =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      // Insert into this table after beginning a transaction
      awaitExecution(sessionA.createStatement(
        "CREATE TABLE testBeginTransaction (value VARCHAR(10))"));

      try {
        // Expect the publisher to set auto-commit false when the first
        // subscriber subscribes
        assertTrue(
          sessionA.isAutoCommit(),
          "Unexpected return value from isAutoCommit() before" +
            " beginTransaction()");
        Publisher<Void> beginTransactionPublisher =
          sessionA.beginTransaction();
        awaitNone(beginTransactionPublisher);
        assertFalse(
          sessionA.isAutoCommit(),
          "Unexpected return value from isAutoCommit() after" +
            " beginTransaction()");

        // Expect the publisher to NOT repeatedly set auto-commit to false
        // for each subscriber
        awaitNone(sessionA.setAutoCommit(true));
        awaitNone(beginTransactionPublisher);
        assertTrue(
          sessionA.isAutoCommit(),
          "Unexpected return value from isAutoCommit() after multiple " +
            "subscriptions to a beginTransaction() publisher");

        // Now begin a transaction and verify that a table INSERT is not visible
        // until the transaction is committed.
        awaitNone(sessionA.beginTransaction());
        assertFalse(
          sessionA.isAutoCommit(),
          "Unexpected return value from isAutoCommit() after" +
            " beginTransaction()");
        awaitUpdate(
          1,
          sessionA.createStatement(
            "INSERT INTO testBeginTransaction VALUES ('A')"));

        // sessionB doesn't see the INSERT made in sessionA's open transaction
        Connection sessionB =
          Mono.from(newConnection()).block(connectTimeout());
        try {
          Statement selectInSessionB = sessionB.createStatement(
            "SELECT value FROM testBeginTransaction");
          awaitQuery(
            Collections.emptyList(), row -> 0, selectInSessionB);

          // Now sessionA COMMITs and sessionB can now see the INSERT
          awaitNone(sessionA.commitTransaction());
          awaitQuery(
            List.of("A"), row -> row.get("value"), selectInSessionB);
        }
        finally {
          awaitNone(sessionB.close());
        }
      }
      finally {
        awaitExecution(sessionA.createStatement(
          "DROP TABLE testBeginTransaction"));
      }
    }
    finally {
      awaitNone(sessionA.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#close()}
   * TODO: Verify resource release upon Subscription.request(long). Consider
   * querying V$SESSION to check if JDBC connections are being closed correctly.
   */
  @Test
  public void testClose() {

    // Expect the connection to remain open until the close publisher is
    // subscribed to.
    Connection connection =
      Mono.from(newConnection()).block(connectTimeout());
    Publisher<Void> closePublisher = connection.close();

    // Expect the connection and objects it creates to be valid when the
    // connection is open
    awaitOne(true, connection.validate(ValidationDepth.LOCAL));
    awaitOne(true, connection.validate(ValidationDepth.REMOTE));
    Statement statement = connection.createStatement("SELECT 1 FROM dual");
    awaitQuery(List.of(1), row -> row.get(0, Integer.class), statement);

    // Expect the connection and objects it created to be invalid after the
    // connection is closed.
    awaitNone(closePublisher);
    awaitOne(false, connection.validate(ValidationDepth.LOCAL));
    awaitOne(false, connection.validate(ValidationDepth.REMOTE));
    awaitError(R2dbcException.class, statement.execute());

    // Expect any synchronous methods to throw IllegalStateException when the
    // connection is closed
    assertThrows(
      IllegalStateException.class,
      () -> connection.createStatement("SELECT 2 FROM dual"));
    assertThrows(
      IllegalStateException.class,
      () -> connection.isAutoCommit());
    assertThrows(
      IllegalStateException.class,
      () -> connection.createBatch());
    assertThrows(
      IllegalStateException.class,
      () -> connection.getMetadata());
    assertThrows(
      IllegalStateException.class,
      () -> connection.getTransactionIsolationLevel());

    // Expect multiple subscribers to see same the signal from the close()
    // publisher
    awaitNone(closePublisher);
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#commitTransaction()}
   */
  @Test
  public void testCommitTransaction() {
    Connection sessionA =
      Mono.from(sharedConnection()).block(connectTimeout());

    // Expect commit to be a no-op when auto-commit is enabled
    assertTrue(sessionA.isAutoCommit(),
      "Expected isAutoCommit() to return true for a new connection");
    awaitNone(sessionA.commitTransaction());
    awaitNone(sessionA.setAutoCommit(false));

    try {
      // Insert into this table during a transaction
      awaitExecution(sessionA.createStatement(
        "CREATE TABLE testCommitTransaction (value VARCHAR(10))"));

      try {
        // Verify that table INSERTs are not visible until the transaction is
        // committed.
        Statement insertInSessionA = sessionA.createStatement(
          "INSERT INTO testCommitTransaction VALUES ('A')");
        awaitUpdate(1, insertInSessionA);
        awaitUpdate(1, insertInSessionA);

        // Expect the commit publisher to defer the commit until a
        // subscriber subscribes.
        Publisher<Void> commitInSessionA = sessionA.commitTransaction();

        // sessionB doesn't see the INSERT made in sessionA's open transaction
        Connection sessionB =
          Mono.from(newConnection()).block(connectTimeout());
        try {
          Statement selectInSessionB = sessionB.createStatement(
            "SELECT value FROM testCommitTransaction");
          awaitQuery(
            Collections.emptyList(), row -> row.get(0), selectInSessionB);

          // Now sessionA COMMITs and sessionB can now see the INSERTs
          awaitNone(commitInSessionA);
          awaitQuery(
            List.of("A", "A"), row -> row.get(0), selectInSessionB);

          // Expect the commit publisher to NOT repeatedly commit for each
          // subscriber
          awaitUpdate(1, insertInSessionA);
          awaitNone(commitInSessionA);
          awaitQuery(
            List.of("A", "A"), row -> row.get(0), selectInSessionB);
        }
        finally {
          awaitNone(sessionB.close());
        }
      }
      finally {
        awaitExecution(sessionA.createStatement(
          "DROP TABLE testCommitTransaction"));
      }
    }
    finally {
      awaitNone(sessionA.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#createBatch()}
   */
  @Test
  public void testCreateBatch() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      awaitMany(
        List.of(0, 1, 2),
        Flux.from(connection.createBatch()
          .add("SELECT 0 FROM dual")
          .add("SELECT 1 FROM dual")
          .add("SELECT 2 FROM dual")
          .execute())
          .flatMap(result ->
            result.map((row, metadata) -> row.get(0, Integer.class))));
    }
    finally {
      awaitNone(connection.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#createStatement(String)}
   */
  @Test
  public void testCreateStatement() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());

    assertThrows(
      IllegalArgumentException.class,
      () -> connection.createStatement(null));

    try {
      awaitQuery(
        List.of("Hello, Oracle"),
        row -> row.get(0),
        connection.createStatement(
          "SELECT 'Hello, Oracle' AS greeting FROM dual"));
    }
    finally {
      awaitNone(connection.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#isAutoCommit()}
   */
  @Test
  public void testIsAutoCommit() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());

    try {
      assertTrue(connection.isAutoCommit(),
        "Unexpected value returned by isAutoCommit() for newly a created" +
          " connection.");
      awaitNone(connection.setAutoCommit(false));
      assertFalse(connection.isAutoCommit(),
        "Unexpected value returned by isAutoCommit() after" +
          " setAutoCommit(false).");

      awaitNone(connection.setAutoCommit(true));
      assertTrue(connection.isAutoCommit(),
        "Unexpected value returned by isAutoCommit() after" +
          " setAutoCommit(true).");
    }
    finally {
      awaitNone(connection.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#getMetadata()}
   */
  @Test
  public void testGetMetadata() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());

    try {
      assertNotNull(connection.getMetadata());
    }
    finally {
      awaitNone(connection.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#createSavepoint(String)}
   */
  @Test
  public void testCreateSavepoint() {
    // TODO: Oracle R2DBC does not implement
    // Connection.createSavepoint(String)
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#releaseSavepoint(String)}
   */
  @Test
  public void testReleaseSavepoint() {
    // TODO: Oracle R2DBC does not implement
    // Connection.releaseSavepoint(String)
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#rollbackTransaction()}
   */
  @Test
  public void testRollbackTransaction() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());

    // Expect a no-op when auto-commit is enabled
    assertTrue(connection.isAutoCommit(),
      "Unexpected value returned by isAutoCommit() for newly a created" +
        " connection.");
    awaitNone(connection.rollbackTransaction());

    try {
      // INSERT rows into this table, verify they are visible to the current
      // session prior to a rollback, and then not visible after a rollback.
      awaitExecution(connection.createStatement(
        "CREATE TABLE testRollbackTransaction (value VARCHAR(100))"));
      try {
        // INSERT values
        awaitNone(connection.setAutoCommit(false));
        Statement insert = connection.createStatement(
          "INSERT INTO testRollbackTransaction VALUES (:value)");
        List<String> values = List.of(
          // Alphabetical order matches order of ORDER BY value in SQL
          "Bonjour, Oracle",
          "Hello, Oracle",
          "Hola, Oracle",
          "Namaste, Oracle",
          "Ni hao, Oracle");
        values.forEach(value -> insert.bind("value", value).add());
        awaitUpdate(
          values.stream().map(value -> 1).collect(Collectors.toList()),
          insert);

        // Expect the rollback publisher to defer execution
        Publisher<Void> rollbackPublisher = connection.rollbackTransaction();
        Statement select = connection.createStatement(
          "SELECT value FROM testRollbackTransaction ORDER BY value");
        awaitQuery(values, row -> row.get("value", String.class), select);
        awaitNone(rollbackPublisher);
        awaitQuery(
          Collections.emptyList(), row -> (String) row.get("value"), select);

        // Expect rollback publisher to not repeat execution for each
        // subscriber.
        awaitUpdate(1, insert.bind(0, "Aloha, Oracle"));
        awaitQuery(List.of("Aloha, Oracle"), row -> row.get(0), select);
        awaitNone(rollbackPublisher);
        awaitQuery(List.of("Aloha, Oracle"), row -> row.get(0), select);
      }
      finally {
        awaitExecution(connection.createStatement(
          "DROP TABLE testRollbackTransaction"));
      }
    }
    finally {
      awaitNone(connection.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#rollbackTransactionToSavepoint(String)}
   */
  @Test
  public void testRollbackTransactionToSavepoint() {
    // TODO: Oracle R2DBC does not implement
    // Connection.rollbackTransactionToSavepoint(String)
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#setAutoCommit(boolean)}
   */
  @Test
  public void testSetAutoCommit() {
    Connection sessionA =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      // Insert into this table with auto-commit enabled/disabled
      awaitExecution(sessionA.createStatement(
        "CREATE TABLE testSetAutoCommit (value VARCHAR(10))"));
      try {
        // sessionB doesn't see uncommitted INSERTs made in sessionA's
        // transaction
        Connection sessionB =
          Mono.from(newConnection()).block(connectTimeout());
        try {
          // Statement executions are automatically committed when auto-commit
          // is enabled. Expect sessionB to see sessionA's INSERT
          assertTrue(sessionA.isAutoCommit(),
            "Unexpected value returned by isAutoCommit() for newly a created" +
              " connection.");
          Statement insertInSessionA = sessionA.createStatement(
            "INSERT INTO testSetAutoCommit VALUES (?)");
          awaitUpdate(1, insertInSessionA.bind(0, "A"));
          Statement selectInSessionB = sessionB.createStatement(
            "SELECT value FROM testSetAutoCommit ORDER BY value");
          awaitQuery(List.of("A"), row -> row.get(0), selectInSessionB);

          // Expect the setAutoCommitPublisher to not change the auto-commit
          // mode until it has been subscribed to. Auto-commit remains enabled
          // for sessionA's next INSERT
          Publisher<Void> disableAutoCommitPublisher =
            Mono.from(sessionA.setAutoCommit(false));
          assertTrue(sessionA.isAutoCommit(),
            "Unexpected value returned by isAutoCommit() for newly a created" +
              " connection.");
          awaitUpdate(1, insertInSessionA.bind(0, "A"));
          awaitQuery(List.of("A", "A"), row -> row.get(0), selectInSessionB);

          // Disable auto-commit, and expect sessionA's INSERT to not be visible
          // in sessionB
          awaitNone(disableAutoCommitPublisher);
          awaitUpdate(1, insertInSessionA.bind(0, "B"));
          awaitQuery(List.of("A", "A"), row -> row.get(0), selectInSessionB);

          // Expect a no-op when auto-commit isn't changed
          awaitNone(sessionA.setAutoCommit(false));
          awaitUpdate(1, insertInSessionA.bind(0, "B"));
          awaitQuery(List.of("A", "A"), row -> row.get(0), selectInSessionB);

          // Expect commit to make INSERTs visible in sessionB
          awaitNone(sessionA.commitTransaction());
          awaitQuery(
            List.of("A", "A", "B", "B"), row -> row.get(0), selectInSessionB);

          // Expect INSERTs to be commited when auto-commit is re-enabled. The
          // auto-commit mode doesn't change until the setAutoCommit publisher is
          // subscribed to.
          Publisher<Void> enableAutoCommitPublisher =
            sessionA.setAutoCommit(true);
          assertFalse(sessionA.isAutoCommit(),
            "Unexpected value returned by isAutoCommit() before subscribing to"
              + " setAutoCommit(true) publisher");
          awaitMany(
            List.of(1, 1),
            Flux.from(sessionA.createBatch()
              .add("INSERT INTO testSetAutoCommit VALUES ('C')")
              .add("INSERT INTO testSetAutoCommit VALUES ('C')")
              .execute())
              .flatMap(Result::getRowsUpdated));
          awaitQuery(
            List.of("A", "A", "B", "B"), row -> row.get(0), selectInSessionB);
          awaitNone(enableAutoCommitPublisher);
          assertTrue(sessionA.isAutoCommit(),
            "Unexpected value returned by isAutoCommit() after subscribing to"
              + " setAutoCommit(true) publisher");
          awaitQuery(
            List.of("A", "A", "B", "B", "C", "C"), row -> row.get(0), selectInSessionB);

          // Expect setAutoCommit(false) publisher to not repeat its action
          // for each subscriber.
          awaitNone(disableAutoCommitPublisher);
          assertTrue(sessionA.isAutoCommit(),
            "Unexpected value returned by isAutoCommit() after subscribing to"
              + " setAutoCommit(false) publisher multiple times");

          // Expect setAutoCommit(true) publisher to not repeat its action
          // for each subscriber.
          awaitNone(sessionA.setAutoCommit(false));
          assertFalse(sessionA.isAutoCommit(),
            "Unexpected value returned by isAutoCommit() after subscribing to"
              + " setAutoCommit(false)");
          awaitUpdate(
            List.of(1, 1),
            insertInSessionA
              .bind(0, "D").add()
              .bind(0, "D").add());
          awaitNone(enableAutoCommitPublisher);
          assertFalse(sessionA.isAutoCommit(),
            "Unexpected value returned by isAutoCommit() after subscribing to"
              + " setAutoCommit(true) publisher multiple times");
          awaitQuery(
            List.of("A", "A", "B", "B", "C", "C"), row -> row.get(0),
            selectInSessionB);

          // Expect rolled back changes to not be visible when auto-commit is
          // reenabled
          awaitNone(sessionA.rollbackTransaction());
          awaitNone(sessionA.setAutoCommit(true));
          awaitQuery(
            List.of("A", "A", "B", "B", "C", "C"), row -> row.get(0),
            selectInSessionB);
        }
        finally {
          awaitNone(sessionA.close());
        }
      }
      finally {
        awaitExecution(sessionA.createStatement(
          "DROP TABLE testSetAutoCommit"));
      }
    }
    finally {
      awaitNone(sessionA.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#getTransactionIsolationLevel()}
   */
  @Test
  public void testGetTransactionIsolationLevel() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      // Expect the initial isolation level to be READ_COMMITTED
      assertEquals(
        IsolationLevel.READ_COMMITTED,
        connection.getTransactionIsolationLevel(),
        "Unexpected return value of getTransactionIsolationLevel() for a" +
          " newly created connection");
    }
    finally {
      awaitNone(connection.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#setTransactionIsolationLevel(IsolationLevel)}
   * when an unsupported isolation level is provided as input.
   */
  @Test
  public void testSetTransactionIsolationLevelUnsupported() {
    Connection sessionA =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {

      // Verify isolation levels by reading inserts made into this table. The
      // table name is an abbreviation of
      // testSetTransactionIsolationLevelUnsupported because the full name
      // might exceed the maximum identifier length on some databases
      awaitExecution(sessionA.createStatement(
        "CREATE TABLE tstilUnsupported" +
          " (value VARCHAR(10))"));

      try {
        // Set the READ COMMITTED level, and expect the sessionA to remain at
        // this level after setting unsupported levels. Expect setting any
        // level other than READ COMMITTED to result in onError
        awaitNone(
          sessionA.setTransactionIsolationLevel(IsolationLevel.READ_COMMITTED));
        awaitError(
          R2dbcException.class,
          sessionA.setTransactionIsolationLevel(
            IsolationLevel.READ_UNCOMMITTED));
        awaitError(
          R2dbcException.class,
          sessionA.setTransactionIsolationLevel(
            IsolationLevel.REPEATABLE_READ));
        awaitError(
          R2dbcException.class,
          sessionA.setTransactionIsolationLevel(
            IsolationLevel.SERIALIZABLE));
        assertEquals(
          IsolationLevel.READ_COMMITTED,
          sessionA.getTransactionIsolationLevel(),
          "Unexpected return value of getTransactionIsolationLevel() after " +
            "setting an unsupported isolation level");

        // Verify the READ COMMITTED level remains set by checking if a phantom
        // read is possible. READ COMMITTED is the only isolation level
        // supported by Oracle Database that allows phantom reads.
        Connection sessionB =
          Mono.from(newConnection()).block(connectTimeout());
        try {
          awaitNone(sessionB.beginTransaction());
          awaitUpdate(1, sessionB.createStatement(
            "INSERT INTO tstilUnsupported" +
              " VALUES('A')"));
          awaitNone(sessionA.beginTransaction());
          awaitNone(sessionB.commitTransaction());
          awaitQuery(
            List.of("A"),
            row -> row.get("value"),
            sessionA.createStatement(
              "SELECT value FROM tstilUnsupported"));
        }
        finally {
          awaitNone(sessionB.close());
        }
      }
      finally {
        awaitExecution(sessionA.createStatement(
          "DROP TABLE tstilUnsupported"));
      }
    }
    finally {
      assertNull(
        Mono.from(sessionA.close()).block(connectTimeout()),
        "Unexpected onNext signal from close()");
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#setTransactionIsolationLevel(IsolationLevel)}
   * when {@link IsolationLevel#READ_COMMITTED} is supplied as an argument.
   */
  @Test
  public void testSetTransactionIsolationLevelReadCommitted() {
    Connection sessionA =
      Mono.from(sharedConnection()).block(connectTimeout());
    awaitNone(
      sessionA.setTransactionIsolationLevel(IsolationLevel.READ_COMMITTED));

    try {
      // Verify isolation levels by reading inserts made into this table. The
      // table name is an abbreviation of
      // testSetTransactionIsolationLevelReadCommitted because the full name
      // might exceed the maximum identifier length on some databases
      awaitExecution(sessionA.createStatement(
        "CREATE TABLE tstilReadCommited" +
          " (value VARCHAR(10))"));

      try {
        // sessionB executes writes to the test table, and sessionA reads
        // from the test table.
        Connection sessionB =
          Mono.from(newConnection()).block(connectTimeout());
        assertEquals(
          IsolationLevel.READ_COMMITTED,
          sessionB.getTransactionIsolationLevel(),
          "Unexpected return value of getTransactionIsolationLevel() for a"
            + " newly created connection");
        try {
          Statement insertInSessionB = sessionB.createStatement(
            "INSERT INTO tstilReadCommited" +
              " VALUES (?)");
          Statement updateInSessionB = sessionB.createStatement(
            "UPDATE tstilReadCommited" +
              " SET value = :newValue WHERE value = :oldValue");
          Statement selectInSessionA = sessionA.createStatement(
            "SELECT value FROM tstilReadCommited" +
              " ORDER BY value");

          // sessionB INSERTs a row and commits before sessionA begins a
          // READ COMMITTED transaction. The row is visible to sessionA because
          // it was committed before the transaction began.
          awaitNone(sessionB.beginTransaction());
          awaitUpdate(1, insertInSessionB.bind(0, "A"));
          awaitNone(sessionB.commitTransaction());
          awaitNone(sessionA.beginTransaction());
          awaitQuery(List.of("A"), row -> row.get(0), selectInSessionA);

          // Expect setting the READ COMMITTED level to prevent dirty reads.
          // sessionB UPDATEs a row and doesn't commit. The updated row is not
          // visible to sessionA.
          awaitUpdate(
            1, updateInSessionB.bind("oldValue", "A").bind("newValue", "B"));
          awaitQuery(List.of("A"), row -> row.get(0), selectInSessionA);

          // Expect setting the READ COMMITTED level to allow non-repeatable
          // reads. The UPDATE is committed in sessionB. The updated row is
          // visible to sessionA.
          awaitNone(sessionB.commitTransaction());
          awaitQuery(List.of("B"), row -> row.get(0), selectInSessionA);

          // Expect setting the READ COMMITTED level to allow Phantom reads.
          // sessionB INSERTs a new row and commits. The INSERTed row is
          // visible to sessionA.
          awaitNone(sessionB.beginTransaction());
          awaitUpdate(1, insertInSessionB.bind(0, "C"));
          awaitNone(sessionB.commitTransaction());
          awaitQuery(List.of("B", "C"), row -> row.get(0), selectInSessionA);

          // sessionA completes it's transaction and all changes from sessionB
          // become visible.
          awaitUpdate(1, sessionA.createStatement(
            "INSERT INTO tstilReadCommited" +
              " VALUES('D')"));
          awaitNone(sessionA.commitTransaction());
          awaitQuery(
            List.of("B", "C", "D"), row -> row.get(0), selectInSessionA);
        }
        finally {
          awaitNone(sessionB.close());
        }
      }
      finally {
        awaitExecution(sessionA.createStatement(
          "DROP TABLE tstilReadCommited"));
      }
    }
    finally {
      awaitNone(sessionA.close());
    }
  }

  /**
   * Verifies the implementation of
   * {@link OracleConnectionImpl#validate(ValidationDepth)}.
   * TODO: Verify REMOTE validation by killing the database session as SYSDBA
   * and then expecting the validation to fail.
   */
  @Test
  public void testValidate() {
    Connection connection = Mono.from(newConnection()).block(connectTimeout());
    try {
      Publisher<Boolean> validateLocalPublisher =
        connection.validate(ValidationDepth.LOCAL);
      Publisher<Boolean> validateRemotePublisher =
        connection.validate(ValidationDepth.REMOTE);

      // Expect validation publishers to emit true when the connection is open.
      awaitOne(true, validateLocalPublisher);
      awaitOne(true, validateRemotePublisher);

      // Expect unsubscribed validation publishers to emit false when the
      // connection is closed
      awaitNone(connection.close());
      awaitOne(false, connection.validate(ValidationDepth.LOCAL));
      awaitOne(false, connection.validate(ValidationDepth.REMOTE));

      // Expect validation publishers to not repeat the validation for each
      // subscriber.
      awaitOne(true, validateLocalPublisher);
      awaitOne(true, validateRemotePublisher);
    }
    finally {
      awaitNone(connection.close());
    }
  }

}