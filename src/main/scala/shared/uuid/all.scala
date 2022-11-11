package shared.uuid

import shared.newtypes.NewExtractor
import zio.*

object all:
  type UUID = java.util.UUID
  def uuidFromString(str: String) = java.util.UUID.fromString(str)
  def generateUUID = ZIO.succeed(java.util.UUID.randomUUID)
  def generateUUIDSync = java.util.UUID.randomUUID
  def newId[A](implicit ex: NewExtractor.Aux[A, UUID]): Task[A] =
    for uuid <- generateUUID
    yield ex.to(uuid)
