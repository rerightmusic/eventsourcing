package eventsourcing.domain

object generics:
  class Remove[Xs <: Tuple, X, Out]

  object Remove:
    given rem[Xs <: Tuple, X]: Remove[X *: Xs, X, Xs] =
      new Remove[X *: Xs, X, Xs]

    given rem2[Xs <: Tuple, Ys <: Tuple, Y, X](using
      r: Remove[Xs, X, Ys]
    ): Remove[Y *: Xs, X, Y *: Ys] = new Remove[Y *: Xs, X, Y *: Ys]

  trait Add[Xs <: Tuple, X, Out]:
    def add(xs: Xs, x: X): Out

  object Add:
    given add[X]: Add[EmptyTuple, X, X *: EmptyTuple] =
      new Add[EmptyTuple, X, X *: EmptyTuple]:
        def add(xs: EmptyTuple, x: X) = x *: EmptyTuple

    given add2[Xs <: Tuple, Ys <: Tuple, Y, X](using
      a: Add[Xs, X, Ys]
    ): Add[Y *: Xs, X, Y *: Ys] = new Add[Y *: Xs, X, Y *: Ys]:
      def add(xs: Y *: Xs, x: X) = xs.head *: a.add(xs.tail, x)

  type MapCons[F[_], Xs <: Tuple] = MapCons_[F, Xs, EmptyTuple]

  type MapCons_[F[_], Xs <: Tuple, +Ys <: Tuple] <: Tuple =
    Xs match
      case EmptyTuple => Ys
      case x *: xs =>
        F[x] *: MapCons_[F, xs, Ys]

  type MapUnion[F[_], Xs <: Tuple] = MapUnion_[F, Xs, EmptyTuple]

  type MapUnion_[F[_], Xs <: Tuple, +Ys <: Tuple] =
    Xs match
      case EmptyTuple => Ys
      case x *: xs =>
        F[x] | MapUnion_[F, xs, Ys]

  trait Unwrap[T, F[_]]:
    type Wrapped

  object Unwrap:
    type Aux[T, F[_], Wrapped_] = Unwrap[T, F] {
      type Wrapped = Wrapped_
    }
    given x[T, F[_], Wrapped_](using
      eq: T =:= F[Wrapped_]
    ): Unwrap.Aux[T, F, Wrapped_] =
      new Unwrap[T, F]:
        type Wrapped = Wrapped_
