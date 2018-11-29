package com.howtographql.scala.sangria

import spray.json.{JsObject, JsString, JsValue}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import sangria.parser.QueryParser
import sangria.ast.Document
import sangria.marshalling.sprayJson._
import sangria.execution.{ExceptionHandler => EHandler, _}
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext
import models._

object GraphServer {
  private val dao = DBSchema.createDatabase

  def endpoint(requestJSON: JsValue)(implicit ec: ExecutionContext): Route = {
    val JsObject(fields) = requestJSON
    val JsString(query) = fields("query")

    QueryParser.parse(query) match {
      case Success(queryAst) =>
        val operation = fields.get("operationName") collect {
          case JsString(op) => op
        }
        val variables = fields.get("variables") match {
          case Some(obj: JsObject) => obj
          case _ => JsObject.empty
        }
        complete(executeGraphQuery(queryAst, operation, variables))
      case Failure(error) =>
        complete(BadRequest, JsObject("error" -> JsString(error.getMessage)))
    }
  }

  val ErrorHandler = EHandler {
    case (_, AuthenticationException(message)) => HandledException(message)
    case (_, AuthorizationException(message)) => HandledException(message)
  }

  private def executeGraphQuery(query: Document, operation: Option[String], vars: JsObject)
                               (implicit ec: ExecutionContext) = {
    Executor.execute(
      GraphSchema.SchemaDefinition,
      query,
      MyContext(dao),
      variables = vars,
      operationName = operation,
      deferredResolver = GraphSchema.Resolver,
      exceptionHandler = ErrorHandler,
      middleware = AuthMiddleware :: Nil
    )
      .map(OK -> _)
      .recover {
        case error: QueryAnalysisError => BadRequest -> error.resolveError
        case error: ErrorWithResolver => InternalServerError -> error.resolveError
      }
  }
}
