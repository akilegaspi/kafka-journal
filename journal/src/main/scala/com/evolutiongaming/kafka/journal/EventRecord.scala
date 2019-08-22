package com.evolutiongaming.kafka.journal

import java.time.Instant

import com.evolutiongaming.skafka.{Offset, Partition}

/**
 * @param origin identifier of event origin, for instance node IP address
 */
final case class EventRecord(
  event: Event,
  timestamp: Instant,
  partitionOffset: PartitionOffset,
  origin: Option[Origin] = None,
  metadata: Metadata,
  headers: Headers) {

  def seqNr: SeqNr = event.seqNr

  def offset: Offset = partitionOffset.offset

  def partition: Partition = partitionOffset.partition

  def pointer: Pointer = Pointer(partitionOffset, event.seqNr)
}

object EventRecord {

  def apply(record: ActionRecord[Action.Append], event: Event): EventRecord = {
    apply(record.action, event, record.partitionOffset)
  }

  def apply(action: Action.Append, event: Event, partitionOffset: PartitionOffset): EventRecord = {
    EventRecord(
      event = event,
      timestamp = action.timestamp,
      partitionOffset = partitionOffset,
      origin = action.origin,
      metadata = action.header.metadata,
      headers = action.headers)
  }
}