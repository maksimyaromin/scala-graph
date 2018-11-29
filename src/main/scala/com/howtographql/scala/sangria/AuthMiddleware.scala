package com.howtographql.scala.sangria

import models.Authorized
import sangria.execution.{BeforeFieldResult, Middleware, MiddlewareBeforeField, MiddlewareQueryContext}
import sangria.schema.Context

object AuthMiddleware extends Middleware[MyContext] with MiddlewareBeforeField[MyContext] {
  override type QueryVal = Unit
  override type FieldVal = Unit

  override def beforeQuery(context: MiddlewareQueryContext[MyContext, _, _]): Unit = ()
  override def afterQuery(queryVal: Unit, context: MiddlewareQueryContext[MyContext, _, _]): Unit = ()
  override def beforeField(queryVal: Unit, mctx: MiddlewareQueryContext[MyContext, _, _], ctx: Context[MyContext, _]): BeforeFieldResult[MyContext, Unit] = {
    val requiredAuth = ctx.field.tags contains Authorized
    if(requiredAuth) ctx.ctx.ensureAuthenticated()
    continue
  }
}