package shared.postgres.doobie

object all
    extends doobie.util.meta.MetaConstructors
    with doobie.util.meta.TimeMetaInstances
    with doobie.postgres.Instances:
  export AllInstances.given
  type WithTransactor[S <: String] = shared.postgres.doobie.WithTransactor[S]
  val WithTransactor = shared.postgres.doobie.WithTransactor
