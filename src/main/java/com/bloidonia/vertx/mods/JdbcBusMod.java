/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bloidonia.vertx.mods ;

import com.mchange.v2.c3p0.* ;

import java.sql.Connection ;
import java.sql.PreparedStatement ;
import java.sql.ResultSet ;
import java.sql.SQLException ;
import java.sql.Statement ;

import java.util.ArrayList ;
import java.util.Iterator ;
import java.util.List ;
import java.util.Map ;

import org.apache.commons.dbutils.QueryRunner ;
import org.apache.commons.dbutils.ResultSetHandler ;
import org.apache.commons.dbutils.handlers.MapListHandler ;
import org.apache.commons.dbutils.handlers.LimitedMapListHandler ;

import org.vertx.java.busmods.BusModBase ;
import org.vertx.java.core.Handler ;
import org.vertx.java.core.eventbus.Message ;
import org.vertx.java.core.json.JsonArray ;
import org.vertx.java.core.json.JsonObject ;

public class JdbcBusMod extends BusModBase implements Handler<Message<JsonObject>> {
  private String address ;
  
  private String driver ;
  private String url ;
  private String username ;
  private String password ;
  private int    minpool ;
  private int    maxpool ;
  private int    acquire ;

  private ComboPooledDataSource pool = null ;

  public void start() {
    super.start() ;
    address   = getOptionalStringConfig( "address", "vertx.jdbcpersistor" ) ;

    driver    = getOptionalStringConfig( "driver",   "org.hsqldb.jdbcDriver" ) ;
    url       = getOptionalStringConfig( "url",      "jdbc:hsqldb:mem:test"  ) ;
    username  = getOptionalStringConfig( "username", ""                      ) ;
    password  = getOptionalStringConfig( "password", ""                      ) ;

    minpool   = getOptionalIntConfig( "minpool",    5  ) ;
    maxpool   = getOptionalIntConfig( "maxpool",    20 ) ;
    acquire   = getOptionalIntConfig( "acquire",    5 ) ;

    try {
      pool = new ComboPooledDataSource() ;
      pool.setDriverClass( driver ) ;
      pool.setJdbcUrl( url ) ;
      pool.setUser( username ) ;
      pool.setPassword( password ) ;
      pool.setMinPoolSize( minpool ) ;
      pool.setMaxPoolSize( maxpool ) ;
      pool.setAcquireIncrement( acquire ) ;
    }
    catch( Exception e ) {
      logger.error( "Failed to create jdbc pool", e ) ;
    }
    eb.registerHandler( address, this ) ;
  }

  public void stop() {
    eb.unregisterHandler( address, this ) ;
    if( pool != null ) {
      pool.close() ;
    }
  }

  public void handle( final Message<JsonObject> message ) {
    String action = message.body.getString( "action" ) ;
    if( action == null ) {
      sendError( message, "action must be specified" ) ;
    }
    switch( action ) {
      case "select" :
        doSelect( message ) ;
        break ;
      case "execute" :
        doExecute( message ) ;
        break ;
      case "update" :
        doUpdate( message, false ) ;
        break ;
      case "insert" :
        doUpdate( message, true ) ;
        break ;
      case "transaction" :
        doTransaction( message ) ;
        break ;
      default:
        sendError( message, "Invalid action : " + action ) ;
    }
  }

  /****************************************************************************
   **
   **  Select handling
   **
   ****************************************************************************/

  private void doSelect( final Message<JsonObject> message ) {
    Connection connection = null ;
    try {
      connection = pool.getConnection() ;
      doSelect( message, connection ) ;
    }
    catch( SQLException ex ) {
      sendError( message, "Caught error with SELECT", ex ) ;
    }
  }

  private void doSelect( final Message<JsonObject> message, Connection connection ) throws SQLException {
    doSelect( message, connection, null ) ;
  }

  private void doSelect( final Message<JsonObject> message, Connection connection, TransactionalHandler transaction ) throws SQLException {
    new BatchHandler( connection, message, transaction ) {
      public JsonObject process() throws SQLException {
        JsonObject reply = new JsonObject() ;
        ArrayList<Map<String,Object>> result = new ArrayList<Map<String,Object>>() ;
        // processing
        while( ( resultSet != null || valueIterator.hasNext() ) &&
               ( batchSize == -1 || result.size() < batchSize ) ) {
          LimitedMapListHandler handler = new LimitedMapListHandler( batchSize == -1 ? -1 : batchSize - result.size() ) ;
          if( resultSet == null ) {
            List<Object> params = valueIterator.next() ;
            new QueryRunner().fillStatement( statement, params.toArray( new Object[] {} ) ) ;
            resultSet = statement.executeQuery() ;
          }
          store( result, handler ) ;
        }
        reply.putArray( "result", JsonUtils.listOfMapsToJsonArray( result ) ) ;
        return reply ;
      }
    }.handle( message ) ;
  }

  /****************************************************************************
   **
   **  Execute handling
   **
   ****************************************************************************/

  private void doExecute( final Message<JsonObject> message ) {
    Connection connection = null ;
    try {
      connection = pool.getConnection() ;
      doExecute( message, connection ) ;
    }
    catch( SQLException ex ) {
      sendError( message, "Caught error with EXECUTE", ex ) ;
    }
    finally {
      SilentCloser.close( connection ) ;
    }
  }

  private void doExecute( final Message<JsonObject> message, Connection conn ) throws SQLException {
    doExecute( message, conn, null ) ;
  }

  private void doExecute( final Message<JsonObject> message, Connection conn, TransactionalHandler transaction ) throws SQLException {
    Connection connection = null ;
    Statement statement = null ;
    try {
      connection = pool.getConnection() ;
      statement = connection.createStatement() ;
      statement.execute( message.body.getString( "stmt" ) ) ;
      if( transaction == null ) {
        sendOK( message ) ;
      }
      else {
        JsonObject reply = new JsonObject() ;
        reply.putString( "status", "ok" ) ;
        message.reply( reply, transaction ) ;
      }
    }
    finally {
      SilentCloser.close( statement ) ;
    }
  }

  /****************************************************************************
   **
   **  Update/Insert handling
   **
   ****************************************************************************/

  private void doUpdate( final Message<JsonObject> message, boolean insert ) {
    Connection connection = null ;
    try {
      connection = pool.getConnection() ;
      doUpdate( message, connection, insert ) ;
    }
    catch( SQLException ex ) {
      sendError( message, "Caught error with UPDATE.", ex ) ;
    }
    finally {
      SilentCloser.close( connection ) ;
    }
  }

  private void doUpdate( final Message<JsonObject> message, Connection connection, boolean insert ) throws SQLException {
    doUpdate( message, connection, insert, null ) ;
  }

  private void doUpdate( final Message<JsonObject> message, Connection connection, final boolean insert, TransactionalHandler transaction ) throws SQLException {
    new BatchHandler( connection, message, transaction ) {
      void initialiseStatement( Message<JsonObject> initial ) throws SQLException {
        if( insert ) {
          this.statement = connection.prepareStatement( initial.body.getString( "stmt" ), Statement.RETURN_GENERATED_KEYS ) ;
        }
        else {
          this.statement = connection.prepareStatement( initial.body.getString( "stmt" ) ) ;
        }
      }
      public JsonObject process() throws SQLException {
        JsonObject reply = new JsonObject() ;
        ArrayList<Map<String,Object>> result = new ArrayList<Map<String,Object>>() ;
        // processing
        int nRows = 0 ;
        if( insert ) {
          if( valueIterator == null ) {
            LimitedMapListHandler handler = new LimitedMapListHandler( batchSize == -1 ? -1 : batchSize ) ;
            if( resultSet == null ) {
              nRows += statement.executeUpdate() ;
              resultSet = statement.getGeneratedKeys() ;
            }
            store( result, handler ) ;
          }
          else {
            while( valueIterator.hasNext() && ( batchSize == -1 || result.size() < batchSize ) ) {
              LimitedMapListHandler handler = new LimitedMapListHandler( batchSize == -1 ? -1 : batchSize - result.size() ) ;
              if( resultSet == null ) {
                List<Object> params = valueIterator.next() ;
                new QueryRunner().fillStatement( statement, params.toArray( new Object[] {} ) ) ;
                nRows += statement.executeUpdate() ;
                resultSet = statement.getGeneratedKeys() ;
              }
              store( result, handler ) ;
            }
          }
          reply.putArray( "result", JsonUtils.listOfMapsToJsonArray( result ) ) ;
        }
        else {
          if( valueIterator == null ) {
            nRows += statement.executeUpdate();
          }
        }
        reply.putNumber( "updated", nRows ) ;
        return reply ;
      }
    }.handle( message ) ;
  }

  /****************************************************************************
   **
   **  Transaction handling
   **
   ****************************************************************************/

  private void doTransaction( final Message<JsonObject> message ) {
    Connection connection = null ;
    try {
      connection = pool.getConnection() ;
      connection.setAutoCommit( false ) ;
      doTransaction( message, connection ) ;
    }
    catch( SQLException ex ) {
      sendError( message, "Caught exception in TRANSACTION.  Rolling back...", ex ) ;
      try { connection.rollback() ; }
      catch( SQLException exx ) {
        logger.error( "Failed to rollback", exx ) ;
      }
      SilentCloser.close( connection ) ;
    }
  }

  private void doTransaction( final Message<JsonObject> message, final Connection connection ) {
    JsonObject reply = new JsonObject() ;
    reply.putString( "status", "ok" ) ;

    int timeout = message.body.getNumber( "timeout", 10000 ).intValue() ;
    final long timerId = vertx.setTimer( timeout, new TransactionTimeoutHandler( connection ) ) ;

    message.reply( reply, new TransactionalHandler( connection, timerId, timeout ) ) ;
  }
  
  private class TransactionTimeoutHandler implements Handler<Long> {
    Connection connection ;

    TransactionTimeoutHandler( Connection connection ) {
      this.connection = connection ;
    }

    public void handle( Long timerID ) {
      logger.warn( "Closing and rolling back transaction on timeout") ;
      try {
        connection.rollback() ;
        SilentCloser.close( connection ) ;
      } catch ( SQLException ex ) {
        logger.error( "Failed to rollback on transaction timeout", ex ) ;
      }
    }
  }

  private class TransactionalHandler implements Handler<Message<JsonObject>> {
    Connection connection ;
    long timerId ;
    int timeout ;

    TransactionalHandler( Connection connection, long timerId, int timeout ) {
      this.connection = connection ;
      this.timerId = timerId ;
      this.timeout = timeout ;
    }

    public void handle( final Message<JsonObject> message ) {
      vertx.cancelTimer( timerId ) ;
      String action = message.body.getString( "action" ) ;
      if( action == null ) {
        sendError( message, "action must be specified" ) ;
      }
      try {
        switch( action ) {
          case "select" :
            doSelect( message, connection, this ) ;
            break ;
          case "execute" :
            doExecute( message, connection, this ) ;
            break ;
          case "update" :
            doUpdate( message, connection, false, this ) ;
            break ;
          case "insert" :
            doUpdate( message, connection, true, this ) ;
            break ;
          case "commit" :
            doCommit( message ) ;
            return ;
          case "rollback" :
            doRollback( message ) ;
            return ;
          default:
            sendError( message, "Invalid action : " + action + ". Rolling back." ) ;
            doRollback( null ) ;
            return ;
        }
        timerId = vertx.setTimer( timeout, new TransactionTimeoutHandler( connection ) ) ;
      }
      catch( SQLException ex ) {
        sendError( message, "Error performing " + action + ".  Rolling back.", ex ) ;
        doRollback( null ) ;
      }
    }

    private void doCommit( final Message<JsonObject> message ) {
      try {
        connection.commit() ;
        if( message != null ) sendOK( message ) ;
      }
      catch( SQLException ex ) { logger.error( "Failed to commit", ex ) ; }
      finally { SilentCloser.close( connection ) ; }
    }

    private void doRollback( final Message<JsonObject> message ) {
      try {
        connection.rollback() ;
        if( message != null ) sendOK( message ) ;
      }
      catch( SQLException ex ) { logger.error( "Failed to rollback", ex ) ; }
      finally { SilentCloser.close( connection ) ; }
    }
  }

  /****************************************************************************
   **
   **  Batch handling
   **
   ****************************************************************************/

  private class BatchTimeoutHandler implements Handler<Long> {
    Connection connection ;
    Statement statement ;
    ResultSet rslt ;

    BatchTimeoutHandler( Statement statement, ResultSet rslt ) {
      this( null, statement, rslt ) ;
    }

    BatchTimeoutHandler( Connection conn, Statement statement, ResultSet rslt ) {
      this.connection = connection ;
      this.statement = statement ;
      this.rslt = rslt ;
    }

    public void handle( Long timerId ) {
      logger.warn( "Closing batch result set and statement on timeout" ) ;
      SilentCloser.close( connection, statement, rslt ) ;
    }
  }

  private abstract class BatchHandler implements Handler<Message<JsonObject>> {
    Connection connection ;
    String statementString ;
    List<List<Object>> values ;
    Iterator<List<Object>> valueIterator ;
    TransactionalHandler transaction ;
    long timerId ;
    int timeout ;
    int batchSize ;
    PreparedStatement statement ;
    ResultSet resultSet ;

    BatchHandler( Connection connection, Message<JsonObject> initial, TransactionalHandler transaction ) throws SQLException {
      this.connection = connection ;
      this.transaction = transaction ;
      this.timerId = -1 ;
      this.batchSize = initial.body.getNumber( "batchsize", -1 ).intValue() ;
      if( this.batchSize <= 0 ) this.batchSize = -1 ;
      this.timeout = initial.body.getNumber( "batchtimeout", 10000 ).intValue() ;

      // create a List<List<Object>> from the values
      this.values = JsonUtils.arrayNormaliser( initial.body.getArray( "values" ) ) ;
      if( this.values != null ) {
        this.valueIterator = values.iterator() ;
      }
      else {
        this.valueIterator = null ;
      }
      initialiseStatement( initial ) ;
    }

    void initialiseStatement( Message<JsonObject> initial ) throws SQLException {
      this.statement = connection.prepareStatement( initial.body.getString( "stmt" ) ) ;
    }

    public abstract JsonObject process() throws SQLException ;

    void store( ArrayList<Map<String,Object>> result, LimitedMapListHandler handler ) throws SQLException {
      result.addAll( handler.handle( resultSet ) ) ;
      if( handler.isExpired() ) {
        SilentCloser.close( resultSet ) ;
        resultSet = null ;
      }
    }

    public void handle( final Message<JsonObject> message ) {
      if( timerId != -1 ) {
        vertx.cancelTimer( timerId ) ;
      }
      JsonObject reply ;
      try {
        reply = process() ;
        if( resultSet != null || valueIterator.hasNext() ) {
          reply.putString( "status", "more-exist" ) ;
          message.reply( reply, this ) ;
          if( transaction == null ) {
            timerId = vertx.setTimer( timeout, new BatchTimeoutHandler( connection, statement, resultSet ) ) ;
          }
          else {
            timerId = vertx.setTimer( timeout, new BatchTimeoutHandler( statement, resultSet ) ) ;
          }
        }
        else if( transaction == null ) {
          SilentCloser.close( connection, statement ) ;
          sendOK( message, reply ) ;
        }
        else {
          SilentCloser.close( statement ) ;
          reply.putString( "status", "ok" ) ;
          message.reply( reply, transaction ) ;
        }
      }
      catch( SQLException ex ) {
        if( transaction == null ) {
          SilentCloser.close( connection, statement, resultSet ) ;
          sendError( message, "Error performing batch select", ex ) ;
        }
        else {
          try { connection.rollback() ; } catch( SQLException exx ) {}
          SilentCloser.close( connection, statement, resultSet ) ;
          reply = new JsonObject() ;
          reply.putString( "status", "error" ) ;
          reply.putString( "message", "Error performing transactional batch select" ) ;
          logger.error( "Error performing transactional batch select", ex ) ;
          message.reply( reply, transaction ) ;
        }
      }
    }
  }
}