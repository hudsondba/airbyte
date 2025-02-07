/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.integrations.source.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.resources.MoreResources;
import io.airbyte.db.Database;
import io.airbyte.db.Databases;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.Field;
import io.airbyte.protocol.models.Field.JsonSchemaPrimitive;
import io.airbyte.test.utils.PostgreSQLContainerHelper;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class PostgresSourceTest {

  private static final String STREAM_NAME = "public.id_and_name";
  private static final ConfiguredAirbyteCatalog CONFIGURED_CATALOG = CatalogHelpers.createConfiguredAirbyteCatalog(
      STREAM_NAME,
      Field.of("id", JsonSchemaPrimitive.NUMBER),
      Field.of("name", JsonSchemaPrimitive.STRING));

  private static final Set<AirbyteMessage> UTF8_MESSAGES = Sets.newHashSet(
      new AirbyteMessage().withType(Type.RECORD)
          .withRecord(
              new AirbyteRecordMessage().withStream(STREAM_NAME).withData(Jsons.jsonNode(ImmutableMap.of("id", 1, "name", "\u2013 someutfstring")))),
      new AirbyteMessage().withType(Type.RECORD)
          .withRecord(new AirbyteRecordMessage().withStream(STREAM_NAME).withData(Jsons.jsonNode(ImmutableMap.of("id", 2, "name", "\u2215")))));

  private static PostgreSQLContainer<?> PSQL_DB;

  @BeforeAll
  static void init() {
    PSQL_DB = new PostgreSQLContainer<>("postgres:13-alpine");
    PSQL_DB.start();

  }

  @BeforeEach
  void setup() throws Exception {
    final String dbName = "db_" + RandomStringUtils.randomAlphabetic(10).toLowerCase();
    final JsonNode config = getConfig(PSQL_DB, dbName);

    final String initScriptName = "init_" + dbName.concat(".sql");
    MoreResources.writeResource(initScriptName, "CREATE DATABASE " + dbName + ";");
    PostgreSQLContainerHelper.runSqlScript(MountableFile.forClasspathResource(initScriptName), PSQL_DB);

    final Database database = getDatabaseFromConfig(config);
    database.query(ctx -> {
      ctx.fetch("CREATE TABLE id_and_name(id INTEGER, name VARCHAR(200));");
      ctx.fetch("INSERT INTO id_and_name (id, name) VALUES (1,'goku'),  (2, 'vegeta'), (3, 'piccolo');");
      ctx.fetch("CREATE SCHEMA test_another_schema;");
      ctx.fetch("CREATE TABLE test_another_schema.id_and_name(id INTEGER, name VARCHAR(200));");
      ctx.fetch("INSERT INTO test_another_schema.id_and_name (id, name) VALUES (1,'tom'),  (2, 'jerry');");
      return null;
    });
    database.close();
  }

  private Database getDatabaseFromConfig(JsonNode config) {
    return Databases.createDatabase(
        config.get("username").asText(),
        config.get("password").asText(),
        String.format("jdbc:postgresql://%s:%s/%s",
            config.get("host").asText(),
            config.get("port").asText(),
            config.get("database").asText()),
        "org.postgresql.Driver",
        SQLDialect.POSTGRES);
  }

  private JsonNode getConfig(PostgreSQLContainer<?> psqlDb, String dbName) {
    return Jsons.jsonNode(ImmutableMap.builder()
        .put("host", psqlDb.getHost())
        .put("port", psqlDb.getFirstMappedPort())
        .put("database", dbName)
        .put("username", psqlDb.getUsername())
        .put("password", psqlDb.getPassword())
        .build());
  }

  private JsonNode getConfig(PostgreSQLContainer<?> psqlDb) {
    return getConfig(psqlDb, psqlDb.getDatabaseName());
  }

  @AfterAll
  static void cleanUp() {
    PSQL_DB.close();
  }

  @Test
  public void testCanReadUtf8() throws Exception {
    // force the db server to start with sql_ascii encoding to verify the tap can read UTF8 even when
    // default settings are in another encoding
    try (PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:13-alpine").withCommand("postgres -c client_encoding=sql_ascii")) {
      db.start();
      final JsonNode config = getConfig(db);
      try (final Database database = getDatabaseFromConfig(config)) {
        database.query(ctx -> {
          ctx.fetch("CREATE TABLE id_and_name(id INTEGER, name VARCHAR(200));");
          ctx.fetch("INSERT INTO id_and_name (id, name) VALUES (1,E'\\u2013 someutfstring'),  (2, E'\\u2215');");
          return null;
        });
      }

      Set<AirbyteMessage> actualMessages = new PostgresSource().read(config, CONFIGURED_CATALOG, null).collect(Collectors.toSet());

      for (AirbyteMessage actualMessage : actualMessages) {
        if (actualMessage.getRecord() != null) {
          actualMessage.getRecord().setEmittedAt(null);
        }
      }

      assertEquals(UTF8_MESSAGES, actualMessages);
    }
  }

}
