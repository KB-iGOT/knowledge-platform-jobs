package org.sunbird.job.util

import com.datastax.driver.core._
import com.datastax.driver.core.exceptions.DriverException
import org.slf4j.LoggerFactory

import java.util

class CassandraUtil(host: String,
                    port: Int,
                    readTimeoutMs: Int = 5000,
                    connectTimeoutMs: Int = 3000,
                    maxRetries: Int = 3) {

  private[this] val logger = LoggerFactory.getLogger("CassandraUtil")

  val cluster: Cluster = {
    Cluster.builder()
      .addContactPoints(host)
      .withPort(port)
      .withoutJMXReporting()
      .withSocketOptions(
        new SocketOptions()
          .setReadTimeoutMillis(readTimeoutMs)
          .setConnectTimeoutMillis(connectTimeoutMs)
      )
      .build()
  }

  var session: Session = cluster.connect()

  // ── Public API ──────────────────────────────────────────────────────────────

  /** Execute a plain CQL string at the default (LOCAL_ONE) consistency level. */
  def findOne(query: String): Row = {
    executeWithRetry(new SimpleStatement(query)).one()
  }

  /**
   * Execute a pre-built Statement (allows caller to set a custom ConsistencyLevel,
   * e.g. LOCAL_QUORUM, before passing it in).
   */
  def findOneWithStatement(stmt: Statement): Row = {
    executeWithRetry(stmt).one()
  }

  /** Execute a plain CQL string and return all rows at default consistency. */
  def find(query: String): util.List[Row] = {
    executeWithRetry(new SimpleStatement(query)).all()
  }

  def upsert(query: String): Boolean = {
    val rs: ResultSet = session.execute(query)
    rs.wasApplied()
  }

  def getUDTType(keyspace: String, typeName: String): UserType =
    session.getCluster.getMetadata.getKeyspace(keyspace).getUserType(typeName)

  /**
   * Execute an update Statement.
   * Callers may set ConsistencyLevel on the Statement before calling this,
   * e.g. stmt.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM).
   */
  def update(query: Statement): Boolean = {
    executeWithRetry(query).wasApplied()
  }

  def executePreparedStatement(query: String, params: Object*): util.List[Row] = {
    val rs: ResultSet = session.execute(session.prepare(query).bind(params: _*))
    rs.all()
  }

  def close(): Unit = {
    this.session.close()
  }

  // ── Internal ────────────────────────────────────────────────────────────────

  /**
   * Execute a Statement with bounded retries.
   *
   * On [[DriverException]], the call is retried up to `maxRetries` times
   * (with a reconnect between attempts).  After exhausting all retries,
   * the last exception is re-thrown so that the caller (e.g. a Flink
   * processElement) receives it and lets Flink trigger a task restart.
   */
  private def executeWithRetry(stmt: Statement): ResultSet = {
    var attempt = 0
    var lastEx: DriverException = null
    while (attempt < maxRetries) {
      try {
        return session.execute(stmt)
      } catch {
        case ex: DriverException =>
          attempt += 1
          lastEx = ex
          logger.warn(s"Cassandra execute attempt $attempt/$maxRetries failed: ${ex.getMessage}")
          if (attempt < maxRetries) reconnect()
      }
    }
    // All retries exhausted — propagate to Flink so it can restart the job
    throw lastEx
  }

  def reconnect(): Unit = {
    this.session.close()
    val newCluster: Cluster = Cluster.builder()
      .addContactPoint(host)
      .withPort(port)
      .withSocketOptions(
        new SocketOptions()
          .setReadTimeoutMillis(readTimeoutMs)
          .setConnectTimeoutMillis(connectTimeoutMs)
      )
      .build()
    this.session = newCluster.connect()
  }
}
