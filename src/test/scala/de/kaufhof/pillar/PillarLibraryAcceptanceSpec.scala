package de.kaufhof.pillar

import java.util.Date

import com.datastax.driver.core.exceptions.InvalidQueryException
import com.datastax.driver.core.querybuilder.QueryBuilder
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, GivenWhenThen}

class PillarLibraryAcceptanceSpec extends AnyFeatureSpec
  with CassandraSpec
  with GivenWhenThen
  with BeforeAndAfter
  with Matchers
  with AcceptanceAssertions {

  val keyspaceName = "test_%d".format(System.currentTimeMillis())
  val simpleStrategy = SimpleStrategy()
  val appliedMigrationsTableName = "applied_migrations"

  val migrations = Seq(
    Migration("creates events table", new Date(System.currentTimeMillis() - 5000),
      Seq("""
        |CREATE TABLE events (
        |  batch_id text,
        |  occurred_at uuid,
        |  event_type text,
        |  payload blob,
        |  PRIMARY KEY (batch_id, occurred_at, event_type)
        |)
      """.stripMargin)),
    Migration("creates views table", new Date(System.currentTimeMillis() - 3000),
      Seq("""
        |CREATE TABLE views (
        |  id uuid PRIMARY KEY,
        |  url text,
        |  person_id int,
        |  viewed_at timestamp
        |)
      """.stripMargin),
      Some( Seq("""
          |DROP TABLE views
        """.stripMargin))),
    Migration("adds user_agent to views table", new Date(System.currentTimeMillis() - 1000),
      Seq("""
        |ALTER TABLE views
        |ADD user_agent text
          """.stripMargin), None), // Dropping a column is coming in Cassandra 2.0
    Migration("adds index on views.user_agent", new Date(),
      Seq("""
        |CREATE INDEX views_user_agent ON views(user_agent)
      """.stripMargin),
      Some( Seq("""
          |DROP INDEX views_user_agent
        """.stripMargin)))
  )
  val registry = Registry(migrations)
  val migrator = Migrator(registry, appliedMigrationsTableName)

  after {
    try {
      session.execute("DROP KEYSPACE %s".format(keyspaceName))
    } catch {
      case ok: InvalidQueryException =>
    }
  }

  feature("The operator can initialize a keyspace") {
    info("As an application operator")
    info("I want to initialize a Cassandra keyspace")
    info("So that I can manage the keyspace schema")

    scenario("initialize a non-existent keyspace") {
      Given("a non-existent keyspace")

      When("the migrator initializes the keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Then("the keyspace contains a applied_migrations column family")
      assertEmptyAppliedMigrationsTable()
    }

    scenario("initialize a non-existent keyspace with a non default applied_migrations table") {
      Given("a non-existent keyspace")

      When("the migrator initializes the keyspace")
      val migrator = Migrator(registry, "applied_migrations_non_default")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Then("the keyspace contains a applied_migrations column family")
      assertEmptyAppliedMigrationsTable("applied_migrations_non_default")
    }

    scenario("initialize an existing keyspace without a applied_migrations column family") {
      Given("an existing keyspace")
      session.execute(s"CREATE KEYSPACE $keyspaceName WITH replication = ${simpleStrategy.cql}")

      When("the migrator initializes the keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Then("the keyspace contains a applied_migrations column family")
      assertEmptyAppliedMigrationsTable()
    }

    scenario("initialize an existing keyspace with a applied_migrations column family") {
      Given("an existing keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      When("the migrator initializes the keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Then("the migration completes successfully")
    }
  }

  feature("The operator can destroy a keyspace") {
    info("As an application operator")
    info("I want to destroy a Cassandra keyspace")
    info("So that I can clean up automated tasks")

    scenario("destroy a keyspace") {
      Given("an existing keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      When("the migrator destroys the keyspace")
      migrator.destroy(session, keyspaceName)

      Then("the keyspace no longer exists")
      assertKeyspaceDoesNotExist()
    }

    scenario("destroy a bad keyspace") {
      Given("a datastore with a non-existing keyspace")

      When("the migrator destroys the keyspace")

      Then("the migrator throws an exception")
      assertThrows[Throwable] {
        migrator.destroy(session, keyspaceName)
      }
    }
  }

  feature("The operator can apply migrations") {
    info("As an application operator")
    info("I want to migrate a Cassandra keyspace from an older version of the schema to a newer version")
    info("So that I can run an application using the schema")

    scenario("all migrations") {
      Given("an initialized, empty, keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Given("a migration that creates an events table")
      Given("a migration that creates a views table")

      When("the migrator migrates the schema")
      migrator.migrate(cluster.connect(keyspaceName))

      Then("the keyspace contains the events table")
      session.execute(QueryBuilder.select().from(keyspaceName, "events")).all().size() should equal(0)

      And("the keyspace contains the views table")
      session.execute(QueryBuilder.select().from(keyspaceName, "views")).all().size() should equal(0)

      And("the applied_migrations table records the migrations")
      session.execute(QueryBuilder.select().from(keyspaceName, "applied_migrations")).all().size() should equal(4)
    }

    scenario("all migrations for a non default applied migrations table name") {
      Given("an initialized, empty, keyspace")
      val migrator = Migrator(registry, "applied_migrations_non_default")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Given("a migration that creates an events table")
      Given("a migration that creates a views table")

      When("the migrator migrates the schema")
      migrator.migrate(cluster.connect(keyspaceName))

      Then("the keyspace contains the events table")
      session.execute(QueryBuilder.select().from(keyspaceName, "events")).all().size() should equal(0)

      And("the keyspace contains the views table")
      session.execute(QueryBuilder.select().from(keyspaceName, "views")).all().size() should equal(0)

      And("the applied_migrations table records the migrations")
      session.execute(QueryBuilder.select().from(keyspaceName, "applied_migrations_non_default")).all().size() should equal(4)
    }

    scenario("some migrations") {
      Given("an initialized, empty, keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Given("a migration that creates an events table")
      Given("a migration that creates a views table")

      When("the migrator migrates with a cut off date")
      migrator.migrate(cluster.connect(keyspaceName), Some(migrations.head.authoredAt))

      Then("the keyspace contains the events table")
      session.execute(QueryBuilder.select().from(keyspaceName, "events")).all().size() should equal(0)

      And("the applied_migrations table records the migration")
      session.execute(QueryBuilder.select().from(keyspaceName, "applied_migrations")).all().size() should equal(1)
    }

    scenario("skip previously applied migration") {
      Given("an initialized keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Given("a set of migrations applied in the past")
      migrator.migrate(cluster.connect(keyspaceName))

      When("the migrator applies migrations")
      migrator.migrate(cluster.connect(keyspaceName))

      Then("the migration completes successfully")
    }
  }

  feature("The operator can reverse migrations") {
    info("As an application operator")
    info("I want to migrate a Cassandra keyspace from a newer version of the schema to an older version")
    info("So that I can run an application using the schema")

    scenario("reversible previously applied migration") {
      Given("an initialized keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Given("a set of migrations applied in the past")
      migrator.migrate(cluster.connect(keyspaceName))

      When("the migrator migrates with a cut off date")
      migrator.migrate(cluster.connect(keyspaceName), Some(migrations.head.authoredAt))

      Then("the migrator reverses the reversible migration")
      val thrown = intercept[InvalidQueryException] {
        session.execute(QueryBuilder.select().from(keyspaceName, "views")).all()
      }
      thrown.getMessage should startWith("unconfigured")

      And("the migrator removes the reversed migration from the applied migrations table")
      val reversedMigration = migrations(1)
      val query = QueryBuilder.
        select().
        from(keyspaceName, "applied_migrations").
        where(QueryBuilder.eq("authored_at", reversedMigration.authoredAt)).
        and(QueryBuilder.eq("description", reversedMigration.description))
      session.execute(query).all().size() should equal(0)
    }

    scenario("irreversible previously applied migration") {
      Given("an initialized keyspace")
      migrator.initialize(session, keyspaceName, simpleStrategy)

      Given("a set of migrations applied in the past")
      migrator.migrate(cluster.connect(keyspaceName))

      When("the migrator migrates with a cut off date")
      val thrown = intercept[IrreversibleMigrationException] {
        migrator.migrate(cluster.connect(keyspaceName), Some(new Date(0)))
      }

      Then("the migrator reverses the reversible migrations")
      session.execute(QueryBuilder.select().from(keyspaceName, "applied_migrations")).all().size() should equal(1)

      And("the migrator throws an IrreversibleMigrationException")
      thrown should not be null
    }
  }
}
