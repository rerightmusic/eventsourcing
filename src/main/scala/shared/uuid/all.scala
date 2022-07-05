package shared.uuid

import shared.newtypes.NewExtractor
import zio.*

object all:
  type UUID = java.util.UUID
  def generateUUID = ZIO.effectTotal(java.util.UUID.randomUUID)
  def generateUUIDSync = java.util.UUID.randomUUID
