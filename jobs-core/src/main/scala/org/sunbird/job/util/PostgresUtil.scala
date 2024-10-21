package org.sunbird.job.util

import org.slf4j.LoggerFactory

import java.sql._

class PostgresUtil(
                             host: String,
                             port: Int,
                             database: String,
                             username: String,
                             password: String
                           ) {
  private[this] val logger = LoggerFactory.getLogger("PostgresUtil")

  // Establish a connection lazily
//  private[this] lazy val connection: Connection = {
//    DriverManager.getConnection(s"jdbc:postgresql://$host:$port/$database", username, password)
//  }


  /**
   * Executes an update operation on the database using a prepared statement.
   *
   * @param query The SQL update query to execute.
   * @return The number of rows updated in the database.
   */

  def updateQuery(query: String): Int = {
    var preparedStatement: PreparedStatement = null
    val connectionUpdate=DriverManager.getConnection(s"jdbc:postgresql://$host:$port/$database", username, password)
//    connectionUpdate.setAutoCommit(true)
    try {
      preparedStatement = connectionUpdate.prepareStatement(query)
      logger.info(s"Executing update query: $query")
      val rowsUpdated = preparedStatement.executeUpdate()
      logger.info(s"Update successful: $rowsUpdated rows updated.")

      rowsUpdated
    } catch {
      case e: SQLException =>
        logger.error("SQL error during update operation", e)
        throw e
      case e: Exception =>
        logger.error("Unexpected error during update operation", e)
        throw e
    } finally {
      if (preparedStatement != null) {
        preparedStatement.close()
      }
//      connectionUpdate.close()
    }
  }

  // Find one record based on the query
  def findOne(query: String): Option[ResultSet] = {
    val connection = DriverManager.getConnection(s"jdbc:postgresql://$host:$port/$database", username, password)
    val statement: PreparedStatement = connection.prepareStatement(query)
    val resultSet: ResultSet = statement.executeQuery()

    if (resultSet.next()) {
      Some(resultSet)
    } else {
      None
    }
  }

  // Close the connection
//  def close(): Unit = {
//    connection.close()
//    logger.info("Postgres connection closed.")
//  }

  object PostgresUtil {
    def apply(host: String, port: Int, database: String, username: String, password: String): PostgresUtil = {
      new PostgresUtil(host, port, database, username, password)
    }
  }

  /**
   * Executes a SQL query and returns the ResultSet if successful.
   *
   * @param sqlQuery The SQL query to be executed.
   * @return An Option containing the ResultSet if successful, None if an error occurs.
   */
  def executeQuery(sqlQuery: String): Option[ResultSet] = {
    val connection = DriverManager.getConnection(s"jdbc:postgresql://$host:$port/$database", username, password)
    var resultSet: ResultSet = null
    var statement: Statement = null
    try {
      statement = connection.createStatement()
      logger.info(s"Executing query: $sqlQuery")
      resultSet = statement.executeQuery(sqlQuery)
      logger.info("Query executed successfully.")
      Some(resultSet)

    } catch {
      case e: SQLException =>
        logger.error(s"SQL error during query execution: ${e.getMessage}", e)
        None
    } finally {
      if (connection != null) {
        connection.close()
      }

    }
  }
}
