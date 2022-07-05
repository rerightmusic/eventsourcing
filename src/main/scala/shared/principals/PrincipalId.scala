package shared.principals

import shared.newtypes.NewtypeWrapped

object PrincipalId extends NewtypeWrapped[String]
type PrincipalId = PrincipalId.Type
