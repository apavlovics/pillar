package com.evolutiongaming.pillar.cli

import com.datastax.driver.core.Session
import com.evolutiongaming.pillar.{Registry, ReplicationStrategy}

case class Command(action: MigratorAction, session: Session, keyspace: String, timeStampOption: Option[Long],
                   registry: Registry, replicationStrategy: ReplicationStrategy, appliedMigrationsTableName: String)
