package com.howtographql.scala.sangria

import akka.http.scaladsl.model.DateTime
import models._
import sangria.ast.StringValue
import sangria.schema._
import sangria.macros.derive._
import sangria.execution.deferred.{DeferredResolver, Fetcher, Relation, RelationIds}
import sangria.marshalling.sprayJson._
import spray.json.DefaultJsonProtocol._

object GraphSchema {
  implicit val GraphDateTime = ScalarType[DateTime](
    "DateTime",
    coerceOutput = (dt, _) => dt.toString,
    coerceInput = {
      case StringValue(dt, _, _, _, _) => DateTime.fromIsoDateTimeString(dt).toRight(DateTimeCoerceViolation)
      case _ => Left(DateTimeCoerceViolation)
    },
    coerceUserInput = {
      case s: String => DateTime.fromIsoDateTimeString(s).toRight(DateTimeCoerceViolation)
      case _ => Left(DateTimeCoerceViolation)
    }
  )
  implicit val AuthProviderEmailInputType: InputObjectType[AuthProviderEmail] = deriveInputObjectType[AuthProviderEmail](
    InputObjectTypeName("AUTH_PROVIDER_EMAIL")
  )
  lazy val AuthProviderSignUpDataInputType: InputObjectType[AuthProviderSignUpData] = deriveInputObjectType[AuthProviderSignUpData]()
  val IdentifiableType = InterfaceType(
    "Identifiable",
    fields[Unit, Identifiable](
      Field("id", IntType, resolve = _.value.id)
    )
  )

  implicit val authProviderEmailFormat = jsonFormat2(AuthProviderEmail)
  implicit val authProviderSignUpDataFormat = jsonFormat1(AuthProviderSignUpData)

  lazy val LinkType: ObjectType[Unit, Link] = deriveObjectType[Unit, Link](
    Interfaces(IdentifiableType),
    ReplaceField("createdAt", Field("createdAt", GraphDateTime, resolve = _.value.createdAt)),
    ReplaceField("postedBy",
      Field("postedBy", UserType, resolve = c => usersFetcher.defer(c.value.postedBy))
    ),
    AddFields(
      Field("votes", ListType(VoteType),
        resolve = c => votesFetcher.deferRelSeq(votesByLinkRel, c.value.id))
    )
  )
  lazy val UserType: ObjectType[Unit, User] = deriveObjectType[Unit, User](
    Interfaces(IdentifiableType),
    AddFields(
      Field("links", ListType(LinkType),
        resolve = c => linksFetcher.deferRelSeq(linksByUserRel, c.value.id)),
      Field("votes", ListType(VoteType),
        resolve = c => votesFetcher.deferRelSeq(votesByUserRel, c.value.id))
    )
  )
  lazy val VoteType: ObjectType[Unit, Vote] = deriveObjectType[Unit, Vote](
    Interfaces(IdentifiableType),
    ExcludeFields("userId", "linkId"),
    AddFields(
      Field("user", UserType,
        resolve = c => usersFetcher.defer(c.value.userId)),
      Field("link", LinkType,
        resolve = c => linksFetcher.defer(c.value.linkId))
    )
  )

  val linksByUserRel = Relation[Link, Int]("byUser", l => Seq(l.postedBy))
  val votesByUserRel = Relation[Vote, Int]("byUser", v => Seq(v.userId))
  val votesByLinkRel = Relation[Vote, Int]("byLink", v => Seq(v.linkId))

  val linksFetcher = Fetcher.rel(
    (ctx: MyContext, ids: Seq[Int]) => ctx.dao.getLinks(ids),
    (ctx: MyContext, ids: RelationIds[Link]) => ctx.dao.getLinksByUserIds(ids(linksByUserRel))
  )
  val usersFetcher = Fetcher(
    (ctx: MyContext, ids: Seq[Int]) => ctx.dao.getUsers(ids)
  )
  val votesFetcher = Fetcher.rel(
    (ctx: MyContext, ids: Seq[Int]) => ctx.dao.getVotes(ids),
    (ctx: MyContext, ids: RelationIds[Vote]) => ctx.dao.getVotesByRelationIds(ids)
  )
  val Resolver = DeferredResolver.fetchers(linksFetcher, usersFetcher, votesFetcher)

  val Id = Argument("id", IntType)
  val Ids = Argument("ids", ListInputType(IntType))
  val NameArg = Argument("name", StringType)
  val AuthProviderArg = Argument("authProvider", AuthProviderSignUpDataInputType)
  val UrlArg = Argument("url", StringType)
  val DescArg = Argument("description", StringType)
  val PostedByArg = Argument("postedById", IntType)
  val LinkIdArg = Argument("linkId", IntType)
  val UserIdArg = Argument("userId", IntType)
  val EmailArg = Argument("email", StringType)
  val PasswordArg = Argument("password", StringType)

  val Mutation = ObjectType(
    "Mutation",
    fields[MyContext, Unit](
      Field("createUser",
        UserType,
        arguments = NameArg :: AuthProviderArg :: Nil,
        resolve = c => c.ctx.dao.createUser(c.arg(NameArg), c.arg(AuthProviderArg))),
      Field("createLink",
        LinkType,
        arguments = UrlArg :: DescArg :: PostedByArg :: Nil,
        tags = Authorized :: Nil,
        resolve = c => c.ctx.dao.createLink(c.arg(UrlArg), c.arg(DescArg), c.arg(PostedByArg))),
      Field("createVote",
        VoteType,
        arguments = LinkIdArg :: UserIdArg :: Nil,
        resolve = c => c.ctx.dao.createVote(c.arg(LinkIdArg), c.arg(UserIdArg))),
      Field("login",
        UserType,
        arguments = EmailArg :: PasswordArg :: Nil,
        resolve = ctx => UpdateCtx(
          ctx.ctx.login(ctx.arg(EmailArg), ctx.arg(PasswordArg))){ user =>
            ctx.ctx.copy(currentUser = Some(user))
          }
      )
    )
  )

  val QueryType = ObjectType(
    "Query",
    fields[MyContext, Unit](
      Field("all_links", ListType(LinkType), resolve = c => c.ctx.dao.allLinks),
      Field("link",
        OptionType(LinkType),
        arguments = Id :: Nil,
        resolve = c => linksFetcher.deferOpt(c.arg(Id))
      ),
      Field("links",
        ListType(LinkType),
        arguments = Ids :: Nil,
        resolve = c => linksFetcher.deferSeq(c.arg(Ids))
      ),
      Field("users",
        ListType(UserType),
        arguments = List(Ids),
        resolve = c => usersFetcher.deferSeq(c.arg(Ids))
      ),
      Field("votes",
        ListType(VoteType),
        arguments = List(Ids),
        resolve = c => votesFetcher.deferSeq(c.arg(Ids))
      )
    )
  )

  val SchemaDefinition = Schema(QueryType, Some(Mutation))
}