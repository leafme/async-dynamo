/*
 * Copyright 2012 2ndlanguage Limited.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package asyncdynamo.nonblocking

import com.amazonaws.services.dynamodb.AmazonDynamoDB
import com.amazonaws.services.dynamodb.model._
import akka.actor.{ActorSystem, ActorRef}
import akka.util.Timeout
import scala.concurrent.duration._
import asyncdynamo._
import concurrent.{Promise, Future, Await}
import util.{Failure, Success}

case class CreateTable[T](readThroughput: Long =5, writeThrougput: Long = 5)(implicit dyn:DynamoObject[T]) extends DbOperation[Unit]{
  def execute(db: AmazonDynamoDB, tablePrefix:String) {


    val keySchema = dyn.range match {
      case Some((range)) =>
        new KeySchema().withHashKeyElement(dyn.key)
        .withRangeKeyElement(range)
      case None =>
        new KeySchema().withHashKeyElement(dyn.key)
    }

    val provisionedThroughput = new ProvisionedThroughput()
      .withReadCapacityUnits(readThroughput)
      .withWriteCapacityUnits(writeThrougput)

    val request = new CreateTableRequest()
      .withTableName(dyn.table(tablePrefix))
      .withKeySchema(keySchema)
      .withProvisionedThroughput(provisionedThroughput)
    db.createTable(request)
  }

  override def blockingExecute(implicit dynamo: ActorRef, timeout: Timeout) {
    val deadline = Deadline.now + timeout.duration
    Await.ready(this.executeOn(dynamo)(timeout), timeout.duration)
    Await.ready(IsTableActive()(dyn).blockUntilTrue(deadline.timeLeft), deadline.timeLeft)
  }



}

case class TableExists[T](implicit dyn: DynamoObject[T]) extends DbOperation[Boolean]{
  private[asyncdynamo] def execute(db: AmazonDynamoDB, tablePrefix: String) = {
    val tableName = dyn.table(tablePrefix)
    db.listTables().getTableNames.contains(tableName)
  }
}

case class IsTableActive[T](implicit dyn: DynamoObject[T]) extends DbOperation[Boolean]{
  private[asyncdynamo] def execute(db: AmazonDynamoDB, tablePrefix: String) = {
    val tableName = dyn.table(tablePrefix)
    if (db.listTables().getTableNames.contains(tableName)){
      val status = db.describeTable(new DescribeTableRequest().withTableName(tableName)).getTable.getTableStatus.toUpperCase()
      status == "ACTIVE"
    }else false
  }


  def blockUntilTrue(timeout:FiniteDuration)(implicit dynamo: ActorRef): Future[Unit] = {

    import scala.concurrent.ExecutionContext.Implicits.global

    val start = System.currentTimeMillis()
    val promise = Promise[Unit]()

    def schedule(): Unit = {
      if (System.currentTimeMillis() - start < timeout.toMillis) {
        this.executeOn(dynamo)(timeout).onComplete {
          case Success(false) => schedule()
          case Success(true)  => promise.success()
          case Failure(e) => promise.failure(e)
        }
      }
    }
    schedule()
    promise.future
  }

}

case class DeleteTable[T] (implicit dyn:DynamoObject[T]) extends DbOperation[Unit]{
  private[asyncdynamo] def execute(db: AmazonDynamoDB, tablePrefix: String) {
    db.deleteTable(new DeleteTableRequest().withTableName(dyn.table(tablePrefix)))
  }
}
