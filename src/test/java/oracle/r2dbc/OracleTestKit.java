/*
  Reactive Relational Database Connectivity
  Copyright 2017-2018 the original author or authors.

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

package oracle.r2dbc;

import io.r2dbc.spi.*;
import io.r2dbc.spi.test.TestKit;
import oracle.r2dbc.util.OracleTestKitSupport;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.support.AbstractLobCreatingPreparedStatementCallback;
import org.springframework.jdbc.support.lob.DefaultLobHandler;
import org.springframework.jdbc.support.lob.LobCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <p>
 * Subclass implementation of the R2DBC {@link TestKit} for Oracle Database.
 * This test kit implementation overrides super class test methods that are
 * fundamentally incompatible with Oracle Database. The javadoc of each
 * overriden test method describes why it must be overriden when interacting
 * with an Oracle Database.
 * </p><p>
 * The developers of the Oracle R2DBC Driver are mindful of the fact that
 * distributing a non-compliant implementation of the SPI would create
 * confusion about what behavior is to be expected from an R2DBC driver. To
 * avoid this confusion, we exercised our best judgement when determining if
 * it would be acceptable to override any test case of the R2DBC SPI TCK. It
 * should only be acceptable to do so when the behavior verified by the
 * overriding method would still be correct according the written specification
 * of the R2DBC SPI.
 * </p><p>
 * If you think that our judgement was incorrect, then we strongly encourage
 * you to bring this to our attention. The easiest way to contact us is through
 * GitHub.
 * </p>
 *
 * @author  harayuanwang, Michael-A-McMahon
 * @since   0.1.0
 */
public class OracleTestKit
  extends OracleTestKitSupport implements TestKit<Integer> {

  static <T> Mono<T> close(Connection connection) {
    return Mono.from(connection
      .close())
      .then(Mono.empty());
  }

  @Override
  public ConnectionFactory getConnectionFactory() {
    return connectionFactory;
  }

  @Override
  public String getCreateTableWithAutogeneratedKey() {
    return "CREATE TABLE test (" +
      "id NUMBER GENERATED ALWAYS AS IDENTITY, value NUMBER)";
  }

  @Override
  public String getInsertIntoWithAutogeneratedKey() {
    return "INSERT INTO test(value) VALUES(100)";
  }

  @Override
  public String getPlaceholder(int index) {
    return String.format(":%d", index + 1);
  }

  @Override
  public Integer getIdentifier(int index) {
    return index;
  }

  @Override
  public JdbcOperations getJdbcOperations() {
    JdbcOperations jdbcOperations = CONFIG.getJDBCOperations();

    if (jdbcOperations == null) {
      throw new IllegalStateException("JdbcOperations not yet initialized");
    }

    return jdbcOperations;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Overrides the default implementation to expect BigDecimal as the default
   * Java type mapping for INTEGER columns. The default implementation of this
   * test in {@link TestKit#changeAutoCommitCommitsTransaction()}expects the
   * R2DBC Specification's default type mapping guideline for INTEGER
   * columns, which is java.lang.Integer.
   * </p><p>
   * This override is necessary because the Oracle Database describes INTEGER
   * type columns as NUMBER types with a precision of 38. This description
   * does not provide enough information for the Oracle R2DBC Driver to
   * distinguish between the NUMBER and INTEGER type, so it uses the default
   * type mapping for NUMBER, which is BigDecimal.
   * </p><p>
   * This override does not prevent this test from verifying that
   * setAutoCommit(boolean) will commit an active transaction.
   * </p>
   */
  @Test
  @Override
  public void changeAutoCommitCommitsTransaction() {
    Mono.from(getConnectionFactory().create())
      .flatMapMany(connection ->
        Flux.from(connection.setAutoCommit(false))
          .thenMany(connection.beginTransaction())
          .thenMany(connection.createStatement("INSERT INTO test VALUES(200)").execute())
          .flatMap(Result::getRowsUpdated)
          .thenMany(connection.setAutoCommit(true))
          .thenMany(connection.createStatement("SELECT value FROM test").execute())
          .flatMap(it -> it.map((row, metadata) -> row.get("value")))
          .concatWith(close(connection))
      )
      .as(StepVerifier::create)
      // Note the overridden behavior below: Expect the BigDecimal type
      .expectNext(BigDecimal.valueOf(200)).as("autoCommit(true) committed the transaction. Expecting a value to be present")
      .verifyComplete();
  }

  /**
   * {@inheritDoc}
   * <p>
   * Overrides the default implementation to expect ColumnMetadata.getName() to
   * return the column's alias in all UPPERCASE letters. The default
   * implementation of this test in
   * {@link TestKit#columnMetadata()} expects getName() to return the alias
   * as it appears in the SQL command, where it is all lower case.
   * </p><p>
   * This override is necessary because the Oracle Database describes aliased
   * columns with the alias converted to all UPPERCASE characters. This
   * description does not provide enough information for the Oracle R2DBC
   * Driver to determine the case of characters as they appeared in the
   * original SQL command.
   * </p><p>
   * This override does not prevent this test from verifying that
   * ColumnMetadata.getName() returns the alias (except not in the original
   * character case), or from verifying case insensitive name matching with
   * RowMetadata.getColumnMetadata(String) and
   * RowMMetadata.getColumnNames().contains(Object)
   * </p>
   */
  @Test
  @Override
  public void columnMetadata() {
    getJdbcOperations().execute("INSERT INTO test_two_column VALUES (100, 'hello')");

    Mono.from(getConnectionFactory().create())
      .flatMapMany(connection -> Flux.from(connection

        .createStatement("SELECT col1 AS value, col2 AS value FROM test_two_column")
        .execute())
        .flatMap(result -> {
          return result.map((row, rowMetadata) -> {
            Collection<String> columnNames = rowMetadata.getColumnNames();
            return Arrays.asList(rowMetadata.getColumnMetadata("value").getName(), rowMetadata.getColumnMetadata("VALUE").getName(), columnNames.contains("value"), columnNames.contains(
              "VALUE"));
          });
        })
        .flatMapIterable(Function.identity())
        .concatWith(close(connection)))
      .as(StepVerifier::create)
      // Note the overridden behavior below: Expect alias "value" to be ALL CAPS
      .expectNext("VALUE").as("Column label col1")
      .expectNext("VALUE").as("Column label col1 (get by uppercase)")
      .expectNext(true).as("getColumnNames.contains(value)")
      .expectNext(true).as("getColumnNames.contains(VALUE)")
      .verifyComplete();
  }

  /**
   * {@inheritDoc}
   * <p>
   * Overrides the default implementation to expect BigDecimal as the default
   * Java type mapping for INTEGER columns. The default implementation of this
   * test in {@link TestKit#duplicateColumnNames()} expects the R2DBC
   * Specification's default type mapping guideline for INTEGER columns,
   * which is java.lang.Integer.
   * </p><p>
   * This override is necessary because the Oracle Database describes INTEGER
   * type columns as NUMBER types with a precision of 38. This description
   * does not provide enough information for the Oracle R2DBC Driver to
   * distinguish between the NUMBER and INTEGER type, so it uses the default
   * type mapping for NUMBER, which is BigDecimal.
   * </p><p>
   * This override does not prevent this test from verifying a case-insensitive
   * name match is implemented by Row.get(String) when duplicate column names
   * are present.
   * </p>
   */
  @Test
  @Override
  public void duplicateColumnNames() {
    getJdbcOperations().execute("INSERT INTO test_two_column VALUES (100, 'hello')");

    Mono.from(getConnectionFactory().create())
      .flatMapMany(connection -> Flux.from(connection

        .createStatement("SELECT col1 AS value, col2 AS value FROM test_two_column")
        .execute())

        .flatMap(result -> result
          .map((row, rowMetadata) -> Arrays.asList(row.get("value"), row.get("VALUE"))))
        .flatMapIterable(Function.identity())

        .concatWith(close(connection)))
      .as(StepVerifier::create)
      // Note the overridden behavior below: Expect the BigDecimal type
      .expectNext(BigDecimal.valueOf(100L)).as("value from col1")
      .expectNext(BigDecimal.valueOf(100L)).as("value from col1 (upper case)")
      .verifyComplete();
  }

  /**
   * {@inheritDoc}
   * <p>
   * Overrides the default implementation to expect {@link Clob} as the default
   * Java type mapping for CLOB columns. The default implementation of this
   * test in {@link TestKit#duplicateColumnNames()} expects the R2DBC
   * Specification's default type mapping guideline for CLOB columns, which
   * is java.lang.String.
   * </p><p>
   * Mapping {@code BLOB/CLOB} to {@code ByteBuffer/String} can not be
   * supported because the Oracle Database allows LOBs to store terabytes of
   * data. If the Oracle R2DBC Driver were to fully materialize a LOB
   * prior to emitting this row, the amount of memory necessary to do so
   * might exceed the capacity of {@code ByteBuffer/String}, and could even
   * exceed the amount of memory available to the Java Virtual Machine.
   * </p><p>
   * This override does not prevent this test from verifying the behavior of a
   * Clob returned from a SQL select query.
   * </p>
   */
  @Test
  public void clobSelect() {
    getJdbcOperations().execute("INSERT INTO clob_test VALUES (?)", new AbstractLobCreatingPreparedStatementCallback(new DefaultLobHandler()) {

      @Override
      protected void setValues(PreparedStatement ps, LobCreator lobCreator) throws SQLException {
        lobCreator.setClobAsString(ps, 1, "test-value");
      }

    });

    // CLOB as String is not supported
    Mono.from(getConnectionFactory().create())
      .flatMapMany(connection -> Flux.from(connection

        .createStatement("SELECT * from clob_test")
        .execute())
        .flatMap(result -> result
          // Note the overridden behavior below: String conversion is an error
          .map((row, rowMetadata) -> {
            try {
              row.get("value", String.class);
              return "Returns normally";
            }
            catch (R2dbcException expected) {
              return "Throws R2dbcException";
            }
          }))

        .concatWith(close(connection)))
      .as(StepVerifier::create)
      .expectNext("Throws R2dbcException").as("get CLOB as String")
      .verifyComplete();

    // CLOB consume as Clob
    Mono.from(getConnectionFactory().create())
      .flatMapMany(connection -> Flux.from(connection

        .createStatement("SELECT * from clob_test")
        .execute())
        .flatMap(result -> result
          // Note the overridden behavior below: The default mapping is Clob
          .map((row, rowMetadata) -> (Clob)row.get("value")))
        .flatMap(clob -> Flux.from(clob.stream())
          .reduce(new StringBuilder(), StringBuilder::append)
          .map(StringBuilder::toString)
          .concatWith(TestKit.discard(clob)))

        .concatWith(close(connection)))
      .as(StepVerifier::create)
      .expectNext("test-value").as("value from select")
      .verifyComplete();
  }


  /**
   * {@inheritDoc}
   * <p>
   * Overrides the default implementation to expect Blob as the default Java
   * type mapping for BLOB columns. The default implementation of this
   * test in {@link TestKit#duplicateColumnNames()} expects the R2DBC
   * Specification's default type mapping guideline for BLOB columns,
   * which is java.nio.ByteBuffer.
   * </p><p>
   * Mapping {@code BLOB/CLOB} to {@code ByteBuffer/String} can not be
   * supported because the Oracle Database allows LOBs to store terabytes of
   * data. If the Oracle R2DBC Driver were to fully materialize a LOB
   * prior to emitting this row, the amount of memory necessary to do so
   * might exceed the capacity of {@code ByteBuffer/String}, and could even
   * exceed the amount of memory available to the Java Virtual Machine.
   * </p><p>
   * This override does not prevent this test from verifying the behavior of a
   * Blob returned from a SQL select query.
   * </p>
   */
  @Test
  public void blobSelect() {
    getJdbcOperations().execute("INSERT INTO blob_test VALUES (?)", new AbstractLobCreatingPreparedStatementCallback(new DefaultLobHandler()) {

      @Override
      protected void setValues(PreparedStatement ps, LobCreator lobCreator) throws SQLException {
        lobCreator.setBlobAsBytes(ps, 1, StandardCharsets.UTF_8.encode("test-value").array());
      }

    });

    // BLOB as ByteBuffer is not supported
    Mono.from(getConnectionFactory().create())
      .flatMapMany(connection -> Flux.from(connection

        .createStatement("SELECT * from blob_test")
        .execute())

        .flatMap(result -> result
          // Note the overridden behavior below: ByteBuffer conversion is not
          // supported
          .map((row, rowMetadata) -> {
            try {
              row.get("value", ByteBuffer.class);
              return "Returns normally";
            }
            catch (R2dbcException expected) {
              return "Throws R2dbcException";
            }
          }))
        .concatWith(close(connection)))
      .as(StepVerifier::create)
      .expectNext("Throws R2dbcException").as("get BLOB as ByteBuffer")
      .verifyComplete();

    // BLOB as Blob
    Mono.from(getConnectionFactory().create())
      .flatMapMany(connection -> Flux.from(connection

        .createStatement("SELECT * from blob_test")
        .execute())
        .flatMap(result -> result
          .map((row, rowMetadata) -> row.get("value", Blob.class)))
        .flatMap(blob -> Flux.from(blob.stream())
          .reduce(ByteBuffer::put)
          .concatWith(TestKit.discard(blob)))

        .concatWith(close(connection)))
      .as(StepVerifier::create)
      .expectNextMatches(actual -> {
        ByteBuffer expected = StandardCharsets.UTF_8.encode("test-value");
        return Arrays.equals(expected.array(), actual.array());
      })
      .verifyComplete();
  }


  @Disabled("Compound statements are not supported by Oracle Database")
  @Test
  @Override
  public void compoundStatement() {}

  @Disabled("Disabled until savepoint is implemented")
  @Test
  @Override
  public void savePoint() {}

  @Disabled("Disabled until savepoint is implemented")
  @Test
  @Override
  public void savePointStartsTransaction() {}

}

/*
   MODIFIED             (MM/DD/YY)
    Michael-A-McMahon   09/22/20 - Blob and Clob tests
    harayuanwang        05/12/20 - Creation
 */

