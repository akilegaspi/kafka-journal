package com.evolutiongaming.kafka.journal.util

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import cats.data.{NonEmptyList => Nel}
import com.evolutiongaming.kafka.journal.Bytes


object ByteBufferHelper {

  implicit class ByteBufferOps(val self: ByteBuffer) extends AnyVal {

    def readBytes: Bytes = {
      val length = self.getInt()
      if (length == 0) Bytes.Empty
      else {
        val bytes = new Bytes(length)
        self.get(bytes)
        bytes
      }
    }

    def writeBytes(bytes: Bytes): Unit = {
      self.putInt(bytes.length)
      if (bytes.nonEmpty) {
        val _ = self.put(bytes)
      }
    }

    def readString: String = {
      new String(readBytes, UTF_8)
    }

    def writeString(value: String): Unit = {
      val bytes = value.getBytes(UTF_8)
      writeBytes(bytes)
    }

    def readNel[T](f: => T): Nel[T] = {
      val length = self.getInt()
      val list = List.fill(length) {
        val length = self.getInt
        val position = self.position()
        val value = f
        self.position(position + length)
        value
      }
      Nel.fromListUnsafe(list)
    }

    def writeNel(bytes: Nel[Bytes]): Unit = {
      self.putInt(bytes.length)
      bytes.toList.foreach { bytes => self.writeBytes(bytes) } // TODO
    }
  }
}
