package eventsourcing.test.infra

import eventsourcing.test.domain as D
import shared.data.all.*
import eventsourcing.all.*
import zio.durationInt

object PostgresAccountDetailsService:
  def live = PostgresAggregateViewService.schemaless[
    "Accounts",
    AccountMeta,
    D.AccountMeta,
    AccountDetailsData,
    D.AccountDetails
  ](
    x => x.auto.to[D.AccountMeta],
    x => x.auto.to[AccountMeta],
    x => Right(x.data.auto.to[D.AccountDetails]),
    x => Right(x.auto.to[AccountDetailsData]),
    s => s.defaultReadState,
    i => Some(i) *: EmptyTuple,
    "test",
    1.minutes
  )
